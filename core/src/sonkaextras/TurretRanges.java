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

    private static void draw(Seq<Building> list, float scaleFactor, float alpha, boolean own){
        //проход 1 - заливки (все под контурами), проход 2 - контуры
        for(int pass = 0; pass < 2; pass++){
            if(pass == 1) Lines.stroke(scaleFactor);
            for(int i = 0; i < list.size; i++){
                Building b = list.get(i);
                if(!(b instanceof BaseTurretBuild t) || !t.isValid()) continue;
                if(!own && t.inFogTo(player.team())) continue;

                float range = t.range();
                if(range < 3f * scaleFactor) continue; //меньше ~3 пикселей - просто шум

                Color c = own ? statusColor(t) : t.team.color;
                int sides = Mathf.clamp(Math.round(range / scaleFactor * 1.2f), 12, 48);
                if(pass == 0){
                    Draw.color(fillColor(tmpFill.set(c)), (own ? 0.16f : 0.12f) * alpha);
                    Fill.poly(t.x, t.y, sides, range);
                }else{
                    Draw.color(c, (own ? 0.6f : 0.45f) * alpha);
                    Lines.poly(t.x, t.y, sides, range);
                }
            }
        }
    }
}
