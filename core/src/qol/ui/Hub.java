package qol.ui;

import arc.Core;
import arc.func.Boolp;
import arc.func.Cons;
import arc.func.Prov;
import arc.graphics.Color;
import arc.scene.style.Drawable;
import arc.scene.ui.ImageButton;
import arc.scene.ui.Tooltip;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import arc.util.Nullable;
import mi2u.MI2UVars;
import mi2u.ui.elements.Mindow2;
import mindustry.gen.Icon;
import mindustrytool.features.FeatureManager;
import mindustrytool.features.quickaccess.QuickAccessFeature;
import qol.core.Feature;
import scheme.SchemeVars;
import sonkaextras.UiStyle;

import static mindustry.Vars.state;
import static mindustry.Vars.ui;

/**
 * Central always-present {@link QolWindow}, now the SINGLE control panel for every baked-in mod's
 * floating HUD window - not only qol's own:
 * <ul>
 * <li><b>Title bar</b> (unchanged): one toggle per qol feature that has its own window, plus the
 *     settings gear. Enabling/disabling the features themselves lives only in Settings - the hub used
 *     to also carry an enable checkbox per feature in its body, but having the same on/off control in
 *     two separate windows was confusing (e.g. a feature's window-visibility toggle here would still
 *     let you re-open a disabled feature's window, since it didn't know about the OTHER checkbox's
 *     state). One control surface for enabling, this one for which windows are showing.</li>
 * <li><b>Body</b> (the "единая панель управления" extension): one section per foreign baked-in mod
 *     with show/hide toggles that drive each mod's OWN mechanism - mi2u Mindow2s via
 *     {@code addTo(scene.root)}/{@code close()} (exactly what MI2UI's own title buttons call, plus the
 *     {@code MI2UI.show*} pref where mi2u persists one), eui panels via their {@code eui-Show*}
 *     settings keys (the panels poll those every tick), the scheme build-tools panel via its
 *     {@link scheme.ui.FlipButton}, and mindustrytool's Quick Access via
 *     {@link FeatureManager#toggle}. The original toggles all keep working - this is an additional
 *     surface, not a replacement, and no settings keys were renamed.</li>
 * </ul>
 * Every foreign reference is read lazily inside the button lambdas, never captured at build time:
 * QolSuiteMod registers its ClientLoadEvent listener FIRST in {@code Main.init()}, so this window is
 * built before mi2u/scheme/mindustrytool have initialized their statics - and any of them may also
 * stay null forever (self-disable when the real external mod is installed). A button whose target
 * mod isn't (yet) initialized just shows disabled/unchecked until it is.
 */
public class Hub extends QolWindow{

    final Seq<Feature> features;

    public Hub(Seq<Feature> features){
        super("qol-hub", "qol.hub.title");
        this.features = features;
        visible(() -> state.isGame() && ui.hudfrag.shown);
        rebuild();
    }

    @Override
    protected void setupTitleButtons(Table titleExtras){
        titleExtras.defaults().size(UiStyle.TITLE_BUTTON_SIZE).pad(2f);
        for(Feature f : features){
            if(!f.hasWindow()) continue;
            QolWindow win = f.window();
            ImageButton btn = new ImageButton(Icon.list, UiStyle.titleToggle());
            btn.clicked(() -> {
                if(win.attached()) win.detach(); else win.attach();
            });
            btn.update(() -> btn.setChecked(win.attached()));
            //a disabled feature's window is force-closed by Feature.setEnabled - don't let this button
            //re-open it; the icon greys out via the style's own disabled look
            btn.setDisabled(() -> !f.isEnabled());
            btn.addListener(new Tooltip(t -> t.background(null).add(Core.bundle.get(f.titleKey(), f.titleKey()))));
            titleExtras.add(btn);
        }
        ImageButton settingsBtn = new ImageButton(Icon.settings, UiStyle.titleButton());
        settingsBtn.clicked(() -> {
            ui.settings.show();
        });
        titleExtras.add(settingsBtn).size(UiStyle.TITLE_BUTTON_SIZE).pad(2f);
    }

    @Override
    protected void setupCont(Table cont){
        cont.margin(UiStyle.PANEL_MARGIN);

        section(cont, "qol.hub.sec.mi2u", t -> {
            mindowToggle(t, Icon.wrench, "MI2UI.MI2U", () -> MI2UVars.mi2ui, "showMain");
            mindowToggle(t, Icon.chat, "Emojis.MI2U", () -> MI2UVars.emojis, "showEmojis");
            mindowToggle(t, Icon.production, "CoreInfo.MI2U", () -> MI2UVars.coreInfo, "showCoreInfo");
            mindowToggle(t, Icon.map, "Minimap.MI2U", () -> MI2UVars.mindowmap, "showMinimap");
            mindowToggle(t, Icon.waves, "WaveInfo.MI2U", () -> MI2UVars.waveInfo, null);
            mindowToggle(t, Icon.android, "AI.MI2U", () -> MI2UVars.aiMindow, null);
            mindowToggle(t, Icon.zoom, "WorldFinder.MI2U", () -> MI2UVars.finderMindow, null);
            //MonitorCanvas - WidgetGroup, не Mindow2; тот же способ показа, что у кнопки в MI2UI
            toggle(t, Icon.chartBar, "qol.hub.mi2u.monitors",
                () -> MI2UVars.monitorCanvas != null,
                () -> MI2UVars.monitorCanvas != null && MI2UVars.monitorCanvas.hasParent(),
                () -> {
                    var mc = MI2UVars.monitorCanvas;
                    if(mc == null) return;
                    if(!mc.remove()) Core.scene.add(mc);
                });
        });

        section(cont, "qol.hub.sec.eui", t -> {
            //eui-панели каждый тик сами читают свои ключи - переключение настройки и есть их
            //родной механизм show/hide (тот же, что чекбоксы в секции eui вкладки "Моды")
            settingToggle(t, Icon.units, "eui-ShowUnitTable", true);
            settingToggle(t, Icon.paste, "eui-ShowSchematicsTable", true);
            settingToggle(t, Icon.effect, "eui-showInteractSettings", true);
            settingToggle(t, Icon.zoom, "eui-ShowBlockInfo", false);
        });

        section(cont, "qol.hub.sec.scheme", t -> {
            toggle(t, Icon.hammer, "qol.hub.scheme.toolbar",
                () -> SchemeVars.hudfrag != null,
                () -> SchemeVars.hudfrag != null && SchemeVars.hudfrag.building.fliped,
                () -> {
                    if(SchemeVars.hudfrag != null) SchemeVars.hudfrag.building.flip();
                });
        });

        section(cont, "qol.hub.sec.mdt", t -> {
            toggle(t, Icon.menu, "feature.quick-access-hud",
                () -> quickAccess() != null,
                () -> {
                    var qa = quickAccess();
                    return qa != null && qa.isEnabled();
                },
                () -> {
                    var qa = quickAccess();
                    if(qa != null) FeatureManager.getInstance().toggle(qa);
                });
        });
    }

    /** Пока mindustrytool не инициализировался (или стоит внешний мод) - null; ищем лениво каждый раз. */
    @Nullable
    static QuickAccessFeature quickAccess(){
        return FeatureManager.getInstance().getFeature(QuickAccessFeature.class);
    }

    /**
     * One body row: dim section label in the left column, that mod's toggle buttons in the right one.
     * Both cells of every section live in the same two Table columns, so the labels and the button
     * rows align vertically across sections for free.
     */
    void section(Table cont, String titleKey, Cons<Table> builder){
        var label = cont.add(Core.bundle.get(titleKey, titleKey)).left().padRight(8f).get();
        label.setColor(Color.lightGray);
        label.setFontScale(0.9f);
        cont.table(t -> {
            t.left();
            t.defaults().size(UiStyle.TITLE_BUTTON_SIZE).pad(2f);
            builder.get(t);
        }).growX().left().row();
    }

    /**
     * Flat toggle in the shared window-button style. All three lambdas re-read their target on every
     * call (see class javadoc for why nothing may be captured at build time).
     */
    void toggle(Table t, Drawable icon, String tooltipKey, Boolp available, Boolp checked, Runnable clicked){
        ImageButton btn = new ImageButton(icon, UiStyle.titleToggle());
        btn.clicked(clicked);
        btn.update(() -> btn.setChecked(checked.get()));
        btn.setDisabled(() -> !available.get());
        btn.addListener(new Tooltip(tt -> tt.background(null).add(Core.bundle.get(tooltipKey, tooltipKey))));
        t.add(btn);
    }

    /**
     * Toggle for one mi2u Mindow2, driven the way mi2u itself shows/hides them: {@code addTo(scene
     * root)}/{@code close()} - identical to MI2UI's own title-pane buttons. For the windows whose
     * shown-state mi2u persists through a pref on MI2UI's SettingHandler (emojis/core info/minimap),
     * {@code showPref} names that key so the state survives a restart the same way it does when
     * toggled from mi2u's own settings checkbox; the others (mi2u never persists them) pass null.
     */
    void mindowToggle(Table t, Drawable icon, String tooltipKey, Prov<Mindow2> win, @Nullable String showPref){
        toggle(t, icon, tooltipKey,
            () -> win.get() != null,
            () -> {
                Mindow2 m = win.get();
                return m != null && !m.closed();
            },
            () -> {
                Mindow2 m = win.get();
                if(m == null) return;
                boolean show = m.closed();
                if(show) m.addTo(Core.scene.root); else m.close();
                if(showPref != null && MI2UVars.mi2ui != null && MI2UVars.mi2ui.settings != null){
                    MI2UVars.mi2ui.settings.putBool(showPref, show);
                }
            });
    }

    /**
     * Toggle backed by a plain Core.settings boolean (the eui panels poll their key every tick, so
     * flipping the setting IS the mod's own show/hide mechanism, same as its settings checkbox).
     */
    void settingToggle(Table t, Drawable icon, String settingKey, boolean def){
        toggle(t, icon, "setting." + settingKey + ".name",
            () -> true,
            () -> Core.settings.getBool(settingKey, def),
            () -> Core.settings.put(settingKey, !Core.settings.getBool(settingKey, def)));
    }
}
