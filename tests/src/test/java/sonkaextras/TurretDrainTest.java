package sonkaextras;

import arc.*;
import arc.math.*;
import arc.math.geom.*;
import arc.struct.*;
import arc.util.*;
import mindustry.ai.*;
import mindustry.ai.types.*;
import mindustry.content.*;
import mindustry.core.*;
import mindustry.core.GameState.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.defense.turrets.BaseTurret.*;
import mindustry.world.blocks.defense.turrets.ItemTurret.*;
import org.junit.jupiter.api.*;
import qol.core.*;
import test.ApplicationTests;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Режим «разряжать турели» на настоящем движке (headless): зоны опасности, раздельно воздух/земля, геометрия P/Q,
 * реальная отправка ванильных приказов (CommandAI получает цель, очередь и stance patrol), перепланирование при
 * разрядке/новых турелях, радиус циклона 29, лечение. Выделение (control.input) в headless нет - отряд собираем напрямую.
 */
public class TurretDrainTest{
    long clock;

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
        state.rules.fog = false;
        state.rules.unitCapVariable = false;
        state.rules.unitCap = 1000; //без ядра лимит юнитов = 0, и add() сразу убивает юнита (unitCapDeath)
        Tiles tiles = world.resize(120, 120);
        world.beginMapLoad();
        tiles.fill();
        world.endMapLoad();

        Player p = Player.create();
        p.team(Team.sharded);
        Unit u = UnitTypes.alpha.create(Team.sharded);
        p.unit(u);
        player = p;

        Core.settings.put(TurretDrain.repairKey, true);
        Core.settings.put(TurretDrain.cyclone29Key, false);
        TurretDrain.resetForTest();
        clock = 1_000_000L;
    }

    /** Вражеская турель с зарядом; ammo = null - пустая. */
    Building turret(Block block, int x, int y, Item ammo){
        world.tile(x, y).setBlock(block, Team.crux, 0);
        Building b = world.tile(x, y).build;
        if(ammo != null && b instanceof ItemTurretBuild t){
            //в живой игре эффективность обновляет тик здания; здесь тиков нет - выставляем как у питаемой турели
            t.potentialEfficiency = 1f;
            t.efficiency = 1f;
            t.handleItem(t, ammo);
            assertTrue(t.hasAmmo(), "заряд принят: " + block.name);
        }
        return b;
    }

    Unit unit(UnitType type, float tx, float ty){
        Unit u = type.create(Team.sharded);
        u.set(tx * tilesize, ty * tilesize);
        u.controller(new CommandAI());
        u.add();
        return u;
    }

    /** Один проход планировщика + досрочная отработка отложенных отправок. */
    void step(){
        clock += 5000; //дольше REPLAN_MIN_MS и RESEND_GRACE_MS
        TurretDrain.step(clock);
        Time.update();
        Time.update();
    }

    TurretDrain.Member join(Unit u){
        return TurretDrain.join(u);
    }

    float distTiles(float x1, float y1, float x2, float y2){
        return Mathf.dst(x1, y1, x2, y2) / tilesize;
    }

    // ------------------------------------------------------------------ зоны

    @Test
    void zonesSplitByAirAndGround(){
        turret(Blocks.hail, 60, 60, Items.graphite);     //только земля
        turret(Blocks.scatter, 70, 60, Items.scrap);     //только воздух
        turret(Blocks.duo, 80, 60, Items.copper);        //и то и другое
        join(unit(UnitTypes.dagger, 10, 60));
        TurretDrain.buildZones(false);
        assertEquals(2, TurretDrain.zonesOf(0).size, "воздух: скаттер + дуо");
        assertEquals(2, TurretDrain.zonesOf(1).size, "земля: хейл + дуо");
        for(var z : TurretDrain.zonesOf(0)) assertNotEquals(world.build(60, 60).pos(), z.pos);
        for(var z : TurretDrain.zonesOf(1)) assertNotEquals(world.build(70, 60).pos(), z.pos);
    }

    @Test
    void emptyTurretIsNotAZone(){
        turret(Blocks.duo, 60, 60, null);
        TurretDrain.buildZones(false);
        assertEquals(0, TurretDrain.zonesOf(0).size);
        assertEquals(0, TurretDrain.zonesOf(1).size);
    }

    @Test
    void ownTurretsIgnored(){
        world.tile(60, 60).setBlock(Blocks.duo, Team.sharded, 0);
        TurretDrain.buildZones(false);
        assertEquals(0, TurretDrain.zonesOf(1).size);
    }

    @Test
    void cyclone29EnlargesOnlyCyclone(){
        turret(Blocks.cyclone, 60, 60, Items.metaglass);
        turret(Blocks.duo, 80, 60, Items.copper);
        TurretDrain.buildZones(false);
        float stock = 0, duo = 0;
        for(var z : TurretDrain.zonesOf(1)){
            if(z.pos == world.build(60, 60).pos()) stock = z.r; else duo = z.r;
        }
        assertEquals(((BaseTurretBuild)world.build(60, 60)).range(), stock, 0.01f, "без настройки - настоящий радиус");
        TurretDrain.buildZones(true);
        float big = 0, duo2 = 0;
        for(var z : TurretDrain.zonesOf(1)){
            if(z.pos == world.build(60, 60).pos()) big = z.r; else duo2 = z.r;
        }
        assertEquals(Math.max(stock, 29f * tilesize), big, 0.01f);
        assertEquals(duo, duo2, 0.01f, "остальные турели не затронуты настройкой");
    }

    // ------------------------------------------------------------------ планирование и отправка

    @Test
    void groundUnitGetsPatrolPlanAndOrdersAreSent(){
        Building t = turret(Blocks.duo, 60, 60, Items.copper);
        Unit u = unit(UnitTypes.dagger, 20, 60);
        TurretDrain.Member m = join(u);
        step();

        assertEquals(1, m.mode, "патруль");
        assertEquals(t.pos(), m.turret);
        float r = ((BaseTurretBuild)t).range() / tilesize;
        float pd = distTiles(m.px, m.py, t.x, t.y), qd = distTiles(m.qx, m.qy, t.x, t.y);
        assertTrue(pd < r + u.hitSize / 2f / tilesize, "вход внутри зоны: " + pd + " < " + r);
        assertTrue(pd > r - 8f, "заход неглубокий: " + pd);
        assertTrue(qd > r + 1f, "выход за радиусом: " + qd + " > " + r);

        CommandAI ai = (CommandAI)u.controller();
        assertNotNull(ai.targetPos, "цель движения выставлена");
        assertEquals(m.px, ai.targetPos.x, 0.5f);
        assertEquals(m.py, ai.targetPos.y, 0.5f);
        assertEquals(1, ai.commandQueue.size, "точка выхода в очереди");
        assertTrue(ai.hasStance(UnitStance.patrol), "включён патруль");
        assertFalse(ManualControl.markedSince(u.id, 0L), "собственные приказы режима не считаются ручными");
    }

    @Test
    void airUnitIgnoresGroundOnlyTurret(){
        turret(Blocks.hail, 60, 60, Items.graphite);
        Unit u = unit(UnitTypes.flare, 20, 60);
        TurretDrain.Member m = join(u);
        step();
        assertEquals(0, m.mode, "хейл не бьёт по воздуху - разряжать воздушных им незачем");
        assertNull(((CommandAI)u.controller()).targetPos);
    }

    @Test
    void groundUnitIgnoresAirOnlyTurret(){
        turret(Blocks.scatter, 60, 60, Items.scrap);
        Unit u = unit(UnitTypes.dagger, 20, 60);
        TurretDrain.Member m = join(u);
        step();
        assertEquals(0, m.mode);
    }

    @Test
    void airAndGroundPlanIndependently(){
        Building hail = turret(Blocks.hail, 60, 60, Items.graphite);       //земля
        Building scatter = turret(Blocks.scatter, 60, 80, Items.scrap);    //воздух
        TurretDrain.Member g = join(unit(UnitTypes.dagger, 20, 70));
        TurretDrain.Member a = join(unit(UnitTypes.flare, 20, 70));
        step();
        assertEquals(hail.pos(), g.turret);
        assertEquals(scatter.pos(), a.turret);
    }

    @Test
    void unitsSpreadOverTurrets(){
        Building t1 = turret(Blocks.duo, 60, 50, Items.copper);
        Building t2 = turret(Blocks.duo, 60, 70, Items.copper);
        TurretDrain.Member a = join(unit(UnitTypes.dagger, 20, 60));
        TurretDrain.Member b = join(unit(UnitTypes.dagger, 20, 61));
        step();
        assertNotEquals(a.turret, b.turret, "два юнита - две разные турели");
        assertTrue((a.turret == t1.pos() && b.turret == t2.pos()) || (a.turret == t2.pos() && b.turret == t1.pos()));
    }

    @Test
    void manyUnitsOnOneTurretFanOut(){
        Building t = turret(Blocks.duo, 60, 60, Items.copper);
        Seq<TurretDrain.Member> ms = new Seq<>();
        for(int i = 0; i < 4; i++) ms.add(join(unit(UnitTypes.flare, 20, 60 + i)));
        step();
        for(var m : ms) assertEquals(t.pos(), m.turret);
        for(int i = 0; i < ms.size; i++) for(int j = i + 1; j < ms.size; j++){
            assertTrue(Mathf.dst(ms.get(i).px, ms.get(i).py, ms.get(j).px, ms.get(j).py) > 2f * tilesize,
                "точки входа разведены веером: " + i + " и " + j);
        }
    }

    @Test
    void exitPointIsOutsideEveryZone(){
        turret(Blocks.duo, 60, 60, Items.copper);
        turret(Blocks.duo, 64, 60, Items.copper); //стоят рядом: зоны сильно перекрываются
        TurretDrain.Member m = join(unit(UnitTypes.dagger, 20, 60));
        step();
        assertEquals(1, m.mode);
        for(var z : TurretDrain.zonesOf(1)){
            assertTrue(Mathf.dst(m.qx, m.qy, z.x, z.y) > z.r, "выход вне зоны турели " + z.pos);
        }
    }

    @Test
    void replanWhenTurretDrains(){
        Building t = turret(Blocks.duo, 60, 60, Items.copper);
        Unit u = unit(UnitTypes.dagger, 20, 60);
        TurretDrain.Member m = join(u);
        step();
        assertEquals(1, m.mode);

        ((ItemTurretBuild)t).ammo.clear();
        ((ItemTurretBuild)t).totalAmmo = 0;
        step();
        assertEquals(2, m.mode, "разряжено: уходим к точке выхода, а не курсируем по устаревшему маршруту");
        CommandAI ai = (CommandAI)u.controller();
        assertFalse(ai.hasStance(UnitStance.patrol), "патруль снят");
        assertEquals(m.qx, ai.targetPos.x, 0.5f);
        assertEquals(m.qy, ai.targetPos.y, 0.5f);
    }

    @Test
    void replanWhenEnemyBuildsNewTurret(){
        Building far = turret(Blocks.duo, 100, 60, Items.copper);
        Unit u = unit(UnitTypes.dagger, 20, 60);
        TurretDrain.Member m = join(u);
        step();
        assertEquals(far.pos(), m.turret);

        Building near = turret(Blocks.duo, 50, 60, Items.copper);
        step();
        assertEquals(near.pos(), m.turret, "ближайшая новая турель взята в работу");
    }

    @Test
    void noResendWhenNothingChanged(){
        turret(Blocks.duo, 60, 60, Items.copper);
        Unit u = unit(UnitTypes.dagger, 20, 60);
        TurretDrain.Member m = join(u);
        step();
        long sent = m.sentAt;
        step();
        step();
        assertEquals(sent, m.sentAt, "обстановка та же - приказы не пересылаются");
    }

    @Test
    void lostOrdersAreResent(){
        turret(Blocks.duo, 60, 60, Items.copper);
        Unit u = unit(UnitTypes.dagger, 20, 60);
        TurretDrain.Member m = join(u);
        step();
        long sent = m.sentAt;
        CommandAI ai = (CommandAI)u.controller();
        ai.clearCommands(); //приказ потерялся
        ai.setStance(UnitStance.patrol, false);
        step();
        assertTrue(m.sentAt > sent, "потеря обнаружена, план отправлен заново");
        assertTrue(ai.hasStance(UnitStance.patrol));
    }

    @Test
    void cyclone29DeepensNothingWhenOff(){
        Building c = turret(Blocks.cyclone, 70, 60, Items.metaglass);
        TurretDrain.Member m = join(unit(UnitTypes.flare, 20, 60));
        step();
        float r = ((BaseTurretBuild)c).range() / tilesize;
        assertTrue(distTiles(m.qx, m.qy, c.x, c.y) > r, "выход за настоящим радиусом циклона");
        assertTrue(distTiles(m.qx, m.qy, c.x, c.y) < 29f + 0.01f + 4f, "при выключенной настройке не отодвигаем до 29 блоков");
    }

    @Test
    void cyclone29PushesExitBeyond29Blocks(){
        Core.settings.put(TurretDrain.cyclone29Key, true);
        Building c = turret(Blocks.cyclone, 70, 60, Items.metaglass);
        TurretDrain.Member m = join(unit(UnitTypes.flare, 20, 60));
        step();
        assertEquals(1, m.mode);
        assertTrue(distTiles(m.qx, m.qy, c.x, c.y) > 29f, "выход дальше 29 блоков: " + distTiles(m.qx, m.qy, c.x, c.y));
        assertTrue(distTiles(m.px, m.py, c.x, c.y) < 29f + 1f, "вход - у границы 29 блоков: " + distTiles(m.px, m.py, c.x, c.y));
    }

    @Test
    void groundPointsAvoidWalls(){
        Building t = turret(Blocks.duo, 60, 60, Items.copper);
        TurretDrain.Member free = join(unit(UnitTypes.dagger, 20, 60));
        step();
        assertEquals(1, free.mode);
        float p0x = free.px, p0y = free.py, q0x = free.qx, q0y = free.qy;

        //забиваем стенами и вход, и выход прямого направления: план обязан уйти в сторону, но остаться проходимым
        TurretDrain.resetForTest();
        for(Point2 c : new Point2[]{new Point2(World.toTile(p0x), World.toTile(p0y)), new Point2(World.toTile(q0x), World.toTile(q0y))}){
            for(int dx = -3; dx <= 3; dx++) for(int dy = -3; dy <= 3; dy++) world.tile(c.x + dx, c.y + dy).setBlock(Blocks.copperWall, Team.derelict, 0);
        }
        Unit u = unit(UnitTypes.dagger, 20, 61);
        TurretDrain.Member m = join(u);
        step();
        assertEquals(1, m.mode, "обходной маршрут найден");
        assertEquals(t.pos(), m.turret);
        assertTrue(u.canPass(World.toTile(m.px), World.toTile(m.py)), "вход проходим");
        assertTrue(u.canPass(World.toTile(m.qx), World.toTile(m.qy)), "выход проходим");
        assertTrue(Mathf.dst(m.px, m.py, p0x, p0y) > 3f * tilesize, "вход сместился с заваленного места");
    }

    @Test
    void groundUnitWithNoWalkableApproachIsLeftAlone(){
        turret(Blocks.duo, 60, 60, Items.copper);
        //кольцо стен вокруг турели шире её радиуса: ни входа, ни выхода пешком
        for(int x = 30; x <= 90; x++) for(int y = 30; y <= 90; y++){
            float d = Mathf.dst(x, y, 60, 60);
            if(d > 12f && d < 20f) world.tile(x, y).setBlock(Blocks.copperWall, Team.derelict, 0);
        }
        TurretDrain.Member m = join(unit(UnitTypes.dagger, 20, 60));
        step();
        assertEquals(0, m.mode, "подойти нельзя - юнита не гоняем впустую");
    }

    // ------------------------------------------------------------------ живой патруль

    /** Прогон настоящего CommandAI: после ОДНОЙ отправки юнит сам курсирует P-Q (заходит в зону и выходит), новых пакетов нет. */
    @Test
    void airUnitPatrolsInAndOutOfZoneWithoutFurtherOrders(){
        Building t = turret(Blocks.duo, 60, 60, Items.copper);
        Unit u = unit(UnitTypes.flare, 20, 60);
        TurretDrain.Member m = join(u);
        step();
        assertEquals(1, m.mode);
        long sent = m.sentAt;
        float r = ((BaseTurretBuild)t).range();

        int crossings = 0;
        boolean inside = false;
        float minD = Float.MAX_VALUE, maxD = 0f;
        for(int tick = 0; tick < 3000; tick++){
            Time.update();
            u.update();
            float d = Mathf.dst(u.x, u.y, t.x, t.y);
            minD = Math.min(minD, d);
            if(tick > 60) maxD = Math.max(maxD, d);
            boolean now = d < r;
            if(tick > 0 && now != inside) crossings++;
            inside = now;
        }
        assertEquals(sent, m.sentAt, "клиент ничего не переотправлял");
        assertTrue(minD < r, "юнит заходил в зону: min=" + minD + " r=" + r);
        assertTrue(maxD > r, "и выходил из неё: max=" + maxD + " r=" + r);
        assertTrue(crossings >= 6, "курсирует циклически (3+ захода): пересечений границы " + crossings);
        assertTrue(((CommandAI)u.controller()).hasStance(UnitStance.patrol));
    }

    @Test
    void groundUnitPatrolsInAndOutOfZoneWithoutFurtherOrders(){
        Building t = turret(Blocks.duo, 60, 60, Items.copper);
        Unit u = unit(UnitTypes.dagger, 20, 60);
        TurretDrain.Member m = join(u);
        step();
        assertEquals(1, m.mode);
        long sent = m.sentAt;
        float r = ((BaseTurretBuild)t).range();

        int crossings = 0;
        boolean inside = false;
        float minD = Float.MAX_VALUE, maxD = 0f;
        for(int tick = 0; tick < 3000; tick++){
            Time.update();
            u.update();
            float d = Mathf.dst(u.x, u.y, t.x, t.y);
            minD = Math.min(minD, d);
            if(tick > 60) maxD = Math.max(maxD, d);
            boolean now = d < r;
            if(tick > 0 && now != inside) crossings++;
            inside = now;
        }
        assertEquals(sent, m.sentAt, "клиент ничего не переотправлял");
        assertTrue(minD < r, "юнит заходил в зону: min=" + minD + " r=" + r);
        assertTrue(maxD > r, "и выходил из неё: max=" + maxD + " r=" + r);
        assertTrue(crossings >= 6, "курсирует циклически (3+ захода): пересечений границы " + crossings);
        assertTrue(((CommandAI)u.controller()).hasStance(UnitStance.patrol));
    }

    // ------------------------------------------------------------------ лечение

    @Test
    void damagedUnitGoesToRepairThenReturns(){
        Building t = turret(Blocks.duo, 90, 60, Items.copper);
        world.tile(30, 60).setBlock(Blocks.repairPoint, Team.sharded, 0);
        Building rp = world.tile(30, 60).build;
        Unit u = unit(UnitTypes.dagger, 20, 60);
        TurretDrain.Member m = join(u);
        step();
        assertEquals(1, m.mode);

        u.health = u.maxHealth * 0.3f;
        step();
        assertTrue(m.repairing, "ранен - летим лечиться");
        assertEquals(rp.pos(), m.repairPos);
        assertEquals(2, m.mode);
        assertTrue(Mathf.dst(m.px, m.py, rp.x, rp.y) < 6f * tilesize, "точка лечения у ремонтного пункта");
        assertFalse(((CommandAI)u.controller()).hasStance(UnitStance.patrol), "на время лечения патруль снят");

        u.health = u.maxHealth;
        step();
        assertFalse(m.repairing, "вылечен");
        step();
        assertEquals(1, m.mode, "и снова в строю");
        assertEquals(t.pos(), m.turret);
    }

    @Test
    void repairDisabledBySetting(){
        Core.settings.put(TurretDrain.repairKey, false);
        turret(Blocks.duo, 90, 60, Items.copper);
        world.tile(30, 60).setBlock(Blocks.repairPoint, Team.sharded, 0);
        Unit u = unit(UnitTypes.dagger, 20, 60);
        TurretDrain.Member m = join(u);
        u.health = u.maxHealth * 0.1f;
        step();
        assertFalse(m.repairing);
        assertEquals(1, m.mode);
    }

    @Test
    void noRepairBuildingMeansKeepFighting(){
        turret(Blocks.duo, 90, 60, Items.copper);
        Unit u = unit(UnitTypes.dagger, 20, 60);
        TurretDrain.Member m = join(u);
        u.health = u.maxHealth * 0.1f;
        step();
        assertFalse(m.repairing);
        assertEquals(1, m.mode);
    }
}
