package sonkaextras.dataio;

import arc.*;
import arc.files.*;
import arc.struct.*;
import arc.util.*;
import arc.util.io.*;
import arc.util.serialization.*;
import mindustry.game.*;
import mindustry.game.Schematic.*;
import mindustry.io.*;

import java.io.*;
import java.nio.charset.*;
import java.util.*;
import java.util.EnumSet;
import java.util.zip.*;

import static mindustry.Vars.*;

/**
 * Импорт категорий из zip (нашего с манифестом или ванильного - категории тогда определяются по
 * путям, см. {@link DataCategory#matches}). Два шага: {@link #scan(Fi)} только читает архив и
 * считает, что в нём есть (диалог показывает РЕАЛЬНЫЕ категории со счётчиками), затем
 * {@link #apply} применяет выбранное.
 * <p>
 * Правила безопасности:
 * <ul>
 * <li>импорт НИКОГДА не удаляет существующие файлы; в режиме без перезаписи (по умолчанию) файл с
 *     тем же именем пропускается, схемы дедупятся по имени и по содержимому, обычные сейвы при
 *     коллизии слота получают следующий свободный номер (как {@code Saves.importSave});</li>
 * <li>перед любой перезаписью файловой категории и перед любым изменением настроек/прогресса -
 *     авто-бэкап затрагиваемой категории в {@code <data>/backups/<ts>-<category>.zip} тем же
 *     форматом ({@link DataExport#backupCategory}) - его можно импортировать обратно;</li>
 * <li>zip-slip: имя записи нормализуется, сегменты {@code ..}, абсолютные пути и выход за пределы
 *     data-каталога (проверка канонического пути) отвергаются - такая запись пропускается с
 *     ошибкой в отчёте, остальные применяются;</li>
 * <li>прогресс кампании в режиме перезаписи заменяется ЦЕЛИКОМ (сначала удаляются все локальные
 *     кампанейские ключи - половинчатая смесь двух кампаний хуже любой из них), без перезаписи -
 *     добавляются только отсутствующие ключи.</li>
 * </ul>
 * Применение вживую: схемы через {@code Schematics.add/overwrite} (сами пишут файлы), карты -
 * копирование + {@code maps.reload()}, сейвы/кампания - копирование + перезагрузка списка сейвов,
 * прогресс - {@link CampaignInventory#reloadLive()} (и рекомендация перезапуска), моды - копирование
 * в {@code mods/} и перезапуск (в этом форке {@code mods.reload()} = выход из игры, живой
 * перезагрузки модов нет), настройки - {@code Core.settings.put} (часть применится после перезапуска).
 */
public final class DataImport{
    private DataImport(){
    }

    /** Прочитанный (но ещё не применённый) архив. Держит открытый ZipFile - закрыть обязательно. */
    public static class Archive implements Closeable{
        public Fi source;
        Fi tmp;
        ZipFile zip;
        public @Nullable Jval manifest;
        public final ObjectMap<DataCategory, Seq<ZipEntry>> entries = new ObjectMap<>();
        public final Seq<ZipEntry> assetEntries = new Seq<>();
        /** settings.bin архива целиком (если есть). */
        public @Nullable ObjectMap<String, Object> settingsBin;
        /** Прогресс кампании: из PROGRESS_FILE, иначе фильтр settings.bin. */
        public @Nullable ObjectMap<String, Object> progress;
        /** Обычные ключи настроек (фильтр settings.bin). */
        public @Nullable ObjectMap<String, Object> plainSettings;
        public int rejected;

        public boolean isVanilla(){
            return manifest == null;
        }

        public boolean has(DataCategory c){
            return count(c) > 0;
        }

        public int count(DataCategory c){
            return switch(c){
                case campaignProgress -> progress == null ? 0 : progress.size;
                case settings -> plainSettings == null ? 0 : plainSettings.size;
                default -> entries.containsKey(c) ? entries.get(c).size : 0;
            };
        }

        public boolean any(){
            for(DataCategory c : DataCategory.all) if(has(c)) return true;
            return false;
        }

        /** Поток записи архива (для импорта файлов профиля мимо data-каталога). */
        public InputStream zipStream(ZipEntry e) throws IOException{
            return zip.getInputStream(e);
        }

        byte[] bytes(ZipEntry e) throws IOException{
            try(InputStream in = zip.getInputStream(e)){
                return arc.util.io.Streams.copyBytes(in); //не readAllBytes: API 33, на Android 8-12 NoSuchMethodError
            }
        }

        @Override
        public void close(){
            try{
                if(zip != null) zip.close();
            }catch(IOException ignored){
            }
            if(tmp != null) tmp.delete();
        }
    }

    /** Итог применения - для диалога-отчёта. */
    public static class Result{
        public final ObjectIntMap<DataCategory> imported = new ObjectIntMap<>();
        public final ObjectIntMap<DataCategory> skipped = new ObjectIntMap<>();
        public final ObjectIntMap<DataCategory> replaced = new ObjectIntMap<>();
        public final Seq<Fi> backups = new Seq<>();
        public final Seq<String> errors = new Seq<>();
        public boolean needsRestart;
        public boolean progressChanged;

        public int total(ObjectIntMap<DataCategory> m){
            int n = 0;
            for(var e : m) n += e.value;
            return n;
        }
    }

    /** Читает архив: копия во временную папку (ZipFile нужен реальный файл), классификация записей. */
    public static Archive scan(Fi file) throws IOException{
        Archive a = new Archive();
        a.source = file;
        tmpDirectory.mkdirs();
        a.tmp = tmpDirectory.child("sonka-dataio-import-" + System.nanoTime() + ".zip");
        file.copyTo(a.tmp);
        try{
            a.zip = new ZipFile(a.tmp.file());
        }catch(Throwable t){
            a.close();
            throw new IOException("not a zip archive: " + t.getMessage());
        }
        try{
            Enumeration<? extends ZipEntry> en = a.zip.entries();
            while(en.hasMoreElements()){
                ZipEntry e = en.nextElement();
                if(e.isDirectory()) continue;
                String path = normalize(e.getName());
                if(path == null){
                    a.rejected++;
                    continue;
                }
                if(path.equals(DataCategory.MANIFEST)){
                    try{
                        a.manifest = Jval.read(new String(a.bytes(e), StandardCharsets.UTF_8));
                    }catch(Throwable t){
                        Log.warn("[sonka-dataio] broken manifest ignored: @", t.getMessage());
                    }
                    continue;
                }
                if(path.equals(DataCategory.PROGRESS_FILE)){
                    a.progress = SettingsBin.fromJson(new String(a.bytes(e), StandardCharsets.UTF_8));
                    continue;
                }
                if(path.equals(DataCategory.SETTINGS_FILE)){
                    a.settingsBin = SettingsBin.read(a.zip.getInputStream(e));
                    continue;
                }
                if(path.startsWith(DataCategory.ASSET_CACHE) && path.indexOf('/', DataCategory.ASSET_CACHE.length()) == -1){
                    a.assetEntries.add(e);
                    continue;
                }
                for(DataCategory c : DataCategory.all){
                    if(c.matches(path)){
                        a.entries.get(c, Seq::new).add(e);
                        break;
                    }
                }
            }
            if(a.settingsBin != null){
                if(a.progress == null){
                    ObjectMap<String, Object> p = CampaignInventory.filterProgress(a.settingsBin);
                    if(p.size > 0) a.progress = p;
                }
                ObjectMap<String, Object> plain = new ObjectMap<>();
                for(var e : a.settingsBin){
                    if(DataCategory.isPlainSettingKey(e.key)) plain.put(e.key, e.value);
                }
                if(plain.size > 0) a.plainSettings = plain;
            }
        }catch(Throwable t){
            a.close();
            if(t instanceof IOException io) throw io;
            throw new IOException(t);
        }
        return a;
    }

    /**
     * Нормализация пути записи + zip-slip: null = запись отвергнута. Обратные слэши приводятся к
     * прямым, ведущие '/' и './' снимаются, любой сегмент '..', пустой сегмент в середине, диск
     * Windows ('C:') и абсолютные пути - отказ.
     */
    static @Nullable String normalize(String name){
        if(name == null) return null;
        String p = name.replace('\\', '/');
        while(p.startsWith("/") || p.startsWith("./")) p = p.substring(p.startsWith("/") ? 1 : 2);
        if(p.isEmpty() || p.indexOf(':') != -1 || p.indexOf('\0') != -1) return null;
        String[] parts = p.split("/");
        for(int i = 0; i < parts.length; i++){
            String s = parts[i];
            if(s.equals("..") || s.equals(".") || (s.isEmpty() && i != parts.length - 1)) return null;
        }
        return p;
    }

    /** Целевой файл в data-каталоге с проверкой канонического пути (второй рубеж после normalize). */
    static Fi safeTarget(String path) throws IOException{
        Fi base = dataDirectory;
        Fi target = base.child(path);
        String basePath = base.file().getCanonicalPath();
        String targetPath = target.file().getCanonicalPath();
        if(!targetPath.startsWith(basePath + File.separator) && !targetPath.equals(basePath)){
            throw new IOException("entry escapes data directory: " + path);
        }
        return target;
    }

    /** Применяет выбранные категории. Архив остаётся открытым - закрыть должен вызывающий. */
    public static Result apply(Archive a, EnumSet<DataCategory> cats, boolean overwrite){
        Result r = new Result();
        for(DataCategory c : DataCategory.all){
            if(!cats.contains(c) || !a.has(c)) continue;
            try{
                switch(c){
                    case schematics -> importSchematics(a, overwrite, r);
                    case maps -> importMaps(a, overwrite, r);
                    case mods -> importMods(a, overwrite, r);
                    case campaignSaves -> importCampaignSaves(a, overwrite, r);
                    case saves -> importRegularSaves(a, overwrite, r);
                    case campaignProgress -> importProgress(a, overwrite, r);
                    case settings -> importSettings(a, overwrite, r);
                }
            }catch(Throwable t){
                Log.err("[sonka-dataio] import of " + c + " failed", t);
                r.errors.add(c.title() + ": " + t.getMessage());
            }
        }
        //assetCache едет с картами/сейвами; контент-адресуемый - только дополняем
        if(cats.stream().anyMatch(DataCategory::wantsAssetCache) && a.assetEntries.any()){
            assetCacheDirectory.mkdirs();
            for(ZipEntry e : a.assetEntries){
                try{
                    Fi dst = safeTarget(normalize(e.getName()));
                    if(!dst.exists()) copy(a, e, dst);
                }catch(Throwable t){
                    r.errors.add("assetCache: " + t.getMessage());
                }
            }
            try{
                assetCache.load();
            }catch(Throwable ignored){
            }
        }
        Core.settings.forceSave();
        return r;
    }

    static void copy(Archive a, ZipEntry e, Fi dst) throws IOException{
        Fi parent = dst.parent();
        if(parent != null && !parent.exists()) parent.mkdirs();
        try(InputStream in = a.zip.getInputStream(e); OutputStream out = dst.write(false, 8192)){
            Streams.copy(in, out);
        }
    }

    /**
     * Бэкап категории перед перезаписью. @return false, если копию сделать не удалось - тогда вызывающий НЕ должен
     * ничего перезаписывать в этой категории (раньше импорт шёл дальше и затирал данные без единой копии).
     */
    static boolean backup(DataCategory c, Result r){
        try{
            r.backups.add(DataExport.backupCategory(c));
            return true;
        }catch(Throwable t){
            Log.err("[sonka-dataio] backup of " + c + " failed", t);
            r.errors.add(Core.bundle.format("client.sonka.dataio.backupfail", c.title(), t.getMessage()));
            return false;
        }
    }

    /** Копирование файлов категории «как есть»: пропуск/замена одноимённых. Возвращает, менялось ли что-то. */
    static boolean copyFiles(Archive a, DataCategory c, boolean overwrite, Result r){
        boolean changed = false, backedUp = false, backupFailed = false;
        for(ZipEntry e : a.entries.get(c)){
            try{
                String path = normalize(e.getName());
                Fi dst = safeTarget(path);
                if(dst.exists()){
                    if(!overwrite || sameContent(a, e, dst)){
                        r.skipped.increment(c);
                        continue;
                    }
                    if(!backedUp){
                        if(backupFailed || !backup(c, r)){
                            backupFailed = true; //копии нет - существующие файлы не трогаем
                            r.skipped.increment(c);
                            continue;
                        }
                        backedUp = true;
                    }
                    copy(a, e, dst);
                    r.replaced.increment(c);
                }else{
                    copy(a, e, dst);
                    r.imported.increment(c);
                }
                changed = true;
            }catch(Throwable t){
                r.errors.add(c.title() + " / " + e.getName() + ": " + t.getMessage());
            }
        }
        return changed;
    }

    static boolean sameContent(Archive a, ZipEntry e, Fi local) throws IOException{
        if(e.getSize() >= 0 && e.getSize() != local.length()) return false;
        return Arrays.equals(a.bytes(e), local.readBytes());
    }

    //---- категории ----

    static void importSchematics(Archive a, boolean overwrite, Result r){
        Seq<Schematic> existing = schematics.all();
        boolean backedUp = false, backupFailed = false;
        for(ZipEntry e : a.entries.get(DataCategory.schematics)){
            try{
                Schematic s = Schematics.read(new ByteArrayInputStream(a.bytes(e)));
                if(!s.tags.containsKey("name")){
                    String n = normalize(e.getName());
                    n = n.substring(n.lastIndexOf('/') + 1);
                    s.tags.put("name", n.endsWith("." + schematicExtension) ? n.substring(0, n.length() - schematicExtension.length() - 1) : n);
                }
                //дедуп по содержимому под любым именем и по имени
                Schematic sameName = existing.find(o -> o.mod == null && o.name().equals(s.name()));
                if(existing.contains(o -> o.mod == null && sameTiles(o, s))){
                    r.skipped.increment(DataCategory.schematics);
                    continue;
                }
                if(sameName != null){
                    if(!overwrite){
                        r.skipped.increment(DataCategory.schematics);
                        continue;
                    }
                    if(!backedUp){
                        if(backupFailed || !backup(DataCategory.schematics, r)){
                            backupFailed = true; //копии нет - одноимённую схему не перезаписываем
                            r.skipped.increment(DataCategory.schematics);
                            continue;
                        }
                        backedUp = true;
                    }
                    schematics.overwrite(sameName, s);
                    r.replaced.increment(DataCategory.schematics);
                }else{
                    schematics.add(s);
                    r.imported.increment(DataCategory.schematics);
                }
            }catch(Throwable t){
                r.errors.add(DataCategory.schematics.title() + " / " + e.getName() + ": " + t.getMessage());
            }
        }
    }

    static boolean sameTiles(Schematic a, Schematic b){
        if(a.width != b.width || a.height != b.height || a.tiles.size != b.tiles.size) return false;
        for(int i = 0; i < a.tiles.size; i++){
            Stile x = a.tiles.get(i), y = b.tiles.get(i);
            if(x.block != y.block || x.x != y.x || x.y != y.y || x.rotation != y.rotation || !Objects.deepEquals(x.config, y.config)) return false;
        }
        return true;
    }

    static void importMaps(Archive a, boolean overwrite, Result r){
        customMapDirectory.mkdirs();
        if(copyFiles(a, DataCategory.maps, overwrite, r)){
            try{
                maps.reload();
            }catch(Throwable t){
                r.errors.add("maps.reload: " + t.getMessage());
            }
        }
    }

    static void importMods(Archive a, boolean overwrite, Result r){
        modDirectory.mkdirs();
        if(copyFiles(a, DataCategory.mods, overwrite, r)){
            //включённость мода хранится в settings (mod-<name>-enabled); не трогаем - новый мод по
            //умолчанию включён (ключа нет = enabled), а выключенные у источника остаются как есть
            r.needsRestart = true;
        }
    }

    static void importCampaignSaves(Archive a, boolean overwrite, Result r){
        saveDirectory.mkdirs();
        if(copyFiles(a, DataCategory.campaignSaves, overwrite, r)){
            //имена сектор-слотов (SaveSlot.getName по ключу save-<stem>-name); без них last-sector-save не сматчится
            for(ZipEntry e : a.entries.get(DataCategory.campaignSaves)){
                String n = normalize(e.getName());
                if(n == null) continue;
                n = n.substring(n.lastIndexOf('/') + 1);
                if(!n.startsWith(CampaignInventory.SECTOR_PREFIX) || !n.endsWith("." + saveExtension)) continue;
                String stem = n.substring(0, n.length() - saveExtension.length() - 1);
                String key = "save-" + stem + "-name";
                if(!Core.settings.has(key)){
                    Object fromArchive = a.settingsBin != null ? a.settingsBin.get(key) : null;
                    Core.settings.put(key, fromArchive instanceof String s ? s : stem);
                }
            }
            reloadSaves(r);
        }
    }

    static void importRegularSaves(Archive a, boolean overwrite, Result r){
        saveDirectory.mkdirs();
        boolean changed = false, backedUp = false, backupFailed = false;
        for(ZipEntry e : a.entries.get(DataCategory.saves)){
            try{
                String path = normalize(e.getName());
                String name = path.substring(path.lastIndexOf('/') + 1);
                String stem = name.substring(0, name.length() - saveExtension.length() - 1);
                Fi dst = safeTarget(path);
                byte[] bytes = a.bytes(e);
                //дедуп по содержимому среди всех обычных слотов
                boolean dup = false;
                for(Fi f : saveDirectory.list()){
                    if(!f.isDirectory() && CampaignInventory.isRegularSaveName(f.name()) && f.length() == bytes.length && Arrays.equals(f.readBytes(), bytes)){
                        dup = true;
                        break;
                    }
                }
                if(dup){
                    r.skipped.increment(DataCategory.saves);
                    continue;
                }
                String targetStem = stem;
                if(dst.exists()){
                    if(overwrite){
                        if(!backedUp){
                            if(backupFailed || !backup(DataCategory.saves, r)){
                                backupFailed = true; //копии нет - существующий слот не перезаписываем
                                r.skipped.increment(DataCategory.saves);
                                continue;
                            }
                            backedUp = true;
                        }
                        dst.writeBytes(bytes);
                        r.replaced.increment(DataCategory.saves);
                    }else{
                        //коллизия слота без перезаписи - следующий свободный номер, как Saves.importSave
                        Fi next = control.saves.getNextSlotFile();
                        next.writeBytes(bytes);
                        targetStem = next.nameWithoutExtension();
                        r.imported.increment(DataCategory.saves);
                    }
                }else{
                    dst.writeBytes(bytes);
                    r.imported.increment(DataCategory.saves);
                }
                //имя/автосейв слота из settings.bin архива (ключи исходного номера), иначе имя файла
                Object nm = a.settingsBin != null ? a.settingsBin.get("save-" + stem + "-name") : null;
                Object as = a.settingsBin != null ? a.settingsBin.get("save-" + stem + "-autosave") : null;
                if(overwrite || !Core.settings.has("save-" + targetStem + "-name")){
                    Core.settings.put("save-" + targetStem + "-name", nm instanceof String s ? s : stem);
                }
                if(as instanceof Boolean b && (overwrite || !Core.settings.has("save-" + targetStem + "-autosave"))){
                    Core.settings.put("save-" + targetStem + "-autosave", b);
                }
                changed = true;
            }catch(Throwable t){
                r.errors.add(DataCategory.saves.title() + " / " + e.getName() + ": " + t.getMessage());
            }
        }
        if(changed) reloadSaves(r);
    }

    static void reloadSaves(Result r){
        try{
            if(state.isMenu()){
                control.saves.load();
                control.saves.unload();
            }
        }catch(Throwable t){
            r.errors.add("saves.load: " + t.getMessage());
        }
    }

    static void importProgress(Archive a, boolean overwrite, Result r){
        if(a.progress == null) return;
        if(!state.isMenu()){
            r.errors.add(Core.bundle.get("client.sonka.dataio.menuonly"));
            return;
        }
        //прогресс меняет settings всегда - бэкап всегда; без бэкапа ничего не трогаем
        if(!backup(DataCategory.campaignProgress, r)) return;
        if(overwrite){
            CampaignInventory.clearProgress();
            CampaignInventory.putProgress(a.progress);
            r.replaced.put(DataCategory.campaignProgress, a.progress.size);
        }else{
            int added = 0, skipped = 0;
            for(var e : a.progress){
                if(Core.settings.has(e.key)){
                    skipped++;
                }else{
                    Core.settings.put(e.key, e.value);
                    added++;
                }
            }
            r.imported.put(DataCategory.campaignProgress, added);
            r.skipped.put(DataCategory.campaignProgress, skipped);
        }
        r.progressChanged = true;
        try{
            CampaignInventory.reloadLive();
        }catch(Throwable t){
            Log.err("[sonka-dataio] live reload after progress import failed", t);
            r.errors.add("reload: " + t.getMessage());
        }
        r.needsRestart = true;
    }

    static void importSettings(Archive a, boolean overwrite, Result r){
        if(a.plainSettings == null) return;
        if(!backup(DataCategory.settings, r)) return;
        int added = 0, replaced = 0, skipped = 0;
        for(var e : a.plainSettings){
            if(Core.settings.has(e.key)){
                if(overwrite && !Objects.deepEquals(Core.settings.get(e.key, null), e.value)){
                    Core.settings.put(e.key, e.value);
                    replaced++;
                }else{
                    skipped++;
                }
            }else{
                Core.settings.put(e.key, e.value);
                added++;
            }
        }
        r.imported.put(DataCategory.settings, added);
        r.replaced.put(DataCategory.settings, replaced);
        r.skipped.put(DataCategory.settings, skipped);
        if(added + replaced > 0) r.needsRestart = true;
    }
}
