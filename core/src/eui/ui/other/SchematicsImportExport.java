package eui.ui.other;

import arc.Core;
import arc.files.Fi;
import arc.files.ZipFi;
import arc.struct.ObjectSet;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.serialization.Jval;
import mindustry.game.Schematic;
import mindustry.ui.FileChooser;

import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static mindustry.Vars.schematics;
import static mindustry.Vars.ui;

/**
 * Export/import of the schematics-table's full setup (grid dimensions, category names/icons, every
 * cell's schematic+icon binding, and copies of every referenced schematic) as a single zip - a way to
 * move a whole curated table setup between installs/machines instead of rebuilding it by hand. Ported
 * from ui/other/schematics-import-export.js.
 * <p>
 * С "Таблицы схем 2.0" конфиг сетки - один вложенный документ "table2" (формат
 * {@link SchemTableData}); импорт СТАРЫХ zip'ов (без "table2") продолжает работать: их легаси-ключи
 * записываются как раньше, после чего обычная автомиграция SchemTableData собирает из них документ.
 * <p>
 * Uses the builder {@code mindustry.ui.FileChooser} API ({@code FileChooser.save/open(ext).submit(cons)})
 * - per the memory of this file's own debugging history, the older {@code Platform.showFileChooser(bool,
 * ext, cons)} the source originally called doesn't exist in this engine version at all (removed upstream
 * of even the JS fork).
 * <p>
 * The JSON round-trip uses {@link Jval} (Arc's own lightweight JSON value type, already a dependency of
 * this engine) rather than any generic library. The source's "convert via java.lang.reflect.Method.invoke
 * on Core.settings.put" dance on import doesn't apply here either - that existed only to force a
 * Rhino-parsed JSON number (always a boxed {@code Double}) into the specific {@code Integer}/{@code
 * String}/{@code Boolean} overload {@code Settings.put} needed; plain Java code just calls the correctly
 * typed overload directly.
 */
public class SchematicsImportExport{
    private final SchematicsTableUi schematicsTableUi;

    public SchematicsImportExport(SchematicsTableUi schematicsTableUi){
        this.schematicsTableUi = schematicsTableUi;
    }

    public void exportSchematicsTable(Fi outputFile) throws IOException{
        Fi tmpDirectory = Core.files.local("extended-ui-temp");
        if(!tmpDirectory.exists()) tmpDirectory.mkdirs();

        Fi exportDir = tmpDirectory.child("schematics-export");
        if(exportDir.exists()) exportDir.deleteDirectory();
        exportDir.mkdirs();

        Jval settings = Jval.newObject();
        settings.put("rows", Core.settings.getInt("eui-SchematicsTableRows", 4));
        settings.put("columns", Core.settings.getInt("eui-SchematicsTableColumns", 5));
        settings.put("buttonSize", Core.settings.getInt("eui-SchematicsTableButtonSize", 48));
        settings.put("positionX", SchematicsTableUi.parseIntSetting("eui-SchematicsTableX", 10));
        settings.put("positionY", SchematicsTableUi.parseIntSetting("eui-SchematicsTableY", 160));
        settings.put("alpha", Core.settings.getInt("eui-SchematicsTableAlpha", 100));
        settings.put("showPreview", Core.settings.getBool("eui-ShowSchematicsPreview", true));

        //"Таблица 2.0": весь конфиг (страницы/ячейки/группы/иконки) - один вложенный документ
        //в том же формате, что и ключ настроек SchemTableData.SETTINGS_KEY
        SchemTableData table2 = SchemTableData.get();
        settings.put("table2", table2.toJson());

        //имена схем всех ячеек - для выгрузки .msch файлов ниже
        Jval schematicsArr = Jval.newArray();
        for(SchemTableData.Page p : table2.pages){
            for(arc.struct.IntMap.Entry<SchemTableData.CellData> e : p.cells){
                if(!e.value.schematic.isEmpty()){
                    Jval entry = Jval.newObject();
                    entry.put("schematicName", e.value.schematic);
                    schematicsArr.add(entry);
                }
            }
        }

        Fi settingsFile = exportDir.child("settings.json");
        settingsFile.writeString(settings.toString());

        Fi schemesDir = exportDir.child("schemes");
        schemesDir.mkdirs();

        //snapshot the schematic library once (name -> Schematic), rather than re-scanning it per entry
        var byName = new arc.struct.ObjectMap<String, Schematic>();
        for(Schematic s : schematics.all()){
            String n = s.name();
            if(n != null) byName.put(n, s);
        }

        Seq<String> zipEntryPaths = new Seq<>();
        Seq<Fi> zipEntryFiles = new Seq<>();
        zipEntryPaths.add("settings.json");
        zipEntryFiles.add(settingsFile);

        ObjectSet<String> exportedNames = new ObjectSet<>();
        for(Jval entry : schematicsArr.asArray()){
            String schematicName = entry.get("schematicName").asString();
            if(schematicName.isEmpty() || exportedNames.contains(schematicName)) continue;

            Schematic s = byName.get(schematicName);
            if(s == null) continue;

            try{
                Fi schemeFile = schemesDir.child(schematicName + ".msch");
                mindustry.game.Schematics.write(s, schemeFile);
                exportedNames.add(schematicName);
                zipEntryPaths.add("schemes/" + schematicName + ".msch");
                zipEntryFiles.add(schemeFile);
            }catch(Exception ignored){
            }
        }

        try(OutputStream fos = outputFile.write(false); ZipOutputStream zos = new ZipOutputStream(fos)){
            for(int i = 0; i < zipEntryPaths.size; i++){
                zos.putNextEntry(new ZipEntry(zipEntryPaths.get(i)));
                zos.write(zipEntryFiles.get(i).readBytes());
                zos.closeEntry();
            }
        }

        exportDir.deleteDirectory();
    }

    public void importSchematicsTable(Fi inputFile, boolean createBackup) throws IOException{
        if(createBackup){
            try{
                Fi backupFile = Core.files.local("extended-ui-schematics-backup-" + System.currentTimeMillis() + ".zip");
                exportSchematicsTable(backupFile);
            }catch(Exception ignored){
            }
        }

        Fi dest = Core.files.local("extended-ui-import.zip");
        inputFile.copyTo(dest);

        if(!dest.exists()){
            dest.delete();
            throw new IOException("Failed to copy ZIP file.");
        }

        ZipFi zipped = new ZipFi(dest);

        Fi settingsFile = zipped.child("settings.json");
        if(!settingsFile.exists()){
            dest.delete();
            throw new IOException("Invalid export - settings.json missing.");
        }

        Jval settings = Jval.read(settingsFile.readString());

        Core.settings.putInt("eui-SchematicsTableRows", intOr(settings, "rows", 4));
        Core.settings.putInt("eui-SchematicsTableColumns", intOr(settings, "columns", 5));
        Core.settings.putInt("eui-SchematicsTableButtonSize", intOr(settings, "buttonSize", 48));
        //Int, а не String: слайдеры X/Y зарегистрированы как Int (EUIMod), строковое значение сбрасывалось ими в дефолт
        //при следующем открытии настроек (parseIntSetting читает оба типа, так что остальные читатели не страдают)
        Core.settings.putInt("eui-SchematicsTableX", intOr(settings, "positionX", 10));
        Core.settings.putInt("eui-SchematicsTableY", intOr(settings, "positionY", 160));
        Core.settings.putInt("eui-SchematicsTableAlpha", intOr(settings, "alpha", 100));
        Core.settings.put("eui-ShowSchematicsPreview", boolOr(settings, "showPreview", true));

        //новый формат: готовый документ "Таблицы 2.0" кладётся напрямую в ключ настроек
        if(settings.has("table2")){
            Core.settings.put(SchemTableData.SETTINGS_KEY, settings.get("table2").toString());
            SchemTableData.invalidate();
        }else{
            //старый zip (легаси-ключи ниже): после их записи сносим документ 2.0 и инвалидируем кэш -
            //следующее обращение прогонит обычную автомиграцию по свежеимпортированным легаси-ключам
            Core.settings.remove(SchemTableData.SETTINGS_KEY);
            SchemTableData.invalidate();
        }

        if(settings.has("categories")){
            for(Jval cat : settings.get("categories").asArray()){
                String name = stringOr(cat, "name", "");
                String image = stringOr(cat, "image", "");
                int id = intOr(cat, "id", 0);
                if(!name.isEmpty()) Core.settings.put("category" + id + "name", name);
                if(!image.isEmpty()) Core.settings.put("category" + id + "image", image);
            }
        }

        Fi schemesDir = zipped.child("schemes");
        if(schemesDir.exists()){
            ObjectSet<String> existingNames = new ObjectSet<>();
            for(Schematic s : schematics.all()){
                if(s != null && s.name() != null) existingNames.add(s.name());
            }

            Seq<Fi> schemeFiles = new Seq<>();
            schemesDir.walk(schemeFiles::add);

            for(Fi f : schemeFiles){
                if(!f.extEquals("msch")) continue;
                try{
                    Schematic schematic = mindustry.game.Schematics.read(f);
                    if(schematic != null){
                        String name = schematic.name();
                        if(name != null && !existingNames.contains(name)){
                            schematics.add(schematic); //persists to its own file too, see class javadoc
                            existingNames.add(name);
                        }
                    }
                }catch(Exception ignored){
                }
            }
        }

        if(settings.has("schematics")){
            for(Jval entry : settings.get("schematics").asArray()){
                String key = "schematic" + intOr(entry, "category", 0) + "." + intOr(entry, "column", 0) + "." + intOr(entry, "row", 0);
                String schematicName = stringOr(entry, "schematicName", "");
                String schematicImage = stringOr(entry, "schematicImage", "");
                if(!schematicName.isEmpty()) Core.settings.put(key, schematicName);
                if(!schematicImage.isEmpty()) Core.settings.put(key + "image", schematicImage);
            }
        }

        schematicsTableUi.rebuildTableIfBuilt();

        dest.delete();
    }

    static int intOr(Jval obj, String key, int def){
        Jval v = obj.get(key);
        return v != null && !v.isNull() ? v.asInt() : def;
    }

    static String stringOr(Jval obj, String key, String def){
        Jval v = obj.get(key);
        return v != null && !v.isNull() ? v.asString() : def;
    }

    static boolean boolOr(Jval obj, String key, boolean def){
        Jval v = obj.get(key);
        return v != null && !v.isNull() ? v.asBool() : def;
    }

    public void showExportDialog(){
        try{
            FileChooser.save("zip").name("extended-ui-schematics.zip").submit(file -> {
                try{
                    exportSchematicsTable(file);
                    ui.showInfoFade("@schematics-table.export.success");
                }catch(Exception e){
                    Log.err("[eui] schematics-table export error", e);
                    ui.showErrorMessage("Export failed\n" + e.getMessage());
                }
            });
        }catch(Exception e){
            Log.err("[eui] schematics-table export dialog error", e);
            ui.showErrorMessage("Failed: " + e.getMessage());
        }
    }

    public void showImportDialog(){
        try{
            ui.showConfirm("@confirm", "@schematics-table.import.confirm", () ->
                FileChooser.open("zip").submit(file -> {
                    try{
                        importSchematicsTable(file, true);
                        ui.showInfoFade("@schematics-table.import.success");
                    }catch(Exception e){
                        Log.err("[eui] schematics-table import error", e);
                        ui.showErrorMessage("Import failed\n" + e.getMessage());
                    }
                })
            );
        }catch(Exception e){
            Log.err("[eui] schematics-table import dialog error", e);
            ui.showErrorMessage("Failed: " + e.getMessage());
        }
    }
}
