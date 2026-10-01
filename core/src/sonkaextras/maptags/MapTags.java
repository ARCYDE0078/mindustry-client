package sonkaextras.maptags;

import arc.*;
import arc.struct.*;
import arc.util.serialization.*;
import mindustry.maps.Map;

import static mindustry.Vars.*;

/**
 * Теги карт - по аналогии с {@link mindustry.game.Schematic#labels}/SchematicsDialog, но
 * БЕЗ изменения формата карты: {@link Map} хранит только {@code StringMap tags} (метаданные
 * name/author/description/...), поля-списка меток вроде {@code labels} у него нет, а формат
 * {@code .msav} (сериализация через SaveIO/MapIO) трогать нельзя - это сломало бы совместимость
 * с ваниль-сохранениями и публикацией карт в Steam Workshop. Поэтому теги живут ОТДЕЛЬНО в
 * {@code Core.settings}, одним ключом {@value #SETTINGS_KEY}, JSON-объектом
 * {@code {"<mapKey>": ["tag1", "tag2", ...], ...}}.
 * <p>
 * Пишем/читаем вручную через {@link Jval} (как {@code mindustrytool.Utils}), а не через
 * {@code Core.settings.getJson(ObjectMap.class, ...)}: встроенный ридер арка сериализует
 * дженерик-элемент только ОДНОГО уровня вложенности (см. сигнатуру
 * {@code getJson(name, type, elementType, def)}) - для {@code ObjectMap<String, Seq<String>>}
 * (два уровня: карта тегов И список тегов внутри) этого недостаточно. Через Jval вложенность
 * произвольная и явная.
 * <p>
 * Идентификатор карты - тот же приём, что уже применён в самом {@link Map} для похожей задачи
 * (см. {@link Map#getHightScore()}/{@link Map#setHighScore(int)}):
 * {@code file.nameWithoutExtension() + tags.get("steamid", "")}. Просто имя файла ненадёжно
 * для воркшоп-карт: Steam кладёт каждый подписанный предмет в свою папку, а сам файл внутри
 * часто называется одинаково (например {@code map.msav}) для всех предметов - коллизия имён
 * файлов между разными картами вполне реальна. steamid уникален на предмет воркшопа и пуст
 * для custom/built-in карт, так что добавка снимает коллизию тем же способом, каким это уже
 * решено в движке для хайскоров и превью-кэша - переиспользуем готовый прецедент, а не
 * изобретаем свой (map.tags.get("name") ненадёжен ещё сильнее: у built-in и custom карты
 * отображаемое имя вообще может совпадать буквально).
 */
public final class MapTags{
    private static final String SETTINGS_KEY = "sonka-map-tags";

    private MapTags(){}

    private static String keyFor(Map map){
        return map.file.nameWithoutExtension() + map.tags.get("steamid", "");
    }

    private static Jval load(){
        String raw = Core.settings.getString(SETTINGS_KEY, null);
        if(raw == null || raw.isEmpty()) return Jval.newObject();
        try{
            Jval v = Jval.read(raw);
            return v.isObject() ? v : Jval.newObject();
        }catch(Throwable t){
            //повреждённая настройка - теги карт не критичны, просто начинаем с чистого листа
            return Jval.newObject();
        }
    }

    private static void save(Jval root){
        qol.core.SafeSettings.putString(SETTINGS_KEY, root.toString());
    }

    private static Seq<String> readArray(Jval arr){
        Seq<String> out = new Seq<>();
        if(arr != null && arr.isArray()){
            for(Jval item : arr.asArray()){
                if(item.isString()) out.add(item.asString());
            }
        }
        return out;
    }

    private static Jval writeArray(Seq<String> tags){
        Jval arr = Jval.newArray();
        for(String tag : tags) arr.asArray().add(Jval.valueOf(tag));
        return arr;
    }

    public static Seq<String> getTags(Map map){
        return readArray(load().get(keyFor(map)));
    }

    public static void setTags(Map map, Seq<String> tags){
        Jval root = load();
        String key = keyFor(map);
        if(tags == null || tags.isEmpty()){
            root.remove(key);
        }else{
            root.put(key, writeArray(tags));
        }
        save(root);
    }

    public static void addTag(Map map, String tag){
        Seq<String> tags = getTags(map);
        if(!tags.contains(tag)){
            tags.add(tag);
            setTags(map, tags);
        }
    }

    public static void removeTag(Map map, String tag){
        Seq<String> tags = getTags(map);
        if(tags.remove(tag)){
            setTags(map, tags);
        }
    }

    /** Все известные карты (custom+built-in+модовые), как их видит MapListDialog. */
    private static Seq<Map> allMaps(){
        Seq<Map> out = new Seq<>();
        out.addAll(maps.customMaps());
        out.addAll(maps.defaultMaps());
        out.addAll(maps.moddedMaps());
        return out.distinct();
    }

    /** Уникальные теги по всем известным картам, с подсчётом; ключи - в порядке первого появления. */
    public static ObjectMap<String, Integer> allTags(){
        ObjectMap<String, Integer> counts = new ObjectMap<>();
        for(Map map : allMaps()){
            for(String tag : getTags(map)){
                counts.put(tag, counts.get(tag, 0) + 1);
            }
        }
        return counts;
    }

    /**
     * Переименовывает тег во ВСЕХ картах. Если {@code newTag} уже был на карте (слияние
     * тегов), дубликат не создаётся - как и {@code SchematicsDialog.deleteTag}/renameTag,
     * которые эту же ситуацию просто исключают отдельной проверкой "тег уже существует"
     * на уровне глобального списка тегов; здесь список глобальный не хранится, поэтому
     * слияние обрабатывается на месте, по каждой карте отдельно.
     */
    public static void renameTag(String oldTag, String newTag){
        if(oldTag == null || newTag == null || newTag.isEmpty() || oldTag.equals(newTag)) return;
        Jval root = load();
        for(String key : root.asObject().keys().toArray()){
            Seq<String> current = readArray(root.get(key));
            if(!current.remove(oldTag)) continue;
            if(!current.contains(newTag)) current.add(newTag);
            root.put(key, writeArray(current));
        }
        save(root);
    }

    /** Снимает тег со всех карт. */
    public static void deleteTag(String tag){
        if(tag == null) return;
        Jval root = load();
        for(String key : root.asObject().keys().toArray()){
            Seq<String> current = readArray(root.get(key));
            if(!current.remove(tag)) continue;
            if(current.isEmpty()) root.remove(key);
            else root.put(key, writeArray(current));
        }
        save(root);
    }
}
