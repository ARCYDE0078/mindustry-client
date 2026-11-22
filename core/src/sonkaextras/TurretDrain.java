package sonkaextras;

import arc.Core;
import arc.Events;
import arc.math.Angles;
import arc.math.Mathf;
import arc.math.geom.Vec2;
import arc.struct.IntMap;
import arc.struct.IntSeq;
import arc.struct.IntSet;
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
 * турели, на миг заходит в зону её поражения (турель тратит боезапас на выстрелы по нему) и сразу уходит назад;
 * цикл повторяется, пока турель не опустеет.
 * <p>
 * <b>Почему клиентский режим, а не настоящая UnitCommand.</b> Новая команда в контенте игры сдвинула бы id контента
 * и сломала совместимость с ванильными серверами, а клиенту, по сути, нужна только раздача обычных приказов. Поэтому
 * режим живёт на клиенте (как {@link AntiSplash}) и общается с сервером ванильными RPC.
 * <p>
 * <b>Зацикливание ванильным патрулём.</b> Каждому юниту отправляется пара точек: «вход» P (чуть внутри зоны турели) и
 * «выход» Q (за её радиусом). Первая идёт обычным приказом, вторая - в очередь, затем включается stance
 * {@link UnitStance#patrol}: по прибытии CommandAI сам кладёт пройденную точку в конец очереди, так что юнит курсирует
 * P-Q бесконечно без единого дальнейшего пакета (клиент шлёт только при смене плана).
 * <p>
 * <b>Перепланирование.</b> Каждые {@link #SCAN_TICKS} тиков считается «подпись» обстановки (какие турели опасны, какие
 * ещё заряжены, кто в отряде). Изменилась (турель разрядилась/уничтожена, враг поставил новую) - план пересчитывается
 * целиком, а юнитам отправляются только изменившиеся приказы. Воздух и земля считаются отдельно: турель опасна для
 * категории, только если умеет по ней стрелять ({@code targetAir}/{@code targetGround}).
 * <p>
 * <b>Состав отряда.</b> Юниты входят в отряд, когда выбраны при включённом режиме; выходят при ручном приказе
 * ({@link ManualControl}) и снова входят только после снятия/нового выбора. Выключение режима снимает патруль и
 * останавливает отряд.
 * <p>
 * Ограничения (честно): ходьба наземных юнитов проверяется только по проходимости точек входа/выхода, а не полным
 * путём; патруль CommandAI останавливается, когда юнит видит цель в {@code range - 2} (юниты с большей дальностью, чем
 * у турели, встанут и начнут её расстреливать сами); «заряжена ли турель» берётся из синхронизированного
 * {@code hasAmmo()}; энергетические турели не разряжаются (боезапаса нет), но учитываются как опасные зоны.
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
    /** Сколько юнитов обслуживать за тик (по 2-3 пакета на юнита; не упираемся в антиспам сервера). */
    private static final int PER_TICK = 20;

    /** Глубина захода в зону = скорость * это (чтобы турель успела выбрать цель: у неё интервал поиска 20 тиков), в пределах [MIN, MAX]. */
    private static final float DIP_PER_SPEED = 18f, DIP_MIN = 8f, DIP_MAX = 48f;
    /** Насколько точка выхода дальше радиуса поражения (с запасом на неточность прибытия). */
    private static final float RETREAT = 24f;
    /** Шаг перебора направлений захода вокруг турели и максимум перебора (до разворота на 180). */
    private static final float ANGLE_STEP = 15f, SPREAD_STEP = 12f;

    /** Ниже этой доли здоровья - летим лечиться; здоров - возвращаемся. */
    private static final float REPAIR_LOW = 0.5f, REPAIR_OK = 0.95f;
    /** Лечение не дало результата за это время - бросаем и не пробуем {@link #REPAIR_BAN_MS}. */
    private static final long REPAIR_TIMEOUT_MS = 45_000L, REPAIR_BAN_MS = 60_000L;

    private static final int MODE_NONE = 0, MODE_PATROL = 1, MODE_MOVE = 2;

    private static boolean active, wasActive;
    private static float timer;

    private static final IntMap<Member> squad = new IntMap<>();
    /** Выбранные юниты, ушедшие из отряда по ручному приказу: не берём обратно, пока их не снимут с выделения. */
    private static final IntSet ignored = new IntSet();
    private static final IntSet selectedNow = new IntSet();

    @SuppressWarnings("unchecked")
    private static final Seq<Zone>[] zones = new Seq[]{new Seq<Zone>(), new Seq<Zone>()};
    private static final long[] lastSig = new long[2], lastReplan = new long[2];
    private static final Seq<Unit> catUnits = new Seq<>();
    private static final Seq<Zone> targets = new Seq<>();
    private static final IntSeq tmpIds = new IntSeq(), patrolOn = new IntSeq(), patrolOff = new IntSeq();

    private TurretDrain(){
    }

    /** Состояние одного юнита отряда. */
    static final class Member{
        final long joinedAt = Time.millis();
        /** План: MODE_NONE - ничего не делаем, PATROL - курсируем P-Q вокруг turret, MOVE - стоим в точке P (лечение/отход). */
        int mode = MODE_NONE;
        int turret = -1;
        float px, py, qx, qy;
        /** План пересчитан под текущую подпись обстановки. */
        boolean evaluated;
        /** План надо (пере)отправить; sentAt - когда последний раз отправляли (0 = ещё не отправляли). */
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

    /** Кнопка в панели команд. Режим не сохраняется между запусками: иначе выбранные юниты сами уходили бы в бой. */
    public static boolean toggle(){
        active = !active;
        timer = SCAN_TICKS;
        if(ui != null) ui.showInfoFade(Core.bundle.get(active ? "client.sonka.turretdrain.on" : "client.sonka.turretdrain.off"));
        return active;
    }

    private static void reset(){
        active = wasActive = false;
        squad.clear();
        ignored.clear();
        lastSig[0] = lastSig[1] = 0;
        lastReplan[0] = lastReplan[1] = 0;
    }

    private static void update(){
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

        timer += Time.delta;
        if(timer < SCAN_TICKS) return;
        timer = 0f;

        syncSquad();
        step(Time.millis());
    }

    /** Один проход планировщика по уже собранному отряду (вынесен из update, чтобы его можно было гонять в тесте без UI-выделения). */
    static void step(long now){
        if(squad.isEmpty()) return;

        buildZones(Core.settings.getBool(cyclone29Key, false));
        handleRepair(Core.settings.getBool(repairKey, true), now);
        for(int c = 0; c < 2; c++) plan(c, now);
        checkLost(now);
        sendPending(now);
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
        timer = 0f;
    }

    // ---------------------------------------------------------------- состав отряда

    private static void syncSquad(){
        selectedNow.clear();
        for(Unit u : control.input.selectedUnits){
            if(u == null || !u.isValid() || u.team != player.team() || !(u.controller() instanceof CommandAI)) continue;
            selectedNow.add(u.id);
            if(squad.containsKey(u.id) || ignored.contains(u.id)) continue;
            squad.put(u.id, new Member());
        }

        //«ушедшие по ручному приказу» возвращаются в игру, как только их сняли с выделения
        var it = ignored.iterator();
        while(it.hasNext){
            if(!selectedNow.contains(it.next())) it.remove();
        }

        tmpIds.clear();
        for(var e : squad){
            Unit u = Groups.unit.getByID(e.key);
            if(u == null || !u.isValid() || !(u.controller() instanceof CommandAI)){
                tmpIds.add(e.key);
            }else if(ManualControl.markedSince(e.key, e.value.joinedAt)){
                tmpIds.add(e.key);
                if(selectedNow.contains(e.key)) ignored.add(e.key);
            }
        }
        for(int i = 0; i < tmpIds.size; i++) squad.remove(tmpIds.get(i));
    }

    /** Выключение режима: снимаем патруль и останавливаем тех, кому что-то отправляли. */
    private static void release(){
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
        ignored.clear();
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
            sig += hash(z.pos, Math.round(z.r), z.drain ? 1 : 0, 13);
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

        boolean same = m.mode == MODE_PATROL && m.turret == z.pos && m.sentAt != 0
            && Mathf.dst(m.px, m.py, bpx, bpy) < 4f && Mathf.dst(m.qx, m.qy, bqx, bqy) < 4f;
        m.mode = MODE_PATROL;
        m.turret = z.pos;
        m.px = bpx;
        m.py = bpy;
        m.qx = bqx;
        m.qy = bqy;
        if(!same) m.needSend = true;
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
        if(m.mode == MODE_PATROL && m.sentAt != 0){
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

    // ---------------------------------------------------------------- отправка

    /** Приказ мог потеряться (рейт-лимит сервера, обрыв): патруль без stance/очереди или стоящий вдали от точки - шлём заново. */
    private static void checkLost(long now){
        for(var e : squad){
            Member m = e.value;
            if(m.needSend || m.sentAt == 0 || now - m.sentAt < RESEND_GRACE_MS) continue;
            Unit u = Groups.unit.getByID(e.key);
            if(u == null || !(u.controller() instanceof CommandAI ai)) continue;

            if(m.mode == MODE_PATROL){
                if(!ai.hasStance(UnitStance.patrol) || (ai.targetPos == null && ai.commandQueue.isEmpty())) m.needSend = true;
            }else if(m.mode == MODE_MOVE){
                if(ai.targetPos == null && !u.within(m.px, m.py, 6f * tilesize)) m.needSend = true;
            }
        }
    }

    private static void sendPending(long now){
        Seq<Unit> pending = new Seq<>();
        for(var e : squad){
            if(!e.value.needSend) continue;
            Unit u = Groups.unit.getByID(e.key);
            if(u != null && u.controller() instanceof CommandAI) pending.add(u);
        }
        if(pending.isEmpty()) return;

        patrolOn.clear();
        patrolOff.clear();
        for(Unit u : pending){
            Member m = squad.get(u.id);
            CommandAI ai = (CommandAI)u.controller();
            m.needSend = false;
            m.sentAt = now;
            if(m.mode == MODE_PATROL){
                if(!ai.hasStance(UnitStance.patrol)) patrolOn.add(u.id);
            }else if(ai.hasStance(UnitStance.patrol)){
                patrolOff.add(u.id);
            }
        }

        //стойку патруля снимаем ДО приказа «идти» (иначе патруль успел бы положить старую точку в очередь), включаем - после
        if(patrolOff.size > 0) sendStance(patrolOff, UnitStance.patrol, false);

        for(int i = 0; i < pending.size; i++){
            Unit u = pending.get(i);
            Member m = squad.get(u.id);
            final int id = u.id, mode = m.mode;
            final float px = m.px, py = m.py, qx = m.qx, qy = m.qy;
            float delay = (i / PER_TICK) * 1f;
            if(delay <= 0f) sendPlan(id, mode, px, py, qx, qy);
            else Time.run(delay, () -> sendPlan(id, mode, px, py, qx, qy));
        }

        if(patrolOn.size > 0){
            int[] ids = patrolOn.toArray();
            float delay = (pending.size / PER_TICK) * 1f + 1f;
            Time.run(delay, () -> {
                if(player == null || state.isMenu()) return;
                IntSeq seq = new IntSeq(ids);
                sendStance(seq, UnitStance.patrol, true);
            });
        }
    }

    private static void sendPlan(int id, int mode, float px, float py, float qx, float qy){
        if(player == null || state.isMenu() || mode == MODE_NONE) return;
        int[] one = {id};
        Call.commandUnits(player, one, null, null, new Vec2(px, py), false, true);
        if(mode == MODE_PATROL) Call.commandUnits(player, one, null, null, new Vec2(qx, qy), true, true);
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
