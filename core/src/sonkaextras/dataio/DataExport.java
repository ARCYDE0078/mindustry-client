package sonkaextras.dataio;

import arc.*;
import arc.files.*;
import arc.struct.*;
import arc.util.*;
import arc.util.io.*;
import arc.util.serialization.*;
import mindustry.core.*;

import java.io.*;
import java.nio.charset.*;
import java.text.*;
import java.util.*;
import java.util.EnumSet;
import java.util.zip.*;

import static mindustry.Vars.*;

/**
 * Экспорт выбранных категорий в zip той же структуры, что и ванильный «экспорт данных» (см.
 * {@link DataCategory}), плюс манифест {@link DataCategory#MANIFEST}:
 * <pre>
 * {
 *   "version": 1,                       - версия формата манифеста
 *   "client": "sonka-custom",
 *   "build": 146, "type": "...",        - Version.build / Version.type игры-источника
 *   "date": "2026-08-21 14:03:11",
 *   "categories": ["schematics", ...],  - что реально положено
 *   "counts": {"schematics": 12, ...}   - число файлов/ключей по категориям
 * }
 * </pre>
 * {@code settings.bin} кладётся, если выбраны «настройки» и/или «прогресс кампании» (ванильному
 * импорту он обязателен - без него архив отвергается): это объединение выбранных кусков -
 * обычные ключи, кампанейские ключи, имена обычных слотов (если выбраны обычные сейвы). Прогресс
 * кампании дополнительно пишется в {@link DataCategory#PROGRESS_FILE} (типизированный JSON,
 * см. {@link SettingsBin}) - его и читает наш импорт; settings.bin - запасной источник для
 * ваниль-архивов. Бэкапы сейвов ({@code *-backup.msav}) не экспортируются - только основные файлы.
 * <p>
 * Этот же писатель делает авто-бэкапы категорий перед импортом с перезаписью и полные бэкапы
 * профилей кампании - формат один, любой бэкап можно импортировать обратно тем же диалогом.
 */
public final class DataExport{
    static final DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    static final DateFormat fileDateFormat = new SimpleDateFormat("yyyyMMdd-HHmmss");

    private DataExport(){
    }

    /** Содержимое, собранное из ЖИВОГО состояния игры по выбранным категориям. */
    public static Payload collect(EnumSet<DataCategory> cats){
        Payload p = new Payload();
        for(DataCategory c : cats){
            if(c.isFiles()){
                for(Fi f : c.localFiles()) p.files.put(DataCategory.archivePath(f), f);
                p.counts.put(c.name(), c.localFiles().size);
            }
        }
        boolean assets = cats.stream().anyMatch(DataCategory::wantsAssetCache);
        if(assets && assetCacheDirectory.exists()){
            for(Fi f : assetCacheDirectory.list()){
                if(!f.isDirectory()) p.files.put(DataCategory.ASSET_CACHE + f.name(), f);
            }
        }
        if(cats.contains(DataCategory.campaignProgress)){
            p.progress = CampaignInventory.snapshotProgress();
            p.counts.put(DataCategory.campaignProgress.name(), p.progress.size);
        }
        if(cats.contains(DataCategory.settings)){
            p.settings = new ObjectMap<>();
            for(String key : Core.settings.keys()){
                if(DataCategory.isPlainSettingKey(key)) p.settings.put(key, Core.settings.get(key, null));
            }
            p.counts.put(DataCategory.settings.name(), p.settings.size);
        }
        if(cats.contains(DataCategory.saves)){
            p.slotKeys = new ObjectMap<>();
            for(String key : Core.settings.keys()){
                if(DataCategory.isRegularSlotKey(key)) p.slotKeys.put(key, Core.settings.get(key, null));
            }
        }
        p.categories.addAll(cats);
        return p;
    }

    /** Экспорт живого состояния по категориям в файл. */
    public static void export(Fi out, EnumSet<DataCategory> cats) throws IOException{
        write(out, collect(cats));
    }

    /** Записывает произвольный payload (живой или собранный из папки профиля) в zip. */
    public static void write(Fi out, Payload p) throws IOException{
        Fi parent = out.parent();
        if(parent != null && !parent.exists()) parent.mkdirs();
        try(OutputStream fos = out.write(false, 8192); ZipOutputStream zos = new ZipOutputStream(fos)){
            //манифест первым - удобно смотреть глазами
            zos.putNextEntry(new ZipEntry(DataCategory.MANIFEST));
            zos.write(manifest(p).getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            ObjectMap<String, Object> settingsOut = new ObjectMap<>();
            if(p.settings != null) settingsOut.putAll(p.settings);
            if(p.slotKeys != null) settingsOut.putAll(p.slotKeys);
            if(p.progress != null){
                settingsOut.putAll(p.progress);
                zos.putNextEntry(new ZipEntry(DataCategory.PROGRESS_FILE));
                zos.write(SettingsBin.toJson(p.progress).getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
            if(settingsOut.size > 0){
                zos.putNextEntry(new ZipEntry(DataCategory.SETTINGS_FILE));
                SettingsBin.write(zos, settingsOut);
                zos.closeEntry();
            }

            Seq<String> paths = p.files.keys().toSeq();
            paths.sort();
            for(String path : paths){
                Fi file = p.files.get(path);
                if(!file.exists() || file.isDirectory()) continue;
                zos.putNextEntry(new ZipEntry(path));
                try(InputStream in = file.read(8192)){
                    Streams.copy(in, zos);
                }
                zos.closeEntry();
            }
        }
    }

    static String manifest(Payload p){
        Jval root = Jval.newObject();
        root.put("version", DataCategory.MANIFEST_VERSION);
        root.put("client", "sonka-custom");
        root.put("build", Version.build);
        root.put("type", Version.type);
        root.put("date", dateFormat.format(new Date()));
        Jval cats = Jval.newArray();
        for(DataCategory c : p.categories) cats.add(c.name());
        root.put("categories", cats);
        Jval counts = Jval.newObject();
        for(var e : p.counts) counts.put(e.key, e.value);
        root.put("counts", counts);
        if(p.label != null) root.put("label", p.label);
        return root.toString(Jval.Jformat.formatted);
    }

    /** Имя файла бэкапа: {@code <data>/backups/<ts>-<suffix>.zip}. */
    public static Fi backupFile(String suffix){
        Fi dir = dataDirectory.child("backups");
        dir.mkdirs();
        String ts = fileDateFormat.format(new Date());
        Fi f = dir.child(ts + "-" + suffix + ".zip");
        int i = 1;
        while(f.exists()) f = dir.child(ts + "-" + suffix + "-" + i++ + ".zip");
        return f;
    }

    /** Авто-бэкап одной живой категории перед импортом с перезаписью. */
    public static Fi backupCategory(DataCategory c) throws IOException{
        Fi out = backupFile(c.name());
        //во временный файл и переименование: оборванный zip (диск полон, исключение посреди записи) не должен
        //остаться в backups/ и считаться рабочей копией
        Fi tmp = out.sibling(out.name() + ".part");
        try{
            export(tmp, EnumSet.of(c));
            tmp.moveTo(out);
        }catch(IOException | RuntimeException e){
            tmp.delete();
            throw e;
        }
        if(!out.exists() || out.length() <= 0) throw new IOException("backup file missing after write: " + out.name());
        return out;
    }

    public static class Payload{
        /** путь в архиве -> файл-источник */
        public final ObjectMap<String, Fi> files = new ObjectMap<>();
        public @Nullable ObjectMap<String, Object> progress;
        public @Nullable ObjectMap<String, Object> settings;
        public @Nullable ObjectMap<String, Object> slotKeys;
        public final ObjectIntMap<String> counts = new ObjectIntMap<>();
        public final Seq<DataCategory> categories = new Seq<>();
        public @Nullable String label;
    }
}
