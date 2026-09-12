package qol.controlhelper.core;

import arc.Core;
import arc.Events;
import arc.input.KeyBind;
import arc.input.KeyCode;
import arc.struct.Seq;
import mindustry.content.Blocks;
import mindustry.entities.units.BuildPlan;
import mindustry.game.EventType;
import mindustry.gen.Building;
import mindustry.gen.Groups;
import mindustry.input.InputHandler;
import mindustry.type.Category;
import mindustry.world.Block;
import mindustry.world.blocks.power.PowerGraph;
import mindustry.world.blocks.power.PowerNode;
import qol.controlhelper.core.requestexecutor.IRequest;
import qol.controlhelper.core.requestexecutor.RequestExecutor;
import qol.core.SafeSettings;

import java.util.function.BooleanSupplier;

import static arc.Core.input;
import static arc.Core.scene;
import static mindustry.Vars.control;
import static mindustry.Vars.headless;
import static mindustry.Vars.player;
import static mindustry.Vars.state;
import static mindustry.Vars.ui;

/**
 * Hotkey version of what {@link PowerNetworkReconnector}'s button only does when the two networks are
 * already in range of a single existing node: press the key, and it links your closest network to the
 * next-closest one - building whatever chain of new power nodes is needed to actually reach it if a
 * direct link isn't possible yet.
 * <p>
 * Reuses {@link PowerNetworkReconnector#findLink} first (via the shared instance passed in) to check
 * whether the closest pair of graphs can already be joined by configuring an existing node - if so, that's
 * strictly cheaper than building anything, so it just queues that link exactly like the reconnect button
 * does. Only when that comes back empty does it fall back to laying a new chain of {@link
 * Blocks#powerNode} - at sonka's request, ported from the vendored Scheme mod's own node-connect tool
 * ({@code scheme.tools.BuildingTools#connect}/{@code scheme.moded.SchemeInput}) rather than the from-scratch
 * hop-spacing/tile-snapping search this used to do: aim a line one tile short of each existing building
 * (same "px - 1 : px + 1" trick Scheme uses so the line's own endpoint doesn't land on the building's
 * occupied tile) and hand it to {@link InputHandler#updateLine} - the same line-placement pass a manual
 * shift-drag runs, so it already spaces plans by the block's size and applies replacement rules - then
 * {@link InputHandler#flushPlans} filters out anything that doesn't fit via the normal {@code validPlace}
 * check. Each node still auto-links to its neighbors the moment it's actually built (see {@code
 * PowerNode.PowerNodeBuild#placed}), so the chain self-assembles as the player walks it, exactly like
 * manually placing a line of nodes always has - no bespoke pathfinding or buildable-tile search needed.
 */
public class PowerBridgeBuilder{
    public static final KeyBind connectPowerNetworksKey = KeyBind.add("connect_power_networks", KeyCode.n, "control-helper");

    static final PowerNode NODE = (PowerNode)Blocks.powerNode;

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
        //sit far from the biggest graph. Restricted to Category.power buildings (nodes/generators/
        //batteries) on both sides - a plain consumer (turret, drill, factory...) is just as much a graph
        //member but is usually tucked away inside the base rather than sitting at an accessible edge, so
        //aiming the new chain at one produces an endpoint that's awkward to reach or outright unbuildable
        Building bestA = null, bestB = null;
        float bestDist = Float.MAX_VALUE;
        for(int i = 0; i < seenGraphs.size; i++){
            for(int j = i + 1; j < seenGraphs.size; j++){
                PowerGraph ga = seenGraphs.get(i), gb = seenGraphs.get(j);
                for(Building a : ga.all){
                    if(a.block.category != Category.power) continue;
                    for(Building b : gb.all){
                        if(b.block.category != Category.power) continue;
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
        int ax = from.tileX(), ay = from.tileY();
        int bx = to.tileX(), by = to.tileY();
        //scheme-size "px - 1 : px + 1" trick, applied at both ends since both are existing occupied
        //buildings here (Scheme's own connect() only ever has one - the other end is a blank zone)
        int x1 = ax > bx ? ax - 1 : ax + 1;
        int y1 = ay > by ? ay - 1 : ay + 1;
        int x2 = bx > ax ? bx - 1 : bx + 1;
        int y2 = by > ay ? by - 1 : by + 1;

        InputHandler in = control.input;
        Block prevBlock = in.block;
        in.block = NODE;
        in.updateLine(x1, y1, x2, y2);
        Seq<BuildPlan> plans = new Seq<>(in.linePlans);
        in.linePlans.clear();
        in.block = prevBlock;

        if(plans.isEmpty()){
            ui.showInfoToast(Core.bundle.get("qol.power-bridge.blocked", "No clear spot for new nodes between those networks"), 3f);
            return;
        }

        in.flushPlans(plans);
        ui.showInfoFade(Core.bundle.format("qol.power-bridge.queued", plans.size));
    }

    public boolean IsEnabled(){
        return SafeSettings.getBool("powerBridgeBuilder", true);
    }
}
