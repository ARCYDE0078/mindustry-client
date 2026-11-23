package sonkaextras;

import arc.Core;
import arc.Events;
import arc.math.Mathf;
import arc.math.geom.Intersector;
import arc.math.geom.Vec2;
import arc.struct.IntIntMap;
import arc.struct.IntMap;
import arc.struct.IntSeq;
import arc.struct.LongMap;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Time;
import mindustry.ai.UnitStance;
import mindustry.ai.types.CommandAI;
import mindustry.content.Blocks;
import mindustry.game.EventType.Trigger;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.gen.Building;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Unit;
import mindustry.type.UnitType;
import mindustry.world.Tile;
import mindustry.world.blocks.defense.turrets.BaseTurret.BaseTurretBuild;
import mindustry.world.blocks.defense.turrets.ItemTurret;
import mindustry.world.blocks.defense.turrets.LiquidTurret;
import mindustry.world.blocks.defense.turrets.Turret.TurretBuild;
import mindustry.world.blocks.units.RepairTower;
import mindustry.world.blocks.units.RepairTurret;
import mindustry.world.meta.BlockFlag;
import qol.core.ManualControl;

import static mindustry.Vars.*;

/**
 * Режим «разряжать турели»: выбранные юниты расходятся по вражеской ЛИНИИ ОБОРОНЫ, каждый заходит краем хитбокса в
 * зону поражения, сразу отлетает назад за радиус и пережидает там; турели тратят боезапас на выстрелы по нему.
 * <p>
 * <b>Почему клиентский режим, а не настоящая UnitCommand.</b> Новая команда в контенте игры сдвинула бы id контента
 * и сломала совместимость с ванильными серверами, а клиенту нужна только раздача обычных приказов. Поэтому режим
 * живёт на клиенте (как {@link AntiSplash}) и общается с сервером ванильными RPC.
 * <p>
 * <b>Линия обороны.</b> Турели не разбираются по одной: берётся «карта поражения» всех опасных для категории
 * (воздух/земля) турелей, и на внешней границе объединения их зон ставятся точки проникновения - слоты. Граница
 * строится выборкой по окружности каждой турели: точка годится, если её не накрывает НИ ОДНА другая зона (иначе она
 * внутри линии, а не на ней). Для слота считаются: P - точка входа на глубину {@link #DIP_PER_SPEED} от скорости внутрь
 * зоны (хитбокс касается радиуса и чуть глубже, чтобы турель успела выбрать цель), Q - точка отхода по нормали наружу
 * на настройку {@link #retreatKey}. Слот отбрасывается, если Q (или путь к ней) попадает в чью-то зону. Из слотов
 * юнит берёт тот, где его накрывает меньше всего турелей (число других зон над P), затем ближайший, с поправкой на
 * то, сколько юнитов уже у этой турели (чтобы разряжали разные).
 * <p>
 * <b>Интервал между юнитами.</b> Два слота не выдаются, если их P или Q ближе настройки {@link #gapKey}: снаряды,
 * пущенные по одному юниту, не заденут соседа. Юнитам, которым места не хватило, слот не даётся - они стоят в тылу
 * и займут место, как только оно освободится (погиб юнит, разрядилась турель, линия сдвинулась).
 * <p>
 * <b>Цикл одним пакетом приказов.</b> Заход и отход отправляются сразу ЦЕПОЧКОЙ: приказ «идти в P» и в очередь приказ
 * «идти в Q» (ванильная очередь команд, shift). Сервер сам разворачивает юнита у P, клиент не ждёт ничьего ответа и не
 * проверяет, стреляла ли турель: задержка не стоит юниту жизни. Юнит, которому далеко до слота, получает впереди ещё
 * приказ «в Q» (подлёт к линии снаружи и вход строго по нормали). После отхода юнит стоит у Q, пока общее время за
 * радиусом (включая обратный путь до границы) не достигнет {@link #safeKey} секунд - чтобы все снаряды успели пропасть.
 * <p>
 * <b>Страховки.</b> Юнит дольше положенного внутри зоны (приказ отхода потерялся) - Q отправляется заново отдельным
 * приказом; юнит у Q накрыт новой турелью - уводится из зоны; слот, до которого юнит не дошёл за 3 попытки, получает
 * временный бан. Юнит, уже зашедший в зону, не перепланируется до выхода (его отход уже в очереди).
 * <p>
 * <b>Состав отряда.</b> Отряд фиксируется в момент включения режима: в него попадают ТОЛЬКО юниты, выбранные в эту
 * секунду. Выбранные позже не подключаются; чтобы добавить, режим выключают и включают снова. Юнит выходит из отряда
 * насовсем при ручном приказе ({@link ManualControl}) или гибели; когда отряд опустел, режим выключается сам.
 * <p>
 * <b>Турели.</b> Разряжаются турели с боезапасом, в том числе ВЫКЛЮЧЕННЫЕ процессором (они включатся, как только в
 * зоне появится цель): важен сам заряд, а не флаг «включена». Энергетические турели боезапаса не имеют - они
 * учитываются только как опасные зоны.
 * <p>
 * Ограничения (честно): ходьба наземных юнитов проверяется по проходимости точек и по прямой «юнит - Q», а не по
 * настоящему пути; юнит с дальностью больше турельной может начать расстреливать турель сам; «заряжена ли турель»
 * берётся из синхронизированного {@code hasAmmo()}; интервал считается между слотами воздуха и земли тоже, но только
 * по уже выданным слотам.
 * <p>
 * Настройки (секция Sonka Extras): {@link #repairKey}, {@link #cyclone29Key}, {@link #retreatKey},
 * {@link #safeKey}, {@link #gapKey}.
 */
public final class TurretDrain{
    public static final String repairKey = "sonka-turretdrain-repair", cyclone29Key = "sonka-turretdrain-cyclone29",
        retreatKey = "sonka-turretdrain-retreat", safeKey = "sonka-turretdrain-safetime", gapKey = "sonka-turretdrain-gap";
    /** Значения настроек по умолчанию: отлёт за радиус (блоков), время за радиусом (секунд), интервал между юнитами (блоков). */
    public static final int DEF_RETREAT = 7, DEF_SAFE = 5, DEF_GAP = 7;

    /** Период пересмотра отряда/обстановки. */
    private static final float SCAN_TICKS = 15f;
    /** Не пересчитывать план одной категории чаще (турели мерцают на границе «есть/нет патрона»). */
    private static final long REPLAN_MIN_MS = 1000L;
    /** Сколько ждать после отправки, прежде чем считать, что приказ потерян, и слать заново. */
    private static final long RESEND_GRACE_MS = 3000L;
    /** Сколько ВЫЗОВОВ commandUnits уходит за тик (цепочка одного юнита не разрывается между тиками). */
    private static final int PER_TICK = 20;

    /** Глубина захода в зону = скорость * это (чтобы турель успела выбрать цель: у неё интервал поиска 20 тиков), в пределах [MIN, MAX]. */
    private static final float DIP_PER_SPEED = 18f, DIP_MIN = 8f, DIP_MAX = 48f;
    /** Запас точки отхода/пути вне зон (px сверх хитбокса). */
    private static final float Q_PAD = 8f;
    /** Юнит считается вышедшим из зоны, когда дальше её границы на столько (px). */
    private static final float OUT_PAD = 6f;
    /** Юнит у P / у Q (px). */
    private static final float ENTER_P = 6f, REACH_Q = 12f;
    /** Дальше этого от Q юнит получает впереди приказа «в P» ещё и «в Q» (подлёт к линии снаружи). */
    private static final float STAGE_DIST = 4f * 8f;
    /** Запас к времени «внутри зоны» до отправки отдельного приказа отхода (тиков) и к длительности круга. */
    private static final float RESCUE_EXTRA = 90f, TRIP_SLACK = 240f;
    /** Круг без захода в зону столько раз подряд - слот недостижим, баним его. */
    private static final int MAX_FAILS = 3;
    private static final long BAN_MS = 60_000L;
    /** Шаг выборки точек по окружности турели (px) и сколько кандидатов юнит проверяет на проходимость пути. */
    private static final float STEP_MIN = 8f, STEP_MAX = 24f;
    private static final int CORRIDOR_TESTS = 150;
    /** Вес «сколько других турелей накрывают P» и «сколько юнитов уже у этой турели» в стоимости слота (в блоках пути). */
    private static final float EXPOSURE_COST = 1000f, LOAD_COST = 25f;
    /** Насколько отодвигаем юнита от накрывшей его зоны (px сверх радиуса). */
    private static final float ESCAPE_PAD = 24f;

    /** Ниже этой доли здоровья - летим лечиться; здоров - возвращаемся. */
    private static final float REPAIR_LOW = 0.5f, REPAIR_OK = 0.95f;
    /** Лечение не дало результата за это время - бросаем и не пробуем {@link #REPAIR_BAN_MS}. */
    private static final long REPAIR_TIMEOUT_MS = 45_000L, REPAIR_BAN_MS = 60_000L;

    private static final int MODE_NONE = 0, MODE_CYCLE = 1, MODE_MOVE = 2;
    /** IDLE - приказы круга ещё не отправлены; GOING - цепочка отправлена, юнит идёт; HOLD - вышел и стоит у Q до готовности. */
    private static final int ST_IDLE = 0, ST_GOING = 1, ST_HOLD = 2;

    private static boolean active, wasActive;
    private static float timer;
    /** Настройки, прочитанные на последнем проходе планировщика (px / тиков). */
    private static float retreatPx = DEF_RETREAT * 8f, gapPx = DEF_GAP * 8f, safeTicks = DEF_SAFE * 60f;

    private static final IntMap<Member> squad = new IntMap<>();
    /** Очередь приказов на отправку (сглаживает всплески). */
    private static final Seq<Order> outbox = new Seq<>();
    /** Для теста: подменяет выделение игрока. */
    static Seq<Unit> testSelection;

    @SuppressWarnings("unchecked")
    private static final Seq<Zone>[] zones = new Seq[]{new Seq<Zone>(), new Seq<Zone>()};
    private static final long[] lastSig = new long[2], lastReplan = new long[2];
    private static final Seq<Unit> catUnits = new Seq<>();
    private static final IntSeq tmpIds = new IntSeq(), patrolOff = new IntSeq();
    private static final Seq<Slot> chosen = new Seq<>();
    private static final IntIntMap load = new IntIntMap();
    private static final ObjectMap<UnitType, Cands> candCache = new ObjectMap<>();

    private TurretDrain(){
    }

    /** Цепочка приказов юниту: первая точка - обычный приказ, остальные - в очередь. */
    private static final class Order{
        final int id;
        final float[] xy = new float[6];
        int n;

        Order(int id){
            this.id = id;
        }

        Order add(float x, float y){
            xy[n * 2] = x;
            xy[n * 2 + 1] = y;
            n++;
            return this;
        }
    }

    /** Занятый слот (для проверки интервала): точки входа и отхода. */
    private static final class Slot{
        final float px, py, qx, qy;

        Slot(float px, float py, float qx, float qy){
            this.px = px;
            this.py = py;
            this.qx = qx;
            this.qy = qy;
        }
    }

    /** Точка проникновения на линии обороны для данного типа юнита. */
    static final class Cand{
        long key;
        int turret;
        /** Точка границы, наружная нормаль, вход P, отход Q, глубина захода. */
        float bx, by, nx, ny, px, py, qx, qy, dip;
        /** Сколько ДРУГИХ турелей накрывают P. */
        int exposure;
    }

    private static final class Cands{
        final Seq<Cand> list = new Seq<>();
        final LongMap<Cand> byKey = new LongMap<>();
    }

    /** Состояние одного юнита отряда. */
    static final class Member{
        final long joinedAt = Time.millis();
        /** План: NONE - стоит в тылу, CYCLE - ходит кругами по слоту, MOVE - стоит в точке P (лечение/отход). */
        int mode = MODE_NONE;
        long slot = -1;
        int turret = -1;
        /** Слот: граница, нормаль, вход P, отход Q, глубина захода. */
        float bx, by, nx, ny, px, py, qx, qy, dip;
        /** Круг: состояние, признаки (вошёл / вышел / дошёл до Q), моменты (Time.time) и пределы времени. */
        int state = ST_IDLE;
        boolean entered, exited, reachedQ;
        float tripAt, enteredAt, outAt, readyAt, rescuedAt;
        /** Когда последний раз уводили из накрывшей зоны (ждём 60 тиков между такими приказами). */
        float escapeAt = -1000f;
        float tripLimit, rescueTicks;
        int fails;
        long banSlot = -1, banUntil;
        /** План пересчитан под текущую подпись обстановки; planned - служебная метка внутри одного прохода plan(). */
        boolean evaluated, planned;
        /** Приказ надо (пере)отправить; sentAt - когда последний раз отправляли (0 = ещё не отправляли). */
        boolean needSend;
        long sentAt;
        /** Лечение. */
        boolean repairing;
        int repairPos = -1;
        long repairSince, repairBanUntil;
        float repairStartHp;
    }

    /** Опасная для категории турель. */
    static final class Zone{
        float x, y, r;
        int pos;
        boolean drain;
    }

    /** Из Main.kt: слушаем тики и сброс при смене мира. */
    public static void init(){
        if(headless) return;
        Events.run(Trigger.update, TurretDrain::update);
        Events.on(WorldLoadEvent.class, e -> reset());
    }

    public static boolean enabled(){
        return active;
    }

    /**
     * Кнопка в панели команд. Включение берёт в отряд тех, кто выбран СЕЙЧАС; выбранные позже не подключаются (чтобы
     * добавить - выключить и включить снова). Режим не сохраняется между запусками: иначе выбранные юниты сами уходили бы в бой.
     */
    public static boolean toggle(){
        if(active){
            active = false;
            if(ui != null) ui.showInfoFade(Core.bundle.get("client.sonka.turretdrain.off"));
            return false;
        }
        if(player == null || !state.isGame()) return false;

        int n = capture();
        if(n == 0){
            if(ui != null) ui.showInfoFade(Core.bundle.get("client.sonka.turretdrain.nounits"));
            return false;
        }
        active = true;
        timer = SCAN_TICKS;
        if(ui != null) ui.showInfoFade(Core.bundle.format("client.sonka.turretdrain.on", n));
        return true;
    }

    private static void reset(){
        active = wasActive = false;
        squad.clear();
        outbox.clear();
        lastSig[0] = lastSig[1] = 0;
        lastReplan[0] = lastReplan[1] = 0;
    }

    static void update(){
        if(!state.isGame() || player == null){
            if(active || wasActive) reset();
            return;
        }

        if(!active){
            if(wasActive){
                wasActive = false;
                release();
            }
            return;
        }
        wasActive = true;

        long now = Time.millis();
        timer += Time.delta;
        if(timer >= SCAN_TICKS){
            timer = 0f;
            prune();
            if(squad.isEmpty()){
                //все погибли или взяты под ручное управление - режиму делать нечего
                active = wasActive = false;
                if(ui != null) ui.showInfoFade(Core.bundle.get("client.sonka.turretdrain.empty"));
                return;
            }
            scan(now);
        }
        tick(now);
    }

    /** Редкий проход планировщика по уже собранному отряду: настройки, обстановка, лечение, план, потерянные приказы. */
    static void scan(long now){
        if(squad.isEmpty()) return;

        readConfig();
        buildZones(Core.settings.getBool(cyclone29Key, false));
        handleRepair(Core.settings.getBool(repairKey, true), now);
        for(int c = 0; c < 2; c++) plan(c, now);
        checkLost(now);
        sendPending(now);
    }

    /** Покадровая часть: ход кругов по положению юнитов и отправка накопившихся приказов. */
    static void tick(long now){
        if(squad.isEmpty()) return;
        cycle(now);
        flushPatrol();
        drain(PER_TICK);
    }

    /** Для теста: scan + tick одним вызовом. */
    static void step(long now){
        scan(now);
        tick(now);
    }

    /** Для теста: добавить юнита в отряд, минуя выделение. */
    static Member join(Unit u){
        Member m = new Member();
        squad.put(u.id, m);
        return m;
    }

    static Member member(int id){
        return squad.get(id);
    }

    static Seq<Zone> zonesOf(int category){
        return zones[category];
    }

    /** Для теста: слоты-кандидаты типа юнита из последнего плана категории (пусто, если план не строился). */
    static Seq<Cand> candidatesOf(UnitType type){
        Cands cs = candCache.get(type);
        return cs == null ? new Seq<>() : cs.list;
    }

    /** Для теста: полный сброс состояния режима. */
    static void resetForTest(){
        reset();
        testSelection = null;
        timer = 0f;
        candCache.clear();
        patrolOff.clear();
    }

    private static void readConfig(){
        retreatPx = Mathf.clamp(Core.settings.getInt(retreatKey, DEF_RETREAT), 2, 40) * tilesize;
        safeTicks = Mathf.clamp(Core.settings.getInt(safeKey, DEF_SAFE), 0, 60) * 60f;
        gapPx = Mathf.clamp(Core.settings.getInt(gapKey, DEF_GAP), 1, 60) * tilesize;
    }

    // ---------------------------------------------------------------- состав отряда

    private static Seq<Unit> selection(){
        return testSelection != null ? testSelection : control.input.selectedUnits;
    }

    /** Новый отряд = выбранные сейчас юниты с CommandAI своей команды. */
    private static int capture(){
        squad.clear();
        outbox.clear();
        lastSig[0] = lastSig[1] = 0;
        lastReplan[0] = lastReplan[1] = 0;
        for(Unit u : selection()){
            if(u == null || !u.isValid() || u.team != player.team() || !(u.controller() instanceof CommandAI)) continue;
            squad.put(u.id, new Member());
        }
        return squad.size;
    }

    /** Убирает из отряда погибших и тех, кому игрок дал ручной приказ (насовсем: обратно сами не возвращаются). */
    static void prune(){
        tmpIds.clear();
        for(var e : squad){
            Unit u = Groups.unit.getByID(e.key);
            if(u == null || !u.isValid() || !(u.controller() instanceof CommandAI) || ManualControl.markedSince(e.key, e.value.joinedAt)){
                tmpIds.add(e.key);
            }
        }
        for(int i = 0; i < tmpIds.size; i++) squad.remove(tmpIds.get(i));
    }

    /** Выключение режима: останавливаем тех, кому что-то отправляли (и снимаем патруль, если он был). */
    private static void release(){
        outbox.clear();
        patrolOff.clear();
        tmpIds.clear();
        for(var e : squad){
            if(e.value.sentAt == 0) continue;
            Unit u = Groups.unit.getByID(e.key);
            if(u == null || !u.isValid() || !(u.controller() instanceof CommandAI ai)) continue;
            if(ai.hasStance(UnitStance.patrol)) patrolOff.add(e.key);
            tmpIds.add(e.key);
        }
        if(patrolOff.size > 0) sendStance(patrolOff, UnitStance.patrol, false);
        if(tmpIds.size > 0) sendStance(tmpIds, UnitStance.stop, true);
        patrolOff.clear();
        squad.clear();
        lastSig[0] = lastSig[1] = 0;
    }

    // ---------------------------------------------------------------- обстановка

    /** Собирает опасные зоны (вражеские работающие или лишь выключенные процессором турели, видимые нам) отдельно для воздуха [0] и земли [1]. */
    static void buildZones(boolean cyclone29){
        zones[0].clear();
        zones[1].clear();

        Seq<Building> enemy = indexer.getEnemy(player.team(), BlockFlag.turret);
        for(int i = 0; i < enemy.size; i++){
            Building b = enemy.get(i);
            if(!(b instanceof BaseTurretBuild t) || !b.isValid() || b.inFogTo(player.team()) || !TurretRanges.active(t)) continue;

            float r = t.range();
            //настройка: радиус циклона не меньше 29 блоков (против аутренж-циклонов); больший реальный радиус не урезаем
            if(cyclone29 && b.block == Blocks.cyclone) r = Math.max(r, 29f * tilesize);

            Zone z = new Zone();
            z.x = b.x;
            z.y = b.y;
            z.r = r;
            z.pos = b.pos();
            //разряжаем тех, у кого есть боезапас, который тратится выстрелами (энергетические - нет). Выключенная процессором
            //турель с зарядом тоже цель: её hasAmmo() врёт (эффективность обнулена), важен сам заряд
            z.drain = (b.block instanceof ItemTurret || b.block instanceof LiquidTurret) && b instanceof TurretBuild tb
                && TurretRanges.hasAmmoEvenIfDisabled(tb);

            if(t.targetAir()) zones[0].add(z);
            if(t.targetGround()) zones[1].add(z);
        }
    }

    // ---------------------------------------------------------------- лечение

    private static void handleRepair(boolean on, long now){
        for(var e : squad){
            Member m = e.value;
            Unit u = Groups.unit.getByID(e.key);
            if(u == null) continue;
            float frac = u.health / Math.max(u.maxHealth, 1f);

            if(!on){
                if(m.repairing) stopRepair(m);
                continue;
            }

            if(!m.repairing){
                if(frac >= REPAIR_LOW || now < m.repairBanUntil) continue;
                Building rb = indexer.findClosestFlag(u.x, u.y, u.team, BlockFlag.repair);
                if(rb == null) continue;
                m.repairing = true;
                m.repairSince = now;
                m.repairStartHp = frac;
                sendRepair(m, u, rb);
            }else{
                Building rb = world.build(m.repairPos);
                boolean gone = rb == null || !rb.isValid() || rb.team != u.team;
                if(frac >= REPAIR_OK){
                    stopRepair(m);
                }else if(now - m.repairSince > REPAIR_TIMEOUT_MS && frac < m.repairStartHp + 0.05f){
                    //не лечится (башня без питания, не достаёт и т.п.) - не висим там вечно
                    m.repairBanUntil = now + REPAIR_BAN_MS;
                    stopRepair(m);
                }else if(gone){
                    Building next = indexer.findClosestFlag(u.x, u.y, u.team, BlockFlag.repair);
                    if(next == null) stopRepair(m);
                    else sendRepair(m, u, next);
                }
            }
        }
    }

    private static void stopRepair(Member m){
        m.repairing = false;
        m.repairPos = -1;
        m.mode = MODE_NONE;
        m.slot = -1;
        m.state = ST_IDLE;
        m.evaluated = false;
    }

    private static void sendRepair(Member m, Unit u, Building rb){
        float radius = rb.block instanceof RepairTurret rt ? rt.repairRadius : rb.block instanceof RepairTower rw ? rw.range : 6f * tilesize;
        float d = Mathf.dst(u.x, u.y, rb.x, rb.y);
        //встаём внутри радиуса лечения, но не в центре башни (наземным она - стена)
        float k = d < 1f ? 0f : Math.min(d, radius * 0.5f) / d;
        m.mode = MODE_MOVE;
        m.px = rb.x + (u.x - rb.x) * k;
        m.py = rb.y + (u.y - rb.y) * k;
        m.repairPos = rb.pos();
        m.turret = -1;
        m.slot = -1;
        m.state = ST_IDLE;
        m.needSend = true;
        m.evaluated = true;
    }

    // ---------------------------------------------------------------- планирование: линия обороны и слоты

    /** Юнит уже в зоне (цепочка с отходом ушла, ждём выхода): не трогаем, чтобы не сорвать отход. */
    private static boolean locked(Member m){
        return m.mode == MODE_CYCLE && m.state == ST_GOING && m.entered && !m.exited;
    }

    private static void plan(int c, long now){
        catUnits.clear();
        boolean unevaluated = false;
        long sig = 0x9E3779B97F4A7C15L + hash(Math.round(retreatPx), Math.round(gapPx), 0, 3);

        for(var e : squad){
            Member m = e.value;
            if(m.repairing) continue;
            Unit u = Groups.unit.getByID(e.key);
            if(u == null || (u.isFlying() ? 0 : 1) != c) continue;
            catUnits.add(u);
            unevaluated |= !m.evaluated;
            sig += hash(u.id, 0, 0, 7);
        }
        if(catUnits.isEmpty()) return;

        Seq<Zone> zs = zones[c];
        for(int i = 0; i < zs.size; i++){
            Zone z = zs.get(i);
            sig += hash(z.pos, Math.round(z.r), z.drain ? 1 : 0, 13);
        }

        if(sig == lastSig[c] && !unevaluated) return;
        if(now - lastReplan[c] < REPLAN_MIN_MS) return;
        lastSig[c] = sig;
        lastReplan[c] = now;

        catUnits.sort((a, b) -> Integer.compare(a.id, b.id));
        candCache.clear();
        chosen.clear();
        load.clear();

        //слоты уже занятые юнитами другой категории тоже держат интервал
        for(var e : squad){
            Member m = e.value;
            Unit u = Groups.unit.getByID(e.key);
            if(u == null || (u.isFlying() ? 0 : 1) == c || m.mode != MODE_CYCLE || m.slot < 0) continue;
            chosen.add(new Slot(m.px, m.py, m.qx, m.qy));
        }

        for(Unit u : catUnits) squad.get(u.id).planned = false;

        //0) юниты внутри зоны остаются на своих слотах (их отход уже в очереди), но слоты занимают
        for(Unit u : catUnits){
            Member m = squad.get(u.id);
            if(!locked(m)) continue;
            m.planned = true;
            chosen.add(new Slot(m.px, m.py, m.qx, m.qy));
            load.put(m.turret, load.get(m.turret, 0) + 1);
            //слот пропал с линии - после выхода юнит будет перепланирован (evaluated=false вызовет пересчёт)
            m.evaluated = cands(u, zs).byKey.containsKey(m.slot);
        }

        //1) прежние слоты, если ещё годятся: без метаний при каждом пересчёте
        for(Unit u : catUnits){
            Member m = squad.get(u.id);
            if(m.planned || m.mode != MODE_CYCLE || m.slot < 0) continue;
            Cand cand = cands(u, zs).byKey.get(m.slot);
            if(cand == null || banned(m, cand.key, now) || !spacingOk(cand)) continue;
            take(u, m, cand);
        }

        //2) остальные: лучший свободный слот или тыл
        for(Unit u : catUnits){
            Member m = squad.get(u.id);
            if(m.planned) continue;
            Cand cand = pick(u, m, zs, now);
            if(cand != null) take(u, m, cand);
            else rear(m);
            m.evaluated = true;
        }
    }

    private static boolean banned(Member m, long key, long now){
        return m.banSlot == key && now < m.banUntil;
    }

    private static void take(Unit u, Member m, Cand c){
        assign(u, m, c);
        chosen.add(new Slot(c.px, c.py, c.qx, c.qy));
        load.put(c.turret, load.get(c.turret, 0) + 1);
    }

    /** Слот c не ближе gapPx ни к одному занятому (по точкам входа и по точкам отхода). */
    private static boolean spacingOk(Cand c){
        float g2 = gapPx * gapPx;
        for(int i = 0; i < chosen.size; i++){
            Slot s = chosen.get(i);
            if(Mathf.dst2(c.px, c.py, s.px, s.py) < g2 || Mathf.dst2(c.qx, c.qy, s.qx, s.qy) < g2) return false;
        }
        return true;
    }

    /**
     * Лучший свободный слот для юнита: стоимость = число других турелей над P * большой вес + путь до Q (блоки) +
     * вес за каждого юнита, уже занявшего эту турель. Берётся первый по стоимости, удовлетворяющий интервалу и
     * чистому пути от юнита к Q; проверок пути не больше CORRIDOR_TESTS (дальше - юнит остаётся в тылу).
     */
    private static Cand pick(Unit u, Member m, Seq<Zone> zs, long now){
        Cands cs = cands(u, zs);
        int n = cs.list.size;
        if(n == 0) return null;

        long[] order = new long[n];
        int cnt = 0;
        for(int i = 0; i < n; i++){
            Cand c = cs.list.get(i);
            if(banned(m, c.key, now) || !spacingOk(c)) continue;
            float cost = c.exposure * EXPOSURE_COST + Mathf.dst(u.x, u.y, c.qx, c.qy) / tilesize + LOAD_COST * load.get(c.turret, 0);
            order[cnt++] = ((long)Float.floatToIntBits(Math.max(cost, 0f)) << 32) | i;
        }
        java.util.Arrays.sort(order, 0, cnt);

        float hs = u.hitSize / 2f;
        for(int k = 0; k < cnt && k < CORRIDOR_TESTS; k++){
            Cand c = cs.list.get((int)(order[k] & 0xFFFFFFFFL));
            if(corridorOk(u, c, zs, hs)) return c;
        }
        return null;
    }

    /** Прямой путь от юнита к Q не задевает зон (кроме тех, в которых юнит уже стоит - из них ему всё равно выходить). */
    private static boolean corridorOk(Unit u, Cand c, Seq<Zone> zs, float hs){
        float pad = hs + Q_PAD;
        for(int i = 0; i < zs.size; i++){
            Zone o = zs.get(i);
            float r = o.r + pad;
            if(Mathf.dst2(u.x, u.y, o.x, o.y) < r * r) continue;
            float d = Intersector.distanceSegmentPoint(u.x, u.y, c.qx, c.qy, o.x, o.y);
            if(d < r) return false;
        }
        return true;
    }

    private static Cands cands(Unit u, Seq<Zone> zs){
        Cands cs = candCache.get(u.type);
        if(cs == null){
            cs = new Cands();
            candCache.put(u.type, cs);
            genCands(cs, u, zs);
        }
        return cs;
    }

    /**
     * Точки проникновения на внешней границе объединения зон для типа юнита u. Выборка по окружности каждой
     * разряжаемой турели; точка на линии, если её не накрывает ни одна другая зона. Для неё P - на глубину dip внутрь,
     * Q - на retreatPx наружу по нормали; Q и середина отхода обязаны быть вне всех зон, P и Q - проходимы.
     */
    private static void genCands(Cands out, Unit u, Seq<Zone> zs){
        float hs = u.hitSize / 2f;
        float dip = Mathf.clamp(u.type.speed * DIP_PER_SPEED, DIP_MIN, DIP_MAX);
        float step = Mathf.clamp(gapPx / 3f, STEP_MIN, STEP_MAX);

        for(int zi = 0; zi < zs.size; zi++){
            Zone z = zs.get(zi);
            if(!z.drain) continue;
            float rEff = z.r + hs;
            int n = Math.max(8, Mathf.ceil(Mathf.pi * 2f * rEff / step));
            for(int i = 0; i < n; i++){
                float ang = 360f * i / n;
                float cx = Mathf.cosDeg(ang), cy = Mathf.sinDeg(ang);
                float bx = z.x + cx * rEff, by = z.y + cy * rEff;
                //внутри чужой зоны - это не линия обороны, а её глубина
                if(coveredBy(zs, bx, by, hs + 1f, z) > 0) continue;

                float qx = bx + cx * retreatPx, qy = by + cy * retreatPx;
                float mx = bx + cx * retreatPx * 0.5f, my = by + cy * retreatPx * 0.5f;
                if(coveredBy(zs, qx, qy, hs + Q_PAD, null) > 0 || coveredBy(zs, mx, my, hs + Q_PAD * 0.5f, null) > 0) continue;

                float px = bx - cx * dip, py = by - cy * dip;
                if(!standable(u, px, py) || !standable(u, qx, qy)) continue;

                Cand c = new Cand();
                c.key = ((long)z.pos << 16) | i;
                c.turret = z.pos;
                c.bx = bx;
                c.by = by;
                c.nx = cx;
                c.ny = cy;
                c.px = px;
                c.py = py;
                c.qx = qx;
                c.qy = qy;
                c.dip = dip;
                c.exposure = coveredBy(zs, px, py, hs, z);
                out.list.add(c);
                out.byKey.put(c.key, c);
            }
        }
    }

    /** Выдаёт юниту слот: новый юнит/сменившийся слот, до зоны не дошедший, получает цепочку заново; ждущий у Q - со следующего круга. */
    private static void assign(Unit u, Member m, Cand c){
        boolean fresh = m.mode != MODE_CYCLE;
        boolean same = !fresh && m.slot == c.key && Mathf.dst(m.px, m.py, c.px, c.py) < 4f && Mathf.dst(m.qx, m.qy, c.qx, c.qy) < 4f;

        m.mode = MODE_CYCLE;
        m.slot = c.key;
        m.turret = c.turret;
        m.bx = c.bx;
        m.by = c.by;
        m.nx = c.nx;
        m.ny = c.ny;
        m.px = c.px;
        m.py = c.py;
        m.qx = c.qx;
        m.qy = c.qy;
        m.dip = c.dip;
        m.rescueTicks = 2f * c.dip / Math.max(u.type.speed, 0.2f) + RESCUE_EXTRA;

        if(fresh){
            m.state = ST_IDLE;
            m.fails = 0;
            m.needSend = true;
        }else if(!same && (m.state == ST_IDLE || (m.state == ST_GOING && !m.entered))){
            m.state = ST_IDLE;
            m.needSend = true;
        }
        m.evaluated = true;
        m.planned = true;
    }

    /**
     * Слота нет (разряжать нечего, места нет или недостижимо): юнит стоит в тылу. Того, кто шёл к зоне, уводим к
     * точке отхода (она была безопасной на момент плана); ждущий у Q остаётся на месте.
     */
    private static void rear(Member m){
        if(m.mode == MODE_CYCLE && m.state == ST_GOING && m.sentAt != 0){
            m.mode = MODE_MOVE;
            m.px = m.qx;
            m.py = m.qy;
            m.needSend = true;
        }else if(m.mode != MODE_MOVE){
            m.mode = MODE_NONE;
            m.state = ST_IDLE;
        }
        if(m.mode != MODE_CYCLE){
            m.slot = -1;
            m.turret = -1;
        }
        m.planned = true;
    }

    /** Сколько зон накрывают точку (радиус зоны + запас pad); skip - не считать эту зону (сама цель). */
    private static int coveredBy(Seq<Zone> all, float x, float y, float pad, Zone skip){
        int n = 0;
        for(int i = 0; i < all.size; i++){
            Zone o = all.get(i);
            if(o == skip) continue;
            float r = o.r + pad;
            if(Mathf.dst2(x, y, o.x, o.y) < r * r) n++;
        }
        return n;
    }

    /** Может ли юнит стоять в точке: внутри карты и проходимо для его типа (летающим - везде). */
    private static boolean standable(Unit u, float wx, float wy){
        if(wx < 0 || wy < 0 || wx > world.unitWidth() || wy > world.unitHeight()) return false;
        if(u.type.flying) return true;
        Tile t = world.tileWorld(wx, wy);
        return t != null && u.canPass(t.x, t.y);
    }

    // ---------------------------------------------------------------- круг: заход, отход, пауза

    /**
     * Ход кругов по положению юнитов (каждый тик, дёшево). GOING: ждём захода, выхода и прихода к Q, следим, чтобы юнит
     * не застрял в зоне; HOLD: стоит у Q, пока не пройдёт время за радиусом, потом новая цепочка.
     */
    private static void cycle(long now){
        for(var e : squad){
            Member m = e.value;
            if(m.mode != MODE_CYCLE || m.repairing) continue;
            Unit u = Groups.unit.getByID(e.key);
            if(u == null) continue;
            Seq<Zone> zs = zones[u.isFlying() ? 0 : 1];
            float hs = u.hitSize / 2f;
            if(m.state == ST_GOING) going(m, u, zs, hs, now);
            else if(m.state == ST_HOLD) hold(m, u, zs, hs, now);
        }
    }

    private static boolean idle(Unit u){
        return u.controller() instanceof CommandAI ai && ai.targetPos == null;
    }

    private static void going(Member m, Unit u, Seq<Zone> zs, float hs, long now){
        if(!m.entered){
            if(coveredBy(zs, u.x, u.y, hs, null) > 0 || u.within(m.px, m.py, ENTER_P)){
                m.entered = true;
                m.enteredAt = Time.time;
            }
        }else if(!m.exited){
            if(coveredBy(zs, u.x, u.y, hs + OUT_PAD, null) == 0){
                m.exited = true;
                m.outAt = Time.time;
            }else if(Time.time - Math.max(m.enteredAt, m.rescuedAt) > m.rescueTicks){
                //слишком долго в зоне: приказ отхода потерялся или юнит упёрся - шлём отход отдельным приказом
                m.rescuedAt = Time.time;
                outbox.add(new Order(u.id).add(m.qx, m.qy));
            }
        }

        if(m.exited && !m.reachedQ && (u.within(m.qx, m.qy, REACH_Q) || idle(u))) m.reachedQ = true;
        if(m.exited && m.reachedQ){
            m.state = ST_HOLD;
            m.fails = 0;
            //общее время за радиусом = время до этого момента + обратный путь до границы: его вычитаем из паузы
            m.readyAt = m.outAt + safeTicks - Mathf.dst(u.x, u.y, m.bx, m.by) / Math.max(u.type.speed, 0.2f);
            return;
        }
        if(Time.time - m.tripAt > m.tripLimit) tripTimeout(m, u, now);
    }

    private static void hold(Member m, Unit u, Seq<Zone> zs, float hs, long now){
        //у Q появилась новая турель (или линия сдвинулась): уходим из-под неё, не ждём конца паузы
        if(coveredBy(zs, u.x, u.y, hs, null) > 0){
            escape(m, u, zs, hs);
            return;
        }
        if(m.slot < 0 || Time.time < m.readyAt) return;
        if(!(u.controller() instanceof CommandAI ai)) return;
        startTrip(m, u, ai, now);
    }

    /** Круг не уложился в отведённое время: зашедшего спасаем, не дошедшего пробуем ещё раз и в конце банем слот. */
    private static void tripTimeout(Member m, Unit u, long now){
        if(m.entered && !m.exited){
            m.rescuedAt = Time.time;
            m.tripAt = Time.time;
            outbox.add(new Order(u.id).add(m.qx, m.qy));
        }else if(m.entered){
            //вышел, но до Q не дошёл (упёрся): круг окончен
            m.reachedQ = true;
        }else{
            m.fails++;
            if(m.fails >= MAX_FAILS){
                //слот недостижим: пока не трогаем его и просим пересчёт (юнит встанет на другой или в тыл)
                m.banSlot = m.slot;
                m.banUntil = now + BAN_MS;
                m.fails = 0;
                m.state = ST_HOLD;
                m.readyAt = Time.time + 600f;
                m.evaluated = false;
                lastReplan[0] = lastReplan[1] = 0;
            }else if(u.controller() instanceof CommandAI ai){
                startTrip(m, u, ai, now);
            }
        }
    }

    /** Выводит юнита из накрывшей его зоны на ближайшую свободную точку. */
    private static void escape(Member m, Unit u, Seq<Zone> zs, float hs){
        if(Time.time - m.escapeAt < 60f) return;
        m.escapeAt = Time.time;

        float x = u.x, y = u.y;
        for(int it = 0; it < 6; it++){
            Zone z = null;
            for(int i = 0; i < zs.size; i++){
                Zone o = zs.get(i);
                if(Mathf.dst2(x, y, o.x, o.y) < (o.r + hs) * (o.r + hs)){
                    z = o;
                    break;
                }
            }
            if(z == null) break;
            float dx = x - z.x, dy = y - z.y, l = Mathf.len(dx, dy);
            if(l < 1f){
                dx = 1f;
                dy = 0f;
                l = 1f;
            }
            x = z.x + dx / l * (z.r + hs + ESCAPE_PAD);
            y = z.y + dy / l * (z.r + hs + ESCAPE_PAD);
        }
        x = Mathf.clamp(x, 0f, world.unitWidth());
        y = Mathf.clamp(y, 0f, world.unitHeight());
        outbox.add(new Order(u.id).add(x, y));
    }

    /**
     * Новый круг: цепочка «P, затем Q» отправляется сразу вся. Юниту дальше STAGE_DIST от Q цепочка начинается с Q:
     * подлёт к линии снаружи, вход - по нормали от Q к P. Патруль, если был, снимается (иначе CommandAI сам зациклил бы точки).
     */
    private static void startTrip(Member m, Unit u, CommandAI ai, long now){
        Order o = new Order(u.id);
        float len;
        if(!u.within(m.qx, m.qy, STAGE_DIST)){
            o.add(m.qx, m.qy).add(m.px, m.py).add(m.qx, m.qy);
            len = Mathf.dst(u.x, u.y, m.qx, m.qy) + 2f * Mathf.dst(m.px, m.py, m.qx, m.qy);
        }else{
            o.add(m.px, m.py).add(m.qx, m.qy);
            len = Mathf.dst(u.x, u.y, m.px, m.py) + Mathf.dst(m.px, m.py, m.qx, m.qy);
        }
        if(ai.hasStance(UnitStance.patrol)) patrolOff.add(u.id);
        outbox.add(o);

        m.state = ST_GOING;
        m.entered = m.exited = m.reachedQ = false;
        m.tripAt = Time.time;
        m.tripLimit = len / Math.max(u.type.speed, 0.2f) * (u.isFlying() ? 1.5f : 2.5f) + TRIP_SLACK;
        m.needSend = false;
        m.sentAt = now;
    }

    /** Приказ мог потеряться (рейт-лимит сервера, обрыв): юнит без цели и вдали от нужных точек - шлём заново. */
    private static void checkLost(long now){
        for(var e : squad){
            Member m = e.value;
            if(m.needSend || m.sentAt == 0 || now - m.sentAt < RESEND_GRACE_MS) continue;
            Unit u = Groups.unit.getByID(e.key);
            if(u == null || !(u.controller() instanceof CommandAI ai) || ai.targetPos != null) continue;
            if(m.mode == MODE_MOVE){
                if(!u.within(m.px, m.py, 6f * tilesize)) m.needSend = true;
            }else if(m.mode == MODE_CYCLE && m.state == ST_GOING && !m.entered){
                if(!u.within(m.px, m.py, 6f * tilesize) && !u.within(m.qx, m.qy, 6f * tilesize)){
                    m.state = ST_IDLE;
                    m.needSend = true;
                }
            }
        }
    }

    /** Отправка плана тем, у кого он изменился: новый круг (цепочка приказов) либо приказ «идти в точку». */
    private static void sendPending(long now){
        for(var e : squad){
            Member m = e.value;
            if(!m.needSend) continue;
            Unit u = Groups.unit.getByID(e.key);
            if(u == null || !(u.controller() instanceof CommandAI ai)) continue;
            if(m.mode == MODE_MOVE){
                m.needSend = false;
                m.sentAt = now;
                if(ai.hasStance(UnitStance.patrol)) patrolOff.add(e.key);
                outbox.add(new Order(e.key).add(m.px, m.py));
            }else if(m.mode == MODE_CYCLE && m.state == ST_IDLE){
                startTrip(m, u, ai, now);
            }else{
                m.needSend = false;
            }
        }
        //патруль снимаем ДО приказов (иначе он успел бы положить старую точку в очередь); outbox уходит позже, в tick
        flushPatrol();
    }

    private static void flushPatrol(){
        if(patrolOff.size == 0) return;
        sendStance(patrolOff, UnitStance.patrol, false);
        patrolOff.clear();
    }

    /** Отправляет цепочки из очереди, пока хватает бюджета вызовов; приказы вышедших из отряда не шлём (не перебиваем ручное управление). */
    private static void drain(int limit){
        if(outbox.isEmpty()) return;
        if(player == null || state.isMenu()){
            outbox.clear();
            return;
        }
        while(!outbox.isEmpty()){
            Order o = outbox.first();
            if(!squad.containsKey(o.id)){
                outbox.remove(0);
                continue;
            }
            if(o.n > limit) break;
            outbox.remove(0);
            limit -= o.n;
            for(int i = 0; i < o.n; i++){
                //первая точка - обычный приказ (сбрасывает старую очередь), остальные - в очередь
                Call.commandUnits(player, new int[]{o.id}, null, null, new Vec2(o.xy[i * 2], o.xy[i * 2 + 1]), i > 0, true);
            }
        }
    }

    private static void sendStance(IntSeq ids, UnitStance stance, boolean enable){
        if(player == null || ids.size == 0) return;
        ids.chunked(1000, values -> Call.setUnitStance(player, values, stance, enable));
    }

    /** Смешивающий хэш для «подписи» обстановки (суммируется, поэтому порядок зон/юнитов не важен). */
    private static long hash(int a, int b, int c, int d){
        long h = a * 0x9E3779B97F4A7C15L;
        h ^= (h >>> 29) + b * 0xBF58476D1CE4E5B9L;
        h ^= (h >>> 31) + c * 0x94D049BB133111EBL;
        h ^= (h >>> 27) + d * 0xD6E8FEB86659FD93L;
        return h;
    }
}
