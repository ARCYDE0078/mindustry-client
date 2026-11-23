package sonkaextras;

import arc.Core;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.graphics.g2d.Lines;
import arc.math.Mathf;
import arc.struct.Seq;
import arc.util.Time;
import mindustry.client.ClientVars;
import mindustry.gen.Building;
import mindustry.graphics.Pal;
import mindustry.world.blocks.defense.turrets.BaseTurret.BaseTurretBuild;
import mindustry.world.blocks.defense.turrets.ItemTurret;
import mindustry.world.blocks.defense.turrets.LiquidTurret;
import mindustry.world.blocks.defense.turrets.PowerTurret;
import mindustry.world.blocks.defense.turrets.Turret;
import mindustry.world.blocks.defense.turrets.Turret.AmmoEntry;
import mindustry.world.blocks.defense.turrets.Turret.TurretBuild;
import mindustry.world.meta.BlockFlag;

import static mindustry.Vars.*;

/**
 * Общий для всех карт/миникарт код «радиус турели с цветом состояния»: цвет заливки зависит от того, запитана ли
 * турель и чем она заряжена (пункт 3 списка улучшений).
 * <ul>
 *   <li>красный - турели нужно питание, а его нет (или оно ниже 1%);</li>
 *   <li>серый - питание есть, но патронов нет;</li>
 *   <li>цвет предмета/жидкости - тип текущего боеприпаса (медь - рыжий, графит - светло-серый и т.д.);</li>
 *   <li>голубой (цвет энергии) - энергетическая турель с питанием, у неё патронов нет по определению.</li>
 * </ul>
 * Заливку радиуса в мире рисует MI2U ({@code RendererExt.drawTurretZone}, стиль цвета «Power / ammo»),
 * кружки на миникарте - {@link #drawOnMinimap}; цвет считается здесь в одном месте, чтобы они не расходились.
 */
public final class TurretRanges{
    public static final String minimapKey = "sonka-minimap-ranges", minimapEnemyKey = "sonka-minimap-ranges-enemy";

    private static final Color tmp = new Color(), tmpFill = new Color();

    private TurretRanges(){
    }

    /** Цвет состояния турели. Возвращает общий объект - не хранить, сразу копировать (set) во владеющий Color. */
    public static Color statusColor(BaseTurretBuild b){
        if(b.block.hasPower && b.block.consPower != null && (b.power == null || b.power.status < 0.01f)){
            return tmp.set(Pal.remove);
        }
        if(b instanceof TurretBuild tb){
            if(b.block instanceof PowerTurret) return tmp.set(Pal.power);
            if(tb instanceof LiquidTurret.LiquidTurretBuild lb){
                if(lb.liquids != null && lb.liquids.currentAmount() > 0.01f) return tmp.set(lb.liquids.current().color);
                return tmp.set(Color.gray);
            }
            if(!tb.hasAmmo()) return tmp.set(Color.gray);
            if(tb.ammo.size > 0){
                AmmoEntry e = tb.ammo.peek();
                if(e instanceof ItemTurret.ItemEntry ie){
                    //тёмные предметы (кремний, титан) на карте почти не видны - подмешиваем белое
                    return tmp.set(ie.item.color).lerp(Color.white, 0.3f);
                }
            }
        }
        return tmp.set(b.team.color);
    }

    /** Яркость цвета заливки относительно цвета состояния: заливка тёмная, чтобы наложения копились в «темнее», а не в «ярче». */
    private static final float FILL_DARKEN = 0.4f;

    /** Притушивает цвет состояния до цвета заливки (меняет и возвращает тот же объект, альфа = 1). */
    public static Color fillColor(Color c){
        return c.set(c.r * FILL_DARKEN, c.g * FILL_DARKEN, c.b * FILL_DARKEN, 1f);
    }

    public static final String dpsFillKey = "sonka-zone-dps-fill";
    /** Тумблер дедупликации радиусов (пункт 6): пунктир Foo / наведение EUI не рисуются там, где уже есть MI2U-зона. */
    public static final String dedupeKey = "sonka-range-dedupe";

    /**
     * Рисует ли MI2U-зона (заливка + контур) эту турель в мире: включены «Радиусы турелей», тип блока не отфильтрован,
     * включён HUD-переключатель её стороны (свои / вражеские - левый верхний угол) а при «только действующие» у неё есть
     * питание и боезапас. Единственное место этого условия - им пользуются и сама зона, и все остальные рисовальщики
     * радиусов турелей, чтобы они не накладывались друг на друга.
     */
    public static boolean zoneAllows(BaseTurretBuild t){
        var f = mi2u.graphics.RendererExt.filterTurretRangeZone;
        if(f == null || t.block.id >= f.length || !f[t.block.id]) return false;
        if(!sideShown(t)) return false;
        return !mi2u.graphics.RendererExt.turretZoneOnlyActive || active(t);
    }

    /** Своя ли турель (по команде игрока) - у своих и вражеских отдельные HUD-переключатели и отдельная нормировка динамической заливки. */
    public static boolean isOwn(BaseTurretBuild t){
        return player != null && t.team == player.team();
    }

    /** Включён ли HUD-переключатель стороны этой турели: зелёный - радиусы своих турелей, красный - вражеских. */
    public static boolean sideShown(BaseTurretBuild t){
        return isOwn(t) ? ClientVars.showingAllyTurrets : ClientVars.showingTurrets;
    }

    /**
     * Турель выключена логикой (процессор: {@code control enabled}), но патроны у неё есть - она не стреляет только
     * пока её не включат. Движок в этот момент обнуляет её эффективность ({@code canShoot()}/{@code estimateDps()} = 0),
     * поэтому для радиусов такая турель считается рабочей: радиус виден целиком и заливка считается по её настоящему DPS.
     */
    public static boolean disabledWithAmmo(BaseTurretBuild t){
        if(t.enabled || !(t instanceof TurretBuild tb)) return false;
        //hasAmmo() у ItemTurret/ContinuousTurret первым делом спрашивает canConsume() (potentialEfficiency > 0), а у выключенной
        //процессором он уже обнулён - вердикт «нет патрона» был бы ложным. Возвращаем в потенциал 1 на время вопроса, остальные
        //виды (энерго/жидкостные/платёжные) от него не зависят. Основной поток, значение возвращаем сразу.
        float saved = tb.potentialEfficiency;
        tb.potentialEfficiency = 1f;
        try{
            return tb.hasAmmo();
        }finally{
            tb.potentialEfficiency = saved;
        }
    }

    /**
     * Есть ли в турели боезапас, даже если её выключил процессор: обычный {@code hasAmmo()} у такой турели врёт
     * («нет патрона», потому что потенциал эффективности обнулён), а для разрядки важен именно заряд.
     */
    public static boolean hasAmmoEvenIfDisabled(TurretBuild t){
        return t.hasAmmo() || disabledWithAmmo(t);
    }

    /** Турель «действующая» для радиусов: может стрелять сейчас либо выключена процессором, но заряжена. */
    public static boolean active(BaseTurretBuild t){
        return t.canShoot() || disabledWithAmmo(t);
    }

    /**
     * DPS турели для заливки. У включённой - {@code estimateDps()} движка; у выключенной процессором, но заряженной,
     * движок отдаёт 0 (эффективность обнулена), поэтому считаем его формулой тот же показатель без обнуления:
     * выстрелы / перезарядка x урон патрона x скорость времени x питание (для тех, кому оно нужно).
     */
    public static float turretDps(BaseTurretBuild t){
        if(!disabledWithAmmo(t)) return t.estimateDps();
        TurretBuild tb = (TurretBuild)t;
        if(!(tb.block instanceof Turret tur) || tb.peekAmmo() == null) return 0f;
        float power = tb.block.hasPower && tb.block.consPower != null && tb.power != null ? tb.power.status : 1f;
        return tur.shoot.shots / tur.reload * 60f * tb.peekAmmo().estimateDPS() * power * tb.timeScale();
    }

    /**
     * Нарисована ли эта сущность MI2U-зоной - тогда пунктирное кольцо Foo (showingTurrets/Ally/Inv) и круг при
     * наведении EUI дублировали бы её на том же радиусе. Не турель (юнит) или выключенная зона/дедупликация - false.
     */
    public static boolean coveredByZone(Object o){
        return o instanceof BaseTurretBuild t && mi2u.graphics.RendererExt.enTurretZone
            && Core.settings.getBool(dedupeKey, true) && zoneAllows(t);
    }

    /** DPS, при котором заливка радиуса имеет «опорную» альфу (та, что без режима DPS была у любой турели). */
    private static final float DPS_REF = 60f;
    private static long dpsFillFrame = -1;
    private static boolean dpsFillOn, zoneFillOn;
    private static float dynDark;

    /** Ключи настроек: заливка радиусов в мире вообще (контуры не затрагивает), максимальное затемнение динамической заливки (0 - выкл.), прозрачность радиусов на миникарте. */
    public static final String zoneFillKey = "sonka-zone-fill", dynDarkKey = "sonka-zone-dps-maxdark", minimapAlphaKey = "sonka-minimap-ranges-alpha";

    /** Настройки заливки читаются раз в кадр, а не на каждую турель. */
    private static void frameSettings(){
        long f = Core.graphics.getFrameId();
        if(f != dpsFillFrame){
            dpsFillFrame = f;
            dpsFillOn = Core.settings.getBool(dpsFillKey, true);
            zoneFillOn = Core.settings.getBool(zoneFillKey, true);
            dynDark = Mathf.clamp(Core.settings.getInt(dynDarkKey, 0) / 100f, 0f, 0.95f);
        }
    }

    /** Режим «заливка по DPS» - настройка читается раз в кадр, а не на каждую турель. */
    private static boolean dpsFill(){
        frameSettings();
        return dpsFillOn;
    }

    /** Рисовать ли заливку радиусов в мире (общий выключатель заливки; контур он не трогает). */
    public static boolean worldFillOn(){
        frameSettings();
        return zoneFillOn;
    }

    /** Максимальное затемнение динамической заливки 0..0.95; 0 - режим выключен (работает только вместе с «по DPS»). */
    public static float dynamicDark(){
        frameSettings();
        return dpsFillOn ? dynDark : 0f;
    }

    /** Множитель прозрачности радиусов на миникарте: ползунок 0..100%, 50% = как было до ползунка (x1), 100% = вдвое плотнее. */
    public static float minimapAlphaMul(){
        return Core.settings.getInt(minimapAlphaKey, 50) / 50f;
    }

    /**
     * Альфа заливки одной турели. Режим «по DPS» (пункт 3.1): альфа = 1-exp(-k*dps), поэтому слои разных турелей
     * при наложении (1-(1-a1)(1-a2)... = 1-exp(-k*сумма DPS)) дают затемнение, пропорциональное СУММАРНОМУ DPS в
     * точке - самое светлое место обороны и есть самое слабое. DPS берётся из движка ({@code estimateDps()}: патрон
     * в стволе x скорострельность x КПД питания); сплэш считается его формулой (75%), смена патрона учитывается
     * только когда она случилась. Нет питания/патронов (DPS 0) - слабый намёк цветом состояния, а не пустота.
     * Без режима - одинаковая альфа у всех (чем больше турелей, тем темнее).
     * <p>
     * Динамическая заливка ({@link #dynamicDark} &gt; 0): k выбирается так, чтобы в точке с максимальным суммарным DPS
     * стороны (свои / вражеские считаются отдельно, см. {@link #peakDps}) затемнение было ровно заданным максимумом,
     * а остальные области - светлее пропорционально своему DPS. Максимум пересчитывается по ходу игры, поэтому самая
     * сильная точка обороны всегда самая тёмная, чем бы ни были заряжены турели.
     *
     * @param refAlpha альфа турели с DPS_REF (60 урона/с) - она же альфа любой турели без режима DPS
     * @param dynScale множитель максимального затемнения динамического режима (1 в мире, прозрачность на миникарте)
     */
    public static float fillAlpha(BaseTurretBuild t, float refAlpha, float dynScale){
        if(!dpsFill()) return refAlpha;
        float dps = turretDps(t);
        float maxDark = Math.min(dynDark * dynScale, 0.95f);
        if(maxDark > 0.001f){
            if(dps <= 0.01f) return maxDark * 0.12f;
            float peak = Math.max(peakDps(isOwn(t)), dps);
            float k = -(float)Math.log(1f - maxDark) / peak;
            return 1f - (float)Math.exp(-k * dps);
        }
        if(dps <= 0.01f) return refAlpha * 0.25f;
        float k = -(float)Math.log(1f - Math.min(refAlpha, 0.95f)) / DPS_REF;
        return Math.min(1f - (float)Math.exp(-k * dps), 0.7f);
    }

    public static float fillAlpha(BaseTurretBuild t, float refAlpha){
        return fillAlpha(t, refAlpha, 1f);
    }

    // ---------------------------------------------------------------- максимум суммарного DPS (динамическая заливка)

    /** Как часто пересчитывать максимум (тики): DPS турелей меняется на секундах, считать каждый кадр незачем. */
    private static final float PEAK_PERIOD = 30f;
    private static final float[] peakValue = new float[2], peakStamp = {-1e9f, -1e9f};
    private static float[] pX = new float[64], pY = new float[64], pR = new float[64], pW = new float[64];
    private static float[] grid = new float[0];

    /**
     * Максимальный суммарный DPS в одной точке плоскости среди турелей стороны: сколько стволов дотягивается до самой
     * плотной точки обороны. Кешируется на {@link #PEAK_PERIOD} тиков отдельно для своих и вражеских турелей.
     */
    public static float peakDps(boolean own){
        int s = own ? 0 : 1;
        float now = Time.globalTime;
        if(now >= peakStamp[s] && now - peakStamp[s] < PEAK_PERIOD) return peakValue[s];
        peakStamp[s] = now;
        return peakValue[s] = computePeak(own);
    }

    private static float computePeak(boolean own){
        if(player == null || state.isMenu()) return 0f;
        Seq<Building> list = own ? indexer.getFlagged(player.team(), BlockFlag.turret) : indexer.getEnemy(player.team(), BlockFlag.turret);
        int n = 0;
        for(int i = 0; i < list.size; i++){
            if(!(list.get(i) instanceof BaseTurretBuild t) || !t.isValid()) continue;
            if(!own && t.inFogTo(player.team())) continue;
            float dps = turretDps(t), r = t.range();
            if(dps <= 0.01f || r < 1f) continue;
            if(n == pX.length){
                int c = n * 2;
                pX = java.util.Arrays.copyOf(pX, c); pY = java.util.Arrays.copyOf(pY, c);
                pR = java.util.Arrays.copyOf(pR, c); pW = java.util.Arrays.copyOf(pW, c);
            }
            pX[n] = t.x; pY[n] = t.y; pR[n] = r; pW[n] = dps;
            n++;
        }
        return peakOfCircles(pX, pY, pR, pW, n);
    }

    /**
     * Максимум по плоскости суммы весов кругов, накрывающих точку (круги: центр, радиус, вес). Растр: клетка >= 1 тайл,
     * не больше ~150 тыс. клеток; в каждую клетку (по её центру) складываются веса накрывающих её кругов через разностный
     * массив по строкам - O(число кругов x их высота в клетках), без O(n^2)-перебора пересечений. Ошибка - до клетки на
     * краях кругов (для заливки несущественно); не меньше самого тяжёлого круга.
     */
    public static float peakOfCircles(float[] xs, float[] ys, float[] rs, float[] ws, int n){
        float single = 0f, minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for(int i = 0; i < n; i++){
            single = Math.max(single, ws[i]);
            minX = Math.min(minX, xs[i] - rs[i]); maxX = Math.max(maxX, xs[i] + rs[i]);
            minY = Math.min(minY, ys[i] - rs[i]); maxY = Math.max(maxY, ys[i] + rs[i]);
        }
        if(n <= 1) return single;

        float w = maxX - minX, h = maxY - minY;
        float cs = Math.max(tilesize, (float)Math.sqrt(w * h / 150000f));
        int gw = (int)(w / cs) + 3, gh = (int)(h / cs) + 3;
        if(grid.length < gw * gh) grid = new float[gw * gh];
        java.util.Arrays.fill(grid, 0, gw * gh, 0f);
        for(int c = 0; c < n; c++){
            float x = xs[c], y = ys[c], r = rs[c], wt = ws[c];
            int j0 = Math.max(0, (int)Math.ceil((y - r - minY) / cs - 0.5f)), j1 = Math.min(gh - 1, (int)Math.floor((y + r - minY) / cs - 0.5f));
            for(int j = j0; j <= j1; j++){
                float dy = minY + (j + 0.5f) * cs - y, d2 = r * r - dy * dy;
                if(d2 < 0f) continue;
                float half = (float)Math.sqrt(d2);
                int i0 = Math.max(0, (int)Math.ceil((x - half - minX) / cs - 0.5f)), i1 = Math.min(gw - 1, (int)Math.floor((x + half - minX) / cs - 0.5f));
                if(i0 > i1) continue;
                grid[j * gw + i0] += wt;
                if(i1 + 1 < gw) grid[j * gw + i1 + 1] -= wt;
            }
        }
        float best = single;
        for(int j = 0; j < gh; j++){
            float acc = 0f;
            for(int i = 0; i < gw; i++){
                acc += grid[j * gw + i];
                if(acc > best) best = acc;
            }
        }
        return best;
    }

    /** Альфа заливки ОДНОГО радиуса в мире от ползунка прозрачности зон MI2U (0..1): 0,3 -> ~0,1; n перекрытий дают 1-(1-a)^n. */
    public static float worldFillAlpha(float zoneTransparency){
        return Mathf.clamp(zoneTransparency * 0.35f, 0f, 0.5f);
    }

    /**
     * Радиусы турелей на миникарте: заливка (чем больше радиусов накладывается на точку, тем она темнее) и тонкий
     * контур поверх. Рисуется в мировых координатах (трансформация миникарты уже выставлена вызывающим),
     * scaleFactor - мировых единиц на пиксель.
     */
    public static void drawOnMinimap(float scaleFactor, float alpha){
        if(player == null || state.isMenu() || !Core.settings.getBool(minimapKey, true)) return;
        alpha *= minimapAlphaMul(); //ползунок «прозрачность радиусов на миникарте» (как «прозрачность зон» для мира)
        if(alpha <= 0.001f) return;
        draw(indexer.getFlagged(player.team(), BlockFlag.turret), scaleFactor, alpha, true);
        if(Core.settings.getBool(minimapEnemyKey, true)){
            draw(indexer.getEnemy(player.team(), BlockFlag.turret), scaleFactor, alpha, false);
        }
        Draw.reset();
    }

    /** Контекст рисования для {@link #sink} (рендер однопоточный, поэтому статикой, без лямбд-захватов на каждый кадр). */
    private static float curScale, curAlpha;
    private static boolean curOwn;
    private static final Seq<BaseTurretBuild> cand = new Seq<>();

    private static void draw(Seq<Building> list, float scaleFactor, float alpha, boolean own){
        //валидные турели в компактный список: нужен и для заливки, и для слияния контуров
        cand.clear();
        for(int i = 0; i < list.size; i++){
            Building b = list.get(i);
            if(!(b instanceof BaseTurretBuild t) || !t.isValid()) continue;
            if(!own && t.inFogTo(player.team())) continue;
            if(mi2u.graphics.RendererExt.turretZoneOnlyActive && !active(t)) continue; //тот же фильтр «только действующие», что у зоны в мире
            if(t.range() < 3f * scaleFactor) continue; //меньше ~3 пикселей - просто шум
            cand.add(t);
        }
        int n = cand.size;
        if(n == 0) return;

        //проход 1 - заливки (все под контурами)
        for(int i = 0; i < n; i++){
            BaseTurretBuild t = cand.get(i);
            Color c = own ? statusColor(t) : t.team.color;
            Draw.color(fillColor(tmpFill.set(c)), fillAlpha(t, (own ? 0.16f : 0.12f) * alpha, alpha));
            Fill.poly(t.x, t.y, sidesFor(t.range(), scaleFactor), t.range());
        }

        //проход 2 - контуры: перекрывающиеся радиусы одной команды сливаются в общий контур (внутренние дуги не рисуем)
        Lines.stroke(scaleFactor);
        curScale = scaleFactor;
        curAlpha = alpha;
        curOwn = own;
        float[] xs = scratch(0, n), ys = scratch(1, n), rs = scratch(2, n);
        int[] gs = scratchInt(n);
        for(int i = 0; i < n; i++){
            BaseTurretBuild t = cand.get(i);
            xs[i] = t.x;
            ys[i] = t.y;
            rs[i] = t.range();
            gs[i] = t.team.id;
        }
        mergedArcs(xs, ys, rs, gs, n, minimapSink);
    }

    private static int sidesFor(float range, float scaleFactor){
        return Mathf.clamp(Math.round(range / scaleFactor * 1.2f), 12, 48);
    }

    private static final ArcSink minimapSink = (i, a0, a1) -> {
        BaseTurretBuild t = cand.get(i);
        Color c = curOwn ? statusColor(t) : t.team.color;
        Draw.color(c, Math.min((curOwn ? 0.6f : 0.45f) * curAlpha, 1f));
        arcLine(t.x, t.y, t.range(), a0, a1, sidesFor(t.range(), curScale));
    };

    // ---------------------------------------------------------------- слияние контуров

    /** Приёмник кусков контура: дуга круга {@code i} от {@code a0} до {@code a1} (радианы, 0 <= a0 < a1 <= 2π). */
    public interface ArcSink{
        void arc(int i, float a0, float a1);
    }

    /** Больше кругов - слияние не делаем (O(n²)), рисуются целые окружности. */
    public static final int MERGE_LIMIT = 800;

    private static final float TAU = Mathf.PI2;
    private static float[][] fScratch = new float[3][0];
    private static int[] iScratch = new int[0];
    private static float[] ivA = new float[0], ivB = new float[0];

    private static float[] scratch(int slot, int n){
        if(fScratch[slot].length < n) fScratch[slot] = new float[Math.max(n, 64)];
        return fScratch[slot];
    }

    private static int[] scratchInt(int n){
        if(iScratch.length < n) iScratch = new int[Math.max(n, 64)];
        return iScratch;
    }

    /**
     * Контур ОБЪЕДИНЕНИЯ кругов: для каждого круга отдаёт в {@code sink} только те дуги, что не лежат внутри другого
     * круга ТОЙ ЖЕ группы {@code gs} (команды). Круг, целиком вложенный в другой, пропадает; одинаковые совпадающие
     * круги рисуются один раз. Больше {@link #MERGE_LIMIT} кругов - каждый отдаётся целиком.
     */
    public static void mergedArcs(float[] xs, float[] ys, float[] rs, int[] gs, int n, ArcSink sink){
        if(n > MERGE_LIMIT){
            for(int i = 0; i < n; i++) sink.arc(i, 0f, TAU);
            return;
        }
        int cap = 2 * n + 2;
        if(ivA.length < cap){
            ivA = new float[cap];
            ivB = new float[cap];
        }
        for(int i = 0; i < n; i++){
            float ri = rs[i];
            int k = 0;
            boolean hidden = false;
            for(int j = 0; j < n; j++){
                if(j == i || gs[j] != gs[i]) continue;
                float dx = xs[j] - xs[i], dy = ys[j] - ys[i], rj = rs[j];
                float d2 = dx * dx + dy * dy, sum = ri + rj;
                if(d2 >= sum * sum) continue; //не пересекаются
                float d = Mathf.sqrt(d2);
                if(d + ri <= rj + 0.01f){
                    //i целиком внутри j; у совпадающих кругов прячем только тот, у кого индекс больше
                    if(rj > ri + 0.01f || d > 0.01f || j < i){
                        hidden = true;
                        break;
                    }
                    continue;
                }
                if(d + rj <= ri) continue; //j целиком внутри i - i не закрывает
                float th = (float)Math.atan2(dy, dx); //не Mathf.atan2: у него порядок аргументов (x, y)
                float ph = (float)Math.acos(Mathf.clamp((ri * ri + d2 - rj * rj) / (2f * ri * d), -1f, 1f));
                float a = th - ph;
                while(a < 0f) a += TAU;
                while(a >= TAU) a -= TAU;
                float b = a + 2f * ph;
                if(b > TAU){
                    ivA[k] = a; ivB[k++] = TAU;
                    ivA[k] = 0f; ivB[k++] = b - TAU;
                }else{
                    ivA[k] = a; ivB[k++] = b;
                }
            }
            if(hidden) continue;
            if(k == 0){
                sink.arc(i, 0f, TAU);
                continue;
            }
            //сортировка закрытых интервалов по началу (их единицы - вставками)
            for(int a = 1; a < k; a++){
                float sa = ivA[a], sb = ivB[a];
                int b = a - 1;
                while(b >= 0 && ivA[b] > sa){
                    ivA[b + 1] = ivA[b];
                    ivB[b + 1] = ivB[b];
                    b--;
                }
                ivA[b + 1] = sa;
                ivB[b + 1] = sb;
            }
            float cur = 0f;
            for(int a = 0; a < k; a++){
                if(ivA[a] - cur > 0.002f) sink.arc(i, cur, ivA[a]);
                cur = Math.max(cur, ivB[a]);
            }
            if(TAU - cur > 0.002f) sink.arc(i, cur, TAU);
        }
    }

    /** Дуга линией с нормальными стыками (Lines.arc теряет до сегмента на конце и оставил бы щели между кусками). */
    public static void arcLine(float x, float y, float r, float a0, float a1, int sidesFull){
        int seg = Math.max(2, (int)Math.ceil((a1 - a0) / TAU * sidesFull));
        Lines.beginLine();
        for(int k = 0; k <= seg; k++){
            float a = a0 + (a1 - a0) * k / seg;
            Lines.linePoint(x + Mathf.cos(a) * r, y + Mathf.sin(a) * r);
        }
        Lines.endLine();
    }
}
