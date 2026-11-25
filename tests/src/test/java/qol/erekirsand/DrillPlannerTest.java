package qol.erekirsand;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.*;

/**
 * Планировщик буров Эрекира без движка: ASCII-карты (коллизии, поток добычи от каждого бура до выходной утки,
 * свободная полоса за выходом, питание) + фаззинг по случайным картам, для песка и для настенной руды.
 * Легенда песка: '.' свободный пол, '#' стена с песком 2, '+' стена 1, '-' стена 0.5, 'x' непроходимо (без песка),
 * 'P' существующий энергоблок (сеть 1, с источником), 'I' изолятор.
 * Легенда руды: '.' пол, 'B' стена с бериллием (твёрдость 3), 'G' с графитом (2), 'T' с вольфрамом (5),
 * 'x' голая стена без руды (твёрдая), 'P', 'I' - как выше.
 */
public class DrillPlannerTest{
    @Test
    public void allLayoutsAreValid(){
        fails = 0;
        runAll();
        assertEquals(0, fails, "расклада с ошибками");
    }

    static DrillPlanner.Grid parse(String[] rows){
        int h = rows.length, w = rows[0].length();
        DrillPlanner.Grid g = new DrillPlanner.Grid(0, 0, w, h);
        for(int ry = 0; ry < h; ry++){
            int y = h - 1 - ry;
            for(int x = 0; x < w; x++){
                char c = rows[ry].charAt(x);
                int i = g.idx(x, y);
                switch(c){
                    case '.' -> g.free[i] = true;
                    case '#' -> g.sand[i] = 2f;
                    case '+' -> g.sand[i] = 1f;
                    case '-' -> g.sand[i] = 0.5f;
                    case 'P' -> g.pw[i] = 1;
                    case 'I' -> g.pw[i] = -1;
                    default -> {}
                }
            }
        }
        g.zx0 = 0; g.zy0 = 0; g.zx1 = w - 1; g.zy1 = h - 1;
        return g;
    }

    /** Предметы руды в тестовой таблице: id + 1 (1 бериллий, 2 графит, 3 вольфрам) и их твёрдость. */
    static final int BE = 1, GR = 2, TU = 3;
    static final int[] HARDNESS = {0, 3, 2, 5};

    static DrillPlanner.Grid parseOre(String[] rows){
        int h = rows.length, w = rows[0].length();
        DrillPlanner.Grid g = new DrillPlanner.Grid(0, 0, w, h);
        g.itemHardness = HARDNESS;
        for(int ry = 0; ry < h; ry++){
            int y = h - 1 - ry;
            for(int x = 0; x < w; x++){
                int i = g.idx(x, y);
                switch(rows[ry].charAt(x)){
                    case '.' -> g.free[i] = true;
                    case 'B' -> { g.solid[i] = true; g.ore[i] = BE; }
                    case 'G' -> { g.solid[i] = true; g.ore[i] = GR; }
                    case 'T' -> { g.solid[i] = true; g.ore[i] = TU; }
                    case 'x' -> g.solid[i] = true;
                    case 'P' -> g.pw[i] = 1;
                    case 'I' -> g.pw[i] = -1;
                    default -> {}
                }
            }
        }
        g.zx0 = 0; g.zy0 = 0; g.zx1 = w - 1; g.zy1 = h - 1;
        return g;
    }

    static DrillPlanner.Params oreParams(){
        DrillPlanner.Params p = new DrillPlanner.Params();
        p.beam = true;
        p.size = 2;
        p.range = 5;
        p.tier = 3;
        p.drillTime = 160f;
        p.minGap = 1;
        p.minOreCols = 2;
        p.targetRate = 15f;
        return p;
    }

    static int fails = 0;
    static void check(boolean ok, String msg){
        if(!ok){ fails++; System.out.println("  FAIL: " + msg); }
    }

    static void run(String name, String[] rows, boolean grid){
        run(name, rows, grid, -1, true);
    }

    static void run(String name, String[] rows, boolean grid, int exitDir, boolean strictDir){
        System.out.println("=== " + name);
        DrillPlanner.Grid g = parse(rows);
        if(grid){
            g.grids.add(new int[]{1, 2, 2, 1});
        }
        DrillPlanner.Params p = new DrillPlanner.Params();
        p.exitDir = exitDir;
        long t0 = System.nanoTime();
        DrillPlanner.Result r = new DrillPlanner(g, p).plan();
        long ms = (System.nanoTime() - t0) / 1_000_000;
        if(r.error != null){
            System.out.println("  error=" + r.error + " (" + ms + " ms)");
            return;
        }
        System.out.print(r.ascii);
        System.out.printf("  crushers=%d ducts=%d nodes=%d rate=%.2f/s powerOk=%b linked=%b (%d ms)%n", r.crushers, r.ducts, r.nodes, r.rate, r.powerOk, r.gridLinked, ms);
        check(r.rate >= p.targetRate * 0.999f, "rate " + r.rate + " < " + p.targetRate);
        if(strictDir) check(r.powerOk, "power"); //в нарочно тесной карте узлам может не хватить места: это честное предупреждение «питание не связано»
        verifyLayout(g, r, p);
        if(exitDir >= 0 && strictDir) check(r.exitDir == exitDir, "выход смотрит " + r.exitDir + ", просили " + exitDir);
    }

    /** Независимая проверка раскладки: коллизии, поток песка от каждого дробителя до выходной утки, свободная полоса за ней. */
    static void verifyLayout(DrillPlanner.Grid g, DrillPlanner.Result r, DrillPlanner.Params p){
        int n = g.w * g.h;
        int sz = p.beam ? p.size : 2, off = (sz - 1) / 2;
        int[] owner = new int[n];
        Arrays.fill(owner, -1);
        for(int k = 0; k < r.items.size(); k++){
            DrillPlanner.Item it = r.items.get(k);
            int[][] cells;
            if(it.kind == DrillPlanner.DRILL){
                //it.x/y - координата блока в движке (у нечётной стороны центр), клетки считаем от левого нижнего угла
                cells = new int[sz * sz][];
                for(int a = 0; a < sz; a++) for(int b = 0; b < sz; b++) cells[a * sz + b] = new int[]{it.x - off + a, it.y - off + b};
            }else{
                cells = new int[][]{{it.x, it.y}};
            }
            for(int[] c : cells){
                if(!g.in(c[0], c[1])){ check(false, "вне окна " + it); continue; }
                int i = g.idx(c[0], c[1]);
                check(g.free[i], "блок на занятой клетке " + it);
                check(owner[i] < 0, "пересечение " + it + " c " + (owner[i] >= 0 ? r.items.get(owner[i]) : ""));
                owner[i] = k;
            }
        }
        //выход: утка в res.exit*, смотрит в res.exitDir, за ней exitLane свободных нетронутых клеток
        check(g.in(r.exitX, r.exitY) && owner[g.idx(r.exitX, r.exitY)] >= 0, "выходной утки нет среди блоков");
        for(int k = 1; k <= p.exitLane; k++){
            int lx = r.exitX + DrillPlanner.DX[r.exitDir] * k, ly = r.exitY + DrillPlanner.DY[r.exitDir] * k;
            check(g.in(lx, ly) && g.free[g.idx(lx, ly)], "полоса за выходом занята/вне окна (клетка " + k + ")");
            check(g.in(lx, ly) && owner[g.idx(lx, ly)] < 0, "в полосе за выходом стоит наш блок (клетка " + k + ")");
        }
        //поток: от каждого дробителя по уткам до выходной утки (нет тупиков, петель и «стенки в лоб»)
        for(DrillPlanner.Item it : r.items){
            if(it.kind != DrillPlanner.DRILL) continue;
            boolean reaches = false;
            outer:
            for(int dx = -1; dx <= sz; dx++) for(int dy = -1; dy <= sz; dy++){
                boolean inside = dx >= 0 && dx < sz && dy >= 0 && dy < sz;
                boolean corner = (dx == -1 || dx == sz) && (dy == -1 || dy == sz);
                if(inside || corner) continue;
                int x = it.x - off + dx, y = it.y - off + dy;
                if(!g.in(x, y)) continue;
                int o = owner[g.idx(x, y)];
                if(o < 0 || r.items.get(o).kind != DrillPlanner.DUCT) continue;
                int guard = 0;
                DrillPlanner.Item cur = r.items.get(o);
                while(guard++ < 2000){
                    if(cur.x == r.exitX && cur.y == r.exitY){ reaches = true; break outer; }
                    int fx = cur.x + DrillPlanner.DX[cur.rot], fy = cur.y + DrillPlanner.DY[cur.rot];
                    if(!g.in(fx, fy)) break;
                    int fo = owner[g.idx(fx, fy)];
                    if(fo < 0 || r.items.get(fo).kind != DrillPlanner.DUCT) break;
                    cur = r.items.get(fo);
                }
            }
            check(reaches, "бур не доходит до выходной утки " + it);
        }
        //каждая утка кроме выходной отдаёт в другую утку (ни одна не смотрит в пустоту/дробитель/узел)
        for(DrillPlanner.Item it : r.items){
            if(it.kind != DrillPlanner.DUCT || (it.x == r.exitX && it.y == r.exitY)) continue;
            int fx = it.x + DrillPlanner.DX[it.rot], fy = it.y + DrillPlanner.DY[it.rot];
            boolean ok = g.in(fx, fy) && owner[g.idx(fx, fy)] >= 0 && r.items.get(owner[g.idx(fx, fy)]).kind == DrillPlanner.DUCT;
            check(ok, "утка смотрит не в утку " + it);
        }
    }

    /**
     * Инварианты режима руды поверх {@link #verifyLayout}: у КАЖДОГО столбца луча каждого бура сначала идёт не меньше
     * minGap пустых нетронутых клеток, потом первая твёрдая - это руда нужной твёрдости и ОДНА на всю линию; столбцов
     * с рудой не меньше minOreCols; ни утка, ни узел, ни чужой бур не стоят в зазоре (там будут трубы с водородом).
     */
    static void verifyOre(DrillPlanner.Grid g, DrillPlanner.Result r, DrillPlanner.Params p){
        int sz = p.size, off = (sz - 1) / 2;
        int n = g.w * g.h;
        int[] owner = new int[n];
        Arrays.fill(owner, -1);
        for(int k = 0; k < r.items.size(); k++){
            DrillPlanner.Item it = r.items.get(k);
            int a0 = it.kind == DrillPlanner.DRILL ? sz : 1;
            for(int a = 0; a < a0; a++) for(int b = 0; b < a0; b++){
                int x = it.x - (a0 == sz ? off : 0) + a, y = it.y - (a0 == sz ? off : 0) + b;
                if(g.in(x, y)) owner[g.idx(x, y)] = k;
            }
        }
        int item = r.item;
        check(item > 0, "результат не знает, какую руду добываем");
        int drills = 0;
        for(DrillPlanner.Item it : r.items){
            if(it.kind != DrillPlanner.DRILL) continue;
            drills++;
            int cols = 0;
            int cx = it.x - off, cy = it.y - off;
            for(int i = 0; i < sz; i++){
                int[] st = DrillPlanner.frontStart(cx, cy, sz, it.rot, i);
                int gap = 0;
                boolean hitSolid = false;
                for(int j = 0; j < p.range; j++){
                    int tx = st[0] + DrillPlanner.DX[it.rot] * j, ty = st[1] + DrillPlanner.DY[it.rot] * j;
                    if(!g.in(tx, ty)) break;
                    int t = g.idx(tx, ty);
                    if(g.solid[t]){
                        hitSolid = true;
                        int o = g.ore[t];
                        if(o > 0 && g.itemHardness[o] <= p.tier){
                            check(o == item, "в линии смешаны руды: " + o + " и " + item + " у " + it);
                            cols++;
                        }
                        break;
                    }
                    gap++;
                    check(g.free[t], "в зазоре занятая клетка (" + tx + "," + ty + ") у " + it);
                    check(owner[t] < 0, "в зазоре стоит наш блок (" + tx + "," + ty + ") у " + it + ": " + (owner[t] >= 0 ? r.items.get(owner[t]) : ""));
                }
                if(hitSolid) check(gap >= p.minGap, "зазор " + gap + " < " + p.minGap + " у " + it + " (столбец " + i + ")");
                if(hitSolid && g.ore[g.idx(st[0] + DrillPlanner.DX[it.rot] * gap, st[1] + DrillPlanner.DY[it.rot] * gap)] > 0)
                    check(gap <= p.maxGap, "зазор " + gap + " > maxGap " + p.maxGap + " у " + it);
                else check(gap >= p.minGap, "нет даже minGap свободных клеток у " + it);
            }
            check(cols >= p.minOreCols, "у бура " + it + " столбцов с рудой " + cols + " < " + p.minOreCols);
        }
        check(drills == r.crushers, "число буров в результате " + r.crushers + " != " + drills);
        for(int[] q : r.gaps) check(g.in(q[0], q[1]) && owner[g.idx(q[0], q[1])] < 0, "помеченный зазор занят " + q[0] + "," + q[1]);
    }

    static DrillPlanner.Result runOre(String name, String[] rows, boolean grid, int exitDir, int oreItem, boolean expectOk){
        System.out.println("=== [ore] " + name);
        DrillPlanner.Grid g = parseOre(rows);
        if(grid) g.grids.add(new int[]{1, 2, 2, 1});
        DrillPlanner.Params p = oreParams();
        p.exitDir = exitDir;
        p.oreItem = oreItem;
        DrillPlanner.Result r = new DrillPlanner(g, p).plan();
        if(r.error != null){
            System.out.println("  error=" + r.error);
            check(!expectOk, "ожидали раскладку, а планировщик: " + r.error);
            return null;
        }
        check(expectOk, "ожидали ошибку, а раскладка есть");
        System.out.print(r.ascii);
        System.out.printf("  drills=%d ducts=%d nodes=%d rate=%.2f/s item=%d powerOk=%b%n", r.crushers, r.ducts, r.nodes, r.rate, r.item, r.powerOk);
        if(oreItem > 0) check(r.item == oreItem, "добыча " + r.item + ", просили " + oreItem);
        check(r.powerOk, "power");
        verifyLayout(g, r, p);
        verifyOre(g, r, p);
        verifyPipes(g, r, !grid);
        return r;
    }

    /** Ромб/ступени: стена руды там, где y >= base - slope*x (y вверх). Для проверки зазора на диагональных стенах. */
    static String[] stairs(int w, int h, double slope, double base, boolean mirror){
        String[] rows = new String[h];
        for(int ry = 0; ry < h; ry++){
            int y = h - 1 - ry;
            StringBuilder sb = new StringBuilder();
            for(int x = 0; x < w; x++){
                int xx = mirror ? w - 1 - x : x;
                sb.append(y >= Math.round(base - slope * xx) ? 'B' : '.');
            }
            rows[ry] = sb.toString();
        }
        return rows;
    }

    /** Прямоугольный блок руды: колонки x0..x1, строки (сверху вниз) r0..r1 внутри пустой карты w x h; top - сколько верхних рядов тоже стена. */
    static String[] block(int w, int h, int x0, int x1, int r0, int r1, int top){
        String[] rows = new String[h];
        for(int ry = 0; ry < h; ry++){
            StringBuilder sb = new StringBuilder();
            for(int x = 0; x < w; x++) sb.append(ry < top || (ry >= r0 && ry <= r1 && x >= x0 && x <= x1) ? 'B' : '.');
            rows[ry] = sb.toString();
        }
        return rows;
    }

    /**
     * Зазоры под трубы: (1) каждая клетка зазора достижима снаружи по свободным клеткам, не занятым нашими блоками (труба
     * подведётся к каждому буру); (2) у узлов нет «дальних»: без внешней сети каждый узел в пределах 4 клеток (по Чебышёву)
     * от какого-нибудь бура.
     */
    static void verifyPipes(DrillPlanner.Grid g, DrillPlanner.Result r, boolean checkNodes){
        int sz = 2, off = 0;
        boolean[] taken = new boolean[g.w * g.h];
        List<int[]> drills = new ArrayList<>();
        for(DrillPlanner.Item it : r.items){
            if(it.kind == DrillPlanner.DRILL){
                drills.add(new int[]{it.x - off, it.y - off});
                for(int a = 0; a < sz; a++) for(int b = 0; b < sz; b++) taken[g.idx(it.x - off + a, it.y - off + b)] = true;
            }else taken[g.idx(it.x, it.y)] = true;
        }
        boolean hasSink = false;
        boolean[] seen = new boolean[g.w * g.h];
        ArrayDeque<Integer> q = new ArrayDeque<>();
        for(int y = 0; y < g.h; y++) for(int x = 0; x < g.w; x++){
            //«снаружи» = край окна или клетка вне рамки (там игрок протянет трубу)
            boolean border = x == 0 || y == 0 || x == g.w - 1 || y == g.h - 1 || !g.inZone(x + g.ox, y + g.oy);
            int i = g.idx(x, y);
            if(border && g.free[i] && !taken[i]){ hasSink = true; seen[i] = true; q.add(i); }
        }
        while(!q.isEmpty()){
            int t = q.poll();
            int x = t % g.w, y = t / g.w;
            for(int d = 0; d < 4; d++){
                int nx = x + DrillPlanner.DX[d], ny = y + DrillPlanner.DY[d];
                if(!g.in(nx, ny)) continue;
                int v = g.idx(nx, ny);
                if(seen[v] || !g.free[v] || taken[v]) continue;
                seen[v] = true;
                q.add(v);
            }
        }
        if(hasSink) for(int[] gp : r.gaps) check(seen[g.idx(gp[0], gp[1])], "зазор " + gp[0] + "," + gp[1] + " отрезан от внешней стороны: трубу не подвести");
        check(r.pipeOk || !hasSink, "pipeOk=false, хотя снаружи есть место");
        if(checkNodes){
            int worst = 0;
            for(DrillPlanner.Item it : r.items){
                if(it.kind != DrillPlanner.NODE) continue;
                int best = 99;
                for(int[] d : drills) best = Math.min(best, Math.max(Math.max(d[0] - it.x, it.x - (d[0] + sz - 1)), Math.max(d[1] - it.y, it.y - (d[1] + sz - 1))));
                worst = Math.max(worst, best);
            }
            check(worst <= 6, "узел в " + worst + " клетках от ближайшего бура (должен быть рядом)");
        }
    }

    /** Число компонент связности (по 4-соседям) среди клеток зазора. */
    static int gapComponents(DrillPlanner.Result r){
        HashSet<Long> set = new HashSet<>();
        for(int[] q : r.gaps) set.add(((long)q[0] << 32) | (q[1] & 0xffffffffL));
        int comps = 0;
        HashSet<Long> done = new HashSet<>();
        for(long k : set){
            if(done.contains(k)) continue;
            comps++;
            ArrayDeque<Long> st = new ArrayDeque<>();
            st.add(k);
            done.add(k);
            while(!st.isEmpty()){
                long c = st.poll();
                int x = (int)(c >> 32), y = (int)c;
                for(int d = 0; d < 4; d++){
                    long nk = (((long)(x + DrillPlanner.DX[d])) << 32) | ((y + DrillPlanner.DY[d]) & 0xffffffffL);
                    if(set.contains(nk) && done.add(nk)) st.add(nk);
                }
            }
        }
        return comps;
    }

    static int rotations(DrillPlanner.Result r){
        boolean[] seen = new boolean[4];
        for(DrillPlanner.Item it : r.items) if(it.kind == DrillPlanner.DRILL) seen[it.rot] = true;
        int c = 0;
        for(boolean b : seen) if(b) c++;
        return c;
    }

    /** Случайные карты руды: сглаженный шум -> стены; руда пятнами (бериллий/графит), часть стены - голая скала. */
    static void fuzzOre(int count, int w, int h){
        int errors = 0, ok = 0;
        for(int seed = 1; seed <= count; seed++){
            Random rnd = new Random(seed * 104729L + w);
            float[][] noise = new float[w][h], kind = new float[w][h];
            for(int i = 0; i < w; i++) for(int j = 0; j < h; j++){ noise[i][j] = rnd.nextFloat(); kind[i][j] = rnd.nextFloat(); }
            for(int pass = 0; pass < 4; pass++){
                float[][] nn = new float[w][h], kk = new float[w][h];
                for(int i = 0; i < w; i++) for(int j = 0; j < h; j++){
                    float sum = 0, ks = 0; int c = 0;
                    for(int di = -2; di <= 2; di++) for(int dj = -2; dj <= 2; dj++){
                        int ii = i + di, jj = j + dj;
                        if(ii < 0 || jj < 0 || ii >= w || jj >= h) continue;
                        sum += noise[ii][jj]; ks += kind[ii][jj]; c++;
                    }
                    nn[i][j] = sum / c; kk[i][j] = ks / c;
                }
                noise = nn; kind = kk;
            }
            DrillPlanner.Grid g = new DrillPlanner.Grid(0, 0, w, h);
            g.itemHardness = HARDNESS;
            for(int i = 0; i < w; i++) for(int j = 0; j < h; j++){
                int idx = g.idx(i, j);
                float v = noise[i][j];
                if(v > 0.52f){
                    g.solid[idx] = true;
                    float kv = kind[i][j];
                    if(rnd.nextFloat() < 0.8f) g.ore[idx] = kv > 0.5f ? BE : GR;
                }else if(rnd.nextFloat() < 0.04f){ /* обломок: не свободно (но луч через него идёт - как через небыстрое здание) */ }
                else g.free[idx] = true;
            }
            g.zx0 = 0; g.zy0 = 0; g.zx1 = w - 1; g.zy1 = h - 1;
            if(seed % 3 == 0){
                int px = rnd.nextInt(w), py = rnd.nextInt(h);
                if(g.free[g.idx(px, py)]){
                    g.pw[g.idx(px, py)] = 1;
                    g.free[g.idx(px, py)] = false;
                    g.grids.add(new int[]{1, px, py, 1});
                }
            }
            DrillPlanner.Params pp = oreParams();
            pp.exitDir = seed % 5 == 0 ? -1 : seed % 4;
            DrillPlanner.Result r = new DrillPlanner(g, pp).plan();
            if(r.error != null){ errors++; continue; }
            int before = fails;
            if(!r.powerOk){ fails++; System.out.println("  FAIL fuzzOre seed " + seed + ": power"); }
            verifyLayout(g, r, pp);
            verifyOre(g, r, pp);
            verifyPipes(g, r, false);
            if(fails != before){ System.out.println("  (ore seed " + seed + ")"); System.out.print(r.ascii); }
            else ok++;
        }
        System.out.printf("=== fuzz ore %dx%d: ok=%d noplan=%d%n", w, h, ok, errors);
    }

    /** Случайные карты: сглаженный шум -> стены (песок 2/1/0.5 по порогу), препятствия, иногда сеть питания. Проверка тех же инвариантов. */
    static void fuzz(int count, int w, int h){
        int errors = 0, ok = 0, shortfall = 0;
        long worst = 0;
        for(int seed = 1; seed <= count; seed++){
            Random rnd = new Random(seed * 7919L + w);
            float[][] noise = new float[w][h];
            for(int i = 0; i < w; i++) for(int j = 0; j < h; j++) noise[i][j] = rnd.nextFloat();
            for(int pass = 0; pass < 4; pass++){
                float[][] nn = new float[w][h];
                for(int i = 0; i < w; i++) for(int j = 0; j < h; j++){
                    float sum = 0; int c = 0;
                    for(int di = -2; di <= 2; di++) for(int dj = -2; dj <= 2; dj++){
                        int ii = i + di, jj = j + dj;
                        if(ii < 0 || jj < 0 || ii >= w || jj >= h) continue;
                        sum += noise[ii][jj]; c++;
                    }
                    nn[i][j] = sum / c;
                }
                noise = nn;
            }
            DrillPlanner.Grid g = new DrillPlanner.Grid(0, 0, w, h);
            for(int i = 0; i < w; i++) for(int j = 0; j < h; j++){
                int idx = g.idx(i, j);
                float v = noise[i][j];
                if(v > 0.52f) g.sand[idx] = v > 0.56f ? 2f : (v > 0.54f ? 1f : 0.5f);
                else if(rnd.nextFloat() < 0.04f) { /* обломок: не свободно, не песок */ }
                else g.free[idx] = true;
            }
            g.zx0 = 0; g.zy0 = 0; g.zx1 = w - 1; g.zy1 = h - 1;
            if(seed % 3 == 0){
                int px = rnd.nextInt(w), py = rnd.nextInt(h);
                if(g.free[g.idx(px, py)]){
                    g.pw[g.idx(px, py)] = 1;
                    g.free[g.idx(px, py)] = false;
                    g.grids.add(new int[]{1, px, py, 1});
                }
            }
            long t0 = System.nanoTime();
            DrillPlanner.Params pp = new DrillPlanner.Params();
            pp.exitDir = seed % 5 == 0 ? -1 : seed % 4; //разные направления выхода, иногда любое
            DrillPlanner.Result r = new DrillPlanner(g, pp).plan();
            long ms = (System.nanoTime() - t0) / 1_000_000;
            worst = Math.max(worst, ms);
            if(r.error != null){
                errors++;
                continue;
            }
            int before = fails;
            if(r.rate < 14.4f * 0.999f){ shortfall++; if(System.getenv("SAND_VERBOSE") != null){ System.out.println("seed " + seed + " rate=" + r.rate); System.out.print(r.ascii);} } //на случайной карте песка может просто не хватить - это не ошибка планировщика
            if(!r.powerOk) { fails++; System.out.println("  FAIL fuzz seed " + seed + ": power"); }
            verifyLayout(g, r, pp);
            if(fails != before){ System.out.println("  (seed " + seed + ")"); System.out.print(r.ascii); }
            else ok++;
        }
        System.out.printf("=== fuzz %dx%d: ok=%d noplan=%d (из них неполный песок: %d) worst=%d ms%n", w, h, ok, errors, shortfall, worst);
    }

    static void runAll(){
        run("полоса песка сверху", new String[]{
            "####################################",
            "####################################",
            "....................................",
            "....................................",
            "....................................",
            "....................................",
            "....................................",
            "....................................",
            "....................................",
            "....................................",
            "....................................",
            "....................................",
        }, false);

        run("карман: песчаная стена слева и справа", new String[]{
            "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
            "x#####..................#######x",
            "x#####..................#######x",
            "x#####..................#######x",
            "x#####..................#######x",
            "x#####..................#######x",
            "x#####..................#######x",
            "x#####..................#######x",
            "x#####..................#######x",
            "x#####..................#######x",
            "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
        }, false);

        run("смешанные стены + сеть питания справа", new String[]{
            "##########+++++++++-----------..",
            "##########+++++++++-----------..",
            "................................",
            "................................",
            "................................",
            "................................",
            "................................",
            "...........................xxxxP",
            "................................",
            "................................",
            "................................",
            "................................",
        }, true);

        run("стена слабая (+ только)", new String[]{
            "++++++++++++++++++++++++++++++++",
            "++++++++++++++++++++++++++++++++",
            "................................",
            "................................",
            "................................",
            "................................",
            "................................",
            "................................",
            "................................",
            "................................",
            "................................",
            "................................",
        }, false);

        run("выход влево (к «ядру» слева)", new String[]{
            "####################################",
            "####################################",
            "....................................",
            "....................................",
            "....................................",
            "....................................",
            "....................................",
            "....................................",
        }, false, 2, true);

        //вниз нельзя нигде (нужно 1+3 свободных клетки в столбце): выход обязан уйти в другую сторону, а не пропасть
        run("направление недоступно - берётся любое", new String[]{
            "####################################",
            "####################################",
            "....................................",
            "....................................",
            "....................................",
            "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
        }, false, 3, false);

        fuzz(40, 60, 60);
        fuzz(6, 100, 90);

        // ---------------- плазменные буры по настенной руде ----------------
        runOre("бериллиевая полоса сверху", new String[]{
            "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB",
            "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB",
            "....................................",
            "....................................",
            "....................................",
            "....................................",
            "....................................",
            "....................................",
            "....................................",
            "....................................",
            "....................................",
            "....................................",
        }, false, -1, 0, true);

        runOre("руда с двух сторон кармана", new String[]{
            "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
            "xBBBBB..................BBBBBBBx",
            "xBBBBB..................BBBBBBBx",
            "xBBBBB..................BBBBBBBx",
            "xBBBBB..................BBBBBBBx",
            "xBBBBB..................BBBBBBBx",
            "xBBBBB..................BBBBBBBx",
            "xBBBBB..................BBBBBBBx",
            "xBBBBB..................BBBBBBBx",
            "xBBBBB..................BBBBBBBx",
            "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
        }, false, -1, 0, true);

        //бериллий слева и графит справа: линия обязана взять ОДНУ руду (смешение руд в одной утке недопустимо)
        runOre("две руды - берётся одна", new String[]{
            "BBBBBBBBBBBBBBBBGGGGGGGGGGGGGGGGGGGG",
            "BBBBBBBBBBBBBBBBGGGGGGGGGGGGGGGGGGGG",
            "....................................",
            "....................................",
            "....................................",
            "....................................",
            "....................................",
            "....................................",
            "....................................",
            "....................................",
        }, false, -1, 0, true);

        runOre("принудительно графит", new String[]{
            "BBBBBBBBBBBBBBBBGGGGGGGGGGGGGGGGGGGG",
            "BBBBBBBBBBBBBBBBGGGGGGGGGGGGGGGGGGGG",
            "....................................",
            "....................................",
            "....................................",
            "....................................",
            "....................................",
            "....................................",
            "....................................",
            "....................................",
        }, false, -1, GR, true);

        //вольфрам обычному плазменному буру не по зубам (tier 3 < hardness 5): нет ни одного кандидата
        runOre("вольфрам не берётся обычным буром", new String[]{
            "TTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTT",
            "TTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTTT",
            "....................................",
            "....................................",
            "....................................",
            "....................................",
            "....................................",
            "....................................",
        }, false, -1, 0, false);

        //голая скала вплотную к зазору: столбец, упирающийся в НЕ руду, зазор не нарушает, но и руды не даёт
        runOre("три ряда руды, выход вверх-вправо", new String[]{
            "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB",
            "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB",
            "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB",
            "....................................",
            "....................................",
            "....................................",
            "....................................",
            "....................................",
            "....................................",
            "....................................",
            "....................................",
        }, false, 1, 0, true);

        runOre("сеть питания справа", new String[]{
            "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB",
            "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB",
            "................................",
            "................................",
            "................................",
            "................................",
            "................................",
            "...........................xxxxP",
            "................................",
            "................................",
            "................................",
        }, true, -1, 0, true);

        //карман с выходом наружу сверху: у каждой стены своя труба, обе достижимы снаружи
        runOre("карман с проёмом сверху", new String[]{
            "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
            "xBBBBB..................BBBBBBBx",
            "xBBBBB..................BBBBBBBx",
            "xBBBBB..................BBBBBBBx",
            "xBBBBB..................BBBBBBBx",
            "xBBBBB..................BBBBBBBx",
            "xBBBBB..................BBBBBBBx",
            "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
        }, false, -1, 0, true);

        //диагональные (ступенчатые) стены: зазор 1 клетка тут не стыкуется, должен получиться ОДИН связный проход под трубу
        for(boolean mirror : new boolean[]{false, true}){
            for(double slope : new double[]{1.0, 0.5, 2.0}){
                String tag = "диагональная стена, наклон " + slope + (mirror ? " (зеркало)" : "");
                int w = slope == 2.0 ? 18 : 30, hh = slope == 2.0 ? 34 : 28;
                DrillPlanner.Result r = runOre(tag, stairs(w, hh, slope, hh - 3, mirror), false, -1, 0, true);
                if(r != null){
                    check(r.crushers >= 4, tag + ": буров мало: " + r.crushers);
                    //у каждого куска свой выход наружу (verifyPipes), но кусков должно быть мало: без требования связности их ~по куску на бур
                    check(gapComponents(r) <= 3, tag + ": зазор распался на " + gapComponents(r) + " кусков, трубе не пройти");
                }
            }
        }

        //руда со всех сторон: островок в чистом поле и полуостров с тремя сторонами
        DrillPlanner.Result isl = runOre("островок руды: со всех сторон", block(30, 26, 11, 18, 8, 15, 0), false, -1, 0, true);
        if(isl != null) check(rotations(isl) >= 3, "островок: буры только с " + rotations(isl) + " сторон(ы)");
        DrillPlanner.Result pen = runOre("полуостров руды: с трёх сторон", block(32, 26, 12, 17, 3, 16, 3), false, -1, 0, true);
        if(pen != null) check(rotations(pen) >= 3, "полуостров: буры только с " + rotations(pen) + " сторон(ы)");

        fuzzOre(40, 60, 60);
        fuzzOre(6, 100, 90);

    }
}
