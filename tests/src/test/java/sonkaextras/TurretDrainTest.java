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
    void groundUnitGetsCyclePlanAndPlainMoveOrder(){
        Building t = turret(Blocks.duo, 60, 60, Items.copper);
        Unit u = unit(UnitTypes.dagger, 20, 60);
        TurretDrain.Member m = join(u);
        step();

        assertEquals(1, m.mode, "цикл захода/выхода");
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
        assertEquals(0, ai.commandQueue.size, "обычный приказ движения, без очереди");
        assertFalse(ai.hasStance(UnitStance.patrol), "ванильный патруль не используется");
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
        assertEquals(2, m.mode, "разряжено: уходим к точке выхода, а не заходим по устаревшему маршруту");
        CommandAI ai = (CommandAI)u.controller();
        assertFalse(ai.hasStance(UnitStance.patrol));
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
        step();
        assertTrue(m.sentAt > sent, "потеря обнаружена, приказ отправлен заново");
        assertNotNull(ai.targetPos);
        assertEquals(m.px, ai.targetPos.x, 0.5f);
        assertEquals(m.py, ai.targetPos.y, 0.5f);
    }

    @Test
    void patrolStanceOfAUnitIsSwitchedOff(){
        turret(Blocks.duo, 60, 60, Items.copper);
        Unit u = unit(UnitTypes.dagger, 20, 60);
        CommandAI ai = (CommandAI)u.controller();
        ai.setStance(UnitStance.patrol, true); //включён игрой/игроком: иначе CommandAI сам зациклил бы пройденные точки
        join(u);
        step();
        assertFalse(ai.hasStance(UnitStance.patrol));
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

    // ------------------------------------------------------------------ живой цикл

    /** Результат прогона цикла юнита: входы в зону и выходы из неё, в тиках. */
    static final class Run{
        final Seq<Integer> entries = new Seq<>(), exits = new Seq<>();
        float minD = Float.MAX_VALUE, maxD = 0f;
        int maxQueue = 0;
        boolean patrolSeen = false;
    }

    /** Прогон настоящего CommandAI с покадровым tick() режима: клиент ведёт цикл обычными приказами. */
    Run simulate(Unit u, Building t, int ticks){
        float r = ((BaseTurretBuild)t).range();
        CommandAI ai = (CommandAI)u.controller();
        Run run = new Run();
        boolean inside = false;
        for(int tick = 0; tick < ticks; tick++){
            Time.update();
            clock += 17;
            TurretDrain.tick(clock);
            u.update();
            float d = Mathf.dst(u.x, u.y, t.x, t.y);
            run.minD = Math.min(run.minD, d);
            if(tick > 60) run.maxD = Math.max(run.maxD, d);
            boolean now = d < r;
            if(tick > 0 && now && !inside) run.entries.add(tick);
            if(tick > 0 && !now && inside) run.exits.add(tick);
            inside = now;
            run.maxQueue = Math.max(run.maxQueue, ai.commandQueue.size);
            run.patrolSeen |= ai.hasStance(UnitStance.patrol);
        }
        return run;
    }

    void assertCycles(Run run, Building t){
        float r = ((BaseTurretBuild)t).range();
        assertTrue(run.minD < r, "юнит заходил в зону: min=" + run.minD + " r=" + r);
        assertTrue(run.maxD > r, "и выходил из неё: max=" + run.maxD + " r=" + r);
        assertTrue(run.entries.size >= 3, "циклически (3+ захода): заходов " + run.entries.size);
        assertEquals(0, run.maxQueue, "очередь приказов не используется");
        assertFalse(run.patrolSeen, "ванильный патруль не включался");
    }

    @Test
    void airUnitCyclesInAndOutOfZoneWithPlainMoves(){
        Building t = turret(Blocks.duo, 60, 60, Items.copper);
        Unit u = unit(UnitTypes.flare, 20, 60);
        join(u);
        step();
        assertCycles(simulate(u, t, 3000), t);
    }

    @Test
    void groundUnitCyclesInAndOutOfZoneWithPlainMoves(){
        Building t = turret(Blocks.duo, 60, 60, Items.copper);
        Unit u = unit(UnitTypes.dagger, 20, 60);
        join(u);
        step();
        assertCycles(simulate(u, t, 4000), t);
    }

    // ------------------------------------------------------------------ пауза после отхода

    @Test
    void airUnitWaitsOutsideForBulletFlight(){
        Building t = turret(Blocks.scatter, 60, 60, Items.scrap);
        Unit u = unit(UnitTypes.flare, 20, 60);
        TurretDrain.Member m = join(u);
        step();
        float r = ((BaseTurretBuild)t).range();
        var bt = ((ItemTurretBuild)t).peekAmmo();
        float flight = Math.min(r / bt.speed, bt.scaleLife ? Float.MAX_VALUE : bt.lifetime);
        assertEquals(flight, m.flight, 0.5f, "время полёта снаряда взято у турели");

        //без паузы юнит вернулся бы, едва выйдя (путь до Q и обратно ~ 2*24 px) - это короче полёта, иначе тест ничего не доказывает
        float plainOutside = 2f * 24f / u.type.speed;
        assertTrue(plainOutside < flight, "без паузы вне зоны быстрее полёта снаряда: " + plainOutside + " < " + flight);

        Run run = simulate(u, t, 4000);
        assertCycles(run, t);
        int checked = 0;
        for(int i = 1; i < run.entries.size; i++){
            //выход, после которого был этот заход
            int exit = -1;
            for(int ex : run.exits) if(ex < run.entries.get(i) && ex > run.entries.get(i - 1)) exit = ex;
            if(exit < 0) continue;
            int outside = run.entries.get(i) - exit;
            assertTrue(outside >= flight, "вне зоны " + outside + " тиков, снаряд летит " + flight);
            checked++;
        }
        assertTrue(checked >= 2, "проверено кругов: " + checked);
    }

    @Test
    void noFlightTimeMeansNoWait(){
        //луч/мгновенный снаряд (flight = 0): после выхода юнит ждёт только запас
        Building t = turret(Blocks.duo, 60, 60, Items.copper);
        Unit u = unit(UnitTypes.flare, 20, 60);
        TurretDrain.Member m = join(u);
        step();
        m.flight = 0f;
        Run run = simulate(u, t, 3000);
        assertCycles(run, t);
    }

    // ------------------------------------------------------------------ состав отряда

    @Test
    void toggleTakesOnlyCurrentSelectionAndLaterOnesDoNotJoin(){
        Unit a = unit(UnitTypes.dagger, 20, 60);
        Unit b = unit(UnitTypes.dagger, 20, 62);
        TurretDrain.testSelection = Seq.with(a);
        assertTrue(TurretDrain.toggle(), "включилось");
        assertTrue(TurretDrain.enabled());
        assertNotNull(TurretDrain.member(a.id));

        TurretDrain.testSelection = Seq.with(a, b); //игрок выделил ещё одного
        for(int i = 0; i < 40; i++) TurretDrain.update();
        assertNull(TurretDrain.member(b.id), "новый выделенный в отряд не берётся");
        assertNotNull(TurretDrain.member(a.id));
        assertTrue(TurretDrain.enabled());
    }

    @Test
    void toggleWithoutSelectionStaysOff(){
        TurretDrain.testSelection = new Seq<>();
        assertFalse(TurretDrain.toggle());
        assertFalse(TurretDrain.enabled());
    }

    @Test
    void toggleOffAndOnTakesNewSelection(){
        Unit a = unit(UnitTypes.dagger, 20, 60);
        Unit b = unit(UnitTypes.dagger, 20, 62);
        TurretDrain.testSelection = Seq.with(a);
        TurretDrain.toggle();
        TurretDrain.toggle();
        assertFalse(TurretDrain.enabled());
        TurretDrain.testSelection = Seq.with(b);
        assertTrue(TurretDrain.toggle());
        assertNull(TurretDrain.member(a.id));
        assertNotNull(TurretDrain.member(b.id));
    }

    @Test
    void modeSwitchesOffWhenSquadIsGone(){
        Unit a = unit(UnitTypes.dagger, 20, 60);
        TurretDrain.testSelection = Seq.with(a);
        assertTrue(TurretDrain.toggle());
        a.kill();
        a.update();
        a.remove();
        Groups.unit.update();
        for(int i = 0; i < 40; i++) TurretDrain.update();
        assertFalse(TurretDrain.enabled(), "отряда нет - режим выключился сам");
    }

    @Test
    void manualOrderDropsUnitForGood() throws Exception{
        Unit a = unit(UnitTypes.dagger, 20, 60);
        Unit b = unit(UnitTypes.dagger, 20, 62);
        TurretDrain.testSelection = Seq.with(a, b);
        TurretDrain.toggle();
        Thread.sleep(5); //метка ручного приказа строго позже включения (в живой игре они разнесены сильно больше)
        ManualControl.mark(new int[]{a.id}); //игрок дал приказ сам
        for(int i = 0; i < 40; i++) TurretDrain.update();
        assertNull(TurretDrain.member(a.id), "ушёл из отряда");
        assertNotNull(TurretDrain.member(b.id));
        for(int i = 0; i < 40; i++) TurretDrain.update();
        assertNull(TurretDrain.member(a.id), "и обратно не вернулся, хотя всё ещё выбран");
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
        assertFalse(((CommandAI)u.controller()).hasStance(UnitStance.patrol));

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
