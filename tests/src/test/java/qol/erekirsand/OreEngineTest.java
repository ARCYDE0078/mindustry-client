package qol.erekirsand;

import arc.struct.*;
import arc.util.*;
import mindustry.content.*;
import mindustry.core.GameState.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.power.*;
import mindustry.world.blocks.production.*;
import org.junit.jupiter.api.*;
import test.ApplicationTests;

import java.util.Random;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Авторасстановка плазменных буров на настоящем движке (headless): карта с настенной рудой -> {@link DrillSnapshot} ->
 * {@link DrillPlanner} (режим beam) -> setBlock -> логика. Проверяет то, чего не видит ASCII-стенд: каждый блок встаёт
 * ({@code Build.validPlace}, в том числе {@code BeamDrill.canPlaceOn}), буры реально добывают руду и она доезжает до
 * выхода, питание есть, а клетки зазора между бурами и рудой остаются пустыми и ПОДХОДЯТ под водородные трубы: источник
 * водорода в первой клетке зазора ускоряет бур в 2.5 раза.
 */
public class OreEngineTest{
    @BeforeAll
    public static void launch(){
        ApplicationTests.launchApplication(true);
    }

    @BeforeEach
    void setup(){
        Time.setDeltaProvider(() -> 1f);
    }

    @Test void bandExitRight(){ scenario(70, 52, 0, 0, 15f, false); }
    @Test void noiseExitUp(){ scenario(80, 70, 2, 1, 15f, false); }
    @Test void noNetworkAnyExit(){ scenario(80, 70, 4, -1, 15f, false); }
    /** Водород в первой клетке зазора каждого бура: добыча растёт ~в 2.5 раза (план на 4.5/с, чтобы упор в 15/с утки не мешал). */
    @Test void hydrogenInGapBoosts(){ scenario(70, 52, 0, 0, 4.5f, true); }

    /** seed 0 - ровная бериллиевая полоса, остальные - сглаженный шум; seed 4 - без источника питания. */
    void scenario(int w, int h, int seed, int exitDir, float target, boolean hydrogen){
        Team team = Team.sharded;
        Random rnd = new Random(seed * 7919L + w);
        boolean band = seed == 0, withPower = seed != 4;

        float[][] noise = new float[w][h];
        for(int i = 0; i < w; i++) for(int j = 0; j < h; j++) noise[i][j] = rnd.nextFloat();
        for(int pass = 0; pass < 4; pass++){
            float[][] nn = new float[w][h];
            for(int i = 0; i < w; i++) for(int j = 0; j < h; j++){
                float sum = 0; int c = 0;
                for(int di = -2; di <= 2; di++) for(int dj = -2; dj <= 2; dj++){
                    int ii = i + di, jj = j + dj;
                    if(ii < 0 || jj < 0 || ii >= w || jj >= h) continue;
                    sum += noise[ii][jj]; c++;
                }
                nn[i][j] = sum / c;
            }
            noise = nn;
        }

        logic.reset();
        state.rules = new Rules();
        //без планеты Эрекира движок считает утки «не для этой среды» (environmentBuildable) и validPlace всюду false
        state.rules.planet = Planets.erekir;
        state.rules.env = Planets.erekir.defaultEnv;
        state.rules.waves = false;
        state.rules.waveTimer = false;
        state.rules.infiniteResources = true;
        state.rules.limitMapArea = false;
        state.rules.fog = false;
        state.rules.solarMultiplier = 40f;

        Tiles tiles = world.resize(w, h);
        world.beginMapLoad();
        tiles.fill();
        for(int x = 0; x < w; x++) for(int y = 0; y < h; y++){
            Tile t = tiles.getn(x, y);
            t.setFloor(Blocks.regolith.asFloor());
            if(x < 4 || y < 4 || x >= w - 4 || y >= h - 4) continue;
            if(band){
                //полоса скалы толщиной 7 у верхнего края: на лице руда с «дырами» (25% голой скалы), как в настоящих картах
                if(y >= h - 12 && y < h - 5){
                    t.setBlock(Blocks.beryllicStoneWall);
                    if(rnd.nextFloat() < 0.75f) t.setOverlay(Blocks.wallOreBeryllium);
                }
            }else{
                float v = noise[x][y];
                if(v > 0.52f){
                    //пятна: часть скалы - графитная (руда у самого блока, не оверлей), часть - бериллиевый оверлей, часть голая
                    if(v > 0.60f) t.setBlock(Blocks.graphiticWall);
                    else{
                        t.setBlock(Blocks.beryllicStoneWall);
                        if(rnd.nextFloat() < 0.8f) t.setOverlay(Blocks.wallOreBeryllium);
                    }
                }
            }
        }
        world.endMapLoad();
        state.set(State.playing);

        world.tile(w - 8, 8).setBlock(Blocks.coreBastion, team);
        if(withPower){
            world.tile(w - 6, h / 2).setBlock(Blocks.largeSolarPanel, team);
            world.tile(w - 6, h / 2 + 3).setBlock(Blocks.largeSolarPanel, team);
            world.tile(w - 12, h / 2).setBlock(Blocks.powerSource, team);
        }

        int zm = 8;
        DrillPlanner.Params params = new DrillPlanner.Params();
        BeamDrill bore = (BeamDrill)Blocks.plasmaBore;
        params.beam = true;
        params.size = bore.size;
        params.range = bore.range;
        params.tier = bore.tier;
        params.drillTime = bore.drillTime;
        params.minGap = 1;
        params.minOreCols = 2;
        params.targetRate = target;
        params.nodeRange = (int)((BeamNode)Blocks.beamNode).range;
        params.exitDir = exitDir;
        DrillPlanner.Grid g = DrillSnapshot.take(team, Blocks.duct, null, zm, zm, w - 1 - zm, h - 1 - zm, 24);
        DrillPlanner.Result r = new DrillPlanner(g, params).plan();
        assertNull(r.error, "планировщик: " + r.error);
        if(exitDir >= 0) assertEquals(exitDir, r.exitDir, "выход смотрит куда просили");
        assertTrue(r.item > 0, "результат знает руду");
        Item ore = content.item(r.item - 1);

        Seq<Building> bores = new Seq<>(), nodes = new Seq<>();
        for(DrillPlanner.Item it : r.items){
            Block b = blockFor(it.kind);
            assertTrue(Build.validPlace(b, team, it.x, it.y, it.rot), "validPlace " + b.name + " @ " + it.x + "," + it.y + " rot " + it.rot);
            Tile t = world.tile(it.x, it.y);
            t.setBlock(b, team, it.rot);
            if(it.kind == DrillPlanner.DRILL) bores.add(t.build);
            if(it.kind == DrillPlanner.NODE) nodes.add(t.build);
        }

        //зазор под трубы: физически пуст и не твёрд
        for(int[] q : r.gaps){
            Tile gt = world.tile(q[0], q[1]);
            assertNull(gt.build, "в зазоре стоит здание " + q[0] + "," + q[1]);
            assertFalse(gt.solid(), "зазор твёрдый " + q[0] + "," + q[1]);
        }

        Seq<Building> pipes = new Seq<>();
        if(hydrogen){
            //«игрок потом проложил трубы»: настоящая реинфорсед-труба в первой клетке зазора напротив первого столбца каждого
            //бура, смотрит В бур; труба НЕ твёрдая, поэтому луч идёт сквозь неё. (Твёрдый блок в зазоре - источник, роутер, мост -
            //закрыл бы столбец луча: LiquidSource/LiquidRouter/ItemBridge solid=true. Поэтому не источник, а подпитка трубы в цикле.)
            int fed = 0;
            for(DrillPlanner.Item it : r.items){
                if(it.kind != DrillPlanner.DRILL) continue;
                int off = (params.size - 1) / 2;
                int[] st = DrillPlanner.frontStart(it.x - off, it.y - off, params.size, it.rot, 0);
                Tile gt = world.tile(st[0], st[1]);
                assertNotNull(gt);
                boolean inGap = false;
                for(int[] q : r.gaps) if(q[0] == gt.x && q[1] == gt.y) inGap = true;
                assertTrue(inGap, "клетка перед буром помечена как зазор");
                gt.setBlock(Blocks.reinforcedConduit, team, (it.rot + 2) % 4);
                assertFalse(gt.solid(), "труба в зазоре не твёрдая");
                pipes.add(gt.build);
                fed++;
            }
            assertTrue(fed > 0, "есть буры");
        }

        //приёмник: укреплённый контейнер 2x2, одна из клеток - первая клетка полосы перед выходной уткой
        Tile lane1 = world.tile(r.exitX + DrillPlanner.DX[r.exitDir], r.exitY + DrillPlanner.DY[r.exitDir]);
        Building sink = null;
        for(int[] o : new int[][]{{0, 0}, {-1, 0}, {0, -1}, {-1, -1}}){
            Tile at = world.tile(lane1.x + o[0], lane1.y + o[1]);
            if(at != null && Build.validPlace(Blocks.reinforcedContainer, team, at.x, at.y, 0)){
                at.setBlock(Blocks.reinforcedContainer, team, 0);
                sink = at.build;
                break;
            }
        }
        assertNotNull(sink, "полоса за выходом свободна: контейнер встал");

        int total = 4200, warm = 1200, arrived = 0;
        for(int tick = 0; tick < total; tick++){
            Time.update();
            for(Building pb : pipes) pb.liquids.set(Liquids.hydrogen, pb.block.liquidCapacity); //виртуальный бак водорода за трубой
            tick();
            if(tick % 25 == 0){
                int got = sink.items.get(ore);
                if(got > 0){
                    sink.items.remove(ore, got);
                    if(tick >= warm) arrived += got;
                }
            }
        }
        float rate = arrived / ((total - warm) / 60f);
        Log.info("[ORETEST] @ exit @,@ dir @: @/s (plan @, hydrogen @), bores @ nodes @", ore.name, r.exitX, r.exitY, r.exitDir, rate, r.rate, hydrogen, bores.size, nodes.size);

        PowerGraph graph = null;
        int unpowered = 0;
        for(Building b : bores){
            if(graph == null) graph = b.power.graph;
            assertSame(graph, b.power.graph, "буры в одной сети питания");
            if(b.power.status < 0.99f) unpowered++;
        }
        for(Building b : nodes) assertSame(graph, b.power.graph, "узел в той же сети");

        if(withPower){
            assertTrue(graph.producers.size > 0, "в сети есть источник");
            assertEquals(0, unpowered, "все буры запитаны");
            if(hydrogen){
                //x2.5 минус неидеальности прогрева/упора: >= 2x плана - уже ясно, что водород дошёл через зазор
                assertTrue(rate >= r.rate * 2f, "с водородом >= 2x плана: " + rate + " vs " + r.rate);
            }else{
                assertTrue(rate >= Math.min(r.rate, 15f) * 0.85f, "на выход доходит >=85% плана: " + rate + " vs " + r.rate);
            }
        }else{
            assertTrue(rate < 0.01f, "без питания руды нет: " + rate);
        }
    }

    static Block blockFor(int kind){
        return switch(kind){
            case DrillPlanner.DRILL -> Blocks.plasmaBore;
            case DrillPlanner.DUCT -> Blocks.duct;
            default -> Blocks.beamNode;
        };
    }

    static void tick(){
        SandEngineTest.tick();
    }
}
