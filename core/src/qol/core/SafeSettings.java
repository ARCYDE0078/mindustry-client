package qol.core;

import arc.Core;
import arc.util.Log;

/**
 * Wraps {@link Core#settings}'s typed getters for any key this mod inherited from one of the standalone
 * mods it merges. {@link Core#settings} is a single untyped key-value store shared by every mod in the
 * install - Bridge To Core, Control Helper and this suite all only ever wrote the type they expected,
 * but a value can still be left over from a completely different write (an old version of the source
 * mod that stored it differently, a hand-edited settings file, anything). {@code arc.Settings}'s own
 * getBool/getInt/getFloat/getString all do a raw cast to the requested type and throw
 * {@link ClassCastException} outright on a mismatch instead of converting - which crashed the whole
 * client on first load here (spy_threshold_* was stored as a string by the original JS mod, read back
 * with getInt). These variants catch that and fall back to the default instead.
 */
public final class SafeSettings{
    private SafeSettings(){
    }

    public static boolean getBool(String key, boolean def){
        try{
            return Core.settings.getBool(key, def);
        }catch(ClassCastException e){
            warn(key, e);
            return def;
        }
    }

    public static int getInt(String key, int def){
        try{
            return Core.settings.getInt(key, def);
        }catch(ClassCastException e){
            warn(key, e);
            return def;
        }
    }

    public static float getFloat(String key, float def){
        try{
            return Core.settings.getFloat(key, def);
        }catch(ClassCastException e){
            warn(key, e);
            return def;
        }
    }

    public static String getString(String key, String def){
        try{
            return Core.settings.getString(key, def);
        }catch(ClassCastException e){
            warn(key, e);
            return def;
        }
    }

    /**
     * arc {@code Settings.saveValues} пишет каждую строку через {@code DataOutputStream.writeUTF}, а у неё лимит 65535
     * байт: одна длинная строка = UTFDataFormatException при сохранении, settings.bin УДАЛЯЕТСЯ, и до перезапуска не
     * сохраняется ничего. Для JSON-хранилищ, которые могут вырасти (таблица схем, теги карт, бинды), кладите значение
     * через этот метод: слишком длинное не записывается (в настройках остаётся прошлая версия), а в лог идёт warn.
     *
     * @return false, если значение не записано из-за размера
     */
    public static boolean putString(String key, String value){
        if(value != null && utf8Length(value) > MAX_UTF){
            Log.warn("[qol-suite] setting '" + key + "' is too large to store (" + utf8Length(value) + " bytes > " + MAX_UTF + "), keeping the previous value");
            return false;
        }
        Core.settings.put(key, value);
        return true;
    }

    /** Безопасно ниже лимита writeUTF в 65535 байт. */
    public static final int MAX_UTF = 65_000;

    static int utf8Length(String s){
        int n = 0;
        for(int i = 0, len = s.length(); i < len; i++){
            char c = s.charAt(i);
            if(c < 0x80) n++;
            else if(c < 0x800) n += 2;
            else if(Character.isHighSurrogate(c)){ n += 4; i++; }
            else n += 3;
        }
        return n;
    }

    static void warn(String key, ClassCastException e){
        Log.warn("[qol-suite] setting '" + key + "' had an unexpected stored type, using the default instead (" + e.getMessage() + ")");
    }
}
