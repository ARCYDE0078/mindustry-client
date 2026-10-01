package sonkaextras.dataio;

import arc.struct.*;
import arc.util.serialization.*;

import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * Чтение/запись файлов формата {@code settings.bin} (arc.Settings) БЕЗ загрузки их в живые
 * {@code Core.settings}: импорт должен посмотреть ключи чужого архива, не трогая текущие настройки,
 * а экспорт - записать отфильтрованное подмножество ключей так, чтобы ванильный импорт данных
 * понял файл. Формат повторяет {@code Settings.loadValues/saveValues} один в один:
 * <pre>
 * int count; count × { UTF key; byte type; value }, type: 0 bool, 1 int, 2 long, 3 float, 4 UTF string, 5 int len + bytes
 * </pre>
 * опционально обёрнуто в zlib (arc определяет по заголовку 0x78 xx - здесь так же). Пишем всегда
 * несжато - так делает и ваниль по умолчанию.
 * <p>
 * Второй формат здесь же - типизированный JSON для {@code campaign-progress.json}/{@code progress.json}
 * профилей: {@code [{"k": key, "t": "bool|int|long|float|string|bytes", "v": ...}]}. Типы
 * хранятся явно (Jval числа - double, без тегов int/long/float были бы неразличимы), long и bytes -
 * строками (точность / base64), чтобы значение вернулось в settings ровно того же Java-типа.
 */
public final class SettingsBin{
    static final byte typeBool = 0, typeInt = 1, typeLong = 2, typeFloat = 3, typeString = 4, typeBinary = 5;

    private SettingsBin(){
    }

    /** Читает весь файл settings.bin из потока (сжатый или нет). Поток закрывается. */
    public static ObjectMap<String, Object> read(InputStream raw) throws IOException{
        byte[] all;
        try(raw){
            all = arc.util.io.Streams.copyBytes(raw); //не readAllBytes: API 33, на Android 8-12 NoSuchMethodError
        }
        boolean compressed = all.length >= 2 && all[0] == (byte)0x78 && (all[1] == (byte)0x01 || all[1] == (byte)0x5E || all[1] == (byte)0x9c || all[1] == (byte)0xda);
        InputStream base = new ByteArrayInputStream(all);
        ObjectMap<String, Object> values = new ObjectMap<>();
        try(DataInputStream stream = new DataInputStream(compressed ? new InflaterInputStream(base) : base)){
            int amount = stream.readInt();
            if(amount <= 0) throw new IOException("settings.bin has 0 values");
            for(int i = 0; i < amount; i++){
                String key = stream.readUTF();
                byte type = stream.readByte();
                switch(type){
                    case typeBool -> values.put(key, stream.readBoolean());
                    case typeInt -> values.put(key, stream.readInt());
                    case typeLong -> values.put(key, stream.readLong());
                    case typeFloat -> values.put(key, stream.readFloat());
                    case typeString -> values.put(key, stream.readUTF());
                    case typeBinary -> {
                        int length = stream.readInt();
                        byte[] bytes = new byte[length];
                        stream.readFully(bytes);
                        values.put(key, bytes);
                    }
                    default -> throw new IOException("Unknown settings key type: " + type);
                }
            }
        }
        return values;
    }

    /** Пишет карту в формате settings.bin (несжато). Поток НЕ закрывается (может быть zip-entry). */
    public static void write(OutputStream out, ObjectMap<String, Object> values) throws IOException{
        //значения неизвестного типа пропускаем, как и arc (он их молча не пишет) - поэтому считаем заранее
        int count = 0;
        for(var e : values){
            if(supported(e.value)) count++;
        }
        DataOutputStream stream = new DataOutputStream(out);
        stream.writeInt(count);
        for(var e : values){
            Object value = e.value;
            if(!supported(value)) continue;
            stream.writeUTF(e.key);
            if(value instanceof Boolean b){
                stream.writeByte(typeBool);
                stream.writeBoolean(b);
            }else if(value instanceof Integer i){
                stream.writeByte(typeInt);
                stream.writeInt(i);
            }else if(value instanceof Long l){
                stream.writeByte(typeLong);
                stream.writeLong(l);
            }else if(value instanceof Float f){
                stream.writeByte(typeFloat);
                stream.writeFloat(f);
            }else if(value instanceof String s){
                stream.writeByte(typeString);
                stream.writeUTF(s);
            }else if(value instanceof byte[] bytes){
                stream.writeByte(typeBinary);
                stream.writeInt(bytes.length);
                stream.write(bytes);
            }
        }
        stream.flush();
    }

    static boolean supported(Object v){
        return v instanceof Boolean || v instanceof Integer || v instanceof Long || v instanceof Float || v instanceof String || v instanceof byte[];
    }

    //---- типизированный JSON ----

    public static String toJson(ObjectMap<String, Object> values){
        Jval arr = Jval.newArray();
        //стабильный порядок ключей - файл профиля не «дребезжит» между снапшотами (удобно диффать)
        Seq<String> keys = values.keys().toSeq();
        keys.sort();
        for(String key : keys){
            Object value = values.get(key);
            if(!supported(value)) continue;
            Jval e = Jval.newObject();
            e.put("k", key);
            if(value instanceof Boolean b){
                e.put("t", "bool");
                e.put("v", b);
            }else if(value instanceof Integer i){
                e.put("t", "int");
                e.put("v", i);
            }else if(value instanceof Long l){
                e.put("t", "long");
                e.put("v", Long.toString(l));
            }else if(value instanceof Float f){
                e.put("t", "float");
                e.put("v", f);
            }else if(value instanceof String s){
                e.put("t", "string");
                e.put("v", s);
            }else if(value instanceof byte[] bytes){
                e.put("t", "bytes");
                e.put("v", Base64.getEncoder().encodeToString(bytes));
            }
            arr.add(e);
        }
        return arr.toString(Jval.Jformat.formatted);
    }

    public static ObjectMap<String, Object> fromJson(String text) throws IOException{
        ObjectMap<String, Object> out = new ObjectMap<>();
        Jval root;
        try{
            root = Jval.read(text);
        }catch(Throwable t){
            throw new IOException("broken progress json: " + t.getMessage());
        }
        if(!root.isArray()) throw new IOException("progress json: array expected");
        for(Jval e : root.asArray()){
            if(!e.isObject()) continue;
            String key = e.getString("k", null);
            String type = e.getString("t", null);
            Jval v = e.get("v");
            if(key == null || type == null || v == null) continue;
            try{
                switch(type){
                    case "bool" -> out.put(key, v.asBool());
                    case "int" -> out.put(key, v.asInt());
                    case "long" -> out.put(key, v.isString() ? Long.parseLong(v.asString()) : v.asLong());
                    case "float" -> out.put(key, v.asFloat());
                    case "string" -> out.put(key, v.asString());
                    case "bytes" -> out.put(key, Base64.getDecoder().decode(v.asString()));
                    default -> throw new IOException("unknown type " + type);
                }
            }catch(IOException ioe){
                throw ioe;
            }catch(Throwable t){
                throw new IOException("progress json: bad value for \"" + key + "\": " + t.getMessage());
            }
        }
        return out;
    }
}
