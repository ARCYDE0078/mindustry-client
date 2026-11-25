package qol.erekirsand;

import arc.struct.*;
import arc.util.*;
import mindustry.content.*;
import mindustry.core.GameState.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.world.*;
import mindustry.world.blocks.power.*;
import mindustry.world.blocks.production.*;
import org.junit.jupiter.api.*;
import test.ApplicationTests;

import java.util.Random;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Авторасстановка песчаных буров на настоящем движке (headless): карта с песчаными скалами -> {@link DrillSnapshot} ->
 * {@link DrillPlanner} -> setBlock -> логика. Проверяет то, чего не видит ASCII-стенд: каждый блок реально встаёт
 * ({@code Build.validPlace}), дробители выдают песок, утки доносят его до выхода, луч узлов связывает сеть питания,
 * без питания песка нет. На выходе стоит укреплённый контейнер-приёмник - как будто игрок подвёл линию дальше сам.
 */
public class SandEngineTest{
    @BeforeAll
    public static void launch(){
        ApplicationTests.launchApplication(true);
    }

    @BeforeEach
    void setup(){
        Time.setDeltaProvider(() -> 1f);
    }

    @Test void bandExitRight(){ scenario(70, 52, 0, 0); }
    @Test void noiseExitUp(){ scenario(80, 70, 3, 1); }
    @Test void noNetworkAnyExit(){ scenario(80, 70, 4, -1); }

    /** seed 0 - ровная песчаная полоса, остальные - сглаженный шум; seed 4 - без источника питания. */
    void scenario(int w, int h, int seed, int exitDir){
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
                if(y >= h - 12 && y < h - 5) t.setBlock(x < w / 2 ? Blocks.duneWall : Blocks.yellowStoneWall);
            }else{
                float v = noise[x][y];
                if(v > 0.52f) t.setBlock(v > 0.56f ? Blocks.duneWall : v > 0.54f ? Blocks.yellowStoneWall : Blocks.regolithWall);
            }
        }
        world.endMapLoad();
        state.set(State.playing);

        //ядро - только чтобы игра не кончилась; генераторы - настоящие панели + PowerSource-приманка на линии луча
        //(луч обязан пройти сквозь неё, а не «присоединиться»: PowerNode для BeamNode прозрачен)
        world.tile(w - 8, 8).setBlock(Blocks.coreBastion, team);
        if(withPower){
            world.tile(w - 6, h / 2).setBlock(Blocks.largeSolarPanel, team);
            world.tile(w - 6, h / 2 + 3).setBlock(Blocks.largeSolarPanel, team);
            world.tile(w - 12, h / 2).setBlock(Blocks.powerSource, team);
        }

        int zm = 8;
        DrillPlanner.Params params = new DrillPlanner.Params();
        params.drillTime = ((WallCrafter)Blocks.cliffCrusher).drillTime;
        params.nodeRange = (int)((BeamNode)Blocks.beamNode).range;
        params.exitDir = exitDir;
        DrillPlanner.Grid g = DrillSnapshot.take(team, Blocks.duct, null, zm, zm, w - 1 - zm, h - 1 - zm, 24);
        DrillPlanner.Result r = new DrillPlanner(g, params).plan();
        assertNull(r.error, "планировщик: " + r.error);
        if(exitDir >= 0) assertEquals(exitDir, r.exitDir, "выход смотрит куда просили");

        Seq<Building> crushers = new Seq<>(), nodes = new Seq<>();
        for(DrillPlanner.Item it : r.items){
            Block b = blockFor(it.kind);
            assertTrue(Build.validPlace(b, team, it.x, it.y, it.rot), "validPlace " + b.name + " @ " + it.x + "," + it.y + " rot " + it.rot);
            Tile t = world.tile(it.x, it.y);
            t.setBlock(b, team, it.rot);
            if(it.kind == DrillPlanner.CRUSHER) crushers.add(t.build);
            if(it.kind == DrillPlanner.NODE) nodes.add(t.build);
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
            tick();
            if(tick % 25 == 0){
                int got = sink.items.get(Items.sand);
                if(got > 0){
                    sink.items.remove(Items.sand, got);
                    if(tick >= warm) arrived += got;
                }
            }
        }
        float rate = arrived / ((total - warm) / 60f);
        Log.info("[SANDTEST] exit @,@ dir @: sand @/s (plan @), crushers @ nodes @", r.exitX, r.exitY, r.exitDir, rate, r.rate, crushers.size, nodes.size);

        PowerGraph graph = null;
        int unpowered = 0;
        for(Building b : crushers){
            if(graph == null) graph = b.power.graph;
            assertSame(graph, b.power.graph, "дробители в одной сети питания");
            if(b.power.status < 0.99f) unpowered++;
        }
        for(Building b : nodes) assertSame(graph, b.power.graph, "узел в той же сети");

        if(withPower){
            assertTrue(graph.producers.size > 0, "в сети есть источник");
            assertEquals(0, unpowered, "все дробители запитаны");
            assertTrue(rate >= Math.min(r.rate, 15f) * 0.85f, "на выход доходит >=85% плана: " + rate);
        }else{
            assertTrue(rate < 0.01f, "без питания песка нет: " + rate);
        }
    }

    static java.lang.reflect.Method updateEntities;

    /**
     * Один тик мира. Полный {@code logic.update()} в headless-тесте не годится: клиентский {@code Client.update()} внутри него
     * лезет в {@code Vars.control.input} (его в тестовом приложении нет). Нужная нам часть - {@code updateEntities()}
     * (здания, юниты, сети питания) - protected, зовём рефлексией.
     */
    static void tick(){
        try{
            if(updateEntities == null){
                updateEntities = mindustry.core.Logic.class.getDeclaredMethod("updateEntities");
                updateEntities.setAccessible(true);
            }
            updateEntities.invoke(logic);
        }catch(Exception e){
            throw new RuntimeException(e);
        }
    }

    static Block blockFor(int kind){
        return switch(kind){
            case DrillPlanner.CRUSHER -> Blocks.cliffCrusher;
            case DrillPlanner.DUCT -> Blocks.duct;
            default -> Blocks.beamNode;
        };
    }
}
