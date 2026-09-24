package qol.autobuildschematic;

import arc.Core;
import arc.Events;
import arc.input.KeyCode;
import arc.math.Mathf;
import arc.struct.IntSeq;
import arc.struct.IntSet;
import arc.struct.Seq;
import arc.util.Time;
import mindustry.ai.UnitCommand;
import mindustry.ai.types.CommandAI;
import mindustry.entities.units.BuildPlan;
import mindustry.game.EventType.LineConfirmEvent;
import mindustry.game.EventType.Trigger;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.game.Teams.BlockPlan;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Unit;
import mindustry.input.Binding;
import mindustry.ui.dialogs.BaseDialog;
import mindustry.ui.dialogs.SettingsMenuDialog.SettingsTable;
import mindustry.world.Block;
import mindustry.world.Tile;
import qol.core.ButtonSetting;
import qol.core.Feature;
import qol.core.SafeSettings;
import qol.ui.QolWindow;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import static mindustry.Vars.control;
import static mindustry.Vars.net;
import static mindustry.Vars.player;
import static mindustry.Vars.state;
import static mindustry.Vars.tilesize;
import static mindustry.Vars.ui;
import static mindustry.Vars.world;

/**
 * Two triggers, same handoff underneath: hold Shift and left-click while a schematic is previewed at the
 * cursor (same gesture surface as {@link qol.forcebuildschematic.ForceBuildSchematicFeature}'s Ctrl-click,
 * deliberately a different modifier so the two don't collide) - OR hold Shift while placing/dragging a
 * normal line of blocks by hand (a conveyor belt, a run of nodes, anything). Either way, the freshly
 * queued blocks get pushed into the team's shared rebuild queue ({@code team.data().plans}, the same
 * {@code Queue<BlockPlan>} vanilla itself feeds when one of your buildings is destroyed, for
 * rebuild-command units to fix later) and PART of the currently assist-commanded helpers get switched over
 * to the vanilla "Rebuild" command, scaled to how many blocks there are.
 * <p>
 * Engine fact ({@code BuilderAI.updateMovement}): on the plain (non-assist) Rebuild command, a unit with
 * no current build plan and nobody to follow pulls its next block straight off {@code team.data().plans}
 * on its own timer ({@code rebuildPeriod}) - entirely independent of any player being present or actively
 * building. "Assist" mode instead needs SOMEONE on the team actively building right now to follow, which
 * is exactly the participation sonka wanted to drop. Pushing plans into the shared queue and handing units
 * the Rebuild command is therefore all it takes for them to keep building completely on their own
 * afterwards - the actual "build without me" work is just vanilla {@code BuilderAI}'s existing Rebuild
 * loop; this feature's own job is the initial hand-off plus noticing when it's done (see below).
 * <p>
 * The hand-drag path listens for {@link LineConfirmEvent} - fired right after the game commits a line
 * placement (drag OR single click) to the player's own build queue (see {@code
 * qol.bridgetocore.BridgeToCoreFeature}'s {@code onLineConfirm}, same event, same "diff against a synced
 * known-plans identity set" idiom, copied from there since it already solved this exact "which plans are
 * new" problem). Fresh plans are then REMOVED from the player's own queue (they're being handed to helpers
 * instead of built personally) via {@code Queue.remove(T)} - {@code BuildPlan} has no {@code equals}
 * override, so this is identity removal, exactly what's needed. {@code knownPlans} is resynced every tick
 * (not just inside the event handler) for the same reason {@code BridgeToCoreFeature} does it: so a plan
 * added some other way (schematic paste, drag-moving an existing plan) is already "known" by the time the
 * next real line commit fires, and doesn't get mistaken for part of it.
 * <p>
 * Registration-order note: {@code BridgeToCoreFeature} registers its own {@link LineConfirmEvent} listener
 * before this feature does (earlier in {@code QolSuiteMod}'s feature list), so if its titanium/conveyor/
 * junction build-toggle rewrote a freshly-dragged conveyor run into a sorter/inverted-sorter pattern before
 * this listener runs, this feature dispatches helpers to build THAT final pattern, not the original plain
 * line - which is what you'd want, not a bug, but worth knowing if the two ever need to be decoupled.
 * <p>
 * Candidates are ranked by an optional configurable TYPE priority first (see {@link #showConfigDialog}),
 * then by distance to the plan-center within the same rank - sonka's ask: slow ground types (a mech like
 * Nova) are good auto-build labor since they don't need to keep pace with a moving player anyway, while
 * fast types (Poly) are worth keeping on Assist so they can actually follow you around. Types not listed
 * rank last (least preferred for auto-build, most likely to stay on Assist) - an empty/unset priority list
 * (the default) falls back to pure distance sorting, unchanged from before this existed. Only
 * {@code Math.ceil(blockCount / autobuild-blocks-per-helper)} of the ranked candidates (clamped to how many
 * are actually available) get redirected, not the whole assist pool.
 * <p>
 * Completion tracking: the exact (x, y, block) targets queued are remembered in {@link #pendingTargets},
 * checked every {@link #CHECK_INTERVAL_TICKS} against the live world (a target is done once that tile
 * actually holds the right block) rather than trying to watch {@code team.data().plans} itself - that
 * queue only pops a finished entry lazily, the next time SOME unit happens to re-examine its head, so it
 * can't be trusted as a live "still pending" signal. Once every target is confirmed built, every unit this
 * feature redirected ({@link #managedUnits}) that's STILL on Rebuild (not manually re-commanded since) gets
 * switched back to Assist in one batch.
 * <p>
 * <b>Real multiplayer (connecting to someone else's server) can't do any of the above.</b> {@code
 * team.data().plans} is a plain object field with no accompanying {@code Call.*} RPC to remotely add to
 * it - unlike a mutation on a self-hosted game (client and server are the SAME objects there), a networked
 * client's {@code player.team().data()} is only a locally-rendered shadow copy fed by server snapshots;
 * writing to it has zero effect on the server's own authoritative `TeamData`, so Rebuild-commanded units
 * on the real server would just find that queue perpetually empty. Checked the full {@code Call} RPC list
 * (`mindustry.gen.Call`, generated from `@Remote`-annotated methods) for anything resembling "queue a plan
 * for another unit" - the only build-plan-related RPC is {@code clientPlanSnapshot}, which syncs the
 * PLAYER'S OWN currently-controlled unit's queue automatically (that's how `player.unit().addBuild(...)`
 * already gets to the server safely, e.g. in {@code ForceBuildSchematicFeature}/{@code
 * ConveyorUpgradeFeature}) - there's no equivalent for arbitrary other units. So on a real server ({@link
 * mindustry.net.Net#client()} true), this feature does nothing beyond what a plain click/drag already
 * does - Shift is simply ignored there rather than pretending to redirect helpers that would never
 * actually receive anything.
 */
public class AutoBuildSchematicFeature implements Feature{
    static final float CHECK_INTERVAL_TICKS = 30f;

    final IntSeq unitIds = new IntSeq();
    final Seq<Unit> candidates = new Seq<>();
    final Seq<Target> pendingTargets = new Seq<>();
    final IntSet managedUnits = new IntSet();
    private final Set<BuildPlan> knownPlans = Collections.newSetFromMap(new IdentityHashMap<>());
    boolean mouseWasDown = false;
    float checkTimer = 0f;

    @Override
    public String id(){
        return "auto-build-schematic";
    }

    @Override
    public String titleKey(){
        return "qol.feature.auto-build-schematic.title";
    }

    @Override
    public void init(){
        Events.run(Trigger.update, this::update);
        Events.on(LineConfirmEvent.class, e -> onLineConfirm());
        Events.on(WorldLoadEvent.class, e -> {
            pendingTargets.clear();
            managedUnits.clear();
            knownPlans.clear();
            checkTimer = 0f;
        });
    }

    @Override
    public void buildSettings(SettingsTable table){
        table.sliderPref("autobuild-blocks-per-helper", 5, 1, 30, 1, v -> v + " blocks/helper");
        table.pref(new ButtonSetting("qol-autobuild-configure", this::showConfigDialog));
    }

    /** Бинд автопостройки (по умолчанию Shift, настраивается в управлении). Пока он на левом Shift, правый работает как раньше. */
    boolean shiftDown(){
        return Core.input.keyDown(Binding.autoBuildModifier)
            || (Binding.autoBuildModifier.value.key == KeyCode.shiftLeft && Core.input.keyDown(KeyCode.shiftRight));
    }

    /** Бинд автопостройки совпал с Shift - тем же, что запускает обход препятствий у мостов. */
    boolean sharesShift(){
        KeyCode key = Binding.autoBuildModifier.value.key;
        return key == KeyCode.shiftLeft || key == KeyCode.shiftRight;
    }

    void update(){
        if(!isEnabled() || state.isMenu() || player == null || player.team() == null){
            mouseWasDown = false;
            return;
        }

        syncKnownPlans();
        checkCompletion();

        boolean mouseDown = Core.input.keyDown(KeyCode.mouseLeft);
        boolean mouseJustPressed = mouseDown && !mouseWasDown;
        mouseWasDown = mouseDown;

        if(!shiftDown() || !mouseJustPressed) return;

        Seq<BuildPlan> selectPlans = control.input.selectPlans;
        if(selectPlans.isEmpty()) return;

        if(net.client()){
            //real server, not host/singleplayer - there's no RPC to remotely populate the team's shared
            //rebuild queue (see class javadoc), so redirecting helpers here would just leave them staring
            //at a queue only WE can see. Leave selectPlans untouched so vanilla places it normally, like
            //Shift was never held.
            ui.showInfoToast(Core.bundle.get("qol.auto-build-schematic.mp-unsupported",
                "Auto Build only works when hosting or in singleplayer"), 3f);
            return;
        }

        dispatch(selectPlans);
        selectPlans.clear();
    }

    void syncKnownPlans(){
        knownPlans.clear();
        if(player.unit() != null) for(BuildPlan p : player.unit().plans()) knownPlans.add(p);
    }

    /**
     * Fires right after the game commits a hand-placed line (or single block) to the player's build
     * queue. Diffs against {@link #knownPlans} to find exactly what this commit just added, and - only
     * while Shift is held - pulls those plans OUT of the player's own queue and hands them to helpers
     * instead, same as the schematic gesture.
     */
    void onLineConfirm(){
        if(!isEnabled() || state.isMenu() || player == null || player.team() == null || player.dead() || player.unit() == null) return;

        Seq<BuildPlan> freshPlans = new Seq<>();
        for(BuildPlan p : player.unit().plans()){
            if(!knownPlans.contains(p)) freshPlans.add(p);
        }
        syncKnownPlans();

        if(freshPlans.isEmpty() || !shiftDown() || net.client()) return;
        // Shift-цепочка мостов (обход препятствий) строится самим игроком, пока автопостройка сидит на том же Shift; перебиндишь - будет уходить помощникам
        if(control.input.bridgePathLine && sharesShift()) return;

        for(BuildPlan p : freshPlans) player.unit().plans.remove(p);

        dispatch(freshPlans);
    }

    /** Queues every plan into the team's shared rebuild queue and redirects a size-scaled slice of the current assist pool to Rebuild. */
    void dispatch(Seq<BuildPlan> plans){
        int queued = 0;
        float sumX = 0f, sumY = 0f;
        for(BuildPlan p : plans){
            if(p.block == null) continue;
            player.team().data().plans.addLast(new BlockPlan(p.x, p.y, (short)p.rotation, p.block, p.config));
            pendingTargets.add(new Target(p.x, p.y, p.block));
            sumX += p.x;
            sumY += p.y;
            queued++;
        }
        if(queued == 0) return;

        float centerX = sumX / queued * tilesize, centerY = sumY / queued * tilesize;

        candidates.clear();
        player.team().data().units.each(u -> {
            if(u.isCommandable() && u.controller() instanceof CommandAI ai && ai.currentCommand() == UnitCommand.assistCommand){
                candidates.add(u);
            }
        });

        Seq<String> priority = loadPriority();
        candidates.sort((a, b) -> {
            int ra = priority.indexOf(a.type.name), rb = priority.indexOf(b.type.name);
            if(ra == -1) ra = Integer.MAX_VALUE;
            if(rb == -1) rb = Integer.MAX_VALUE;
            if(ra != rb) return Integer.compare(ra, rb);
            return Float.compare(a.dst2(centerX, centerY), b.dst2(centerX, centerY));
        });

        int blocksPerHelper = SafeSettings.getInt("autobuild-blocks-per-helper", 5);
        //Mathf.clamp(v, 1, candidates.size) would floor at 1 even with zero candidates (min > max),
        //handing candidates.get(0) an empty Seq - crashed for sonka (IndexOutOfBoundsException) the
        //first time this ran with no helpers currently on assist
        int use = candidates.isEmpty() ? 0 : Mathf.clamp(Mathf.ceil(queued / (float)blocksPerHelper), 1, candidates.size);

        unitIds.clear();
        for(int i = 0; i < use; i++) unitIds.add(candidates.get(i).id);
        if(unitIds.size > 0){
            Call.setUnitCommand(player, unitIds.toArray(), UnitCommand.rebuildCommand);
            for(int i = 0; i < unitIds.size; i++) managedUnits.add(unitIds.get(i));
        }

        ui.showInfoToast(Core.bundle.format("qol.auto-build-schematic.queued", queued, unitIds.size), 3f);
    }

    /** Prunes targets that are actually built now; once none are left, releases every still-Rebuild-commanded managed unit back to Assist. */
    void checkCompletion(){
        if(pendingTargets.isEmpty()) return;

        checkTimer += Time.delta;
        if(checkTimer < CHECK_INTERVAL_TICKS) return;
        checkTimer = 0f;

        for(int i = pendingTargets.size - 1; i >= 0; i--){
            Target t = pendingTargets.get(i);
            Tile tile = world.tile(t.x, t.y);
            if(tile != null && tile.block() == t.block) pendingTargets.remove(i);
        }

        if(!pendingTargets.isEmpty()) return;

        IntSeq ids = new IntSeq();
        for(IntSet.IntSetIterator it = managedUnits.iterator(); it.hasNext;){
            int id = it.next();
            Unit u = Groups.unit.getByID(id);
            if(u != null && u.isValid() && u.team == player.team()
                && u.controller() instanceof CommandAI ai && ai.currentCommand() == UnitCommand.rebuildCommand){
                ids.add(id);
            }
        }
        if(ids.size > 0) Call.setUnitCommand(player, ids.toArray(), UnitCommand.assistCommand);
        managedUnits.clear();
    }

    static Seq<String> loadPriority(){
        String raw = SafeSettings.getString("autobuild-priority", "");
        Seq<String> out = new Seq<>();
        for(String s : raw.split(",")){
            s = s.trim();
            if(!s.isEmpty()) out.add(s);
        }
        return out;
    }

    void showConfigDialog(){
        BaseDialog dialog = new BaseDialog(Core.bundle.get("qol.auto-build-schematic.dialog.title", "Auto Build"));
        dialog.addCloseButton();

        String[] priorityText = {SafeSettings.getString("autobuild-priority", "")};

        dialog.cont.pane(t -> {
            t.defaults().left().pad(4f);
            t.add(Core.bundle.get("qol.auto-build-schematic.dialog.hint", "")).wrap().width(480f).row();

            t.add(Core.bundle.get("qol.auto-build-schematic.dialog.priority", "Priority list (comma-separated, best for auto-build first; unlisted types stay on Assist):")).row();
            t.area(priorityText[0], v -> priorityText[0] = v).size(480f, 120f).row();
        }).width(520f).height(280f).row();

        dialog.cont.button(Core.bundle.get("qol.auto-build-schematic.dialog.save", "Save"), () -> {
            Core.settings.put("autobuild-priority", priorityText[0]);
            dialog.hide();
        }).size(200f, 50f).padTop(10f);

        dialog.show();
    }

    static class Target{
        final int x, y;
        final Block block;

        Target(int x, int y, Block block){
            this.x = x;
            this.y = y;
            this.block = block;
        }
    }
}
