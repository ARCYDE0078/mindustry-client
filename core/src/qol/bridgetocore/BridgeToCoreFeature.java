package qol.bridgetocore;

import arc.*;
import arc.func.Cons;
import arc.input.*;
import arc.math.geom.*;
import arc.struct.*;
import mindustry.content.*;
import mindustry.entities.units.*;
import mindustry.game.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.distribution.*;
import mindustry.world.blocks.storage.CoreBlock.*;
import mindustry.ui.dialogs.SettingsMenuDialog.SettingsTable;
import qol.core.Feature;
import qol.core.SafeSettings;
import qol.ui.QolWindow;

import java.util.*;

import static arc.Core.*;
import static mindustry.Vars.*;

/**
 * Ported unchanged from the standalone Bridge To Core mod (github sonka / mindustry-bridge-to-core) -
 * only the packaging changed: it now implements {@link Feature} instead of {@code extends Mod}, its
 * toolbar became a {@link ToolbarWindow} (a {@link QolWindow}) instead of a bespoke draggable Table, and
 * both {@link #update()} and {@link #onLineConfirm()} additionally gate on {@link #isEnabled()} so the
 * hub's per-feature checkbox can turn the whole thing off.
 * <p>
 * Presses a hotkey to route a placement from the cursor to the nearest friendly core, pathfinding
 * around the player's own buildings. Which blocks get used depends on the mode picked on the
 * {@link ToolbarWindow}.
 * <p>
 * Bridge mode routes a chain of regular item bridges (bridge-conveyor). The final stretch next to
 * the core is built with inverted sorters instead of bridges, since bridges can only go straight
 * and can't usefully cover single-tile hops or turns. Only inverted sorters are used (never plain
 * sorters): with no item filter configured, a plain sorter always dumps cargo out its sides, while
 * an inverted sorter passes everything straight through in whatever direction it arrived from,
 * acting like an omnidirectional conveyor. Since that means it still can't perform an actual turn,
 * the search forbids changing direction once inside the final stretch.
 * <p>
 * Junction mode routes the whole way with single tile steps. While {@code ToolbarWindow.junctionBuildActive}
 * is on, that's junction for straight runs, an inverted sorter (lead-in) followed by a plain sorter (the
 * actual corner) for turns - junction isn't instant-transfer, so it gets the same throughput treatment
 * titanium mode uses rather than a single plain sorter per turn - with a minimum gap enforced between
 * turns, same as titanium mode, for the same reason. With the toggle off, the route is plain junction
 * tiles the whole way instead, turns included, and the turn-gap restriction doesn't apply either (nothing
 * to protect once no sorters are being placed). See {@link JunctionRouteFinder}.
 * <p>
 * Titanium conveyor mode also routes with single tile steps. A turn is an inverted sorter (lead-in)
 * followed by a plain sorter (the actual corner), dodging the usual conveyor-corner throughput hit;
 * straight runs cycle through repeating inverted-sorter pairs separated by a single conveyor tile
 * instead of solid conveyor. A minimum gap is enforced between turns, and the tile touching any
 * existing lead-in/corner always stays plain conveyor, so the pattern never chains 3+
 * instant-transfer tiles in a row. See {@link TitaniumRouteFinder}.
 * <p>
 * Independently of all three modes, the three build toggles on the {@link ToolbarWindow} rewrite a
 * block placed through the game's own normal placement UI (drag or click, anywhere - not just along a
 * routed path to the core) into the same optimized conveyor/sorter/inverted-sorter pattern - titanium,
 * regular conveyor, or junction all share {@link #applyLinePattern}, just a different block for the
 * straight runs. Junction-build uses the same lead-in-pair-turns-plus-cadence pattern {@link
 * JunctionRouteFinder}'s own pathfound route uses too (junction isn't an instant-transfer block, so both
 * the manual-drag and pathfound junction paths benefit from it, same as titanium/conveyor mode always
 * have) - the two are effectively mirrors of each other, not a deliberately different pattern, and it's
 * the SAME {@code junctionBuildActive} toggle that gates both halves of the mirror: on, both the
 * manual-drag rewrite and the pathfound route get the full sorter pattern; off, junction-build simply
 * doesn't fire at all (see {@link #onLineConfirm}) and {@link JunctionRouteFinder} leaves its route as
 * plain junction tiles too (see {@link JunctionRouteFinder#sortersEnabled}) - so the two stay mirrors in
 * both states, not just when the toggle happens to be on. See {@link #applyLinePattern}.
 */
public class BridgeToCoreFeature implements Feature{
    public static final KeyBind layBridgesKey = KeyBind.add("lay_bridges_to_core", KeyCode.l, "bridge-to-core");

    //index must line up with dirToRotation()'s convention (0=east, 1=north, 2=west, 3=south) - the
    //junction/titanium route finders store this index directly as a "direction" and later compare it
    //against dirToRotation()'s output to detect turns, so the two must use the same numbering
    static final int[] DX = {1, 0, -1, 0};
    static final int[] DY = {0, 1, 0, -1};

    static final int SEARCH_PADDING = 20;
    /**
     * Cap on how many tiles a single A* search is allowed to expand. Needs to comfortably exceed the
     * tile count of the padded start-end bounding box, not just be "big enough in general" - the
     * strict (forbidTurns) pass specifically requires this, since satisfying a hard turn ban sometimes
     * means the optimal path has to move away from the target for a few tiles before turning back in.
     * A* explores nodes in order of estimated total cost (actual cost so far + straight-line distance
     * left), and moving away from the target always looks worse by that estimate than moving toward
     * it, however briefly - so those "back away first" nodes sort last and only get explored once
     * everything cheaper-looking has already been tried. With too small a budget, the search exhausts
     * itself on doomed-but-heuristically-promising nodes that get close to the target and then hit the
     * ban, never reaching the genuinely valid detour - producing a "no path" result even though one
     * exists, silently falling back to the permissive pass and its close-to-the-core turns. Raised well
     * past the old 4000 specifically to give that detour room to be found.
     */
    static final int MAX_NODES = 20000;
    static final int FINAL_STRETCH_TILES = 2;
    static final float CORNER_PENALTY = 20f;
    static final float CENTER_PRIORITY = 0.1f;
    static final int NO_TURN_RADIUS = 16;
    /**
     * Default for the "minimum turn distance" setting (see {@link #buildSettings}) - hard minimum
     * distance from the target a turn is allowed to happen at, when a route finder's `forbidTurns` is
     * on - not just penalized like the no-turn-radius setting governs, but outright rejected as a
     * search move. A single-bend path's corner sits at a fixed spot determined purely by
     * the cursor/core geometry - if that spot happens to fall close to the core, no amount of penalty
     * tuning can pull it further away, since the cheaper 1-turn path stays cheaper than any 2-turn
     * detour by a wide margin regardless of how the penalty curve is shaped. A hard ban forces the
     * search to actually find that detour instead. See {@link #connectAtCursor} and friends for the
     * two-pass strict-then-permissive fallback that keeps this from ever reporting "no path" just
     * because the strict search couldn't route around something.
     */
    static final int MIN_TURN_DISTANCE = 10;
    static final float TURN_PENALTY_MULT = 5f;
    static final float TURN_BASE_FRACTION = 0.1f;
    static final float LENGTH_TIEBREAK = 0.01f;
    static final float TURN_TIEBREAK = 0.01f;

    /**
     * Build plans this mod has already accounted for (either seen and left alone, or already
     * rewritten by {@link #onLineConfirm}), tracked by identity - {@link mindustry.entities.units.BuildPlan}
     * doesn't override equals/hashCode, so a regular identity-backed set correctly distinguishes two
     * plans that happen to share the same tile/rotation/block. Resynced every tick in {@link #update}
     * regardless of any build toggle, so anything added outside of a line-drag commit
     * (schematic paste, drag-selecting and moving existing plans, etc.) is already "known" by the time
     * any later line placement fires {@link LineConfirmEvent} - otherwise those unrelated plans would
     * look "new" at that point and risk getting swept into the rewrite.
     */
    private final Set<BuildPlan> knownPlans = Collections.newSetFromMap(new IdentityHashMap<>());

    public ToolbarWindow window;

    @Override
    public String id(){
        return "bridge-to-core";
    }

    @Override
    public String titleKey(){
        return "qol.feature.bridge-to-core.title";
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
        window = new ToolbarWindow();
        Events.on(LineConfirmEvent.class, e -> onLineConfirm());
        Events.run(Trigger.update, this::update);
    }

    /**
     * Exposes the pathfinder's tuning knobs as sliders, all defaulting to exactly the constant they
     * replace - at default settings the search is byte-for-byte the same as before this existed.
     * Deliberately NOT exposed: {@link #MAX_NODES} (a search-budget safety valve, not a preference),
     * {@link #FINAL_STRETCH_TILES} (a physical fact about inverted sorters, not tunable), tiebreak
     * epsilons (no user-visible effect), and {@link TitaniumRouteFinder#MIN_TURN_GAP} - that one is
     * load-bearing for titanium mode's core correctness guarantee (never 3+ chained instant-transfer
     * tiles, which Mindustry silently refuses to carry cargo through); letting it go below its current
     * value could produce belts that silently don't move items, with no error to point at why.
     */
    @Override
    public void buildSettings(SettingsTable table){
        table.sliderPref("bridgetocore-turn-aversion", 100, 20, 300, 10, v -> (v / 100f) + "x");
        table.sliderPref("bridgetocore-no-turn-radius", NO_TURN_RADIUS, 4, 40, 2, v -> v + " tiles");
        table.sliderPref("bridgetocore-min-turn-distance", MIN_TURN_DISTANCE, 2, 30, 1, v -> v + " tiles");
        table.sliderPref("bridgetocore-search-padding", SEARCH_PADDING, 5, 60, 5, v -> v + " tiles");
        table.sliderPref("bridgetocore-corner-penalty", (int)CORNER_PENALTY, 0, 100, 5, v -> v + "");
        table.sliderPref("bridgetocore-center-priority", (int)(CENTER_PRIORITY * 100), 0, 200, 5, v -> (v / 100f) + "");
    }

    void update(){
        //this mod is client-only (hotkey, mouse cursor, HUD panel); on a headless/dedicated server
        //there's no scene or input device at all, so touching either would throw. If this jar is ever
        //loaded server-side, just do nothing instead of crashing the whole server every tick.
        if(headless || !isEnabled()) return;
        if(state.isMenu() || player == null || player.dead() || player.unit() == null) return;

        syncKnownPlans();

        if(scene.hasKeyboard() || !input.keyTap(layBridgesKey)) return;

        ToolbarWindow.Mode mode = ToolbarWindow.active;
        if(mode == null) return;

        switch(mode){
            case BRIDGE -> connectAtCursor();
            case JUNCTION -> connectJunctionAtCursor();
            case TITANIUM -> connectTitaniumAtCursor();
        }
    }

    void syncKnownPlans(){
        knownPlans.clear();
        for(BuildPlan p : player.unit().plans()) knownPlans.add(p);
    }

    /**
     * Fires right after the game commits a line placement (drag or single click) to the player's
     * build queue - see DesktopInput/MobileInput's touch-up handling: flush the line, clear it, then
     * fire this event, all synchronously in that order. By comparing the queue against
     * {@link #knownPlans} we find exactly the plans this specific commit just added (new insertions
     * always land at the queue's tail - see BuilderComp#addBuild), regardless of how many plans are
     * already queued ahead of them or how fast those are being consumed by construction.
     * <p>
     * Only rewrites plans matching one of the three build toggles (titanium conveyor / plain conveyor /
     * junction), and only while that specific toggle is on; the queue is still resynced either way, so
     * toggling any of the buttons mid-session never causes a backlog of pre-existing plans to suddenly
     * get swept up.
     * <p>
     * A single line-drag is always uniformly one block type (matching whatever's selected in the build
     * menu), so at most one of the three {@link #rewriteRuns} calls below ever finds anything to do for
     * a given commit - the other two just scan {@code freshPlans} once and see no matching runs. Cheap
     * enough not to bother detecting which toggle is relevant up front.
     */
    void onLineConfirm(){
        if(headless || !isEnabled()) return;
        if(state.isMenu() || player == null || player.dead() || player.unit() == null) return;

        Seq<BuildPlan> freshPlans = new Seq<>();
        for(BuildPlan p : player.unit().plans()){
            if(!knownPlans.contains(p)) freshPlans.add(p);
        }
        syncKnownPlans();

        if(freshPlans.isEmpty()) return;

        if(ToolbarWindow.titaniumBuildActive){
            rewriteRuns(freshPlans, Blocks.titaniumConveyor, run -> applyLinePattern(run, Blocks.titaniumConveyor));
        }
        if(ToolbarWindow.conveyorBuildActive){
            rewriteRuns(freshPlans, Blocks.conveyor, run -> applyLinePattern(run, Blocks.conveyor));
        }
        if(ToolbarWindow.junctionBuildActive){
            rewriteRuns(freshPlans, Blocks.junction, run -> applyLinePattern(run, Blocks.junction));
        }
    }

    /** Finds every contiguous run of {@code targetBlock} within {@code freshPlans} and hands each one to {@code apply} in turn. */
    void rewriteRuns(Seq<BuildPlan> freshPlans, Block targetBlock, Cons<Seq<BuildPlan>> apply){
        int i = 0;
        while(i < freshPlans.size){
            if(freshPlans.get(i).block != targetBlock){ i++; continue; }
            int start = i;
            while(i < freshPlans.size && freshPlans.get(i).block == targetBlock) i++;

            Seq<BuildPlan> run = new Seq<>();
            for(int k = start; k < i; k++) run.add(freshPlans.get(k));
            apply.get(run);
        }
    }

    /**
     * Rewrites a freshly-placed, contiguous run of plain conveyor build plans - titanium, regular, OR
     * junction, whichever {@code conveyor} is passed in; the pattern itself doesn't care which block,
     * only that corners (and, for junction specifically, an unboosted straight run too - junction isn't
     * instant-transfer) cost throughput and instant-transfer blocks (sorter/inverted sorter) don't -
     * into the same pattern {@link TitaniumRouteFinder#reconstruct} builds for titanium mode: turns
     * become an inverted-sorter lead-in plus a plain-sorter corner, straight runs cycle through
     * repeating inverted-sorter pairs separated by single conveyor/junction tiles. See the class comment
     * on {@link TitaniumRouteFinder} for why that specific pattern. {@link JunctionRouteFinder}'s own
     * PATHFOUND route uses this identical pattern too (its {@code reconstruct} was updated to match,
     * with its own {@code MIN_TURN_GAP}/{@code canTurnHere} guarding the search the same way
     * {@link TitaniumRouteFinder} does), but ONLY while {@code junctionBuildActive} is on - the same
     * toggle this method's junction-build call site is itself gated behind, so junction-build and
     * pathfound Junction mode stay mirrors of each other in both states: toggle on, both lay the full
     * sorter pattern; toggle off, junction-build never calls this method at all and the pathfound route
     * falls back to plain junction tiles ({@link JunctionRouteFinder#sortersEnabled}).
     * <p>
     * Computes each tile's outgoing direction itself, from consecutive plans' tile coordinates via
     * {@link #dirToRotation}, and writes it back to {@code p.rotation} - rather than trusting whatever
     * rotation the game's own line placement assigned. This used to just trust {@code p.rotation}
     * (reliable for titanium/plain conveyor, which do get correct per-tile "point toward next tile"
     * rotation from line placement), but junction turned out NOT to reliably get one (junction is one of
     * the few blocks with {@code rotate = false} - visually symmetric, a crossing point, so the game
     * never offers manual rotation for it - plausibly enough of an edge case in the placement-line
     * code's rotation computation to misfire). Computing geometrically instead is a pure
     * behavior-preserving swap for titanium/plain conveyor (a correctly-functioning engine assignment
     * and a geometric computation from the same known tile coordinates necessarily agree) and is what
     * makes this method safe to also call for junction. The LAST tile has no "next" tile within this run
     * to derive a direction from, so it's the one spot that still keeps the plan's own existing
     * rotation - the direction the line was heading.
     * <p>
     * The run's FIRST tile is always left a plain conveyor, unconditionally. It's the one tile
     * whose incoming feed this drag doesn't control - and that feed frequently doesn't exist yet:
     * the supply line gets built up to the belt's start later. An instant-transfer tile only passes
     * cargo straight through in whatever direction it arrived from, so a side feed built against
     * one later would push cargo out of the line entirely, where a plain conveyor accepts a feed
     * from any side and turns it down the line. (Accepted price: the seam where one drag continues
     * another can show two conveyors back to back - the previous drag's ...C-I-I-C tail cadence
     * meeting this rule's mandatory C.)
     * <p>
     * Two turns on adjacent tiles - every step of a shallow-angle diagonal drag is one - are their
     * own special corner shape: the two corner sorters ARE a legal instant-transfer pair all by
     * themselves, so the lead-in pass must NOT prepend an inverted sorter (that would form a
     * 3-chain and bait the defensive sweep into degrading the second corner into a conveyor, which
     * is exactly how shallow diagonals used to end up with 2-3 conveyors bunched together). The
     * double corner comes out ...C-S-S-C..., padded by the neighboring stretches' mandatory
     * boundary conveyors.
     * <p>
     * Also checks the actual tile just past each end of this run - a previous, already-processed
     * drag's build plan, or an already-built structure - and treats an instant-transfer block
     * sitting there exactly like an in-run turn boundary, so continuing a belt across multiple
     * separate drags never chains a 3rd instant-transfer tile onto whatever the previous drag
     * ended on.
     */
    void applyLinePattern(Seq<BuildPlan> run, Block conveyor){
        if(run.size == 0) return;

        Block sorter = Blocks.sorter;
        Block invertedSorter = Blocks.invertedSorter;

        //see the method comment: geometric computation instead of trusting the engine's own rotation,
        //written straight into p.rotation so every later read in this method (classification below,
        //plus first.rotation/last.rotation in the boundary check further down) automatically sees the
        //corrected value with no other changes needed
        for(int i = 0; i < run.size - 1; i++){
            BuildPlan p = run.get(i), next = run.get(i + 1);
            p.rotation = dirToRotation(next.x - p.x, next.y - p.y);
        }

        for(int i = 0; i < run.size; i++){
            BuildPlan p = run.get(i);
            int incomingDir = i == 0 ? -1 : run.get(i - 1).rotation;
            p.block = (incomingDir != -1 && incomingDir != p.rotation) ? sorter : conveyor;
        }

        //lead-ins: an inverted sorter right before each corner - except before a double corner (see
        //the method comment: S-S is already a complete instant pair), and never at position 0 (the
        //first-tile rule). Scans left to right, so at index i everything right of i still holds
        //only first-pass values (conveyor or corner sorter) - the double-corner check can't be
        //fooled by an already-placed lead-in
        for(int i = 1; i < run.size - 1; i++){
            if(run.get(i + 1).block == sorter && run.get(i).block == conveyor
                && (i + 2 >= run.size || run.get(i + 2).block != sorter)){
                run.get(i).block = invertedSorter;
            }
        }

        //the tile just past each end of this drag, if anything's actually there already (a previous
        //drag's plan, still queued, or an already-built structure) - null if that tile is empty
        BuildPlan first = run.get(0), last = run.get(run.size - 1);
        Block beforeRun = adjacentBlock(first.x - DX[first.rotation], first.y - DY[first.rotation]);
        Block afterRun = adjacentBlock(last.x + DX[last.rotation], last.y + DY[last.rotation]);
        boolean externalLeftBounded = beforeRun == sorter || beforeRun == invertedSorter;
        boolean externalRightBounded = afterRun == sorter || afterRun == invertedSorter;

        //straight-stretch cadence: fill each all-conveyor stretch with inverted sorters, then let
        //spreadConveyors write the mandatory and rhythm conveyors back in. A stretch end that butts
        //up against a turn's lead-in+corner pair (always exactly 2 instant-transfer tiles), or
        //against an external instant tile just past the run's edge, must itself stay conveyor -
        //anything else would chain a 3rd instant tile onto that pair - hence the real anchor
        //(offset 0 / runLen - 1, actually written) on bounded ends versus the virtual one
        //(-1 / runLen, only paced off) on open ends. runStart == 0 forces the real anchor
        //unconditionally: that's the first-tile rule
        int idx = 0;
        while(idx < run.size){
            if(run.get(idx).block != conveyor){ idx++; continue; }
            int runStart = idx;
            while(idx < run.size && run.get(idx).block == conveyor) idx++;
            int runEnd = idx;
            int runLen = runEnd - runStart;

            boolean leftBounded = runStart > 0 ? run.get(runStart - 1).block != conveyor : externalLeftBounded;
            boolean rightBounded = runEnd < run.size ? run.get(runEnd).block != conveyor : externalRightBounded;

            for(int k = 0; k < runLen; k++){
                run.get(runStart + k).block = invertedSorter;
            }
            spreadConveyors(run, conveyor, runStart, runLen, (runStart == 0 || leftBounded) ? 0 : -1, rightBounded ? runLen - 1 : runLen);
        }

        //defensive: unlike the route finder's search (which enforces a minimum gap between turns
        //specifically so this can't happen), a manually dragged line - especially diagonal/pathfind
        //placement, which can bend more than once - can put turns close enough together that their
        //corner/lead-in tiles chain up directly, with no plain-conveyor run between them for the
        //cycling pass above to even see (a triple corner, or two single corners one tile apart).
        //Sweep for any leftover stretch of 3+ consecutive instant-transfer tiles and break it up -
        //with the EVENLY-spaced walk, not the pair-greedy one: these stretches sit between the
        //boundary conveyors the cycling pass already anchored, and the pair-greedy walk parks its
        //last break flush against the right edge, gluing 2-3 conveyors together right there.
        //Even spacing centers the breaks instead; the lone corner sorters it leaves behind are
        //fine - a lone plain sorter is just a normal turn (junction mode's standard corner), unlike
        //a lone inverted sorter mid-straight. Virtual anchors as above: offsets -1 and span mean
        //"just past this stretch's edge", never actually written, except at the true ends of this
        //whole drag where a real external instant tile means offset 0 or span - 1 itself must
        //become the mandatory conveyor
        int j = 0;
        while(j < run.size){
            if(run.get(j).block == conveyor){ j++; continue; }
            int start = j;
            while(j < run.size && run.get(j).block != conveyor) j++;
            int span = j - start;
            int from = (start == 0 && externalLeftBounded) ? 0 : -1;
            int to = (j == run.size && externalRightBounded) ? span - 1 : span;
            spreadConveyorsEvenly(run, conveyor, start, span, from, to);
        }
    }

    /**
     * Cadence for the cycling pass's plain-conveyor stretches: marks offsets `from` and `to` within
     * a length-`span` stretch of `run` (starting at `start`) as conveyor, then walks from `from` in
     * steps of 3 writing one conveyor per step, so the instant-transfer tiles left between
     * consecutive conveyors always come in the pattern's signature PAIRS - never 3+ (Mindustry
     * silently refuses to pass cargo through a chain that long), and never the lone single the old
     * spread-as-evenly-as-possible spacing used to scatter (e.g. a 5-stretch between two turns came
     * out C-I-C-I-C where C-I-I-C-C moves cargo exactly as fast - conveyor tiles are what cost
     * travel time, and both have three; when a length forces choosing between a lone inverted
     * sorter and two adjacent conveyors, the pair of conveyors reads as deliberate where the lone
     * sorter reads as a mistake). When the stretch length leaves a lone tile mathematically
     * unavoidable ((to - from) ≡ 2 mod 3), the walk parks it adjacent to the `to` anchor rather
     * than mid-stretch. `from`/`to` may fall outside `[0, span)` (offset -1 or span, "just past
     * this stretch's edge") to mean "don't write it, just pace spacing off it"; bounded ends
     * instead pass 0 and runLen - 1, both inside the stretch and thus explicitly written.
     */
    void spreadConveyors(Seq<BuildPlan> run, Block conveyor, int start, int span, int from, int to){
        if(from >= 0 && from < span) run.get(start + from).block = conveyor;
        if(to >= 0 && to < span) run.get(start + to).block = conveyor;

        //from >= -1 and to <= span keep every visited pos inside [2, span) - no bounds check needed
        for(int pos = from + 3; pos < to; pos += 3){
            run.get(start + pos).block = conveyor;
        }
    }

    /**
     * Same contract as {@link #spreadConveyors}, but spacing the conveyors as evenly as possible
     * instead of pair-greedy - consecutive ones never more than 3 apart, i.e. at most 2
     * instant-transfer tiles between any two. This is the right shape for the defensive sweep's
     * corner clusters and the WRONG one for straight stretches, which is why both spreads exist:
     * a sweep stretch sits between boundary conveyors the cycling pass already wrote, so the
     * pair-greedy walk's habit of parking its final conveyor flush against the `to` edge would glue
     * 2-3 conveyors together right at the seam (how shallow diagonals used to get their conveyor
     * clumps back). Even spacing keeps every break centered - a break lands adjacent to an edge
     * only when it IS the edge anchor itself (a real external instant tile at the drag's true end
     * forcing offset 0 / span - 1, exactly like the cycling pass's bounded ends).
     */
    void spreadConveyorsEvenly(Seq<BuildPlan> run, Block conveyor, int start, int span, int from, int to){
        if(from >= 0 && from < span) run.get(start + from).block = conveyor;
        if(to >= 0 && to < span) run.get(start + to).block = conveyor;

        int totalSteps = to - from;
        if(totalSteps <= 2) return;

        int gaps = (totalSteps + 2) / 3;
        int pos = from, remaining = totalSteps;
        for(int g = 0; g < gaps; g++){
            int stepsLeft = gaps - g;
            int step = (remaining + stepsLeft - 1) / stepsLeft;
            pos += step;
            remaining -= step;
            if(pos >= 0 && pos < span){
                run.get(start + pos).block = conveyor;
            }
        }
    }

    /**
     * The block already sitting at (x, y), if any - checked first against the player's own build
     * queue (a previous drag's plan, possibly not built yet, and possibly already rewritten by an
     * earlier call to {@link #applyLinePattern}) and then against whatever's actually constructed
     * there. Null if the tile is empty (or doesn't exist). Used to extend turn/cadence awareness
     * across separate drags instead of treating each one in isolation.
     */
    Block adjacentBlock(int x, int y){
        for(BuildPlan p : player.unit().plans()){
            if(!p.breaking && p.x == x && p.y == y) return p.block;
        }
        Tile t = world.tile(x, y);
        return t != null && t.build != null ? t.block() : null;
    }

    void connectAtCursor(){
        ItemBridge bridge = (ItemBridge)Blocks.itemBridge;
        Block sorter = Blocks.invertedSorter;

        Tile start = world.tileWorld(input.mouseWorldX(), input.mouseWorldY());
        if(start == null) return;

        CoreBuild core = state.teams.closestCore(start.worldx(), start.worldy(), player.team());
        if(core == null){
            ui.showInfoFade("[scarlet]No core found for your team.");
            return;
        }

        Tile end = findApproachTile(core, start.x, start.y, bridge);
        if(end == null){
            ui.showInfoFade("[scarlet]No free tile next to the core.");
            return;
        }

        if(start.x == end.x && start.y == end.y){
            //a plain conveyor here, not the inverted sorter used the rest of the route: we don't
            //know which side this tile will actually be fed from, and unlike an inverted sorter (which
            //only ever passes cargo straight through), a regular conveyor correctly accepts both a
            //straight feed from directly behind and a 90-degree feed from either side
            Block single = Blocks.conveyor;
            int rot = dirToRotation(core.tileX() - start.x, core.tileY() - start.y);
            if(!canBuildOn(single, player.team(), start.x, start.y, rot)){
                ui.showInfoFade("[scarlet]Can't build next to the core here.");
                return;
            }
            player.unit().addBuild(new BuildPlan(start.x, start.y, rot, single));
            ui.showInfoFade("[accent]Queued 1 block into the core.");
            return;
        }

        //try first with turns forbidden near the core (forces the route to detour rather than accept
        //a turn close to the target, which a soft penalty alone can't do for a single-bend path - see
        //the min-turn-distance setting); fall back to the permissive search if that's not achievable at all
        Seq<Node> path = new RouteFinder(start, end, core, bridge, sorter, true).search();
        if(path == null) path = new RouteFinder(start, end, core, bridge, sorter, false).search();
        if(path == null){
            ui.showInfoFade("[scarlet]No path to the core - too many obstacles.");
            return;
        }

        for(Node n : path){
            player.unit().addBuild(new BuildPlan(n.x, n.y, n.rotation, n.block, n.config));
        }
        ui.showInfoFade("[accent]Queued " + path.size + " block(s) toward the core.");
    }

    /**
     * Junction mode: same target tile as bridge mode, but the whole route is single-tile steps. While
     * {@code ToolbarWindow.junctionBuildActive} is on, that's junction for straight runs, a lead-in/corner
     * sorter pair for turns, same throughput pattern {@link #applyLinePattern} uses for its manual-drag
     * build toggles (junction isn't instant-transfer, so it benefits from the same treatment
     * titanium/conveyor mode already get - see {@link JunctionRouteFinder} for the full reasoning,
     * including what happens with the toggle off). Junctions have no long-range jump, so this is a
     * plain tile-by-tile A* with a flat turn penalty everywhere (not just near the core) to keep the
     * route mostly straight.
     * <p>
     * Every tile is queued as its real target block directly - no scaffold, no separate upgrade pass.
     */
    void connectJunctionAtCursor(){
        Block junction = Blocks.junction;
        Block sorter = Blocks.sorter;
        Block invertedSorter = Blocks.invertedSorter;

        Tile start = world.tileWorld(input.mouseWorldX(), input.mouseWorldY());
        if(start == null) return;

        CoreBuild core = state.teams.closestCore(start.worldx(), start.worldy(), player.team());
        if(core == null){
            ui.showInfoFade("[scarlet]No core found for your team.");
            return;
        }

        Tile end = findApproachTile(core, start.x, start.y, junction);
        if(end == null){
            ui.showInfoFade("[scarlet]No free tile next to the core.");
            return;
        }

        if(start.x == end.x && start.y == end.y){
            //plain conveyor here, not junction - a junction only ever passes cargo straight through,
            //but we don't know which side this tile will actually be fed from, and a regular conveyor
            //correctly accepts a feed from directly behind or a 90-degree feed from either side
            Block single = Blocks.conveyor;
            int rot = dirToRotation(core.tileX() - start.x, core.tileY() - start.y);
            if(!canBuildOn(single, player.team(), start.x, start.y, rot)){
                ui.showInfoFade("[scarlet]Can't build next to the core here.");
                return;
            }
            player.unit().addBuild(new BuildPlan(start.x, start.y, rot, single));
            ui.showInfoFade("[accent]Queued 1 block into the core.");
            return;
        }

        //see connectAtCursor for why this tries a strict (no turns near the core) pass first
        Seq<Node> path = new JunctionRouteFinder(start, end, core, junction, sorter, invertedSorter, true).search();
        if(path == null) path = new JunctionRouteFinder(start, end, core, junction, sorter, invertedSorter, false).search();
        if(path == null){
            ui.showInfoFade("[scarlet]No path to the core - too many obstacles.");
            return;
        }

        for(Node n : path){
            player.unit().addBuild(new BuildPlan(n.x, n.y, n.rotation, n.block));
        }
        ui.showInfoFade("[accent]Queued " + path.size + " block(s) toward the core.");
    }

    /**
     * Titanium conveyor mode: single-tile steps like junction mode, with titanium conveyor for
     * straight runs. A turn is a conveyor -> inverted sorter -> sorter -> conveyor pair, not a single
     * tile - see {@link TitaniumRouteFinder} for the full reasoning and the spacing rule that keeps
     * this safe.
     * <p>
     * Every tile is queued as its real target block directly - no scaffold, no separate upgrade pass.
     */
    void connectTitaniumAtCursor(){
        Block conveyor = Blocks.titaniumConveyor;
        Block sorter = Blocks.sorter;
        Block invertedSorter = Blocks.invertedSorter;

        Tile start = world.tileWorld(input.mouseWorldX(), input.mouseWorldY());
        if(start == null) return;

        CoreBuild core = state.teams.closestCore(start.worldx(), start.worldy(), player.team());
        if(core == null){
            ui.showInfoFade("[scarlet]No core found for your team.");
            return;
        }

        Tile end = findApproachTile(core, start.x, start.y, conveyor);
        if(end == null){
            ui.showInfoFade("[scarlet]No free tile next to the core.");
            return;
        }

        if(start.x == end.x && start.y == end.y){
            //plain titanium conveyor here, not inverted sorter - we don't know which side this tile
            //will actually be fed from, and unlike an inverted sorter (straight-through only), a
            //regular conveyor correctly accepts either a straight feed from behind or a 90-degree
            //feed from either side
            int rot = dirToRotation(core.tileX() - start.x, core.tileY() - start.y);
            if(!canBuildOn(conveyor, player.team(), start.x, start.y, rot)){
                ui.showInfoFade("[scarlet]Can't build next to the core here.");
                return;
            }
            player.unit().addBuild(new BuildPlan(start.x, start.y, rot, conveyor));
            ui.showInfoFade("[accent]Queued 1 block into the core.");
            return;
        }

        //see connectAtCursor for why this tries a strict (no turns near the core) pass first
        Seq<Node> path = new TitaniumRouteFinder(start, end, core, conveyor, sorter, invertedSorter, true).search();
        if(path == null) path = new TitaniumRouteFinder(start, end, core, conveyor, sorter, invertedSorter, false).search();
        if(path == null){
            ui.showInfoFade("[scarlet]No path to the core - too many obstacles.");
            return;
        }

        for(Node n : path){
            player.unit().addBuild(new BuildPlan(n.x, n.y, n.rotation, n.block));
        }
        ui.showInfoFade("[accent]Queued " + path.size + " block(s) toward the core.");
    }

    /** Finds the best free tile touching the core's footprint: prefers the middle of a side over its corners. */
    static Tile findApproachTile(CoreBuild core, int fromX, int fromY, Block placeCheck){
        int cx = core.tileX(), cy = core.tileY();
        int r = core.block.size + 1;

        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for(int fx = -r; fx <= r; fx++){
            for(int fy = -r; fy <= r; fy++){
                Tile ft = world.tile(cx + fx, cy + fy);
                if(ft != null && ft.build == core){
                    minX = Math.min(minX, ft.x); maxX = Math.max(maxX, ft.x);
                    minY = Math.min(minY, ft.y); maxY = Math.max(maxY, ft.y);
                }
            }
        }
        if(minX == Integer.MAX_VALUE){ minX = maxX = cx; minY = maxY = cy; }

        float midX = (minX + maxX) / 2f, midY = (minY + maxY) / 2f;
        float cornerPenalty = SafeSettings.getInt("bridgetocore-corner-penalty", (int)CORNER_PENALTY);
        float centerPriority = SafeSettings.getInt("bridgetocore-center-priority", (int)(CENTER_PRIORITY * 100)) / 100f;

        Tile best = null;
        float bestScore = Float.MAX_VALUE;
        for(int dx = -r; dx <= r; dx++){
            for(int dy = -r; dy <= r; dy++){
                int tx = cx + dx, ty = cy + dy;
                Tile t = world.tile(tx, ty);
                if(t == null || !isOrthogonallyAdjacentTo(t, core)) continue;
                if(!canBuildOn(placeCheck, player.team(), tx, ty, 0)) continue;

                boolean eastWest = Math.abs(tx - midX) > Math.abs(ty - midY);
                boolean corner = eastWest ? (ty == minY || ty == maxY) : (tx == minX || tx == maxX);
                float distToRef = Math.abs(tx - fromX) + Math.abs(ty - fromY);
                float centerOffset = eastWest ? Math.abs(ty - midY) : Math.abs(tx - midX);

                float score = (corner ? cornerPenalty : 0f) + centerOffset * centerPriority + distToRef;
                if(score < bestScore){ bestScore = score; best = t; }
            }
        }
        return best;
    }

    static boolean isOrthogonallyAdjacentTo(Tile t, Building building){
        for(int i = 0; i < 4; i++){
            Tile nt = world.tile(t.x + DX[i], t.y + DY[i]);
            if(nt != null && nt.build == building) return true;
        }
        return false;
    }

    /**
     * rotation: 0 = east(+x), 1 = north(+y), 2 = west(-x), 3 = south(-y). Picks whichever axis has
     * the larger magnitude, not just whichever sign check happens to match first - this matters
     * because this is also called with the raw (core.tileX()-x, core.tileY()-y) offset to face the
     * final tile toward the core, and that offset isn't always purely axis-aligned: for an even-sized
     * core (e.g. Core Foundation, size 4) the true center falls on a half-tile, so core.tileX() is
     * off by one from it, and the approach tile picked on the north/south side can likewise land one
     * tile off on the x-axis. A naive "dx>0 else dy>0 else dx<0 else south" check would then latch
     * onto that 1-tile x-noise and face the block east/west instead of south/north.
     */
    static int dirToRotation(int dx, int dy){
        if(Math.abs(dx) >= Math.abs(dy)) return dx >= 0 ? 0 : 2;
        return dy > 0 ? 1 : 3;
    }

    /**
     * Like {@link Build#validPlace}, but also requires the tile to be genuinely empty. Mindustry's
     * placement rules otherwise happily let a new block "upgrade" an existing one from the same
     * block group (e.g. titanium conveyor placed over a plain conveyor, junction over a router) -
     * which for this mod would mean silently routing straight through and repurposing whatever the
     * player already built there, instead of routing around it like any other obstacle.
     */
    //public: qol.controlhelper.core.PowerBridgeBuilder reuses this exact obstacle/overlap check for its
    //own node-chain placement instead of re-deriving the same Build.validPlace + air-tile logic
    public static boolean canBuildOn(Block type, Team team, int x, int y, int rotation){
        Tile t = world.tile(x, y);
        return t != null && t.block() == Blocks.air && Build.validPlace(type, team, x, y, rotation);
    }

    static float buildCost(Block block, float fallback){
        float total = 0;
        for(ItemStack s : block.requirements) total += s.amount;
        return total > 0 ? total : fallback;
    }

    /**
     * Extra cost added on top of a turn's own block cost, scaled by how close the tile is to the
     * core: cheap (just TURN_BASE_FRACTION of the max) far away, rising to the full scale right next
     * to it. Used by bridge mode, where baseCost is the (relatively expensive) bridge's own cost -
     * scaling off of that keeps the penalty meaningful relative to how much a bridge itself costs.
     * <p>
     * The proximity curve is cubic, not quadratic - it stays close to its far-away baseline for most
     * of the no-turn radius and then rises much more steeply over the last few tiles, so a turn right next
     * to the core costs dramatically more than one just a handful of tiles further out, not just
     * somewhat more. Pushes the search to spend its one unavoidable turn as early as possible and
     * finish with a long straight run into the core, rather than turning comfortably right up to the
     * doorstep.
     */
    static float turnPenalty(float baseCost, int distToEnd, float turnAversion, int noTurnRadius){
        float proximity = Math.max(0f, 1f - distToEnd / (float)noTurnRadius);
        return baseCost * TURN_PENALTY_MULT * turnAversion * (TURN_BASE_FRACTION + (1f - TURN_BASE_FRACTION) * proximity * proximity * proximity);
    }

    static final float SINGLE_TILE_TURN_BASE = 8f;
    static final float SINGLE_TILE_TURN_NEAR_BOOST = 28f;

    /**
     * Same idea as {@link #turnPenalty}, for junction/titanium mode's single-tile steps. Doesn't
     * scale off of a block's own cost the way the bridge version does - junction/conveyor tiles are
     * cheap (cost 1-3), so scaling off of them the same way collapsed the far-away penalty to a
     * fraction of what it used to be (a flat 8) and made the search noticeably more willing to
     * zigzag unnecessarily anywhere along the route, not just near the core. This keeps that same
     * proven baseline (8) everywhere, and adds a separate, larger boost specifically the closer a
     * tile gets to the core, instead of shrinking the baseline to make room for it. Same cubic curve
     * and reasoning as {@link #turnPenalty} - most of the ramp-up happens right at the doorstep.
     */
    static float singleTileTurnPenalty(int distToEnd, float turnAversion, int noTurnRadius){
        float proximity = Math.max(0f, 1f - distToEnd / (float)noTurnRadius);
        return (SINGLE_TILE_TURN_BASE + SINGLE_TILE_TURN_NEAR_BOOST * proximity * proximity * proximity) * turnAversion;
    }

    static class Node{
        int x, y, rotation;
        Block block;
        Object config;
        /** Direction this node was reached from (see DX/DY), or -1 for the very first node. */
        int incomingDir = -1;

        Node(int x, int y, Block block){
            this.x = x;
            this.y = y;
            this.block = block;
        }
    }

    static class PathState{
        float cost;
        int parentX, parentY;
        Block block;
        int dirIndex;

        PathState(float cost, int parentX, int parentY, Block block, int dirIndex){
            this.cost = cost;
            this.parentX = parentX;
            this.parentY = parentY;
            this.block = block;
            this.dirIndex = dirIndex;
        }
    }

    static class QNode{
        int x, y;
        float priority;

        QNode(int x, int y, float priority){
            this.x = x;
            this.y = y;
            this.priority = priority;
        }
    }

    /**
     * A* search over a tile grid with two edge types: a bridge step (1..range tiles, never spanning
     * over the player's own buildings) and a single-tile inverted-sorter step. Only the sorter step
     * is offered within FINAL_STRETCH_TILES of the destination, and only bridge steps everywhere
     * else - this keeps every bridge tile a real bridge-to-bridge link (bridges can only link to
     * another bridge; a 1-tile bridge step just relies on the plain forward dump between two
     * adjacent bridges, no actual link needed). An inverted sorter can't actually turn cargo (it
     * only continues whatever direction the cargo arrived from), so once a node is inside the final
     * stretch, the search is no longer allowed to change direction - the turn has to happen earlier,
     * out in the bridge zone. This particular ban is a hard physical fact and always applies,
     * regardless of `forbidTurns` below.
     * <p>
     * `forbidTurns`, when on, extends that same hard ban out to the min-turn-distance setting - much
     * farther than the final stretch alone. A single-bend path's corner sits at one of exactly two
     * fixed spots determined by cursor/core geometry, so the soft, distance-scaled {@link
     * #turnPenalty} can only pick which of those two spots to use - it can't pull the corner away from
     * the core if that's where the fixed spot happens to be; only a hard rule that forces an actual
     * multi-turn detour can. See {@link #connectAtCursor} for the two-pass strict-then-permissive
     * fallback that uses this without ever turning a merely-inconvenient corner into an outright
     * "no path found".
     */
    static class RouteFinder{
        static final int NO_PARENT = Integer.MIN_VALUE;

        final Tile start, end;
        final CoreBuild core;
        final ItemBridge bridge;
        final Block sorter;
        final Team team;
        final int range;
        final float bridgeCost, sorterCost;
        final boolean approachAxisX;
        final boolean forbidTurns;
        final int minX, maxX, minY, maxY;
        final float turnAversion;
        final int noTurnRadius, minTurnDistance;
        final HashMap<Long, PathState> dist = new HashMap<>();
        final HashSet<Long> closed = new HashSet<>();
        final PriorityQueue<QNode> heap = new PriorityQueue<>((a, b) -> Float.compare(a.priority, b.priority));

        RouteFinder(Tile start, Tile end, CoreBuild core, ItemBridge bridge, Block sorter, boolean forbidTurns){
            this.start = start;
            this.end = end;
            this.core = core;
            this.bridge = bridge;
            this.sorter = sorter;
            this.team = player.team();
            this.range = bridge.range;
            this.bridgeCost = buildCost(bridge, 6);
            this.sorterCost = buildCost(sorter, 2);
            this.approachAxisX = Math.abs(end.x - core.tileX()) >= Math.abs(end.y - core.tileY());
            this.forbidTurns = forbidTurns;
            this.turnAversion = SafeSettings.getInt("bridgetocore-turn-aversion", 100) / 100f;
            this.noTurnRadius = SafeSettings.getInt("bridgetocore-no-turn-radius", NO_TURN_RADIUS);
            this.minTurnDistance = SafeSettings.getInt("bridgetocore-min-turn-distance", MIN_TURN_DISTANCE);
            int searchPadding = SafeSettings.getInt("bridgetocore-search-padding", SEARCH_PADDING);
            this.minX = Math.min(start.x, end.x) - searchPadding;
            this.maxX = Math.max(start.x, end.x) + searchPadding;
            this.minY = Math.min(start.y, end.y) - searchPadding;
            this.maxY = Math.max(start.y, end.y) + searchPadding;
        }

        static long key(int x, int y){
            return (long)x << 32 | (y & 0xFFFFFFFFL);
        }

        float heuristic(int x, int y){
            int manhattan = Math.abs(end.x - x) + Math.abs(end.y - y);
            float costPerTile = Math.min(sorterCost, bridgeCost / range);
            return manhattan * costPerTile;
        }

        boolean friendlyBetween(int x0, int y0, int x1, int y1){
            if(x0 == x1){
                int lo = Math.min(y0, y1), hi = Math.max(y0, y1);
                for(int y = lo + 1; y < hi; y++){
                    Tile t = world.tile(x0, y);
                    if(t != null && t.build != null && t.build.team == team) return true;
                }
            }else{
                int lo = Math.min(x0, x1), hi = Math.max(x0, x1);
                for(int x = lo + 1; x < hi; x++){
                    Tile t = world.tile(x, y0);
                    if(t != null && t.build != null && t.build.team == team) return true;
                }
            }
            return false;
        }

        Seq<Node> search(){
            boolean startNear = Math.abs(end.x - start.x) + Math.abs(end.y - start.y) < FINAL_STRETCH_TILES;
            dist.put(key(start.x, start.y), new PathState(0f, NO_PARENT, NO_PARENT, startNear ? sorter : bridge, -1));
            heap.add(new QNode(start.x, start.y, heuristic(start.x, start.y)));

            boolean found = false;
            int expanded = 0;

            while(!heap.isEmpty()){
                QNode cur = heap.poll();
                long ck = key(cur.x, cur.y);
                if(closed.contains(ck)) continue;
                closed.add(ck);
                if(++expanded > MAX_NODES) break;

                if(cur.x == end.x && cur.y == end.y){ found = true; break; }

                PathState curState = dist.get(ck);
                int distToEnd = Math.abs(end.x - cur.x) + Math.abs(end.y - cur.y);
                boolean nearHere = distToEnd < FINAL_STRETCH_TILES;
                //nearHere's ban is a hard physical fact (a bridge/sorter can't turn in the final
                //stretch - see class comment) and always applies; forbidTurns extends the same ban
                //out to minTurnDistance, but only on the strict first pass - see connectAtCursor
                boolean turnBanned = nearHere || (forbidTurns && distToEnd < minTurnDistance);

                float turnCost = turnPenalty(bridgeCost, distToEnd, turnAversion, noTurnRadius);

                for(int di = 0; di < 4; di++){
                    int ddx = DX[di], ddy = DY[di];

                    if(nearHere){
                        tryStep(cur.x, cur.y, curState, di, ddx, ddy, 1, sorter, sorterCost, false, turnCost, turnBanned);
                    }else{
                        //h can go all the way down to 1: bridges sitting right next to each other still
                        //hand off items via a plain forward dump, no real link/gap needed for that
                        for(int h = range; h >= 1; h--){
                            tryStep(cur.x, cur.y, curState, di, ddx, ddy, h, bridge, bridgeCost, true, turnCost, turnBanned);
                        }
                    }
                }
            }

            if(!found) return null;
            return reconstruct();
        }

        void tryStep(int cx, int cy, PathState curState, int di, int ddx, int ddy, int h, Block edgeBlock,
                     float edgeCost, boolean checkSpan, float turnCost, boolean turnBanned){
            int nx = cx + ddx * h, ny = cy + ddy * h;
            if(nx < minX || nx > maxX || ny < minY || ny > maxY) return;

            if(nx == end.x && ny == end.y){
                if(approachAxisX && ddy != 0) return;
                if(!approachAxisX && ddx != 0) return;
            }

            long k = key(nx, ny);
            if(closed.contains(k)) return;

            boolean isTurn = curState.dirIndex != -1 && curState.dirIndex != di;
            if(isTurn && turnBanned) return;

            if(!canBuildOn(edgeBlock, team, nx, ny, 0)) return;
            if(checkSpan && friendlyBetween(cx, cy, nx, ny)) return;

            float lengthPenalty = (edgeBlock == bridge && h < range) ? LENGTH_TIEBREAK : 0f;
            float newCost = curState.cost + edgeCost + lengthPenalty + (isTurn ? turnCost + TURN_TIEBREAK : 0f);

            PathState existing = dist.get(k);
            if(existing == null || newCost < existing.cost){
                dist.put(k, new PathState(newCost, cx, cy, edgeBlock, di));
                heap.add(new QNode(nx, ny, newCost + heuristic(nx, ny)));
            }
        }

        Seq<Node> reconstruct(){
            Seq<Node> path = new Seq<>();
            int cx = end.x, cy = end.y;
            while(true){
                PathState st = dist.get(key(cx, cy));
                path.add(new Node(cx, cy, st.block));
                if(st.parentX == NO_PARENT) break;
                cx = st.parentX;
                cy = st.parentY;
            }
            path.reverse();

            for(int i = 0; i < path.size - 1; i++){
                Node n = path.get(i), next = path.get(i + 1);
                int dx = next.x - n.x, dy = next.y - n.y;
                n.rotation = dirToRotation(dx, dy);

                //explicitly link consecutive bridges instead of relying on the game's own auto-link
                //(which tracks only a single global "last placed bridge" and can get confused if the
                //player has other bridges under construction elsewhere at the same time)
                if(n.block == bridge && next.block == bridge){
                    n.config = new Point2(dx, dy);
                }
            }
            Node last = path.peek();
            last.rotation = dirToRotation(core.tileX() - last.x, core.tileY() - last.y);

            return path;
        }
    }

    /**
     * A* search for junction mode: every step is a single tile, either continuing the current
     * direction (junction) or turning. A turn is two tiles, not one: an inverted sorter (the "lead-in",
     * still on the incoming straight leg) immediately followed by a plain sorter (the actual corner) -
     * same shape {@link TitaniumRouteFinder} uses, and for the same reason: junction is NOT
     * instant-transfer (unlike sorter/inverted sorter), so a plain junction corner would be a throughput
     * bottleneck the same way a plain conveyor corner is, and routing the turn through the two sorter
     * variants instead avoids it. The lead-in's only job is passing cargo straight through regardless of
     * which side it arrived from, so the corner always receives from a consistent, known side.
     * <p>
     * Straight runs cycle through repeating inverted-sorter pairs separated by a single junction tile,
     * instead of solid junction, for the same throughput reason - see {@link #reconstruct}. {@link
     * #MIN_TURN_GAP} enforces a minimum distance between turns (checked in {@link #canTurnHere}), same
     * value and reasoning as {@link TitaniumRouteFinder#MIN_TURN_GAP}: a turn occupies 2 instant-transfer
     * tiles, so a gap of 3 guarantees at least 1 plain junction tile separates any two turns, keeping any
     * run of instant-transfer tiles at 2 long, never the 3+ Mindustry refuses to carry cargo through.
     * <p>
     * A distance-scaled penalty is added to a turn's own cost - see {@link #singleTileTurnPenalty} -
     * turning near the core costs much more than turning far away, so bends get pushed toward happening
     * early, out where there's room to route around obstacles, rather than right on the core's doorstep.
     * `forbidTurns`, when on, goes further and rejects a turn outright within the min-turn-distance
     * setting of the core - a single-bend path's corner sits at one of exactly two fixed spots determined
     * by cursor/core geometry, so a soft penalty alone can't move it away from the core if that's where
     * it happens to land; only a hard rule forcing an actual detour can. See {@link
     * #connectJunctionAtCursor} for the two-pass strict-then-permissive fallback that uses this without
     * ever turning a merely-inconvenient corner into an outright "no path found".
     * <p>
     * All of the above (turn sorters, their lead-ins, straight-run cadence, and the {@link #MIN_TURN_GAP}
     * search constraint that exists only to protect that pattern from self-chaining) is gated behind
     * {@link #sortersEnabled}, a snapshot of {@link ToolbarWindow#junctionBuildActive} taken at search
     * time: with the toggle off, the route is plain junction tiles the whole way, turns included - no
     * sorters, no lead-ins, no cadence, and no turn-spacing restriction on the search either, since
     * nothing is being chained anymore. Toggle on = the full throughput-optimized pattern described
     * above.
     */
    static class JunctionRouteFinder{
        static final int NO_PARENT = Integer.MIN_VALUE;
        /** See {@link TitaniumRouteFinder#MIN_TURN_GAP} - identical value and reasoning, applied to junction instead of titanium conveyor. */
        static final int MIN_TURN_GAP = 3;

        final Tile start, end;
        final CoreBuild core;
        final Block junction, sorter, invertedSorter;
        final Team team;
        final float junctionCost, sorterCost;
        final boolean approachAxisX;
        final boolean forbidTurns;
        /** Mirrors {@link ToolbarWindow#junctionBuildActive} at search time - see {@link #reconstruct}. */
        final boolean sortersEnabled;
        final int minX, maxX, minY, maxY;
        final float turnAversion;
        final int noTurnRadius, minTurnDistance;
        final HashMap<Long, PathState> dist = new HashMap<>();
        final HashSet<Long> closed = new HashSet<>();
        final PriorityQueue<QNode> heap = new PriorityQueue<>((a, b) -> Float.compare(a.priority, b.priority));

        JunctionRouteFinder(Tile start, Tile end, CoreBuild core, Block junction, Block sorter, Block invertedSorter, boolean forbidTurns){
            this.start = start;
            this.end = end;
            this.core = core;
            this.junction = junction;
            this.sorter = sorter;
            this.invertedSorter = invertedSorter;
            this.team = player.team();
            this.junctionCost = buildCost(junction, 3);
            this.sorterCost = buildCost(sorter, 2);
            this.approachAxisX = Math.abs(end.x - core.tileX()) >= Math.abs(end.y - core.tileY());
            this.forbidTurns = forbidTurns;
            this.sortersEnabled = ToolbarWindow.junctionBuildActive;
            this.turnAversion = SafeSettings.getInt("bridgetocore-turn-aversion", 100) / 100f;
            this.noTurnRadius = SafeSettings.getInt("bridgetocore-no-turn-radius", NO_TURN_RADIUS);
            this.minTurnDistance = SafeSettings.getInt("bridgetocore-min-turn-distance", MIN_TURN_DISTANCE);
            int searchPadding = SafeSettings.getInt("bridgetocore-search-padding", SEARCH_PADDING);
            this.minX = Math.min(start.x, end.x) - searchPadding;
            this.maxX = Math.max(start.x, end.x) + searchPadding;
            this.minY = Math.min(start.y, end.y) - searchPadding;
            this.maxY = Math.max(start.y, end.y) + searchPadding;
        }

        static long key(int x, int y){
            return (long)x << 32 | (y & 0xFFFFFFFFL);
        }

        float heuristic(int x, int y){
            int manhattan = Math.abs(end.x - x) + Math.abs(end.y - y);
            return manhattan * Math.min(junctionCost, sorterCost);
        }

        Seq<Node> search(){
            dist.put(key(start.x, start.y), new PathState(0f, NO_PARENT, NO_PARENT, junction, -1));
            heap.add(new QNode(start.x, start.y, heuristic(start.x, start.y)));

            boolean found = false;
            int expanded = 0;

            while(!heap.isEmpty()){
                QNode cur = heap.poll();
                long ck = key(cur.x, cur.y);
                if(closed.contains(ck)) continue;
                closed.add(ck);
                if(++expanded > MAX_NODES) break;

                if(cur.x == end.x && cur.y == end.y){ found = true; break; }

                PathState curState = dist.get(ck);
                int distToEnd = Math.abs(end.x - cur.x) + Math.abs(end.y - cur.y);
                float turnCost = singleTileTurnPenalty(distToEnd, turnAversion, noTurnRadius);
                boolean turnBanned = forbidTurns && distToEnd < minTurnDistance;
                for(int di = 0; di < 4; di++){
                    tryStep(cur.x, cur.y, curState, di, DX[di], DY[di], turnCost, turnBanned);
                }
            }

            if(!found) return null;
            return reconstruct();
        }

        /** See {@link TitaniumRouteFinder#canTurnHere} - identical logic, walking this search's own parent chain instead. */
        boolean canTurnHere(PathState curState){
            PathState s = curState;
            int dir = s.dirIndex;
            if(dir == -1) return true;
            for(int i = 0; i < MIN_TURN_GAP; i++){
                if(s.dirIndex == -1) return true;
                if(s.dirIndex != dir) return false;
                if(s.parentX == NO_PARENT) return true;
                s = dist.get(key(s.parentX, s.parentY));
            }
            return true;
        }

        void tryStep(int cx, int cy, PathState curState, int di, int ddx, int ddy, float turnCost, boolean turnBanned){
            int nx = cx + ddx, ny = cy + ddy;
            if(nx < minX || nx > maxX || ny < minY || ny > maxY) return;

            if(nx == end.x && ny == end.y){
                if(approachAxisX && ddy != 0) return;
                if(!approachAxisX && ddx != 0) return;
            }

            long k = key(nx, ny);
            if(closed.contains(k)) return;

            boolean isTurn = curState.dirIndex != -1 && curState.dirIndex != di;
            //canTurnHere/MIN_TURN_GAP only guards against chaining 3+ instant-transfer tiles, a risk
            //that only exists once turns actually get built as sorter pairs - with sorters disabled
            //there's nothing to protect, and enforcing the gap anyway would just needlessly constrain
            //the search (worse/blocked routes) for no benefit.
            if(isTurn && (turnBanned || (sortersEnabled && !canTurnHere(curState)))) return;

            Block edgeBlock = (sortersEnabled && isTurn) ? sorter : junction;
            float edgeCost = (sortersEnabled && isTurn) ? sorterCost : junctionCost;

            if(!canBuildOn(edgeBlock, team, nx, ny, 0)) return;

            float newCost = curState.cost + edgeCost + (isTurn ? turnCost : 0f);

            PathState existing = dist.get(k);
            if(existing == null || newCost < existing.cost){
                dist.put(k, new PathState(newCost, cx, cy, edgeBlock, di));
                heap.add(new QNode(nx, ny, newCost + heuristic(nx, ny)));
            }
        }

        /** Same shape as {@link TitaniumRouteFinder#reconstruct} - base classification, lead-in promotion, then straight-run cadence - with junction standing in for titanium/plain conveyor throughout. */
        Seq<Node> reconstruct(){
            Seq<Node> path = new Seq<>();
            int cx = end.x, cy = end.y;
            while(true){
                PathState st = dist.get(key(cx, cy));
                Node n = new Node(cx, cy, null);
                n.incomingDir = st.dirIndex;
                path.add(n);
                if(st.parentX == NO_PARENT) break;
                cx = st.parentX;
                cy = st.parentY;
            }
            path.reverse();

            //block for each node depends on ITS OWN incoming vs outgoing direction, not the edge
            //that reaches the *next* node - a turn happens at the tile where direction changes, and
            //that tile is the one that needs the sorter, not the tile after it. With sortersEnabled
            //off (ToolbarWindow.junctionBuildActive was false at search time) every tile just stays
            //plain junction, turns included - "просто перекрестки" (sonka's own phrasing): keep the
            //route dead simple unless the toggle explicitly asked for the throughput-optimized pattern.
            for(int i = 0; i < path.size; i++){
                Node n = path.get(i);
                int outDir = i < path.size - 1
                    ? dirToRotation(path.get(i + 1).x - n.x, path.get(i + 1).y - n.y)
                    : dirToRotation(core.tileX() - n.x, core.tileY() - n.y);
                n.rotation = outDir;
                n.block = (sortersEnabled && n.incomingDir != -1 && n.incomingDir != outDir) ? sorter : junction;
            }

            if(sortersEnabled){
                //promote the tile right before each corner to an inverted sorter (the turn's "lead-in") -
                //MIN_TURN_GAP already guarantees at least one plain junction tile separates this from any
                //earlier turn. Skips node 0 (the clicked tile itself) for the same reason
                //TitaniumRouteFinder's own lead-in pass does: its incoming feed isn't controlled by this
                //route (it's whatever the player already has built leading up to the clicked tile).
                for(int i = 1; i < path.size - 1; i++){
                    if(path.get(i + 1).block == sorter && path.get(i).block == junction){
                        path.get(i).block = invertedSorter;
                    }
                }

                //everything still plain junction at this point is the rest of a straight run, once turns
                //and their lead-ins are already placed. Cycle it into repeating pairs of inverted sorters
                //separated by a single junction tile, instead of leaving it solid junction - same cadence
                //math as TitaniumRouteFinder#reconstruct, junction standing in for conveyor.
                int idx = 0;
                while(idx < path.size){
                    if(path.get(idx).block != junction){ idx++; continue; }
                    int runStart = idx;
                    while(idx < path.size && path.get(idx).block == junction) idx++;
                    int runEnd = idx; //exclusive
                    int runLen = runEnd - runStart;

                    boolean leftBounded = runStart > 0 && path.get(runStart - 1).block != junction;
                    boolean rightBounded = runEnd < path.size && path.get(runEnd).block != junction;

                    if(leftBounded && rightBounded){
                        for(int k = 0; k < runLen; k++){
                            path.get(runStart + k).block = invertedSorter;
                        }
                        int from = 0, to = runLen - 1;
                        path.get(runStart + from).block = junction;
                        path.get(runStart + to).block = junction;
                        int totalSteps = to - from;
                        if(totalSteps > 2){
                            int gaps = (totalSteps + 2) / 3;
                            int pos = from, remaining = totalSteps;
                            for(int g = 0; g < gaps; g++){
                                int stepsLeft = gaps - g;
                                int step = (remaining + stepsLeft - 1) / stepsLeft;
                                pos += step;
                                remaining -= step;
                                path.get(runStart + pos).block = junction;
                            }
                        }
                    }else if(leftBounded){
                        for(int k = 0; k < runLen; k++){
                            path.get(runStart + k).block = (k % 3 == 0) ? junction : invertedSorter;
                        }
                    }else if(rightBounded){
                        for(int k = 0; k < runLen; k++){
                            path.get(runEnd - 1 - k).block = (k % 3 == 0) ? junction : invertedSorter;
                        }
                    }else{
                        for(int k = 0; k < runLen; k++){
                            path.get(runStart + k).block = (k % 3 == 2) ? junction : invertedSorter;
                        }
                    }
                }
            }

            return path;
        }
    }

    /**
     * A* search for titanium conveyor mode. Single-tile steps the whole way, titanium conveyor for
     * straight runs. A turn is two tiles, not one: an inverted sorter (the "lead-in", still on the
     * incoming straight leg) immediately followed by a plain sorter (the actual corner) - not letting
     * plain conveyor handle the turn itself, even though it could, because conveyor corners are a
     * well-known belt throughput bottleneck in Mindustry, and neither sorter variant has one (both are
     * "instant transfer"). The lead-in's only job is passing cargo straight through regardless of
     * which side it arrived from (that's what an unconfigured inverted sorter does), so the corner
     * always receives from a consistent, known side; the corner itself is a plain, unconfigured sorter
     * that dumps to the correct perpendicular side to make the actual 90-degree turn.
     * <p>
     * Two instant-transfer tiles in a row (lead-in + corner) is fine - Mindustry only refuses to chain
     * *three* instant-transfer blocks in a row, checked from the receiving tile's own perspective
     * (its immediate source and its own chosen target can't both be instant, or it refuses to pass the
     * item at all). The danger is two turns ending up close enough together that their lead-in/corner
     * pairs touch, forming a run of 3+ instant tiles with no plain conveyor gap - that's exactly what
     * broke silently in an earlier version of this mode. {@link #MIN_TURN_GAP} enforces a minimum
     * distance between turns (checked in {@link #canTurnHere}) so that never happens: by the time a
     * new turn is considered, at least one plain conveyor tile is guaranteed to separate it from the
     * previous one.
     * <p>
     * `forbidTurns`, when on, rejects a turn outright within the min-turn-distance setting of the core -
     * on top of the soft, distance-scaled {@link #singleTileTurnPenalty}. A single-bend path's corner
     * sits at one of exactly two fixed spots determined by cursor/core geometry, so no matter how steep
     * the soft penalty curve is, it can only pick which of those two spots to use - it can't pull the
     * corner away from the core if that's where the fixed spot happens to be. Only a hard rule that
     * forces an actual multi-turn detour can. See {@link #connectTitaniumAtCursor} for the two-pass
     * strict-then-permissive fallback that uses this without ever turning a merely-inconvenient corner
     * into an outright "no path found".
     */
    static class TitaniumRouteFinder{
        static final int NO_PARENT = Integer.MIN_VALUE;
        /**
         * Minimum number of tiles a turn's own corner must sit past the previous turn's corner. A
         * turn occupies 2 instant-transfer tiles (lead-in + corner); with a gap of 3, exactly 1 plain
         * conveyor tile ends up between the previous corner and the next turn's lead-in - enough to
         * keep any run of instant-transfer tiles at 2 long, never 3. See {@link #canTurnHere}.
         */
        static final int MIN_TURN_GAP = 3;

        final Tile start, end;
        final CoreBuild core;
        final Block conveyor, sorter, invertedSorter;
        final Team team;
        final float conveyorCost, sorterCost;
        final boolean approachAxisX;
        final boolean forbidTurns;
        final int minX, maxX, minY, maxY;
        final float turnAversion;
        final int noTurnRadius, minTurnDistance;
        final HashMap<Long, PathState> dist = new HashMap<>();
        final HashSet<Long> closed = new HashSet<>();
        final PriorityQueue<QNode> heap = new PriorityQueue<>((a, b) -> Float.compare(a.priority, b.priority));

        TitaniumRouteFinder(Tile start, Tile end, CoreBuild core, Block conveyor, Block sorter, Block invertedSorter, boolean forbidTurns){
            this.start = start;
            this.end = end;
            this.core = core;
            this.conveyor = conveyor;
            this.sorter = sorter;
            this.invertedSorter = invertedSorter;
            this.team = player.team();
            this.conveyorCost = buildCost(conveyor, 1);
            this.sorterCost = buildCost(sorter, 2);
            this.approachAxisX = Math.abs(end.x - core.tileX()) >= Math.abs(end.y - core.tileY());
            this.forbidTurns = forbidTurns;
            this.turnAversion = SafeSettings.getInt("bridgetocore-turn-aversion", 100) / 100f;
            this.noTurnRadius = SafeSettings.getInt("bridgetocore-no-turn-radius", NO_TURN_RADIUS);
            this.minTurnDistance = SafeSettings.getInt("bridgetocore-min-turn-distance", MIN_TURN_DISTANCE);
            int searchPadding = SafeSettings.getInt("bridgetocore-search-padding", SEARCH_PADDING);
            this.minX = Math.min(start.x, end.x) - searchPadding;
            this.maxX = Math.max(start.x, end.x) + searchPadding;
            this.minY = Math.min(start.y, end.y) - searchPadding;
            this.maxY = Math.max(start.y, end.y) + searchPadding;
        }

        static long key(int x, int y){
            return (long)x << 32 | (y & 0xFFFFFFFFL);
        }

        float heuristic(int x, int y){
            int manhattan = Math.abs(end.x - x) + Math.abs(end.y - y);
            return manhattan * Math.min(conveyorCost, sorterCost);
        }

        Seq<Node> search(){
            dist.put(key(start.x, start.y), new PathState(0f, NO_PARENT, NO_PARENT, conveyor, -1));
            heap.add(new QNode(start.x, start.y, heuristic(start.x, start.y)));

            boolean found = false;
            int expanded = 0;

            while(!heap.isEmpty()){
                QNode cur = heap.poll();
                long ck = key(cur.x, cur.y);
                if(closed.contains(ck)) continue;
                closed.add(ck);
                if(++expanded > MAX_NODES) break;

                if(cur.x == end.x && cur.y == end.y){ found = true; break; }

                PathState curState = dist.get(ck);
                int distToEnd = Math.abs(end.x - cur.x) + Math.abs(end.y - cur.y);
                float turnCost = singleTileTurnPenalty(distToEnd, turnAversion, noTurnRadius);
                boolean turnBanned = forbidTurns && distToEnd < minTurnDistance;

                for(int di = 0; di < 4; di++){
                    tryStep(cur.x, cur.y, curState, di, DX[di], DY[di], turnCost, turnBanned);
                }
            }

            if(!found) return null;
            return reconstruct();
        }

        /**
         * Walks back through the parent chain from curState (the tile a candidate turn would be
         * placed at) checking that its direction has held steady for at least MIN_TURN_GAP tiles.
         * Finding a different dirIndex within that stretch means a previous turn's corner is too
         * close, so this new turn has to be rejected - the search will have to find another way to
         * work around whatever it was trying to route past.
         */
        boolean canTurnHere(PathState curState){
            PathState s = curState;
            int dir = s.dirIndex;
            if(dir == -1) return true;
            for(int i = 0; i < MIN_TURN_GAP; i++){
                //reached the true start of the route (dirIndex -1 is the "no direction yet" sentinel,
                //not a real direction) without finding MIN_TURN_GAP steps of steady direction - that's
                //fine, there's no earlier turn this close to conflict with, the route just hasn't run
                //far enough yet. Checking this before the dirIndex comparison below matters: -1 never
                //equals a real direction value, so without this check every turn attempted within
                //MIN_TURN_GAP tiles of the route's start would look like a false "conflict" and get
                //rejected, forcing the search to avoid turning near the start entirely.
                if(s.dirIndex == -1) return true;
                if(s.dirIndex != dir) return false;
                if(s.parentX == NO_PARENT) return true;
                s = dist.get(key(s.parentX, s.parentY));
            }
            return true;
        }

        void tryStep(int cx, int cy, PathState curState, int di, int ddx, int ddy, float turnCost, boolean turnBanned){
            int nx = cx + ddx, ny = cy + ddy;
            if(nx < minX || nx > maxX || ny < minY || ny > maxY) return;

            if(nx == end.x && ny == end.y){
                if(approachAxisX && ddy != 0) return;
                if(!approachAxisX && ddx != 0) return;
            }

            long k = key(nx, ny);
            if(closed.contains(k)) return;

            boolean isTurn = curState.dirIndex != -1 && curState.dirIndex != di;
            if(isTurn && (turnBanned || !canTurnHere(curState))) return;

            Block edgeBlock = isTurn ? sorter : conveyor;
            float edgeCost = isTurn ? sorterCost : conveyorCost;

            if(!canBuildOn(edgeBlock, team, nx, ny, 0)) return;

            float newCost = curState.cost + edgeCost + (isTurn ? turnCost : 0f);

            PathState existing = dist.get(k);
            if(existing == null || newCost < existing.cost){
                dist.put(k, new PathState(newCost, cx, cy, edgeBlock, di));
                heap.add(new QNode(nx, ny, newCost + heuristic(nx, ny)));
            }
        }

        Seq<Node> reconstruct(){
            Seq<Node> path = new Seq<>();
            int cx = end.x, cy = end.y;
            while(true){
                PathState st = dist.get(key(cx, cy));
                Node n = new Node(cx, cy, null);
                n.incomingDir = st.dirIndex;
                path.add(n);
                if(st.parentX == NO_PARENT) break;
                cx = st.parentX;
                cy = st.parentY;
            }
            path.reverse();

            //same fix as junction mode: block depends on this node's own incoming vs outgoing
            //direction, not the edge that reaches the next node
            for(int i = 0; i < path.size; i++){
                Node n = path.get(i);
                int outDir = i < path.size - 1
                    ? dirToRotation(path.get(i + 1).x - n.x, path.get(i + 1).y - n.y)
                    : dirToRotation(core.tileX() - n.x, core.tileY() - n.y);
                n.rotation = outDir;
                n.block = (n.incomingDir != -1 && n.incomingDir != outDir) ? sorter : conveyor;
            }

            //promote the tile right before each corner to an inverted sorter (the turn's "lead-in") -
            //MIN_TURN_GAP already guarantees at least one plain conveyor tile separates this from any
            //earlier turn, so this never creates a run of 3+ instant-transfer tiles. Starts from i = 1,
            //deliberately skipping node 0 (the clicked tile itself): a plain conveyor there feeds the
            //very next corner exactly as reliably as an inverted sorter would (its output is always
            //from its facing side, which this route already points at that corner) - the lead-in
            //treatment only actually buys instant transfer instead of a normal conveyor tick, not
            //correctness. Node 0 is the one tile whose own *incoming* feed isn't controlled by this
            //route (it's whatever the player already has built leading up to the clicked tile), so
            //leaving it a plain, unremarkable conveyor - the same block as everywhere else on a
            //straight run - means there's nothing there that could need a manual fix if that existing
            //feed comes from an unexpected side.
            for(int i = 1; i < path.size - 1; i++){
                if(path.get(i + 1).block == sorter && path.get(i).block == conveyor){
                    path.get(i).block = invertedSorter;
                }
            }

            //everything still plain conveyor at this point is the rest of a straight run, once turns
            //and their lead-ins are already placed. Cycle it into repeating pairs of inverted sorters
            //separated by a single conveyor tile, instead of leaving it solid conveyor. The tile
            //touching an existing lead-in/corner has to stay conveyor no matter what, though: an extra
            //inverted sorter there would put 3 consecutive instant-transfer tiles in a row (that tile,
            //the lead-in, and the corner), exactly the chain Mindustry refuses to carry cargo through -
            //so the cycle starts counting from whichever end of a run actually touches a turn, with
            //that first tile forced to conveyor. A run with no turn on either side (the whole route is
            //a straight line with no turns at all) has no such constraint and just cycles from its own
            //first tile instead.
            int idx = 0;
            while(idx < path.size){
                if(path.get(idx).block != conveyor){ idx++; continue; }
                int runStart = idx;
                while(idx < path.size && path.get(idx).block == conveyor) idx++;
                int runEnd = idx; //exclusive
                int runLen = runEnd - runStart;

                boolean leftBounded = runStart > 0 && path.get(runStart - 1).block != conveyor;
                boolean rightBounded = runEnd < path.size && path.get(runEnd).block != conveyor;

                if(leftBounded && rightBounded){
                    //both ends butt up against a turn's fixed lead-in+corner pair, which is always
                    //exactly 2 instant-transfer tiles - leaving EITHER end tile as a sorter would
                    //chain a 3rd instant-transfer tile onto that pair, so both ends (offsets 0 and
                    //runLen - 1) are mandatory conveyor, unconditionally. Spread the remaining
                    //conveyor tiles as evenly as possible between them instead of cycling from the
                    //left and patching the right afterward - patching can leave a second conveyor
                    //sitting right next to the boundary one whenever the natural cycle doesn't
                    //already land on the right tile (any run of length 2, or length congruent to 2
                    //mod 3 - both reachable here despite MIN_TURN_GAP, which only guarantees runLen
                    //>= 1, not any particular residue)
                    for(int k = 0; k < runLen; k++){
                        path.get(runStart + k).block = invertedSorter;
                    }
                    int from = 0, to = runLen - 1;
                    path.get(runStart + from).block = conveyor;
                    path.get(runStart + to).block = conveyor;
                    int totalSteps = to - from;
                    if(totalSteps > 2){
                        int gaps = (totalSteps + 2) / 3;
                        int pos = from, remaining = totalSteps;
                        for(int g = 0; g < gaps; g++){
                            int stepsLeft = gaps - g;
                            int step = (remaining + stepsLeft - 1) / stepsLeft;
                            pos += step;
                            remaining -= step;
                            path.get(runStart + pos).block = conveyor;
                        }
                    }
                }else if(leftBounded){
                    for(int k = 0; k < runLen; k++){
                        path.get(runStart + k).block = (k % 3 == 0) ? conveyor : invertedSorter;
                    }
                }else if(rightBounded){
                    for(int k = 0; k < runLen; k++){
                        path.get(runEnd - 1 - k).block = (k % 3 == 0) ? conveyor : invertedSorter;
                    }
                }else{
                    for(int k = 0; k < runLen; k++){
                        path.get(runStart + k).block = (k % 3 == 2) ? conveyor : invertedSorter;
                    }
                }
            }

            return path;
        }
    }
}
