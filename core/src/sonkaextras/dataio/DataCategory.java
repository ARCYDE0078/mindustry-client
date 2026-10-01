package sonkaextras.dataio;

import arc.*;
import arc.files.*;
import arc.struct.*;
import arc.util.*;

import java.util.*;

import static mindustry.Vars.*;

/**
 * Категории гранулярного импорта/экспорта. Пути в архиве - ровно как у ванильного «экспорта данных»
 * ({@code SettingsMenuDialog.exportData}: {@code settings.bin}, {@code maps/}, {@code saves/},
 * {@code mods/}, {@code schematics/}, {@code assetCache/} относительно data-каталога), поэтому наш
 * архив понимает и ванильный импорт, а ванильный архив - наш.
 * <p>
 * {@code assetCache/} (контент-адресуемые картинки карт/сейвов с внешними ассетами, имя файла = хэш)
 * отдельной категорией не является: едет вместе с картами и сейвами и при импорте только
 * дополняется - одинаковое имя = одинаковое содержимое, конфликтов нет по построению.
 */
public enum DataCategory{
    schematics("schematics/"),
    maps("maps/"),
    mods("mods/"),
    campaignSaves("saves/"),
    saves("saves/"),
    /** Кампанейские ключи settings: свой файл {@link #PROGRESS_FILE}, либо (ваниль-архив) фильтр settings.bin. */
    campaignProgress(null),
    /** Остальные ключи settings.bin (без кампании, без имён сейв-слотов, без uuid/usid). */
    settings(null);

    public static final String MANIFEST = "custom-export.json";
    public static final String PROGRESS_FILE = "campaign-progress.json";
    public static final String SETTINGS_FILE = "settings.bin";
    public static final String ASSET_CACHE = "assetCache/";
    public static final int MANIFEST_VERSION = 1;

    public static final DataCategory[] all = values();

    /** Префикс пути в архиве для файловых категорий; null у настроечных. */
    public final @Nullable String prefix;

    DataCategory(String prefix){
        this.prefix = prefix;
    }

    public String title(){
        return Core.bundle.get("client.sonka.dataio.cat." + name());
    }

    public String description(){
        return Core.bundle.get("client.sonka.dataio.cat." + name() + ".desc");
    }

    public boolean isFiles(){
        return prefix != null;
    }

    /** Нужен ли этой категории попутный assetCache/. */
    public boolean wantsAssetCache(){
        return this == maps || this == saves || this == campaignSaves;
    }

    /**
     * Относится ли путь архива (нормализованный, без ведущего '/') к категории. Директории (с '/' на
     * конце) и бэкапы сейвов не считаются. Служебные файлы (манифест/прогресс/settings.bin) - нет.
     */
    public boolean matches(String path){
        if(prefix == null || !path.startsWith(prefix) || path.endsWith("/")) return false;
        String rest = path.substring(prefix.length());
        if(rest.isEmpty()) return false;
        switch(this){
            case schematics:
                return rest.indexOf('/') == -1 && rest.endsWith("." + schematicExtension);
            case maps:
                return rest.indexOf('/') == -1 && rest.endsWith("." + mapExtension);
            case mods:
                //и zip/jar-моды, и распакованные папки-моды (вложенные пути)
                return !rest.endsWith("/");
            case campaignSaves:
                return rest.indexOf('/') == -1 && CampaignInventory.isCampaignSaveName(rest) && !CampaignInventory.isBackupSaveName(rest);
            case saves:
                return rest.indexOf('/') == -1 && CampaignInventory.isRegularSaveName(rest);
            default:
                return false;
        }
    }

    /** Локальные файлы категории (что уедет в экспорт / что бэкапится перед перезаписью). */
    public Seq<Fi> localFiles(){
        Seq<Fi> out = new Seq<>();
        switch(this){
            case schematics -> {
                if(schematicDirectory.exists()) for(Fi f : schematicDirectory.list()) if(!f.isDirectory() && f.extEquals(schematicExtension)) out.add(f);
            }
            case maps -> {
                if(customMapDirectory.exists()) for(Fi f : customMapDirectory.list()) if(!f.isDirectory() && f.extEquals(mapExtension)) out.add(f);
            }
            case mods -> {
                if(modDirectory.exists()) modDirectory.walk(f -> {
                    if(!f.isDirectory()) out.add(f);
                });
            }
            case campaignSaves -> {
                for(Fi f : CampaignInventory.campaignSaveFiles()) if(!CampaignInventory.isBackupSaveName(f.name())) out.add(f);
            }
            case saves -> {
                if(saveDirectory.exists()) for(Fi f : saveDirectory.list()) if(!f.isDirectory() && CampaignInventory.isRegularSaveName(f.name())) out.add(f);
            }
            default -> {
            }
        }
        return out;
    }

    /** Сколько локально элементов в категории (для счётчиков в диалоге экспорта). */
    public int localCount(){
        return switch(this){
            case campaignProgress -> CampaignInventory.snapshotProgress().size;
            case settings -> {
                int n = 0;
                for(String key : Core.settings.keys()) if(isPlainSettingKey(key)) n++;
                yield n;
            }
            default -> localFiles().size;
        };
    }

    /** Путь файла в архиве (относительно data-каталога), как у ванили. */
    public static String archivePath(Fi file){
        String base = Core.settings.getDataDirectory().path();
        String path = file.path();
        if(path.startsWith(base)) path = path.substring(base.length());
        path = path.replace('\\', '/');
        return path.startsWith("/") ? path.substring(1) : path;
    }

    /** Подстроки (в нижнем регистре) ключей, под которыми лежат учётки и ключи доступа: токены входа mindustry.tool, API-ключи переводчиков, пароль комнаты Player Connect, персональный chat-id. */
    private static final String[] SECRET_PARTS = {
        "token", "password", "passwd", "secret", "api-key", "apikey", "api_key", "chat-id", "chatid",
        "cookie", "bearer", "credential", "email", "mindustrytool.auth."
    };

    /**
     * Ключ, который нельзя переносить архивом ни в одну сторону. Два вида:
     * <ul>
     * <li>секреты ({@link #SECRET_PARTS}): zip с экспортом люди шлют друзьям и выкладывают «вот мои настройки», и вместе с
     * ним уезжали бы токены входа и ключи API; обратно такие ключи тоже не принимаем - они привязаны к аккаунту и машине;</li>
     * <li>{@code updateurl}/{@code autoupdate}: это точка доверия апдейтера. Принять их из чужого архива = подменить репозиторий,
     * откуда клиент сам скачивает и запускает jar (даже с проверкой SHA-256 сумма берётся из того же репозитория).</li>
     * </ul>
     * Сам ключ при этом остаётся в локальных настройках - просто не участвует в экспорте/импорте.
     */
    public static boolean isProtectedSettingKey(String key){
        if(key == null) return true;
        String k = key.toLowerCase(Locale.ROOT);
        if(k.equals("updateurl") || k.equals("autoupdate")) return true;
        for(String part : SECRET_PARTS){
            if(k.contains(part)) return true;
        }
        return false;
    }

    /**
     * Ключ «обычной» настройки - всё, что не кампания, не имена/автосейв слотов (едут с сейвами) и
     * не идентичность игрока ({@code uuid}, {@code usid-*}: их и ваниль бережёт при «очистить всё»),
     * не служебные ключи профилей кампании и не защищённые ключи ({@link #isProtectedSettingKey}).
     */
    public static boolean isPlainSettingKey(String key){
        if(isProtectedSettingKey(key)) return false;
        if(CampaignInventory.isCampaignKey(key)) return false;
        if(key.startsWith("save-")) return false;
        if(key.equals("uuid") || key.startsWith("usid-")) return false;
        return !key.startsWith("sonka-campaign-");
    }

    /** Ключи имён/автосейва обычных слотов ({@code save-<n>-name}/{@code -autosave}, n - число). */
    public static boolean isRegularSlotKey(String key){
        if(!key.startsWith("save-")) return false;
        String rest = key.substring(5);
        int dash = rest.indexOf('-');
        if(dash <= 0) return false;
        String idx = rest.substring(0, dash);
        for(int i = 0; i < idx.length(); i++){
            if(!Character.isDigit(idx.charAt(i))) return false;
        }
        return true;
    }
}
