package sectorstats;

import arc.Core;
import arc.Events;
import arc.scene.ui.layout.Table;
import arc.util.Log;
import mindustry.Vars;
import mindustry.client.ui.ModsSettings;
import mindustry.game.EventType.ClientLoadEvent;
import sonkaextras.CampaignRetry;

import static mindustry.Vars.state;
import static mindustry.Vars.ui;

/**
 * Orchestrator for sonka's "Campaign Utils" mod (mod.json internal id "sector-production-stats",
 * displayName "Campaign Utils" - renamed late in the JS mod's life, id deliberately left alone so nothing
 * that references mod identity breaks), baked directly into the client as native code - same approach as
 * {@link qol.QolSuiteMod}/{@link eui.EUIMod}, see their javadocs for the instantiation-site rationale
 * ({@code mindustry.client.Main.init()}).
 * <p>
 * Ported from the mod's {@code scripts/main.js} (854 lines, Rhino), which had two independent features:
 * a summary dialog of production/export/import across every captured campaign sector
 * ({@link SectorStatsDialog}), and a no-landing sector preview - a static terrain render plus an
 * experimental-but-since-confirmed-stable "live" preview that actually loads the sector into
 * {@code Vars.world} and pauses it ({@link SectorPreview}, {@link LiveSectorPreview}).
 * <p>
 * Like {@link eui.ui.other.BottomPanelUi}/{@link eui.interact.AutofillPriorityDialog}, actual UI
 * construction (dialogs, {@code Core.scene} watchers) is deferred to {@code ClientLoadEvent} rather than
 * happening directly in this constructor - {@code Vars.ui.planet}/{@code Vars.ui.settings} etc. are safer
 * to touch once mod/UI loading has actually reached that point, matching the source's own
 * {@code Events.on(ClientLoadEvent, ...)}-wrapped {@code main.js} entry point.
 */
public class CampaignUtilsMod{
    //static: инстанс мода один (Main.init()), а диалогу статистики нужна внешняя точка входа -
    //кнопка секции «Вшитые моды» в mindustry.client.ui.FeaturesDialog берёт его через statsDialog()
    private static SectorStatsDialog statsDialog;
    private SectorPreview preview;

    /** Диалог статистики секторов; null при self-disable (или до ClientLoadEvent). */
    public static SectorStatsDialog statsDialog(){
        return statsDialog;
    }

    public CampaignUtilsMod(){
        //self-disable if the original jar/script mod is still dropped in the mods folder - two copies
        //would double-register the campaign-screen button watcher and the eye-button watcher below.
        if(Vars.mods.locateMod("sector-production-stats") != null){
            Log.info("[campaign-utils] External sector-production-stats jar/script mod is also loaded - baked-in copy is standing down.");
            return;
        }

        Events.on(ClientLoadEvent.class, e -> {
            try{
                statsDialog = new SectorStatsDialog();
                preview = new SectorPreview();

                attachStatsButton();
                attachRestartButton();
                preview.attachEyeButton();
                LaunchPadAutoRedirect.init();

                buildSettings();
            }catch(Throwable t){
                Log.err("[campaign-utils] failed to initialize", t);
            }
        });
    }

    /**
     * Adds a single "Sector Production" button to the campaign screen's bottom button row
     * ({@code Vars.ui.planet.buttons}). Ported from the source's {@code attachToCampaignScreen(entries)},
     * which accepted an array of {@code {label, action}} entries for reuse between features - simplified
     * to one button here since production stats was the only caller (the array was never used with more
     * than one entry).
     * <p>
     * {@code PlanetDialog.rebuildButtons()} does {@code buttons.clearChildren()} on both dialog init and
     * resize (confirmed against this repo's own {@code PlanetDialog.java}), which would silently orphan a
     * one-time-added row - so, like the source, a small watcher {@code Table} added straight to
     * {@code Core.scene} re-parents the row every frame its {@code parent} has gone null, instead of
     * adding it once.
     */
    private void attachStatsButton(){
        Table row = new Table();
        row.button(Core.bundle.get("campaignutils.sector-production-button"), () -> statsDialog.show()).width(220).height(50).padRight(6);

        Table watcher = new Table();
        Core.scene.add(watcher);

        boolean[] broken = {false};
        watcher.update(() -> {
            row.visible = Core.settings.getBool("campaignutils-show-stats-button", true);

            if(broken[0] || row.parent != null) return;

            try{
                Table buttons = ui.planet.buttons;
                buttons.row();
                buttons.add(row).padTop(6);
            }catch(Throwable t){
                broken[0] = true;
                Log.err("[campaign-utils] failed to attach sector-production button to campaign screen", t);
            }
        });
    }

    /**
     * Adds a "Restart Sector" button next to the production-stats one, calling the same
     * {@link CampaignRetry#restartSector()} used by the pause-menu button ({@code PausedDialog}) - this is
     * an additional access point for it, not a replacement, requested alongside the loadout bug fix on
     * that same feature. Only meaningful while actually playing a sector, so it's gated on
     * {@code state.rules.sector != null} same as the pause-menu button's {@code disabled()}.
     */
    private void attachRestartButton(){
        Table row = new Table();
        row.button(Core.bundle.get("client.sonka.restartsector"), CampaignRetry::restartSector).width(220).height(50).padRight(6)
            .disabled(b -> state.rules.sector == null);

        Table watcher = new Table();
        Core.scene.add(watcher);

        boolean[] broken = {false};
        watcher.update(() -> {
            row.visible = Core.settings.getBool("campaignutils-show-restart-button", true);

            if(broken[0] || row.parent != null) return;

            try{
                Table buttons = ui.planet.buttons;
                buttons.row();
                buttons.add(row).padTop(6);
            }catch(Throwable t){
                broken[0] = true;
                Log.err("[campaign-utils] failed to attach restart-sector button to campaign screen", t);
            }
        });
    }

    /** The "Campaign Utils" section of the shared "Mods" settings tab, same convention as {@code QolSuiteMod}/{@code EUIMod} - see {@link ModsSettings}. */
    private void buildSettings(){
        ModsSettings.section("modsec-campaignutils", table -> {
            table.checkPref("campaignutils-show-stats-button", true);
            table.checkPref("campaignutils-show-restart-button", true);
            table.checkPref("campaignutils-show-eye-button", true);
            table.checkPref(LaunchPadAutoRedirect.settingKey, true);
        });
    }
}
