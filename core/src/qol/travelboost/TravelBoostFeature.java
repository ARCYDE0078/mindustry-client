package qol.travelboost;

import arc.Events;
import arc.struct.IntSeq;
import arc.struct.IntSet;
import arc.util.Time;
import mindustry.ai.UnitCommand;
import mindustry.ai.UnitStance;
import mindustry.ai.types.CommandAI;
import mindustry.game.EventType.Trigger;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Unit;
import mindustry.ui.dialogs.SettingsMenuDialog.SettingsTable;
import qol.core.Feature;
import qol.core.ManualControl;
import qol.core.SafeSettings;
import qol.core.UnitClaims;

import static mindustry.Vars.player;
import static mindustry.Vars.state;
import static mindustry.Vars.tilesize;

/**
 * Mechs on a long trip take off: any commandable {@code canBoost} unit (pulsar, quasar, vela...)
 * holding a plain move order to a point farther than the configured distance gets the vanilla boost
 * stance for the trip - same client-side lever as mine-defaults' flee-boost, generalized - and has
 * it taken back on arrival (a few tiles from the target), so it lands and behaves vanilla again.
 * <p>
 * Deliberately narrow triggers: only the plain move command with a position target. Attack orders
 * are also "move" under the hood but carry an attack target and NO position target, so they never
 * match - a mech boosted into a fight couldn't shoot until it landed. Units mid-cycle under other
 * features (flee, core-heal, assist-share parking - all {@link UnitClaims}-claimed) are skipped;
 * those features manage their own boosting where it makes sense. A unit whose boost stance the
 * player set themselves is never touched (only stances THIS feature enabled are tracked and
 * cleared). Redirecting a boosted unit to another far point mid-flight just keeps it flying.
 */
public class TravelBoostFeature implements Feature{
    /** How often (in ticks) units are scanned. */
    static final float SCAN_INTERVAL_TICKS = 30f;
    /** Close enough to the move target to land - matches the "practically there" feel, not exact arrival. */
    static final float ARRIVAL_TILES = 4f;

    /** Units whose boost stance WE enabled for the current trip - the only ones ever cleared. */
    final IntSet boostedByUs = new IntSet();
    final IntSeq enableIds = new IntSeq();
    final IntSeq disableIds = new IntSeq();
    final IntSeq dropIds = new IntSeq();

    float scanTimer = 0f;

    @Override
    public String id(){
        return "travel-boost";
    }

    @Override
    public String titleKey(){
        return "qol.feature.travel-boost.title";
    }

    @Override
    public void init(){
        Events.on(WorldLoadEvent.class, e -> {
            boostedByUs.clear();
            scanTimer = 0f;
        });
        Events.run(Trigger.update, this::update);
    }

    @Override
    public void buildSettings(SettingsTable table){
        table.sliderPref("travelboost-distance", 25, 10, 100, 5, v -> v + " tiles");
        table.sliderPref("travelboost-manual-hold", 20, 0, 120, 5, v -> v == 0 ? "off" : v + " s");
    }

    void update(){
        if(!state.isGame() || !isEnabled() || player == null || player.team().data() == null) return;

        scanTimer += Time.delta;
        if(scanTimer < SCAN_INTERVAL_TICKS) return;
        scanTimer = 0f;

        float far = SafeSettings.getInt("travelboost-distance", 25) * tilesize;
        float arrive = ARRIVAL_TILES * tilesize;
        long manualHold = SafeSettings.getInt("travelboost-manual-hold", 20) * 1000L;

        //maintain pass: land arrivals, forget re-commanded/dead units
        dropIds.clear();
        disableIds.clear();
        for(IntSet.IntSetIterator it = boostedByUs.iterator(); it.hasNext;){
            int id = it.next();
            Unit u = Groups.unit.getByID(id);
            if(u == null || !u.isValid() || u.team != player.team() || !(u.controller() instanceof CommandAI ai)){
                dropIds.add(id);
            }else if(ManualControl.isStanceRecent(id, manualHold)){
                //игрок сам переключил stance (в т.ч. посадил мех) - не возвращаем и не сажаем за него
                dropIds.add(id);
            }else if(ai.currentCommand() != UnitCommand.moveCommand || ai.targetPos == null
                || u.within(ai.targetPos, arrive)){
                //trip over (arrived, or the order changed to something that isn't a plain move) -
                //take the stance back so the mech lands and is vanilla again
                dropIds.add(id);
                disableIds.add(id);
            }
        }
        for(int i = 0; i < dropIds.size; i++){
            boostedByUs.remove(dropIds.get(i));
        }
        if(disableIds.size > 0){
            Call.setUnitStance(player, disableIds.toArray(), UnitStance.boost, false);
        }

        //enable pass: fresh long-distance movers take off
        enableIds.clear();
        player.team().data().units.each(u -> {
            if(!u.type.canBoost) return;
            if(!u.isCommandable() || !(u.controller() instanceof CommandAI ai)) return;
            if(boostedByUs.contains(u.id)) return;
            if(UnitClaims.isClaimed(u.id)) return;
            if(ManualControl.isStanceRecent(u.id, manualHold)) return; //игрок только что сам рулил boost-ом
            if(ai.currentCommand() != UnitCommand.moveCommand || ai.targetPos == null) return;
            if(u.within(ai.targetPos, far)) return;
            if(ai.hasStance(UnitStance.boost)) return; //player set it themselves - theirs to keep

            boostedByUs.add(u.id);
            enableIds.add(u.id);
        });
        if(enableIds.size > 0){
            Call.setUnitStance(player, enableIds.toArray(), UnitStance.boost, true);
        }
    }
}
