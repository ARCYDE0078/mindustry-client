package eui.ui.other;

import arc.Core;
import arc.Events;
import arc.func.Boolf;
import arc.func.Cons;
import arc.func.Intc;
import arc.func.Intp;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.TextureRegion;
import arc.input.KeyCode;
import arc.math.Mathf;
import arc.scene.Element;
import arc.scene.event.InputEvent;
import arc.scene.event.InputListener;
import arc.scene.event.Touchable;
import arc.scene.style.Drawable;
import arc.scene.style.TextureRegionDrawable;
import arc.scene.ui.Button;
import arc.scene.ui.Image;
import arc.scene.ui.ScrollPane;
import arc.scene.ui.TextField;
import arc.scene.ui.layout.Cell;
import arc.scene.ui.layout.Table;
import arc.scene.ui.layout.WidgetGroup;
import arc.struct.IntIntMap;
import arc.struct.IntMap;
import arc.struct.IntSeq;
import arc.struct.ObjectIntMap;
import arc.struct.ObjectSet;
import arc.struct.Seq;
import arc.util.Align;
import arc.util.Interval;
import arc.util.Log;
import arc.util.Time;
import eui.icons.IconCategoriesConfig;
import eui.icons.IconCategoriesConfig.Category;
import eui.icons.Icons;
import eui.input.EuiBinding;
import eui.ui.other.SchemTableData.CellData;
import eui.ui.other.SchemTableData.ClipEntry;
import eui.ui.other.SchemTableData.Group;
import eui.ui.other.SchemTableData.IconRef;
import eui.ui.other.SchemTableData.MultiEntry;
import eui.ui.other.SchemTableData.Page;
import mindustry.content.Blocks;
import mindustry.game.EventType.ClientLoadEvent;
import mindustry.game.EventType.Trigger;
import mindustry.game.Schematic;
import mindustry.gen.Icon;
import mindustry.gen.Tex;
import mindustry.graphics.Pal;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.BaseDialog;
import mindustry.ui.dialogs.SchematicsDialog.SchematicImage;

import java.util.Comparator;

import static mindustry.Vars.control;
import static mindustry.Vars.mobile;
import static mindustry.Vars.player;
import static mindustry.Vars.schematics;
import static mindustry.Vars.state;
import static mindustry.Vars.ui;

/**
 * "Таблица схем 2.0" (идея sonka): сетка быстрого доступа к схемам у миникарты, выросшая из
 * eui-ShowSchematicsTable. Три больших надстройки над портированной версией:
 * <ul>
 * <li><b>Страницы фиксированного размера</b> - вкладки-категории стали страницами, у КАЖДОЙ свой
 * размер (ряды x колонны, правится в диалоге страницы по ПКМ на вкладке/названии); хранение переехало
 * из россыпи строковых ключей в один Jval-документ ({@link SchemTableData}, там же автомиграция
 * старых сохранений).</li>
 * <li><b>Бинды G+две цифры</b> - лидер-клавиша ({@link EuiBinding#schemTableLeader}, перебиндиваемая),
 * затем две цифры подряд = взять схему ячейки активной страницы в руку (с сохранённой ротацией);
 * порядок цифр ряд/колонна настраивается ("eui-SchemTableColFirst"). Цифры читаются ТОЛЬКО внутри
 * ~600мс цепочки, ваниль-хотбар цифр на это время глушится (гейт в PlacementFragment.gridUpdate,
 * прецедент - гейт Helium там же), в командном режиме цепочка не стартует (там G = select-all-units,
 * а цифры = контрол-группы).</li>
 * <li><b>Режим редактирования</b> (кнопка-карандаш): выделение кликом/рамкой, группы ячеек,
 * перетаскивание ячейки/группы, ротация, очистка, внутренний буфер обмена с выборочной вставкой
 * свойств и умной групповой вставкой, назначение иконки всей группе, паттерны названий с
 * автонумерацией, у ячейки 1 основная + до 4 угловых иконок с индивидуальными размерами
 * (рисуются {@link CellIconsElement}).</li>
 * </ul>
 * Вне режима редактирования поведение прежнее: клик берёт схему в руку, drag-swap меняет ячейки
 * местами, ячейки подкрашены градиентом доступности по ресурсам ядра. История rhino-портирования и
 * старых обходов - в javadoc {@link eui.EUIMod}; здесь остался только их конечный смысл.
 */
public class SchematicsTableUi{
    private static final int SCHEMATIC_PICKER_ICON_SIZE = 140;
    private static final int SCHEMATIC_PICKER_CARD_WIDTH = SCHEMATIC_PICKER_ICON_SIZE + 16;
    private static final int SCHEMATIC_PICKER_CARD_PAD = 4;
    private static final int SCHEMATIC_PICKER_SLOT_WIDTH = SCHEMATIC_PICKER_CARD_WIDTH + SCHEMATIC_PICKER_CARD_PAD * 2;

    private static final long CHORD_TIMEOUT_MS = 600;

    private static SchematicsTableUi instance;

    private boolean built = false;
    private Table contentTable;
    private Table previewTable;

    private int currentPage = 0;
    private int lastPage = 0;

    private int schematicButtonSize;
    private int categoryButtonSize;
    private Integer oldSize;

    private Schematic hovered;

    /* перф: слот 0 - опрос layout-настроек раз в полсекунды вместо каждого кадра; слот 1 - редкий
     * пере-снимок player.core() для живущего превью (см. update) */
    private final Interval settingsPoll = new Interval(2);
    private boolean settingsPolled = false;
    private int posOffsetX, posOffsetY;
    /** какую схему сейчас показывает превью - перестраиваем его только при смене (образец: BlockInfoUi) */
    private Schematic lastHoveredPreview;

    // ---- режим редактирования ----
    private boolean editMode = false;
    /** выделенные позиции активной страницы (кодировка {@link SchemTableData#pos}) */
    private IntSeq selection = new IntSeq();
    /** позиции, которые сейчас тащим (ячейка/группа/выделение); null - перенос не идёт */
    private IntSeq moveSet;
    private int movePressPos = -1, moveHoverPos = -1;
    private boolean bandActive = false;
    private int bandAnchor = -1, bandHover = -1;

    //настройки выборочной вставки из внутреннего буфера (живут до конца сессии)
    private static boolean pasteSchematic = true, pasteRotation = true, pasteLabel = true, pasteMainIcon = true, pasteCornerIcons = true;
    //настройки массового импорта по тегу (живут до конца сессии, как и paste-флаги выше)
    private static boolean importTagNewPage = false, importTagSkipPresent = true;

    // ---- цепочка G+цифры ----
    private int chordStage = 0; //0 - нет, 1 - ждём первую цифру, 2 - вторую
    private int chordFirst = -1;
    private long chordStartMs;
    private long chordDigitFrame = -1;

    private static final KeyCode[] DIGIT_MAIN = {KeyCode.num0, KeyCode.num1, KeyCode.num2, KeyCode.num3, KeyCode.num4, KeyCode.num5, KeyCode.num6, KeyCode.num7, KeyCode.num8, KeyCode.num9};
    private static final KeyCode[] DIGIT_PAD = {KeyCode.numpad0, KeyCode.numpad1, KeyCode.numpad2, KeyCode.numpad3, KeyCode.numpad4, KeyCode.numpad5, KeyCode.numpad6, KeyCode.numpad7, KeyCode.numpad8, KeyCode.numpad9};

    /** подкраска НЕвыделенных ячеек групп в режиме редактирования, цвет = индекс группы по кругу */
    private static final Color[] GROUP_COLORS = {
        Color.valueOf("b8f4b0"), Color.valueOf("f4ddb0"), Color.valueOf("b0d8f4"),
        Color.valueOf("f4b0e6"), Color.valueOf("d9b0f4"), Color.valueOf("f4f0b0")
    };

    private static class CellRef{
        final int row, col;
        CellRef(int row, int col){ this.row = row; this.col = col; }
        boolean is(int r, int c){ return row == r && col == c; }
    }

    private static class CellButton{
        final Button btn;
        final int row, col;
        CellButton(Button btn, int row, int col){ this.btn = btn; this.row = row; this.col = col; }
    }

    private Seq<CellButton> cellButtons = new Seq<>();
    private CellRef dragSource;
    private CellRef dragHoverTarget;

    private static class SchematicEntry{
        final Schematic schematic;
        final String name;
        SchematicEntry(Schematic schematic, String name){ this.schematic = schematic; this.name = name; }
    }

    private BaseDialog schematicPickerDialog;
    private Table schematicPickerList;
    private TextField schematicPickerSearchField;
    private Cell<ScrollPane> schematicPickerPaneCell;
    private Seq<SchematicEntry> allSchematicEntries = new Seq<>();
    private Cons<String> pickerOnPicked;
    private int pickerColumns = 3;

    //mobile double-tap-to-edit tracking
    private String lastTapped;
    private long lastTapTimeMs;

    //shared scroll-position buffer for the icon-picker pane (ModsDialog.java-style: one dialog, reused across opens)
    private float iconScrollY = 0;

    public SchematicsTableUi(){
        instance = this;

        Events.on(ClientLoadEvent.class, e -> {
            ui.hudGroup.fill(null, t -> {
                previewTable = t.table(sonkaextras.UiStyle.windowBg()).get();
                previewTable.visibility = this::previewTableVisibility;
                previewTable.update(() -> previewTable.color.a = Core.settings.getInt("eui-SchematicsTableAlpha", 100) / 100f);
                t.center();
                t.pack();
            });

            schematicPickerDialog = new BaseDialog(Core.bundle.get("schematics-table.dialog.pick-schematic.title"));
            schematicPickerDialog.addCloseButton();

            Table searchRow = new Table();
            searchRow.add(Core.bundle.get("schematics-table.dialog.pick-schematic.search") + ":").padRight(6f);
            schematicPickerSearchField = searchRow.field("", this::refreshSchematicPickerList).growX().get();
            schematicPickerDialog.cont.add(searchRow).growX().pad(4f).row();

            schematicPickerList = new Table();
            schematicPickerPaneCell = schematicPickerDialog.cont.pane(schematicPickerList).scrollX(false)
                .width(SCHEMATIC_PICKER_SLOT_WIDTH * pickerColumns).height(560f);
            schematicPickerPaneCell.row();
        });

        Events.run(Trigger.update, this::update);
    }

    // ---------------------------------------------------------------- модель

    SchemTableData data(){
        return SchemTableData.get();
    }

    Page page(){
        SchemTableData d = data();
        d.ensurePage();
        if(currentPage >= d.pages.size || currentPage < 0) currentPage = 0;
        return d.pages.get(currentPage);
    }

    void update(){
        if(radialOverlay != null && Core.input.keyTap(KeyCode.escape)) closeRadialPicker();

        if(!Core.settings.getBool("eui-ShowSchematicsTable", true)){
            if(built) clearTable();
            chordStage = 0;
            return;
        }

        if(!settingsPolled || settingsPoll.get(0, 30f)){
            settingsPolled = true;
            migrateButtonSizeDefault();
            schematicButtonSize = Core.settings.getInt("eui-SchematicsTableButtonSize", 48);
            categoryButtonSize = schematicButtonSize + 2;
            posOffsetX = clamp(parseIntSetting("eui-SchematicsTableX", 10), 0, 5000);
            posOffsetY = clamp(parseIntSetting("eui-SchematicsTableY", 160), 0, 5000);
        }

        if(contentTable == null) setMarker();
        if(isRebuildNeeded()) rebuildTable();

        updatePosition();

        if(radialOverlay == null) pollChord();

        if(hovered != null && contentTable.hasMouse()){
            //перф: контент превью статичен по идентичности схемы (лейблы требований - живые супплаеры),
            //перестраивать каждый кадр незачем; редкий рефреш (слот 1) пере-снимает player.core(),
            //чтобы смена/потеря ядра при долгом наведении отражалась как раньше
            if(hovered != lastHoveredPreview || settingsPoll.get(1, 60f)){
                lastHoveredPreview = hovered;
                rebuildPreviewTable();
            }
        }else{
            hovered = null;
            lastHoveredPreview = null;
        }
    }

    private boolean buttonSizeMigrationChecked = false;

    /**
     * sonka 2026-08-21: одноразовый апгрейд дефолта 30→48 (см. коммит про schematicButtonSize) для
     * УЖЕ существующих сохранений. Проблема: {@code SliderSetting.add()} (mindustry.ui.dialogs.
     * SettingsMenuDialog) дергает {@code slider.change()} сразу при ПОСТРОЕНИИ виджета настроек, а
     * колбэк слайдера безусловно пишет {@code settings.put(name, value)} - то есть просто открыть
     * вкладку настроек мода (ничего не двигая) уже молча сохраняет старый дефолт 30 на диск. После
     * этого явно сохранённое значение навсегда перекрывает новый дефолт в {@code getInt(key, 48)} -
     * поэтому чистая правка дефолта в коде не помогает тем, кто хоть раз открывал настройки.
     * Флаг-метка гарантирует, что бамп применится РОВНО один раз - если пользователь потом сам
     * уменьшит слайдер, повторно мы это не перезапишем.
     */
    void migrateButtonSizeDefault(){
        if(buttonSizeMigrationChecked) return;
        buttonSizeMigrationChecked = true;
        if(Core.settings.getBool("eui-SchemTableButtonSize48Migrated", false)) return;
        Core.settings.put("eui-SchemTableButtonSize48Migrated", true);
        if(Core.settings.getInt("eui-SchematicsTableButtonSize", 48) < 48){
            Core.settings.putInt("eui-SchematicsTableButtonSize", 48);
        }
    }

    // ---------------------------------------------------------------- цепочка G + две цифры

    /**
     * true, пока цифры принадлежат цепочке таблицы схем - PlacementFragment.gridUpdate в это время
     * пропускает ваниль-комбо "категория+блок" на цифрах (тот же приём, что гейт Helium там же).
     * Кадр, в котором цепочка употребила цифру, тоже накрыт (chordDigitFrame): порядок обработчиков
     * внутри кадра не гарантирован, без этого завершающая цифра могла бы утечь в хотбар.
     */
    public static boolean digitChordActive(){
        SchematicsTableUi in = instance;
        if(in == null) return false;
        return in.chordStage > 0 || in.radialOverlay != null || (in.chordDigitFrame >= 0 && Core.graphics.getFrameId() == in.chordDigitFrame);
    }

    void pollChord(){
        if(mobile) return;
        if(chordStage > 0 && Time.timeSinceMillis(chordStartMs) > CHORD_TIMEOUT_MS) chordStage = 0;

        //в командном режиме не стартуем и рвём цепочку: G там = select-all-units, цифры = контрол-группы
        if(!state.isGame() || state.rules.editor || !built || contentTable == null || !contentTable.visible
            || Core.scene.hasDialog() || Core.scene.hasField() || control.input == null || control.input.commandMode){
            chordStage = 0;
            return;
        }

        if(Core.input.keyTap(EuiBinding.schemTableLeader)){
            chordStage = 1;
            chordFirst = -1;
            chordStartMs = Time.millis();
            return;
        }

        if(chordStage == 0) return;

        int digit = tappedDigit();
        if(digit < 0) return;

        chordDigitFrame = Core.graphics.getFrameId();
        chordStartMs = Time.millis();
        if(chordStage == 1){
            chordFirst = digit;
            chordStage = 2;
            return;
        }
        chordStage = 0;
        resolveChord(chordFirst, digit);
    }

    static int tappedDigit(){
        for(int d = 0; d < 10; d++){
            if(Core.input.keyTap(DIGIT_MAIN[d]) || Core.input.keyTap(DIGIT_PAD[d])) return d;
        }
        return -1;
    }

    /** цифры 1..9 = индексы 1..9, цифра 0 = десятый ряд/колонна */
    static int digitIndex(int d){
        return d == 0 ? 9 : d - 1;
    }

    static int displayDigit(int d){
        return d == 0 ? 10 : d;
    }

    void resolveChord(int d1, int d2){
        boolean colFirst = Core.settings.getBool("eui-SchemTableColFirst", false);
        int first = digitIndex(d1), second = digitIndex(d2);
        int row = colFirst ? second : first;
        int col = colFirst ? first : second;

        Page p = page();
        if(!p.inBounds(row, col)){
            ui.announce(Core.bundle.format("schematics-table.chord.out-of-range", (row + 1) + "/" + (col + 1)), 2f);
            return;
        }
        CellData c = p.cell(row, col);
        if(c != null && c.multi){
            openRadialPicker(c);
            return;
        }
        Schematic s = c == null ? null : findSchematic(c.schematic);
        if(s == null){
            ui.announce(Core.bundle.get("schematics-table.chord.empty-cell"), 2f);
            return;
        }
        useCell(c, s);
    }

    /** Точка входа и для клика по ячейке, и для чорда: multi-ячейка открывает radial-меню вместо немедленного взятия схемы в руку. */
    void activateCell(CellData c, Schematic s){
        if(c != null && c.multi){
            openRadialPicker(c);
        }else if(s != null){
            useCell(c, s);
        }
    }

    /** Взять схему ячейки в руку с её сохранённой ротацией (тот же путь, что ручной клик + N раз "повернуть"). */
    void useCell(CellData c, Schematic s){
        placeSchematic(s, c == null ? 0 : c.rotation);
    }

    void placeSchematic(Schematic s, int rotation){
        control.input.useSchematic(s);
        int rot = ((rotation % 4) + 4) % 4;
        for(int i = 0; i < rot; i++){
            control.input.rotatePlans(control.input.selectPlans, 1);
        }
    }

    // ---------------------------------------------------------------- table build/layout

    void setMarker(){
        //фон тела панели - из единого style-гайда (тот же black3)
        contentTable = new Table(sonkaextras.UiStyle.windowBg());
        contentTable.name = "eui-schematics-table";
        contentTable.visibility = () -> built && ui.hudfrag.shown;

        ui.hudGroup.addChild(contentTable);
        contentTable.toBack(); //behind the placement menu (PlacementFragment), never on top of it

        updatePosition();
        contentTable.pack();
    }

    void updatePosition(){
        if(contentTable == null) return;

        //перф: offsetX/Y кэшируются в update() (settingsPoll), тут только позиционирование
        contentTable.setPosition(Core.graphics.getWidth() - posOffsetX, Core.graphics.getHeight() - posOffsetY, Align.topRight);
    }

    /** Reads a setting that may be stored as either an int or a string - shares the same "eui-SchematicsTableX/Y" settings keys an existing install may already have on disk. */
    static int parseIntSetting(String key, int def){
        Object raw = Core.settings.get(key, def);
        if(raw instanceof Integer i) return i;
        if(raw instanceof String s){
            try{
                return Integer.parseInt(s.trim());
            }catch(NumberFormatException ignored){
                return def;
            }
        }
        return def;
    }

    static int clamp(int value, int min, int max){
        return Math.max(min, Math.min(value, max));
    }

    static int parseIntOr(String s, int def){
        try{
            return Integer.parseInt(s.trim());
        }catch(Exception e){
            return def;
        }
    }

    boolean isRebuildNeeded(){
        if(!built) return true;

        if(oldSize == null) oldSize = schematicButtonSize;
        if(schematicButtonSize != oldSize){
            oldSize = schematicButtonSize;
            return true;
        }

        if(lastPage != currentPage){
            lastPage = currentPage;
            return true;
        }

        return false;
    }

    void rebuildTable(){
        if(contentTable == null) return;
        clearTable();
        buildTable();
    }

    void buildTable(){
        if(contentTable == null) return;

        SchemTableData d = data();
        Page page = page();

        cellButtons = new Seq<>();
        dragSource = null;
        dragHoverTarget = null;
        resetEditTransients();

        Table wrapped = contentTable.table().margin(3f).get();

        //---- вкладки страниц ----
        Table tabsTable = wrapped.table().get();
        int perRow = Math.max(4, page.cols);
        int shown = 0;
        for(int i = 0; i < d.pages.size; i++){
            int index = i;
            Page tp = d.pages.get(i);
            var tabButton = tabsTable.button(pageIcon(tp), Styles.clearTogglei, () -> {
                    if(currentPage != index){
                        currentPage = index;
                        selection.clear();
                    }
                })
                .update(b -> b.setChecked(currentPage == index)).width(categoryButtonSize).height(categoryButtonSize)
                .tooltip(pageTooltip(tp, index)).get();
            tabButton.resizeImage(categoryButtonSize * 0.8f);

            if(!mobile){
                tabButton.clicked(KeyCode.mouseRight, () -> showPageDialog(index));
            }else{
                tabButton.clicked(() -> {
                    if(mobileDoubleTap("page" + index)) showPageDialog(index);
                });
            }
            if(++shown % perRow == 0) tabsTable.row();
        }
        if(editMode){
            tabsTable.button(Icon.addSmall, Styles.clearNonei, this::addPage)
                .width(categoryButtonSize).height(categoryButtonSize)
                .tooltip(Core.bundle.get("schematics-table.edit.add-page"));
        }

        //---- строка названия страницы + карандаш ----
        wrapped.row();
        Table labelRow = wrapped.table().get();
        float labelWidth = Math.max(categoryButtonSize * (float)page.cols - categoryButtonSize, 80f);
        var pageLabel = labelRow.labelWrap("").width(labelWidth).padTop(6f).padBottom(6f).get();
        pageLabel.setAlignment(Align.center);
        pageLabel.update(() -> pageLabel.setText(pageLabelText()));
        if(!mobile) pageLabel.clicked(KeyCode.mouseRight, () -> showPageDialog(currentPage));
        labelRow.button(Icon.pencilSmall, Styles.clearTogglei, this::toggleEditMode)
            .update(b -> b.setChecked(editMode)).size(categoryButtonSize)
            .tooltip(Core.bundle.get("schematics-table.edit.tooltip"));

        //---- панель операций режима редактирования ----
        if(editMode){
            wrapped.row();
            Table toolbar = wrapped.table().get();
            int[] toolCount = {0};
            int toolsPerRow = Math.max(5, page.cols);
            Runnable rowBreak = () -> {
                if(++toolCount[0] % toolsPerRow == 0) toolbar.row();
            };
            addToolButton(toolbar, Icon.cancelSmall, "deselect", () -> selection.clear(), rowBreak);
            addToolButton(toolbar, Icon.layersSmall, "group", this::groupSelection, rowBreak);
            addToolButton(toolbar, Icon.gridSmall, "ungroup", this::ungroupSelection, rowBreak);
            addToolButton(toolbar, Icon.rotateSmall, "rotate", this::rotateSelection, rowBreak);
            addToolButton(toolbar, Icon.tagSmall, "names", this::showNamesDialog, rowBreak);
            addToolButton(toolbar, Icon.imageSmall, "set-icon", this::setIconForSelection, rowBreak);
            addToolButton(toolbar, Icon.refreshSmall, "auto-icons", this::autoIconsForSelection, rowBreak);
            addToolButton(toolbar, Icon.downloadSmall, "import-tag", this::showTagImportDialog, rowBreak);
            addToolButton(toolbar, Icon.copySmall, "copy", this::copySelection, rowBreak);
            addToolButton(toolbar, Icon.pasteSmall, "paste", this::showPasteDialog, rowBreak);
            addToolButton(toolbar, Icon.trashSmall, "clear", this::clearSelectionCells, rowBreak);
        }

        //---- сетка ячеек ----
        wrapped.row();
        Table schematicButtonsTable = wrapped.table().get();
        for(int i = 0; i < page.rows; i++){
            int row = i;
            for(int j = 0; j < page.cols; j++){
                int col = j;
                CellData cellData = page.cell(row, col);
                Schematic schematic = cellData == null ? null : findSchematic(cellData.schematic);

                //built manually (new Button + Table.add) - proven simple and correct since the port
                Button btn = new Button(Styles.defaulti);
                //sonka 2026-08-21: НАЙДЕНА настоящая причина "мелких иконок" (диагностический лог
                //это подтвердил: elW/H=13 при заявленной кнопке 48!) - Table.setBackground()
                //(вызывается конструктором Button) автоматически выставляет margin ПО ВСТРОЕННЫМ
                //отступам NinePatch-фона стиля Styles.defaulti ("button" - декоративная рамка под
                //обычные текстовые кнопки, ~17-18px на сторону при их UI-масштабе), а не по размеру
                //самой кнопки. Вся наша многодневная возня с frac/буст/дефолтным размером ячейки
                //рисовала идеальные 85-98% от ЭТОЙ урезанной области - формула была верна с самого
                //начала, просто область для неё была в разы меньше кнопки. Фиксированный маленький
                //margin вместо авто-производного от фона - и иконка получает почти всю клетку.
                //3f после первого фикса вплотную упирался в рамку ячейки (98%-иконка перекрывала
                //декоративную кайму) - 6f даёт зазор, чтобы рамка оставалась видна по контуру.
                btn.margin(6f);

                try{
                    buildCellContent(btn, cellData);
                }catch(Throwable t){
                    Log.err("[eui] schematics-table: buildCellContent failed at " + row + "/" + col + ", falling back to empty icon", t);
                    btn.clearChildren();
                    btn.image(defaultSchematicImage()).size(schematicButtonSize * 0.6f);
                }

                var cellButton = schematicButtonsTable.add(btn).update(b -> {
                    b.setDisabled(false);
                    b.color.set(cellColor(row, col, schematic));
                }).width(schematicButtonSize).height(schematicButtonSize).pad(1f).tooltip(cellTooltip(cellData, schematic)).get();

                cellButton.hovered(() -> hovered = schematic);
                if(!mobile){
                    cellButton.clicked(KeyCode.mouseRight, () -> showCellDialog(row, col));
                }else{
                    String tapKey = "cell" + currentPage + "." + row + "." + col;
                    cellButton.clicked(() -> {
                        if(mobileDoubleTap(tapKey)) showCellDialog(row, col);
                    });
                }

                if(editMode){
                    attachCellEdit(btn, row, col);
                }else{
                    CellData finalCell = cellData;
                    Schematic finalSchematic = schematic;
                    btn.clicked(() -> activateCell(finalCell, finalSchematic));
                }

                cellButtons.add(new CellButton(btn, row, col));
            }
            schematicButtonsTable.row();
        }

        contentTable.pack();
        built = true;
    }

    void addToolButton(Table toolbar, Drawable icon, String key, Runnable action, Runnable rowBreak){
        toolbar.button(icon, Styles.clearNonei, action)
            .width(categoryButtonSize).height(categoryButtonSize)
            .tooltip(Core.bundle.get("schematics-table.edit." + key));
        rowBreak.run();
    }

    void clearTable(){
        if(!built || contentTable == null) return;

        ui.hudGroup.removeChild(contentTable);
        contentTable = null;
        built = false;
        cellButtons = new Seq<>();
        dragSource = null;
        dragHoverTarget = null;
        resetEditTransients();
    }

    void toggleEditMode(){
        editMode = !editMode;
        selection.clear();
        resetEditTransients();
        rebuildTable();
    }

    String pageLabelText(){
        if(chordStage > 0){
            boolean colFirst = Core.settings.getBool("eui-SchemTableColFirst", false);
            String first = Core.bundle.get(colFirst ? "schematics-table.chord.col" : "schematics-table.chord.row");
            String second = Core.bundle.get(colFirst ? "schematics-table.chord.row" : "schematics-table.chord.col");
            if(chordStage == 1) return "[accent]G: " + first + "?";
            return "[accent]G: " + first + " " + displayDigit(chordFirst) + ", " + second + "?";
        }
        Page p = page();
        if(!p.name.isEmpty()) return p.name;
        return Core.bundle.format("schematics-table.page.default-name", currentPage + 1);
    }

    String pageTooltip(Page p, int index){
        return !p.name.isEmpty() ? p.name : Core.bundle.format("schematics-table.page.default-name", index + 1);
    }

    Drawable pageIcon(Page p){
        try{
            if(p.icon.isEmpty()) return defaultSchematicImage();
            Drawable drawable = Icons.getIconDrawable(p.icon);
            return drawable != null ? drawable : defaultSchematicImage();
        }catch(Throwable t){
            return defaultSchematicImage();
        }
    }

    void addPage(){
        SchemTableData d = data();
        Page p = new Page();
        p.rows = clamp(Core.settings.getInt("eui-SchematicsTableRows", 4), 1, SchemTableData.MAX_ROWS);
        p.cols = clamp(Core.settings.getInt("eui-SchematicsTableColumns", 5), 1, SchemTableData.MAX_COLS);
        d.pages.add(p);
        currentPage = d.pages.size - 1;
        selection.clear();
        d.save();
        rebuildTable();
    }

    // ---------------------------------------------------------------- отрисовка ячейки

    /**
     * Иконки ячейки: 1 основная по центру + до 4 угловых, у каждой свой размер в % от кнопки. Углы -
     * это ровно позиции старой мини-сетки 2x2 (ВЛ, ВП, НЛ, НП), поэтому мигрированные ячейки выглядят
     * как раньше. Ненулевая ротация схемы показывается маленькой стрелкой снизу по центру,
     * повёрнутой на N x 90 (тот же смысл, что у rotatePlans).
     */
    static class CellIconsElement extends Element{
        Drawable main;
        float mainFrac = 0.6f;
        /** true - иконка БЛОКА (в спрайте block.uiIcon заложен отступ, который компенсируем бустом) - см. {@link Icons#isBlockIcon}; предметы/юниты/жидкости/значки - false. */
        boolean mainBoost;
        /** true - значок из {@code Icon.icons} (глиф шрифта) - рисуется через {@link #drawIcon}, см. почему. */
        boolean mainGlyph;
        final Drawable[] corners = new Drawable[4];
        final float[] cornerFracs = new float[4];
        final boolean[] cornerBoost = new boolean[4];
        final boolean[] cornerGlyph = new boolean[4];
        int rotation;

        /**
         * sonka 2026-08-22: "эти иконки не меняют размер, если их несколько в ячейке" - после
         * ТРЁХ правок констант/классификации без эффекта выяснилось по исходнику
         * {@code Fonts.getGlyph}: drawable значка ({@code Icon.*}) переопределяет
         * {@code draw(x, y, width, height)} и ИГНОРИРУЕТ переданные width/height - всегда рисует
         * глиф его родным размером {@code g.width x g.height}, лишь центрируя в заданном
         * прямоугольнике (в ванили это by design: размер {@code Icon.*} фиксирован, для мелких
         * есть отдельные {@code *Small}). Поэтому ни GRID_FRAC_GLYPH, ни frac одиночного режима
         * на значки не действовали вовсе - "идеальный" одиночный значок был просто совпадением
         * родного размера с клеткой. Для глифов рисуем регион напрямую нужным размером, сохраняя
         * пропорции через {@code imageSize()} (= max(g.width, g.height), по нему и вписываем в
         * квадрат s x s); {@code TextureRegion.width/height} у перевёрнутого по v региона глифа
         * корректны (считаются через Math.abs - проверено по байткоду Arc). Контентные иконки -
         * обычные TextureRegionDrawable, у них честный stretch, оставлен прежний путь.
         */
        static void drawIcon(Drawable d, boolean glyph, float x, float y, float s){
            if(glyph && d instanceof TextureRegionDrawable trd && trd.getRegion() != null){
                TextureRegion r = trd.getRegion();
                float size = Math.max(1f, trd.imageSize());
                Draw.rect(r, x + s / 2f, y + s / 2f, s * r.width / size, s * r.height / size);
            }else{
                d.draw(x, y, s, s);
            }
        }

        /** Единый размер грид-иконок при 2-4 штуках сразу (старый EUI++ стиль) - безопасно помещает пару бок о бок с отступом даже с учётом буста контентных иконок ниже; это ДОБУСТовая база для контентных, не финальный размер (см. MAX_GRID_FRAC). */
        static final float GRID_FRAC = 0.46f;
        /**
         * sonka 2026-08-21: "иконки не-блоки всё ещё большие, когда их 2-4 в ячейке" - в сетке
         * контентные иконки зажаты геометрическим потолком MAX_GRID_FRAC (0.49), буст туда почти
         * не доходит (0.46*1.6 упирается в потолок сразу), поэтому реального запаса, чтобы
         * "подрасти" им навстречу значкам, там нет. В одиночном режиме компенсация работает
         * НАОБОРОТ и подтверждена sonka как идеальная - контент растёт до 0.98 против 0.85 у
         * значка. 2026-08-22: выяснилось, что попытки 0.40 и 0.28 "не действовали" не из-за
         * значения, а потому что предметы/юниты/жидкости шли по БЛОЧНОЙ ветке (буст до 0.49) -
         * см. {@link Icons#isBlockIcon}; после исправления классификации возвращаем пропорцию
         * одиночного режима, подтверждённую sonka как идеальную: 0.85/0.98 * 0.49 ≈ 0.42.
         */
        static final float GRID_FRAC_GLYPH = 0.42f;
        /**
         * sonka: "иконки блоков слишком мелкие, а значки нормального размера" - у контентных
         * иконок (block/unit/item/... uiIcon) в спрайте обычно заложен заметный отступ вокруг
         * самой картинки, у "значков" (Icon.star и т.п.) его почти нет - при одинаковом
         * коэффициенте контентные иконки визуально мельче (проверено: Table.image()+.size() у
         * оригинального EUI++ и наш прямой Drawable.draw(x,y,w,h) идентичны по факту - оба через
         * Scaling.stretch, дело не в алгоритме масштабирования, а в самом спрайте). Первая версия
         * бустила только одиночную иконку - оказалось мало, если у ячейки заявлено 2-4 иконки
         * (частый случай), буст туда вообще не доходил. Теперь применяется в ОБОИХ режимах.
         */
        static final float CONTENT_ICON_BOOST = 1.6f;
        static final float MAX_SINGLE_FRAC = 0.98f;
        /** Геометрический потолок для сетки - 2 иконки в ряд должны поместиться бок о бок с отступом даже если обе бустнуты. */
        static final float MAX_GRID_FRAC = 0.49f;

        @Override
        public void draw(){
            float w = getWidth(), h = getHeight();
            Draw.color(color.r, color.g, color.b, color.a * parentAlpha);

            //sonka: вернули раскладку "как в Extended UI++" вместо "1 крупная в центре + до 4
            //мелких по углам" - единый список до 4 иконок (main + corners по порядку), 1 штука
            //рисуется крупно по центру её СОБСТВЕННЫМ настроенным размером (сохраняет ценность
            //слайдера для самого частого случая - одна иконка на схему), 2-4 штуки - равномерная
            //сетка по тем же угловым позициям, единого безопасного размера (индивидуальные
            //размеры игнорируются в этом режиме - при разных настроенных % иконки бы налезали
            //друг на друга в сетке). Данные (main/corners/их size) не менялись - это чисто
            //перерисовка, старые сохранённые размеры остаются на месте на случай отката.
            Drawable[] slots = {main, corners[0], corners[1], corners[2], corners[3]};
            float[] fracs = {mainFrac, cornerFracs[0], cornerFracs[1], cornerFracs[2], cornerFracs[3]};
            boolean[] boosts = {mainBoost, cornerBoost[0], cornerBoost[1], cornerBoost[2], cornerBoost[3]};
            boolean[] glyphs = {mainGlyph, cornerGlyph[0], cornerGlyph[1], cornerGlyph[2], cornerGlyph[3]};
            int count = 0;
            for(Drawable d : slots) if(d != null) count++;

            if(count == 1){
                for(int i = 0; i < slots.length; i++){
                    if(slots[i] == null) continue;
                    float frac = boosts[i] ? Math.min(fracs[i] * CONTENT_ICON_BOOST, MAX_SINGLE_FRAC) : fracs[i];
                    float s = Math.min(w, h) * frac;
                    drawIcon(slots[i], glyphs[i], x + (w - s) / 2f, y + (h - s) / 2f, s);
                    break;
                }
            }else if(count > 1){
                float pad = 1f;
                float minwh = Math.min(w, h);
                int placed = 0;
                for(int j = 0; j < slots.length; j++){
                    Drawable d = slots[j];
                    if(d == null) continue;
                    if(placed >= 4) break; //старый EUI++ лимит - до 4 иконок в сетке
                    int i = placed++;
                    //контентные иконки (блок/юнит/предмет) бустятся и в сетке, но зажаты
                    //MAX_GRID_FRAC - геометрия строки/колонки из двух ячеек не даёт разъехаться;
                    //значки (не контентные) ужаты отдельной, меньшей константой - см. GRID_FRAC_GLYPH
                    float frac = boosts[j] ? Math.min(GRID_FRAC * CONTENT_ICON_BOOST, MAX_GRID_FRAC) : GRID_FRAC_GLYPH;
                    float s = minwh * frac;
                    float cx = (i % 2 == 0) ? x + pad : x + w - s - pad;
                    float cy = (i < 2) ? y + h - s - pad : y + pad;
                    drawIcon(d, glyphs[j], cx, cy, s);
                }
            }

            int rot = ((rotation % 4) + 4) % 4;
            if(rot != 0 && Icon.upSmall != null){
                TextureRegion arrow = Icon.upSmall.getRegion();
                float s = Math.min(w, h) * 0.3f;
                Draw.color(1f, 1f, 1f, 0.8f * parentAlpha);
                Draw.rect(arrow, x + w / 2f, y + s / 2f + 1f, s, s, rot * 90f);
            }
            Draw.reset();
        }
    }

    /** Пустая ячейка - прежний "нет блока" по центру; иконка с нерезолвящимся именем (снесли мод) просто пропускается. */
    void buildCellContent(Button btn, CellData c){
        CellIconsElement el = new CellIconsElement();
        boolean any = false;
        if(c != null){
            if(c.main != null){
                Drawable d = Icons.getIconDrawable(c.main.name);
                if(d != null){
                    el.main = d;
                    //sonka 2026-08-21: НЕ c.main.size - слайдер размера отдельной иконки убрали ещё в
                    //c55ad8d, но поле в данных осталось (обратная совместимость), и у ячеек, к которым
                    //с тех пор не притрагивались через новый диалог (или мигрировавших из совсем старых
                    //сохранений), там могли лежать древние мелкие значения - тогда даже ОДИНОКАЯ иконка
                    //рисовалась мелкой, хотя её место в раскладке (count==1) рассчитано на почти всю
                    //клетку. Раскладка и так уже не читает per-icon размер в режиме сетки (GRID_FRAC) -
                    //здесь та же логика: размер одиночной иконки решает счётчик, а не хранилище.
                    el.mainFrac = iconFrac(SchemTableData.MAIN_ICON_DEFAULT_SIZE);
                    el.mainBoost = Icons.isBlockIcon(c.main.name);
                    el.mainGlyph = Icons.isGlyphIcon(c.main.name);
                    any = true;
                }
            }
            for(int i = 0; i < 4; i++){
                if(c.corners[i] == null) continue;
                Drawable d = Icons.getIconDrawable(c.corners[i].name);
                if(d != null){
                    el.corners[i] = d;
                    el.cornerFracs[i] = iconFrac(SchemTableData.CORNER_ICON_DEFAULT_SIZE);
                    el.cornerBoost[i] = Icons.isBlockIcon(c.corners[i].name);
                    el.cornerGlyph[i] = Icons.isGlyphIcon(c.corners[i].name);
                    any = true;
                }
            }
            el.rotation = c.rotation;
        }
        if(!any && c != null && !c.schematic.isEmpty() && Core.settings.getBool("eui-SchemTableAutoIcons", true)){
            //sonka 2026-08-22: авто-иконки по содержимому схемы (см. SchemAutoIcons) - только у
            //ячеек БЕЗ вручную выбранных иконок, чисто на отрисовке: в сохранение ничего не пишется,
            //ручной выбор в диалоге ячейки всегда главнее (как только пользователь выберет хоть одну
            //иконку - этот путь не выполняется). Закрепить авто-набор в данных, чтобы потом подправить,
            //можно кнопкой в диалоге ячейки / в тулбаре режима редактирования.
            Seq<String> auto = SchemAutoIcons.names(findSchematic(c.schematic));
            for(int i = 0; i < Math.min(auto.size, MAX_CELL_ICONS); i++){
                String name = auto.get(i);
                Drawable d = Icons.getIconDrawable(name);
                if(d == null) continue;
                if(i == 0){
                    el.main = d;
                    el.mainFrac = iconFrac(SchemTableData.MAIN_ICON_DEFAULT_SIZE);
                    el.mainBoost = Icons.isBlockIcon(name);
                    el.mainGlyph = Icons.isGlyphIcon(name);
                }else{
                    el.corners[i - 1] = d;
                    el.cornerFracs[i - 1] = iconFrac(SchemTableData.CORNER_ICON_DEFAULT_SIZE);
                    el.cornerBoost[i - 1] = Icons.isBlockIcon(name);
                    el.cornerGlyph[i - 1] = Icons.isGlyphIcon(name);
                }
                any = true;
            }
        }
        if(!any && el.main == null){
            //sonka: раньше был жёстко зашит 0.6f - у большинства ячеек нет вручную выбранной
            //иконки (просто привязана схема), так что ИМЕННО этот путь рисует большинство ячеек
            //таблицы, и раньше ни на что не влиять размером было нельзя - теперь читает общую
            //настройку вместо константы (слайдер добавлен в addSettings)
            el.main = defaultSchematicImage();
            el.mainFrac = iconFrac(Core.settings.getInt("eui-SchematicsTableDefaultIconSize", SchemTableData.MAIN_ICON_DEFAULT_SIZE));
            el.mainBoost = true; //Blocks.empty.uiIcon - тоже контентная (блочная) иконка
        }
        btn.add(el).grow();
    }

    static float iconFrac(int percent){
        return clamp(percent, 10, 100) / 100f;
    }

    // ---------------------------------------------------------------- цвета ячеек

    Color cellColor(int row, int col, Schematic schematic){
        int p = SchemTableData.pos(row, col);
        if(editMode){
            if(bandActive && inBand(row, col)) return Color.sky;
            if(moveSet != null){
                if(isMoveTarget(p)) return Pal.accent;
                if(moveSet.contains(p)) return Color.gray;
            }
            if(selection.contains(p)) return Pal.accent;
            Group g = page().groupOf(p);
            if(g != null) return GROUP_COLORS[Math.floorMod(page().groups.indexOf(g), GROUP_COLORS.length)];
            return Color.white;
        }

        if(dragSource != null && dragSource.is(row, col)) return Color.gray;
        if(dragHoverTarget != null && dragHoverTarget.is(row, col)) return Pal.accent;
        return schematicAffordabilityColor(schematic);
    }

    boolean inBand(int row, int col){
        if(bandAnchor < 0 || bandHover < 0) return false;
        int r0 = Math.min(SchemTableData.rowOf(bandAnchor), SchemTableData.rowOf(bandHover));
        int r1 = Math.max(SchemTableData.rowOf(bandAnchor), SchemTableData.rowOf(bandHover));
        int c0 = Math.min(SchemTableData.colOf(bandAnchor), SchemTableData.colOf(bandHover));
        int c1 = Math.max(SchemTableData.colOf(bandAnchor), SchemTableData.colOf(bandHover));
        return row >= r0 && row <= r1 && col >= c0 && col <= c1;
    }

    boolean isMoveTarget(int position){
        if(moveSet == null || movePressPos < 0 || moveHoverPos < 0) return false;
        int dRow = SchemTableData.rowOf(moveHoverPos) - SchemTableData.rowOf(movePressPos);
        int dCol = SchemTableData.colOf(moveHoverPos) - SchemTableData.colOf(movePressPos);
        if(dRow == 0 && dCol == 0) return false;
        for(int i = 0; i < moveSet.size; i++){
            int src = moveSet.get(i);
            if(SchemTableData.pos(SchemTableData.rowOf(src) + dRow, SchemTableData.colOf(src) + dCol) == position) return true;
        }
        return false;
    }

    void resetEditTransients(){
        moveSet = null;
        movePressPos = moveHoverPos = -1;
        bandActive = false;
        bandAnchor = bandHover = -1;
    }

    // ---------------------------------------------------------------- drag & drop (обычный режим: swap)

    /**
     * Cell repainting during a drag happens through the SAME per-frame {@code .update(...)} callback
     * that already paints the affordability color in {@link #buildTable} ({@link #cellColor}) - painting
     * directly from this listener would just get overwritten the very next frame.
     */
    void attachCellDrag(Button btn, int row, int col){
        boolean[] dragging = {false};
        float[] start = {0, 0};
        float DRAG_SLOP = 10f;

        btn.addListener(new InputListener(){
            @Override
            public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button){
                if(button != KeyCode.mouseLeft) return false;
                dragging[0] = false;
                start[0] = x;
                start[1] = y;
                return true;
            }

            @Override
            public void touchDragged(InputEvent event, float x, float y, int pointer){
                try{
                    if(!dragging[0] && (Math.abs(x - start[0]) > DRAG_SLOP || Math.abs(y - start[1]) > DRAG_SLOP)){
                        dragging[0] = true;
                        dragSource = new CellRef(row, col);
                    }
                    if(!dragging[0]) return;

                    CellButton target = hitCell(event.stageX, event.stageY);
                    dragHoverTarget = (target != null && !target.btn.equals(btn) && !(target.row == row && target.col == col))
                        ? new CellRef(target.row, target.col) : null;
                }catch(Throwable t){
                    Log.err("[eui] schematics-table cell drag failed", t);
                }
            }

            @Override
            public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode button){
                try{
                    if(dragging[0] && dragHoverTarget != null){
                        swapCells(row, col, dragHoverTarget.row, dragHoverTarget.col);
                        rebuildTable();
                        return;
                    }
                }catch(Throwable t){
                    Log.err("[eui] schematics-table cell drop failed", t);
                }
                dragging[0] = false;
                dragSource = null;
                dragHoverTarget = null;
            }
        });
    }

    CellButton hitCell(float stageX, float stageY){
        var hit = Core.scene.hit(stageX, stageY, true);
        if(hit == null) return null;
        for(CellButton c : cellButtons){
            if(c.btn == hit || hit.isDescendantOf(c.btn)) return c;
        }
        return null;
    }

    /** Swap двух ячеек активной страницы (пустая сторона остаётся пустой на новом месте); группы переезжают за своими позициями. */
    void swapCells(int rowA, int colA, int rowB, int colB){
        Page p = page();
        int a = SchemTableData.pos(rowA, colA), b = SchemTableData.pos(rowB, colB);
        CellData ca = p.cells.get(a), cb = p.cells.get(b);
        if(ca != null) p.cells.put(b, ca); else p.cells.remove(b);
        if(cb != null) p.cells.put(a, cb); else p.cells.remove(a);

        IntIntMap mapping = new IntIntMap();
        mapping.put(a, b);
        mapping.put(b, a);
        remapGroups(p, mapping);
        data().save();
    }

    static void remapGroups(Page p, IntIntMap mapping){
        for(Group g : p.groups){
            for(int i = 0; i < g.cells.size; i++){
                int v = g.cells.get(i);
                int nv = mapping.get(v, v);
                if(nv != v) g.cells.set(i, nv);
            }
        }
    }

    // ---------------------------------------------------------------- режим редактирования: ввод

    /**
     * Жест в режиме редактирования: клик = переключить выделение (группа - целиком); драг с
     * ВЫДЕЛЕННОЙ/сгруппированной ячейки = перенос всего набора; драг с невыделенной = рамка выделения
     * (Shift - добавить к существующему). Никакой отдельной инфраструктуры: тот же
     * InputListener-паттерн, что у drag-swap обычного режима.
     */
    void attachCellEdit(Button btn, int row, int col){
        int position = SchemTableData.pos(row, col);
        boolean[] dragging = {false};
        boolean[] moving = {false};
        float[] start = {0, 0};
        float DRAG_SLOP = 10f;

        btn.addListener(new InputListener(){
            @Override
            public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button){
                if(button != KeyCode.mouseLeft) return false;
                dragging[0] = false;
                start[0] = x;
                start[1] = y;
                return true;
            }

            @Override
            public void touchDragged(InputEvent event, float x, float y, int pointer){
                try{
                    if(!dragging[0] && (Math.abs(x - start[0]) > DRAG_SLOP || Math.abs(y - start[1]) > DRAG_SLOP)){
                        dragging[0] = true;
                        moving[0] = selection.contains(position) || page().groupOf(position) != null;
                        if(moving[0]){
                            moveSet = computeMoveSet(position);
                            movePressPos = position;
                            moveHoverPos = position;
                        }else{
                            bandActive = true;
                            bandAnchor = position;
                            bandHover = position;
                        }
                    }
                    if(!dragging[0]) return;

                    CellButton target = hitCell(event.stageX, event.stageY);
                    if(target != null){
                        int tp = SchemTableData.pos(target.row, target.col);
                        if(moving[0]) moveHoverPos = tp;
                        else bandHover = tp;
                    }
                }catch(Throwable t){
                    Log.err("[eui] schematics-table edit drag failed", t);
                }
            }

            @Override
            public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode button){
                try{
                    if(dragging[0]){
                        if(moving[0]){
                            int dRow = SchemTableData.rowOf(moveHoverPos) - SchemTableData.rowOf(movePressPos);
                            int dCol = SchemTableData.colOf(moveHoverPos) - SchemTableData.colOf(movePressPos);
                            IntSeq set = moveSet;
                            resetEditTransients();
                            if((dRow != 0 || dCol != 0) && set != null) applyMove(set, dRow, dCol);
                        }else{
                            int a = bandAnchor, b = bandHover;
                            boolean additive = Core.input.shift();
                            resetEditTransients();
                            applyBand(a, b, additive);
                        }
                        return;
                    }
                    toggleSelect(position);
                }catch(Throwable t){
                    Log.err("[eui] schematics-table edit drop failed", t);
                    resetEditTransients();
                }
            }
        });
    }

    void toggleSelect(int position){
        Group g = page().groupOf(position);
        if(g != null){
            boolean all = true;
            for(int i = 0; i < g.cells.size; i++){
                if(!selection.contains(g.cells.get(i))){
                    all = false;
                    break;
                }
            }
            for(int i = 0; i < g.cells.size; i++){
                int m = g.cells.get(i);
                if(all) selection.removeValue(m);
                else if(!selection.contains(m)) selection.add(m);
            }
        }else{
            if(!selection.removeValue(position)) selection.add(position);
        }
    }

    void applyBand(int a, int b, boolean additive){
        if(a < 0 || b < 0) return;
        if(!additive) selection.clear();
        int r0 = Math.min(SchemTableData.rowOf(a), SchemTableData.rowOf(b));
        int r1 = Math.max(SchemTableData.rowOf(a), SchemTableData.rowOf(b));
        int c0 = Math.min(SchemTableData.colOf(a), SchemTableData.colOf(b));
        int c1 = Math.max(SchemTableData.colOf(a), SchemTableData.colOf(b));
        for(int r = r0; r <= r1; r++){
            for(int c = c0; c <= c1; c++){
                int p = SchemTableData.pos(r, c);
                if(!selection.contains(p)) selection.add(p);
            }
        }
        expandSelectionToGroups();
    }

    /** Группы неделимы для выделения: если рамка/клик зацепили часть группы - доезжает вся. */
    void expandSelectionToGroups(){
        for(Group g : page().groups){
            boolean any = false;
            for(int i = 0; i < g.cells.size; i++){
                if(selection.contains(g.cells.get(i))){
                    any = true;
                    break;
                }
            }
            if(any){
                for(int i = 0; i < g.cells.size; i++){
                    int m = g.cells.get(i);
                    if(!selection.contains(m)) selection.add(m);
                }
            }
        }
    }

    /** Набор для переноса: выделение (если тянем выделенную ячейку) или группа ячейки; группы едут целиком. */
    IntSeq computeMoveSet(int pressed){
        IntSeq set = new IntSeq();
        if(selection.contains(pressed)){
            set.addAll(selection);
        }else{
            Group g = page().groupOf(pressed);
            if(g != null) set.addAll(g.cells);
            else set.add(pressed);
        }

        boolean changed = true;
        while(changed){
            changed = false;
            for(Group g : page().groups){
                boolean any = false;
                for(int i = 0; i < g.cells.size; i++){
                    if(set.contains(g.cells.get(i))){
                        any = true;
                        break;
                    }
                }
                if(!any) continue;
                for(int i = 0; i < g.cells.size; i++){
                    int m = g.cells.get(i);
                    if(!set.contains(m)){
                        set.add(m);
                        changed = true;
                    }
                }
            }
        }
        return set;
    }

    /**
     * Перенос набора на (dRow, dCol). Одна ячейка - прежний swap с занятым местом; несколько - только
     * в свободные (или свои же) клетки, иначе отказ. Группы и выделение переезжают за позициями.
     */
    void applyMove(IntSeq set, int dRow, int dCol){
        Page p = page();

        for(int i = 0; i < set.size; i++){
            int src = set.get(i);
            if(!p.inBounds(SchemTableData.rowOf(src) + dRow, SchemTableData.colOf(src) + dCol)){
                ui.announce(Core.bundle.get("schematics-table.edit.move-blocked"), 2f);
                return;
            }
        }

        IntIntMap mapping = new IntIntMap();
        if(set.size == 1){
            int src = set.first();
            int dst = SchemTableData.pos(SchemTableData.rowOf(src) + dRow, SchemTableData.colOf(src) + dCol);
            CellData ca = p.cells.get(src), cb = p.cells.get(dst);
            if(ca != null) p.cells.put(dst, ca); else p.cells.remove(dst);
            if(cb != null) p.cells.put(src, cb); else p.cells.remove(src);
            mapping.put(src, dst);
            mapping.put(dst, src);
        }else{
            for(int i = 0; i < set.size; i++){
                int src = set.get(i);
                int dst = SchemTableData.pos(SchemTableData.rowOf(src) + dRow, SchemTableData.colOf(src) + dCol);
                if(p.cells.containsKey(dst) && !set.contains(dst)){
                    ui.announce(Core.bundle.get("schematics-table.edit.move-blocked"), 2f);
                    return;
                }
            }
            IntMap<CellData> moved = new IntMap<>();
            for(int i = 0; i < set.size; i++){
                int src = set.get(i);
                int dst = SchemTableData.pos(SchemTableData.rowOf(src) + dRow, SchemTableData.colOf(src) + dCol);
                CellData c = p.cells.remove(src);
                if(c != null) moved.put(dst, c);
                mapping.put(src, dst);
            }
            for(IntMap.Entry<CellData> e : moved){
                p.cells.put(e.key, e.value);
            }
        }

        remapGroups(p, mapping);

        IntSeq newSelection = new IntSeq();
        for(int i = 0; i < selection.size; i++){
            int v = selection.get(i);
            newSelection.add(mapping.get(v, v));
        }
        selection = newSelection;

        data().save();
        rebuildTable();
    }

    // ---------------------------------------------------------------- режим редактирования: операции

    void groupSelection(){
        if(selection.size < 2){
            ui.announce(Core.bundle.get("schematics-table.edit.need-two"), 2f);
            return;
        }
        Page p = page();
        p.ungroupPositions(selection);
        Group g = new Group();
        for(int v : orderedSelection(0)) g.cells.add(v);
        p.groups.add(g);
        data().save();
        ui.announce(Core.bundle.get("schematics-table.edit.grouped"), 2f);
    }

    void ungroupSelection(){
        if(selection.isEmpty()){
            ui.announce(Core.bundle.get("schematics-table.edit.need-selection"), 2f);
            return;
        }
        Page p = page();
        int before = p.groups.size;
        p.groups.removeAll(g -> {
            for(int i = 0; i < g.cells.size; i++){
                if(selection.contains(g.cells.get(i))) return true;
            }
            return false;
        });
        if(p.groups.size != before){
            data().save();
            ui.announce(Core.bundle.get("schematics-table.edit.ungrouped"), 2f);
        }
    }

    void rotateSelection(){
        if(selection.isEmpty()){
            ui.announce(Core.bundle.get("schematics-table.edit.need-selection"), 2f);
            return;
        }
        Page p = page();
        int count = 0;
        for(int i = 0; i < selection.size; i++){
            CellData c = p.cells.get(selection.get(i));
            if(c != null && !c.schematic.isEmpty()){
                c.rotation = (c.rotation + 1) % 4;
                count++;
            }
        }
        if(count > 0){
            data().save();
            ui.announce(Core.bundle.format("schematics-table.edit.rotated", count), 2f);
            rebuildTable();
        }
    }

    void clearSelectionCells(){
        if(selection.isEmpty()){
            ui.announce(Core.bundle.get("schematics-table.edit.need-selection"), 2f);
            return;
        }
        Page p = page();
        int count = 0;
        for(int i = 0; i < selection.size; i++){
            if(p.cells.remove(selection.get(i)) != null) count++;
        }
        data().save();
        ui.announce(Core.bundle.format("schematics-table.edit.cleared", count), 2f);
        rebuildTable();
    }

    void copySelection(){
        if(selection.isEmpty()){
            ui.announce(Core.bundle.get("schematics-table.edit.need-selection"), 2f);
            return;
        }
        Page p = page();
        SchemTableData.clipboard.clear();
        for(int v : orderedSelection(0)){
            CellData c = p.cells.get(v);
            SchemTableData.clipboard.add(new ClipEntry(c != null ? c.copy() : new CellData(),
                SchemTableData.rowOf(v), SchemTableData.colOf(v)));
        }
        ui.announce(Core.bundle.format("schematics-table.edit.copied", SchemTableData.clipboard.size), 2f);
    }

    void showPasteDialog(){
        if(SchemTableData.clipboard.isEmpty()){
            ui.announce(Core.bundle.get("schematics-table.edit.clipboard-empty"), 2f);
            return;
        }
        BaseDialog dialog = new BaseDialog(Core.bundle.get("schematics-table.dialog.paste.title"));
        dialog.addCloseButton();
        Table t = dialog.cont;
        t.defaults().left();
        t.check(Core.bundle.get("schematics-table.dialog.paste.schematic"), pasteSchematic, v -> pasteSchematic = v).left().row();
        t.check(Core.bundle.get("schematics-table.dialog.paste.rotation"), pasteRotation, v -> pasteRotation = v).left().padTop(4f).row();
        t.check(Core.bundle.get("schematics-table.dialog.paste.label"), pasteLabel, v -> pasteLabel = v).left().padTop(4f).row();
        t.check(Core.bundle.get("schematics-table.dialog.paste.main-icon"), pasteMainIcon, v -> pasteMainIcon = v).left().padTop(4f).row();
        t.check(Core.bundle.get("schematics-table.dialog.paste.corner-icons"), pasteCornerIcons, v -> pasteCornerIcons = v).left().padTop(4f).row();
        t.button(Core.bundle.get("schematics-table.dialog.paste.apply"), Icon.paste, () -> {
            pasteClipboard();
            dialog.hide();
        }).width(260f).height(50f).padTop(12f);
        dialog.show();
    }

    /**
     * Умная групповая вставка: при непустом выделении буфер кладётся В выделенные позиции (одна
     * скопированная ячейка размножается на всё выделение, несколько - паруются по порядку обхода);
     * без выделения каждая ячейка буфера ложится в свои ИСХОДНЫЕ координаты на ТЕКУЩЕЙ странице -
     * так буфер переносит набор ячеек со страницы на страницу один-в-один.
     */
    void pasteClipboard(){
        Page p = page();
        Seq<ClipEntry> clips = SchemTableData.clipboard;
        int pasted = 0;

        if(!selection.isEmpty()){
            Seq<Integer> targets = orderedSelection(0);
            if(clips.size == 1){
                for(int t : targets){
                    applyPasteProps(clips.first().data, p.cellForWrite(SchemTableData.rowOf(t), SchemTableData.colOf(t)));
                    pasted++;
                }
            }else{
                int n = Math.min(clips.size, targets.size);
                for(int i = 0; i < n; i++){
                    int t = targets.get(i);
                    applyPasteProps(clips.get(i).data, p.cellForWrite(SchemTableData.rowOf(t), SchemTableData.colOf(t)));
                    pasted++;
                }
            }
        }else{
            for(ClipEntry e : clips){
                if(!p.inBounds(e.srcRow, e.srcCol)) continue;
                applyPasteProps(e.data, p.cellForWrite(e.srcRow, e.srcCol));
                pasted++;
            }
        }

        data().save();
        rebuildTable();
        ui.announce(Core.bundle.format("schematics-table.edit.pasted", pasted), 2f);
    }

    static void applyPasteProps(CellData src, CellData dst){
        if(pasteSchematic) dst.schematic = src.schematic;
        if(pasteRotation) dst.rotation = src.rotation;
        if(pasteLabel) dst.label = src.label;
        if(pasteMainIcon) dst.main = src.main == null ? null : src.main.copy();
        if(pasteCornerIcons){
            for(int i = 0; i < 4; i++) dst.corners[i] = src.corners[i] == null ? null : src.corners[i].copy();
        }
    }

    /** Задание основной иконки ячейке или всей группе/выделению ОДНИМ выбором из пикера. */
    /**
     * Закрепить авто-иконки (см. {@link SchemAutoIcons}) в данных всех выделенных ячеек со схемой -
     * ПЕРЕЗАПИСЫВАЕТ ручной набор иконок (это и есть смысл кнопки: "пересчитать по схеме");
     * ячейки без схемы или без значимых блоков пропускаются.
     */
    void autoIconsForSelection(){
        if(selection.isEmpty()){
            ui.announce(Core.bundle.get("schematics-table.edit.need-selection"), 2f);
            return;
        }
        Page p = page();
        int count = 0;
        for(int i = 0; i < selection.size; i++){
            CellData c = p.cells.get(selection.get(i));
            if(c == null || c.schematic.isEmpty()) continue;
            Seq<String> auto = SchemAutoIcons.names(findSchematic(c.schematic));
            if(auto.isEmpty()) continue;
            applyCellIconNames(c, auto);
            count++;
        }
        if(count > 0){
            data().save();
            rebuildTable();
        }
        ui.announce(Core.bundle.format("schematics-table.edit.auto-iconed", count), 2f);
    }

    void setIconForSelection(){
        if(selection.isEmpty()){
            ui.announce(Core.bundle.get("schematics-table.edit.need-selection"), 2f);
            return;
        }
        showIconPickDialog(name -> {
            Page p = page();
            for(int i = 0; i < selection.size; i++){
                int v = selection.get(i);
                CellData c = p.cellForWrite(SchemTableData.rowOf(v), SchemTableData.colOf(v));
                if(c.main == null) c.main = new IconRef(name, SchemTableData.MAIN_ICON_DEFAULT_SIZE);
                else c.main.name = name;
            }
            data().save();
            rebuildTable();
        });
    }

    /** Паттерны названий: базовое имя + автонумерация по выбранному направлению обхода выделения. */
    void showNamesDialog(){
        if(selection.isEmpty()){
            ui.announce(Core.bundle.get("schematics-table.edit.need-selection"), 2f);
            return;
        }
        BaseDialog dialog = new BaseDialog(Core.bundle.get("schematics-table.dialog.names.title"));
        dialog.addCloseButton();

        String[] base = {""};
        boolean[] autonum = {selection.size > 1};
        int[] start = {1};
        int[] dir = {0};

        Table t = dialog.cont;
        t.defaults().left();
        t.table(r -> {
            r.add(Core.bundle.get("schematics-table.dialog.names.base") + ":").padRight(6f);
            r.field("", v -> base[0] = v).width(260f);
        }).row();
        t.check(Core.bundle.get("schematics-table.dialog.names.autonumber"), autonum[0], v -> autonum[0] = v).padTop(6f).left().row();
        t.table(r -> {
            r.add(Core.bundle.get("schematics-table.dialog.names.start") + ":").padRight(6f);
            r.field("1", v -> start[0] = parseIntOr(v, 1)).width(80f);
        }).padTop(6f).row();
        t.add(Core.bundle.get("schematics-table.dialog.names.direction") + ":").padTop(10f).row();
        String[] dirKeys = {"dir-rows-ltr", "dir-rows-rtl", "dir-cols-ttb", "dir-cols-btt"};
        for(int k = 0; k < dirKeys.length; k++){
            int kk = k;
            t.button(Core.bundle.get("schematics-table.dialog.names." + dirKeys[k]), Styles.togglet, () -> dir[0] = kk)
                .update(b -> b.setChecked(dir[0] == kk)).width(340f).height(40f).padTop(4f).row();
        }
        t.button(Core.bundle.get("schematics-table.dialog.names.apply"), Icon.ok, () -> {
            Page p = page();
            int n = start[0];
            for(int v : orderedSelection(dir[0])){
                CellData c = p.cellForWrite(SchemTableData.rowOf(v), SchemTableData.colOf(v));
                c.label = autonum[0]
                    ? (base[0].isEmpty() ? String.valueOf(n) : base[0] + " " + n)
                    : base[0];
                n++;
            }
            data().save();
            rebuildTable();
            dialog.hide();
        }).width(260f).height(50f).padTop(12f);

        dialog.show();
    }

    /** Выделение в порядке обхода: 0 - по рядам слева направо, 1 - по рядам справа налево, 2 - по колоннам сверху вниз, 3 - по колоннам снизу вверх. */
    Seq<Integer> orderedSelection(int dir){
        Seq<Integer> list = new Seq<>();
        for(int i = 0; i < selection.size; i++) list.add(selection.get(i));
        Comparator<Integer> cmp = switch(dir){
            case 1 -> (a, b) -> SchemTableData.rowOf(a) != SchemTableData.rowOf(b)
                ? Integer.compare(SchemTableData.rowOf(a), SchemTableData.rowOf(b))
                : Integer.compare(SchemTableData.colOf(b), SchemTableData.colOf(a));
            case 2 -> (a, b) -> SchemTableData.colOf(a) != SchemTableData.colOf(b)
                ? Integer.compare(SchemTableData.colOf(a), SchemTableData.colOf(b))
                : Integer.compare(SchemTableData.rowOf(a), SchemTableData.rowOf(b));
            case 3 -> (a, b) -> SchemTableData.colOf(a) != SchemTableData.colOf(b)
                ? Integer.compare(SchemTableData.colOf(a), SchemTableData.colOf(b))
                : Integer.compare(SchemTableData.rowOf(b), SchemTableData.rowOf(a));
            default -> Integer::compare; //кодировка pos = row*100+col: натуральный порядок и есть "по рядам слева направо"
        };
        list.sort(cmp);
        return list;
    }

    // ---------------------------------------------------------------- массовый импорт по тегу схем

    /**
     * sonka 2026-08-22: "массовый импорт схем из одного тега схем в таблицу" - список всех тегов
     * ({@code Schematic.labels}) с количеством схем; клик по тегу кладёт все его схемы (по алфавиту)
     * в таблицу. Куда: при непустом выделении - в выделенные ячейки по порядку (с перезаписью - это
     * осознанный выбор пользователя), иначе - в ПУСТЫЕ ячейки текущей страницы по рядам;
     * чекбокс "на новую страницу" создаёт страницу с именем тега и нужным числом рядов. Схемы,
     * уже лежащие на странице, по умолчанию пропускаются (второй чекбокс). Перезаписанная ячейка
     * получает чистый CellData (старые иконки/подпись относились к другой схеме) - иконки
     * дорисует SchemAutoIcons.
     */
    void showTagImportDialog(){
        ObjectIntMap<String> counts = new ObjectIntMap<>();
        for(Schematic s : schematics.all()){
            for(String l : s.labels) counts.put(l, counts.get(l, 0) + 1);
        }
        if(counts.isEmpty()){
            ui.announce(Core.bundle.get("schematics-table.import-tag.no-tags"), 2f);
            return;
        }
        Seq<String> tags = counts.keys().toSeq();
        tags.sort(String.CASE_INSENSITIVE_ORDER);

        BaseDialog dialog = new BaseDialog(Core.bundle.get("schematics-table.import-tag.title"));
        dialog.addCloseButton();
        Table t = dialog.cont;
        t.defaults().left();
        t.check(Core.bundle.get("schematics-table.import-tag.new-page"), importTagNewPage, v -> importTagNewPage = v).left().row();
        t.check(Core.bundle.get("schematics-table.import-tag.skip-present"), importTagSkipPresent, v -> importTagSkipPresent = v).left().padTop(4f).row();
        t.label(() -> selection.isEmpty() || importTagNewPage
            ? Core.bundle.get("schematics-table.import-tag.target-empty")
            : Core.bundle.format("schematics-table.import-tag.target-selection", selection.size)
        ).color(Color.lightGray).padTop(6f).padBottom(6f).row();

        t.pane(list -> {
            list.defaults().left().growX();
            for(String tag : tags){
                int n = counts.get(tag, 0);
                list.button("[accent]" + tag + "[] (" + n + ")", Styles.flatt, () -> {
                    importTag(tag, importTagNewPage, importTagSkipPresent);
                    dialog.hide();
                }).height(44f).padBottom(2f).row();
            }
        }).scrollX(false).size(mobile ? 380f : 520f, mobile ? 360f : 520f);
        dialog.show();
    }

    void importTag(String tag, boolean asNewPage, boolean skipPresent){
        Seq<Schematic> list = schematics.all().select(s -> s.labels.contains(tag));
        list.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.name(), b.name()));
        if(list.isEmpty()){
            ui.announce(Core.bundle.get("schematics-table.import-tag.no-tags"), 2f);
            return;
        }

        SchemTableData d = data();
        if(asNewPage){
            Page np = new Page();
            np.name = tag;
            np.cols = clamp(Core.settings.getInt("eui-SchematicsTableColumns", 5), 1, SchemTableData.MAX_COLS);
            np.rows = clamp((list.size + np.cols - 1) / np.cols, 1, SchemTableData.MAX_ROWS);
            d.pages.add(np);
            currentPage = d.pages.size - 1;
            lastPage = currentPage; //иначе isRebuildNeeded перестроит ещё раз на следующем кадре - безвредно, но лишнее
            selection.clear();
        }
        Page p = page();

        //цели: выделение (по рядам) или пустые ячейки страницы по рядам
        Seq<Integer> targets;
        if(!asNewPage && !selection.isEmpty()){
            targets = orderedSelection(0);
        }else{
            targets = new Seq<>();
            for(int r = 0; r < p.rows; r++){
                for(int c = 0; c < p.cols; c++){
                    CellData cd = p.cell(r, c);
                    if(cd == null || cd.isEmpty()) targets.add(SchemTableData.pos(r, c));
                }
            }
        }

        ObjectSet<String> present = new ObjectSet<>();
        if(skipPresent){
            for(IntMap.Entry<CellData> e : p.cells){
                if(e.value != null && !e.value.schematic.isEmpty()) present.add(e.value.schematic);
            }
        }

        int placed = 0, dup = 0, noRoom = 0;
        for(Schematic s : list){
            String name = s.name();
            if(present.contains(name)){
                dup++;
                continue;
            }
            if(placed >= targets.size){
                noRoom++;
                continue;
            }
            int pos = targets.get(placed++);
            CellData nc = new CellData();
            nc.schematic = name;
            p.cells.put(pos, nc);
            present.add(name);
        }

        d.save();
        selection.clear();
        rebuildTable();
        ui.announce(Core.bundle.format("schematics-table.import-tag.done", placed, dup, noRoom), 3f);
    }

    // ---------------------------------------------------------------- диалог ячейки

    void showCellDialog(int row, int col){
        BaseDialog dialog = new BaseDialog(Core.bundle.get("schematics-table.dialog.edit-schematic-button.title"));
        dialog.addCloseButton();
        Runnable[] rebuild = {null};
        rebuild[0] = () -> {
            dialog.cont.clearChildren();
            buildCellDialogContent(dialog.cont, row, col, rebuild[0]);
        };
        rebuild[0].run();
        dialog.hidden(this::rebuildTable);
        dialog.show();
    }

    void buildCellDialogContent(Table cont, int row, int col, Runnable rebuild){
        Page p = page();
        CellData cell = p.cell(row, col);
        boolean isMulti = cell != null && cell.multi;

        cont.pane(t -> {
            t.defaults().left();

            //схема (в multi-режиме центр колеса - см. javadoc CellData.multi)
            t.table(schem -> {
                schem.left();
                String cur = cell != null ? cell.schematic : "";
                schem.labelWrap(currentSchematicLabelText(cur)).width(mobile ? 220f : 420f).color(Pal.accent).padRight(8f);
                //ЛКМ - выбрать схему, ПКМ - очистить (без отдельной кнопки-корзины)
                schem.button(Core.bundle.get(isMulti ? "schematics-table.dialog.change-center-preview" : "schematics-table.dialog.pick-schematic"), () ->
                    showSchematicPickerDialog(name -> {
                        page().cellForWrite(row, col).schematic = name;
                        data().save();
                        rebuild.run();
                    })
                ).width(200f).height(44f).pad(4f).get().clicked(KeyCode.mouseRight, () -> {
                    page().cellForWrite(row, col).schematic = "";
                    data().save();
                    rebuild.run();
                });
            }).growX().row();

            //режим ячейки: одна схема / несколько по кругу
            t.button(Core.bundle.get(isMulti ? "schematics-table.dialog.mode.multi" : "schematics-table.dialog.mode.single"), () -> {
                CellData cc = page().cellForWrite(row, col);
                cc.multi = !cc.multi;
                data().save();
                rebuild.run();
            }).width(260f).height(44f).padTop(6f).row();

            if(isMulti){
                stepperRow(t, Core.bundle.get("schematics-table.dialog.sections"), () -> page().cell(row, col).sections, v -> {
                    page().cellForWrite(row, col).sections = v;
                    data().save();
                    rebuild.run();
                }, CellData.MIN_SECTIONS, CellData.MAX_SECTIONS);

                t.button(Core.bundle.get("schematics-table.dialog.manage-multi"), Icon.list, () -> showMultiManageDialog(row, col, rebuild))
                    .width(280f).height(50f).padTop(6f).row();

                t.add(Core.bundle.get("schematics-table.dialog.radial-preview") + ":").padTop(50f).row();
                WidgetGroup radial = buildRadialLayout(page().cell(row, col), mobile ? 26f : 34f, mobile ? 46f : 58f,
                    () -> showSchematicPickerDialog(name -> {
                        page().cellForWrite(row, col).schematic = name;
                        data().save();
                        rebuild.run();
                    }),
                    (idx, s, me) -> showSchematicPickerDialog(name -> {
                        CellData cc = page().cellForWrite(row, col);
                        MultiEntry entry = cc.multiEntries.get(idx);
                        if(entry == null){
                            entry = new MultiEntry();
                            cc.multiEntries.put(idx, entry);
                        }
                        entry.schematic = name;
                        data().save();
                        rebuild.run();
                    }),
                    () -> { //ПКМ по центру - очистить центральную схему
                        page().cellForWrite(row, col).schematic = "";
                        data().save();
                        rebuild.run();
                    },
                    (idx, s, me) -> { //ПКМ по спице - убрать схему из этой секции
                        page().cellForWrite(row, col).multiEntries.remove(idx);
                        data().save();
                        rebuild.run();
                    }
                );
                t.table(rp -> rp.add(radial).size(radial.getWidth(), radial.getHeight())).padTop(24f).padBottom(20f).row();
            }

            //подпись
            t.table(lr -> {
                lr.add(Core.bundle.get("schematics-table.dialog.cell.label") + ":").padRight(6f);
                lr.field(cell != null ? cell.label : "", text -> {
                    page().cellForWrite(row, col).label = text;
                    data().save();
                }).width(280f);
            }).padTop(6f).row();

            //ротация
            t.table(rr -> {
                rr.add(Core.bundle.get("schematics-table.dialog.cell.rotation") + ":").padRight(6f);
                rr.button("[accent]<", () -> {
                    CellData cc = page().cellForWrite(row, col);
                    cc.rotation = (cc.rotation + 3) % 4;
                    data().save();
                }).size(44f);
                rr.label(() -> {
                    CellData cc = page().cell(row, col);
                    return (cc == null ? 0 : cc.rotation) + " x 90°";
                }).padLeft(10f).padRight(10f);
                rr.button("[accent]>", () -> {
                    CellData cc = page().cellForWrite(row, col);
                    cc.rotation = (cc.rotation + 1) % 4;
                    data().save();
                }).size(44f);
            }).padTop(6f).row();

            //sonka: "сделай выбор иконок как в Extended UI++, а не менять каждую отдельно" -
            //вместо 4 отдельных строк "Иконка N: [Превью] [Выбрать]" (каждая - свой попап) теперь
            //ОДИН тоггл-браузер прямо в диалоге ячейки, как в оригинале (addEditImageTable c
            //multiSelect=true): клик по иконке добавляет/убирает её из набора ячейки (до 4 штук),
            //уже выбранные подсвечены. Хранилище (main+corners[0..2]) не поменялось - см.
            //cellIconNames/applyCellIconNames ниже, это чисто смена UI поверх тех же данных.
            t.add(Core.bundle.get("schematics-table.dialog.cell.icons") + ":").padTop(10f).row();
            t.table(icons -> addIconPickTable(icons, mobile ? 150f : 350f,
                name -> toggleCellIcon(row, col, name, rebuild),
                name -> cellIconNames(page().cell(row, col)).contains(name)
            )).growX().padTop(4f).row();

            t.button(Core.bundle.get("schematics-table.dialog.cell.auto-icons"), Icon.refresh, () -> {
                CellData cc = page().cell(row, col);
                Seq<String> auto = cc == null ? new Seq<>() : SchemAutoIcons.names(findSchematic(cc.schematic));
                if(auto.isEmpty()){
                    ui.announce(Core.bundle.get("schematics-table.dialog.cell.auto-icons-none"), 2f);
                    return;
                }
                applyCellIconNames(page().cellForWrite(row, col), auto);
                data().save();
                rebuild.run();
            }).width(260f).height(50f).padTop(12f).row();

            t.button(Core.bundle.get("schematics-table.dialog.clear-cell"), Icon.trash, () -> {
                page().removeCell(row, col);
                data().save();
                ui.announce(Core.bundle.get("schematics-table.dialog.clear-cell-announce"));
                rebuild.run();
            }).width(240f).height(50f).padTop(12f).row();
        }).size(mobile ? 420f : 800f, mobile ? 620f : 820f);
    }

    // ---------------------------------------------------------------- multi-ячейка: radial-меню

    interface RadialClick{
        void get(int index, Schematic schematic, MultiEntry entry);
    }

    /** Кнопка одной "спицы" колеса - превью схемы (как в пикере) или пустой "+", если секция не назначена. */
    Button buildRadialButton(Schematic s){
        Button b = new Button(Styles.defaulti);
        if(s != null){
            try{
                b.add(new SchematicImage(s)).grow().pad(4f);
            }catch(Throwable t){
                b.image(defaultSchematicImage()).grow().pad(6f);
            }
        }else{
            b.image(Icon.add).grow().pad(mobile ? 8f : 12f);
        }
        return b;
    }

    /**
     * Раскладка колеса выбора - секции {@link CellData#sections} штук по кругу (12 часов, далее по
     * часовой) + центр (та же пара {@link CellData#schematic}/{@link CellData#rotation}, что у
     * обычной ячейки). Один и тот же билдер используется и для статичного превью в диалоге ячейки
     * (клик по спице/центру там же меняет назначение), и для живого picker'а в игре (openRadialPicker) -
     * поведение клика передаётся коллбэками, разметка - общая.
     */
    WidgetGroup buildRadialLayout(CellData cell, float radius, float btnSize, Runnable onCenter, RadialClick onSection){
        return buildRadialLayout(cell, radius, btnSize, onCenter, onSection, null, null);
    }

    /** То же самое + ПКМ по спице/центру для быстрой очистки без отдельной кнопки-корзины (null - без очистки, как в живом picker'е). */
    WidgetGroup buildRadialLayout(CellData cell, float radius, float btnSize, Runnable onCenter, RadialClick onSection, Runnable onCenterRemove, RadialClick onSectionRemove){
        int n = Math.max(CellData.MIN_SECTIONS, Math.min(cell.sections, CellData.MAX_SECTIONS));
        float centerSize = btnSize * 1.25f;
        //при большом числе секций (и вплотную к центру при малом) кнопки иначе накладываются друг
        //на друга - радиус должен обеспечивать зазор и между соседними спицами, и между спицей и центром
        float gap = 6f;
        float minRadiusSpokes = (btnSize + gap) / (2f * Mathf.sin(Mathf.pi / n));
        float minRadiusCenter = btnSize / 2f + centerSize / 2f + gap;
        radius = Math.max(radius, Math.max(minRadiusSpokes, minRadiusCenter));

        float pad = 6f;
        float span = radius * 2f + Math.max(btnSize, centerSize) + pad * 2f;

        WidgetGroup g = new WidgetGroup();
        g.setSize(span, span);
        float cx = span / 2f, cy = span / 2f;
        for(int i = 0; i < n; i++){
            MultiEntry me = cell.multiEntries.get(i);
            Schematic s = me == null || me.schematic.isEmpty() ? null : findSchematic(me.schematic);
            float angle = 90f - i * (360f / n);
            float rad = angle * Mathf.degRad;
            float bx = cx + Mathf.cos(rad) * radius;
            float by = cy + Mathf.sin(rad) * radius;

            Button b = buildRadialButton(s);
            b.setSize(btnSize, btnSize);
            b.setPosition(bx, by, Align.center);
            int idx = i;
            b.clicked(() -> onSection.get(idx, s, me));
            if(onSectionRemove != null) b.clicked(KeyCode.mouseRight, () -> onSectionRemove.get(idx, s, me));
            g.addChild(b);
        }

        Schematic centerS = findSchematic(cell.schematic);
        Button centerBtn = buildRadialButton(centerS);
        centerBtn.setSize(centerSize, centerSize);
        centerBtn.setPosition(cx, cy, Align.center);
        centerBtn.clicked(onCenter);
        if(onCenterRemove != null) centerBtn.clicked(KeyCode.mouseRight, onCenterRemove);
        g.addChild(centerBtn);

        return g;
    }

    /** Список секций с подробным управлением (выбор/поворот/очистка каждой) - то, что клик по спице превью не даёт. */
    void showMultiManageDialog(int row, int col, Runnable parentRebuild){
        BaseDialog dialog = new BaseDialog(Core.bundle.get("schematics-table.dialog.manage-multi.title"));
        dialog.addCloseButton();

        Runnable[] rebuild = {null};
        rebuild[0] = () -> {
            dialog.cont.clearChildren();
            CellData cell = page().cellForWrite(row, col);
            Table t = dialog.cont;
            t.pane(list -> {
                list.defaults().left().padBottom(6f);
                int n = Math.max(CellData.MIN_SECTIONS, Math.min(cell.sections, CellData.MAX_SECTIONS));
                for(int i = 0; i < n; i++){
                    int idx = i;
                    MultiEntry me = cell.multiEntries.get(idx);
                    Schematic s = me == null || me.schematic.isEmpty() ? null : findSchematic(me.schematic);

                    list.table(row2 -> {
                        row2.left();
                        row2.add(Core.bundle.format("schematics-table.dialog.manage-multi.section", idx + 1)).width(110f);
                        try{
                            if(s != null) row2.add(new SchematicImage(s)).size(40f).pad(2f);
                        }catch(Throwable ex){
                            //превью не критично - строка со схемой всё равно рабочая
                        }

                        String name = s != null ? s.name() : Core.bundle.get("schematics-table.dialog.manage-multi.empty");
                        row2.label(() -> name).width(mobile ? 160f : 260f).padLeft(6f).padRight(6f);

                        row2.button(Core.bundle.get("schematics-table.dialog.cell.pick"), () -> showSchematicPickerDialog(picked -> {
                            CellData cc = page().cellForWrite(row, col);
                            MultiEntry entry = cc.multiEntries.get(idx);
                            if(entry == null){
                                entry = new MultiEntry();
                                cc.multiEntries.put(idx, entry);
                            }
                            entry.schematic = picked;
                            data().save();
                            rebuild[0].run();
                            parentRebuild.run();
                        })).size(90f, 40f).padRight(6f);

                        row2.button("[accent]<", () -> {
                            MultiEntry entry = page().cellForWrite(row, col).multiEntries.get(idx);
                            if(entry != null){
                                entry.rotation = (entry.rotation + 3) % 4;
                                data().save();
                                rebuild[0].run();
                            }
                        }).size(36f);
                        row2.label(() -> {
                            MultiEntry e = page().cell(row, col) == null ? null : page().cell(row, col).multiEntries.get(idx);
                            return (e == null ? 0 : e.rotation) + "x90";
                        }).padLeft(4f).padRight(4f);
                        row2.button("[accent]>", () -> {
                            CellData cc = page().cellForWrite(row, col);
                            MultiEntry entry = cc.multiEntries.get(idx);
                            if(entry == null){
                                entry = new MultiEntry();
                                cc.multiEntries.put(idx, entry);
                            }
                            entry.rotation = (entry.rotation + 1) % 4;
                            data().save();
                            rebuild[0].run();
                        }).size(36f);

                        row2.button(Icon.trash, Styles.clearNonei, () -> {
                            page().cellForWrite(row, col).multiEntries.remove(idx);
                            data().save();
                            rebuild[0].run();
                            parentRebuild.run();
                        }).size(40f).padLeft(6f);
                    }).growX().row();
                }
            }).grow();
        };
        rebuild[0].run();
        dialog.show();
    }

    // ---------------------------------------------------------------- multi-ячейка: рантайм picker

    private WidgetGroup radialOverlay;

    void openRadialPicker(CellData cell){
        closeRadialPicker();

        {
            boolean any = !cell.schematic.isEmpty();
            if(!any){
                for(IntMap.Entry<MultiEntry> e : cell.multiEntries) if(!e.value.schematic.isEmpty()){ any = true; break; }
            }
            if(!any){
                ui.announce(Core.bundle.get("schematics-table.radial.empty"), 2f);
                return;
            }
        }

        WidgetGroup overlay = new WidgetGroup();
        overlay.setFillParent(true);
        overlay.touchable = Touchable.childrenOnly;

        Image dim = new Image(Tex.whiteui);
        dim.setColor(0f, 0f, 0f, 0.35f);
        dim.setFillParent(true);
        dim.clicked(this::closeRadialPicker);
        overlay.addChild(dim);

        WidgetGroup radial = buildRadialLayout(cell, 120f, 84f,
            () -> {
                Schematic s = findSchematic(cell.schematic);
                if(s != null){
                    placeSchematic(s, cell.rotation);
                }
                closeRadialPicker();
            },
            (idx, s, me) -> {
                if(s != null){
                    placeSchematic(s, me.rotation);
                }
                closeRadialPicker();
            });

        float cx = Mathf.clamp(Core.input.mouseX(), radial.getWidth() / 2f + 10f, Core.graphics.getWidth() - radial.getWidth() / 2f - 10f);
        float cy = Mathf.clamp(Core.input.mouseY(), radial.getHeight() / 2f + 10f, Core.graphics.getHeight() - radial.getHeight() / 2f - 10f);
        radial.setPosition(cx, cy, Align.center);
        overlay.addChild(radial);

        Core.scene.add(overlay);
        radialOverlay = overlay;
    }

    void closeRadialPicker(){
        if(radialOverlay != null){
            radialOverlay.remove();
            radialOverlay = null;
        }
    }

    /**
     * Текущие выбранные иконки ячейки в виде плоского списка (как в оригинале EUI++ - там это
     * был один comma-разделённый список в настройке, здесь эквивалент собирается на лету из
     * main+corners[0..2]; corners[3] намеренно не используется - ограничение MAX_CELL_ICONS=4,
     * как и в оригинале).
     */
    Seq<String> cellIconNames(CellData c){
        Seq<String> names = new Seq<>();
        if(c != null){
            if(c.main != null) names.add(c.main.name);
            for(int i = 0; i < 3; i++) if(c.corners[i] != null) names.add(c.corners[i].name);
        }
        return names;
    }

    /** Записывает плоский список обратно в main+corners[0..2] (порядок = порядок в списке); corners[3] всегда очищается. */
    void applyCellIconNames(CellData cc, Seq<String> names){
        cc.main = names.size > 0 ? new IconRef(names.get(0), SchemTableData.MAIN_ICON_DEFAULT_SIZE) : null;
        for(int i = 0; i < 3; i++) cc.corners[i] = names.size > i + 1 ? new IconRef(names.get(i + 1), SchemTableData.CORNER_ICON_DEFAULT_SIZE) : null;
        cc.corners[3] = null;
    }

    static final int MAX_CELL_ICONS = 4;

    /** Тоггл иконки в наборе ячейки - клик добавляет/убирает, до MAX_CELL_ICONS штук (ровно как toggleImageName в оригинале). */
    void toggleCellIcon(int row, int col, String name, Runnable rebuild){
        CellData cc = page().cellForWrite(row, col);
        Seq<String> names = cellIconNames(cc);
        int idx = names.indexOf(name);
        boolean added;
        if(idx >= 0){
            names.remove(idx);
            added = false;
        }else{
            if(names.size >= MAX_CELL_ICONS){
                ui.announce(Core.bundle.format("schematics-table.dialog.change-image.max-icons-announce-text", MAX_CELL_ICONS), 3f);
                return;
            }
            names.add(name);
            added = true;
        }
        applyCellIconNames(cc, names);
        data().save();
        ui.announce(Core.bundle.get(added
            ? "schematics-table.dialog.change-image.added-announce-text"
            : "schematics-table.dialog.change-image.removed-announce-text") + " " + name, 2f);
        rebuild.run();
    }

    String currentSchematicLabelText(String name){
        return name != null && !name.isEmpty()
            ? Core.bundle.get("schematics-table.dialog.pick-schematic.current") + " " + name
            : Core.bundle.get("schematics-table.dialog.pick-schematic.none-selected");
    }

    // ---------------------------------------------------------------- диалог страницы

    void showPageDialog(int index){
        SchemTableData d = data();
        if(index < 0 || index >= d.pages.size) return;
        Page p = d.pages.get(index);

        BaseDialog dialog = new BaseDialog(Core.bundle.get("schematics-table.dialog.page.title"));
        dialog.addCloseButton();

        Runnable[] rebuild = {null};
        rebuild[0] = () -> {
            dialog.cont.clearChildren();
            Table t = dialog.cont;
            t.defaults().left();

            t.table(nr -> {
                nr.add(Core.bundle.get("schematics-table.dialog.page.name") + ":").padRight(6f);
                nr.field(p.name, v -> {
                    p.name = v;
                    d.save();
                }).width(260f);
            }).row();

            t.table(ir -> {
                ir.add(Core.bundle.get("schematics-table.dialog.page.icon") + ":").padRight(6f);
                Drawable ic = p.icon.isEmpty() ? null : Icons.getIconDrawable(p.icon);
                ir.image(ic != null ? ic : defaultSchematicImage()).size(32f).padRight(6f);
                ir.button(Core.bundle.get("schematics-table.dialog.cell.pick"), () -> showIconPickDialog(name -> {
                    p.icon = name;
                    d.save();
                    rebuild[0].run();
                    rebuildTable();
                })).width(110f).height(40f);
            }).padTop(6f).row();

            //свой фиксированный размер КАЖДОЙ страницы; при уменьшении вылезшие ячейки не удаляются -
            //они скрыты, но остаются в данных и вернутся при обратном увеличении
            stepperRow(t, Core.bundle.get("schematics-table.dialog.page.rows"), () -> p.rows, v -> {
                p.rows = v;
                d.save();
                rebuildTable();
            }, 1, SchemTableData.MAX_ROWS);
            stepperRow(t, Core.bundle.get("schematics-table.dialog.page.cols"), () -> p.cols, v -> {
                p.cols = v;
                d.save();
                rebuildTable();
            }, 1, SchemTableData.MAX_COLS);

            t.table(br -> {
                br.button(Core.bundle.get("schematics-table.dialog.page.add"), Icon.add, () -> {
                    addPage();
                    dialog.hide();
                }).width(200f).height(50f).padRight(6f);
                br.button(Core.bundle.get("schematics-table.dialog.page.delete"), Icon.trash, () -> {
                    if(d.pages.size <= 1){
                        ui.announce(Core.bundle.get("schematics-table.dialog.page.last-page"), 2f);
                        return;
                    }
                    ui.showConfirm("@confirm",
                        Core.bundle.format("schematics-table.dialog.page.delete-confirm", pageTooltip(p, index)), () -> {
                            d.pages.remove(index);
                            if(currentPage >= d.pages.size) currentPage = d.pages.size - 1;
                            selection.clear();
                            d.save();
                            rebuildTable();
                            dialog.hide();
                        });
                }).width(200f).height(50f);
            }).padTop(12f).row();
        };
        rebuild[0].run();
        dialog.hidden(this::rebuildTable);
        dialog.show();
    }

    void stepperRow(Table t, String label, Intp get, Intc set, int min, int max){
        t.table(sr -> {
            sr.left();
            sr.add(label + ":").width(130f).left();
            sr.button("[accent]-", () -> {
                int v = get.get();
                if(v > min) set.get(v - 1);
            }).size(44f);
            sr.label(() -> String.valueOf(get.get())).width(50f).get().setAlignment(Align.center);
            sr.button("[accent]+", () -> {
                int v = get.get();
                if(v < max) set.get(v + 1);
            }).size(44f);
        }).padTop(6f).row();
    }

    // ---------------------------------------------------------------- пикер иконок

    void showIconPickDialog(Cons<String> onPicked){
        float size = mobile ? 320f : 640f;
        BaseDialog dialog = new BaseDialog(Core.bundle.get("schematics-table.dialog.change-image.title"));
        dialog.addCloseButton();
        addIconPickTable(dialog.cont, size, name -> {
            onPicked.get(name);
            dialog.hide();
        }, null);
        dialog.show();
    }

    /**
     * Браузер иконок по сворачиваемым категориям, встраивается прямо в переданный контейнер
     * (как в оригинале EUI++ - {@code addEditImageTable}). {@code isSelected == null} - режим
     * "один клик = один выбор" (страницы, старый {@link #showIconPickDialog}); иначе - режим
     * мульти-тоггла (диалог ячейки): кнопки становятся переключателями, подсвечивают уже
     * выбранные иконки, клик просто вызывает {@code onPicked} без закрытия контейнера.
     */
    void addIconPickTable(Table cont, float size, Cons<String> onPicked, Boolf<String> isSelected){
        cont.pane(table -> {
            for(Category category : IconCategoriesConfig.CATEGORIES){
                if(IconCategoriesConfig.isErrorCategory(category.name)) continue;

                Seq<String> iconsToDisplay;
                if(IconCategoriesConfig.USE_ICON_CATEGORIES){
                    if(category.name.equals("Other")) iconsToDisplay = IconCategoriesConfig.getOtherIcons();
                    else if(category.name.equals("Mods")) iconsToDisplay = IconCategoriesConfig.getModIconsCategory();
                    else iconsToDisplay = Seq.with(category.icons);
                }else{
                    iconsToDisplay = IconCategoriesConfig.getAllCategoryIcons();
                }

                if(iconsToDisplay.isEmpty() && !category.name.equals("Other") && !category.name.equals("Mods")) continue;

                String collapsedKey = "eui-iconCategory-collapsed-" + category.name;
                boolean[] isCollapsed = {Core.settings.getBool(collapsedKey, false)};

                Table iconsContent = new Table();
                int[] col = {0};
                for(String iconName : iconsToDisplay){
                    Drawable iconDrawable = Icons.getIconDrawable(iconName);

                    var cell = iconsContent.button(iconDrawable, isSelected != null ? Styles.clearTogglei : Styles.cleari, () -> onPicked.get(iconName))
                        .size(48f).pad(4f);
                    if(isSelected != null) cell.update(btn -> btn.setChecked(isSelected.get(iconName)));
                    var imageButton = cell.get();
                    imageButton.resizeImage(48f * 0.8f);

                    if(++col[0] % IconCategoriesConfig.ICONS_PER_ROW == 0) iconsContent.row();
                }

                table.row();
                table.table(null, headerTable -> {
                    headerTable.left().defaults().left();

                    headerTable.table(null, iconNameTable -> {
                        String categoryIconName = category.icon != null ? category.icon : "info";
                        iconNameTable.image(Icons.getIconDrawable(categoryIconName)).size(48f).pad(4f).padLeft(8f);
                        iconNameTable.add(category.name).color(Color.lightGray).padLeft(8f).padRight(8f);
                    });

                    headerTable.image(Tex.whiteui).color(Color.gray).height(3f).growX().pad(4f);

                    headerTable.button(Icon.downOpen, Styles.emptyi, () -> {
                        isCollapsed[0] = !isCollapsed[0];
                        Core.settings.put(collapsedKey, isCollapsed[0]);
                    }).update(btn -> {
                        if(!btn.getChildren().isEmpty() && btn.getChildren().first() instanceof Image img){
                            img.setDrawable(isCollapsed[0] ? Icon.upOpen : Icon.downOpen);
                        }
                        btn.setChecked(isCollapsed[0]);
                    }).size(40f).padLeft(8f).padRight(8f);
                }).growX().padTop(8f).padBottom(4f);

                table.row();
                Table wrapperTable = new Table();
                wrapperTable.collapser(collapserTable -> collapserTable.left().add(iconsContent), () -> isCollapsed[0]);
                table.add(wrapperTable).growX();
            }
        }).size(size * 2, size).scrollX(false).update(s -> iconScrollY = s.getScrollY()).get().setScrollYForce(iconScrollY);
    }

    // ---------------------------------------------------------------- schematic picker

    void showSchematicPickerDialog(Cons<String> onPicked){
        pickerOnPicked = onPicked;

        //fill-screen adaptive grid, recomputed on every open rather than fixed
        pickerColumns = Math.max(1, (int)((Core.graphics.getWidth() - 80) / SCHEMATIC_PICKER_SLOT_WIDTH));
        float paneHeight = Math.max(300, Core.graphics.getHeight() - 220);
        if(schematicPickerPaneCell != null){
            schematicPickerPaneCell.width(SCHEMATIC_PICKER_SLOT_WIDTH * pickerColumns).height(paneHeight);
            schematicPickerDialog.cont.invalidateHierarchy();
        }

        //recollected on every open (not cached) so freshly imported/created schematics show up without a restart
        allSchematicEntries = new Seq<>();
        for(Schematic s : schematics.all()) allSchematicEntries.add(new SchematicEntry(s, s.name()));
        allSchematicEntries.sort((a, b) -> a.name.compareTo(b.name));

        if(schematicPickerSearchField != null) schematicPickerSearchField.setText("");
        refreshSchematicPickerList("");
        schematicPickerDialog.show();
    }

    void refreshSchematicPickerList(String filterText){
        if(schematicPickerList == null) return;
        schematicPickerList.clearChildren();

        try{
            refreshSchematicPickerListInner(filterText);
        }catch(Throwable t){
            Log.err("[eui] schematics-table picker refreshList error", t);
            schematicPickerList.add("refreshList error: " + t).color(Color.scarlet).wrap().width(400f).row();
        }
    }

    void refreshSchematicPickerListInner(String filterText){
        String filterLower = filterText == null ? "" : filterText.toLowerCase().trim();
        int shown = 0;

        for(SchematicEntry entry : allSchematicEntries){
            if(!filterLower.isEmpty() && !entry.name.toLowerCase().contains(filterLower)) continue;

            schematicPickerList.add(buildSchematicPickerCard(entry.name, entry.schematic))
                .width(SCHEMATIC_PICKER_CARD_WIDTH).pad(SCHEMATIC_PICKER_CARD_PAD).top();
            shown++;
            if(shown % pickerColumns == 0) schematicPickerList.row();
        }

        if(shown == 0){
            schematicPickerList.add(Core.bundle.get("schematics-table.dialog.pick-schematic.no-results")).pad(12f).row();
        }
    }

    Button buildSchematicPickerCard(String name, Schematic schematic){
        Button card = new Button(Styles.defaulti);
        try{
            SchematicImage preview = new SchematicImage(schematic);
            card.add(preview).size(SCHEMATIC_PICKER_ICON_SIZE).pad(2f);
            card.row();
        }catch(Throwable t){
            Log.err("[eui] schematics-table picker preview failed for " + name, t);
        }
        card.add(name).width(SCHEMATIC_PICKER_ICON_SIZE).wrap().get().setAlignment(Align.center);
        card.clicked(() -> pickSchematic(name));
        return card;
    }

    void pickSchematic(String name){
        ui.announce(Core.bundle.get("schematics-table.dialog.change-image.setted-announce-text") + " " + name);
        if(pickerOnPicked != null) pickerOnPicked.get(name);
        schematicPickerDialog.hide();
    }

    // ---------------------------------------------------------------- preview panel

    void rebuildPreviewTable(){
        previewTable.clearChildren();

        var requirements = hovered.requirements();
        float powerConsumption = hovered.powerConsumption() * 60;
        float powerProduction = hovered.powerProduction() * 60;
        var core = player.core();

        previewTable.add(new SchematicImage(hovered)).maxSize(800f);
        previewTable.row();

        previewTable.table(null, requirementsTable -> {
            int[] i = {0};
            requirements.each((item, amount) -> {
                requirementsTable.image(item.uiIcon).left();
                requirementsTable.label(() -> {
                    if(core == null || state.rules.infiniteResources || core.items.has(item, amount)) return "[lightgray]" + amount;
                    return (core.items.has(item, amount) ? "[lightgray]" : "[scarlet]") + Math.min(core.items.get(item), amount) + "[lightgray]/" + amount;
                }).padLeft(2f).left().padRight(4f);

                if(++i[0] % 4 == 0) requirementsTable.row();
            });
        });

        previewTable.row();

        if(powerConsumption != 0 || powerProduction != 0){
            previewTable.table(null, powerTable -> {
                if(powerProduction != 0){
                    powerTable.image(Icon.powerSmall).color(Pal.powerLight).padRight(3f);
                    powerTable.add("+" + arc.util.Strings.autoFixed(powerProduction, 2)).color(Pal.powerLight).left();
                    if(powerConsumption != 0) powerTable.add().width(15f);
                }
                if(powerConsumption != 0){
                    powerTable.image(Icon.powerSmall).color(Pal.remove).padRight(3f);
                    powerTable.add("-" + arc.util.Strings.autoFixed(powerConsumption, 2)).color(Pal.remove).left();
                }
            });
        }
    }

    boolean previewTableVisibility(){
        return Core.settings.getBool("eui-ShowSchematicsPreview", true) && contentTable != null && contentTable.visible && hovered != null;
    }

    // ---------------------------------------------------------------- misc lookups

    /** Blocks.empty (EmptyFloor, "no block") - not Blocks.air (AirBlock, the placeable removal tool) - and read directly rather than through Icons/IconCategoriesConfig, to not depend on their cache init timing. */
    static Drawable defaultSchematicImage(){
        return new TextureRegionDrawable(Blocks.empty.uiIcon);
    }

    /**
     * Green ({@link Pal#heal}) when the core covers every requirement; below that, a gradient from red
     * ({@link Pal#remove}, ~nothing available) to orange (almost affordable) by the worst-covered
     * requirement's ratio. White when it can't be judged (no schematic/core, or infinite resources).
     */
    private static final Color affordabilityColor = new Color();

    static Color schematicAffordabilityColor(Schematic schematic){
        if(schematic == null || state.rules.infiniteResources) return Color.white;

        var core = player.core();
        if(core == null) return Color.white;

        float[] ratio = {1};
        schematic.requirements().each((item, amount) -> {
            if(amount > 0){
                float r = Math.min(1, (float)core.items.get(item) / amount);
                if(r < ratio[0]) ratio[0] = r;
            }
        });

        if(ratio[0] >= 1) return Pal.heal;
        return affordabilityColor.set(Pal.remove).lerp(Color.orange, ratio[0]);
    }

    String cellTooltip(CellData c, Schematic schematic){
        if(c != null && c.multi){
            String display = !c.label.isEmpty() ? c.label : Core.bundle.get("schematics-table.dialog.mode.multi");
            return Core.bundle.get("schematics-table.use-schematic") + " " + display;
        }
        if(schematic == null) return Core.bundle.get("schematics-table.default-cathegory-desktop-name");
        String display = c != null && !c.label.isEmpty() ? c.label : schematic.name();
        return Core.bundle.get("schematics-table.use-schematic") + " " + display;
    }

    static Schematic findSchematic(String name){
        if(name == null || name.isEmpty()) return null;
        for(Schematic s : schematics.all()) if(s.name().equals(name)) return s;
        return null;
    }

    boolean mobileDoubleTap(String name){
        long now = System.currentTimeMillis();
        if(name.equals(lastTapped) && now - lastTapTimeMs < 250){
            return true;
        }else{
            lastTapped = name;
            lastTapTimeMs = now;
            return false;
        }
    }

    // ---------------------------------------------------------------- external API (used by schematics import/export)

    /** Rebuild after an import replaces the table config/schematics, so the table picks up the change immediately. */
    public void rebuildTableIfBuilt(){
        try{
            if(built && contentTable != null){
                currentPage = 0;
                selection.clear();
                rebuildTable();
            }
        }catch(Throwable ignored){
        }
    }
}
