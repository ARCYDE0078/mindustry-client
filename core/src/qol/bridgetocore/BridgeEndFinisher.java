package qol.bridgetocore;

import arc.math.geom.Point2;
import arc.struct.Seq;
import mindustry.content.Blocks;
import mindustry.entities.units.BuildPlan;
import mindustry.gen.Call;
import mindustry.world.Block;
import mindustry.world.Build;
import mindustry.world.Tile;
import mindustry.world.blocks.distribution.ItemBridge;
import mindustry.world.blocks.distribution.ItemBridge.ItemBridgeBuild;
import mindustry.world.blocks.storage.CoreBlock.CoreBuild;

import static mindustry.Vars.*;

/**
 * Достройка конца цепочки мостов до ядра сортировщиками (пункт 5 первого списка улучшений + пункт 1 второго).
 * Срабатывает на только что поставленный игроком КОНЕЦ цепочки мостов и ищет до {@link #MAX_SORTERS} сортировщиков
 * (цепочка из двух мгновенных блоков игрой разрешена, из трёх -- нет), которыми груз из моста дойдёт до ядра.
 * <p>
 * Мост «выплёвывает» груз в любого соседа (кроме стороны, откуда пришёл вход), а сортировщик без фильтра ведёт себя так:
 * <ul>
 *   <li>инвертированный - пропускает груз ПРЯМО (в ту же сторону, куда он ехал);</li>
 *   <li>обычный - отдаёт груз ВБОК (в обе стороны поровну, если принимают обе; в ядро - если ядро с одной стороны).</li>
 * </ul>
 * {@link #solve} перебирает все такие цепочки от моста: прямой подход к ядру (инв., инв.+инв.), подход сбоку
 * (обычный у ядра), угол ядра и «за 1 блок до угла» (инв.+обычный, обычный+инв., обычный+обычный). Берётся
 * самое короткое решение. Обычный сортировщик ставится только если с его второй стороны пусто (иначе груз утёк бы
 * на сторону).
 * <p>
 * Случаи, на которые срабатывает {@link #finish}:
 * <ol>
 *   <li><b>свежий конец цепочки не у ядра вплотную</b> - сортировщики от самого конца;</li>
 *   <li><b>последний мост «поставлен на ядро»</b>: игра отбрасывает такой план (ядро занято), а у предпоследнего моста
 *       остаётся связь на пустоту - он и считается концом. Если вся линия - один клик по ядру, предпоследний мост
 *       ищется среди мостов в дальности по прямой от кликнутой клетки (см. {@link #snapshot});</li>
 *   <li><b>два моста вплотную/через тайл у ядра</b> (неэффективно): последний мост ЗАМЕНЯЕТСЯ сортировщиками, у
 *       предпоследнего снимается связь - он становится концом. Работает и для подхода по углу ядра.</li>
 * </ol>
 * Железное правило: ставим ТОЛЬКО на пустые тайлы без других планов (кроме самого свежего плана моста,
 * который и заменяем) - любой чужой уже стоящий блок = выходим, ничего не трогая.
 */
final class BridgeEndFinisher{
    /** Больше двух мгновенных блоков подряд игра не пропускает. */
    static final int MAX_SORTERS = 2;

    /** Снимок линии до flushPlans: отброшенный «мост на ядре» в очереди уже не виден. */
    private static final Seq<BuildPlan> attempt = new Seq<>();
    private static long attemptFrame = -1;

    private BridgeEndFinisher(){
    }

    /** Вызывается из ввода строго ДО flushPlans(linePlans, ...): запоминает, что игрок пытался поставить. */
    static void snapshot(Seq<BuildPlan> line){
        attempt.clear();
        attemptFrame = arc.Core.graphics.getFrameId();
        if(line == null) return;
        for(BuildPlan p : line){
            if(p != null && p.block != null && !p.breaking) attempt.add(p.copy());
        }
    }

    /** Один шаг решения: блок и клетка. */
    private static final class Step{
        final int x, y;
        final Block block;

        Step(int x, int y, Block block){
            this.x = x;
            this.y = y;
            this.block = block;
        }
    }

    /** Мост: либо план в очереди, либо уже стоящее здание. */
    private static final class Br{
        int x, y;
        BuildPlan plan;
        ItemBridgeBuild build;
        Block block;

        boolean isPlan(){
            return plan != null;
        }

        /** Куда указывает связь (позиция) или -1. */
        int linkPos(){
            if(plan != null) return cfgTarget(plan);
            return build.link;
        }

        boolean linksTo(int tx, int ty){
            return linkPos() == Point2.pack(tx, ty);
        }

        /** Есть ли у моста РАБОЧАЯ исходящая связь - на другой мост (план или здание) в дальности. */
        boolean hasLiveLink(){
            int lp = linkPos();
            if(lp == -1) return false;
            int tx = Point2.x(lp), ty = Point2.y(lp);
            Br t = bridgeAt(tx, ty);
            return t != null && block instanceof ItemBridge ib && ib.positionsValid(x, y, tx, ty);
        }
    }

    /** @return сколько сортировщиков поставлено (0 - ничего не подошло или не потребовалось). */
    static int finish(Seq<BuildPlan> freshPlans){
        if(player == null || player.unit() == null) return 0;

        Br end = findEnd(freshPlans);
        if(end == null) return 0;

        //связь конца: если она ведёт в пустоту (мост «на ядре» отброшен) - она ложная и при любом исходе снимается
        boolean stale = end.isPlan() && end.linkPos() != -1 && !end.hasLiveLink();

        //1) мост вплотную к ядру ИЛИ рядом ещё мост за 1-2 тайла позади: «два моста подряд» меняем на сортировщики
        Br prev = findCloseFeeder(end);
        if(prev != null){
            Step[] steps = solve(prev, end.plan);
            if(steps != null){
                int n = commit(prev, end, steps);
                if(n > 0) return n;
            }
        }

        //2) сортировщики от самого конца
        if(touchesCore(end.x, end.y)){
            if(stale) end.plan.config = null; //вплотную к ядру: мост сам отдаёт груз в ядро, ложную связь просто снимаем
            return 0;
        }
        //одинокий мост без входящих - не конец цепочки (кроме явного «клика по ядру»/ложной связи, там намерение очевидно)
        if(!stale && !viaCoreClick && !hasIncoming(end)) return 0;
        Step[] steps = solve(end, null);
        if(steps == null) return 0;
        if(stale) end.plan.config = null;
        for(Step s : steps) player.unit().addBuild(new BuildPlan(s.x, s.y, 0, s.block));
        return steps.length;
    }

    /** Заполняется в {@link #findEnd}: конец найден по одиночному клику моста по ядру (а не по свежему плану). */
    private static boolean viaCoreClick;

    /**
     * Конец цепочки: последний свежий план моста, если его связь отсутствует или ложная (ведёт не на мост), иначе -
     * мост, найденный по «мосту, поставленному на ядро» из снимка линии.
     */
    private static Br findEnd(Seq<BuildPlan> freshPlans){
        viaCoreClick = false;
        for(int i = freshPlans.size - 1; i >= 0; i--){
            BuildPlan p = freshPlans.get(i);
            if(!isBridge(p.block) || p.breaking) continue;
            Br b = bridgeAt(p.x, p.y);
            if(b == null || b.plan != p) return null;
            //связь на живой мост = цепочка идёт дальше, это не конец
            return b.hasLiveLink() ? null : b;
        }

        //свежих мостов нет: пробуем «клик мостом по ядру» - игра такой план отбросила
        if(attempt.isEmpty() || arc.Core.graphics.getFrameId() != attemptFrame) return null;
        BuildPlan last = attempt.peek();
        if(!isBridge(last.block) || !isCoreTile(last.x, last.y)) return null;
        ItemBridge ib = (ItemBridge)last.block;

        Br best = null;
        int bestDist = Integer.MAX_VALUE;
        for(int dir = 0; dir < 4; dir++){
            for(int k = 1; k <= ib.range; k++){
                int x = last.x + BridgeToCoreFeature.DX[dir] * k, y = last.y + BridgeToCoreFeature.DY[dir] * k;
                Br b = bridgeAt(x, y);
                if(b == null) continue;
                if(b.hasLiveLink() || b.block != last.block){
                    break; //этот мост уже ведёт куда-то (или другой тип) - ему на ядро не нужно
                }
                if(k < bestDist){
                    best = b;
                    bestDist = k;
                }
                break;
            }
        }
        if(best != null) viaCoreClick = true;
        return best;
    }

    /**
     * Мост-«подающий» в 1-2 тайлах от конца по прямой (связан с ним или ещё без связи): его пара с концом - тот самый
     * неэффективный «мост вплотную/через тайл». Тайл между ними (при 2) должен быть пуст.
     */
    private static Br findCloseFeeder(Br end){
        if(!end.isPlan()) return null; //заменить можно только свежий план, не построенный мост
        for(int dir = 0; dir < 4; dir++){
            for(int m = 1; m <= 2; m++){
                int x = end.x + BridgeToCoreFeature.DX[dir] * m, y = end.y + BridgeToCoreFeature.DY[dir] * m;
                Tile t = world.tile(x, y);
                if(t == null) break;
                Br b = bridgeAt(x, y);
                if(b == null){
                    if(t.block() != Blocks.air || planCovering(x, y) != null) break; //на пути что-то другое
                    continue;
                }
                if(b.block != end.block) break;
                if(b.linksTo(end.x, end.y) || !b.hasLiveLink()) return b;
                break;
            }
        }
        return null;
    }

    /**
     * Применяет «замену конца»: у feeder снимается связь, свежий план конца убирается (или перекрывается сортировщиком
     * на той же клетке), ставятся сортировщики. @return число поставленных сортировщиков.
     */
    private static int commit(Br feeder, Br end, Step[] steps){
        if(feeder.isPlan()){
            feeder.plan.config = null;
        }else if(feeder.build.link != -1){
            Call.tileConfig(player, feeder.build, null);
        }
        boolean reused = false;
        for(Step s : steps){
            if(s.x == end.x && s.y == end.y) reused = true;
            player.unit().addBuild(new BuildPlan(s.x, s.y, 0, s.block)); //на тех же x,y старый план заменяется
        }
        //дерево планов ввода пересобирается из очереди каждый кадр (InputHandler.update), отдельно его чистить не нужно
        if(!reused) player.unit().removeBuild(end.x, end.y, false);
        return steps.length;
    }

    // ------------------------------------------------------------------ решатель

    /** Порядок обхода направлений: сначала те, чей соседний тайл ближе к ядру (при равной длине побеждает ближайшая к ядру цепочка). */
    private static int[] dirsByCoreDistance(int ex, int ey){
        int[] order = {0, 1, 2, 3};
        int[] dist = new int[4];
        for(int d = 0; d < 4; d++) dist[d] = coreDistance(ex + BridgeToCoreFeature.DX[d], ey + BridgeToCoreFeature.DY[d]);
        for(int i = 1; i < 4; i++){
            int v = order[i], j = i - 1;
            while(j >= 0 && dist[order[j]] > dist[v]){
                order[j + 1] = order[j];
                j--;
            }
            order[j + 1] = v;
        }
        return order;
    }

    /** Расстояние (манхэттенское) от тайла до ближайшего тайла своего ядра; большое, если ядра нет. */
    private static int coreDistance(int x, int y){
        int best = 1000;
        for(CoreBuild c : player.team().cores()){
            int off = (c.block.size - 1) / 2;
            int x0 = c.tile.x - off, y0 = c.tile.y - off, x1 = x0 + c.block.size - 1, y1 = y0 + c.block.size - 1;
            int dx = Math.max(Math.max(x0 - x, 0), x - x1), dy = Math.max(Math.max(y0 - y, 0), y - y1);
            best = Math.min(best, dx + dy);
        }
        return best;
    }

    /**
     * Самая короткая (1-2 тайла) цепочка сортировщиков от моста {@code from} до ядра или null. {@code replaceable} - план,
     * чей тайл считается свободным (свежий план конца, который заменяем).
     */
    private static Step[] solve(Br from, BuildPlan replaceable){
        boolean[] blocked = incomingDirs(from, replaceable);
        int[] order = dirsByCoreDistance(from.x, from.y);
        Block inv = Blocks.invertedSorter, plain = Blocks.sorter;

        //одним сортировщиком
        for(int d1 : order){
            if(blocked[d1]) continue;
            int x1 = from.x + dx(d1), y1 = from.y + dy(d1);
            if(!free(x1, y1, replaceable)) continue;
            if(isCoreTile(x1 + dx(d1), y1 + dy(d1))) return new Step[]{new Step(x1, y1, inv)};
            if(sideToCore(x1, y1, d1)) return new Step[]{new Step(x1, y1, plain)};
        }

        //двумя
        for(int d1 : order){
            if(blocked[d1]) continue;
            int x1 = from.x + dx(d1), y1 = from.y + dy(d1);
            if(!free(x1, y1, replaceable)) continue;

            //инвертированный вперёд -> второй на продолжении
            int fx = x1 + dx(d1), fy = y1 + dy(d1);
            if(free(fx, fy, replaceable)){
                if(isCoreTile(fx + dx(d1), fy + dy(d1))) return new Step[]{new Step(x1, y1, inv), new Step(fx, fy, inv)};
                if(sideToCore(fx, fy, d1)) return new Step[]{new Step(x1, y1, inv), new Step(fx, fy, plain)};
            }

            //обычный вбок -> второй сбоку; вторая сторона первого должна быть пуста (иначе утечка груза)
            for(int s = -1; s <= 1; s += 2){
                int d2 = Math.floorMod(d1 + s, 4), other = Math.floorMod(d1 - s, 4);
                if(!quietSide(x1 + dx(other), y1 + dy(other))) continue;
                int sx = x1 + dx(d2), sy = y1 + dy(d2);
                if(!free(sx, sy, replaceable)) continue;
                if(isCoreTile(sx + dx(d2), sy + dy(d2))) return new Step[]{new Step(x1, y1, plain), new Step(sx, sy, inv)};
                if(sideToCore(sx, sy, d2)) return new Step[]{new Step(x1, y1, plain), new Step(sx, sy, plain)};
            }
        }
        return null;
    }

    /**
     * Обычный сортировщик в клетке (x,y), в которую груз въезжает в направлении {@code dir}: с одной из боковых сторон
     * ядро, а с другой пусто или тоже ядро (иначе груз частично ушёл бы на сторону).
     */
    private static boolean sideToCore(int x, int y, int dir){
        int a = Math.floorMod(dir + 1, 4), b = Math.floorMod(dir - 1, 4);
        int ax = x + dx(a), ay = y + dy(a), bx = x + dx(b), by = y + dy(b);
        boolean ac = isCoreTile(ax, ay), bc = isCoreTile(bx, by);
        if(ac) return bc || quietSide(bx, by);
        if(bc) return quietSide(ax, ay);
        return false;
    }

    /** Сторона «тихая»: ничего не принимающая - пустой тайл (стена/пол) без здания и без плана на нём. */
    private static boolean quietSide(int x, int y){
        Tile t = world.tile(x, y);
        if(t == null) return true; //край карты
        if(isCoreTile(x, y)) return true;
        return t.build == null && planCovering(x, y) == null;
    }

    /** Свободный для сортировщика тайл: воздух, строить можно, никаких планов (кроме заменяемого). */
    private static boolean free(int x, int y, BuildPlan replaceable){
        Tile t = world.tile(x, y);
        if(t == null || t.block() != Blocks.air) return false;
        if(!Build.validPlace(Blocks.invertedSorter, player.team(), x, y, 0)) return false;
        BuildPlan p = planCovering(x, y);
        return p == null || p == replaceable;
    }

    /** Стороны, в которые мост НЕ выплёвывает груз - там мост-источник (входящий): в эту сторону сортировщик не поставить. */
    private static boolean[] incomingDirs(Br from, BuildPlan ignore){
        boolean[] blocked = new boolean[4];
        int range = from.block instanceof ItemBridge ib ? ib.range : 4;
        for(int dir = 0; dir < 4; dir++){
            for(int k = 1; k <= range; k++){
                int x = from.x + dx(dir) * k, y = from.y + dy(dir) * k;
                Tile t = world.tile(x, y);
                if(t == null) break;
                Br b = bridgeAt(x, y);
                if(b == null) continue;
                if(b.plan != null && b.plan == ignore) continue;
                //входящий: связан с нами или ещё без связи (при постройке свяжется)
                if(b.linksTo(from.x, from.y) || !b.hasLiveLink()){
                    blocked[dir] = true;
                }
                break;
            }
        }
        return blocked;
    }

    /** Есть ли мост в дальности с любой стороны - «входящий» (иначе это не конец цепочки). */
    private static boolean hasIncoming(Br end){
        for(boolean b : incomingDirs(end, null)) if(b) return true;
        return false;
    }

    // ------------------------------------------------------------------ геометрия и мосты

    private static int dx(int d){
        return BridgeToCoreFeature.DX[d];
    }

    private static int dy(int d){
        return BridgeToCoreFeature.DY[d];
    }

    static boolean isBridge(Block b){
        return b == Blocks.itemBridge || b == Blocks.phaseConveyor;
    }

    /** Тайл (x,y) занят ядром нашей команды (построенным). */
    private static boolean isCoreTile(int x, int y){
        Tile t = world.tile(x, y);
        return t != null && t.build instanceof CoreBuild cb && cb.team == player.team();
    }

    /** Касается ли клетка ядра стороной (уже рабочий конец - сортировщики не нужны). */
    private static boolean touchesCore(int x, int y){
        for(int d = 0; d < 4; d++) if(isCoreTile(x + dx(d), y + dy(d))) return true;
        return false;
    }

    /** Абсолютная позиция цели связи плана-моста (Point2 относительный, Integer - абсолютный) или -1. */
    private static int cfgTarget(BuildPlan p){
        if(p.config instanceof Point2 pt) return Point2.pack(p.x + pt.x, p.y + pt.y);
        if(p.config instanceof Integer i && i != -1) return i;
        return -1;
    }

    /** Мост в клетке: план (приоритетнее) или здание нашей команды. */
    private static Br bridgeAt(int x, int y){
        for(BuildPlan p : player.unit().plans){
            if(!p.breaking && p.x == x && p.y == y && isBridge(p.block)){
                Br b = new Br();
                b.x = x;
                b.y = y;
                b.plan = p;
                b.block = p.block;
                return b;
            }
        }
        Tile t = world.tile(x, y);
        if(t != null && t.build instanceof ItemBridgeBuild ib && ib.team == player.team() && isBridge(t.block()) && ib.tileX() == x && ib.tileY() == y){
            Br b = new Br();
            b.x = x;
            b.y = y;
            b.build = ib;
            b.block = t.block();
            return b;
        }
        return null;
    }

    /** Любой (не «ломающий») план, чья площадь накрывает клетку. */
    private static BuildPlan planCovering(int x, int y){
        for(BuildPlan p : player.unit().plans){
            if(p.breaking || p.block == null) continue;
            int off = (p.block.size - 1) / 2;
            int x0 = p.x - off, y0 = p.y - off;
            if(x >= x0 && x < x0 + p.block.size && y >= y0 && y < y0 + p.block.size) return p;
        }
        return null;
    }
}
