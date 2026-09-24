package qol;

import arc.Core;
import arc.Events;
import arc.struct.Seq;
import arc.util.Log;
import mindustry.client.ui.ModsSettings;
import mindustry.game.EventType.ClientLoadEvent;
import mindustry.game.EventType.WorldLoadEvent;
import qol.assistshare.AssistShareFeature;
import qol.autobuildschematic.AutoBuildSchematicFeature;
import qol.autopossess.AutoPossessFeature;
import qol.bridgetocore.BridgeToCoreFeature;
import qol.buildbeamcolor.BuildBeamColorFeature;
import qol.cbinds.CustomBindsFeature;
import qol.controlhelper.ControlHelperFeature;
import qol.crawlercontrol.CrawlerControlFeature;
import qol.copyanywhere.CopyAnywhereFeature;
import qol.core.EventsOverflowGuard;
import qol.core.Feature;
import qol.core.UnitClaims;
import qol.coreheal.CoreHealFeature;
import qol.core.LabelSetting;
import qol.conveyorupgrade.ConveyorUpgradeFeature;
import qol.quickchat.QuickChatFeature;
import qol.quicktoggles.QuickTogglesFeature;
import qol.resourceforecast.ResourceForecastFeature;
import qol.travelboost.TravelBoostFeature;
import qol.erekirautopower.ErekirAutoPowerFeature;
import qol.forcebuildschematic.ForceBuildSchematicFeature;
import qol.minedefaults.MineDefaultsFeature;
import qol.payloaddrop.PayloadDropFeature;
import qol.resourcesviewer.EnemyMonitorFeature;
import qol.schematicsanitizer.SchematicSanitizerFeature;
import qol.unitnotify.UnitNotifyFeature;
import qol.ui.Hub;
import qol.ui.QolWindow;
import qol.zoomdisplay.ZoomDisplayFeature;

/**
 * Baked directly into the client as native code (not loaded via the mod system) - see
 * {@code mindustry.client.Main.init()} for the instantiation site and why it has to happen there.
 */
public class QolSuiteMod{
    public static Hub hub;
    public static final Seq<Feature> features = new Seq<>();

    public QolSuiteMod(){
        //self-disable if the original jar mod is still dropped in the mods folder - two copies would
        //double-register every event handler and effect. Same guard/pattern as beam-colors' standalone
        //extract (BeamColorsMod.setup()), which stands down when this suite is present.
        if(mindustry.Vars.mods.locateMod("sonka-qol-suite") != null){
            Log.info("[qol-suite] External sonka-qol-suite jar mod is also loaded - baked-in copy is standing down.");
            return;
        }

        features.add(new BridgeToCoreFeature());
        features.add(new ControlHelperFeature());
        features.add(new UnitNotifyFeature());
        features.add(new ForceBuildSchematicFeature());
        features.add(new AutoBuildSchematicFeature());
        features.add(new ErekirAutoPowerFeature());
        features.add(new EnemyMonitorFeature());
        features.add(new BuildBeamColorFeature());
        features.add(new ZoomDisplayFeature());
        features.add(new MineDefaultsFeature());
        features.add(new CoreHealFeature());
        features.add(new PayloadDropFeature());
        features.add(new AssistShareFeature());
        features.add(new TravelBoostFeature());
        features.add(new ConveyorUpgradeFeature());
        features.add(new ResourceForecastFeature());
        features.add(new SchematicSanitizerFeature());
        features.add(new QuickTogglesFeature());
        features.add(new AutoPossessFeature());
        features.add(new QuickChatFeature());
        features.add(new CustomBindsFeature());
        //MiniMapFeature removed in the dedupe pass: mi2u's MinimapMindow is the richer live minimap
        //(fog, spawns, RTS command taps, zoom); the vanilla full map stays on Tab. Unique qol bits that
        //went with it: desktop click-to-open-full-map, per-type unit sprites w/ outline/sort/size and
        //the player eye/name markers.
        features.add(new CrawlerControlFeature());
        features.add(new CopyAnywhereFeature());

        Events.on(WorldLoadEvent.class, e -> {
            UnitClaims.clear();
            qol.core.ManualControl.clear();
        });

        Events.on(ClientLoadEvent.class, e -> {
            for(Feature f : features) f.init();

            hub = new Hub(features);
            hub.restoreShown();

            for(Feature f : features){
                if(!f.hasWindow()) continue;
                QolWindow win = f.window();
                if(win != null) win.restoreShown();
            }

            buildSettings();

            //last, once everything above has registered its own listeners - see its javadoc for the
            //foo's-client arc.Events crash this fends off
            EventsOverflowGuard.install();
        });
    }

    /**
     * The "QoL Suite" section of the shared "Mods" settings tab (see {@link ModsSettings} - one tab
     * for every baked-in mod, this suite's section comes first), with a bold header per feature. An
     * earlier version added that header via a raw {@code table.add(...)} call, which isn't tracked in
     * {@link mindustry.ui.dialogs.SettingsMenuDialog.SettingsTable}'s own {@code Setting} list - the
     * settings screen noticed the mismatch and disabled that category's search bar ("Mod added an
     * unexpected row to SettingsTable"). Splitting into one category per feature also fixed that (no
     * header needed, the category name says it), but one shared category groups better - so the header
     * is now a {@link qol.core.LabelSetting}, registered the same tracked way {@code checkPref}/
     * {@code sliderPref} are, instead of a raw row.
     */
    void buildSettings(){
        ModsSettings.section("modsec-qol", table -> {
            table.pref(new LabelSetting("qol-hub-header", Core.bundle.get("qol.hub.title", "Hub")));
            hub.buildSizeSetting(table);

            for(Feature f : features){
                table.pref(new LabelSetting(f.settingsKey() + "-header", Core.bundle.get(f.titleKey(), f.titleKey())));
                //checkPref's 2-arg overload writes straight to Core.settings, bypassing
                //Feature.setEnabled(...) entirely - routing the change through it here is what makes
                //disabling a feature from this checkbox also hide its window, same as disabling it from
                //the hub's own checkbox does (Hub.setupCont already calls f.setEnabled(...) directly)
                table.checkPref(f.settingsKey(), true, checked -> f.setEnabled(checked));
                f.buildSettings(table);
                if(f.hasWindow() && f.window() != null) f.window().buildSizeSetting(table);
            }
        });
    }
}
