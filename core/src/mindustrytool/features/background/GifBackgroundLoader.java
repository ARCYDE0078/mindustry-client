package mindustrytool.features.background;

import arc.files.Fi;
import arc.graphics.Color;
import arc.graphics.Pixmap;
import arc.graphics.Texture;
import arc.struct.Seq;

import java.io.BufferedInputStream;
import java.io.InputStream;

/**
 * Превращает декодированные {@link GifDecoder} кадры в готовые к отрисовке текстуры для
 * {@link BackgroundFeature.GifMenuRenderer}. MAX_FRAMES/MAX_DIMENSION - страховка от гигантского
 * gif на сотни МБ видеопамяти при разовой синхронной декодировке на GL-потоке (этот метод, как и
 * Texture(file) для статичного фона рядом, всегда вызывается синхронно из UI-колбэка).
 */
public final class GifBackgroundLoader{
    private static final int MAX_FRAMES = 240;
    private static final int MAX_DIMENSION = 1920;

    private GifBackgroundLoader(){}

    public static class Result{
        public final Texture[] frames;
        public final int[] delaysMs;
        public final boolean truncated;

        Result(Texture[] frames, int[] delaysMs, boolean truncated){
            this.frames = frames;
            this.delaysMs = delaysMs;
            this.truncated = truncated;
        }

        public int totalDurationMs(){
            int total = 0;
            for(int d : delaysMs) total += d;
            return Math.max(total, 1);
        }

        public void dispose(){
            for(Texture t : frames) t.dispose();
        }
    }

    public static Result load(Fi file) throws Exception{
        GifDecoder.Result gif;
        try(InputStream in = new BufferedInputStream(file.read())){
            gif = GifDecoder.decode(in);
        }

        if(gif.frames.isEmpty()) throw new IllegalStateException("gif has no frames");

        boolean truncated = gif.frames.size() > MAX_FRAMES;
        int numFrames = Math.min(gif.frames.size(), MAX_FRAMES);

        float scale = Math.min(1f, MAX_DIMENSION / (float)Math.max(gif.width, gif.height));
        int outW = Math.max(1, Math.round(gif.width * scale));
        int outH = Math.max(1, Math.round(gif.height * scale));

        Seq<Texture> frames = new Seq<>();
        int[] delaysMs = new int[numFrames];
        for(int i = 0; i < numFrames; i++){
            GifDecoder.Frame frame = gif.frames.get(i);
            delaysMs[i] = frame.delayMs;
            frames.add(toTexture(frame.argb, gif.width, gif.height, outW, outH));
        }

        return new Result(frames.toArray(Texture.class), delaysMs, truncated);
    }

    private static Texture toTexture(int[] argb, int srcW, int srcH, int outW, int outH){
        //arc Pixmaps are top-down like the GIF's own row order (Renderer.takeMapScreenshot copies
        //ScreenUtils output straight into pixmap.pixels with no extra flip) - no flip needed here;
        //the previous bottom-up assumption was wrong and rendered every gif upside down
        Pixmap pixmap = new Pixmap(outW, outH);
        for(int y = 0; y < outH; y++){
            int srcY = Math.min(srcH - 1, y * srcH / outH);
            for(int x = 0; x < outW; x++){
                int srcX = Math.min(srcW - 1, x * srcW / outW);
                int c = argb[srcY * srcW + srcX];
                int a = (c >>> 24) & 0xFF, r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
                pixmap.set(x, y, Color.rgba8888(r / 255f, g / 255f, b / 255f, a / 255f));
            }
        }
        Texture texture = new Texture(pixmap);
        pixmap.dispose();
        return texture;
    }
}
