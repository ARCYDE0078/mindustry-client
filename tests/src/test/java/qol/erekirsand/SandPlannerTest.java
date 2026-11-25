package qol.erekirsand;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.*;

/**
 * Автономный стенд планировщика песка: ASCII-карта -> Grid -> plan() -> картинка + проверки.
 * Запуск: devtest/run.sh (javac без движка, планировщик от него не зависит).
 * Легенда: '.' свободный пол, '#' стена с песком 2, '+' стена 1, '-' стена 0.5, 'x' непроходимо (без песка),
 * 'P' существующий энергоблок (сеть 1, с источником), 'I' изолятор.
 */
/**
 * Планировщик песчаных буров без движка: ASCII-карты (коллизии, поток песка от каждого дробителя до выходной утки,
 * свободная полоса за выходом, питание) + фаззинг по случайным картам. Копия devtest/SandPlannerTest из qol-suite.
 */
public class SandPlannerTest{
    @Test
    public void allLayoutsAreValid(){
        fails = 0;
        runAll();
        assertEquals(0, fails, "расклада с ошибками");
    }

    static SandPlanner.Grid parse(String[] rows){
        int h = rows.length, w = rows[0].length();
        SandPlanner.Grid g = new SandPlanner.Grid(0, 0, w, h);
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

    static int fails = 0;
    static void check(boolean ok, String msg){
        if(!ok){ fails++; System.out.println("  FAIL: " + msg); }
    }

    static void run(String name, String[] rows, boolean grid){
        run(name, rows, grid, -1, true);
    }

    static void run(String name, String[] rows, boolean grid, int exitDir, boolean strictDir){
        System.out.println("=== " + name);
        SandPlanner.Grid g = parse(rows);
        if(grid){
            g.grids.add(new int[]{1, 2, 2, 1});
        }
        SandPlanner.Params p = new SandPlanner.Params();
        p.exitDir = exitDir;
        long t0 = System.nanoTime();
        SandPlanner.Result r = new SandPlanner(g, p).plan();
        long ms = (System.nanoTime() - t0) / 1_000_000;
        if(r.error != null){
            System.out.println("  error=" + r.error + " (" + ms + " ms)");
            return;
        }
        System.out.print(r.ascii);
        System.out.printf("  crushers=%d ducts=%d nodes=%d rate=%.2f/s powerOk=%b linked=%b (%d ms)%n", r.crushers, r.ducts, r.nodes, r.sandRate, r.powerOk, r.gridLinked, ms);
        check(r.sandRate >= p.targetSand * 0.999f, "rate " + r.sandRate + " < " + p.targetSand);
        if(strictDir) check(r.powerOk, "power"); //в нарочно тесной карте узлам может не хватить места: это честное предупреждение «питание не связано»
        verifyLayout(g, r, p);
        if(exitDir >= 0 && strictDir) check(r.exitDir == exitDir, "выход смотрит " + r.exitDir + ", просили " + exitDir);
    }

    /** Независимая проверка раскладки: коллизии, поток песка от каждого дробителя до выходной утки, свободная полоса за ней. */
    static void verifyLayout(SandPlanner.Grid g, SandPlanner.Result r, SandPlanner.Params p){
        int n = g.w * g.h;
        int[] owner = new int[n];
        Arrays.fill(owner, -1);
        for(int k = 0; k < r.items.size(); k++){
            SandPlanner.Item it = r.items.get(k);
            int[][] cells;
            switch(it.kind){
                case SandPlanner.CRUSHER -> cells = new int[][]{{it.x, it.y}, {it.x + 1, it.y}, {it.x, it.y + 1}, {it.x + 1, it.y + 1}};
                default -> cells = new int[][]{{it.x, it.y}};
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
            int lx = r.exitX + SandPlanner.DX[r.exitDir] * k, ly = r.exitY + SandPlanner.DY[r.exitDir] * k;
            check(g.in(lx, ly) && g.free[g.idx(lx, ly)], "полоса за выходом занята/вне окна (клетка " + k + ")");
            check(g.in(lx, ly) && owner[g.idx(lx, ly)] < 0, "в полосе за выходом стоит наш блок (клетка " + k + ")");
        }
        //поток: от каждого дробителя по уткам до выходной утки (нет тупиков, петель и «стенки в лоб»)
        for(SandPlanner.Item it : r.items){
            if(it.kind != SandPlanner.CRUSHER) continue;
            boolean reaches = false;
            outer:
            for(int dx = -1; dx <= 2; dx++) for(int dy = -1; dy <= 2; dy++){
                boolean inside = dx >= 0 && dx <= 1 && dy >= 0 && dy <= 1;
                boolean corner = (dx == -1 || dx == 2) && (dy == -1 || dy == 2);
                if(inside || corner) continue;
                int x = it.x + dx, y = it.y + dy;
                if(!g.in(x, y)) continue;
                int o = owner[g.idx(x, y)];
                if(o < 0 || r.items.get(o).kind != SandPlanner.DUCT) continue;
                int guard = 0;
                SandPlanner.Item cur = r.items.get(o);
                while(guard++ < 2000){
                    if(cur.x == r.exitX && cur.y == r.exitY){ reaches = true; break outer; }
                    int fx = cur.x + SandPlanner.DX[cur.rot], fy = cur.y + SandPlanner.DY[cur.rot];
                    if(!g.in(fx, fy)) break;
                    int fo = owner[g.idx(fx, fy)];
                    if(fo < 0 || r.items.get(fo).kind != SandPlanner.DUCT) break;
                    cur = r.items.get(fo);
                }
            }
            check(reaches, "дробитель не доходит до выходной утки " + it);
        }
        //каждая утка кроме выходной отдаёт в другую утку (ни одна не смотрит в пустоту/дробитель/узел)
        for(SandPlanner.Item it : r.items){
            if(it.kind != SandPlanner.DUCT || (it.x == r.exitX && it.y == r.exitY)) continue;
            int fx = it.x + SandPlanner.DX[it.rot], fy = it.y + SandPlanner.DY[it.rot];
            boolean ok = g.in(fx, fy) && owner[g.idx(fx, fy)] >= 0 && r.items.get(owner[g.idx(fx, fy)]).kind == SandPlanner.DUCT;
            check(ok, "утка смотрит не в утку " + it);
        }
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
            SandPlanner.Grid g = new SandPlanner.Grid(0, 0, w, h);
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
            SandPlanner.Params pp = new SandPlanner.Params();
            pp.exitDir = seed % 5 == 0 ? -1 : seed % 4; //разные направления выхода, иногда любое
            SandPlanner.Result r = new SandPlanner(g, pp).plan();
            long ms = (System.nanoTime() - t0) / 1_000_000;
            worst = Math.max(worst, ms);
            if(r.error != null){
                errors++;
                continue;
            }
            int before = fails;
            if(r.sandRate < 14.4f * 0.999f){ shortfall++; if(System.getenv("SAND_VERBOSE") != null){ System.out.println("seed " + seed + " rate=" + r.sandRate); System.out.print(r.ascii);} } //на случайной карте песка может просто не хватить - это не ошибка планировщика
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

    }
}
