package qol.assistshare;

import arc.Events;
import arc.math.geom.Vec2;
import arc.struct.IntIntMap;
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
import mindustry.gen.Player;
import mindustry.gen.Unit;
import mindustry.ui.dialogs.SettingsMenuDialog.SettingsTable;
import qol.core.Feature;
import qol.core.ManualControl;
import qol.core.SafeSettings;
import qol.core.UnitClaims;
import qol.minedefaults.MineDefaultsFeature;

import static mindustry.Vars.player;
import static mindustry.Vars.state;
import static mindustry.Vars.tilesize;

/**
 * Splits assist-commanded helper units between the team's builder players and makes each one STICK
 * to its player, instead of the whole swarm chasing whoever touched a blueprint last.
 * <p>
 * Why they chase (vanilla BuilderAI, assist mode): every 20 ticks the unit re-picks
 * {@code assistFollowing} = the player physically CLOSEST to the unit, and a second scan follows
 * ANY actively-building team unit within ~187 tiles - so when your teammate places a block across
 * the base, every idle helper on the team charges over there. The one lever a client has is unit
 * POSITION: the closest-player pick means a unit parked at its player's side follows that player
 * whenever they build. So this feature keeps each helper parked near its assigned player between
 * builds (plain move command - no BuilderAI, no chasing), and hands the assist command back the
 * moment the player is building. Everything runs over the same command RPCs the vanilla UI uses.
 * <p>
 * Units are divided evenly and assignments are sticky - rebalancing (players joining/leaving,
 * helpers dying or being produced) moves only the shortfall, preferring the units nearest the
 * player that needs them. Re-commanding a helper manually takes it out of the system (sync-grace
 * detection, as everywhere in this mod). With fewer than two builder players present the feature
 * stands down entirely and releases its units back to plain vanilla assist.
 * <p>
 * Coordination: managed units are claimed via {@link UnitClaims} so core-heal won't draft them
 * mid-park - EXCEPT poly-split's diverted polys, whose claim is owned by
 * {@link MineDefaultsFeature}; those are managed "borrowed" (no claim of our own, dropped the
 * instant poly-split takes them back to mining), and poly-split in turn treats a unit this feature
 * parked on move as still-assisting (see {@link #managedExpected}).
 * <p>
 * ONLY ONE player on a team should run this feature - two mods managing the same team units would
 * fight each other's commands (each sees the other's RPCs as "manual" overrides).
 */
public class AssistShareFeature implements Feature{
    /** How often (in ticks) helpers are scanned, adopted, rebalanced and parked. */
    static final float SCAN_INTERVAL_TICKS = 30f;
    /** See CoreHealFeature - a just-sent command RPC needs a server round-trip before the client sees it. */
    static final long COMMAND_SYNC_GRACE_MS = 2000L;
    /**
     * While its player idles (within the idle-delay window), a helper still on assist that strays
     * this far from them has been hijacked by BuilderAI's any-nearby-builder scan - yank it back.
     * Wider than BuilderAI's own ~8-tile follow hover, so normal orbiting never trips it.
     */
    static final float STRAY_TILES = 12f;
    /** A parked helper's move target is refreshed when its player has wandered this far from it. */
    static final float REPARK_TILES = 8f;

    static AssistShareFeature instance;

    /** unit id -> assigned player id. Presence here = "managed by this feature". UNASSIGNED until rebalance picks a player. */
    final IntIntMap assignedPlayer = new IntIntMap();
    static final int UNASSIGNED = -1;
    /** Managed units currently parked on our move command; absent = on assist. */
    final IntSet parked = new IntSet();
    /** Managed units whose UnitClaims claim belongs to poly-split (diverted polys) - never claim or release those ourselves. */
    final IntSet borrowed = new IntSet();
    /** unit id -> Time.millis() of the last command RPC we sent it, for the sync-grace check. */
    final IntMap<Long> lastCommandSent = new IntMap<>();
    /** unit id -> where its park move was aimed, to notice the player wandering off. */
    final IntMap<Vec2> parkTarget = new IntMap<>();
    /** player id -> Time.millis() the player was last seen actively building, for the idle-delay hysteresis. */
    final IntMap<Long> lastBuilt = new IntMap<>();

    final Seq<Player> builders = new Seq<>();
    final IntMap<Seq<Unit>> playerUnits = new IntMap<>();
    final Seq<Unit> pool = new Seq<>();
    final IntSeq dropIds = new IntSeq();
    final IntSeq assistIds = new IntSeq();
    final ObjectMap<Player, IntSeq> parkBatch = new ObjectMap<>();

    float scanTimer = 0f;

    public AssistShareFeature(){
        instance = this;
    }

    /**
     * Cross-feature hook: the command this feature expects the unit to be on (assist, or move while
     * parked), or null if the unit isn't managed. Poly-split checks this so a poly we parked on move
     * doesn't read as "the player re-commanded it" and get released from the split.
     */
    public static UnitCommand managedExpected(int id){
        if(instance == null || !instance.assignedPlayer.containsKey(id)) return null;
        return instance.parked.contains(id) ? UnitCommand.moveCommand : UnitCommand.assistCommand;
    }

    @Override
    public String id(){
        return "assist-share";
    }

    @Override
    public String titleKey(){
        return "qol.feature.assist-share.title";
    }

    @Override
    public void init(){
        Events.on(WorldLoadEvent.class, e -> {
            assignedPlayer.clear();
            parked.clear();
            borrowed.clear();
            lastCommandSent.clear();
            parkTarget.clear();
            lastBuilt.clear();
            scanTimer = 0f;
        });
        Events.run(Trigger.update, this::update);
    }

    @Override
    public void buildSettings(SettingsTable table){
        table.sliderPref("assistshare-idle-delay", 5, 1, 15, 1, v -> v + " s");
        table.sliderPref("assistshare-manual-hold", 20, 0, 120, 5, v -> v == 0 ? "off" : v + " s");
    }

    /** Сколько мс после ручной команды юнит неприкосновенен для распределения; 0 = приоритет ручных команд выключен. */
    long manualHoldMs(){
        return SafeSettings.getInt("assistshare-manual-hold", 20) * 1000L;
    }

    void drop(int id){
        assignedPlayer.remove(id, 0);
        parked.remove(id);
        lastCommandSent.remove(id);
        parkTarget.remove(id);
        if(!borrowed.remove(id)) UnitClaims.release(id);
    }

    /** Hand everything back to plain assist (parked units included) and forget it all. */
    void releaseAll(){
        if(assignedPlayer.isEmpty()) return;
        assistIds.clear();
        for(IntIntMap.Entry entry : assignedPlayer){
            Unit u = Groups.unit.getByID(entry.key);
            if(parked.contains(entry.key) && u != null && u.isValid() && u.team == player.team()
                && u.controller() instanceof CommandAI ai && ai.currentCommand() == UnitCommand.moveCommand){
                assistIds.add(entry.key);
            }
            if(!borrowed.contains(entry.key)) UnitClaims.release(entry.key);
        }
        if(assistIds.size > 0){
            Call.setUnitCommand(player, assistIds.toArray(), UnitCommand.assistCommand);
        }
        assignedPlayer.clear();
        parked.clear();
        borrowed.clear();
        lastCommandSent.clear();
        parkTarget.clear();
    }

    void update(){
        if(!state.isGame() || player == null || player.team().data() == null) return;

        scanTimer += Time.delta;
        if(scanTimer < SCAN_INTERVAL_TICKS) return;
        scanTimer = 0f;

        //who can be helped: alive builder players of our team, in stable id order so the even
        //split's "who gets the odd extra unit" doesn't reshuffle every scan
        builders.clear();
        for(Player p : Groups.player){
            if(!p.dead() && p.isBuilder() && p.team() == player.team()) builders.add(p);
        }
        builders.sort(p -> p.id());

        //nothing to split with one (or zero) builders - and a disabled feature must not leave
        //units stranded on its park command, so both cases fully stand down
        if(!isEnabled() || builders.size < 2){
            releaseAll();
            return;
        }

        long now = Time.millis();
        for(Player p : builders){
            if(p.unit() != null && p.unit().activelyBuilding()) lastBuilt.put(p.id(), now);
        }

        dropPass();
        adoptPass();
        rebalance();
        behaviorPass(now);
    }

    /** Let go of the dead, the re-commanded, and borrowed polys that poly-split took back. */
    void dropPass(){
        dropIds.clear();
        for(IntIntMap.Entry entry : assignedPlayer){
            Unit u = Groups.unit.getByID(entry.key);
            if(u == null || !u.isValid() || u.team != player.team() || !(u.controller() instanceof CommandAI ai)){
                dropIds.add(entry.key);
                continue;
            }
            //ручной приказ (в т.ч. повторный assist/move, неотличимый по команде) - отпускаем на время таймера
            if(ManualControl.isRecent(entry.key, manualHoldMs())){
                dropIds.add(entry.key);
                continue;
            }
            //poly-split returned this poly to mining - it's not a helper anymore, hands off
            //immediately (poly-split owns its claim and already sent the mine command)
            if(borrowed.contains(entry.key) && !MineDefaultsFeature.divertedToAssist(entry.key)){
                dropIds.add(entry.key);
                continue;
            }
            UnitCommand expected = parked.contains(entry.key) ? UnitCommand.moveCommand : UnitCommand.assistCommand;
            if(ai.currentCommand() != expected
                && Time.timeSinceMillis(lastCommandSent.get(entry.key, 0L)) > COMMAND_SYNC_GRACE_MS){
                dropIds.add(entry.key); //player took over - hands off
                continue;
            }
            //a right-click redirect of a PARKED unit is still the move command, invisible to the
            //check above - catch it by the unit's move target having left our park spot (with the
            //same margin re-parking uses, so formation spread around the spot never trips it)
            if(parked.contains(entry.key) && ai.currentCommand() == UnitCommand.moveCommand){
                Vec2 target = parkTarget.get(entry.key);
                if(target != null && ai.targetPos != null
                    && !ai.targetPos.within(target, REPARK_TILES * tilesize)
                    && Time.timeSinceMillis(lastCommandSent.get(entry.key, 0L)) > COMMAND_SYNC_GRACE_MS){
                    dropIds.add(entry.key);
                }
            }
        }
        for(int i = 0; i < dropIds.size; i++){
            drop(dropIds.get(i));
        }
    }

    /** Bring every unmanaged assist unit of the team under management. */
    void adoptPass(){
        player.team().data().units.each(u -> {
            if(!u.isCommandable() || !(u.controller() instanceof CommandAI ai)) return;
            if(ai.currentCommand() != UnitCommand.assistCommand) return;
            if(assignedPlayer.containsKey(u.id)) return;
            //игрок только что сам дал этому юниту assist - не подхватываем и не паркуем до истечения таймера
            if(ManualControl.isRecent(u.id, manualHoldMs())) return;

            //team data and Groups.unit's id map CAN disagree on this client (fog server + foo's
            //client's own unit caching): a unit present in data().units may not resolve via
            //getByID, which is the lookup every other pass uses - adopting such a phantom put a
            //null unit into rebalance()'s pool (crash 2026-08-11 09:38, u.dst2 NPE)
            if(Groups.unit.getByID(u.id) == null) return;

            boolean diverted = MineDefaultsFeature.divertedToAssist(u.id);
            //some other feature (core-heal etc.) is mid-cycle with this unit - not ours to take.
            //Poly-split's diverted polys are the exception: their claim stays poly-split's, but the
            //assisting itself is exactly what this feature distributes
            if(UnitClaims.isClaimed(u.id) && !diverted) return;

            if(diverted) borrowed.add(u.id);
            else UnitClaims.claim(u.id);
            assignedPlayer.put(u.id, UNASSIGNED);
        });
    }

    /**
     * Even, sticky split: every managed unit keeps its player while that player is present; only
     * fresh units, orphans of players who left, and the surplus of over-served players move - each
     * to the needy player closest to it.
     */
    void rebalance(){
        playerUnits.clear();
        pool.clear();
        int total = 0;
        for(IntIntMap.Entry entry : assignedPlayer){
            Unit u = Groups.unit.getByID(entry.key);
            //belt to adoptPass's braces: skip ids that stopped resolving mid-tick rather than
            //pooling a null. Can't drop() here - that mutates the map being iterated - so the
            //entry just sits out this pass and next update's dropPass removes it properly
            if(u == null) continue;
            total++;
            Player p = null;
            if(entry.value != UNASSIGNED){
                for(Player b : builders){
                    if(b.id() == entry.value){
                        p = b;
                        break;
                    }
                }
            }
            if(p == null) pool.add(u);
            else playerUnits.get(p.id(), Seq::new).add(u);
        }
        if(total == 0) return;

        int base = total / builders.size, extra = total % builders.size;
        //shed surplus into the pool first...
        for(int i = 0; i < builders.size; i++){
            int target = base + (i < extra ? 1 : 0);
            Seq<Unit> units = playerUnits.get(builders.get(i).id(), Seq::new);
            while(units.size > target){
                Unit u = units.pop();
                assignedPlayer.put(u.id, UNASSIGNED);
                pool.add(u);
            }
        }
        //...then top up the underserved, nearest pool unit first
        for(int i = 0; i < builders.size; i++){
            Player p = builders.get(i);
            int target = base + (i < extra ? 1 : 0);
            Seq<Unit> units = playerUnits.get(p.id(), Seq::new);
            while(units.size < target && !pool.isEmpty()){
                Unit best = null;
                float bestDst = 0f;
                for(Unit u : pool){
                    float dst = u.dst2(p);
                    if(best == null || dst < bestDst){
                        best = u;
                        bestDst = dst;
                    }
                }
                pool.remove(best);
                assignedPlayer.put(best.id, p.id());
                units.add(best);
            }
        }
    }

    /** Park idle helpers at their player's side, put them back on assist while the player builds. */
    void behaviorPass(long now){
        long idleDelay = SafeSettings.getInt("assistshare-idle-delay", 5) * 1000L;
        assistIds.clear();
        parkBatch.clear();

        for(IntIntMap.Entry entry : assignedPlayer){
            Unit u = Groups.unit.getByID(entry.key);
            Player p = null;
            for(Player b : builders){
                if(b.id() == entry.value){
                    p = b;
                    break;
                }
            }
            if(p == null || u == null) continue; //just rebalanced away or dropped - next scan

            boolean activeNow = p.unit() != null && p.unit().activelyBuilding();
            boolean activeRecently = now - lastBuilt.get(p.id(), 0L) < idleDelay;

            if(parked.contains(entry.key)){
                if(activeNow){
                    //their player is building again - back to work
                    parked.remove(entry.key);
                    parkTarget.remove(entry.key);
                    lastCommandSent.put(entry.key, now);
                    assistIds.add(entry.key);
                }else{
                    Vec2 target = parkTarget.get(entry.key);
                    if(target == null || !p.within(target.x, target.y, REPARK_TILES * tilesize)){
                        park(entry.key, p, now);
                    }
                }
            }else{
                //on assist: park once the player has idled past the delay, or immediately if the
                //idle unit is drifting off toward someone else's build site (BuilderAI hijack)
                if(!activeNow && (!activeRecently || !u.within(p, STRAY_TILES * tilesize))){
                    parked.add(entry.key);
                    park(entry.key, p, now);
                }
            }
        }

        if(assistIds.size > 0){
            Call.setUnitCommand(player, assistIds.toArray(), UnitCommand.assistCommand);
        }
        parkBatch.each((p, ids) ->
            Call.commandUnits(player, ids.toArray(), null, null, new Vec2(p.x, p.y), false, true));
    }

    void park(int id, Player p, long now){
        Vec2 target = parkTarget.get(id);
        if(target == null){
            target = new Vec2();
            parkTarget.put(id, target);
        }
        target.set(p.x, p.y);
        lastCommandSent.put(id, now);
        parkBatch.get(p, IntSeq::new).add(id);
    }
}
