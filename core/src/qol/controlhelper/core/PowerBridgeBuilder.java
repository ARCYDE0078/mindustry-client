package qol.controlhelper.core;

import arc.Core;
import arc.Events;
import arc.input.KeyBind;
import arc.input.KeyCode;
import arc.math.Mathf;
import arc.struct.Seq;
import mindustry.content.Blocks;
import mindustry.entities.units.BuildPlan;
import mindustry.game.EventType;
import mindustry.gen.Building;
import mindustry.gen.Groups;
import mindustry.world.Tile;
import mindustry.world.blocks.power.PowerGraph;
import mindustry.world.blocks.power.PowerNode;
import qol.bridgetocore.BridgeToCoreFeature;
import qol.controlhelper.core.requestexecutor.IRequest;
import qol.controlhelper.core.requestexecutor.RequestExecutor;
import qol.core.SafeSettings;

import java.util.function.BooleanSupplier;

import static arc.Core.input;
import static arc.Core.scene;
import static mindustry.Vars.headless;
import static mindustry.Vars.player;
import static mindustry.Vars.state;
import static mindustry.Vars.tilesize;
import static mindustry.Vars.ui;
import static mindustry.Vars.world;

/**
 * Hotkey version of what {@link PowerNetworkReconnector}'s button only does when the two networks are
 * already in range of a single existing node: press the key, and it links your closest network to the
 * next-closest one - building whatever chain of new power nodes is needed to actually reach it if a
 * direct link isn't possible yet.
 * <p>
 * Reuses {@link PowerNetworkReconnector#findLink} first (via the shared instance passed in) to check
 * whether the closest pair of graphs can already be joined by configuring an existing node - if so, that's
 * strictly cheaper than building anything, so it just queues that link exactly like the reconnect button
 * does. Only when that comes back empty (neither graph has a node with spare capacity already in laser
 * range of the other) does this fall back to laying new {@link Blocks#powerNodeLarge} down as a straight
 * chain of {@link BuildPlan}s, one per hop, spaced under the block's own {@code laserRange} - each node
 * auto-links to its neighbors the moment it's actually built (see {@code PowerNode.PowerNodeBuild#placed}),
 * so the chain self-assembles as the player walks it, the same way manually placing a line of nodes always
 * has.
 * <p>
 * Power node lasers aren't blocked by terrain or other buildings (only distance and each node's own
 * {@code maxNodes} capacity matter - see {@code PowerNode#linkValid}), so unlike {@link
 * qol.bridgetocore.BridgeToCoreFeature}'s item routes this deliberately does NOT need a real obstacle-
 * avoiding A* search: a straight line of evenly spaced waypoints is already an optimal path. Each waypoint
 * only needs a small spiral nudge off its exact straight-line spot to land on a buildable tile, reusing
 * {@link BridgeToCoreFeature#canBuildOn} for that check - the same validity predicate the bridge/junction/
 * titanium route finders use, just without the pathfinding around it.
 */
public class PowerBridgeBuilder{
    public static final KeyBind connectPowerNetworksKey = KeyBind.add("connect_power_networks", KeyCode.n, "control-helper");

    static final PowerNode NODE = (PowerNode)Blocks.powerNodeLarge;
    /**
     * Hop spacing is kept safely under the node's real laser range rather than right up against it - a
     * waypoint that lands exactly on the theoretical max range can still fail {@code linkValid} once
     * nudged a tile or two off the ideal straight-line spot to dodge an obstacle, which would silently
     * break the chain in the middle. 80% of {@code laserRange} leaves enough slack for that nudge.
     */
    static final float RANGE_SAFETY = 0.8f;
    static final int MAX_SNAP_RADIUS = 8;

    final RequestExecutor requestExecutor;
    final PowerNetworkReconnector reconnector;
    final BooleanSupplier masterEnabled;

    public PowerBridgeBuilder(RequestExecutor requestExecutor, PowerNetworkReconnector reconnector, BooleanSupplier masterEnabled){
        this.requestExecutor = requestExecutor;
        this.reconnector = reconnector;
        this.masterEnabled = masterEnabled;
    }

    public void Init(){
        Events.run(EventType.Trigger.update, this::update);
    }

    void update(){
        if(headless || !masterEnabled.getAsBoolean() || !IsEnabled() || !state.isGame()
            || player == null || player.dead() || player.unit() == null) return;
        if(scene.hasKeyboard() || !input.keyTap(connectPowerNetworksKey)) return;
        connect();
    }

    void connect(){
        Seq<PowerGraph> seenGraphs = new Seq<>();
        for(Building b : Groups.build){
            if(b.team != player.team() || b.power == null || b.power.graph == null) continue;
            if(!seenGraphs.contains(b.power.graph)) seenGraphs.add(b.power.graph);
        }

        if(seenGraphs.size < 2){
            ui.showInfoToast(Core.bundle.get("qol.power-bridge.none", "No other power network to connect to"), 3f);
            return;
        }

        //closest PAIR of buildings across any two distinct graphs, not just vs the biggest one - same
        //reasoning as PowerNetworkReconnector's own javadoc: two small graphs near each other might both
        //sit far from the biggest graph
        Building bestA = null, bestB = null;
        float bestDist = Float.MAX_VALUE;
        for(int i = 0; i < seenGraphs.size; i++){
            for(int j = i + 1; j < seenGraphs.size; j++){
                PowerGraph ga = seenGraphs.get(i), gb = seenGraphs.get(j);
                for(Building a : ga.all){
                    for(Building b : gb.all){
                        float d = a.dst2(b);
                        if(d < bestDist){
                            bestDist = d;
                            bestA = a;
                            bestB = b;
                        }
                    }
                }
            }
        }
        if(bestA == null) return;

        Building[] direct = reconnector.findLink(bestA.power.graph, bestB.power.graph);
        if(direct == null) direct = reconnector.findLink(bestB.power.graph, bestA.power.graph);
        if(direct != null){
            requestExecutor.AddRequest(new IRequest.TileConfig(direct[0], direct[1].pos()));
            ui.showInfoFade(Core.bundle.get("qol.power-bridge.linked", "Linked with an existing node"));
            return;
        }

        buildChain(bestA, bestB);
    }

    void buildChain(Building from, Building to){
        float hopDist = NODE.laserRange * tilesize * RANGE_SAFETY;
        float totalDist = from.dst(to);
        //at least 2: this branch only runs once a direct link was already ruled out, so even a short
        //gap needs at least one brand new node in between to act as the bridge
        int hops = Math.max(2, Mathf.ceil(totalDist / hopDist));

        int placed = 0;
        for(int k = 1; k < hops; k++){
            float t = (float)k / hops;
            float wx = Mathf.lerp(from.x, to.x, t);
            float wy = Mathf.lerp(from.y, to.y, t);
            Tile tile = findBuildableNear(wx, wy);
            //a single blocked waypoint doesn't necessarily doom the chain - autolink plus the
            //neighboring hops' own range may still bridge across the gap once built, so keep going
            //rather than aborting the whole chain over one obstructed spot
            if(tile == null) continue;
            player.unit().addBuild(new BuildPlan(tile.x, tile.y, 0, NODE));
            placed++;
        }

        if(placed > 0){
            ui.showInfoFade(Core.bundle.format("qol.power-bridge.queued", placed));
        }else{
            ui.showInfoToast(Core.bundle.get("qol.power-bridge.blocked", "No clear spot for new nodes between those networks"), 3f);
        }
    }

    static Tile findBuildableNear(float worldX, float worldY){
        Tile center = world.tileWorld(worldX, worldY);
        if(center == null) return null;

        for(int r = 0; r <= MAX_SNAP_RADIUS; r++){
            for(int dx = -r; dx <= r; dx++){
                for(int dy = -r; dy <= r; dy++){
                    if(Math.max(Math.abs(dx), Math.abs(dy)) != r) continue; //ring only - inner radii already tried on a previous r
                    int tx = center.x + dx, ty = center.y + dy;
                    if(BridgeToCoreFeature.canBuildOn(NODE, player.team(), tx, ty, 0)){
                        return world.tile(tx, ty);
                    }
                }
            }
        }
        return null;
    }

    public boolean IsEnabled(){
        return SafeSettings.getBool("powerBridgeBuilder", true);
    }
}
