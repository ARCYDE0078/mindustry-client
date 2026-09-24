package qol.unitnotify;

import arc.Core;
import arc.Events;
import arc.func.Boolf;
import arc.graphics.Color;
import arc.input.KeyBind;
import arc.input.KeyCode;
import arc.scene.ui.CheckBox;
import arc.scene.ui.layout.Cell;
import arc.scene.ui.layout.Table;
import arc.struct.IntSeq;
import arc.struct.Seq;
import arc.math.Mathf;
import arc.util.Scaling;
import arc.util.Time;
import mindustry.ai.UnitCommand;
import mindustry.ai.types.CommandAI;
import mindustry.content.Blocks;
import mindustry.content.UnitTypes;
import mindustry.entities.units.UnitController;
import mindustry.game.EventType.BlockBuildEndEvent;
import mindustry.game.EventType.Trigger;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Unit;
import mindustry.type.Item;
import mindustry.type.ItemStack;
import mindustry.type.UnitType;
import mindustry.ui.dialogs.BaseDialog;
import mindustry.ui.dialogs.SettingsMenuDialog.SettingsTable;
import mindustry.world.Block;
import mindustry.world.blocks.defense.turrets.ItemTurret;
import mindustry.world.blocks.defense.turrets.Turret;
import mindustry.world.blocks.logic.LogicBlock.LogicBuild;
import mindustry.world.blocks.storage.CoreBlock.CoreBuild;
import qol.core.ButtonSetting;
import qol.core.Feature;
import qol.core.SafeSettings;
import qol.ui.QolWindow;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static mindustry.Vars.content;
import static mindustry.Vars.control;
import static mindustry.Vars.mobile;
import static mindustry.Vars.player;
import static mindustry.Vars.state;
import static mindustry.Vars.ui;

/**
 * Ported from the standalone Unit Notifications mod (author DefinedFort) - recovered by decompiling its
 * .class files, since no source was available. Warns about enemy activity (unit-controlled fleets,
 * tier-2..5 reconstructors, first plastanium factory, logic processors auto-piloting flares/crawlers),
 * auto-fetches ammo for a turret you're about to place, and adds a few quick-select hotkeys for poly
 * support units.
 * <p>
 * The original mod's "Genius Mode" easter egg (swaps every unit's sprite/type for a stronger-looking one
 * purely cosmetically, every frame) was dropped: it's not a QoL feature and reassigning live entities'
 * {@code type} field every frame is the kind of thing that's one game update away from crashing.
 */
public class UnitNotifyFeature implements Feature{
    static final KeyBind selectAllZeniths = KeyBind.add("unit-notify-select-zeniths", KeyCode.f12, "unit-notifications");
    static final KeyBind selectAllPoly = KeyBind.add("unit-notify-select-poly", KeyCode.f11, "unit-notifications");
    static final KeyBind selectAllExceptPoly = KeyBind.add("unit-notify-select-except-poly", KeyCode.f10, "unit-notifications");
    static final KeyBind polyHelpPlayer = KeyBind.add("unit-notify-poly-help-player", KeyCode.f9, "unit-notifications");
    static final KeyBind polyHeal = KeyBind.add("unit-notify-poly-heal", KeyCode.f8, "unit-notifications");

    static final int MAX_CONTROL_LOGS = 8;
    static final float CONTROL_LOG_LIFETIME_MS = 8000f;

    /** Below this speed a "commanded" unit counts as just holding position, not actually moving. */
    static final float MOVE_THRESHOLD = 0.05f;
    /**
     * A factory/reconstructor commonly auto-assigns its freshly-built units a one-off "move to rally
     * point" command the instant they roll out - that's normal production, not an enemy actively
     * directing an attack, so a unit isn't eligible to trigger a notification until it's been seen alive
     * for at least this long.
     */
    static final long SPAWN_GRACE_MS = 3000L;

    private final Set<Integer> plastaniumCompressorPlaced = new HashSet<>();
    private final Map<UnitType, Integer> controlledCounts = new HashMap<>();
    private final Map<Integer, Long> firstSeenAt = new HashMap<>();
    private final Set<Integer> seenThisScan = new HashSet<>();
    private final IntSeq tempIDs = new IntSeq();
    private final AmmoFetchTimer ammoTimer = new AmmoFetchTimer();
    private int toastCountdown = 0;

    final Seq<LogEntry> logList = new Seq<>();
    UnitControlWindow window;

    @Override
    public String id(){
        return "unit-notifications";
    }

    @Override
    public String titleKey(){
        return "qol.feature.unit-notifications.title";
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
        window = new UnitControlWindow(this);
        Events.on(WorldLoadEvent.class, e -> plastaniumCompressorPlaced.clear());
        Events.on(BlockBuildEndEvent.class, this::buildEnd);
        Events.run(Trigger.update, this::update);
    }

    @Override
    public void buildSettings(SettingsTable table){
        table.checkPref("unotify-ToggleControlNotification", true);
        table.checkPref("unotify-T2Notification", true);
        table.checkPref("unotify-T3Notification", true);
        table.checkPref("unotify-T4Notification", true);
        table.checkPref("unotify-T5Notification", true);
        table.checkPref("unotify-FirstPlastNotification", false);
        table.checkPref("unotify-LogicFlares", true);
        table.checkPref("unotify-LogicCrawlers", true);
        table.checkPref("unotify-TakeItemForSelectedTurret", false);
        table.sliderPref("unotify-MinUnit", 1, 1, 256, 4, String::valueOf);
        table.sliderPref("unotify-ControlBlinkSpeed", 3, 1, 10, 1, String::valueOf);
        table.pref(new ButtonSetting("unotify-control-color-a", this::pickColorA));
        table.pref(new ButtonSetting("unotify-control-color-b", this::pickColorB));
        table.pref(new ButtonSetting("unotify-configure-units", this::showUnitSettings));
    }

    void pickColorA(){
        Color current = new Color().set(SafeSettings.getInt("unotify-ControlColorA", defaultColorA()));
        ui.picker.show(current, false, picked -> Core.settings.put("unotify-ControlColorA", Color.rgba8888(picked.r, picked.g, picked.b, picked.a)));
    }

    void pickColorB(){
        Color current = new Color().set(SafeSettings.getInt("unotify-ControlColorB", defaultColorB()));
        ui.picker.show(current, false, picked -> Core.settings.put("unotify-ControlColorB", Color.rgba8888(picked.r, picked.g, picked.b, picked.a)));
    }

    static int defaultColorA(){
        return Color.rgba8888(Color.scarlet.r, Color.scarlet.g, Color.scarlet.b, Color.scarlet.a);
    }

    static int defaultColorB(){
        return Color.rgba8888(Color.crimson.r, Color.crimson.g, Color.crimson.b, Color.crimson.a);
    }

    /** Smoothly oscillates between the two configured colors - the log window's stand-in for the old toast's discrete 3-flip blink. */
    Color blinkColor(){
        Color a = new Color().set(SafeSettings.getInt("unotify-ControlColorA", defaultColorA()));
        Color b = new Color().set(SafeSettings.getInt("unotify-ControlColorB", defaultColorB()));
        int speed = SafeSettings.getInt("unotify-ControlBlinkSpeed", 3);
        float t = (Mathf.sin(Time.time / 60f * speed) + 1f) / 2f;
        return a.lerp(b, t);
    }

    boolean unitTypeEnabled(UnitType type){
        return SafeSettings.getBool("unotify-unit-" + type.name, true);
    }

    void setUnitTypeEnabled(UnitType type, boolean enabled){
        Core.settings.put("unotify-unit-" + type.name, enabled);
    }

    void showUnitSettings(){
        BaseDialog dialog = new BaseDialog(Core.bundle.get("unotify-configure-units-title", "Watched Units"));
        dialog.addCloseButton();
        dialog.cont.pane(unitTable -> {
            unitTable.defaults().growX().pad(4f);
            //hidden unit types are internal/non-player-facing (missiles and other projectile-driven
            //pseudo-units, mainly) - same flag mindustry.ui.dialogs content lists filter on themselves,
            //via UnlockableContent.shown()/isHidden()
            for(UnitType type : content.units().select(t -> !t.isHidden())){
                Table row = new Table();
                row.left();
                row.image(type.uiIcon).size(32f).scaling(Scaling.fit).padRight(6f);
                //growX() stretches the checkbox to the row's full width, so Cell.left() alone (which
                //only positions the actor WITHIN the cell) no longer visibly does anything - left() on
                //the checkbox itself is what's needed, same fix as Hub's feature list
                Cell<CheckBox> cell = row.check(type.localizedName, unitTypeEnabled(type), checked -> setUnitTypeEnabled(type, checked));
                cell.get().left();
                cell.left().growX();
                unitTable.add(row).growX().row();
            }
        }).width(400f).height(340f);
        dialog.show();
    }

    void buildEnd(BlockBuildEndEvent event){
        if(!isEnabled() || player == null || event.team == player.team()) return;

        Block block = event.tile.block();
        String reconType = "";
        if(block == Blocks.additiveReconstructor && SafeSettings.getBool("unotify-T2Notification", true)) reconType = "T2";
        else if(block == Blocks.multiplicativeReconstructor && SafeSettings.getBool("unotify-T3Notification", true)) reconType = "T3";
        else if(block == Blocks.exponentialReconstructor && SafeSettings.getBool("unotify-T4Notification", true)) reconType = "T4";
        else if(block == Blocks.tetrativeReconstructor && SafeSettings.getBool("unotify-T5Notification", true)) reconType = "T5";

        if(!reconType.isEmpty()){
            ui.chatfrag.addMessage(Core.bundle.format("danger.build.message", event.team.localized(), reconType));
        }else if(block == Blocks.plastaniumCompressor && !plastaniumCompressorPlaced.contains(event.team.id) && SafeSettings.getBool("unotify-FirstPlastNotification", false)){
            ui.chatfrag.addMessage(Core.bundle.format("danger.first-plastanium.message", event.team.localized()));
            plastaniumCompressorPlaced.add(event.team.id);
        }else if(block == Blocks.microProcessor || block == Blocks.logicProcessor || block == Blocks.hyperProcessor){
            if(!(event.tile.build instanceof LogicBuild logicBuild)) return;

            if(SafeSettings.getBool("unotify-LogicFlares", true)){
                boolean autoWinFlares = logicBuild.code.contains("ubind @flare")
                    && (logicBuild.code.contains("pathfind") || logicBuild.code.contains("move") && (logicBuild.code.contains("target") || logicBuild.code.contains("targetp")));
                if(autoWinFlares) ui.chatfrag.addMessage(Core.bundle.get("danger.logic-flares-controller.message"));
            }
            if(SafeSettings.getBool("unotify-LogicCrawlers", true)){
                boolean autoWinCrawlers = logicBuild.code.contains("ubind @crawler")
                    && (logicBuild.code.contains("pathfind") || logicBuild.code.contains("autoPathfind") || logicBuild.code.contains("target") || logicBuild.code.contains("targetp"));
                if(autoWinCrawlers) ui.chatfrag.addMessage(Core.bundle.get("danger.logic-crawlers-controller.message"));
            }
        }
    }

    void modedInput(){
        if(ui.chatfrag.shown() || ui.schematics.isShown()) return;

        Boolf<Unit> filter = null;
        tempIDs.clear();
        if(Core.input.keyDown(selectAllZeniths)) filter = u -> u.isCommandable() && u.type == UnitTypes.zenith;
        if(Core.input.keyDown(selectAllPoly)) filter = u -> u.isCommandable() && u.type == UnitTypes.poly;
        if(Core.input.keyDown(selectAllExceptPoly)) filter = u -> u.isCommandable() && u.type != UnitTypes.poly;

        if(Core.input.keyDown(polyHeal)){
            player.team().data().units.each(u -> u.isCommandable() && u.type == UnitTypes.poly, u -> tempIDs.add(u.id));
            Call.setUnitCommand(player, tempIDs.toArray(), UnitCommand.repairCommand);
        }
        if(Core.input.keyDown(polyHelpPlayer)){
            player.team().data().units.each(u -> u.isCommandable() && u.type == UnitTypes.poly, u -> tempIDs.add(u.id));
            Call.setUnitCommand(player, tempIDs.toArray(), UnitCommand.assistCommand);
        }

        if(filter != null){
            control.input.selectedUnits.clear();
            Boolf<Unit> f = filter;
            player.team().data().units.each(f, u -> control.input.selectedUnits.add(u));
        }
    }

    Item getBestAmmo(ItemTurret turret, CoreBuild core){
        Item[] best = {null};
        float[] bestDamage = {0f};
        turret.ammoTypes.each((item, ammo) -> {
            float totalDamage = ammo.damage + ammo.splashDamage;
            if(totalDamage > bestDamage[0] && core.items.get(item) >= 20){
                best[0] = item;
                bestDamage[0] = totalDamage;
            }
        });
        return best[0];
    }

    void takeItemForSelectedTurret(){
        if(!SafeSettings.getBool("unotify-TakeItemForSelectedTurret", false) || !ammoTimer.canInteract()) return;

        Block block = control.input.block;
        if(!(block instanceof ItemTurret turret)) return;

        CoreBuild core = player.closestCore();
        if(core == null || !player.within(core, 220f)) return;

        Item bestAmmo = getBestAmmo(turret, core);
        if(bestAmmo == null) return;

        Unit unit = player.unit();
        if(unit == null) return; // спектейт/без юнита - брать нечем

        ItemStack playerItem = unit.stack;
        if(playerItem.amount != 0 && playerItem.item != bestAmmo){
            Call.transferInventory(player, core);
        }else{
            Call.requestItem(player, core, bestAmmo, 999);
        }
        ammoTimer.addTime();
    }

    void update(){
        if(!state.isGame() || !isEnabled() || player == null) return;

        takeItemForSelectedTurret();

        if(SafeSettings.getBool("unotify-ToggleControlNotification", true)){
            toastCountdown++;
            if(toastCountdown >= Core.graphics.getFramesPerSecond()){
                toastCountdown = 0;
                controlledCounts.clear();
                seenThisScan.clear();
                long now = Time.millis();
                Groups.unit.each(u -> {
                    if(u.team.id == player.team().id) return;

                    seenThisScan.add(u.id);
                    firstSeenAt.putIfAbsent(u.id, now);

                    if(!(u.controller() instanceof CommandAI commandAI) || !commandAI.hasCommand()) return;
                    if(u.vel.len() < MOVE_THRESHOLD) return;
                    if(now - firstSeenAt.get(u.id) < SPAWN_GRACE_MS) return;

                    controlledCounts.merge(u.type, 1, Integer::sum);
                });
                firstSeenAt.keySet().removeIf(id -> !seenThisScan.contains(id));

                int minUnit = SafeSettings.getInt("unotify-MinUnit", 1);
                StringBuilder dangerText = new StringBuilder(Core.bundle.format("danger.control.message"));
                boolean any = false;
                for(Map.Entry<UnitType, Integer> entry : controlledCounts.entrySet()){
                    if(entry.getValue() <= minUnit || entry.getKey().isHidden() || !unitTypeEnabled(entry.getKey())) continue;
                    dangerText.append("\n").append(entry.getKey().localizedName).append(": ").append(entry.getValue());
                    any = true;
                }
                if(any) addControlLog(dangerText.toString());

                boolean removedOld = logList.size > 0 && logList.contains(l -> Time.timeSinceMillis(l.time) > CONTROL_LOG_LIFETIME_MS);
                if(removedOld){
                    logList.removeAll(l -> Time.timeSinceMillis(l.time) > CONTROL_LOG_LIFETIME_MS);
                    window.rebuild();
                }
            }
        }

        if(!mobile) modedInput();
    }

    /** Stable id ("unit-control") so a fresh detection replaces the previous entry instead of piling up near-duplicates every second the same enemy keeps controlling units. */
    void addControlLog(String text){
        logList.removeAll(l -> l.id.equals("unit-control"));
        logList.add(new LogEntry("unit-control", text, Time.millis()));
        if(logList.size > MAX_CONTROL_LOGS) logList.remove(0);
        window.rebuild();
    }

    static class LogEntry{
        final String id, text;
        final long time;

        LogEntry(String id, String text, long time){
            this.id = id;
            this.text = text;
            this.time = time;
        }
    }
}
