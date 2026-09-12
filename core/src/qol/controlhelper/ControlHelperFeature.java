package qol.controlhelper;

import mindustry.ui.dialogs.SettingsMenuDialog.SettingsTable;
import qol.controlhelper.core.DisconnectedPowerHighlighter;
import qol.controlhelper.core.DrillsValidator;
import qol.controlhelper.core.ExtinguishedRebuilder;
import qol.controlhelper.core.HandMiner;
import qol.controlhelper.core.PlansPrioritizer;
import qol.controlhelper.core.PlansSaver;
import qol.controlhelper.core.PowerBridgeBuilder;
import qol.controlhelper.core.PowerNetworkReconnector;
import qol.controlhelper.core.SupportsIgnorer;
import qol.controlhelper.core.UnitSplitter;
import qol.controlhelper.core.buildingsdepowerer.FactoriesDepowerer;
import qol.controlhelper.core.buildingsdepowerer.ProducersDepowerer;
import qol.controlhelper.core.requestexecutor.RequestExecutor;
import qol.controlhelper.ui.ControlHelperWindow;
import qol.core.Feature;
import qol.ui.QolWindow;

/**
 * Ported from the standalone Control Helper mod (author Blaizi) - recovered by decompiling its .class
 * files, since no source was available, then cleaned up and rewired to plug into the shared hub instead
 * of its own bespoke settings dialogs and draggable window. Every sub-feature keeps the exact
 * Core.settings key it used standalone (drillsValidator, plansSaver, handMiner, ignoreSupportUnits,
 * prioritizePlans, splitAdd1.size/2/3), so settings carry over if you'd been running the original mod.
 * Only the depower-toggle window survives as a UI element; the old custom keybind-rebind dialog was
 * replaced by the game's own native rebindable {@link arc.input.KeyBind} entries (shows up under
 * Settings > Controls automatically, same as Bridge To Core's hotkey).
 * <p>
 * Every sub-feature is constructed here in {@link #init()}, not as an eager instance field - the
 * original decompiled {@code ControlHelper.init()} did the same, for a real reason: a mod's main class
 * (this whole tree's root, {@link qol.QolSuiteMod}) gets instantiated by {@code Mods.load()}, called
 * from {@code Vars.init()} - which runs before {@code ClientLauncher} calls
 * {@code content.createBaseContent()}. Any field initializer that runs at THAT point sees every
 * {@code mindustry.content.Blocks}/{@code UnitTypes}/etc. static field as still null. That's exactly
 * what silently broke {@link FactoriesDepowerer}/{@link ProducersDepowerer} (their block lists were all
 * null, so nothing ever matched a real building) and {@link SupportsIgnorer} (its ignored-unit-types
 * list, same issue) the first time these were eager fields here instead.
 */
public class ControlHelperFeature implements Feature{
    RequestExecutor requestExecutor;
    FactoriesDepowerer factoriesDepowerer;
    ProducersDepowerer producersDepowerer;

    UnitSplitter unitSplitter;
    SupportsIgnorer supportsIgnorer;
    PlansSaver plansSaver;
    PlansPrioritizer plansPrioritizer;
    HandMiner handMiner;
    ExtinguishedRebuilder extinguishedRebuilder;
    DrillsValidator drillsValidator;
    DisconnectedPowerHighlighter disconnectedPowerHighlighter;
    PowerNetworkReconnector powerNetworkReconnector;
    PowerBridgeBuilder powerBridgeBuilder;

    ControlHelperWindow window;

    @Override
    public String id(){
        return "control-helper";
    }

    @Override
    public String titleKey(){
        return "qol.feature.control-helper.title";
    }

    @Override
    public boolean hasWindow(){
        return true;
    }

    @Override
    public QolWindow window(){
        return window;
    }

    @Override
    public void init(){
        requestExecutor = new RequestExecutor();
        factoriesDepowerer = new FactoriesDepowerer(requestExecutor);
        producersDepowerer = new ProducersDepowerer(requestExecutor);

        unitSplitter = new UnitSplitter(this::isEnabled);
        supportsIgnorer = new SupportsIgnorer(this::isEnabled);
        plansSaver = new PlansSaver(this::isEnabled);
        plansPrioritizer = new PlansPrioritizer(this::isEnabled);
        handMiner = new HandMiner(this::isEnabled);
        extinguishedRebuilder = new ExtinguishedRebuilder(this::isEnabled);
        drillsValidator = new DrillsValidator(this::isEnabled);
        disconnectedPowerHighlighter = new DisconnectedPowerHighlighter(this::isEnabled);
        powerNetworkReconnector = new PowerNetworkReconnector(requestExecutor);
        powerBridgeBuilder = new PowerBridgeBuilder(requestExecutor, powerNetworkReconnector, this::isEnabled);

        requestExecutor.Init();
        unitSplitter.Init();
        supportsIgnorer.Init();
        plansSaver.Init();
        plansPrioritizer.Init();
        handMiner.Init();
        extinguishedRebuilder.Init();
        drillsValidator.Init();
        disconnectedPowerHighlighter.Init();
        powerBridgeBuilder.Init();
        window = new ControlHelperWindow(factoriesDepowerer, producersDepowerer, disconnectedPowerHighlighter, powerNetworkReconnector);
    }

    @Override
    public void buildSettings(SettingsTable table){
        table.checkPref("drillsValidator", true);
        table.checkPref("plansSaver", true);
        table.checkPref("prioritizePlans", true);
        table.checkPref("handMiner", true);
        table.checkPref("ignoreSupportUnits", true);
        table.checkPref("highlightDisconnectedPower", false);
        table.checkPref("powerBridgeBuilder", true);
        table.sliderPref("splitAdd1.size", 0, 0, 100, i -> i + "%");
        table.sliderPref("splitAdd2.size", 0, 0, 100, i -> i + "%");
        table.sliderPref("splitAdd3.size", 0, 0, 100, i -> i + "%");
    }
}
