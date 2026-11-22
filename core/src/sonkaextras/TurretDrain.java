package sonkaextras;

import arc.Core;
import arc.Events;
import arc.math.Angles;
import arc.math.Mathf;
import arc.math.geom.Vec2;
import arc.struct.IntMap;
import arc.struct.IntSeq;
import arc.struct.Seq;
import arc.util.Time;
import mindustry.ai.UnitStance;
import mindustry.ai.types.CommandAI;
import mindustry.content.Blocks;
import mindustry.entities.bullet.BulletType;
import mindustry.game.EventType.Trigger;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.gen.Building;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Unit;
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
 * Режим «разряжать турели»: выбранные юниты расходятся по вражеской линии обороны, каждый подлетает к своей
 * турели, на миг заходит в зону её поражения (турель тратит боезапас на выстрелы по нему), уходит назад и пережидает,
 * пока выпущенный снаряд долетит; цикл повторяется, пока турель не опустеет.
 * <p>
 * <b>Почему клиентский режим, а не настоящая UnitCommand.</b> Новая команда в контенте игры сдвинула бы id контента
 * и сломала совместимость с ванильными серверами, а клиенту, по сути, нужна только раздача обычных приказов. Поэтому
 * режим живёт на клиенте (как {@link AntiSplash}) и общается с сервером ванильными RPC.
 * <p>
 * <b>Обычные приказы движения, не патруль.</b> Ванильный патруль ({@link UnitStance#patrol}) разворачивает юнита сразу
 * в точке и ждать не умеет, поэтому цикл ведёт сам клиент: у каждого юнита три фазы - IN (идёт к точке входа P чуть
 * внутри зоны турели), OUT (по прибытии - приказ к точке выхода Q за радиусом), WAIT (стоит у Q, пока с момента отхода
 * не пройдёт время полёта снаряда {@link Zone#flight} плюс {@link #WAIT_MARGIN}), затем снова IN. Это по одному
 * обычному приказу {@code commandUnits} на смену фазы; прибытие определяется по позиции юнита, а не по эху сервера.
 * Если у юнита был включён патруль, он снимается: иначе CommandAI сам зациклил бы пройденные точки.
 * <p>
 * <b>Перепланирование.</b> Каждые {@link #SCAN_TICKS} тиков считается «подпись» обстановки (какие турели опасны, какие
 * ещё заряжены, кто в отряде). Изменилась (турель разрядилась/уничтожена, враг поставил новую) - план пересчитывается
 * целиком, а юнитам отправляются только изменившиеся приказы. Воздух и земля считаются отдельно: турель опасна для
 * категории, только если умеет по ней стрелять ({@code targetAir}/{@code targetGround}).
 * <p>
 * <b>Состав отряда.</b> Отряд фиксируется в момент включения режима: в него попадают ТОЛЬКО юниты, выбранные в эту
 * секунду. Выбранные позже (при уже включённом режиме) не подключаются; чтобы добавить их, режим выключают и включают
 * снова. Юнит выходит из отряда насовсем при ручном приказе ({@link ManualControl}) или гибели; когда отряд опустел,
 * режим выключается сам. Выключение режима останавливает отряд.
 * <p>
 * Ограничения (честно): ходьба наземных юнитов проверяется только по проходимости точек входа/выхода, а не полным
 * путём; обычный приказ движения не мешает юниту стрелять, но юнит с дальностью больше турельной может начать
 * расстреливать её сам; «заряжена ли турель» берётся из синхронизированного {@code hasAmmo()}; энергетические турели
 * не разряжаются (боезапаса нет), но учитываются как опасные зоны.
 * <p>
 * Настройки (секция Sonka Extras): {@link #repairKey} - лечить сильно побитых у ближайшей ремонтной башни и
 * возвращать в строй; {@link #cyclone29Key} - для этой команды считать радиус циклона не меньше 29 блоков
 * (циклоны с аутренжем-патчем, дальность которых клиент не видит).
 */
public final class TurretDrain{
    public static final String repairKey = "sonka-turretdrain-repair", cyclone29Key = "sonka-turretdrain-cyclone29";

    /** Период пересмотра отряда/обстановки. */
    private static final float SCAN_TICKS = 15f;
    /** Не пересчитывать план одной категории чаще (турели мерцают на границе «есть/нет патрона»). */
    private static final long REPLAN_MIN_MS = 1000L;
    /** Сколько ждать после отправки, прежде чем считать, что приказ потерян, и слать заново. */
    private static final long RESEND_GRACE_MS = 3000L;
    /** Сколько приказов уходит за тик (смены фаз у большого отряда могут совпасть; не упираемся в антиспам сервера). */
    private static final int PER_TICK = 20;

    /** Глубина захода в зону = скорость * это (чтобы турель успела выбрать цель: у неё интервал поиска 20 тиков), в пределах [MIN, MAX]. */
    private static final float DIP_PER_SPEED = 18f, DIP_MIN = 8f, DIP_MAX = 48f;
    /** Насколько точка выхода дальше радиуса поражения (с запасом на неточность прибытия). */
    private static final float RETREAT = 24f;
    /**
     * Запас к времени полёта снаряда (тиков): турель может выстрелить и в те тики, пока юнит ещё выходит из зоны.
     * Ожидание отсчитывается от прибытия в точку входа.
     */
    private static final float WAIT_MARGIN = 20f;
    /** Заход считается состоявшимся на эту долю глубины P (юнит гасит скорость не мгновенно, а ждать точного попадания не нужно). */
    private static final float DIP_REACHED = 0.75f;
    /** Выход считается состоявшимся, когда юнит дальше границы собственной зоны турели на столько (px) или у Q ближе 10 px. */
    private static final float OUT_REACHED = 18f;
    /** Юнит не приближается к цели фазы дольше этого (тиков) - фаза пропускается (застрял, цель недостижима). */
    private static final float STUCK_TICKS = 240f;
    /** Шаг перебора направлений захода вокруг турели и максимум перебора (до разворота на 180). */
    private static final float ANGLE_STEP = 15f, SPREAD_STEP = 12f;

    /** Ниже этой доли здоровья - летим лечиться; здоров - возвращаемся. */
    private static final float REPAIR_LOW = 0.5f, REPAIR_OK = 0.95f;
    /** Лечение не дало результата за это время - бросаем и не пробуем {@link #REPAIR_BAN_MS}. */
    private static final long REPAIR_TIMEOUT_MS = 45_000L, REPAIR_BAN_MS = 60_000L;

    private static final int MODE_NONE = 0, MODE_CYCLE = 1, MODE_MOVE = 2;
    private static final int PH_IN = 0, PH_OUT = 1, PH_WAIT = 2;

    private static boolean active, wasActive;
    private static float timer;

    private static final IntMap<Member> squad = new IntMap<>();
    /** Очередь приказов на отправку (сглаживает всплески смены фаз). */
    private static final Seq<Order> outbox = new Seq<>();
    /** Для теста: подменяет выделение игрока. */
    static Seq<Unit> testSelection;

    @SuppressWarnings("unchecked")
    private static final Seq<Zone>[] zones = new Seq[]{new Seq<Zone>(), new Seq<Zone>()};
    private static final long[] lastSig = new long[2], lastReplan = new long[2];
    private static final Seq<Unit> catUnits = new Seq<>();
    private static final Seq<Zone> targets = new Seq<>();
    private static final IntSeq tmpIds = new IntSeq(), patrolOff = new IntSeq();

    private TurretDrain(){
    }

    /** Приказ «идти в точку» из очереди отправки. */
    private static final class Order{
        final int id;
        final float x, y;

        Order(int id, float x, float y){
            this.id = id;
            this.x = x;
            this.y = y;
        }
    }

    /** Состояние одного юнита отряда. */
    static final class Member{
        final long joinedAt = Time.millis();
        /** План: MODE_NONE - ничего не делаем, CYCLE - ходим фазами IN/OUT/WAIT вокруг turret, MOVE - стоим в точке P (лечение/отход). */
        int mode = MODE_NONE;
        int turret = -1;
        /** Точка входа P (она же цель режима MOVE) и точка выхода Q. */
        float px, py, qx, qy;
        /** Турель цикла: центр, эффективный радиус зоны, глубина захода и время полёта её снаряда (тиков). */
        float zx, zy, zr, dip, flight;
        /** Фаза цикла и её признаки: когда юнит прибыл к P и пошёл на выход (Time.time), прогресс подхода к цели фазы. */
        int phase = PH_IN;
        float leftAt, bestDist, progressAt;
        /** План пересчитан под текущую подпись обстановки. */
        boolean evaluated;
        /** Приказ текущей фазы надо (пере)отправить; sentAt - когда последний раз отправляли (0 = ещё не отправляли). */
        boolean needSend;
        long sentAt;
        /** Лечение. */
        boolean repairing;
        int repairPos = -1;
        long repairSince, repairBanUntil;
        float repairStartHp;

        void beginPhase(int ph){
            phase = ph;
            bestDist = Float.MAX_VALUE;
            progressAt = Time.time;
        }
    }

    /** Опасная для категории турель. */
    static final class Zone{
        float x, y, r;
        int pos;
        boolean drain;
        /** Сколько тиков снаряд текущего боезапаса летит до границы настоящей (не раздутой настройкой) дальности; 0 - мгновенный/неизвестно. */
        float flight;
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

    /** Редкий проход планировщика по уже собранному отряду: обстановка, лечение, план, потерянные приказы. */
    static void scan(long now){
        if(squad.isEmpty()) return;

        buildZones(Core.settings.getBool(cyclone29Key, false));
        handleRepair(Core.settings.getBool(repairKey, true), now);
        for(int c = 0; c < 2; c++) plan(c, now);
        checkLost(now);
        sendPending(now);
    }

    /** Покадровая часть: смена фаз цикла по положению юнитов и отправка накопившихся приказов. */
    static void tick(long now){
        if(squad.isEmpty()) return;
        cycle(now);
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

    /** Для теста: полный сброс состояния режима. */
    static void resetForTest(){
        reset();
        testSelection = null;
        timer = 0f;
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
    private static void prune(){
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
        squad.clear();
        lastSig[0] = lastSig[1] = 0;
    }

    // ---------------------------------------------------------------- обстановка

    /** Собирает опасные зоны (вражеские работающие турели, видимые нам) отдельно для воздуха [0] и земли [1]. */
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
            //разряжаем только тех, у кого есть боезапас, который тратится выстрелами (энергетические - нет)
            z.drain = (b.block instanceof ItemTurret || b.block instanceof LiquidTurret) && b instanceof TurretBuild tb && tb.hasAmmo();
            z.flight = z.drain ? flightTicks((TurretBuild)b, t.range()) : 0f;

            if(t.targetAir()) zones[0].add(z);
            if(t.targetGround()) zones[1].add(z);
        }
    }

    /** Время полёта снаряда текущего боезапаса на дальность range (px); артиллерия летит ровно до цели, прочие - не дольше жизни пули. */
    private static float flightTicks(TurretBuild tb, float range){
        BulletType bt = tb.peekAmmo();
        if(bt == null || bt.speed < 0.1f) return 0f; //лучи/мгновенные: ждать нечего
        float t = range / bt.speed;
        if(!bt.scaleLife) t = Math.min(t, bt.lifetime);
        return t;
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
        m.needSend = true;
        m.evaluated = true;
    }

    // ---------------------------------------------------------------- планирование

    private static void plan(int c, long now){
        catUnits.clear();
        boolean unevaluated = false;
        long sig = 0x9E3779B97F4A7C15L;

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
            sig += hash(z.pos, Math.round(z.r), z.drain ? 1 : 0, 13 + Math.round(z.flight) * 31);
        }

        if(sig == lastSig[c] && !unevaluated) return;
        if(now - lastReplan[c] < REPLAN_MIN_MS) return;
        lastSig[c] = sig;
        lastReplan[c] = now;

        targets.clear();
        for(int i = 0; i < zs.size; i++) if(zs.get(i).drain) targets.add(zs.get(i));

        catUnits.sort((a, b) -> Integer.compare(a.id, b.id));
        int[] load = new int[targets.size];

        for(Unit u : catUnits){
            Member m = squad.get(u.id);
            boolean planned = false;

            //кандидаты по возрастанию стоимости: близко к юниту, не перегружено другими, бонус за прежнюю цель (без метаний)
            boolean[] tried = new boolean[targets.size];
            for(int attempt = 0; attempt < Math.min(targets.size, 6) && !planned; attempt++){
                int best = -1;
                float bestCost = Float.MAX_VALUE;
                for(int ti = 0; ti < targets.size; ti++){
                    if(tried[ti]) continue;
                    Zone z = targets.get(ti);
                    float cost = Mathf.dst(u.x, u.y, z.x, z.y) / tilesize + 25f * load[ti] - (z.pos == m.turret ? 40f : 0f);
                    if(cost < bestCost){
                        bestCost = cost;
                        best = ti;
                    }
                }
                if(best < 0) break;
                tried[best] = true;

                if(geometry(u, m, targets.get(best), load[best], zs)){
                    load[best]++;
                    planned = true;
                }
            }

            if(!planned) parkOrIdle(u, m);
            m.evaluated = true;
        }
    }

    /**
     * Точки входа P / выхода Q для юнита у турели z. Юниты одной турели расходятся веером (k - номер в её группе),
     * чтобы не лететь кучей под один сплэш. Направление ищется от «стороны юнита» с перебором углов по возрастанию
     * отклонения: нужна проходимая точка Q вне зон ВСЕХ опасных турелей, а P - в зонах как можно меньшего числа
     * чужих турелей. false - подходящего направления нет (юнит попробует другую турель).
     */
    private static boolean geometry(Unit u, Member m, Zone z, int k, Seq<Zone> all){
        float hs = u.hitSize / 2f;
        float rEff = z.r + hs;
        float dip = Mathf.clamp(u.type.speed * DIP_PER_SPEED, DIP_MIN, DIP_MAX);

        float spread = ((k + 1) / 2) * SPREAD_STEP * (k % 2 == 1 ? 1f : -1f);
        float base = Angles.angle(z.x, z.y, u.x, u.y) + spread;

        float bestCost = Float.MAX_VALUE, bpx = 0, bpy = 0, bqx = 0, bqy = 0;
        int steps = (int)(360f / ANGLE_STEP);
        for(int i = 0; i <= steps; i++){
            float delta = ((i + 1) / 2) * ANGLE_STEP * (i % 2 == 1 ? 1f : -1f);
            float ang = base + delta;
            float cx = Mathf.cosDeg(ang), cy = Mathf.sinDeg(ang);

            float px = z.x + cx * (rEff - dip), py = z.y + cy * (rEff - dip);
            float qx = z.x + cx * (rEff + RETREAT), qy = z.y + cy * (rEff + RETREAT);
            if(!standable(u, px, py) || !standable(u, qx, qy)) continue;
            if(coveredBy(all, qx, qy, hs + 8f, null) > 0) continue;

            float cost = coveredBy(all, px, py, hs, z) * 1000f + Math.abs(delta);
            if(cost < bestCost){
                bestCost = cost;
                bpx = px;
                bpy = py;
                bqx = qx;
                bqy = qy;
                if(cost < 1000f) break; //чужих турелей в точке входа нет, а отклонение растёт - лучшего уже не будет
            }
        }
        if(bestCost == Float.MAX_VALUE) return false;

        boolean same = m.mode == MODE_CYCLE && m.turret == z.pos && m.sentAt != 0
            && Mathf.dst(m.px, m.py, bpx, bpy) < 4f && Mathf.dst(m.qx, m.qy, bqx, bqy) < 4f;
        m.mode = MODE_CYCLE;
        m.turret = z.pos;
        m.px = bpx;
        m.py = bpy;
        m.qx = bqx;
        m.qy = bqy;
        m.zx = z.x;
        m.zy = z.y;
        m.zr = rEff;
        m.dip = dip;
        m.flight = z.flight;
        if(!same){
            m.beginPhase(PH_IN);
            m.needSend = true;
        }
        return true;
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

    /**
     * Разряжать нечего (или не нашлось безопасного захода): юнита, которого мы уже посылали, уводим из зоны к точке
     * выхода (она была безопасной на момент плана) вместо вечного курсирования по устаревшему маршруту.
     */
    private static void parkOrIdle(Unit u, Member m){
        if(m.mode == MODE_CYCLE && m.sentAt != 0){
            m.mode = MODE_MOVE;
            m.px = m.qx;
            m.py = m.qy;
            m.turret = -1;
            m.needSend = true;
        }else if(m.mode != MODE_MOVE){
            m.mode = MODE_NONE;
            m.turret = -1;
        }
    }

    // ---------------------------------------------------------------- цикл и отправка

    /** Куда сейчас должен идти юнит по плану; false - ничего ждать (WAIT/нет плана). */
    private static boolean target(Member m, Vec2 out){
        if(m.mode == MODE_MOVE){
            out.set(m.px, m.py);
            return true;
        }
        if(m.mode != MODE_CYCLE || m.phase == PH_WAIT) return false;
        if(m.phase == PH_IN) out.set(m.px, m.py);
        else out.set(m.qx, m.qy);
        return true;
    }

    /** Нет приближения к цели фазы дольше STUCK_TICKS: юнит упёрся/цель недостижима - пропускаем фазу, а не висим. */
    private static boolean stuck(Member m, Unit u, float tx, float ty){
        float d = Mathf.dst(u.x, u.y, tx, ty);
        if(d < m.bestDist - 4f){
            m.bestDist = d;
            m.progressAt = Time.time;
        }
        return Time.time - m.progressAt > STUCK_TICKS;
    }

    /**
     * Смена фаз цикла по положению юнита (каждый тик, дёшево): IN - дошёл до глубины захода -> приказ к Q;
     * OUT - вышел за зону -> ждёт; WAIT - с момента отхода прошло время полёта снаряда -> приказ к P.
     */
    private static void cycle(long now){
        for(var e : squad){
            Member m = e.value;
            if(m.mode != MODE_CYCLE || m.repairing || m.needSend || m.sentAt == 0) continue;
            Unit u = Groups.unit.getByID(e.key);
            if(u == null) continue;
            float d = Mathf.dst(u.x, u.y, m.zx, m.zy);

            if(m.phase == PH_IN){
                if(d <= m.zr - m.dip * DIP_REACHED || u.within(m.px, m.py, 6f) || stuck(m, u, m.px, m.py)){
                    m.leftAt = Time.time;
                    m.beginPhase(PH_OUT);
                    m.sentAt = now;
                    outbox.add(new Order(u.id, m.qx, m.qy));
                }
            }else if(m.phase == PH_OUT){
                if(d >= m.zr + OUT_REACHED || u.within(m.qx, m.qy, 10f) || stuck(m, u, m.qx, m.qy)){
                    m.beginPhase(PH_WAIT);
                }
            }else if(Time.time - m.leftAt >= m.flight + WAIT_MARGIN){
                m.beginPhase(PH_IN);
                m.sentAt = now;
                outbox.add(new Order(u.id, m.px, m.py));
            }
        }
    }

    /** Приказ мог потеряться (рейт-лимит сервера, обрыв): юнит без цели и вдали от точки своей фазы - шлём заново. */
    private static void checkLost(long now){
        Vec2 t = new Vec2();
        for(var e : squad){
            Member m = e.value;
            if(m.needSend || m.sentAt == 0 || now - m.sentAt < RESEND_GRACE_MS) continue;
            Unit u = Groups.unit.getByID(e.key);
            if(u == null || !(u.controller() instanceof CommandAI ai)) continue;
            if(!target(m, t)) continue;
            if(ai.targetPos == null && !u.within(t.x, t.y, 6f * tilesize)) m.needSend = true;
        }
    }

    /** Отправка плана тем, у кого он изменился: снять патруль (если был) и приказать идти в точку текущей фазы. */
    private static void sendPending(long now){
        patrolOff.clear();
        Vec2 t = new Vec2();
        for(var e : squad){
            Member m = e.value;
            if(!m.needSend) continue;
            Unit u = Groups.unit.getByID(e.key);
            if(u == null || !(u.controller() instanceof CommandAI ai)) continue;
            m.needSend = false;
            m.sentAt = now;
            if(ai.hasStance(UnitStance.patrol)) patrolOff.add(e.key);
            if(target(m, t)) outbox.add(new Order(e.key, t.x, t.y));
        }
        //патруль снимаем ДО приказа «идти» (иначе он успел бы положить старую точку в очередь); outbox уходит позже, в tick
        if(patrolOff.size > 0) sendStance(patrolOff, UnitStance.patrol, false);
    }

    /** Отправляет до limit приказов из очереди; приказы тех, кто уже вышел из отряда, не шлём (не перебиваем ручное управление). */
    private static void drain(int limit){
        if(outbox.isEmpty()) return;
        if(player == null || state.isMenu()){
            outbox.clear();
            return;
        }
        while(limit > 0 && !outbox.isEmpty()){
            Order o = outbox.remove(0);
            if(!squad.containsKey(o.id)) continue;
            limit--;
            Call.commandUnits(player, new int[]{o.id}, null, null, new Vec2(o.x, o.y), false, true);
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
