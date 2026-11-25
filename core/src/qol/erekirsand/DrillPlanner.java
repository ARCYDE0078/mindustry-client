package qol.erekirsand;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Планировщик буров Эрекира (песчаные скальные дробители и плазменные буры по настенной руде): буры + утки +
 * лучевые узлы, один открытый выход. Режим выбирает {@link Params#beam}. НЕ зависит
 * от движка вообще (только массивы-снимок {@link Grid}) - поэтому проверяется обычным javac на синтетических
 * картах, а в игре его кормит {@link ErekirSandFeature}, снимая такой же снимок с мира.
 * <p>
 * Что строит (всё внутри зоны, которую выделил игрок):
 * <ul>
 *   <li>скальные дробители ({@code cliff-crusher}, 2x2) лицом к стене с песчаным атрибутом - дробитель
 *       бьёт ТОЛЬКО по двум клеткам перед собой (WallCrafter.getEfficiency), отдача на единицу атрибута
 *       = 60/drillTime предмета в секунду;</li>
 *   <li>дерево уток к ОДНОЙ выходной утке - утка принимает сзади и с боков (не спереди), поэтому ветки
 *       вливаются в магистраль сбоку, а дробитель выгружает песок в любую соседнюю утку сам
 *       (WallCrafter dump());</li>
 *   <li>лучевые узлы ({@code beam-node}): каждый дробитель должен оказаться в одной сети (+ связь с ближайшей
 *       существующей), с учётом правил луча: он останавливается на ПЕРВОМ энергоблоке в 4 сторонах
 *       (два «чистых» потребителя рядом друг с другом НЕ проводят ток).</li>
 * </ul>
 * Куда песок девается дальше - забота игрока: выходная утка просто смотрит в одну сторону, а перед ней
 * оставлена свободная полоса {@link Params#exitLane} клеток (в неё не встают ни дробители, ни утки, ни узлы),
 * чтобы линию можно было продолжить к печам/ядру/чему угодно. Печей, роутеров и прочего тут нет.
 * <p>
 * Режим {@code beam} (плазменный бур, {@code BeamDrill}): бур бьёт лучом по столбцам перед собой до ПЕРВОЙ твёрдой
 * клетки; пока клетки между буром и рудой пусты, луч идёт сквозь них. Между буром и рудой оставляется зазор не
 * меньше {@link Params#minGap} клеток, и ВСЕ клетки зазора резервируются ({@link #GAP}): ни утка, ни узел, ни
 * соседний бур туда не встанут - игрок потом проложит там трубы с водородом (он ускоряет плазменный бур x2.5), а
 * любое твёрдое здание в зазоре просто перекрыло бы луч. Бур берёт только ОДНУ руду (иначе в одну утку польётся
 * смесь): либо заданную {@link Params#oreItem}, либо ту, которой в выделении больше всего.
 */
public final class DrillPlanner{
    public static final int DRILL = 1, CRUSHER = DRILL, DUCT = 2, NODE = 3;
    /** Клетка занята «не нашим» блоком, но и не пуста: свободная полоса перед выходной уткой. */
    static final byte RESERVED = 9;
    /** Клетка зазора между плазменным буром и рудой: свободна и такой остаётся (трубы/луч). */
    static final byte GAP = 8;
    /** Штраф к весу кандидата за каждую лишнюю (сверх minGap) клетку зазора в столбце с рудой: при прочих равных бур жмётся к стене. */
    static final float GAP_PENALTY = 0.3f;

    public static final int[] DX = {1, 0, -1, 0}, DY = {0, 1, 0, -1};
    /** Значение pw[] для плитки-изолятора: луч узла дальше неё не идёт. */
    public static final int INSULATED_ID = -1;
    static final int INSULATED = INSULATED_ID;
    static final int NEW_BASE = 1_000_000;
    static final float INF = 1e9f;

    public static final class Item{
        public final int kind, x, y, rot;

        Item(int kind, int x, int y, int rot){
            this.kind = kind;
            this.x = x;
            this.y = y;
            this.rot = rot;
        }

        @Override
        public String toString(){
            return "Item{" + kind + "@" + x + "," + y + " r" + rot + "}";
        }
    }

    public static final class Params{
        /** Сколько песка в секунду набирать: 3 кремниевые дуговые печи x 4.8 = 14.4 (одна утка тянет 15). */
        public float targetRate = 14.4f;
        /** Минимальная суммарная «эффективность» двух передних клеток, чтобы дробитель вообще рассматривался. */
        public float minEff = 1.0f;
        /** WallCrafter.drillTime у cliff-crusher. */
        public float drillTime = 110f;
        public int nodeRange = 10;
        public boolean connectPower = true;
        /** «Цена» самого дробителя в штуках уток при выборе: чем больше, тем охотнее берёт дорогие длинные ветки ради эффективных стен. */
        public float crusherCostInDucts = 4f;
        /** Сколько положений выхода пробовать (на каждое - полный расчёт дерева). */
        public int exitCandidates = 20;
        /** Куда смотрит выходная утка: 0 вправо, 1 вверх, 2 влево, 3 вниз; -1 - любое (берётся самое дешёвое). */
        public int exitDir = -1;
        /** Сколько клеток перед выходной уткой должно остаться свободными: туда игрок продолжит линию. */
        public int exitLane = 3;

        // --- режим плазменного бура (BeamDrill); для скального дробителя ничего из этого не используется
        /** true - плазменный бур по настенной руде, false - скальный дробитель по песку. */
        public boolean beam = false;
        /** Сторона бура: 2 (плазменный) или 3 (большой плазменный). Дробитель всегда 2x2. */
        public int size = 2;
        /** BeamDrill.range: сколько клеток перед буром просматривает луч (зазор + клетка руды). */
        public int range = 5;
        /** BeamDrill.tier: руда с hardness выше не добывается. */
        public int tier = 3;
        /** Зазор между буром и стеной: минимум столько свободных клеток в КАЖДОМ столбце луча. */
        public int minGap = 1;
        /** Потолок зазора: дальше бур не ставим, хотя луч достал бы (иначе линия расползается по всей дальности и трубам не за что зацепиться). */
        public int maxGap = 2;
        /** Сколько столбцов луча должны упираться в руду (иначе бур не рассматривается). */
        public int minOreCols = 2;
        /** Какую руду брать: id предмета + 1; 0 - ту, которой в выделении больше. */
        public int oreItem = 0;
    }

    /** Снимок мира: окно плиток + зона, в которой разрешено строить. */
    public static final class Grid{
        public final int ox, oy, w, h;
        /** Клетка пуста и на ней можно поставить 1x1 блок. */
        public final boolean[] free;
        /** Атрибут песка у ТВЁРДОЙ плитки (стена), иначе 0. */
        public final float[] sand;
        /** Плитка твёрдая (стена или твёрдое здание): луч плазменного бура останавливается на первой такой. */
        public final boolean[] solid;
        /** Что даёт стена как настенная руда: id предмета + 1, иначе 0. */
        public final int[] ore;
        /** Твёрдость предметов по индексу id + 1 (чтобы отсечь руду выше tier бура). */
        public int[] itemHardness = new int[0];
        /** Энергия: 0 - ничего, -1 - изолятор (останавливает луч), &gt;0 - id уже существующей энергосети. */
        public final int[] pw;
        public int zx0, zy0, zx1, zy1;
        /** Существующие сети в окне: {graphId, centerX, centerY, hasSource(0/1)}. */
        public final List<int[]> grids = new ArrayList<>();

        public Grid(int ox, int oy, int w, int h){
            this.ox = ox;
            this.oy = oy;
            this.w = w;
            this.h = h;
            free = new boolean[w * h];
            sand = new float[w * h];
            solid = new boolean[w * h];
            ore = new int[w * h];
            pw = new int[w * h];
        }

        public boolean in(int x, int y){
            return x >= ox && y >= oy && x < ox + w && y < oy + h;
        }

        public int idx(int x, int y){
            return (y - oy) * w + (x - ox);
        }

        boolean inZone(int x, int y){
            return x >= zx0 && x <= zx1 && y >= zy0 && y <= zy1;
        }
    }

    public static final class Result{
        public final List<Item> items = new ArrayList<>();
        public float rate;
        /** Сколько буров (crushers - историческое имя поля: там и скальные дробители, и плазменные буры). */
        public int crushers, ducts, nodes;
        /** Добываемая руда (id предмета + 1) в режиме beam, иначе 0. */
        public int item;
        /** Клетки зазора между бурами и рудой (x, y): остаются свободными под трубы. */
        public final List<int[]> gaps = new ArrayList<>();
        public boolean powerOk = true, gridLinked;
        /** Выходная утка (последняя в линии, песок из неё сыплется вперёд в никуда) и её направление. */
        public int exitX, exitY, exitDir;
        public String error;
        /** Для тестов/отладки: ASCII-картинка результата. */
        public String ascii;
    }

    // ------------------------------------------------------------------------------------------

    final Grid g;
    final Params p;
    final int n;
    final List<Cand> cands = new ArrayList<>();
    /** Дробители, которые не удалось связать питанием (мешают соседи/стены): исключаются из расчёта и он повторяется. */
    boolean[] bannedGlobal;
    /** Индексы (в Run.crushers) дробителей, не попавших в общую сеть питания, - заполняет verifyPower. */
    final List<Integer> unlinked = new ArrayList<>();

    public DrillPlanner(Grid g, Params p){
        this.g = g;
        this.p = p;
        this.n = g.w * g.h;
    }

    static final class Cand{
        int id, x, y, rot;
        /** Сколько единиц добычи даёт бур: сумма песчаного атрибута (дробитель) либо число столбцов луча в руду (плазменный). */
        float eff;
        /** Вес при выборе кандидата: eff минус штраф за лишний зазор (при прочих равных бур жмётся к стене). */
        float pref;
        /** Добываемая руда (id + 1), только режим beam. */
        int item;
        int[] foot = new int[4];
        int[] ports = new int[6];
        int portCount;
        /** Клетки зазора перед буром (режим beam): их резервируем. */
        int[] gap = new int[0];
    }

    /** Одна попытка при конкретном положении выхода. */
    final class Run{
        final byte[] occ = new byte[n];
        final byte[] dir = new byte[n];
        final List<Cand> crushers = new ArrayList<>();
        final List<Integer> ducts = new ArrayList<>();
        int entry = -1;
        int exitDir;
        float rate;
        float[] dist;
        int[] from;

        Run(){
            Arrays.fill(dir, (byte)-1);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Кандидаты-дробители

    void buildCandidates(){
        cands.clear();
        if(p.beam) buildBeamCandidates();
        else buildCrusherCandidates();
        for(Cand c : cands) if(c.pref == 0f) c.pref = c.eff;
    }

    int hardness(int itemPlusOne){
        return itemPlusOne >= 0 && itemPlusOne < g.itemHardness.length ? g.itemHardness[itemPlusOne] : 99;
    }

    /** Первая клетка перед стороной бура в столбце i (rot - куда смотрит бур, s - его сторона, (x,y) - левый нижний угол). */
    static int[] frontStart(int x, int y, int s, int rot, int i){
        return switch(rot){
            case 0 -> new int[]{x + s, y + i};
            case 1 -> new int[]{x + i, y + s};
            case 2 -> new int[]{x - 1, y + i};
            default -> new int[]{x + i, y - 1};
        };
    }

    /** Порты (клетки периметра, куда утка может принимать): все стороны, кроме передней. */
    void fillPorts(Cand c, int s){
        int[] ports = new int[3 * s];
        int pc = 0;
        for(int sd = 0; sd < 4; sd++){
            if(sd == c.rot) continue;
            for(int i = 0; i < s; i++){
                int[] q = frontStart(c.x, c.y, s, sd, i);
                if(g.in(q[0], q[1])) ports[pc++] = g.idx(q[0], q[1]);
            }
        }
        c.ports = ports;
        c.portCount = pc;
    }

    /**
     * Плазменные буры: для каждого положения/поворота прослеживаем луч по каждому столбцу ровно как BeamDrill.updateFacing
     * (до первой ТВЁРДОЙ плитки не дальше range). Берём только положения, где: каждый столбец имеет зазор &gt;= minGap
     * свободных клеток до первой твёрдой (даже если это не руда - иначе бур стоял бы вплотную к скале), все клетки
     * зазора свободны (туда пойдут трубы), руда в столбцах одна и та же и не выше tier, а столбцов с рудой достаточно.
     */
    void buildBeamCandidates(){
        int s = p.size;
        for(int x = g.zx0; x <= g.zx1 - (s - 1); x++){
            for(int y = g.zy0; y <= g.zy1 - (s - 1); y++){
                int[] foot = new int[s * s];
                boolean free = true;
                for(int i = 0; i < s && free; i++){
                    for(int j = 0; j < s; j++){
                        int t = g.idx(x + i, y + j);
                        if(!g.free[t]){
                            free = false;
                            break;
                        }
                        foot[i * s + j] = t;
                    }
                }
                if(!free) continue;
                for(int rot = 0; rot < 4; rot++){
                    int item = 0, cols = 0;
                    boolean ok = true, mixed = false;
                    float penalty = 0f;
                    List<Integer> gap = new ArrayList<>();
                    for(int i = 0; i < s && ok; i++){
                        int[] st = frontStart(x, y, s, rot, i);
                        List<Integer> col = new ArrayList<>();
                        int hit = 0;
                        boolean solidHit = false;
                        for(int j = 0; j < p.range; j++){
                            int tx = st[0] + DX[rot] * j, ty = st[1] + DY[rot] * j;
                            if(!g.in(tx, ty)) break; //край окна/карты: дальше не видно
                            int t = g.idx(tx, ty);
                            if(g.solid[t]){
                                solidHit = true;
                                int it = g.ore[t];
                                if(it > 0 && hardness(it) <= p.tier) hit = it;
                                break;
                            }
                            col.add(t);
                        }
                        if(solidHit && col.size() < p.minGap){
                            ok = false; //упирается в скалу вплотную - трубам места нет
                            break;
                        }
                        if(hit > 0 && col.size() > p.maxGap){
                            ok = false; //руда есть, но далеко: такой бур не берём (а двигать его ближе - это уже другой кандидат)
                            break;
                        }
                        //резервируем весь зазор до руды; в столбце без руды хватит minGap клеток у самого бура
                        int reserve = hit > 0 ? col.size() : Math.min(col.size(), p.minGap);
                        if(reserve < p.minGap){
                            ok = false;
                            break;
                        }
                        for(int k = 0; k < reserve; k++){
                            int t = col.get(k);
                            if(!g.free[t]){
                                ok = false;
                                break;
                            }
                            gap.add(t);
                        }
                        if(!ok) break;
                        if(hit > 0){
                            if(item != 0 && item != hit) mixed = true;
                            item = hit;
                            cols++;
                            penalty += GAP_PENALTY * (col.size() - p.minGap);
                        }
                    }
                    if(!ok || mixed || cols < p.minOreCols) continue;
                    Cand c = new Cand();
                    c.id = cands.size();
                    c.x = x;
                    c.y = y;
                    c.rot = rot;
                    c.eff = cols;
                    c.pref = cols - penalty;
                    c.item = item;
                    c.foot = foot;
                    int[] ga = new int[gap.size()];
                    for(int k = 0; k < ga.length; k++) ga[k] = gap.get(k);
                    c.gap = ga;
                    fillPorts(c, s);
                    cands.add(c);
                }
            }
        }
    }

    void buildCrusherCandidates(){
        for(int x = g.zx0; x <= g.zx1 - 1; x++){
            for(int y = g.zy0; y <= g.zy1 - 1; y++){
                int[] foot = {g.idx(x, y), g.idx(x + 1, y), g.idx(x, y + 1), g.idx(x + 1, y + 1)};
                if(!g.free[foot[0]] || !g.free[foot[1]] || !g.free[foot[2]] || !g.free[foot[3]]) continue;
                for(int rot = 0; rot < 4; rot++){
                    float eff = 0;
                    for(int i = 0; i < 2; i++){
                        int fx, fy;
                        switch(rot){
                            case 0 -> { fx = x + 2; fy = y + i; }
                            case 1 -> { fx = x + i; fy = y + 2; }
                            case 2 -> { fx = x - 1; fy = y + i; }
                            default -> { fx = x + i; fy = y - 1; }
                        }
                        if(g.in(fx, fy)) eff += g.sand[g.idx(fx, fy)];
                    }
                    if(eff < p.minEff) continue;
                    Cand c = new Cand();
                    c.id = cands.size();
                    c.x = x;
                    c.y = y;
                    c.rot = rot;
                    c.eff = eff;
                    c.foot = foot;
                    //порты: периметр 2x2 (8 клеток) без передней стороны (там стена)
                    int pc = 0;
                    for(int s = 0; s < 4; s++){
                        if(s == rot) continue;
                        for(int i = 0; i < 2; i++){
                            int px, py;
                            switch(s){
                                case 0 -> { px = x + 2; py = y + i; }
                                case 1 -> { px = x + i; py = y + 2; }
                                case 2 -> { px = x - 1; py = y + i; }
                                default -> { px = x + i; py = y - 1; }
                            }
                            if(g.in(px, py)) c.ports[pc++] = g.idx(px, py);
                        }
                    }
                    c.portCount = pc;
                    cands.add(c);
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Выход

    /** Выходная утка в (x,y) смотрит в d: сама клетка и полоса перед ней свободны и в окне; сама утка - в зоне. */
    boolean exitFits(int x, int y, int d){
        if(!g.in(x, y) || !g.inZone(x, y) || !g.free[g.idx(x, y)]) return false;
        for(int k = 1; k <= p.exitLane; k++){
            int fx = x + DX[d] * k, fy = y + DY[d] * k;
            if(!g.in(fx, fy) || !g.free[g.idx(fx, fy)]) return false;
        }
        return true;
    }

    /** Ставит выходную утку и резервирует полосу перед ней (после {@link #exitFits}). */
    void placeExit(Run r, int x, int y, int d){
        int t = g.idx(x, y);
        r.occ[t] = DUCT;
        r.dir[t] = (byte)d;
        r.entry = t;
        r.exitDir = d;
        r.ducts.add(t);
        for(int k = 1; k <= p.exitLane; k++) r.occ[g.idx(x + DX[d] * k, y + DY[d] * k)] = RESERVED;
    }

    // ------------------------------------------------------------------------------------------
    // Рост магистрали: Dijkstra «от сети наружу» по состояниям (клетка, куда смотрит утка)

    boolean pathable(Run r, int t){
        if(!g.free[t] || r.occ[t] != 0) return false;
        int x = t % g.w + g.ox, y = t / g.w + g.oy;
        return g.inZone(x, y);
    }

    void dijkstra(Run r){
        float[] dist = new float[n * 4];
        int[] from = new int[n * 4];
        Arrays.fill(dist, INF);
        Arrays.fill(from, -1);
        PriorityQueue<float[]> pq = new PriorityQueue<>((a, b) -> Float.compare(a[0], b[0]));

        for(int q = 0; q < n; q++){
            if(r.occ[q] != DUCT) continue;
            int qx = q % g.w + g.ox, qy = q / g.w + g.oy;
            for(int k = 0; k < 4; k++){
                //спереди от q стоит то, КУДА q отдаёт; с этой стороны q ничего не принимает
                if(k == r.dir[q]) continue;
                int px = qx + DX[k], py = qy + DY[k];
                if(!g.in(px, py)) continue;
                int pt = g.idx(px, py);
                if(!pathable(r, pt)) continue;
                int d = (k + 2) % 4;       //утка в p смотрит на q
                int s = pt * 4 + d;
                if(1f < dist[s]){
                    dist[s] = 1f;
                    from[s] = -1;
                    pq.add(new float[]{1f, s});
                }
            }
        }
        while(!pq.isEmpty()){
            float[] top = pq.poll();
            int s = (int)top[1];
            if(top[0] > dist[s]) continue;
            int t = s >> 2, d = s & 3;
            int tx = t % g.w + g.ox, ty = t / g.w + g.oy;
            for(int e : new int[]{d, (d + 1) % 4, (d + 3) % 4}){
                int ux = tx - DX[e], uy = ty - DY[e];
                if(!g.in(ux, uy)) continue;
                int u = g.idx(ux, uy);
                if(!pathable(r, u)) continue;
                int ns = u * 4 + e;
                float nd = dist[s] + 1f + (e != d ? 0.02f : 0f);
                if(nd < dist[ns]){
                    dist[ns] = nd;
                    from[ns] = s;
                    pq.add(new float[]{nd, ns});
                }
            }
        }
        r.dist = dist;
        r.from = from;
    }

    /** Лучшее состояние клетки t (минимум по четырём направлениям) или -1. */
    int bestState(Run r, int t){
        int best = -1;
        float bd = INF;
        for(int d = 0; d < 4; d++){
            float v = r.dist[t * 4 + d];
            if(v < bd){
                bd = v;
                best = t * 4 + d;
            }
        }
        return best;
    }

    boolean available(Run r, Cand c){
        for(int t : c.foot) if(r.occ[t] != 0) return false;
        //зазор должен быть пуст либо уже зарезервирован соседним буром (общий зазор); утка/бур/полоса выхода в нём - нет
        for(int t : c.gap) if(r.occ[t] != 0 && r.occ[t] != GAP) return false;
        return true;
    }

    /** Стоимость (кол-во новых уток) подключения дробителя либо INF; в out[0] - выбранный порт-состояние (или -2 если уже стоит утка рядом). */
    float portCost(Run r, Cand c, int[] out){
        float best = INF;
        int bs = -1;
        for(int i = 0; i < c.portCount; i++){
            int pt = c.ports[i];
            if(r.occ[pt] == DUCT){
                out[0] = -2;
                return 0f;
            }
            if(!pathable(r, pt)) continue;
            int s = bestState(r, pt);
            if(s < 0) continue;
            float v = r.dist[s];
            if(v < best){
                best = v;
                bs = s;
            }
        }
        out[0] = bs;
        return bs < 0 ? INF : best;
    }

    void grow(Run r){
        boolean[] banned = bannedGlobal.clone();
        int guard = 0;
        int[] out = new int[1];
        while(r.rate < p.targetRate && guard++ < 120){
            dijkstra(r);
            int best = -1;
            float bestScore = 0f;
            for(int i = 0; i < cands.size(); i++){
                if(banned[i]) continue;
                Cand c = cands.get(i);
                if(!available(r, c)) continue;
                float cost = portCost(r, c, out);
                if(cost >= INF) continue;
                float score = c.pref / (cost + p.crusherCostInDucts);
                if(score > bestScore){
                    bestScore = score;
                    best = i;
                }
            }
            if(best < 0) break;
            Cand c = cands.get(best);
            for(int t : c.foot) r.occ[t] = CRUSHER;
            //зазор резервируем ДО пересчёта путей: утки обходят его
            List<Integer> marked = new ArrayList<>();
            for(int t : c.gap){
                if(r.occ[t] == 0){
                    r.occ[t] = GAP;
                    marked.add(t);
                }
            }
            dijkstra(r);
            float cost = portCost(r, c, out);
            if(cost >= INF){
                for(int t : c.foot) r.occ[t] = 0;
                for(int t : marked) r.occ[t] = 0;
                banned[best] = true;
                continue;
            }
            if(out[0] >= 0){
                int s = out[0];
                while(s >= 0){
                    int t = s >> 2, d = s & 3;
                    r.occ[t] = DUCT;
                    r.dir[t] = (byte)d;
                    r.ducts.add(t);
                    s = r.from[s];
                }
            }
            r.crushers.add(c);
            r.rate += 60f * c.eff / p.drillTime;
        }
    }

    float runScore(Run r){
        float cost = 30f * r.crushers.size() + r.ducts.size();
        return (r.rate >= p.targetRate ? 1e6f : r.rate * 1000f) - cost;
    }

    // ------------------------------------------------------------------------------------------

    public Result plan(){
        Result res = new Result();
        buildCandidates();
        int chosenItem = 0;
        if(p.beam && !cands.isEmpty()){
            //одна руда на всю линию: заданная либо та, у которой больше суммарная добыча кандидатов
            chosenItem = p.oreItem;
            if(chosenItem == 0){
                HashMap<Integer, Float> sum = new HashMap<>();
                for(Cand c : cands) sum.merge(c.item, c.eff, Float::sum);
                float bestSum = -1f;
                for(var e : sum.entrySet()){
                    if(e.getValue() > bestSum || (e.getValue() == bestSum && e.getKey() < chosenItem)){
                        bestSum = e.getValue();
                        chosenItem = e.getKey();
                    }
                }
            }
            final int ci = chosenItem;
            cands.removeIf(c -> c.item != ci);
            for(int i = 0; i < cands.size(); i++) cands.get(i).id = i;
        }
        if(cands.isEmpty()){
            res.error = "no-walls";
            return res;
        }

        //центр масс лучших дробителей - выход ищем поближе к нему
        List<Cand> sorted = new ArrayList<>(cands);
        sorted.sort((a, b) -> Float.compare(b.eff, a.eff));
        double sx = 0, sy = 0, sw = 0;
        for(int i = 0; i < Math.min(60, sorted.size()); i++){
            Cand c = sorted.get(i);
            sx += c.x * c.eff;
            sy += c.y * c.eff;
            sw += c.eff;
        }
        final double cx = sx / sw, cy = sy / sw;

        //положения выхода: по направлениям. Предпочтённое направление недоступно нигде - пробуем любое
        List<int[]> picked = new ArrayList<>();
        for(int pass = 0; pass < 2 && picked.isEmpty(); pass++){
            boolean only = pass == 0 && p.exitDir >= 0 && p.exitDir < 4;
            int perDir = only ? p.exitCandidates : Math.max(4, p.exitCandidates / 4);
            for(int d = 0; d < 4; d++){
                if(only && d != p.exitDir) continue;
                //чуть вперёд по ходу выхода: линия должна кончаться со стороны, куда смотрит, а не посреди скопления дробителей
                final double tx = cx + DX[d] * 4, ty = cy + DY[d] * 4;
                List<int[]> spots = new ArrayList<>();
                for(int x = g.zx0; x <= g.zx1; x++){
                    for(int y = g.zy0; y <= g.zy1; y++){
                        if(exitFits(x, y, d)) spots.add(new int[]{x, y, d});
                    }
                }
                spots.sort(Comparator.comparingDouble(s -> Math.hypot(s[0] - tx, s[1] - ty)));
                int taken = 0;
                List<int[]> mine = new ArrayList<>();
                for(int[] s : spots){
                    boolean near = false;
                    for(int[] q : mine){
                        if(Math.abs(q[0] - s[0]) <= 2 && Math.abs(q[1] - s[1]) <= 2){
                            near = true;
                            break;
                        }
                    }
                    if(near) continue;
                    mine.add(s);
                    if(++taken >= perDir) break;
                }
                picked.addAll(mine);
            }
        }
        if(picked.isEmpty()){
            res.error = "no-room";
            return res;
        }

        bannedGlobal = new boolean[cands.size()];
        Run bestRun = null;
        for(int attempt = 0; attempt < 4; attempt++){
            float bestScore = -Float.MAX_VALUE;
            bestRun = null;
            for(int[] s : picked){
                Run r = new Run();
                placeExit(r, s[0], s[1], s[2]);
                grow(r);
                float sc = runScore(r);
                if(sc > bestScore){
                    bestScore = sc;
                    bestRun = r;
                }
            }
            if(bestRun == null || bestRun.crushers.isEmpty()){
                if(attempt == 0){
                    res.error = "unreachable";
                    return res;
                }
                break;
            }

            res = new Result();
            emit(res, bestRun);
            unlinked.clear();
            if(p.connectPower) power(res, bestRun);
            if(unlinked.isEmpty()) break;
            //часть дробителей не достать лучом: исключаем их и считаем заново (последняя попытка остаётся как есть)
            if(attempt < 3) for(int i : unlinked) bannedGlobal[bestRun.crushers.get(i).id] = true;
        }
        if(bestRun == null){
            res.error = "unreachable";
            return res;
        }
        res.ascii = ascii(res, bestRun);
        return res;
    }

    void emit(Result res, Run r){
        //выходная утка -> магистраль от выхода наружу (по глубине: сначала то, что ближе к выходу), затем дробители
        res.exitX = r.entry % g.w + g.ox;
        res.exitY = r.entry / g.w + g.oy;
        res.exitDir = r.exitDir;
        res.items.add(new Item(DUCT, res.exitX, res.exitY, r.exitDir));

        List<int[]> trunk = new ArrayList<>();
        for(int t : r.ducts){
            if(t == r.entry) continue;
            int depth = 0, cur = t;
            while(cur != r.entry && depth < 5000){
                int cx = cur % g.w + g.ox + DX[r.dir[cur]];
                int cy = cur / g.w + g.oy + DY[r.dir[cur]];
                cur = g.idx(cx, cy);
                depth++;
            }
            trunk.add(new int[]{depth, t});
        }
        trunk.sort(Comparator.comparingInt(a -> a[0]));
        for(int[] e : trunk){
            int t = e[1];
            res.items.add(new Item(DUCT, t % g.w + g.ox, t / g.w + g.oy, r.dir[t]));
        }
        //координата блока в движке: у чётной стороны (2) это левый нижний угол, у нечётной (3) - центр
        int off = ((p.beam ? p.size : 2) - 1) / 2;
        for(Cand c : r.crushers) res.items.add(new Item(DRILL, c.x + off, c.y + off, c.rot));
        if(p.beam && !r.crushers.isEmpty()) res.item = r.crushers.get(0).item;
        for(int t = 0; t < n; t++) if(r.occ[t] == GAP) res.gaps.add(new int[]{t % g.w + g.ox, t / g.w + g.oy});
        res.crushers = r.crushers.size();
        res.ducts = r.ducts.size();
        res.rate = r.rate;
    }

    // ------------------------------------------------------------------------------------------
    // Питание

    final HashMap<Integer, Integer> uf = new HashMap<>();

    int find(int a){
        Integer pa = uf.get(a);
        if(pa == null){
            uf.put(a, a);
            return a;
        }
        if(pa == a) return a;
        int root = find(pa);
        uf.put(a, root);
        return root;
    }

    void union(int a, int b){
        int ra = find(a), rb = find(b);
        if(ra != rb) uf.put(ra, rb);
    }

    int hit(int[] pw, int x, int y, int d){
        for(int j = 1; j <= p.nodeRange; j++){
            int tx = x + DX[d] * j, ty = y + DY[d] * j;
            if(!g.in(tx, ty)) return 0;
            int c = pw[g.idx(tx, ty)];
            if(c == INSULATED) return 0;
            if(c > 0) return c;
        }
        return 0;
    }

    /** К каким компонентам присоединится узел в (x,y): лучи в 4 стороны + соседние энергоблоки. Возвращает число различных корней в out. */
    int attach(int[] pw, int x, int y, int[] out){
        int cnt = 0;
        for(int d = 0; d < 4; d++){
            int c = hit(pw, x, y, d);
            if(c > 0) cnt = addRoot(out, cnt, find(c));
            int nx = x + DX[d], ny = y + DY[d];
            if(g.in(nx, ny)){
                int a = pw[g.idx(nx, ny)];
                if(a > 0) cnt = addRoot(out, cnt, find(a));
            }
        }
        return cnt;
    }

    static int addRoot(int[] out, int cnt, int root){
        for(int i = 0; i < cnt; i++) if(out[i] == root) return cnt;
        out[cnt] = root;
        return cnt + 1;
    }

    boolean nodeSpot(Run r, int[] pw, int t){
        return g.free[t] && r.occ[t] == 0 && pw[t] == 0;
    }

    void power(Result res, Run r){
        int[] pw = g.pw.clone();
        int nextId = NEW_BASE;
        List<Integer> terminalIds = new ArrayList<>();
        for(Cand c : r.crushers){
            int id = nextId++;
            for(int t : c.foot) pw[t] = id;
            terminalIds.add(id);
        }
        uf.clear();
        for(int id : terminalIds) find(id);

        //выбираем существующую сеть: ближайшая с источником (иначе просто ближайшая)
        int gridId = -1;
        double bd = Double.MAX_VALUE;
        double zcx = (g.zx0 + g.zx1) / 2.0, zcy = (g.zy0 + g.zy1) / 2.0;
        for(int pass = 0; pass < 2 && gridId < 0; pass++){
            for(int[] gi : g.grids){
                if(pass == 0 && gi[3] == 0) continue;
                double d = Math.hypot(gi[1] - zcx, gi[2] - zcy);
                if(d < bd){
                    bd = d;
                    gridId = gi[0];
                }
            }
            bd = Double.MAX_VALUE;
        }
        if(gridId > 0){
            find(gridId);
        }
        //существующие сети: одна и та же сеть = один id уже на уровне снимка

        int[] roots = new int[16];
        int guard = 0;
        List<Item> nodes = new ArrayList<>();
        while(guard++ < 400){
            //относящиеся к делу компоненты
            java.util.HashSet<Integer> rel = new java.util.HashSet<>();
            for(int id : terminalIds) rel.add(find(id));
            if(gridId > 0) rel.add(find(gridId));
            if(rel.size() <= 1) break;

            //1) жадно: один узел, склеивающий как можно больше нужных компонентов
            int bestT = -1, bestGain = 1;
            double bestDist = Double.MAX_VALUE;
            for(int t = 0; t < n; t++){
                if(!nodeSpot(r, pw, t)) continue;
                int x = t % g.w + g.ox, y = t / g.w + g.oy;
                int cnt = attach(pw, x, y, roots);
                int k = 0;
                for(int i = 0; i < cnt; i++) if(rel.contains(roots[i])) k++;
                if(k < 2) continue;
                double dd = Math.hypot(x - zcx, y - zcy);
                if(k > bestGain || (k == bestGain && bestT >= 0 && dd < bestDist)){
                    bestGain = k;
                    bestT = t;
                    bestDist = dd;
                }else if(bestT < 0){
                    bestGain = k;
                    bestT = t;
                    bestDist = dd;
                }
            }
            if(bestT >= 0){
                placeNode(res, nodes, pw, bestT, nextId++);
                continue;
            }

            //2) цепочка узлов от главной компоненты к ближайшей другой (BFS по «прыжкам» луча)
            int mainRoot = gridId > 0 ? find(gridId) : find(terminalIds.get(0));
            int[] chain = chainSearch(r, pw, mainRoot, rel);
            if(chain == null){
                res.powerOk = false;
                break;
            }
            for(int t : chain) placeNode(res, nodes, pw, t, nextId++);
        }

        //итоговая проверка «с нуля» - по правилам лучей, а не по инкрементальным объединениям
        res.items.addAll(nodes);
        res.nodes = nodes.size();
        boolean ok = verifyPower(r, pw, nodes, terminalIds, gridId);
        res.powerOk = ok;
        res.gridLinked = gridId > 0 && ok;
    }

    void placeNode(Result res, List<Item> nodes, int[] pw, int t, int id){
        int x = t % g.w + g.ox, y = t / g.w + g.oy;
        pw[t] = id;
        find(id);
        int[] roots = new int[16];
        //объединяем то, к чему он присоединяется (после установки пересчитываем те же правила)
        for(int d = 0; d < 4; d++){
            int c = hit(pw, x, y, d);
            if(c > 0) union(id, c);
            int nx = x + DX[d], ny = y + DY[d];
            if(g.in(nx, ny)){
                int a = pw[g.idx(nx, ny)];
                if(a > 0) union(id, a);
            }
        }
        nodes.add(new Item(NODE, x, y, 0));
    }

    /** BFS по клеткам-кандидатам для узла: возвращает цепочку клеток (не пустую) или null. */
    int[] chainSearch(Run r, int[] pw, int mainRoot, java.util.HashSet<Integer> rel){
        int[] prev = new int[n];
        Arrays.fill(prev, -2);
        ArrayList<Integer> queue = new ArrayList<>();
        int[] roots = new int[16];
        for(int t = 0; t < n; t++){
            if(!nodeSpot(r, pw, t)) continue;
            int x = t % g.w + g.ox, y = t / g.w + g.oy;
            int cnt = attach(pw, x, y, roots);
            boolean touchMain = false;
            for(int i = 0; i < cnt; i++) if(roots[i] == mainRoot) touchMain = true;
            if(touchMain){
                prev[t] = -1;
                queue.add(t);
            }
        }
        for(int qi = 0; qi < queue.size(); qi++){
            int t = queue.get(qi);
            int x = t % g.w + g.ox, y = t / g.w + g.oy;
            //цель: узел здесь присоединится к другой нужной компоненте
            int cnt = attach(pw, x, y, roots);
            for(int i = 0; i < cnt; i++){
                if(roots[i] != mainRoot && rel.contains(roots[i])){
                    ArrayList<Integer> path = new ArrayList<>();
                    for(int c = t; c >= 0; c = prev[c]) path.add(c);
                    int[] arr = new int[path.size()];
                    for(int k = 0; k < arr.length; k++) arr[k] = path.get(k);
                    return arr;
                }
            }
            //прыжки по осям до первого энергоблока
            for(int d = 0; d < 4; d++){
                for(int j = 1; j <= p.nodeRange; j++){
                    int ux = x + DX[d] * j, uy = y + DY[d] * j;
                    if(!g.in(ux, uy)) break;
                    int u = g.idx(ux, uy);
                    if(pw[u] != 0) break;
                    if(!nodeSpot(r, pw, u) || prev[u] != -2) continue;
                    prev[u] = t;
                    queue.add(u);
                }
            }
        }
        return null;
    }

    /** Пересчёт сети с нуля по правилам BeamNode.updateDirections и «два потребителя рядом не проводят». */
    boolean verifyPower(Run r, int[] pw, List<Item> nodes, List<Integer> terminals, int gridId){
        HashMap<Integer, Integer> p2 = new HashMap<>();
        java.util.function.IntUnaryOperator[] f = new java.util.function.IntUnaryOperator[1];
        f[0] = a -> {
            Integer pa = p2.get(a);
            if(pa == null){
                p2.put(a, a);
                return a;
            }
            if(pa == a) return a;
            int root = f[0].applyAsInt(pa);
            p2.put(a, root);
            return root;
        };
        for(Item nd : nodes){
            int x = nd.x, y = nd.y;
            int id = pw[g.idx(x, y)];
            f[0].applyAsInt(id);
            for(int d = 0; d < 4; d++){
                int c = hit(pw, x, y, d);
                if(c > 0) p2.put(f[0].applyAsInt(id), f[0].applyAsInt(c));
                int nx = x + DX[d], ny = y + DY[d];
                if(g.in(nx, ny)){
                    int a = pw[g.idx(nx, ny)];
                    if(a > 0) p2.put(f[0].applyAsInt(id), f[0].applyAsInt(a));
                }
            }
        }
        //главная компонента - та, где больше всего дробителей: «плохие» это те, что остались вне неё
        HashMap<Integer, Integer> count = new HashMap<>();
        for(int id : terminals) count.merge(f[0].applyAsInt(id), 1, Integer::sum);
        int root = -1, most = -1;
        for(var e : count.entrySet()){
            if(e.getValue() > most){
                most = e.getValue();
                root = e.getKey();
            }
        }
        for(int i = 0; i < terminals.size(); i++){
            if(f[0].applyAsInt(terminals.get(i)) != root) unlinked.add(i);
        }
        if(!unlinked.isEmpty()) return false;
        if(gridId > 0 && f[0].applyAsInt(gridId) != root) return false;
        return true;
    }

    // ------------------------------------------------------------------------------------------

    String ascii(Result res, Run r){
        char[] ch = new char[n];
        for(int t = 0; t < n; t++){
            if(g.sand[t] > 0 || (p.beam && g.ore[t] > 0 && g.solid[t])) ch[t] = '#';
            else if(p.beam && g.solid[t]) ch[t] = 'X';
            else if(!g.free[t]) ch[t] = 'x';
            else ch[t] = '.';
            if(g.pw[t] > 0) ch[t] = 'P';
        }
        for(Item it : res.items){
            switch(it.kind){
                case DRILL -> {
                    char c = "ABCD".charAt(it.rot);
                    int sz = p.beam ? p.size : 2, off = (sz - 1) / 2;
                    for(int dx = 0; dx < sz; dx++) for(int dy = 0; dy < sz; dy++) ch[g.idx(it.x - off + dx, it.y - off + dy)] = c;
                }
                case DUCT -> ch[g.idx(it.x, it.y)] = ">^<v".charAt(it.rot);
                case NODE -> ch[g.idx(it.x, it.y)] = 'n';
                default -> {
                }
            }
        }
        //зазор между бурами и рудой (под трубы)
        for(int[] q : res.gaps) if(ch[g.idx(q[0], q[1])] == '.') ch[g.idx(q[0], q[1])] = '~';
        //свободная полоса перед выходом
        for(int k = 1; k <= p.exitLane; k++){
            int lx = res.exitX + DX[res.exitDir] * k, ly = res.exitY + DY[res.exitDir] * k;
            if(g.in(lx, ly)) ch[g.idx(lx, ly)] = '*';
        }
        StringBuilder sb = new StringBuilder();
        for(int y = g.oy + g.h - 1; y >= g.oy; y--){
            for(int x = g.ox; x < g.ox + g.w; x++){
                sb.append(ch[g.idx(x, y)]);
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
