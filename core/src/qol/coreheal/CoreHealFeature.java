package qol.coreheal;

import arc.Events;
import arc.math.geom.Vec2;
import arc.struct.IntMap;
import arc.struct.IntSeq;
import arc.struct.IntSet;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Time;
import mindustry.ai.UnitCommand;
import mindustry.ai.types.CommandAI;
import mindustry.game.EventType.Trigger;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Unit;
import mindustry.ui.dialogs.SettingsMenuDialog.SettingsTable;
import mindustry.world.blocks.storage.CoreBlock.CoreBuild;
import qol.core.Feature;
import qol.core.ManualControl;
import qol.core.SafeSettings;
import qol.core.UnitClaims;

import static mindustry.Vars.player;
import static mindustry.Vars.state;
import static mindustry.Vars.tilesize;

/**
 * Support units prioritize healing a damaged core. Any commandable unit whose type supports the
 * repair command (vanilla only grants it to FLYING healers - poly/mega and friends) that's within
 * the configured radius of a damaged own core gets drafted, over the same RPCs the vanilla command
 * UI uses ({@link Call#setUnitCommand}/{@link Call#commandUnits}), in two steps:
 * <ul>
 * <li><b>Travel</b>: units farther than {@link #ARRIVAL_TILES} from the core - including ones the
 * player already commanded to repair, which vanilla's {@link mindustry.ai.types.RepairAI} would
 * otherwise keep busy on whatever damaged building is closest to them - are sent flying AT the core
 * first ({@code commandUnits} with a position target; every eligible command auto-switches to move).
 * RepairAI picks its own target and that can't be forced from a client, so "repair the core, not
 * random walls" is achieved by physically parking the unit next to the core before handing it the
 * repair command.</li>
 * <li><b>Repair</b>: once within {@link #ARRIVAL_TILES} of the damaged core, the unit is switched
 * to the repair command - from there the nearest-damaged-building targeting lands on the core
 * cluster itself.</li>
 * </ul>
 * The unit's original command is remembered and restored once no damaged core has been near it for
 * the configured delay (cores under wave attack flicker between full and damaged - restoring the
 * instant the core tops up would ping-pong units between commands). If the player manually
 * re-commands a drafted unit at any point, it's dropped from tracking and never touched again for
 * that stretch - detected as "current command isn't the one this feature last gave it", with a sync
 * grace window since the client's view of a unit's command lags the server right after our own RPC.
 */
public class CoreHealFeature implements Feature{
    /** How often (in ticks) units are scanned, drafted and restored. */
    static final float SCAN_INTERVAL_TICKS = 60f;
    /** Close enough to the core that RepairAI's nearest-damaged targeting means the core cluster. */
    static final float ARRIVAL_TILES = 8f;
    /**
     * After sending a command RPC, the unit's client-side command state only reflects it once the
     * server applies and syncs it back - until then a mismatch means lag, not the player manually
     * re-commanding the unit. Don't treat it as a manual change within this window.
     */
    static final long COMMAND_SYNC_GRACE_MS = 2000L;

    /** id -> command the unit had before being drafted. Presence here = "drafted by us". */
    final IntMap<UnitCommand> savedCommand = new IntMap<>();
    /** Drafted units currently in the travel phase (expected on move); absent = repair phase (expected on repair). */
    final IntSet traveling = new IntSet();
    /** id -> Time.millis() of the last command RPC this feature sent for the unit, for the sync-grace check. */
    final IntMap<Long> lastCommandSent = new IntMap<>();
    /** id -> Time.millis() a damaged core was last seen within radius of the unit, for the restore delay. */
    final IntMap<Long> lastDamagedNear = new IntMap<>();

    final IntSeq repairIds = new IntSeq();
    final IntSeq dropIds = new IntSeq();
    final ObjectMap<CoreBuild, IntSeq> travelBatch = new ObjectMap<>();
    final ObjectMap<UnitCommand, IntSeq> restoreBatch = new ObjectMap<>();
    final Seq<CoreBuild> damagedCores = new Seq<>();

    float scanTimer = 0f;

    @Override
    public String id(){
        return "core-heal";
    }

    @Override
    public String titleKey(){
        return "qol.feature.core-heal.title";
    }

    @Override
    public void init(){
        Events.on(WorldLoadEvent.class, e -> {
            savedCommand.clear();
            traveling.clear();
            lastCommandSent.clear();
            lastDamagedNear.clear();
            scanTimer = 0f;
        });
        Events.run(Trigger.update, this::update);
    }

    @Override
    public void buildSettings(SettingsTable table){
        table.sliderPref("coreheal-radius", 15, 5, 100, 5, v -> v + " tiles");
        table.sliderPref("coreheal-restore-delay", 3, 1, 15, 1, v -> v + " s");
        table.sliderPref("coreheal-manual-hold", 20, 0, 120, 5, v -> v == 0 ? "off" : v + " s");
    }

    /** Сколько мс после ручной команды юнит неприкосновенен для авто-хила; 0 = приоритет ручных команд выключен. */
    long manualHoldMs(){
        return SafeSettings.getInt("coreheal-manual-hold", 20) * 1000L;
    }

    float radiusWorld(){
        return SafeSettings.getInt("coreheal-radius", 15) * tilesize;
    }

    void collectDamagedCores(){
        damagedCores.clear();
        for(CoreBuild core : player.team().cores()){
            if(core.damaged()) damagedCores.add(core);
        }
    }

    CoreBuild damagedCoreNear(float x, float y, float radius){
        CoreBuild best = null;
        float bestDst = 0f;
        for(CoreBuild core : damagedCores){
            float dst = core.dst2(x, y);
            if(core.within(x, y, radius) && (best == null || dst < bestDst)){
                best = core;
                bestDst = dst;
            }
        }
        return best;
    }

    void track(Unit u, UnitCommand original, boolean travel){
        UnitClaims.claim(u.id);
        savedCommand.put(u.id, original);
        if(travel) traveling.add(u.id);
        lastCommandSent.put(u.id, Time.millis());
        lastDamagedNear.put(u.id, Time.millis());
    }

    void drop(int id){
        savedCommand.remove(id);
        traveling.remove(id);
        lastCommandSent.remove(id);
        lastDamagedNear.remove(id);
        UnitClaims.release(id);
    }

    void update(){
        if(!state.isGame() || !isEnabled() || player == null || player.team().data() == null) return;

        scanTimer += Time.delta;
        if(scanTimer < SCAN_INTERVAL_TICKS) return;
        scanTimer = 0f;

        collectDamagedCores();
        float radius = radiusWorld();
        float arrival = Math.min(radius, ARRIVAL_TILES * tilesize);

        //draft pass: eligible healers near a hurt core either start repairing (already at the core)
        //or get sent flying to it first
        if(!damagedCores.isEmpty()){
            repairIds.clear();
            travelBatch.clear();
            player.team().data().units.each(u -> {
                if(!u.isCommandable() || !(u.controller() instanceof CommandAI ai)) return;
                if(!u.type.commands.contains(UnitCommand.repairCommand)) return;
                if(savedCommand.containsKey(u.id)) return;
                //игрок только что сам командовал юнитом - не тащим его к ядру, пока не истечёт таймер
                if(ManualControl.isRecent(u.id, manualHoldMs())) return;

                UnitCommand current = ai.currentCommand();
                if(current == null) return;

                CoreBuild core = damagedCoreNear(u.x, u.y, radius);
                if(core == null) return;

                //another feature (e.g. mine-defaults' flee) is mid-cycle with this unit - drafting it
                //now would save the wrong "original" command and strand it there afterwards
                if(UnitClaims.isClaimed(u.id)) return;

                if(core.within(u, arrival)){
                    //already parked at the core: repair-commanded units are doing the right thing,
                    //anything else switches to repair
                    if(current != UnitCommand.repairCommand){
                        track(u, current, false);
                        repairIds.add(u.id);
                    }
                }else{
                    //too far for RepairAI to pick the core - fly there first, then repair on arrival.
                    //This includes units already ON the repair command ("commanded to repair means
                    //prioritize the core"): their saved command is repair, so that's also what they
                    //return to once the core is topped up.
                    track(u, current, true);
                    travelBatch.get(core, IntSeq::new).add(u.id);
                }
            });
            if(repairIds.size > 0){
                Call.setUnitCommand(player, repairIds.toArray(), UnitCommand.repairCommand);
            }
            travelBatch.each((core, ids) ->
                Call.commandUnits(player, ids.toArray(), null, null, new Vec2(core.x, core.y), false, true));
        }

        //maintenance pass over drafted units: advance travelers that arrived, hand back the old
        //command once cores near the unit have stayed healed long enough, let go of anything the
        //player re-commanded manually
        dropIds.clear();
        repairIds.clear();
        restoreBatch.clear();
        for(IntMap.Entry<UnitCommand> entry : savedCommand){
            Unit u = Groups.unit.getByID(entry.key);
            if(u == null || !u.isValid() || u.team != player.team() || !(u.controller() instanceof CommandAI ai)){
                dropIds.add(entry.key);
                continue;
            }

            //ручной приказ поверх нашего цикла (в т.ч. move/repair, совпадающий с ожидаемой командой) - отпускаем
            //без восстановления старой команды: игрок только что решил за юнита сам
            if(ManualControl.isRecent(entry.key, manualHoldMs())){
                dropIds.add(entry.key);
                continue;
            }

            boolean travelPhase = traveling.contains(entry.key);
            UnitCommand expected = travelPhase ? UnitCommand.moveCommand : UnitCommand.repairCommand;
            if(ai.currentCommand() != expected){
                if(Time.timeSinceMillis(lastCommandSent.get(entry.key, 0L)) > COMMAND_SYNC_GRACE_MS){
                    dropIds.add(entry.key); //player took over - hands off
                }
                continue;
            }

            CoreBuild near = damagedCoreNear(u.x, u.y, radiusWorld());
            if(near != null){
                lastDamagedNear.put(entry.key, Time.millis());
                if(travelPhase && near.within(u, arrival)){
                    traveling.remove(entry.key);
                    lastCommandSent.put(entry.key, Time.millis());
                    repairIds.add(entry.key);
                }
            }else if(Time.timeSinceMillis(lastDamagedNear.get(entry.key, 0L)) > SafeSettings.getInt("coreheal-restore-delay", 3) * 1000L){
                restoreBatch.get(entry.value, IntSeq::new).add(entry.key);
                dropIds.add(entry.key);
            }
        }
        for(int i = 0; i < dropIds.size; i++){
            drop(dropIds.get(i));
        }
        if(repairIds.size > 0){
            Call.setUnitCommand(player, repairIds.toArray(), UnitCommand.repairCommand);
        }
        restoreBatch.each((command, ids) -> Call.setUnitCommand(player, ids.toArray(), command));
    }
}
