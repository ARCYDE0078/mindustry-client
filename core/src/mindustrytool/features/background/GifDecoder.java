package mindustrytool.features.background;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Чистый Java-декодер GIF89a без java.awt/javax.imageio. Прошлая реализация шла через
 * javax.imageio, но на JRE где нет модуля java.desktop (см. custom-b25 краш:
 * NoClassDefFoundError: java/awt/Image на машине пользователя) этот путь принципиально
 * недоступен - никакая проверка доступности этого не обходит, т.к. декодировать реально нечем.
 * Здесь LZW-распаковка и композитинг кадров написаны вручную по спецификации GIF89a, так что
 * это работает одинаково на любой JRE.
 */
public final class GifDecoder{
    private GifDecoder(){}

    public static class Frame{
        public final int[] argb; //canvasWidth*canvasHeight, уже сведённый композит на момент этого кадра
        public final int delayMs;

        Frame(int[] argb, int delayMs){
            this.argb = argb;
            this.delayMs = delayMs;
        }
    }

    public static class Result{
        public final int width, height;
        public final List<Frame> frames;

        Result(int width, int height, List<Frame> frames){
            this.width = width;
            this.height = height;
            this.frames = frames;
        }
    }

    private static final int MAX_STACK_SIZE = 4096;

    public static Result decode(InputStream rawIn) throws IOException{
        Reader in = new Reader(arc.util.io.Streams.copyBytes(rawIn));

        String sig = new String(in.readBytes(6), StandardCharsets.US_ASCII);
        if(!sig.startsWith("GIF")) throw new IOException("not a GIF file");

        int width = in.readShortLE();
        int height = in.readShortLE();
        if(width <= 0 || height <= 0) throw new IOException("invalid GIF dimensions");
        int screenPacked = in.readByte();
        boolean globalColorTableFlag = (screenPacked & 0x80) != 0;
        int globalColorTableSize = 2 << (screenPacked & 0x07);
        in.readByte(); //background color index
        in.readByte(); //pixel aspect ratio

        int[] globalColorTable = globalColorTableFlag ? readColorTable(in, globalColorTableSize) : null;

        int[] canvas = new int[width * height];
        int[] canvasBackup = null;
        boolean havePrevFrame = false;
        int prevDisposal = 0, prevLeft = 0, prevTop = 0, prevW = 0, prevH = 0;

        int pendingDelayMs = 100, pendingDisposal = 0, pendingTransparentIndex = -1;
        boolean pendingTransparent = false;

        List<Frame> frames = new ArrayList<>();

        outer:
        while(true){
            int block = in.readByte();
            switch(block){
                case 0x21 -> {
                    int label = in.readByte();
                    if(label == 0xF9){
                        in.readByte(); //block size, always 4
                        int p = in.readByte();
                        pendingDisposal = (p >> 2) & 0x07;
                        pendingTransparent = (p & 0x01) != 0;
                        pendingDelayMs = in.readShortLE() * 10;
                        if(pendingDelayMs <= 10) pendingDelayMs = 100;
                        pendingTransparentIndex = in.readByte();
                        in.readByte(); //block terminator
                    }else{
                        skipSubBlocks(in);
                    }
                }
                case 0x2C -> {
                    int left = in.readShortLE();
                    int top = in.readShortLE();
                    int w = in.readShortLE();
                    int h = in.readShortLE();
                    int p = in.readByte();
                    boolean localColorTableFlag = (p & 0x80) != 0;
                    boolean interlace = (p & 0x40) != 0;
                    int localColorTableSize = 2 << (p & 0x07);

                    int[] colorTable = localColorTableFlag ? readColorTable(in, localColorTableSize) : globalColorTable;
                    if(colorTable == null) colorTable = new int[]{0xFF000000};

                    int minCodeSize = in.readByte();
                    byte[] lzwData = readSubBlocksConcat(in);
                    int[] indices = decodeLZW(lzwData, minCodeSize, Math.max(0, w) * Math.max(0, h));

                    if(havePrevFrame){
                        if(prevDisposal == 2){
                            clearRect(canvas, width, height, prevLeft, prevTop, prevW, prevH);
                        }else if(prevDisposal == 3 && canvasBackup != null){
                            System.arraycopy(canvasBackup, 0, canvas, 0, canvas.length);
                        }
                    }

                    int thisDisposal = pendingDisposal;
                    canvasBackup = thisDisposal == 3 ? canvas.clone() : null;

                    drawIndices(canvas, width, height, indices, left, top, w, h, interlace, colorTable,
                        pendingTransparent, pendingTransparentIndex);

                    frames.add(new Frame(canvas.clone(), pendingDelayMs));

                    prevDisposal = thisDisposal;
                    prevLeft = left; prevTop = top; prevW = w; prevH = h;
                    havePrevFrame = true;

                    pendingDelayMs = 100;
                    pendingDisposal = 0;
                    pendingTransparent = false;
                    pendingTransparentIndex = -1;
                }
                case 0x3B, -1 -> {
                    break outer;
                }
                default -> {
                    break outer;
                }
            }
        }

        return new Result(width, height, frames);
    }

    private static int[] readColorTable(Reader in, int size){
        int[] table = new int[size];
        for(int i = 0; i < size; i++){
            int r = in.readByte(), g = in.readByte(), b = in.readByte();
            table[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        return table;
    }

    private static void skipSubBlocks(Reader in){
        int size;
        while((size = in.readByte()) > 0){
            in.skip(size);
        }
    }

    private static byte[] readSubBlocksConcat(Reader in){
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int size;
        while((size = in.readByte()) > 0){
            out.write(in.readBytes(size), 0, size);
        }
        return out.toByteArray();
    }

    private static void clearRect(int[] canvas, int canvasW, int canvasH, int left, int top, int w, int h){
        for(int y = 0; y < h; y++){
            int cy = top + y;
            if(cy < 0 || cy >= canvasH) continue;
            for(int x = 0; x < w; x++){
                int cx = left + x;
                if(cx < 0 || cx >= canvasW) continue;
                canvas[cy * canvasW + cx] = 0;
            }
        }
    }

    private static void drawIndices(int[] canvas, int canvasW, int canvasH, int[] indices, int left, int top,
                                     int w, int h, boolean interlace, int[] colorTable,
                                     boolean transparent, int transparentIndex){
        if(w <= 0 || h <= 0) return;
        int[] rowOrder = interlace ? interlacedRowOrder(h) : null;
        for(int row = 0; row < h; row++){
            int srcRow = interlace ? rowOrder[row] : row;
            int cy = top + srcRow;
            boolean rowVisible = cy >= 0 && cy < canvasH;
            for(int x = 0; x < w; x++){
                int idx = indices[row * w + x];
                if(!rowVisible) continue;
                int cx = left + x;
                if(cx < 0 || cx >= canvasW) continue;
                if(transparent && idx == transparentIndex) continue;
                int color = idx >= 0 && idx < colorTable.length ? colorTable[idx] : 0xFF000000;
                canvas[cy * canvasW + cx] = color;
            }
        }
    }

    private static int[] interlacedRowOrder(int h){
        int[] order = new int[h];
        int i = 0;
        for(int y = 0; y < h; y += 8) order[i++] = y;
        for(int y = 4; y < h; y += 8) order[i++] = y;
        for(int y = 2; y < h; y += 4) order[i++] = y;
        for(int y = 1; y < h; y += 2) order[i++] = y;
        return order;
    }

    private static int[] decodeLZW(byte[] data, int minCodeSize, int pixelCount){
        int[] pixels = new int[pixelCount];
        if(minCodeSize < 2 || minCodeSize > 8 || data.length == 0 || pixelCount == 0) return pixels;

        int clear = 1 << minCodeSize;
        int eoi = clear + 1;

        short[] prefix = new short[MAX_STACK_SIZE];
        byte[] suffix = new byte[MAX_STACK_SIZE];
        byte[] pixelStack = new byte[MAX_STACK_SIZE + 1];

        for(int c = 0; c < clear; c++){
            prefix[c] = 0;
            suffix[c] = (byte)c;
        }

        int codeSize = minCodeSize + 1;
        int codeMask = (1 << codeSize) - 1;
        int available = eoi + 1;
        int oldCode = -1;
        int first = 0;
        int top = 0;
        int bits = 0;
        int datum = 0;
        int bytePos = 0;
        int pixelIndex = 0;

        while(pixelIndex < pixelCount){
            if(top == 0){
                if(bits < codeSize){
                    if(bytePos >= data.length) break;
                    datum += (data[bytePos++] & 0xFF) << bits;
                    bits += 8;
                    continue;
                }
                int code = datum & codeMask;
                datum >>= codeSize;
                bits -= codeSize;

                if(code == clear){
                    codeSize = minCodeSize + 1;
                    codeMask = (1 << codeSize) - 1;
                    available = eoi + 1;
                    oldCode = -1;
                    continue;
                }
                if(code == eoi || code > available) break;

                if(oldCode == -1){
                    pixelStack[top++] = suffix[code];
                    oldCode = code;
                    first = code;
                    continue;
                }

                int inCode = code;
                if(code == available && available < MAX_STACK_SIZE){
                    pixelStack[top++] = (byte)first;
                    code = oldCode;
                }
                while(code > clear && code < MAX_STACK_SIZE){
                    pixelStack[top++] = suffix[code];
                    code = prefix[code];
                }
                first = suffix[code] & 0xFF;
                pixelStack[top++] = (byte)first;

                if(available < MAX_STACK_SIZE){
                    prefix[available] = (short)oldCode;
                    suffix[available] = (byte)first;
                    available++;
                    if((available & codeMask) == 0 && available < MAX_STACK_SIZE){
                        codeSize++;
                        codeMask = (1 << codeSize) - 1;
                    }
                }
                oldCode = inCode;
            }
            top--;
            if(pixelIndex < pixelCount) pixels[pixelIndex++] = pixelStack[top] & 0xFF;
        }
        return pixels;
    }

    private static final class Reader{
        final byte[] data;
        int pos;

        Reader(byte[] data){
            this.data = data;
        }

        int readByte(){
            return pos < data.length ? (data[pos++] & 0xFF) : -1;
        }

        int readShortLE(){
            int lo = readByte(), hi = readByte();
            return lo == -1 || hi == -1 ? -1 : (lo | (hi << 8));
        }

        byte[] readBytes(int n){
            byte[] out = new byte[n];
            int avail = Math.max(0, Math.min(n, data.length - pos));
            if(avail > 0) System.arraycopy(data, pos, out, 0, avail);
            pos += n;
            return out;
        }

        void skip(int n){
            pos += n;
        }
    }
}
