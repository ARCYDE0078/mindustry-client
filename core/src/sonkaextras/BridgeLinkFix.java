package sonkaextras;

import arc.Core;
import arc.math.geom.Point2;
import mindustry.entities.units.BuildPlan;
import mindustry.gen.Building;
import mindustry.world.Tile;
import mindustry.world.blocks.ConstructBlock.ConstructBuild;
import mindustry.world.blocks.distribution.ItemBridge;
import mindustry.world.blocks.distribution.ItemBridge.ItemBridgeBuild;

import static mindustry.Vars.*;

/**
 * Автопочинка связей мостов, когда стройка идёт ПОВЕРХ моста (пункт 2a списка улучшений).
 * <p>
 * В ванили исходящая связь моста живёт только в самом мосте ({@code link}) или в конфиге его плана. Поэтому
 * она теряется в двух случаях:
 * <ol>
 *   <li><b>План поверх плана:</b> {@code BuilderComp.addBuild} выкидывает старый план на этой клетке вместе с его
 *       конфигом, а у нового (одиночный клик мостом) конфига нет - мост в середине цепочки остаётся без связи
 *       дальше. {@link #inheritPlanLink} переносит связь старого плана в новый.</li>
 *   <li><b>Мост поверх построенного моста</b> (замена на другой тип: мост-конвейер -> фазовый конвейер и т.п.):
 *       новый мост рождается с {@code link = -1}. {@link ItemBridgeBuild#overwrote} запоминает связь старого
 *       здания, а {@code playerPlaced} у строителя (только у него - иначе пакеты конфига слали бы все клиенты)
 *       отправляет её обычным {@code configure}, если она допустима для нового блока.</li>
 * </ol>
 * Правим ТОЛЬКО пустую связь (config == null / link == -1) и только пока цель связи ещё мост: явно заданный
 * протяжкой/схемой конфиг никогда не перетирается. Входящие связи (предшественников) не трогаем - они хранят
 * позицию, а не ссылку на здание, и при совпадении типа блока остаются рабочими.
 * <p>
 * Настройка {@link #key} (секция Sonka Extras, по умолчанию ВКЛ).
 */
public final class BridgeLinkFix{
    public static final String key = "sonka-bridgelinkfix";

    private BridgeLinkFix(){
    }

    public static boolean enabled(){
        return Core.settings.getBool(key, true);
    }

    /** Абсолютная позиция цели связи плана-моста или -1, если у плана связи нет. */
    private static int targetOf(BuildPlan plan){
        if(plan.config instanceof Point2 p) return Point2.pack(plan.x + p.x, plan.y + p.y);
        if(plan.config instanceof Integer i && i != -1) return i;
        return -1;
    }

    /**
     * Вызывается из {@code BuilderComp.addBuild}, когда {@code fresh} вытеснил {@code old} с той же клетки:
     * если у нового плана моста нет связи, а у старого была и всё ещё допустима - переносим её.
     */
    public static void inheritPlanLink(BuildPlan old, BuildPlan fresh, Iterable<BuildPlan> plans){
        if(old == null || fresh == null || old.breaking || fresh.breaking || fresh.config != null) return;
        if(!(old.block instanceof ItemBridge) || !(fresh.block instanceof ItemBridge bridge) || !enabled()) return;

        int target = targetOf(old);
        if(target == -1) return;
        int tx = Point2.x(target), ty = Point2.y(target);
        if(tx == fresh.x && ty == fresh.y) return;
        if(!bridge.positionsValid(fresh.x, fresh.y, tx, ty)) return;

        //цель всё ещё мост (в очереди или уже стоит) и не смотрит связью обратно на нас - иначе получится петля
        int freshPos = Point2.pack(fresh.x, fresh.y);
        boolean targetOk = false;
        for(BuildPlan p : plans){
            if(p.x == tx && p.y == ty && !p.breaking && p.block instanceof ItemBridge){
                if(targetOf(p) == freshPos) return;
                targetOk = true;
                break;
            }
        }
        if(!targetOk){
            Building b = world.build(tx, ty);
            if(b instanceof ItemBridgeBuild ib && ib.block instanceof ItemBridge){
                if(ib.link == freshPos) return;
                targetOk = true;
            }
        }
        if(!targetOk) return;

        fresh.config = new Point2(tx - fresh.x, ty - fresh.y);
    }

    /**
     * Вызывается из {@code ItemBridgeBuild.playerPlaced} (только у игрока, построившего этот мост): мост заменил
     * построенный мост со связью {@code prevLink}, а своей связи не получил - восстанавливаем её.
     * <p>
     * Допустимость проверяем мягко (дальность по оси + цель сейчас мост или стройка моста), а не через
     * {@code linkValid}: при апгрейде всей цепочки соседний мост ещё старого типа/в стройке, и строгая проверка
     * отказала бы, хотя после его достройки связь станет рабочей. Конфиг моста кладёт связь без проверки, а
     * недопустимая связь просто не работает (мост сливает груз по соседству), так что вреда нет.
     */
    public static void restoreLink(ItemBridgeBuild self, int prevLink){
        if(self.link != -1 || prevLink == -1 || !enabled()) return;
        Tile old = world.tile(prevLink);
        if(old == null || old.build == null || old == self.tile || old.team() != self.team) return;
        if(!((ItemBridge)self.block).positionsValid(self.tile.x, self.tile.y, old.x, old.y)) return;

        Building target = old.build;
        if(target instanceof ItemBridgeBuild ib){
            if(ib.link == self.tile.pos()) return; //петля: цель уже смотрит на нас
        }else if(!(target instanceof ConstructBuild cb && cb.current instanceof ItemBridge)){
            return;
        }
        self.configure(new Point2(old.x - self.tile.x, old.y - self.tile.y));
    }
}
