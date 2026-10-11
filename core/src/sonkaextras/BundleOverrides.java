package sonkaextras;

import arc.*;
import arc.files.*;
import arc.struct.*;
import arc.util.*;
import arc.util.io.*;
import mindustry.ctype.*;
import mindustry.game.EventType.*;

import java.io.*;

import static mindustry.Vars.*;

/**
 * Пользовательские переопределения строк бандла (названия блоков/юнитов/предметов, подписи
 * настроек и вообще любой ключ {@code bundle.properties}). Хранятся в
 * {@code <data>/bundle-overrides.properties} - обычный properties-файл {@code ключ=значение}, поэтому
 * его можно править руками/слать другу; сам редактор - {@link BundleEditorDialog}.
 * <p>
 * Как это встроено в загрузку:
 * <ul>
 * <li>{@link #apply()} зовётся в конце {@code Mods.loadBundles()} - то есть ПОСЛЕ слияния бандлов
 * модов (иначе мод затёр бы наш текст) и ДО {@code content.createBaseContent()}. Конструктор
 * {@code UnlockableContent} читает {@code localizedName/description/details} из бандла один раз, так
 * что к моменту создания блоков подмена уже в бандле и отдельно контент трогать не надо.</li>
 * <li>Контент мода с явным {@code localizedName} из json конструктор-значение затирает уже после
 * создания, поэтому на {@link ClientLoadEvent} подмена повторяется ещё и прямо по полям
 * контента ({@link #refreshContent}); то же самое делает живая правка из редактора.</li>
 * <li>Всё пишется в ЛИСТОВОЙ бандл ({@code Core.bundle}), а не в родителя: листовой побеждает при
 * поиске, а сброс возвращает ровно то, что там было (в т.ч. "ключа не было вовсе").</li>
 * </ul>
 * Что НЕ обновляется на лету: надписи, которые UI уже прочитал при построении (заголовки
 * настроек - {@code SettingsTable.Setting} читает их в конструкторе, уже собранные диалоги);
 * они подхватят подмену после перезапуска/пересоздания окна. Аварийный выход, если подмена что-то
 * сломала: удалить файл или запустить с {@code -Dnobundleoverrides}.
 */
public final class BundleOverrides{
    private static final String fileName = "bundle-overrides.properties";
    /** Суффиксы ключей {@code <тип>.<имя>.<суффикс>}, которые UnlockableContent кэширует в полях. */
    private static final ObjectSet<String> contentSuffixes = ObjectSet.with("name", "description", "details", "credit");

    /** ключ -> пользовательский текст; OrderedMap, чтобы файл не перемешивался при каждом сохранении. */
    private static final OrderedMap<String, String> overrides = new OrderedMap<>();
    /** ключ -> что лежало в листовом бандле ДО нашей подмены (null = ключа там не было). */
    private static final ObjectMap<String, Saved> leafBefore = new ObjectMap<>();
    /** ключ -> значение поля контента ДО подмены; только если оно отличалось от бандлового (контент мода). */
    private static final ObjectMap<String, Saved> fieldBefore = new ObjectMap<>();
    /** ключ -> что мы сами записали в поле контента в последний раз (отличает своё от значения мода/json). */
    private static final ObjectMap<String, String> fieldWritten = new ObjectMap<>();
    /** ключ -> что мы САМИ положили в листовой бандл в последний раз (чтобы отличить свою подмену от чужой). */
    private static final ObjectMap<String, String> applied = new ObjectMap<>();
    private static boolean loaded;

    private static class Saved{
        final @Nullable String value;

        Saved(@Nullable String value){
            this.value = value;
        }
    }

    private BundleOverrides(){
    }

    /** Из Main.kt: только вешает слушатель (подмена бандла уже сделана из Mods.loadBundles). */
    public static void init(){
        //baked-in моды могли дописать строки в бандл уже после loadBundles(); контент мода мог
        //переопределить localizedName сам - повторяем подмену, когда всё загружено
        Events.on(ClientLoadEvent.class, e -> {
            if(disabled()) return;
            apply();
            refreshContent(overrides.keys().toSeq());
        });
    }

    public static boolean disabled(){
        return OS.hasProp("nobundleoverrides");
    }

    public static Fi file(){
        return Core.settings.getDataDirectory().child(fileName);
    }

    /** Читает файл (один раз) и кладёт все переопределения в листовой бандл. Зовётся из Mods.loadBundles(). */
    public static void apply(){
        if(Core.bundle == null || disabled()) return;
        ensureLoaded();
        for(var e : overrides){
            putToBundle(e.key, e.value);
        }
    }

    private static void ensureLoaded(){
        if(loaded) return;
        loaded = true;
        Fi file = file();
        if(!file.exists()) return;
        try(Reader reader = file.reader()){
            ObjectMap<String, String> map = new ObjectMap<>();
            PropertiesUtils.load(map, reader);
            for(var e : map) overrides.put(e.key, e.value);
        }catch(Throwable t){
            Log.err("[sonka] Не удалось прочитать " + fileName + " - переопределения игнорируются", t);
        }
    }

    private static void save(){
        Fi file = file();
        if(overrides.isEmpty()){
            if(file.exists()) file.delete();
            return;
        }
        try(Writer writer = file.writer(false)){
            PropertiesUtils.store(sorted(), writer, "Monolith bundle overrides: key=value, edited in Settings > Interface > Bundle editor", false);
        }catch(Throwable t){
            Log.err("[sonka] Не удалось записать " + fileName, t);
        }
    }

    /** Кладёт значение в листовой бандл, запоминая, что там было (для сброса). */
    private static void putToBundle(String key, String value){
        var props = Core.bundle.getProperties();
        String current = props.get(key);
        //"оригиналом" считаем только то, что положил НЕ мы: свой прошлый вариант (повторная правка)
        //запоминать нельзя, иначе сброс вернул бы предыдущую правку, а не исходный текст
        boolean ours = current != null && current.equals(applied.get(key));
        if(!ours && !value.equals(current)){
            leafBefore.put(key, new Saved(current));
        }
        applied.put(key, value);
        props.put(key, value);
    }

    public static boolean isOverridden(String key){
        ensureLoaded();
        return overrides.containsKey(key);
    }

    public static int count(){
        ensureLoaded();
        return overrides.size;
    }

    /** Текст ключа в обход подмены: то, что показал бы бандл без нашего файла. */
    public static @Nullable String original(String key){
        Saved saved = leafBefore.get(key);
        if(overrides.containsKey(key) && saved != null && saved.value != null) return saved.value;
        //в листе ключа не было - смотрим родителя (английский фолбэк и т.п.)
        if(overrides.containsKey(key)){
            var parent = Core.bundle.getParent();
            return parent == null ? null : parent.getOrNull(key);
        }
        return Core.bundle.getOrNull(key);
    }

    /**
     * Проверка, что строка не уронит игру при форматировании: подпись с {@code {0}} идёт через
     * {@code bundle.format}, и битый шаблон (непарная скобка) кидал бы исключение каждый кадр.
     * Возвращает текст ошибки или null, если всё в порядке.
     */
    public static @Nullable String validate(String value){
        String probe = "__bundleeditor.probe";
        var props = Core.bundle.getProperties();
        String prev = props.get(probe);
        try{
            props.put(probe, value);
            Core.bundle.format(probe, "1", "2", "3", "4", "5", "6");
            return null;
        }catch(Throwable t){
            return Strings.getSimpleMessage(t);
        }finally{
            if(prev == null) props.remove(probe); else props.put(probe, prev);
        }
    }

    /** Ставит/меняет переопределение (живьём) и сохраняет файл. Пустое значение или равное оригиналу = сброс. */
    public static void set(String key, String value){
        ensureLoaded();
        if(value.isEmpty() || value.equals(original(key))){
            reset(key);
            return;
        }
        overrides.put(key, value);
        putToBundle(key, value);
        refreshContent(Seq.with(key));
        save();
    }

    public static void reset(String key){
        ensureLoaded();
        if(!overrides.containsKey(key)) return;
        overrides.remove(key);
        restoreBundle(key);
        refreshContent(Seq.with(key));
        save();
    }

    public static void resetAll(){
        ensureLoaded();
        Seq<String> keys = overrides.keys().toSeq();
        overrides.clear();
        for(String key : keys) restoreBundle(key);
        refreshContent(keys);
        save();
    }

    private static void restoreBundle(String key){
        applied.remove(key);
        Saved saved = leafBefore.remove(key);
        var props = Core.bundle.getProperties();
        if(saved == null || saved.value == null) props.remove(key); else props.put(key, saved.value);
    }

    /** Копия переопределений по алфавиту: файл и экспорт не перемешиваются от сохранения к сохранению. */
    private static OrderedMap<String, String> sorted(){
        OrderedMap<String, String> out = new OrderedMap<>();
        Seq<String> keys = overrides.keys().toSeq();
        keys.sort();
        for(String key : keys) out.put(key, overrides.get(key));
        return out;
    }

    /** Текст для экспорта (в буфер обмена): тот же формат, что у файла. */
    public static String exportText(){
        ensureLoaded();
        StringWriter out = new StringWriter();
        try{
            PropertiesUtils.store(sorted(), out, null, false);
        }catch(Throwable t){
            Log.err(t);
        }
        return out.toString();
    }

    /** Разбор текста импорта; null-элементы не возвращаются. Ничего не применяет. */
    public static ObjectMap<String, String> parse(String text){
        ObjectMap<String, String> map = new OrderedMap<>();
        try{
            PropertiesUtils.load(map, new StringReader(text));
        }catch(Throwable t){
            Log.err(t);
        }
        return map;
    }

    /** Применяет набор ключей разом; битые по {@link #validate} пропускает. Возвращает число применённых. */
    public static int importAll(ObjectMap<String, String> map, boolean replace){
        ensureLoaded();
        if(replace) resetAll();
        int n = 0;
        Seq<String> touched = new Seq<>();
        for(var e : map){
            if(e.value.isEmpty() || validate(e.value) != null) continue;
            overrides.put(e.key, e.value);
            putToBundle(e.key, e.value);
            touched.add(e.key);
            n++;
        }
        refreshContent(touched);
        save();
        return n;
    }

    /**
     * Контент, чьё кэш-поле читается из этого ключа ({@code block.conveyor.name} -> конвейер), либо
     * null для всех остальных ключей. Тип = всё до первой точки, имя контента - между ней и
     * последним суффиксом из {@link #contentSuffixes}.
     */
    public static @Nullable UnlockableContent contentFor(String key){
        if(content == null) return null;
        int last = key.lastIndexOf('.');
        int first = key.indexOf('.');
        if(first < 0 || last <= first) return null;
        if(!contentSuffixes.contains(key.substring(last + 1))) return null;

        ContentType type;
        try{
            type = ContentType.valueOf(key.substring(0, first));
        }catch(IllegalArgumentException e){
            return null;
        }
        if(type.contentClass == null) return null;

        return content.getByName(type, key.substring(first + 1, last)) instanceof UnlockableContent u ? u : null;
    }

    /**
     * Переносит подмену в кэш-поля контента ({@code localizedName/description/details/credit}, см.
     * {@link #contentFor}). Для ключей, которых уже нет в {@link #overrides}, поле возвращается к
     * тому, что было (запомненное значение контента мода либо текущее значение бандла).
     */
    private static void refreshContent(Iterable<String> keys){
        for(String key : keys){
            UnlockableContent u = contentFor(key);
            if(u == null) continue;
            String suffix = key.substring(key.lastIndexOf('.') + 1);

            String now = overrides.get(key);
            if(now != null){
                String field = readField(u, suffix);
                //запоминаем поле для сброса, только если его выставил не мы: ни текущая подмена, ни
                //наша прошлая запись (на старте конструктор уже прочитал подмену из бандла) его не объясняют
                boolean ours = field != null && (field.equals(now) || field.equals(fieldWritten.get(key)));
                if(!ours && !fieldBefore.containsKey(key)) fieldBefore.put(key, new Saved(field));
                writeField(u, suffix, now);
                fieldWritten.put(key, now);
            }else{
                fieldWritten.remove(key);
                Saved saved = fieldBefore.remove(key);
                String text = saved != null ? saved.value : Core.bundle.getOrNull(key);
                //как в конструкторе UnlockableContent: у name фолбэк на внутреннее имя
                if(text == null && suffix.equals("name")) text = u.name;
                writeField(u, suffix, text);
            }
        }
    }

    private static @Nullable String readField(UnlockableContent u, String suffix){
        return switch(suffix){
            case "name" -> u.localizedName;
            case "description" -> u.description;
            case "details" -> u.details;
            default -> u.credit;
        };
    }

    private static void writeField(UnlockableContent u, String suffix, @Nullable String text){
        switch(suffix){
            case "name" -> u.localizedName = text;
            case "description" -> u.description = text;
            case "details" -> u.details = text;
            default -> u.credit = text;
        }
    }
}
