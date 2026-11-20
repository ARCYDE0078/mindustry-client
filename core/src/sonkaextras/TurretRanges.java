package sonkaextras;

import arc.Core;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.graphics.g2d.Lines;
import arc.math.Mathf;
import arc.struct.Seq;
import mindustry.gen.Building;
import mindustry.graphics.Pal;
import mindustry.world.blocks.defense.turrets.BaseTurret.BaseTurretBuild;
import mindustry.world.blocks.defense.turrets.ItemTurret;
import mindustry.world.blocks.defense.turrets.LiquidTurret;
import mindustry.world.blocks.defense.turrets.PowerTurret;
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

    /** DPS, при котором заливка радиуса имеет «опорную» альфу (та, что без режима DPS была у любой турели). */
    private static final float DPS_REF = 60f;
    private static long dpsFillFrame = -1;
    private static boolean dpsFillOn;

    /** Режим «заливка по DPS» - настройка читается раз в кадр, а не на каждую турель. */
    private static boolean dpsFill(){
        long f = Core.graphics.getFrameId();
        if(f != dpsFillFrame){
            dpsFillFrame = f;
            dpsFillOn = Core.settings.getBool(dpsFillKey, true);
        }
        return dpsFillOn;
    }

    /**
     * Альфа заливки одной турели. Режим «по DPS» (пункт 3.1): альфа = 1-exp(-k*dps), поэтому слои разных турелей
     * при наложении (1-(1-a1)(1-a2)... = 1-exp(-k*сумма DPS)) дают затемнение, пропорциональное СУММАРНОМУ DPS в
     * точке - самое светлое место обороны и есть самое слабое. DPS берётся из движка ({@code estimateDps()}: патрон
     * в стволе x скорострельность x КПД питания); сплэш считается его формулой (75%), смена патрона учитывается
     * только когда она случилась. Нет питания/патронов (DPS 0) - слабый намёк цветом состояния, а не пустота.
     * Без режима - одинаковая альфа у всех (чем больше турелей, тем темнее).
     *
     * @param refAlpha альфа турели с DPS_REF (60 урона/с) - она же альфа любой турели без режима DPS
     */
    public static float fillAlpha(BaseTurretBuild t, float refAlpha){
        if(!dpsFill()) return refAlpha;
        float dps = t.estimateDps();
        if(dps <= 0.01f) return refAlpha * 0.25f;
        float k = -(float)Math.log(1f - Math.min(refAlpha, 0.95f)) / DPS_REF;
        return Math.min(1f - (float)Math.exp(-k * dps), 0.7f);
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
            if(t.range() < 3f * scaleFactor) continue; //меньше ~3 пикселей - просто шум
            cand.add(t);
        }
        int n = cand.size;
        if(n == 0) return;

        //проход 1 - заливки (все под контурами)
        for(int i = 0; i < n; i++){
            BaseTurretBuild t = cand.get(i);
            Color c = own ? statusColor(t) : t.team.color;
            Draw.color(fillColor(tmpFill.set(c)), fillAlpha(t, (own ? 0.16f : 0.12f) * alpha));
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
        Draw.color(c, (curOwn ? 0.6f : 0.45f) * curAlpha);
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
