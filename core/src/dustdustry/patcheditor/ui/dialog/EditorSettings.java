package dustdustry.patcheditor.ui.dialog;

import arc.func.*;
import arc.graphics.*;
import arc.scene.Element;
import arc.scene.ui.TextButton.*;
import arc.scene.ui.layout.*;
import dustdustry.patcheditor.core.*;
import dustdustry.patcheditor.export.ObjectExporter.*;
import arc.*;
import arc.struct.*;
import dustdustry.patcheditor.core.JsonProcessor.*;
import dustdustry.patcheditor.ui.*;
import mindustry.*;
import mindustry.content.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.logic.LogicFx.*;
import mindustry.ui.*;
import mindustry.ui.dialogs.*;
import mindustry.ui.dialogs.SettingsMenuDialog.*;
import mindustry.ui.dialogs.SettingsMenuDialog.SettingsTable.*;

import static arc.Core.settings;

public class EditorSettings extends BaseDialog{
    private static final TextButtonStyle grayTogglet = new TextButtonStyle(){{
        down = over = Styles.flatOver;
        disabled = Styles.grayPanelDark;
        up = Styles.grayPanel;
        checked = Styles.flatDown;
        font = Fonts.def;
        fontColor = EPalettes.gray;
        downFontColor = overFontColor = EPalettes.lighterGray;
        checkedFontColor = Color.white;
    }};

    public EditorSettings(){
        super("@patch-editor.settings");

        fallback();

        shown(() -> {
            if(!cont.hasChildren()) setup();
        });
    }

    private void fallback(){
        if(settings.has("patch-editor.simplifyPatch")){
            boolean simplifyPatch = settings.getBool("patch-editor.simplifyPatch");
            settings.put("patch-editor.simplifyPath", simplifyPatch);
            settings.remove("patch-editor.simplifyPatch");
        }
    }

    private void setup(){
        SettingsTable table = new SettingsTable();
        buildSettings(table);
        cont.pane(Styles.noBarPane, table);
        addCloseButton();
    }

    public void buildSettings(SettingsTable table){
        Seq<Setting> settings = table.getSettings();
        table.checkPref("patch-editor.simplifyPath", true);
        table.checkPref("patch-editor.sugar.stacks", true);
        table.checkPref("patch-editor.magicExport.allowDefault", false);
        table.checkPref("patch-editor.editNotes", false);
        table.checkPref("patch-editor.rememberPath", false);
        table.checkPref("patch-editor.formatJson", false);

        table.sliderPref("patch-editor.undoLimit", 20, 0, 160, 20,s -> Core.bundle.format("setting.patch-editor.undoLimit.text", s));
        settings.add(new SingleEnumSettings("patch-editor.exportType", ExportType.values(), ExportType.hjson));
        settings.add(new SlotSettings(8, t -> {
            t.background(Styles.grayPanel).margin(8f).left();
            t.add("@patch-editor.settings.quickAccess").width(72f).wrap();
            t.image().color(Pal.lightishGray).width(2f).pad(8f, 8f,  8f, 16f).growY();
            t.table(EditorSettings::setupQuickButtons);
        }));
        //оригинал здесь ещё рисует баннер-ссылку на GitHub (иконка/автор из EVars.thisMod) -
        //у вшитой копии LoadedMod'а нет, баннер не переносили

        table.rebuild();
    }

    private static void setupQuickButtons(Table table){
        table.defaults().padRight(8f).fillX();

        table.button(b -> {
            b.margin(8f).left();
            b.image(Tex.alphaaaa).size(Vars.iconMed).padRight(8f);
            b.add("@patch-editor.settings.quickAccess.texture");
        },  Styles.cleari, () -> EUI.textureRegionSelector.select(r -> false));

        table.button(b -> {
            b.margin(8f);
            Element element = EffectElems.getEffectElem(new EffectEntry(Fx.reactorExplosion), b.getClickListener());
            b.add(element).size(Vars.iconMed).padRight(8f);
            b.add("@patch-editor.settings.quickAccess.effect");
        },  Styles.cleari, () -> EUI.effectSelector.select(r -> false));

        table.row();

        table.button(b -> {
            b.margin(8f).left();
            b.add(new BorderImage()).size(Vars.iconMed).padRight(8f).color(Pal.accent);
            b.add("@patch-editor.settings.quickAccess.color");
        },  Styles.cleari, () -> EUI.colorSelector.select(r -> false));

        table.button(b -> {
            b.margin(8f).left();
            b.image(Icon.play).size(Vars.iconMed).padRight(8f);
            b.add("@patch-editor.settings.quickAccess.sound");
        },  Styles.cleari, () -> EUI.soundSelector.select(r -> false));

        table.row();

        table.button(b -> {
            b.margin(8f).left();
            b.image(Icon.infoCircle).size(Vars.iconMed).padRight(8f);
            b.add("@patch-editor.settings.quickAccess.usageHint");
        },  Styles.cleari, () -> EUI.showUsageInfo(null));
    }

    public static ExportConfig getExportConfig(){
        ExportConfig config = new ExportConfig();
        config.allowDefault = settings.getBool("patch-editor.magicExport.allowDefault");
        return config;
    }

    public static PatchExportOptions getPatchExportOptions(){
        String exportType = settings.getString("patch-editor.exportType");
        OutputFormat format = ExportType.hjson.is(exportType) ? OutputFormat.hjson : OutputFormat.json;
        return new PatchExportOptions(
        settings.getBool("patch-editor.sugar.stacks"),
        settings.getBool("patch-editor.simplifyPath"),
        settings.getBool("patch-editor.formatJson"),
        format
        );
    }

    public enum ExportType{
        hjson, json;

        public boolean is(String name){
            return name().equals(name);
        }
    }

    public static class SingleEnumSettings extends Setting{
        public Enum<?>[] enums;
        public Enum<?> def;

        public SingleEnumSettings(String name, Enum<?>[] enums, Enum<?> def){
            super(name);

            this.enums = enums;
            this.def = def;

            settings.defaults(name, def.name());
        }

        @Override
        public void add(SettingsTable table){
            table.table(cont -> {
                cont.background(Styles.grayPanel).margin(8f);

                cont.table(top -> {
                    top.left();
                    top.image(Icon.settingsSmall);
                    top.add(title).padLeft(8f);
                }).growX().row();

                cont.table(buttons -> {
                    for(Enum<?> anEnum : enums){
                        String text = Core.bundle.get(name + "." + anEnum.name(), anEnum.name());
                        buttons.button(text, grayTogglet, () -> settings.put(name, anEnum.name()))
                        .margin(8f).growX().checked(b -> anEnum.name().equals(settings.getString(name)));
                    }
                }).padTop(4f).growX();

                addDesc(cont);
            }).padTop(6f).growX();

            table.row();
        }
    }

    public static class SlotSettings extends Setting{
        public float padding;
        protected Cons<Table> cons;

        public SlotSettings(Cons<Table> cons){
            this(6f, cons);
        }

        public SlotSettings(float padding, Cons<Table> cons){
            super("");

            this.cons = cons;
            this.padding = padding;
        }

        @Override
        public void add(SettingsTable table){
            table.table(cons).padTop(padding).padBottom(padding).growX().row();
            table.row();
        }
    }
}
