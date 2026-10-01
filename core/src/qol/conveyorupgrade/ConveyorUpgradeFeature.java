package qol.conveyorupgrade;

import arc.Core;
import arc.Events;
import arc.input.KeyBind;
import arc.input.KeyCode;
import arc.struct.IntSet;
import arc.struct.Seq;
import arc.util.Time;
import mindustry.content.Blocks;
import mindustry.entities.units.BuildPlan;
import mindustry.game.EventType.Trigger;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.gen.Building;
import mindustry.gen.Unit;
import mindustry.ui.dialogs.SettingsMenuDialog.SettingsTable;
import mindustry.world.Tile;
import qol.core.Feature;

import static mindustry.Vars.player;
import static mindustry.Vars.state;
import static mindustry.Vars.ui;
import static mindustry.Vars.world;

/**
 * Mass conveyor upgrade: one hotkey queues a replacement of EVERY plain conveyor the team owns with
 * a titanium conveyor. Titanium is the one true drop-in upgrade (same behavior, just faster) -
 * armored conveyors refuse side input from non-conveyors and would break existing layouts, so
 * they're deliberately not offered.
 * <p>
 * The plans go through the player unit's completely normal build queue ({@code addBuild} - placing
 * a conveyor onto a conveyor is a vanilla replace), so cost, build speed, helpers assisting, and
 * multiplayer sync all behave exactly like hand-placed blocks. The full base can be thousands of
 * tiles though, and every queued plan draws its ghost overlay every frame - so instead of dumping
 * the whole list at once, a pending list tops the queue up to {@link #QUEUE_WATERMARK} plans and
 * refills as they complete. Dying mid-upgrade is fine: the respawned unit's empty queue just gets
 * topped up again. Pressing the hotkey again while an upgrade is running cancels it (pending
 * dropped + our still-queued plans removed; anything already under construction finishes normally).
 */
public class ConveyorUpgradeFeature implements Feature{
    static final KeyBind upgradeKey = KeyBind.add("upgrade-conveyors", KeyCode.unset, "conveyor-upgrade"); // без дефолта: F7 = штатный toggle_strict_mode, и одно нажатие делало и то, и массовый апгрейд за титан

    /** How many of our plans may sit in the unit's build queue at once. */
    static final int QUEUE_WATERMARK = 60;
    /** How often (in ticks) the queue is topped up / completion is checked. */
    static final float TOPUP_INTERVAL_TICKS = 15f;

    final Seq<BuildPlan> pending = new Seq<>();
    /** Packed positions of plans we've already pushed into the unit's queue, for cancel and the done-check. */
    final IntSet issuedPos = new IntSet();
    boolean active = false;

    float topupTimer = 0f;

    @Override
    public String id(){
        return "conveyor-upgrade";
    }

    @Override
    public String titleKey(){
        return "qol.feature.conveyor-upgrade.title";
    }

    @Override
    public void init(){
        Events.on(WorldLoadEvent.class, e -> {
            pending.clear();
            issuedPos.clear();
            active = false;
        });
        Events.run(Trigger.update, this::update);
    }

    @Override
    public void buildSettings(SettingsTable table){
        //nothing beyond the enable toggle - the hotkey lives in the vanilla Controls screen
    }

    void update(){
        if(!state.isGame() || !isEnabled() || player == null) return;

        //hasKeyboard = the player is typing (chat, textfields) - a keybind must not fire there
        if(Core.input.keyTap(upgradeKey) && !Core.scene.hasKeyboard()){
            if(active){
                cancel();
            }else{
                start();
            }
        }

        if(!active) return;
        topupTimer += Time.delta;
        if(topupTimer < TOPUP_INTERVAL_TICKS) return;
        topupTimer = 0f;

        topUp();
        checkDone();
    }

    void start(){
        pending.clear();
        issuedPos.clear();
        for(Building b : player.team().data().buildings){
            if(b.block == Blocks.conveyor){
                pending.add(new BuildPlan(b.tileX(), b.tileY(), b.rotation, Blocks.titaniumConveyor));
            }
        }
        if(pending.isEmpty()){
            ui.showInfoToast(Core.bundle.get("qol.conveyor-upgrade.none", "No plain conveyors to upgrade"), 3f);
            return;
        }
        active = true;
        topupTimer = TOPUP_INTERVAL_TICKS; //first top-up next frame
        ui.showInfoToast(Core.bundle.format("qol.conveyor-upgrade.start", pending.size), 3f);
    }

    void cancel(){
        pending.clear();
        Unit u = player.unit();
        if(u != null && u.canBuild()){
            //Queue.remove(Boolf) removes only the FIRST match - loop until none left
            while(u.plans().remove(p -> !p.breaking && p.block == Blocks.titaniumConveyor
                && p.tile() != null && issuedPos.contains(p.tile().pos()))){
            }
        }
        issuedPos.clear();
        active = false;
        ui.showInfoToast(Core.bundle.get("qol.conveyor-upgrade.cancel", "Conveyor upgrade cancelled"), 3f);
    }

    void topUp(){
        Unit u = player.unit();
        if(u == null || !u.canBuild()) return;

        while(!pending.isEmpty() && u.plans().size < QUEUE_WATERMARK){
            BuildPlan plan = pending.pop();
            //the world may have moved on since the sweep - only replace what is still a plain
            //conveyor of ours (skips tiles already upgraded, destroyed, or under construction)
            Tile tile = world.tile(plan.x, plan.y);
            if(tile == null || tile.build == null || tile.build.block != Blocks.conveyor || tile.build.team != player.team()) continue;
            issuedPos.add(tile.pos());
            u.addBuild(plan);
        }
    }

    void checkDone(){
        if(!pending.isEmpty()) return;
        Unit u = player.unit();
        if(u != null && u.canBuild() && u.plans().indexOf(p -> !p.breaking && p.tile() != null && issuedPos.contains(p.tile().pos())) != -1) return;

        active = false;
        issuedPos.clear();
        ui.showInfoToast(Core.bundle.get("qol.conveyor-upgrade.done", "Conveyor upgrade finished"), 3f);
    }
}
