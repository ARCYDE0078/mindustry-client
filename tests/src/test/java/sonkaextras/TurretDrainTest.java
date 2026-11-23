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
 * Режим «разряжать турели» на настоящем движке (headless): карта поражения и линия обороны, слоты с интервалом между
 * юнитами, тыл для не поместившихся, цепочка приказов «заход - отход» одним пакетом (очередь команд CommandAI),
 * пауза за радиусом по времени, спасение/уход из-под новой турели, выключенные процессором турели, настройки.
 * Выделение (control.input) в headless нет - отряд собираем напрямую.
 */
public class TurretDrainTest{
    static final int NONE = 0, CYCLE = 1, MOVE = 2, IDLE = 0, GOING = 1, HOLD = 2;
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
        Core.settings.put(TurretDrain.retreatKey, TurretDrain.DEF_RETREAT);
        Core.settings.put(TurretDrain.safeKey, TurretDrain.DEF_SAFE);
        Core.settings.put(TurretDrain.gapKey, TurretDrain.DEF_GAP);
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

    float rangeOf(Building t){
        return ((BaseTurretBuild)t).range();
    }

    /** Накрыта ли точка какой-либо зоной категории (с запасом pad, px). */
    boolean covered(int category, float x, float y, float pad){
        for(var z : TurretDrain.zonesOf(category)){
            if(Mathf.dst(x, y, z.x, z.y) < z.r + pad) return true;
        }
        return false;
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
        assertEquals(rangeOf(world.build(60, 60)), stock, 0.01f, "без настройки - настоящий радиус");
        TurretDrain.buildZones(true);
        float big = 0, duo2 = 0;
        for(var z : TurretDrain.zonesOf(1)){
            if(z.pos == world.build(60, 60).pos()) big = z.r; else duo2 = z.r;
        }
        assertEquals(Math.max(stock, 29f * tilesize), big, 0.01f);
        assertEquals(duo, duo2, 0.01f, "остальные турели не затронуты настройкой");
    }

    // ------------------------------------------------------------------ турели, выключенные процессором

    @Test
    void disabledTurretWithAmmoIsADrainZone(){
        Building t = turret(Blocks.duo, 60, 60, Items.copper);
        t.enabled = false;
        ((ItemTurretBuild)t).updateConsumption(); //движок обнуляет эффективность выключенной
        assertFalse(((ItemTurretBuild)t).hasAmmo(), "обычный hasAmmo() у выключенной врёт - ради этого вся правка");
        TurretDrain.buildZones(false);
        assertEquals(1, TurretDrain.zonesOf(1).size);
        assertTrue(TurretDrain.zonesOf(1).first().drain, "заряд есть - разряжаем");
    }

    @Test
    void disabledTurretWithoutAmmoIsNotAZone(){
        Building t = turret(Blocks.duo, 60, 60, null);
        t.enabled = false;
        ((ItemTurretBuild)t).updateConsumption();
        TurretDrain.buildZones(false);
        assertEquals(0, TurretDrain.zonesOf(1).size);
    }

    @Test
    void disabledTurretWithAmmoGetsAUnit(){
        Building t = turret(Blocks.duo, 60, 60, Items.copper);
        t.enabled = false;
        ((ItemTurretBuild)t).updateConsumption();
        Unit u = unit(UnitTypes.dagger, 20, 60);
        TurretDrain.Member m = join(u);
        step();
        assertEquals(CYCLE, m.mode, "выключенная процессором, но заряженная турель - цель");
        assertEquals(t.pos(), m.turret);
        assertNotNull(((CommandAI)u.controller()).targetPos);
    }

    // ------------------------------------------------------------------ слоты и цепочка приказов

    @Test
    void farUnitGetsQueuedChainApproachThenDipThenRetreat(){
        Building t = turret(Blocks.duo, 60, 60, Items.copper);
        Unit u = unit(UnitTypes.dagger, 20, 60);
        TurretDrain.Member m = join(u);
        step();

        assertEquals(CYCLE, m.mode);
        assertEquals(t.pos(), m.turret);
        float hs = u.hitSize / 2f / tilesize, r = rangeOf(t) / tilesize;
        float pd = distTiles(m.px, m.py, t.x, t.y), qd = distTiles(m.qx, m.qy, t.x, t.y);
        assertTrue(pd < r + hs, "вход внутри зоны: " + pd + " < " + (r + hs));
        assertTrue(pd > r + hs - 6f, "заход неглубокий: " + pd);
        assertEquals(r + hs + TurretDrain.DEF_RETREAT, qd, 0.2f, "отход - на настройку «дальность отлёта» за границей зоны");

        //цепочка уходит сразу целиком (далёкому юниту впереди ещё подлёт к Q): Q -> P -> Q
        CommandAI ai = (CommandAI)u.controller();
        assertNotNull(ai.targetPos);
        assertEquals(m.qx, ai.targetPos.x, 0.5f);
        assertEquals(m.qy, ai.targetPos.y, 0.5f);
        assertEquals(2, ai.commandQueue.size, "в очереди заход и отход");
        assertEquals(m.px, ai.commandQueue.get(0).getX(), 0.5f);
        assertEquals(m.py, ai.commandQueue.get(0).getY(), 0.5f);
        assertEquals(m.qx, ai.commandQueue.get(1).getX(), 0.5f);
        assertEquals(m.qy, ai.commandQueue.get(1).getY(), 0.5f);
        assertFalse(ai.hasStance(UnitStance.patrol), "ванильный патруль не используется");
        assertFalse(ManualControl.markedSince(u.id, 0L), "собственные приказы режима не считаются ручными");
    }

    @Test
    void nearUnitGetsDipAndRetreatImmediately(){
        Building t = turret(Blocks.duo, 60, 60, Items.copper);
        float r = rangeOf(t) / tilesize;
        //уже у линии обороны, в нескольких блоках от точки отхода
        Unit u = unit(UnitTypes.dagger, 60 - r - 0.5f - TurretDrain.DEF_RETREAT - 1f, 60);
        TurretDrain.Member m = join(u);
        step();
        assertEquals(CYCLE, m.mode);
        CommandAI ai = (CommandAI)u.controller();
        assertEquals(m.px, ai.targetPos.x, 0.5f, "первым - заход");
        assertEquals(m.py, ai.targetPos.y, 0.5f);
        assertEquals(1, ai.commandQueue.size, "и сразу за ним в очереди отход");
        assertEquals(m.qx, ai.commandQueue.get(0).getX(), 0.5f);
        assertEquals(m.qy, ai.commandQueue.get(0).getY(), 0.5f);
    }

    @Test
    void airUnitIgnoresGroundOnlyTurret(){
        turret(Blocks.hail, 60, 60, Items.graphite);
        Unit u = unit(UnitTypes.flare, 20, 60);
        TurretDrain.Member m = join(u);
        step();
        assertEquals(NONE, m.mode, "хейл не бьёт по воздуху - разряжать воздушных им незачем");
        assertNull(((CommandAI)u.controller()).targetPos);
    }

    @Test
    void groundUnitIgnoresAirOnlyTurret(){
        turret(Blocks.scatter, 60, 60, Items.scrap);
        Unit u = unit(UnitTypes.dagger, 20, 60);
        TurretDrain.Member m = join(u);
        step();
        assertEquals(NONE, m.mode);
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
    void defenseLineLiesOnTheOuterEdgeOfTheUnion(){
        Building t1 = turret(Blocks.duo, 60, 60, Items.copper);
        Building t2 = turret(Blocks.duo, 64, 60, Items.copper); //стоят рядом: зоны сильно перекрываются
        Unit u = unit(UnitTypes.dagger, 20, 60);
        TurretDrain.Member m = join(u);
        step();
        assertEquals(CYCLE, m.mode);
        float hs = u.hitSize / 2f;
        var cands = TurretDrain.candidatesOf(u.type);
        assertTrue(cands.size > 20, "линия обороны построена: " + cands.size + " точек");
        for(var c : cands){
            for(var z : TurretDrain.zonesOf(1)){
                if(z.pos == c.turret) continue;
                assertTrue(Mathf.dst(c.bx, c.by, z.x, z.y) >= z.r + hs, "точка линии не внутри чужой зоны");
            }
            assertFalse(covered(1, c.qx, c.qy, hs), "отход - вне любой зоны");
        }
        //внутренняя сторона пары (между турелями) в линию не попала
        for(var c : cands) assertFalse(c.bx > 61 * tilesize && c.bx < 63 * tilesize && Math.abs(c.by - 60 * tilesize) < 10 * tilesize, "между турелями слотов нет");
        assertNotNull(t1);
        assertNotNull(t2);
        assertFalse(covered(1, m.qx, m.qy, hs));
    }

    @Test
    void unitsKeepMinimumDistanceBetweenEachOther(){
        turret(Blocks.duo, 60, 60, Items.copper);
        Seq<TurretDrain.Member> ms = new Seq<>();
        for(int i = 0; i < 4; i++) ms.add(join(unit(UnitTypes.flare, 20, 60 + i)));
        step();
        float gap = TurretDrain.DEF_GAP * tilesize;
        for(var m : ms) assertEquals(CYCLE, m.mode, "места хватает всем");
        for(int i = 0; i < ms.size; i++) for(int j = i + 1; j < ms.size; j++){
            var a = ms.get(i);
            var b = ms.get(j);
            assertTrue(Mathf.dst(a.px, a.py, b.px, b.py) >= gap - 0.5f, "точки входа " + i + " и " + j + " не ближе интервала");
            assertTrue(Mathf.dst(a.qx, a.qy, b.qx, b.qy) >= gap - 0.5f, "точки отхода " + i + " и " + j + " не ближе интервала");
        }
    }

    @Test
    void gapSettingIsRespected(){
        Core.settings.put(TurretDrain.gapKey, 12);
        turret(Blocks.duo, 60, 60, Items.copper);
        Seq<TurretDrain.Member> ms = new Seq<>();
        for(int i = 0; i < 3; i++) ms.add(join(unit(UnitTypes.flare, 20, 60 + i)));
        step();
        float gap = 12 * tilesize;
        int placed = 0;
        for(var m : ms) if(m.mode == CYCLE) placed++;
        assertTrue(placed >= 1);
        for(int i = 0; i < ms.size; i++) for(int j = i + 1; j < ms.size; j++){
            var a = ms.get(i);
            var b = ms.get(j);
            if(a.mode != CYCLE || b.mode != CYCLE) continue;
            assertTrue(Mathf.dst(a.px, a.py, b.px, b.py) >= gap - 0.5f);
            assertTrue(Mathf.dst(a.qx, a.qy, b.qx, b.qy) >= gap - 0.5f);
        }
    }

    @Test
    void surplusUnitsWaitInTheRearAndTakeFreedSpots() throws Exception{
        Core.settings.put(TurretDrain.gapKey, 12); //на одну турель помещается 2-3 юнита
        turret(Blocks.duo, 60, 60, Items.copper);
        Seq<Unit> us = new Seq<>();
        for(int i = 0; i < 8; i++) us.add(unit(UnitTypes.flare, 20, 56 + i));
        TurretDrain.testSelection = us;
        assertTrue(TurretDrain.toggle());
        step();

        int placed = 0, rear = 0;
        for(Unit u : us){
            var m = TurretDrain.member(u.id);
            if(m.mode == CYCLE) placed++;
            else{
                rear++;
                assertNull(((CommandAI)u.controller()).targetPos, "тыловому приказов не слали");
            }
        }
        assertTrue(placed >= 1 && placed <= 4, "разряжают немногие: " + placed);
        assertTrue(rear >= 4, "остальные - в тылу: " + rear);

        //один из разряжающих погиб - его место занимает тыловой
        Unit dead = null;
        for(Unit u : us) if(TurretDrain.member(u.id).mode == CYCLE){
            dead = u;
            break;
        }
        dead.kill();
        dead.update();
        dead.remove();
        Groups.unit.update();
        TurretDrain.prune();
        step();
        int placedNow = 0;
        for(Unit u : us){
            var m = TurretDrain.member(u.id);
            if(m != null && m.mode == CYCLE) placedNow++;
        }
        assertEquals(placed, placedNow, "освободившееся место занято тыловым юнитом");
    }

    @Test
    void retreatSettingMovesExitPoint(){
        Core.settings.put(TurretDrain.retreatKey, 12);
        Building t = turret(Blocks.duo, 60, 60, Items.copper);
        Unit u = unit(UnitTypes.dagger, 20, 60);
        TurretDrain.Member m = join(u);
        step();
        float hs = u.hitSize / 2f / tilesize, r = rangeOf(t) / tilesize;
        assertEquals(r + hs + 12f, distTiles(m.qx, m.qy, t.x, t.y), 0.2f);
    }

    @Test
    void exitPointIsOutsideEveryZone(){
        turret(Blocks.duo, 60, 60, Items.copper);
        turret(Blocks.duo, 64, 60, Items.copper);
        Unit u = unit(UnitTypes.dagger, 20, 60);
        TurretDrain.Member m = join(u);
        step();
        assertEquals(CYCLE, m.mode);
        for(var z : TurretDrain.zonesOf(1)){
            assertTrue(Mathf.dst(m.qx, m.qy, z.x, z.y) > z.r + u.hitSize / 2f, "выход вне зоны турели " + z.pos);
        }
    }

    // ------------------------------------------------------------------ перепланирование

    @Test
    void turretDrainedBeforeEntryUnitBacksOffToExitPoint(){
        Building t = turret(Blocks.duo, 60, 60, Items.copper);
        Unit u = unit(UnitTypes.dagger, 20, 60);
        TurretDrain.Member m = join(u);
        step();
        assertEquals(CYCLE, m.mode);
        float qx = m.qx, qy = m.qy;

        ((ItemTurretBuild)t).ammo.clear();
        ((ItemTurretBuild)t).totalAmmo = 0;
        step();
        assertEquals(MOVE, m.mode, "разряжено: уходим к точке отхода, а не заходим по устаревшему маршруту");
        CommandAI ai = (CommandAI)u.controller();
        assertEquals(qx, ai.targetPos.x, 0.5f);
        assertEquals(qy, ai.targetPos.y, 0.5f);
        assertEquals(0, ai.commandQueue.size, "старая цепочка сброшена");
    }

    @Test
    void unitInsideTheZoneIsNotReplannedUntilItIsOut(){
        Building t = turret(Blocks.duo, 60, 60, Items.copper);
        Unit u = unit(UnitTypes.dagger, 20, 60);
        TurretDrain.Member m = join(u);
        step();
        int slotQ = (int)m.qx;
        u.set(m.px, m.py); //юнит добрался до точки захода
        for(int i = 0; i < 5; i++){
            Time.update();
            clock += 17;
            TurretDrain.tick(clock);
        }
        assertTrue(m.entered, "заход зафиксирован");
        long sent = m.sentAt;

        ((ItemTurretBuild)t).ammo.clear();
        ((ItemTurretBuild)t).totalAmmo = 0;
        step();
        assertEquals(CYCLE, m.mode, "внутри зоны юнита не перепланируют: его отход уже в очереди");
        assertEquals(sent, m.sentAt, "приказ не пересылался");
        assertEquals(slotQ, (int)m.qx);
        CommandAI ai = (CommandAI)u.controller();
        assertFalse(ai.commandQueue.isEmpty() && ai.targetPos == null, "отход остался в очереди");
    }

    @Test
    void slotThatNewTurretCoversIsReplaced(){
        Building far = turret(Blocks.duo, 100, 60, Items.copper);
        Unit u = unit(UnitTypes.dagger, 20, 60);
        TurretDrain.Member m = join(u);
        step();
        assertEquals(far.pos(), m.turret);

        //новая турель накрывает точку отхода прежнего слота: он больше не годится
        Building near = turret(Blocks.duo, 70, 60, Items.copper);
        step();
        assertEquals(CYCLE, m.mode);
        float hs = u.hitSize / 2f;
        TurretDrain.buildZones(false);
        assertFalse(covered(1, m.qx, m.qy, hs), "новый отход - вне всех зон");
        assertEquals(near.pos(), m.turret, "ближайшая из турелей линии взята в работу");
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
        assertTrue(m.sentAt > sent, "потеря обнаружена, цепочка отправлена заново");
        assertNotNull(ai.targetPos);
        assertEquals(2, ai.commandQueue.size);
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
        float r = rangeOf(c) / tilesize;
        assertTrue(distTiles(m.px, m.py, c.x, c.y) < r + 0.6f, "без настройки вход у настоящей границы циклона");
        assertTrue(distTiles(m.qx, m.qy, c.x, c.y) > r, "выход за настоящим радиусом циклона");
    }

    @Test
    void cyclone29PushesBothPointsOutToTheBoundaryOf29Blocks(){
        Core.settings.put(TurretDrain.cyclone29Key, true);
        Building c = turret(Blocks.cyclone, 70, 60, Items.metaglass);
        TurretDrain.Member m = join(unit(UnitTypes.flare, 20, 60));
        step();
        assertEquals(CYCLE, m.mode);
        assertTrue(distTiles(m.qx, m.qy, c.x, c.y) > 29f + TurretDrain.DEF_RETREAT - 0.2f, "выход за 29 блоков + отлёт: " + distTiles(m.qx, m.qy, c.x, c.y));
        assertTrue(distTiles(m.px, m.py, c.x, c.y) < 29f + 0.6f, "вход - у границы 29 блоков: " + distTiles(m.px, m.py, c.x, c.y));
        assertTrue(distTiles(m.px, m.py, c.x, c.y) > 29f - 6.5f, "и неглубоко: " + distTiles(m.px, m.py, c.x, c.y));
    }

    // ------------------------------------------------------------------ проходимость для наземных

    @Test
    void groundPointsAvoidWalls(){
        Building t = turret(Blocks.duo, 60, 60, Items.copper);
        TurretDrain.Member free = join(unit(UnitTypes.dagger, 20, 60));
        step();
        assertEquals(CYCLE, free.mode);
        float p0x = free.px, p0y = free.py, q0x = free.qx, q0y = free.qy;

        //забиваем стенами и вход, и выход прямого направления: план обязан уйти в сторону, но остаться проходимым
        TurretDrain.resetForTest();
        for(Point2 c : new Point2[]{new Point2(World.toTile(p0x), World.toTile(p0y)), new Point2(World.toTile(q0x), World.toTile(q0y))}){
            for(int dx = -3; dx <= 3; dx++) for(int dy = -3; dy <= 3; dy++) world.tile(c.x + dx, c.y + dy).setBlock(Blocks.copperWall, Team.derelict, 0);
        }
        Unit u = unit(UnitTypes.dagger, 20, 61);
        TurretDrain.Member m = join(u);
        step();
        assertEquals(CYCLE, m.mode, "обходной маршрут найден");
        assertEquals(t.pos(), m.turret);
        assertTrue(u.canPass(World.toTile(m.px), World.toTile(m.py)), "вход проходим");
        assertTrue(u.canPass(World.toTile(m.qx), World.toTile(m.qy)), "выход проходим");
        assertTrue(Mathf.dst(m.px, m.py, p0x, p0y) > 3f * tilesize, "вход сместился с заваленного места");
    }

    @Test
    void groundUnitWithNoWalkableApproachIsLeftAlone(){
        turret(Blocks.duo, 60, 60, Items.copper);
        //кольцо стен вокруг турели шире её радиуса: входа пешком нет
        for(int x = 30; x <= 90; x++) for(int y = 30; y <= 90; y++){
            float d = Mathf.dst(x, y, 60, 60);
            if(d > 12f && d < 20f) world.tile(x, y).setBlock(Blocks.copperWall, Team.derelict, 0);
        }
        TurretDrain.Member m = join(unit(UnitTypes.dagger, 20, 60));
        step();
        assertEquals(NONE, m.mode, "подойти нельзя - юнита не гоняем впустую");
    }

    // ------------------------------------------------------------------ живой цикл

    /** Результат прогона цикла юнита: входы в зону и выходы из неё, в тиках, и сколько юнит пробыл внутри за заход. */
    static final class Run{
        final Seq<Integer> entries = new Seq<>(), exits = new Seq<>();
        float minD = Float.MAX_VALUE, maxD = 0f;
        int maxQueue = 0, maxInside = 0;
        boolean patrolSeen = false;
    }

    /** Прогон настоящего CommandAI с покадровым tick() режима: цепочку отправляет клиент один раз за круг. */
    Run simulate(Unit u, Building t, int ticks){
        float r = rangeOf(t);
        CommandAI ai = (CommandAI)u.controller();
        Run run = new Run();
        boolean inside = false;
        int enteredAt = 0;
        for(int tick = 0; tick < ticks; tick++){
            Time.update();
            clock += 17;
            TurretDrain.tick(clock);
            u.update();
            float d = Mathf.dst(u.x, u.y, t.x, t.y);
            run.minD = Math.min(run.minD, d);
            if(tick > 60) run.maxD = Math.max(run.maxD, d);
            boolean now = d < r;
            if(tick > 0 && now && !inside){
                run.entries.add(tick);
                enteredAt = tick;
            }
            if(tick > 0 && !now && inside){
                run.exits.add(tick);
                run.maxInside = Math.max(run.maxInside, tick - enteredAt);
            }
            inside = now;
            run.maxQueue = Math.max(run.maxQueue, ai.commandQueue.size);
            run.patrolSeen |= ai.hasStance(UnitStance.patrol);
        }
        return run;
    }

    /** Между выходом из зоны и следующим заходом - не меньше безопасного времени, внутри - недолго. */
    void assertCycles(Run run, Building t, int maxInsideTicks){
        float r = rangeOf(t);
        float safe = TurretDrain.DEF_SAFE * 60f;
        assertTrue(run.minD < r, "юнит заходил в зону: min=" + run.minD + " r=" + r);
        assertTrue(run.maxD > r, "и выходил из неё: max=" + run.maxD + " r=" + r);
        assertTrue(run.entries.size >= 3, "циклически (3+ захода): заходов " + run.entries.size);
        assertTrue(run.maxQueue >= 1, "заход и отход ставились в очередь заранее");
        assertFalse(run.patrolSeen, "ванильный патруль не включался");
        assertTrue(run.maxInside <= maxInsideTicks, "внутри зоны не дольше " + maxInsideTicks + " тиков: " + run.maxInside);

        int checked = 0;
        for(int i = 1; i < run.entries.size; i++){
            int exit = -1;
            for(int ex : run.exits) if(ex < run.entries.get(i) && ex > run.entries.get(i - 1)) exit = ex;
            if(exit < 0) continue;
            int outside = run.entries.get(i) - exit;
            assertTrue(outside >= safe - 40f, "за радиусом " + outside + " тиков, нужно ~" + safe);
            assertTrue(outside <= safe + 600f, "и не бесконечно долго: " + outside);
            checked++;
        }
        assertTrue(checked >= 2, "проверено кругов: " + checked);
    }

    @Test
    void airUnitCyclesWithSafePauseOutsideTheRadius(){
        Building t = turret(Blocks.duo, 60, 60, Items.copper);
        Unit u = unit(UnitTypes.flare, 20, 60);
        join(u);
        step();
        //быстрый юнит: без паузы вне зоны он бы провёл меньше секунды - пауза обязана добрать до 5 с
        float plainOutside = 2f * TurretDrain.DEF_RETREAT * tilesize / u.type.speed;
        assertTrue(plainOutside < 120f, "без паузы вне зоны быстро: " + plainOutside);
        assertCycles(simulate(u, t, 4000), t, 160);
    }

    @Test
    void groundUnitCyclesWithSafePauseOutsideTheRadius(){
        Building t = turret(Blocks.duo, 60, 60, Items.copper);
        Unit u = unit(UnitTypes.dagger, 20, 60);
        join(u);
        step();
        assertCycles(simulate(u, t, 7000), t, 400);
    }

    @Test
    void safeTimeSettingChangesThePause(){
        Core.settings.put(TurretDrain.safeKey, 10);
        Building t = turret(Blocks.duo, 60, 60, Items.copper);
        Unit u = unit(UnitTypes.flare, 20, 60);
        join(u);
        step();
        Run run = simulate(u, t, 5000);
        assertTrue(run.entries.size >= 3);
        int checked = 0;
        for(int i = 1; i < run.entries.size; i++){
            int exit = -1;
            for(int ex : run.exits) if(ex < run.entries.get(i) && ex > run.entries.get(i - 1)) exit = ex;
            if(exit < 0) continue;
            assertTrue(run.entries.get(i) - exit >= 10 * 60 - 40, "пауза 10 с по настройке");
            checked++;
        }
        assertTrue(checked >= 2);
    }

    // ------------------------------------------------------------------ страховки

    @Test
    void unitStuckInsideGetsRetreatOrderOnItsOwn(){
        Building t = turret(Blocks.duo, 60, 60, Items.copper);
        Unit u = unit(UnitTypes.dagger, 20, 60);
        TurretDrain.Member m = join(u);
        step();
        CommandAI ai = (CommandAI)u.controller();
        u.set(m.px, m.py);
        ai.clearCommands(); //заход состоялся, а приказ отхода потерялся
        for(int i = 0; i < 400 && ai.targetPos == null; i++){
            Time.update();
            clock += 17;
            TurretDrain.tick(clock);
        }
        assertNotNull(ai.targetPos, "отход отправлен отдельным приказом");
        assertEquals(m.qx, ai.targetPos.x, 0.5f);
        assertEquals(m.qy, ai.targetPos.y, 0.5f);
        assertNotNull(t);
    }

    @Test
    void unitCaughtByNewTurretWhileWaitingIsMovedOut(){
        Building t = turret(Blocks.duo, 60, 60, Items.copper);
        Unit u = unit(UnitTypes.flare, 20, 60);
        TurretDrain.Member m = join(u);
        step();
        u.set(m.qx, m.qy);
        m.state = HOLD;
        m.readyAt = Time.time + 100000f; //пауза ещё не кончилась
        CommandAI ai = (CommandAI)u.controller();
        ai.clearCommands();

        //враг ставит ещё одну турель, зона которой накрыла стоянку юнита
        Building fresh = turret(Blocks.duo, (int)(m.qx / tilesize) + 5, (int)(m.qy / tilesize), Items.copper);
        step();
        assertNotNull(ai.targetPos, "юнит уводится из-под новой турели, не дожидаясь конца паузы");
        float hs = u.hitSize / 2f;
        assertTrue(Mathf.dst(ai.targetPos.x, ai.targetPos.y, fresh.x, fresh.y) > rangeOf(fresh) + hs, "новая точка вне зоны новой турели");
        assertNotNull(t);
    }

    @Test
    void unreachableSlotIsBannedAfterThreeFailedTrips(){
        turret(Blocks.duo, 60, 60, Items.copper);
        Unit u = unit(UnitTypes.dagger, 20, 60);
        TurretDrain.Member m = join(u);
        step();
        long slot = m.slot;
        //юнит не двигается (застрял): круги кончаются по времени
        for(int i = 0; i < 40000 && m.banSlot < 0; i++){
            Time.update();
            clock += 17;
            TurretDrain.tick(clock);
        }
        assertEquals(slot, m.banSlot, "недостижимый слот забанен");
        step();
        assertTrue(m.mode != CYCLE || m.slot != slot, "юнит пересажен на другой слот либо в тыл");
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
        assertEquals(CYCLE, m.mode);

        u.health = u.maxHealth * 0.3f;
        step();
        assertTrue(m.repairing, "ранен - летим лечиться");
        assertEquals(rp.pos(), m.repairPos);
        assertEquals(MOVE, m.mode);
        assertTrue(Mathf.dst(m.px, m.py, rp.x, rp.y) < 6f * tilesize, "точка лечения у ремонтного пункта");
        assertFalse(((CommandAI)u.controller()).hasStance(UnitStance.patrol));

        u.health = u.maxHealth;
        step();
        assertFalse(m.repairing, "вылечен");
        step();
        assertEquals(CYCLE, m.mode, "и снова в строю");
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
        assertEquals(CYCLE, m.mode);
    }

    @Test
    void noRepairBuildingMeansKeepFighting(){
        turret(Blocks.duo, 90, 60, Items.copper);
        Unit u = unit(UnitTypes.dagger, 20, 60);
        TurretDrain.Member m = join(u);
        u.health = u.maxHealth * 0.1f;
        step();
        assertFalse(m.repairing);
        assertEquals(CYCLE, m.mode);
    }
}
