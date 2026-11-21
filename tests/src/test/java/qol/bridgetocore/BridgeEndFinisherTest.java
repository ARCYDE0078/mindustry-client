package qol.bridgetocore;

import arc.math.geom.*;
import arc.struct.*;
import arc.util.*;
import mindustry.ai.types.*;
import mindustry.content.*;
import mindustry.core.GameState.*;
import mindustry.entities.units.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.world.*;
import mindustry.world.blocks.storage.CoreBlock.*;
import org.junit.jupiter.api.*;
import test.ApplicationTests;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Прогон {@link BridgeEndFinisher} на настоящем движке (headless): планы -> материализуем блоки -> гоним медь из
 * источника до ядра и проверяем, что дошла (и что не утекла в соседние блоки). Ядро шард 3x3 в (20,20): тайлы 19..21.
 * Направления: 0 = вправо, 1 = вверх, 2 = влево, 3 = вниз.
 */
public class BridgeEndFinisherTest{
    static final int CX = 20, CY = 20;
    CoreBuild core;
    Seq<BuildPlan> line = new Seq<>();

    @BeforeAll
    public static void launch(){
        ApplicationTests.launchApplication(true);
    }

    @BeforeEach
    void setup(){
        Time.setDeltaProvider(() -> 1f);
        logic.reset();
        state.set(State.playing);
        state.rules.limitMapArea = false;
        Tiles tiles = world.resize(40, 40);
        world.beginMapLoad();
        tiles.fill();
        world.endMapLoad();
        world.tile(CX, CY).setBlock(Blocks.coreShard, Team.sharded, 0);
        core = (CoreBuild)world.tile(CX, CY).build;

        Player p = Player.create();
        p.team(Team.sharded);
        Unit u = UnitTypes.alpha.create(Team.sharded);
        p.unit(u);
        u.controller(new GroundAI()); //не «локальный» игрок: addBuild не лезет в control.input (его в headless нет)
        player = p;
        line.clear();
    }

    /** План моста; связь на next (относительный Point2, как у handlePlacementLine) или без связи. */
    BuildPlan bridge(int x, int y, int nx, int ny){
        BuildPlan p = new BuildPlan(x, y, 0, Blocks.itemBridge);
        if(nx != Integer.MIN_VALUE) p.config = new Point2(nx - x, ny - y);
        return p;
    }

    BuildPlan bridgeEnd(int x, int y){
        return bridge(x, y, Integer.MIN_VALUE, 0);
    }

    void queue(BuildPlan... ps){
        for(BuildPlan p : ps){
            player.unit().addBuild(p);
            line.add(p);
        }
    }

    /** Уже построенный мост (без связи). */
    void built(int x, int y){
        world.tile(x, y).setBlock(Blocks.itemBridge, Team.sharded, 0);
    }

    int sorters(){
        int n = 0;
        for(BuildPlan p : player.unit().plans) if(p.block == Blocks.sorter || p.block == Blocks.invertedSorter) n++;
        return n;
    }

    String dump(){
        StringBuilder sb = new StringBuilder();
        for(BuildPlan p : player.unit().plans) sb.append(p.block.name).append('(').append(p.x).append(',').append(p.y).append(")->")
            .append(p.config instanceof Point2 pt ? (p.x + pt.x) + "," + (p.y + pt.y) : "-").append("  ");
        return sb.toString();
    }

    /** Строит все планы как блоки, ставит источник меди позади (sx,sy) и гоняет 800 тиков. @return медь в ядре. */
    int simulate(int sx, int sy, Seq<Tile> watchEmpty){
        for(BuildPlan p : player.unit().plans){
            Tile t = world.tile(p.x, p.y);
            assertTrue(t.block() == Blocks.air || t.build != null, "tile busy " + p.x + "," + p.y);
            if(t.block() == Blocks.air) t.setBlock(p.block, Team.sharded, 0);
        }
        for(BuildPlan p : player.unit().plans){
            if(p.config != null) world.tile(p.x, p.y).build.configureAny(p.config);
        }
        Tile src = world.tile(sx, sy);
        assertEquals(Blocks.air, src.block(), "source tile busy");
        src.setBlock(Blocks.itemSource, Team.sharded);
        src.build.configureAny(Items.copper);

        for(Tile tile : world.tiles) if(tile.build != null && tile.isCenter()) tile.build.updateProximity();
        //ядро «своей» команды в headless лезет в ui.hudfrag (его нет): на время прогона игрок - чужая команда (решатель уже отработал)
        player.team(Team.derelict);
        for(int i = 0; i < 800; i++){
            Time.update();
            for(Tile tile : world.tiles) if(tile.build != null && tile.isCenter() && tile.build != core) tile.build.update();
        }
        if(watchEmpty != null) for(Tile t : watchEmpty) assertEquals(0, t.build.items.total(), "leak into " + t.x + "," + t.y);
        return core.items.get(Items.copper);
    }

    void expectDelivered(int sx, int sy, int maxSorters){
        System.out.println("PLANS: " + dump());
        assertTrue(sorters() >= 1 && sorters() <= maxSorters, "sorters=" + sorters() + " " + dump());
        assertTrue(simulate(sx, sy, null) > 0, "copper didn't reach the core: " + dump());
    }

    // ---- прямые подходы

    @Test
    void straightTwoSorters(){ //конец в 2 тайлах от грани ядра: инв.+инв.
        queue(bridge(12, 20, 16, 20), bridgeEnd(16, 20));
        assertEquals(2, BridgeEndFinisher.finish(line));
        expectDelivered(11, 20, 2);
    }

    @Test
    void straightOneSorter(){
        queue(bridge(13, 20, 17, 20), bridgeEnd(17, 20));
        assertEquals(1, BridgeEndFinisher.finish(line));
        expectDelivered(12, 20, 1);
    }

    @Test
    void alreadyTouching(){
        queue(bridge(14, 20, 18, 20), bridgeEnd(18, 20));
        assertEquals(0, BridgeEndFinisher.finish(line));
        assertEquals(0, sorters());
    }

    @Test
    void fromAbove(){
        queue(bridge(20, 28, 20, 24), bridgeEnd(20, 24));
        assertEquals(2, BridgeEndFinisher.finish(line));
        expectDelivered(20, 29, 2);
    }

    // ---- углы

    @Test
    void cornerDiagonal(){ //(18,22) - по диагонали от угла ядра
        queue(bridge(14, 22, 18, 22), bridgeEnd(18, 22));
        assertEquals(1, BridgeEndFinisher.finish(line));
        expectDelivered(13, 22, 1);
    }

    @Test
    void oneBeforeCorner(){ //(17,22): за 1 блок до угла
        queue(bridge(13, 22, 17, 22), bridgeEnd(17, 22));
        assertEquals(2, BridgeEndFinisher.finish(line));
        expectDelivered(12, 22, 2);
    }

    @Test
    void oneBeforeCornerVertical(){ //(18,23) сверху от диагонали
        queue(bridge(18, 27, 18, 23), bridgeEnd(18, 23));
        int n = BridgeEndFinisher.finish(line);
        assertTrue(n >= 1 && n <= 2, "n=" + n);
        expectDelivered(18, 28, 2);
    }

    @Test
    void cornerBottomRight(){
        queue(bridge(26, 18, 22, 18), bridgeEnd(22, 18)); //угол (21,19): диагональ (22,18)
        assertEquals(1, BridgeEndFinisher.finish(line));
        expectDelivered(27, 18, 1);
    }

    @Test
    void cornerLeakGuard(){ //сверху от угла стоит конвейер: обычный сортировщик у (19,22) отпал бы - решатель обязан выбрать другой путь без утечки
        world.tile(19, 23).setBlock(Blocks.conveyor, Team.sharded, 1);
        queue(bridge(14, 22, 18, 22), bridgeEnd(18, 22));
        int n = BridgeEndFinisher.finish(line);
        assertTrue(n >= 1, "n=" + n);
        Seq<Tile> watch = Seq.with(world.tile(19, 23));
        System.out.println("PLANS: " + dump());
        assertTrue(simulate(13, 22, watch) > 0, "no delivery: " + dump());
    }

    // ---- мост «на ядре»

    @Test
    void lastBridgeOnCoreSingleClickBuiltPrev(){ //построенный мост в (16,20), клик мостом по ядру (19,20)
        built(16, 20);
        BuildPlan onCore = bridgeEnd(19, 20);
        BridgeEndFinisher.snapshot(Seq.with(onCore));
        //игра план отбросила: очередь пуста
        assertEquals(2, BridgeEndFinisher.finish(new Seq<>()));
        System.out.println("PLANS: " + dump());
        assertEquals(2, sorters());
    }

    @Test
    void lastBridgeOnCoreStaleLink(){ //цепочка b(12)->b(16)->(19,20): последний мост отброшен, у предпоследнего связь на пустоту
        BuildPlan a = bridge(12, 20, 16, 20), b = bridge(16, 20, 19, 20);
        queue(a, b);
        BuildPlan onCore = bridgeEnd(19, 20);
        BridgeEndFinisher.snapshot(Seq.with(a, b, onCore));
        assertEquals(2, BridgeEndFinisher.finish(line));
        assertNull(b.config, "stale link must be removed");
        expectDelivered(11, 20, 2);
    }

    // ---- мост вплотную к мосту у ядра: замена

    @Test
    void twoBridgesTouchingCoreGetReplaced(){
        BuildPlan a = bridge(12, 20, 16, 20), b = bridge(16, 20, 18, 20), c = bridgeEnd(18, 20);
        queue(a, b, c);
        int n = BridgeEndFinisher.finish(line);
        assertTrue(n >= 1, "n=" + n);
        assertNull(b.config, "feeder must lose its link");
        for(BuildPlan p : player.unit().plans) assertFalse(p.x == 18 && p.y == 20 && p.block == Blocks.itemBridge, "last bridge must be replaced: " + dump());
        expectDelivered(11, 20, 2);
    }

    // ---- отрицательные

    @Test
    void tooFar(){
        queue(bridge(10, 20, 14, 20), bridgeEnd(14, 20));
        assertEquals(0, BridgeEndFinisher.finish(line));
        assertEquals(0, sorters());
    }

    @Test
    void lonelyBridgeIgnored(){
        queue(bridgeEnd(17, 20));
        assertEquals(0, BridgeEndFinisher.finish(line));
    }

    @Test
    void occupiedTileBlocks(){ //все свободные соседи конца заняты стенами - ставить сортировщики некуда, план не трогаем
        world.tile(18, 20).setBlock(Blocks.copperWall, Team.sharded);
        world.tile(17, 21).setBlock(Blocks.copperWall, Team.sharded);
        world.tile(17, 19).setBlock(Blocks.copperWall, Team.sharded);
        queue(bridge(13, 20, 17, 20), bridgeEnd(17, 20));
        assertEquals(0, BridgeEndFinisher.finish(line));
        assertEquals(0, sorters());
    }

    @Test
    void routeAroundBlockedTile(){ //прямая клетка занята стеной - решатель находит обход через соседние клетки
        world.tile(18, 20).setBlock(Blocks.copperWall, Team.sharded);
        queue(bridge(13, 20, 17, 20), bridgeEnd(17, 20));
        int n = BridgeEndFinisher.finish(line);
        assertTrue(n >= 1, "n=" + n);
        expectDelivered(12, 20, 2);
    }

    @Test
    void builtUnlinkedFeederReplaced(){ //построенная пара (12)->(16) без выхода + новый мост в (18,20) вплотную к ядру
        built(12, 20);
        built(16, 20);
        world.tile(12, 20).build.configureAny(new Point2(4, 0)); //(12)->(16): у (16) есть вход, но нет выхода
        queue(bridgeEnd(18, 20));
        assertEquals(2, BridgeEndFinisher.finish(line));
        for(BuildPlan p : player.unit().plans) assertNotEquals(Blocks.itemBridge, p.block, "last bridge must be replaced: " + dump());
        System.out.println("PLANS: " + dump());
        assertTrue(simulate(11, 20, null) > 0, "no delivery: " + dump());
    }
}
