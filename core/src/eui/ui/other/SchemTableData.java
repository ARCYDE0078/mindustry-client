package eui.ui.other;

import arc.Core;
import arc.struct.IntMap;
import arc.struct.IntSeq;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Nullable;
import arc.util.serialization.Jval;

/**
 * Модель "Таблицы схем 2.0" + её персистентность. Вся конфигурация таблицы (страницы со СВОИМИ
 * размерами, ячейки со схемой/ротацией/подписью/иконками, группы ячеек) хранится ОДНИМ Jval-JSON
 * документом в ключе {@link #SETTINGS_KEY} (прецедент: qol-cbinds), а не россыпью строковых ключей,
 * как хранила легаси-таблица ({@code schematic<cat>.<col>.<row>} + {@code ...image} +
 * {@code category<i>name/image}).
 * <p>
 * МИГРАЦИЯ: при первом обращении, если {@link #SETTINGS_KEY} ещё нет, старые ключи автоматически
 * конвертируются ({@link #migrateFromLegacy}): каждая легаси-категория становится страницей текущего
 * глобального размера (число вкладок легаси == слайдеру колонок), найденные вне этих размеров ячейки
 * расширяют страницу (ничего не теряем). Правило для иконок ячейки: одна легаси-иконка -> основная
 * (60% кнопки по центру); 2-4 легаси-иконки -> угловые слоты в порядке ВЛ, ВП, НЛ, НП по 42% - это
 * ровно те же визуальные позиции, что у старой мини-сетки 2x2, так что вид ячеек не меняется.
 * Легаси-ключи после миграции НЕ удаляются - откат на старую сборку ничего не потеряет.
 * <p>
 * Формат документа (version 1):
 * <pre>
 * {"version":1, "pages":[
 *   {"name":"...", "icon":"...", "rows":4, "cols":5,
 *    "cells":[{"row":0,"col":1,"schem":"имя","label":"...","rot":1,
 *              "main":{"n":"иконка","s":60},
 *              "corners":[{"c":0,"n":"иконка","s":42}, ...]}],   // c: 0=ВЛ 1=ВП 2=НЛ 3=НП
 *    "groups":[{"name":"...","cells":[pos,...]}]}]}              // pos = row*100+col
 * </pre>
 */
public class SchemTableData{
    public static final String SETTINGS_KEY = "eui-schemtable2";
    //sonka: подняты с 60/42 - слишком мелко смотрелось по умолчанию (пределы слайдеров - 100/70,
    //поднять дефолт можно было без какой-либо доп. правки диапазона)
    public static final int MAIN_ICON_DEFAULT_SIZE = 85;
    public static final int CORNER_ICON_DEFAULT_SIZE = 55;
    public static final int MAX_ROWS = 30, MAX_COLS = 30;

    public final Seq<Page> pages = new Seq<>();

    private static SchemTableData instance;

    /** Иконка: имя (в терминах {@link eui.icons.Icons#getIconDrawable}) + размер в % от кнопки ячейки. */
    public static class IconRef{
        public String name = "";
        public int size;

        public IconRef(){}

        public IconRef(String name, int size){
            this.name = name;
            this.size = size;
        }

        public IconRef copy(){
            return new IconRef(name, size);
        }
    }

    /** Одна секция radial-меню multi-ячейки: своя схема + своя ротация (та же семантика, что у {@link CellData#rotation}). */
    public static class MultiEntry{
        public String schematic = "";
        public int rotation;

        public MultiEntry(){}

        public MultiEntry(String schematic, int rotation){
            this.schematic = schematic;
            this.rotation = rotation;
        }

        public MultiEntry copy(){
            return new MultiEntry(schematic, rotation);
        }
    }

    public static class CellData{
        public static final int MIN_SECTIONS = 2, MAX_SECTIONS = 8, DEFAULT_SECTIONS = 8;

        public String schematic = "";
        /** Пользовательская подпись ячейки (тултип); пустая - показывается имя схемы. */
        public String label = "";
        /** Сохранённая ротация схемы, N*90° против часовой; применяется при взятии в руку. */
        public int rotation;
        public @Nullable IconRef main;
        /** Угловые иконки: 0=верх-лево, 1=верх-право, 2=низ-лево, 3=низ-право. */
        public final IconRef[] corners = new IconRef[4];

        /**
         * "Несколько схем по кругу" (идея sonka, скрины 2026-09-22): вместо одной схемы ячейка
         * открывает radial-меню на {@link #sections} секций, каждая - своя {@link MultiEntry}
         * (ключ карты - индекс 0..sections-1, дыры допустимы - секция без записи рисуется пустой
         * "+"). Центр колеса - ТА ЖЕ пара {@link #schematic}/{@link #rotation}, что у обычной ячейки
         * (в диалоге редактирования кнопка выбора просто переименовывается в "Сменить центральную
         * схему" - см. {@link eui.ui.other.SchematicsTableUi#buildCellDialogContent}), поэтому
         * отдельного поля под центр не заведено.
         */
        public boolean multi;
        public int sections = DEFAULT_SECTIONS;
        public final IntMap<MultiEntry> multiEntries = new IntMap<>();

        public boolean isEmpty(){
            if(!schematic.isEmpty() || !label.isEmpty() || rotation != 0 || main != null || multi) return false;
            for(IconRef c : corners) if(c != null) return false;
            return true;
        }

        public CellData copy(){
            CellData c = new CellData();
            c.schematic = schematic;
            c.label = label;
            c.rotation = rotation;
            c.main = main == null ? null : main.copy();
            for(int i = 0; i < 4; i++) c.corners[i] = corners[i] == null ? null : corners[i].copy();
            c.multi = multi;
            c.sections = sections;
            for(IntMap.Entry<MultiEntry> e : multiEntries) c.multiEntries.put(e.key, e.value.copy());
            return c;
        }
    }

    /** Группа - сохранённый набор ПОЗИЦИЙ ячеек страницы (см. {@link #pos}); двигается/красится целиком. */
    public static class Group{
        public String name = "";
        public IntSeq cells = new IntSeq();
    }

    public static class Page{
        public String name = "";
        public String icon = "";
        public int rows = 4, cols = 5;
        public IntMap<CellData> cells = new IntMap<>();
        public Seq<Group> groups = new Seq<>();

        public @Nullable CellData cell(int row, int col){
            return cells.get(pos(row, col));
        }

        /** Ячейка для записи - создаётся при отсутствии (пустые вычищаются при сохранении). */
        public CellData cellForWrite(int row, int col){
            int p = pos(row, col);
            CellData c = cells.get(p);
            if(c == null){
                c = new CellData();
                cells.put(p, c);
            }
            return c;
        }

        public void removeCell(int row, int col){
            cells.remove(pos(row, col));
        }

        public @Nullable Group groupOf(int position){
            for(Group g : groups) if(g.cells.contains(position)) return g;
            return null;
        }

        /** Убирает позиции из всех групп (перед включением их в новую); опустевшие группы удаляются. */
        public void ungroupPositions(IntSeq positions){
            for(Group g : groups){
                for(int i = 0; i < positions.size; i++) g.cells.removeValue(positions.get(i));
            }
            groups.removeAll(g -> g.cells.isEmpty());
        }

        public boolean inBounds(int row, int col){
            return row >= 0 && row < rows && col >= 0 && col < cols;
        }
    }

    // ---------------------------------------------------------------- позиции

    public static int pos(int row, int col){
        return row * 100 + col;
    }

    public static int rowOf(int position){
        return position / 100;
    }

    public static int colOf(int position){
        return position % 100;
    }

    // ---------------------------------------------------------------- внутренний буфер обмена

    /** Скопированная ячейка: полный снимок настроек + исходные координаты (для вставки "на то же место"). */
    public static class ClipEntry{
        public final CellData data;
        public final int srcRow, srcCol;

        public ClipEntry(CellData data, int srcRow, int srcCol){
            this.data = data;
            this.srcRow = srcRow;
            this.srcCol = srcCol;
        }
    }

    /** Внутренний буфер (НЕ системный clipboard); живёт между страницами до конца сессии. */
    public static final Seq<ClipEntry> clipboard = new Seq<>();

    // ---------------------------------------------------------------- загрузка/сохранение

    public static SchemTableData get(){
        if(instance == null) instance = load();
        return instance;
    }

    /** Сброс кэша (после импорта таблицы) - следующий {@link #get} перечитает настройки. */
    public static void invalidate(){
        instance = null;
    }

    static SchemTableData load(){
        String raw = Core.settings.getString(SETTINGS_KEY, "");
        if(raw.isEmpty()){
            SchemTableData migrated = migrateFromLegacy();
            migrated.save();
            Log.info("[eui] schem-table 2.0: legacy cells migrated into one '@' JSON document (@ page(s)).", SETTINGS_KEY, migrated.pages.size);
            return migrated;
        }

        try{
            SchemTableData data = fromJson(Jval.read(raw));
            data.ensurePage();
            return data;
        }catch(Throwable t){
            //повреждённый документ: не умираем и не затираем его - работаем с пустой таблицей,
            //бэкап кладём рядом, чтобы можно было спасти вручную
            Log.err("[eui] schem-table 2.0: corrupt " + SETTINGS_KEY + " document, starting empty (backup in " + SETTINGS_KEY + "-corrupt)", t);
            Core.settings.put(SETTINGS_KEY + "-corrupt", raw);
            SchemTableData data = new SchemTableData();
            data.ensurePage();
            return data;
        }
    }

    public void save(){
        prune();
        Core.settings.put(SETTINGS_KEY, toJson().toString());
    }

    /** Пустые ячейки не храним; ссылки групп на несуществующие позиции чистим при сохранении. */
    void prune(){
        for(Page p : pages){
            Seq<Integer> dead = new Seq<>();
            for(IntMap.Entry<CellData> e : p.cells) if(e.value.isEmpty()) dead.add(e.key);
            for(int k : dead) p.cells.remove(k);
            p.groups.removeAll(g -> g.cells.isEmpty());
        }
    }

    public void ensurePage(){
        if(pages.isEmpty()) pages.add(new Page());
    }

    public Jval toJson(){
        Jval root = Jval.newObject();
        root.put("version", 1);
        Jval pagesArr = Jval.newArray();
        for(Page p : pages){
            Jval pj = Jval.newObject();
            pj.put("name", p.name);
            pj.put("icon", p.icon);
            pj.put("rows", p.rows);
            pj.put("cols", p.cols);

            Jval cellsArr = Jval.newArray();
            for(IntMap.Entry<CellData> e : p.cells){
                CellData c = e.value;
                if(c.isEmpty()) continue;
                Jval cj = Jval.newObject();
                cj.put("row", rowOf(e.key));
                cj.put("col", colOf(e.key));
                if(!c.schematic.isEmpty()) cj.put("schem", c.schematic);
                if(!c.label.isEmpty()) cj.put("label", c.label);
                if(c.rotation != 0) cj.put("rot", c.rotation);
                if(c.main != null) cj.put("main", iconJson(c.main, -1));
                Jval cornersArr = Jval.newArray();
                for(int i = 0; i < 4; i++) if(c.corners[i] != null) cornersArr.add(iconJson(c.corners[i], i));
                if(cornersArr.asArray().size > 0) cj.put("corners", cornersArr);
                if(c.multi){
                    cj.put("multi", true);
                    cj.put("sections", c.sections);
                    Jval multiArr = Jval.newArray();
                    for(IntMap.Entry<MultiEntry> me : c.multiEntries){
                        if(me.value.schematic.isEmpty()) continue;
                        Jval mj = Jval.newObject();
                        mj.put("i", me.key);
                        mj.put("n", me.value.schematic);
                        if(me.value.rotation != 0) mj.put("r", me.value.rotation);
                        multiArr.add(mj);
                    }
                    cj.put("msch", multiArr);
                }
                cellsArr.add(cj);
            }
            pj.put("cells", cellsArr);

            Jval groupsArr = Jval.newArray();
            for(Group g : p.groups){
                Jval gj = Jval.newObject();
                gj.put("name", g.name);
                Jval members = Jval.newArray();
                for(int i = 0; i < g.cells.size; i++) members.add(Jval.valueOf(g.cells.get(i)));
                gj.put("cells", members);
                groupsArr.add(gj);
            }
            pj.put("groups", groupsArr);

            pagesArr.add(pj);
        }
        root.put("pages", pagesArr);
        return root;
    }

    static Jval iconJson(IconRef icon, int corner){
        Jval j = Jval.newObject();
        if(corner >= 0) j.put("c", corner);
        j.put("n", icon.name);
        j.put("s", icon.size);
        return j;
    }

    public static SchemTableData fromJson(Jval root){
        SchemTableData data = new SchemTableData();
        if(!root.has("pages")) return data;

        for(Jval pj : root.get("pages").asArray()){
            Page p = new Page();
            p.name = pj.getString("name", "");
            p.icon = pj.getString("icon", "");
            p.rows = clampSize(pj.getInt("rows", 4), MAX_ROWS);
            p.cols = clampSize(pj.getInt("cols", 5), MAX_COLS);

            if(pj.has("cells")){
                for(Jval cj : pj.get("cells").asArray()){
                    int row = cj.getInt("row", 0), col = cj.getInt("col", 0);
                    if(row < 0 || col < 0 || row >= MAX_ROWS || col >= MAX_COLS) continue;
                    CellData c = new CellData();
                    c.schematic = cj.getString("schem", "");
                    c.label = cj.getString("label", "");
                    c.rotation = ((cj.getInt("rot", 0) % 4) + 4) % 4;
                    if(cj.has("main")){
                        Jval mj = cj.get("main");
                        c.main = new IconRef(mj.getString("n", ""), mj.getInt("s", MAIN_ICON_DEFAULT_SIZE));
                    }
                    if(cj.has("corners")){
                        for(Jval kj : cj.get("corners").asArray()){
                            int corner = kj.getInt("c", 0);
                            if(corner < 0 || corner > 3) continue;
                            c.corners[corner] = new IconRef(kj.getString("n", ""), kj.getInt("s", CORNER_ICON_DEFAULT_SIZE));
                        }
                    }
                    c.multi = cj.getBool("multi", false);
                    c.sections = clamp(cj.getInt("sections", CellData.DEFAULT_SECTIONS), CellData.MIN_SECTIONS, CellData.MAX_SECTIONS);
                    if(cj.has("msch")){
                        for(Jval mj : cj.get("msch").asArray()){
                            int idx = mj.getInt("i", -1);
                            String name = mj.getString("n", "");
                            if(idx < 0 || name.isEmpty()) continue;
                            c.multiEntries.put(idx, new MultiEntry(name, ((mj.getInt("r", 0) % 4) + 4) % 4));
                        }
                    }
                    if(!c.isEmpty()){
                        p.cells.put(pos(row, col), c);
                        //ячейка за пределами объявленного размера страницу растягивает, а не теряется
                        if(row >= p.rows) p.rows = Math.min(row + 1, MAX_ROWS);
                        if(col >= p.cols) p.cols = Math.min(col + 1, MAX_COLS);
                    }
                }
            }

            if(pj.has("groups")){
                for(Jval gj : pj.get("groups").asArray()){
                    Group g = new Group();
                    g.name = gj.getString("name", "");
                    if(gj.has("cells")) for(Jval m : gj.get("cells").asArray()) g.cells.add(m.asInt());
                    if(!g.cells.isEmpty()) p.groups.add(g);
                }
            }

            data.pages.add(p);
        }
        return data;
    }

    static int clampSize(int v, int max){
        return Math.max(1, Math.min(v, max));
    }

    static int clamp(int v, int min, int max){
        return Math.max(min, Math.min(v, max));
    }

    // ---------------------------------------------------------------- миграция легаси-ключей

    /** Границы сканирования легаси-ключей: чуть шире максимумов старых слайдеров (ряды до 20, колонки/вкладки до 16), чтобы подобрать и ячейки, сохранённые при больших прошлых размерах. */
    static final int LEGACY_SCAN_CATS = 16, LEGACY_SCAN_ROWS = 24, LEGACY_SCAN_COLS = 16;

    static SchemTableData migrateFromLegacy(){
        SchemTableData data = new SchemTableData();
        int rows = Core.settings.getInt("eui-SchematicsTableRows", 4);
        int cols = Core.settings.getInt("eui-SchematicsTableColumns", 5);
        //легаси-инвариант: число вкладок-категорий == слайдеру колонок
        int pageCount = Math.max(1, Math.min(cols, LEGACY_SCAN_CATS));

        for(int cat = 0; cat < LEGACY_SCAN_CATS; cat++){
            Page p = new Page();
            p.rows = clampSize(rows, MAX_ROWS);
            p.cols = clampSize(cols, MAX_COLS);
            p.name = Core.settings.getString("category" + cat + "name", "");
            p.icon = Core.settings.getString("category" + cat + "image", "");

            for(int row = 0; row < LEGACY_SCAN_ROWS; row++){
                for(int col = 0; col < LEGACY_SCAN_COLS; col++){
                    String key = "schematic" + cat + "." + col + "." + row;
                    String schematicName = Core.settings.getString(key, "");
                    Seq<String> icons = legacyImageNames(key + "image");
                    if(schematicName.isEmpty() && icons.isEmpty()) continue;

                    CellData c = new CellData();
                    c.schematic = schematicName;
                    if(icons.size == 1){
                        c.main = new IconRef(icons.first(), MAIN_ICON_DEFAULT_SIZE);
                    }else{
                        for(int i = 0; i < Math.min(icons.size, 4); i++){
                            c.corners[i] = new IconRef(icons.get(i), CORNER_ICON_DEFAULT_SIZE);
                        }
                    }
                    p.cells.put(pos(row, col), c);
                    if(row >= p.rows) p.rows = Math.min(row + 1, MAX_ROWS);
                    if(col >= p.cols) p.cols = Math.min(col + 1, MAX_COLS);
                }
            }

            //страницы в пределах легаси-числа вкладок сохраняем даже пустыми (пользователь их видел);
            //найденные ЗА ним (данные от старых больших размеров) - только непустые
            if(cat < pageCount || !p.cells.isEmpty() || !p.name.isEmpty() || !p.icon.isEmpty()){
                while(data.pages.size < cat) data.pages.add(emptyLegacyPage(rows, cols));
                data.pages.add(p);
            }
        }

        data.ensurePage();
        return data;
    }

    static Page emptyLegacyPage(int rows, int cols){
        Page p = new Page();
        p.rows = clampSize(rows, MAX_ROWS);
        p.cols = clampSize(cols, MAX_COLS);
        return p;
    }

    /** Копия легаси-парсера списка иконок (запятая-разделитель, одиночное имя = список из одного). */
    static Seq<String> legacyImageNames(String settingKey){
        String raw = Core.settings.getString(settingKey, "");
        Seq<String> names = new Seq<>();
        if(raw.isEmpty()) return names;
        for(String s : raw.split(",")) if(!s.isEmpty()) names.add(s);
        return names;
    }
}
