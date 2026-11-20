package qol.bridgetocore;

import arc.struct.Seq;
import mindustry.content.Blocks;
import mindustry.entities.units.BuildPlan;
import mindustry.gen.Building;
import mindustry.gen.Call;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.blocks.distribution.ItemBridge;
import mindustry.world.blocks.storage.CoreBlock.CoreBuild;

import static mindustry.Vars.*;

/**
 * Достройка последних 1-2 тайлов моста до ядра инвертированными сортировщиками (пункт 5 списка улучшений).
 * Срабатывает на только что поставленный игроком КОНЕЦ цепочки мостов (последний мост из свежих планов):
 * <ul>
 *   <li><b>конец моста -- 1-2 свободных тайла -- ядро:</b> в этих тайлах ставятся инвертированные
 *       сортировщики (один или два подряд; цепочка из двух мгновенных блоков игрой разрешена, из трёх -- нет,
 *       а длиннее и не нужно). Бридж сам «выплёвывает» груз только в соседний тайл, так что без них
 *       ресурс до ядра не дойдёт;</li>
 *   <li><b>последний мост вплотную к ядру, а предпоследний в 1-2 тайлах от него</b> (два моста подряд или
 *       через один тайл - неэффективно): последний мост ЗАМЕНЯЕТСЯ сортировщиком, у предпоследнего снимается
 *       связь (он становится концом), а промежуток между ними (если есть) тоже закрывается сортировщиком.</li>
 * </ul>
 * Железное правило: ставим ТОЛЬКО на пустые тайлы без других планов (кроме самого свежего плана моста,
 * который и заменяем) - любой чужой уже стоящий блок = выходим, ничего не трогая.
 */
final class BridgeEndFinisher{
    private BridgeEndFinisher(){
    }

    /** @return сколько тайлов достроено/заменено (0 - ничего не подошло). */
    static int finish(Seq<BuildPlan> freshPlans){
        if(player == null || player.unit() == null) return 0;

        //конец цепочки - последний свежий план моста без исходящей связи
        BuildPlan end = null;
        for(int i = freshPlans.size - 1; i >= 0; i--){
            BuildPlan p = freshPlans.get(i);
            if(isBridge(p.block)){
                end = p;
                break;
            }
        }
        if(end == null || end.config != null || end.breaking) return 0;
        ItemBridge bridge = (ItemBridge)end.block;

        //где ядро: по каждой из 4 сторон смотрим до 3 тайлов
        int coreDir = -1, gap = -1;
        for(int dir = 0; dir < 4 && coreDir < 0; dir++){
            for(int k = 1; k <= 3; k++){
                Tile t = world.tile(end.x + BridgeToCoreFeature.DX[dir] * k, end.y + BridgeToCoreFeature.DY[dir] * k);
                if(t == null) break;
                if(t.build instanceof CoreBuild cb && cb.team == player.team()){
                    coreDir = dir;
                    gap = k - 1;
                    break;
                }
                if(t.block() != Blocks.air || planAt(t.x, t.y) != null) break; //чужой блок/план на пути - сторона не подходит
            }
        }
        if(coreDir < 0 || gap > 2) return 0;

        int dx = BridgeToCoreFeature.DX[coreDir], dy = BridgeToCoreFeature.DY[coreDir];
        int rot = BridgeToCoreFeature.dirToRotation(dx, dy);
        Block sorter = Blocks.invertedSorter;

        if(gap >= 1){
            //мост -- 1..2 пустых тайла -- ядро: только если у конца есть входящий мост (иначе это не конец цепочки)
            if(!hasIncoming(end, bridge, coreDir)) return 0;
            for(int k = 1; k <= gap; k++){
                int x = end.x + dx * k, y = end.y + dy * k;
                if(!BridgeToCoreFeature.canBuildOn(sorter, player.team(), x, y, rot) || planAt(x, y) != null) return 0;
            }
            for(int k = 1; k <= gap; k++){
                player.unit().addBuild(new BuildPlan(end.x + dx * k, end.y + dy * k, rot, sorter));
            }
            return gap;
        }

        //gap == 0: последний мост вплотную к ядру. Ищем предпоследний строго сзади (в 1-2 тайлах) - иначе всё ок как есть
        for(int m = 1; m <= 2; m++){
            int bx = end.x - dx * m, by = end.y - dy * m;
            Tile bt = world.tile(bx, by);
            if(bt == null) return 0;

            BuildPlan bPlan = planAt(bx, by);
            Building bBuild = bt.build;
            boolean planIsBridge = bPlan != null && isBridge(bPlan.block) && !bPlan.breaking;
            boolean builtIsBridge = bBuild instanceof ItemBridge.ItemBridgeBuild && isBridge(bBuild.block);
            if(!planIsBridge && !builtIsBridge){
                //на пути что-то другое (не пустой тайл и не мост) - не наш случай
                if(bt.block() != Blocks.air || bPlan != null) return 0;
                continue; //пустой тайл: возможно мост дальше (m=2)
            }

            //промежуточный тайл (при m=2) должен быть свободен
            if(m == 2){
                int mx = end.x - dx, my = end.y - dy;
                if(!BridgeToCoreFeature.canBuildOn(sorter, player.team(), mx, my, rot) || planAt(mx, my) != null) return 0;
            }
            //сам тайл конца: плана моста там нет (заменим), а стоять должен пустой тайл
            Tile et = world.tile(end.x, end.y);
            if(et == null || et.block() != Blocks.air || !BridgeToCoreFeature.canBuildOn(sorter, player.team(), end.x, end.y, rot)) return 0;

            //1) снять связь у предпоследнего моста
            if(planIsBridge){
                bPlan.config = null;
            }else if(((ItemBridge.ItemBridgeBuild)bBuild).link != -1){
                Call.tileConfig(player, bBuild, null);
            }
            //2) промежуточный сортировщик, потом сортировщик на месте последнего моста (addBuild заменяет план на тех же x,y)
            if(m == 2) player.unit().addBuild(new BuildPlan(end.x - dx, end.y - dy, rot, sorter));
            player.unit().addBuild(new BuildPlan(end.x, end.y, rot, sorter));
            return m;
        }
        return 0;
    }

    static boolean isBridge(Block b){
        return b == Blocks.itemBridge || b == Blocks.phaseConveyor;
    }

    /** Есть ли с любой из трёх других сторон (не со стороны ядра) мост в зоне дальности - тот самый «входящий». */
    private static boolean hasIncoming(BuildPlan end, ItemBridge bridge, int coreDir){
        for(int dir = 0; dir < 4; dir++){
            if(dir == coreDir) continue;
            for(int k = 1; k <= bridge.range; k++){
                int x = end.x + BridgeToCoreFeature.DX[dir] * k, y = end.y + BridgeToCoreFeature.DY[dir] * k;
                Tile t = world.tile(x, y);
                if(t == null) break;
                BuildPlan p = planAt(x, y);
                if((p != null && isBridge(p.block) && !p.breaking) || (t.build instanceof ItemBridge.ItemBridgeBuild && isBridge(t.block()))) return true;
            }
        }
        return false;
    }

    private static BuildPlan planAt(int x, int y){
        for(BuildPlan p : player.unit().plans){
            if(!p.breaking && p.block != null && p.block.size == 1 && p.x == x && p.y == y) return p;
        }
        return null;
    }
}
