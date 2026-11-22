package qol.crawlercontrol;

import arc.struct.*;
import arc.util.*;
import mindustry.ai.*;
import mindustry.ai.types.*;
import mindustry.content.*;
import mindustry.core.GameState.*;
import mindustry.entities.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.world.*;
import org.junit.jupiter.api.*;
import test.ApplicationTests;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Групповой взрыв ползунов на настоящем движке (headless): (1) оценка урона {@link CrawlerControlFeature#estimate}
 * совпадает с тем, что реально наносит {@code Damage.damage} от взрыва в той же точке; (2) решения группы -
 * ждать/рвать/защёлка/тайм-аут - на сценариях с живыми CommandAI-юнитами.
 */
public class CrawlerGroupTest{
    static final int T = 20; //центр цели (тайл)
    CrawlerControlFeature feature;

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
        feature = new CrawlerControlFeature();
    }

    Building place(Block block, int x, int y, Team team){
        world.tile(x, y).setBlock(block, team, 0);
        return world.tile(x, y).build;
    }

    Unit crawler(float wx, float wy){
        Unit u = UnitTypes.crawler.create(Team.sharded);
        u.set(wx, wy);
        CommandAI ai = new CommandAI();
        u.controller(ai);
        return u;
    }

    /** Настоящий взрыв ползуна в точке: тот же вызов, что делает пуля (splash * множитель по зданию), урон по прочности цели. */
    float actual(Unit u, Building target){
        float before = target.health;
        var b = UnitTypes.crawler.weapons.first().bullet;
        Damage.damage(Team.sharded, u.x, u.y, b.splashDamageRadius, b.splashDamage * b.buildingDamageMultiplier, false, true, true, false, null);
        Time.update(); //Damage.tileDamage откладывает работу на Time.run(0)
        float dealt = before - target.health;
        target.health = before;
        return dealt;
    }

    @Test
    void estimateMatchesEngineOnLargeTarget(){
        Building wall = place(Blocks.thoriumWallLarge, T, T, Team.crux); //2x2, тайлы 20..21
        wall.health = wall.maxHealth;
        int checked = 0;
        for(float dx = -60; dx <= 60; dx += 7){
            for(float dy = -60; dy <= 60; dy += 7){
                //центр блока 2x2 - в углу между тайлами: wall.x/y
                float wx = wall.x + dx, wy = wall.y + dy;
                if(world.buildWorld(wx, wy) != null) continue; //внутри самого блока - другая ветка тайлДамажа
                Unit u = crawler(wx, wy);
                float est = feature.estimate(u, wall), act = actual(u, wall);
                assertEquals(act, est, 1e-2f + 1e-4f * act, "позиция dx=" + dx + " dy=" + dy);
                if(act > 0) checked++;
            }
        }
        assertTrue(checked > 20, "слишком мало точек, где взрыв вообще задевает блок: " + checked);
    }

    @Test
    void estimateMatchesEngineWithBlockerInFront(){
        Building target = place(Blocks.thoriumWallLarge, T, T, Team.crux);
        float[] dys = {-30, -24, -18, -12, -6, 0, 6, 12, 18, 24, 30};
        float[] before = new float[dys.length];
        for(int i = 0; i < dys.length; i++){
            Unit u = crawler(target.x - 4 * tilesize, target.y + dys[i]);
            before[i] = actual(u, target);
            assertEquals(before[i], feature.estimate(u, target), 1e-2f + 1e-4f * before[i], "без заслона, dy=" + dys[i]);
        }

        //короткий заслон ровно перед целью: лучи по центру он гасит, лучи сбоку обходят его концы
        for(int y = T; y <= T + 1; y++) place(Blocks.copperWall, T - 2, y, Team.crux);
        int reduced = 0, stillHits = 0;
        for(int i = 0; i < dys.length; i++){
            Unit u = crawler(target.x - 4 * tilesize, target.y + dys[i]);
            float est = feature.estimate(u, target), act = actual(u, target);
            assertEquals(act, est, 1e-2f + 1e-4f * act, "с заслоном, dy=" + dys[i]);
            if(act < before[i] - 1e-3f) reduced++;
            if(act > 0) stillHits++;
        }
        assertTrue(reduced > 0, "заслон должен хоть где-то ослаблять урон - иначе тест ничего не проверяет");
        assertTrue(stillHits > 0, "и хоть где-то урон проходит в обход - иначе сравниваем 0 с 0");
    }

    @Test
    void estimateIsZeroOutOfRange(){
        Building wall = place(Blocks.thoriumWallLarge, T, T, Team.crux);
        Unit u = crawler(wall.x + 12 * tilesize, wall.y);
        assertEquals(0f, feature.estimate(u, wall), 1e-6f);
    }

    // ------------------------------------------------------------ решения группы

    /** Группа из n ползунов у цели: первые arrived стоят вплотную, остальные далеко. */
    Seq<Unit> group(Building target, int arrived, int far){
        Seq<Unit> list = new Seq<>();
        for(int i = 0; i < arrived; i++){
            Unit u = crawler(target.x - 20f, target.y + (i - arrived / 2f) * 3f);
            ((CommandAI)u.controller()).attackTarget = target;
            list.add(u);
        }
        for(int i = 0; i < far; i++){
            Unit u = crawler(target.x - 300f, target.y + i * 3f);
            ((CommandAI)u.controller()).attackTarget = target;
            list.add(u);
        }
        return list;
    }

    void run(Building target, Seq<Unit> units){
        feature.toHold.clear();
        feature.toRelease.clear();
        feature.processGroup(target, units);
    }

    void holdAll(Seq<Unit> units){
        for(Unit u : units) ((CommandAI)u.controller()).setStance(UnitStance.holdFire, true);
    }

    @Test
    void oneArrivedAmongManyWaits(){
        Building wall = place(Blocks.thoriumWallLarge, T, T, Team.crux);
        Seq<Unit> units = group(wall, 1, 4);
        run(wall, units);
        //никто ещё не отпущен: все 5 надо поставить на hold
        assertEquals(5, feature.toHold.size);
        assertEquals(0, feature.toRelease.size);
    }

    @Test
    void enoughArrivedReleasesOnlyArrived(){
        Building wall = place(Blocks.thoriumWallLarge, T, T, Team.crux);
        wall.health = 100f; //хватит и одного
        Seq<Unit> units = group(wall, 3, 2);
        holdAll(units);
        run(wall, units);
        assertEquals(3, feature.toRelease.size, "подошедшие отпущены");
        assertEquals(0, feature.toHold.size, "уже держат; далёкие остаются на hold");
        for(int i = 0; i < 3; i++) assertTrue(feature.toRelease.contains(units.get(i).id), "отпущен подошедший #" + i);
        for(int i = 3; i < 5; i++) assertFalse(feature.toRelease.contains(units.get(i).id), "далёкий #" + i + " не отпущен");
    }

    @Test
    void notEnoughButAllArrivedReleases(){
        Building wall = place(Blocks.thoriumWallLarge, T, T, Team.crux);
        Seq<Unit> units = group(wall, 2, 0);
        holdAll(units);
        run(wall, units);
        assertEquals(2, feature.toRelease.size, "больше ждать некого - рвёмся тем, что есть");
    }

    @Test
    void notEnoughWithStragglersKeepsHolding(){
        Building wall = place(Blocks.thoriumWallLarge, T, T, Team.crux); //прочность в тысячи - двух ползунов мало
        Seq<Unit> units = group(wall, 2, 3);
        holdAll(units);
        run(wall, units);
        assertEquals(0, feature.toRelease.size);
        assertEquals(0, feature.toHold.size);
    }

    @Test
    void latchSurvivesFirstExplosions(){
        Building wall = place(Blocks.thoriumWallLarge, T, T, Team.crux);
        wall.health = 100f;
        Seq<Unit> units = group(wall, 3, 1);
        holdAll(units);
        run(wall, units);
        assertEquals(3, feature.toRelease.size);

        //двое взорвались, третий уже не держит; цели осталось много (оценка одного ползуна её не добьёт) - защёлка не даёт снова встать на hold
        wall.health = 5000f;
        Seq<Unit> rest = new Seq<>();
        rest.add(units.get(2), units.get(3));
        ((CommandAI)units.get(2).controller()).setStance(UnitStance.holdFire, false);
        holdAll(Seq.with(units.get(3)));
        run(wall, rest);
        assertEquals(0, feature.toHold.size, "подошедший не должен вернуться на hold");
        assertEquals(0, feature.toRelease.size, "далёкий всё ещё едет и держит");
    }

    @Test
    void stalledGroupReleasesAfterTimeout(){
        Building wall = place(Blocks.thoriumWallLarge, T, T, Team.crux);
        Seq<Unit> units = group(wall, 2, 2);
        holdAll(units);
        run(wall, units);
        assertEquals(0, feature.toRelease.size);

        Time.time += CrawlerControlFeature.STALL_TICKS + 1f;
        run(wall, units);
        assertEquals(2, feature.toRelease.size, "число подошедших давно не растёт - рвёмся тем, что на месте");
    }

    @Test
    void singleCrawlerArrivedReleasesLikeBefore(){
        Building wall = place(Blocks.thoriumWallLarge, T, T, Team.crux);
        Seq<Unit> units = group(wall, 1, 0);
        holdAll(units);
        run(wall, units);
        assertEquals(1, feature.toRelease.size);
    }
}
