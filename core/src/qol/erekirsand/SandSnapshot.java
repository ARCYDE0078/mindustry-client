package qol.erekirsand;

import arc.struct.Queue;
import arc.struct.Seq;
import mindustry.entities.units.BuildPlan;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.world.Block;
import mindustry.world.Build;
import mindustry.world.Tile;
import mindustry.world.blocks.power.PowerGraph;
import mindustry.world.blocks.power.PowerNode;
import mindustry.world.meta.Attribute;

import java.util.IdentityHashMap;

import static mindustry.Vars.world;

/**
 * Мир -> {@link SandPlanner.Grid}. Отдельным классом, чтобы тот же снимок брал и тестовый мод на
 * настоящем движке (devtest/enginetest), где нет ни {@code player}, ни интерфейса. Только главный поток.
 */
public final class SandSnapshot{
    private SandSnapshot(){
    }

    /**
     * @param probe  1x1 блок-мерка «сюда вообще можно строить» (утка): граница карты, глубокая вода, тьма, зона запрета
     * @param plans  уже стоящие в очереди планы юнита (null - нет): их клетки заняты, иначе положим второй блок поверх своего же призрака
     */
    public static SandPlanner.Grid take(Team team, Block probe, Queue<BuildPlan> plans, int zx0, int zy0, int zx1, int zy1, int margin){
        int ox = Math.max(0, zx0 - margin), oy = Math.max(0, zy0 - margin);
        int ex = Math.min(world.width() - 1, zx1 + margin), ey = Math.min(world.height() - 1, zy1 + margin);
        SandPlanner.Grid g = new SandPlanner.Grid(ox, oy, ex - ox + 1, ey - oy + 1);
        g.zx0 = zx0;
        g.zy0 = zy0;
        g.zx1 = zx1;
        g.zy1 = zy1;

        IdentityHashMap<PowerGraph, Integer> ids = new IdentityHashMap<>();
        Seq<double[]> stats = new Seq<>(); //по id-1: {sumX, sumY, count, hasSource}

        for(int y = oy; y <= ey; y++){
            for(int x = ox; x <= ex; x++){
                Tile t = world.tile(x, y);
                if(t == null) continue;
                int i = g.idx(x, y);

                if(t.solid()){
                    //WallCrafter.getEfficiency берёт атрибут у block() твёрдой плитки - так же и здесь
                    g.sand[i] = t.block().attributes.get(Attribute.sand);
                }else if(t.build == null && t.block().isAir()){
                    g.free[i] = Build.validPlace(probe, team, x, y, 0);
                }

                Building b = t.build;
                if(b == null) continue;
                //изолятор останавливает луч даже чужой команды (BeamNode.updateDirections проверяет его ДО команды)
                if(b.isInsulated()){
                    g.pw[i] = SandPlanner.INSULATED_ID;
                }else if(b.block instanceof PowerNode){
                    //луч BeamNode.updateDirections такие здания НЕ берёт и не останавливается на них (иначе он перепутал бы им связи):
                    //проходит насквозь. Включая PowerSource, он тоже PowerNode. pw остаётся 0 - как у стены
                }else if(b.team == team && b.power != null && b.block.connectedPower){
                    PowerGraph pg = b.power.graph;
                    Integer id = ids.get(pg);
                    if(id == null){
                        id = ids.size() + 1;
                        ids.put(pg, id);
                        stats.add(new double[]{0, 0, 0, pg.producers.size > 0 ? 1 : 0});
                    }
                    g.pw[i] = id;
                    double[] st = stats.get(id - 1);
                    st[0] += x;
                    st[1] += y;
                    st[2] += 1;
                }
            }
        }
        for(int k = 0; k < stats.size; k++){
            double[] st = stats.get(k);
            g.grids.add(new int[]{k + 1, (int)(st[0] / st[2]), (int)(st[1] / st[2]), (int)st[3]});
        }

        if(plans != null){
            for(BuildPlan bp : plans){
                if(bp.breaking || bp.block == null) continue;
                int size = bp.block.size, off = (size - 1) / 2;
                for(int dx = 0; dx < size; dx++){
                    for(int dy = 0; dy < size; dy++){
                        int px = bp.x - off + dx, py = bp.y - off + dy;
                        if(g.in(px, py)) g.free[g.idx(px, py)] = false;
                    }
                }
            }
        }
        return g;
    }
}
