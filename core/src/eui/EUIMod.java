package eui;

import arc.Core;
import arc.Events;
import arc.util.Log;
import eui.core.ButtonSetting;
import eui.core.LabelSetting;
import eui.input.CoreDrag;
import eui.input.Drag;
import eui.interact.ActionDelayHotkey;
import eui.interact.AmmoPriorityDialog;
import eui.interact.AutofillPriorityDialog;
import eui.interact.SourceBlocksDialog;
import eui.interact.AutoUnit;
import eui.other.Mine;
import eui.ui.alerts.LosingSupport;
import eui.ui.alerts.UnderAttack;
import eui.ui.blocks.BlockInfoUi;
import eui.ui.blocks.EfficiencyOverlay;
import eui.ui.blocks.ProgressBarOverlay;
import eui.ui.other.BottomPanelUi;
import eui.ui.other.ResourceRateUi;
import eui.ui.other.SchematicsImportExport;
import eui.ui.other.SchematicsTableUi;
import eui.ui.units.DrawCycle;
import eui.ui.units.UnitsTableUi;
import mindustry.client.ui.ModsSettings;
import mindustry.game.EventType.ClientLoadEvent;
import mindustry.game.EventType.Trigger;

import static mindustry.Vars.mobile;

/**
 * Baked directly into the client as native code (not loaded via the mod system), same approach as
 * {@link qol.QolSuiteMod} - see its javadoc and {@code mindustry.client.Main.init()} for why this has to
 * be instantiated before {@code ClientLoadEvent} fires. This is Extended UI++'s own independent
 * {@code eui.*} package / {@code eui-*} settings-key namespace - deliberately not sharing any of
 * qol-suite's classes (own {@link eui.core.LabelSetting}/{@link eui.core.ButtonSetting} instead of qol's,
 * own section in the shared "Mods" tab registered through the neutral
 * {@link mindustry.client.ui.ModsSettings}, not through qol code), so either baked-in mod can be
 * disabled/removed on its own without touching the other.
 * <p>
 * Ported from the "extended-ui" Rhino mod's {@code scripts/main.js}, which loaded every feature module
 * through a single-point-of-failure-tolerant {@code require()} chain (see {@link eui.input.Drag}'s
 * javadoc for why that indirection isn't needed here) - this constructor is the direct Java equivalent of
 * that file's {@code coreModules} loop, minus the try/catch-per-module wrapper Rhino needed. Every
 * feature module from the source is ported and wired in below, grouped by the three porting phases they
 * landed in (input/automation, HUD/overlays, schematics-table/settings/bottom-panel) - see each feature's
 * own class javadoc for what it does and any behavioural notes from the port.
 * <p>
 * The settings category is likewise simpler than the source: {@code ui/other/settings-ui.js} hid almost
 * all of its prefs behind a separate custom dialog opened from a single button, purely to work around
 * Rhino not being able to natively subclass {@code SettingsMenuDialog.SettingsTable.Setting} (it had to
 * fall back to a dynamic {@code extend(...)} hack, with its own try/catch fallback for when that hack
 * failed). {@link eui.core.LabelSetting}/{@link eui.core.ButtonSetting} are ordinary compiled Java
 * subclasses with no such risk, so every pref lives directly in one flat "Extended UI++" section of the
 * shared "Mods" tab here - same as {@code qol.QolSuiteMod.buildSettings()} already does.
 * <p>
 * KNOWN COLLISIONS with already-baked-in client/qol-suite functionality (ported anyway, per the task
 * spec - just flagged here):
 * <ul>
 * <li>RESOLVED (dedupe pass): {@code eui.interact.AutoFill} vs. this client's own
 * {@code mindustry.client.utils.AutoTransfer} - both forked from the same Extended-UI upstream and did
 * overlapping core item hand-off. The eui loop and its bottom-panel toggle ("eui-auto-fill", plus the
 * "eui-interact-core" gate only it read) were removed; its unique value - the per-block-type priority
 * config ({@link AutofillPriorityDialog}, key "eui.autofill.priority") - was merged into AutoTransfer as
 * a service-order modifier (-2 = exclude a block). The dialog stays, now configuring AutoTransfer.
 * 2026-08-27: AutoTransfer's own decision algorithm (aggregate every building's needs over the whole
 * range, then transfer one round's worth of the single most-needed item) turned out worse in practice
 * than eui AutoFill's original greedy one - sonka asked for AutoTransfer's internals to be reworked to
 * match eui AutoFill's approach (commit to the single best candidate - deposit over fetch at equal
 * priority - the moment it's found, instead of scanning everything first). AutoTransfer.transfer() now
 * runs that ported algorithm; nothing about the eui package itself changed, only AutoTransfer's own
 * code (see AutoTransfer.kt's own doc comments on transfer()/pickFetchItem()). Same day, a follow-up:
 * the from-scratch settings-slider delay bolted onto AutoTransfer.init() turned out to be storing the
 * wrong boxed type for its own getFloat() read, crashing startup - and even fixed, it was still a
 * different pacing model from eui AutoFill's, which sonka specifically wanted mounted as-is. So the
 * slider and its "autotransfer-transferdelay" key were dropped, and AutoTransfer's update()/transfer()
 * now gate directly on {@link eui.interact.InteractTimer} (the exact cooldown eui AutoFill used, driven
 * by "eui-action-delay" - Settings > Mods > Extended UI++, plus its Alt+=/Alt+- binds) instead of a
 * homegrown ticks-since-last-round timer.</li>
 * <li>RESOLVED (dedupe pass): {@code eui.other.ExtendZoom} vs. this fork's native "Min Zoom" settings
 * slider (Settings > Client, key "minzoom") - removed outright: {@code Renderer.minScale()} only reads
 * the {@code renderer.minZoom} field it wrote during logic cutscenes (and the
 * {@code min/maxZoomInGame} fields recomputed from it every frame are never read anywhere), so outside
 * cutscenes the port was fully inert, and the native slider already covers zooming out further.
 * Removing it also restores the vanilla cutscene zoom bounds the repo comment on those fields asks to
 * keep ("don't change or vanilla compat breaks").</li>
 * <li>RESOLVED (dedupe pass): {@code eui.interact.SchematicSelector} (bottom-panel toggle + drag) vs.
 * this engine's own native {@code Binding.schematicSelect} (hold "F", drag over built tiles) - removed:
 * with a unit the native gesture does the same select-capture-arm job, and while spectating qol-suite's
 * {@code CopyAnywhereFeature} (same F key, dead-player-gated) covers area-capture-to-library; arming
 * for placement without a unit was useless anyway since placing requires one. Its
 * {@code eui.util.Schematics} helper went with it.</li>
 * <li>RESOLVED (dedupe pass): {@code eui.input.ConveyorDrag} ("eui-DragPathfind") vs. this engine's own
 * native conveyor drag-placement, which already routes around obstacles automatically
 * ({@code Placement.pathfindLine(...)}, gated by the native "conveyorpathfinding" setting, called from
 * {@code InputHandler.java} whenever a conveyor/rail is drag-placed normally) - removed along with its
 * {@code eui.ai.ConveyorPathfind} helper; qol-suite's BridgeToCore covers the bridge/junction
 * route-to-core niche. {@link CoreDrag} ("eui-DragBlock", drag from a core to auto-place a vault) has
 * no native equivalent, so it stays.</li>
 * <li>RESOLVED (dedupe pass): {@code eui-showMinimap} vs. the native Settings > Graphics "Minimap"
 * checkbox - the eui toggle was a plain per-frame proxy writing the same {@code Core.settings} key
 * ("minimap"); removed, the native checkbox remains the single switch.</li>
 * </ul>
 */
public class EUIMod{
    private AutofillPriorityDialog autofillPriorityDialog;
    private AmmoPriorityDialog ammoPriorityDialog;
    private SourceBlocksDialog sourceBlocksDialog;
    private SchematicsImportExport schematicsImportExport;

    public EUIMod(){
        //self-disable if the original jar/script mod is still dropped in the mods folder - two copies
        //would double-register every event handler. "extended-ui" is this mod's internal id from its own
        //mod.hjson (displayName "Extended UI++", but the id itself was never renamed - see memory).
        if(mindustry.Vars.mods.locateMod("extended-ui") != null){
            Log.info("[eui] External extended-ui script mod is also loaded - baked-in copy is standing down.");
            return;
        }

        //форс-инициализация EuiBinding: KeyBind.add(...) в его статике должен отработать до
        //открытия диалога биндов (тот же приём, что у MI2UMod с MBinding)
        eui.input.EuiBinding.init();

        //--- phase A: input/automation ---
        new AutoUnit();
        autofillPriorityDialog = new AutofillPriorityDialog();
        ammoPriorityDialog = new AmmoPriorityDialog();
        sourceBlocksDialog = new SourceBlocksDialog();
        new ActionDelayHotkey();
        new Mine();

        CoreDrag coreDrag = new CoreDrag();

        Events.run(Trigger.update, Drag::update);
        Events.run(Trigger.draw, coreDrag::draw);

        //--- phase B: HUD/overlays ---
        new BlockInfoUi();
        new EfficiencyOverlay();
        new ProgressBarOverlay();
        new DrawCycle();
        new UnitsTableUi();
        new ResourceRateUi();
        new LosingSupport();
        new UnderAttack();

        //--- phase C: schematics table, import/export, bottom panel ---
        SchematicsTableUi schematicsTableUi = new SchematicsTableUi();
        schematicsImportExport = new SchematicsImportExport(schematicsTableUi);
        new BottomPanelUi(autofillPriorityDialog);

        Events.on(ClientLoadEvent.class, e -> buildSettings());
    }

    /**
     * The "Extended UI++" section of the shared "Mods" settings tab (see
     * {@link mindustry.client.ui.ModsSettings} - one tab for every baked-in mod), sectioned by a bold
     * {@link LabelSetting} header per group of related prefs - grows across phases as more features
     * land. The auto-fill priority dialog and the schematic area-select tool aren't opened from here
     * (matching the source): both are opened from the bottom panel's buttons instead (phase C), not the
     * settings screen.
     */
    void buildSettings(){
        ModsSettings.section("modsec-eui", table -> {
            table.pref(new LabelSetting("eui-interact-header", Core.bundle.get("eui.interact.title", "Interact")));
            table.sliderPref("eui-action-delay", 500, 0, 3000, 25, i -> i + " ms");
            table.checkPref("eui-makeMineble", false);
            if(!mobile){
                table.checkPref("eui-DragBlock", false);
            }

            table.pref(new LabelSetting("eui-hud-header", Core.bundle.get("eui.hud.title", "HUD")));
            //default OFF since the dedupe pass: three under-cursor block-info surfaces coexist (this
            //bottom-left panel, mi2u's HoverTopTable behind its "replaceTopTable" pref - also default
            //OFF, verified - and the native top panel, which stays the default); nothing deleted since
            //each shows a different place/composition
            table.checkPref("eui-ShowBlockInfo", false);
            table.checkPref("eui-showPowerBar", true);
            table.checkPref("eui-showFactoryProgress", true);
            table.checkPref("eui-ShowResourceRate", false);
            table.checkPref("eui-ShowAlerts", true);
            table.checkPref("eui-ShowAlertsBottom", false);

            table.pref(new LabelSetting("eui-units-header", Core.bundle.get("eui.units.title", "Units")));
            //default OFF since the dedupe pass: mi2u's enUnitHpBar (default ON, richer: drown/overshield
            //multiplier/damaged-only filter) is the primary unit hp bar; this one is kept, not deleted,
            //for its unique flash-on-change-then-fade display mode - see HealthShieldBar's javadoc
            table.checkPref("eui-showUnitBar", false);
            table.checkPref("eui-ShowUnitTable", true);
            table.sliderPref("eui-UnitsTableAlpha", 100, 0, 100, 5, i -> i + "%");
            table.checkPref("eui-TrackPlayerCursor", false);
            table.sliderPref("eui-playerCursorStyle", 7, 1, 7, 1, i -> i + "");
            table.checkPref("eui-ShowOwnCursor", false);
            table.checkPref("eui-TrackLogicControl", false);

            table.pref(new LabelSetting("eui-efficiency-header", Core.bundle.get("eui.efficiency.title", "Efficiency")));
            table.checkPref("eui-ShowEfficiency", false);
            table.sliderPref("eui-EfficiencyTimer", 15, 10, 180, 5, i -> i + "s");

            table.pref(new LabelSetting("eui-schematics-header", Core.bundle.get("eui.schematics.title", "Schematics table")));
            table.checkPref("eui-ShowSchematicsTable", true);
            table.checkPref("eui-ShowSchematicsPreview", true);
            //порядок цифр цепочки G+две цифры (см. SchematicsTableUi.pollChord)
            table.checkPref("eui-SchemTableColFirst", false);
            //sonka 2026-08-22: авто-иконки по содержимому схемы у ячеек без ручных иконок (SchemAutoIcons)
            table.checkPref("eui-SchemTableAutoIcons", true);
            //размеры теперь у КАЖДОЙ страницы свои (диалог страницы по ПКМ); слайдеры - дефолт для новых страниц
            table.sliderPref("eui-SchematicsTableRows", 4, 2, 20, 1, i -> i + "");
            table.sliderPref("eui-SchematicsTableColumns", 5, 4, 16, 1, i -> i + "");
            //sonka 2026-08-21: дефолт был 30 - при 20-80 диапазоне это близко к минимуму, из-за чего
            //ВСЕ ячейки (не только сетки 2-4 иконок) выходили мелкими независимо от буста доли иконки
            //внутри клетки (та доля уже упёрлась в потолок 98%/49% - см. CellIconsElement); подняли
            //дефолт и потолок слайдера, чтобы было куда расти без правки диапазона повторно
            table.sliderPref("eui-SchematicsTableButtonSize", 48, 20, 100, 2, i -> i + "");
            //sonka: размер иконки-заглушки ("нет вручную выбранной иконки" - у большинства ячеек)
            //раньше был жёстко зашит на 60% и никак не менялся; теперь общий слайдер на всю таблицу
            table.sliderPref("eui-SchematicsTableDefaultIconSize", eui.ui.other.SchemTableData.MAIN_ICON_DEFAULT_SIZE, 20, 100, 5, i -> i + "%");
            table.sliderPref("eui-SchematicsTableX", 10, 0, 2000, 10, i -> i + "px");
            table.sliderPref("eui-SchematicsTableY", 160, 0, 2000, 10, i -> i + "px");
            table.sliderPref("eui-SchematicsTableAlpha", 100, 0, 100, 5, i -> i + "%");
            table.pref(new ButtonSetting("eui-schematics-export", () -> schematicsImportExport.showExportDialog()));
            table.pref(new ButtonSetting("eui-schematics-import", () -> schematicsImportExport.showImportDialog()));

            table.pref(new LabelSetting("eui-misc-header", Core.bundle.get("eui.misc.title", "Misc")));
            table.checkPref("eui-showInteractSettings", true);
        });
    }
}
