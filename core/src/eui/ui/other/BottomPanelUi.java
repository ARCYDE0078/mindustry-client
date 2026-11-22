package eui.ui.other;

import arc.Core;
import arc.Events;
import arc.scene.style.Drawable;
import arc.scene.ui.ScrollPane;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import eui.icons.Icons;
import eui.interact.AutofillPriorityDialog;
import mindustry.game.EventType.ClientLoadEvent;
import mindustry.game.EventType.Trigger;
import mindustry.gen.Icon;
import mindustry.type.UnitType;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.BaseDialog;
import sonkaextras.UiStyle;

import static mindustry.Vars.content;
import static mindustry.Vars.ui;

/**
 * Collapsible strip of interact toggles docked bottom-center: the auto-fill priority editor button
 * (whose priorities now feed the native {@code mindustry.client.utils.AutoTransfer} - the eui AutoFill
 * loop plus its interact-with-core/auto-fill toggles were removed in the dedupe pass, as was the
 * schematic area-select toggle, covered by native "F" + qol's CopyAnywhere) and a unit-type picker for
 * auto-unit-control. Ported from ui/other/bottom-panel-ui.js.
 */
public class BottomPanelUi{
    private record UnitCategory(String name, String[] units){}

    private static final UnitCategory[] UNIT_CATEGORIES = {
        new UnitCategory("Mech", new String[]{"dagger", "mace", "fortress", "scepter", "reign"}),
        new UnitCategory("Mech2", new String[]{"nova", "pulsar", "quasar", "vela", "corvus"}),
        new UnitCategory("Legs", new String[]{"crawler", "atrax", "spiroct", "arkyid", "toxopid"}),
        new UnitCategory("Air", new String[]{"flare", "horizon", "zenith", "antumbra", "eclipse"}),
        new UnitCategory("Air2", new String[]{"mono", "poly", "mega", "quad", "oct"}),
        new UnitCategory("Naval", new String[]{"risso", "minke", "bryde", "sei", "omura"}),
        new UnitCategory("Naval", new String[]{"retusa", "oxynoe", "cyerce", "aegires", "navanax"}),
        new UnitCategory("Tank", new String[]{"stell", "locus", "precept", "vanquish", "conquer"}),
        new UnitCategory("Hover", new String[]{"elude", "avert", "obviate", "quell", "disrupt"}),
        new UnitCategory("spider", new String[]{"merui", "cleroi", "anthicus", "tecta", "collaris"}),
        new UnitCategory("Neoplasm", new String[]{"renale", "latum"}),
        new UnitCategory("Core Units", new String[]{"alpha", "beta", "gamma", "evoke", "incite", "emanate"}),
    };

    private final AutofillPriorityDialog autofillPriorityDialog;

    private BaseDialog selectUnitDialog;
    private Table contentTable;
    private Table unitTable;
    private boolean built = false;
    private boolean showSettings = Core.settings.getBool("eui-bottompanel-settings", false);

    private String selectedUnit;

    public BottomPanelUi(AutofillPriorityDialog autofillPriorityDialog){
        this.autofillPriorityDialog = autofillPriorityDialog;
        selectedUnit = Core.settings.getString("eui-auto-unit", null);

        Events.on(ClientLoadEvent.class, e -> {
            ui.hudGroup.fill(null, t -> {
                contentTable = t.table().get();
                t.center().bottom();
                t.pack();
            });
            contentTable.visibility = () -> ui.hudfrag.shown && built;

            buildSelectUnitDialog();
        });

        Events.run(Trigger.update, this::update);
    }

    void update(){
        if(!Core.settings.getBool("eui-showInteractSettings", true)){
            if(built) clearTable();
            return;
        }
        if(!built) rebuildTable();
    }

    void rebuildTable(){
        clearTable();
        buildTable();
    }

    void buildTable(){
        if(contentTable == null) return;
        built = true;

        //скиновая унификация: шеврон - плоский clearNonei (как FlipButton scheme-панели), а не
        //рамочный selecti; развёрнутый ряд кнопок получает канонный black6-фон тулбара из UiStyle
        //вместо голых иконок, висящих в воздухе
        contentTable.button(Icon.upOpen, UiStyle.titleButton(), () -> {
            showSettings = !showSettings;
            Core.settings.put("eui-bottompanel-settings", showSettings);
            rebuildTable();
        }).width(64f).height(16f).marginBottom(3f);
        if(!showSettings) return;

        contentTable.row();
        Table buttonTable = contentTable.table(UiStyle.titleBg()).get();
        buttonTable.margin(UiStyle.PANEL_MARGIN / 2f);
        buttonTable.defaults().size(32f);

        //переключатели автофилла: две половины (брать из ядра / класть в постройки); состояние живёт
        //в AutoTransfer и в настройках - те же ключи, что в меню настроек и у иконок HUD-панели режимов
        autofillToggle(buttonTable, Icon.download, "client.setting.autotransfer-fromcores.name",
            () -> mindustry.client.utils.AutoTransfer.fromCores,
            () -> mindustry.client.utils.AutoTransfer.changeFromCores(!mindustry.client.utils.AutoTransfer.fromCores));
        autofillToggle(buttonTable, Icon.upload, "client.setting.autotransfer-deposit.name",
            () -> mindustry.client.utils.AutoTransfer.deposit,
            () -> mindustry.client.utils.AutoTransfer.changeDeposit(!mindustry.client.utils.AutoTransfer.deposit));

        buttonTable.button(Icon.list, Styles.cleari, autofillPriorityDialog::show).get().resizeImage(32f * 0.8f);

        buttonTable.button(Icons.getIconDrawable(selectedUnit), Styles.cleari, () -> selectUnitDialog.show())
            .get().resizeImage(32f * 0.8f);
    }

    private static void autofillToggle(Table t, arc.scene.style.Drawable icon, String nameKey, arc.func.Boolp state, Runnable toggle){
        t.button(icon, Styles.clearNoneTogglei, toggle).update(b -> b.setChecked(state.get()))
            .tooltip(Core.bundle.get(nameKey), true).get().resizeImage(32f * 0.8f);
    }

    void clearTable(){
        if(!built) return;
        contentTable.clearChildren();
        built = false;
    }

    // ---------------------------------------------------------------- unit picker

    void buildSelectUnitDialog(){
        float size = 568f;

        selectUnitDialog = new BaseDialog(Core.bundle.get("schematics-table.dialog.change-image.title"));
        selectUnitDialog.addCloseButton();

        Seq<UnitType> allUnits = new Seq<>();
        for(UnitType u : content.units()){
            if(u != null && !u.hidden && u.uiIcon != null && u.uiIcon.found()) allUnits.add(u);
        }

        ScrollPane scrollPane = new ScrollPane(null);
        selectUnitDialog.cont.add(scrollPane).size(size, size);

        Table scrollContent = new Table();
        scrollPane.setWidget(scrollContent);

        Table categoryTable = scrollContent.table().get();
        categoryTable.defaults().size(40f).pad(2f);

        categoryTable.button(Icon.list, Styles.cleari, () -> showUnitCategory(-1, allUnits)).get().resizeImage(40f * 0.8f);

        for(int i = 0; i < UNIT_CATEGORIES.length; i++){
            int catIndex = i;
            UnitCategory cat = UNIT_CATEGORIES[i];
            Drawable catIcon = Icons.getIconDrawable(cat.units.length > 0 ? cat.units[0] : "cancel");

            categoryTable.button(catIcon, Styles.cleari, () -> showUnitCategory(catIndex, allUnits)).get().resizeImage(40f * 0.8f);

            if((i + 1) % 5 == 0) categoryTable.row();
        }
        categoryTable.row();

        scrollContent.add().height(8f).row();

        unitTable = scrollContent.table().get();
        showUnitCategory(-1, allUnits);
    }

    void showUnitCategory(int categoryIndex, Seq<UnitType> allUnits){
        if(unitTable == null) return;
        unitTable.clear();

        Seq<UnitType> toShow = new Seq<>();
        if(categoryIndex == -1){
            for(UnitType u : allUnits) if(shouldShowUnit(u.name)) toShow.add(u);
            toShow.sort((a, b) -> a.name.compareTo(b.name));
        }else{
            for(String name : UNIT_CATEGORIES[categoryIndex].units){
                UnitType u = findByName(allUnits, name);
                if(u != null && shouldShowUnit(name)) toShow.add(u);
            }
        }

        int r = 0;
        for(UnitType unit : toShow){
            String settedName = unit.name;
            var imageButton = unitTable.button(unit.uiIcon != null ? new arc.scene.style.TextureRegionDrawable(unit.uiIcon) : Icon.cancel, Styles.cleari, () -> {
                Core.settings.put("eui-auto-unit", settedName);
                selectedUnit = settedName;
                rebuildTable();
                selectUnitDialog.hide();
            }).size(48f).pad(4f).get();
            imageButton.resizeImage(48f * 0.8f);

            if(++r % 10 == 0) unitTable.row();
        }
    }

    static UnitType findByName(Seq<UnitType> units, String name){
        for(UnitType u : units) if(u.name.equals(name)) return u;
        return null;
    }

    static boolean shouldShowUnit(String unitName){
        if("block".equals(unitName)) return false;
        if(eui.units.CoreUnits.includes(unitName)) return false;
        return !eui.units.Blacklist.includes(unitName);
    }
}
