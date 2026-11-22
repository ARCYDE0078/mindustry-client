package qol.crawlercontrol;

import arc.Events;
import arc.math.Mathf;
import arc.math.geom.Point2;
import arc.struct.IntFloatMap;
import arc.struct.IntMap;
import arc.struct.IntSeq;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Time;
import mindustry.ai.UnitStance;
import mindustry.ai.types.CommandAI;
import mindustry.content.UnitTypes;
import mindustry.entities.bullet.BulletType;
import mindustry.game.EventType.Trigger;
import mindustry.gen.Building;
import mindustry.gen.Call;
import mindustry.gen.Unit;
import mindustry.ui.dialogs.SettingsMenuDialog.SettingsTable;
import mindustry.core.World;
import qol.core.Feature;

import static mindustry.Vars.*;

/**
 * Crawlers ({@link UnitTypes#crawler}) explode on whatever enemy is nearest the instant one's in range -
 * fine for their default {@code SuicideAI}, but a crawler you've commanded to travel somewhere detonates
 * on the first enemy it passes on the way, never reaching the target you actually sent it at.
 * <p>
 * Engine fact: {@link CommandAI#shouldFire} is {@code !hasStance(UnitStance.holdFire)} - a real vanilla
 * stance (same one the in-game stance UI toggles), not a hack, so holding it over the network via
 * {@link Call#setUnitStance} suppresses the self-destruct weapon exactly like it would for any other
 * unit's normal weapons, and releasing it lets the unit's own weapon logic take over again. Crawlers
 * running the vanilla {@code SuicideAI} (i.e. never commanded) are left alone entirely.
 * <p>
 * <b>Приказ «атаковать блок» - групповой взрыв.</b> Раньше каждый ползун отпускался сам, как только подходил
 * к цели, и рвался в одиночку: один 80-урончик по блоку в сотни/тысячи прочности = впустую потерянный ползун.
 * Теперь ползуны, которым дан приказ атаковать ОДИН И ТОТ ЖЕ блок, образуют группу и ждут (держат holdFire)
 * у цели, пока не выполнится одно из условий:
 * <ol>
 * <li>суммарный ожидаемый урон от уже подошедших ползунов (с запасом {@link #SAFETY}) гарантированно
 * добивает прочность блока - тогда подошедшие рвутся все вместе;</li>
 * <li>к цели прибыли ВСЕ ползуны группы (больше ждать некого - рвёмся тем, что есть);</li>
 * <li>тайм-аут {@link #STALL_TICKS}: число подошедших давно не растёт (кто-то застрял/сбит) - иначе группа
 * ждала бы вечно.</li>
 * </ol>
 * Как только группа «отпущена», это запоминается ({@link #latched}): первые взорвавшиеся уменьшают сумму
 * подошедших, и без защёлки оставшиеся ползуны снова встали бы на hold на полпути к удару.
 * <p>
 * Урон считается копией {@code Damage.tileDamage} (лучи по тайлам с затуханием от центра и поглощением
 * зданиями на пути), а не прикидкой «80 × число»: сплэш по зданию бьёт КАЖДЫЙ задетый тайл отдельно (большой блок
 * получает несколько ударов, но с затуханием и экранированием соседями), а прочность делится на blockHealth
 * правил. Не учитывается то, чего клиент не знает наперёд: ремонт мендерами, щиты, чужие выстрелы по блоку.
 */
public class CrawlerControlFeature implements Feature{
    /* перф: сканировать всех краулеров каждый кадр незачем - троттлим по образцу соседних scan-фич
     * (CoreHeal/AssistShare), но короче (10 тиков, ~0.17с): смена stance чувствительна к задержке -
     * свежескомандованный краулер не должен успеть подорваться до первого скана */
    static final float SCAN_INTERVAL_TICKS = 10f;
    /** Запас к ожидаемому урону: оценка не знает про мендеры/щиты/разброс позиций в момент взрыва. */
    static final float SAFETY = 1.25f;
    /** Сколько тиков число подошедших ползунов не растёт, после чего группа рвётся тем, что есть (15 с). */
    static final float STALL_TICKS = 900f;
    /** Сколько тиков защёлка живёт без единого ползуна у цели (потом группа оценивается заново). */
    static final float LATCH_TICKS = 300f;
    /** Радиус взрыва в тайлах и базовый урон по зданию - берём из самого оружия, чтобы не разойтись с патчами. */
    static final float FALLBACK_RADIUS = 44f, FALLBACK_DAMAGE = 80f * 0.68f;

    float scanTimer = 0f;

    final IntSeq toHold = new IntSeq();
    final IntSeq toRelease = new IntSeq();

    /** id блока-цели -> состояние группы (прибытие/тайм-аут/защёлка). Чистится от мёртвых целей каждый скан. */
    final IntMap<Group> groups = new IntMap<>();
    final ObjectMap<Building, Seq<Unit>> byTarget = new ObjectMap<>();
    final Seq<Unit> loose = new Seq<>();
    /** Для tileDamage-копии: урон по тайлам (переиспользуем, чтобы не плодить мусор). */
    final IntFloatMap damages = new IntFloatMap();

    static class Group{
        boolean latched;
        float lastGrowTime = Time.time, latchTime;
        int lastArrived = -1;
    }

    @Override
    public String id(){
        return "crawler-control";
    }

    @Override
    public String titleKey(){
        return "qol.feature.crawler-control.title";
    }

    @Override
    public void init(){
        Events.run(Trigger.update, this::update);
    }

    @Override
    public void buildSettings(SettingsTable table){
    }

    void update(){
        if(!isEnabled() || !state.isGame() || player == null || player.team().data() == null) return;

        scanTimer += Time.delta;
        if(scanTimer < SCAN_INTERVAL_TICKS) return;
        scanTimer = 0f;

        toHold.clear();
        toRelease.clear();

        //getUnits(type) returns null, not an empty Seq, for a type this team has never had a unit of
        Seq<Unit> crawlers = player.team().data().getUnits(UnitTypes.crawler);
        if(crawlers == null){
            groups.clear();
            return;
        }

        //1) раскладываем: «атакую конкретный вражеский блок» -> в группу этого блока; всё остальное - как раньше, поштучно
        byTarget.clear();
        loose.clear();
        for(Unit u : crawlers){
            if(!(u.controller() instanceof CommandAI ai)) continue;

            if(ai.attackTarget instanceof Building b && b.isValid() && b.team != u.team){
                Seq<Unit> list = byTarget.get(b);
                if(list == null) byTarget.put(b, list = new Seq<>());
                list.add(u);
            }else{
                loose.add(u);
            }
        }

        for(Unit u : loose){
            CommandAI ai = (CommandAI)u.controller();
            boolean traveling = ai.targetPos != null || ai.attackTarget != null;
            boolean nearAttackTarget = ai.attackTarget != null && u.within(ai.attackTarget, u.range());
            apply(u, ai, traveling && !nearAttackTarget);
        }

        //2) группы по блокам
        for(var e : byTarget){
            processGroup(e.key, e.value);
        }

        //3) забываем группы, чьей цели больше нет в приказах (взорвана/отменено/сменили цель)
        if(groups.size > 0){
            var it = groups.iterator();
            while(it.hasNext()){
                var g = it.next();
                Building target = world.build(Point2.x(g.key), Point2.y(g.key));
                boolean alive = false;
                if(target != null) alive = byTarget.containsKey(target);
                if(!alive) it.remove();
            }
        }

        if(toHold.size > 0) Call.setUnitStance(player, toHold.toArray(), UnitStance.holdFire, true);
        if(toRelease.size > 0) Call.setUnitStance(player, toRelease.toArray(), UnitStance.holdFire, false);
    }

    /** Ставит/снимает holdFire только при расхождении с текущим состоянием (лишних пакетов не шлём). */
    void apply(Unit u, CommandAI ai, boolean shouldHold){
        boolean holding = ai.hasStance(UnitStance.holdFire);
        if(shouldHold && !holding) toHold.add(u.id());
        else if(!shouldHold && holding) toRelease.add(u.id());
    }

    void processGroup(Building target, Seq<Unit> units){
        //ключ группы - позиция блока (id у Building нет; позиция уникальна и стабильна, пока блок жив)
        int key = target.tile.pos();
        Group g = groups.get(key);
        if(g == null) groups.put(key, g = new Group());

        //кто уже у цели; считаем ожидаемый урон от каждого подошедшего
        float need = target.health * SAFETY, expected = 0f;
        int arrived = 0;
        boolean[] isArrived = new boolean[units.size];
        for(int i = 0; i < units.size; i++){
            Unit u = units.get(i);
            if(u.within(target, u.range())){
                isArrived[i] = true;
                arrived++;
                expected += estimate(u, target);
            }
        }

        if(arrived != g.lastArrived){
            //растёт число подошедших - таймер «застоя» сбрасывается; падает (взорвались/погибли) - тоже не застой
            g.lastArrived = arrived;
            g.lastGrowTime = Time.time;
        }

        boolean enough = arrived > 0 && expected >= need;
        boolean all = arrived == units.size;
        boolean stalled = arrived > 0 && Time.time - g.lastGrowTime >= STALL_TICKS;

        if(!g.latched && (enough || all || stalled)){
            g.latched = true;
        }
        if(g.latched){
            //защёлка держится, пока у цели кто-то есть; если все ушли/пропали на LATCH_TICKS - оцениваем заново
            if(arrived > 0) g.latchTime = Time.time;
            else if(Time.time - g.latchTime > LATCH_TICKS) g.latched = false;
        }

        for(int i = 0; i < units.size; i++){
            Unit u = units.get(i);
            CommandAI ai = (CommandAI)u.controller();
            //отпускаем только подошедших: ещё едущие продолжают держать огонь (иначе рванут на первом встречном)
            boolean fire = g.latched && isArrived[i];
            apply(u, ai, !fire);
        }
    }

    /**
     * Ожидаемый урон взрыва ползуна, стоящего там, где он стоит сейчас, по блоку-цели - в единицах прочности блока
     * (т.е. уже поделённый на blockHealth правил). Копия логики {@code Damage.tileDamage}; отличие одно - здесь
     * ничего не наносится, а суммируется урон по тайлам именно ЭТОЙ цели.
     */
    float estimate(Unit u, Building target){
        BulletType b = UnitTypes.crawler.weapons.isEmpty() ? null : UnitTypes.crawler.weapons.first().bullet;
        float radiusWorld = b != null && b.splashDamageRadius > 0 ? b.splashDamageRadius : FALLBACK_RADIUS;
        float base = b != null && b.splashDamage > 0 ? b.splashDamage * b.buildingDamageMultiplier : FALLBACK_DAMAGE;
        base *= u.damageMultiplier() * state.rules.unitDamage(u.team);

        int x = Mathf.clamp(World.toTile(u.x), -100, world.width() + 100), y = Mathf.clamp(World.toTile(u.y), -100, world.height() + 100);
        float radius = Math.min(radiusWorld / tilesize, 100f), rad2 = radius * radius;

        //взрыв внутри многоклеточного блока: ваниль бьёт сразу «стороной» блока, без лучей
        Building in = world.build(x, y);
        float dm = Math.max(state.rules.blockHealth(target.team), 0.0001f);
        if(in != null && in.team != u.team && in.block.size > 1 && in.health > base){
            return in == target ? base * Math.min(in.block.size, radius * 0.4f) / dm : 0f;
        }

        int rays = Mathf.ceil(radius * Mathf.PI2);
        double spacing = Math.PI * 2.0 / rays;
        damages.clear();

        for(int i = 0; i <= rays; i++){
            float dealt = 0f;
            int sx = x, sy = y;
            int endX = x + (int)(Math.cos(spacing * i) * radius), endY = y + (int)(Math.sin(spacing * i) * radius);

            int xDist = Math.abs(endX - sx);
            int yDist = -Math.abs(endY - sy);
            int xStep = (sx < endX ? +1 : -1);
            int yStep = (sy < endY ? +1 : -1);
            int error = xDist + yDist;

            while(sx != endX || sy != endY){
                Building build = world.build(sx, sy);
                if(build != null && build.team != u.team){
                    float edgeScale = 0.6f;
                    float mult = (1f - (Mathf.dst2(sx, sy, x, y) / rad2) + edgeScale) / (1f + edgeScale);
                    float next = base * mult - dealt;
                    int p = Point2.pack(sx, sy);
                    damages.put(p, Math.max(damages.get(p), next));
                    dealt += build.health;

                    if(next - dealt <= 0) break;
                }

                if(2 * error - yDist > xDist - 2 * error){
                    error += yDist;
                    sx += xStep;
                }else{
                    error += xDist;
                    sy += yStep;
                }
            }
        }

        float sum = 0f;
        for(var e : damages){
            if(world.build(Point2.x(e.key), Point2.y(e.key)) == target && e.value > 0) sum += e.value;
        }
        return sum / dm;
    }
}
