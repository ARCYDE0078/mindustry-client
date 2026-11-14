package scheme.moded;

import arc.Core;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Lines;
import arc.math.Mathf;
import arc.math.geom.Geometry;
import arc.math.geom.Vec2;
import mindustry.content.Blocks;
import mindustry.core.World;
import mindustry.graphics.Pal;
import mindustry.input.Binding;
import mindustry.input.DesktopInput;
import mindustry.input.InputHandler;
import mindustry.input.Placement;
import mindustry.input.Placement.NormalizeDrawResult;
import mindustry.input.Placement.NormalizeResult;
import mindustry.world.blocks.power.PowerNode;
import scheme.SchemeSizeMod;
import scheme.input.SBinding;
import scheme.tools.BuildingTools.Mode;
import scheme.tools.SchematicLayers;

import static arc.Core.*;
import static mindustry.Vars.*;
import static scheme.SchemeVars.*;

/**
 * Клей между DesktopInput форка и инструментами Scheme Size. Мод подменял весь input handler
 * (ModedDesktopInput extends DesktopInput), но в этом клиенте активный обработчик - mi2u
 * DesktopInputExt, поэтому вместо подмены DesktopInput зовёт статические хуки отсюда
 * (update/drawTop/drawBottom/зум) - они наследуются и mi2u-обработчиком.
 */
public class SchemeInput{

    public static boolean using;
    public static int buildX, buildY, lastX, lastY, lastSize = 8;

    /** Пока тянем выделение инструмента - скролл меняет размер кисти, а не зум камеры. */
    public static boolean consumingZoom(){
        return SchemeSizeMod.enabled() && using && build != null && build.mode != Mode.none;
    }

    /** Рисуем ли сейчас планы инструментов вместо обычного drawBottom. */
    public static boolean drawingTools(){
        return SchemeSizeMod.enabled() && build != null && build.isPlacing();
    }

    /**
     * Зажата ли клавиша-модификатор. Бинд без назначенной клавиши (у нас по умолчанию) - всегда false:
     * keyDown по unset не должен срабатывать ни при каких обстоятельствах, чтобы комбо не включились сами.
     */
    public static boolean altDown(){
        return SchemeSizeMod.enabled() && state.isGame() && !scene.hasKeyboard() && !scene.hasField()
            && !SBinding.alternative.isUnset() && Core.input.keyDown(SBinding.alternative);
    }

    /**
     * Альтернативные действия на ванильных клавишах при зажатом модификаторе. Вызывается в самом начале
     * DesktopInput.update(), ДО ванильного ввода, и при срабатывании update() пропускает остаток кадра:
     * иначе ванильный обработчик увидит тот же keyTap и сделает своё (респавн, переключение схем и т. д.).
     * Возвращает true, если комбо сработало.
     */
    public static boolean altCombo(DesktopInput input){
        if(!altDown()) return false;

        //доступность админ-инструмента (unusable) проверяют сами despawn/teleport: проверка здесь
        //показывала бы всплывашку каждый кадр
        if(Core.input.keyTap(Binding.respawn)) admins.despawn();
        else if(Core.input.keyTap(Binding.mouseMove)) admins.teleport(Core.input.mouseWorld().cpy());
        else if(Core.input.keyTap(Binding.schematicSelect)) SchematicLayers.nextLayer();
        else if(Core.input.keyTap(Binding.deselect)) hudfrag.building.flip();
        else if(Core.input.keyTap(Binding.schematicMenu)) flushLastRemoved(input);
        else return false;
        //Alt+pan (блокировка движения) из мода не портирована: у форка свой free camera/движение

        return true;
    }

    /** Вызывается в конце DesktopInput.update(). */
    public static void updateInput(DesktopInput input){
        if(!SchemeSizeMod.enabled() || !state.isGame()) return;
        if(scene.hasKeyboard() || scene.hasField()) return;

        //с модификатором юнит смотрит на курсор, а R выбрасывает предметы из блока под ним
        boolean alt = altDown();
        if(alt){
            if(!player.dead()) admins.look();
            if(Core.input.keyDown(Binding.rotatePlaced)) build.drop(tileX(input), tileY(input));
        }

        //админ-бинды (все по умолчанию не назначены)
        if(Core.input.keyTap(SBinding.coreBind)) admins.placeCore();
        if(Core.input.keyTap(SBinding.despawnBind)) admins.despawn();
        if(Core.input.keyTap(SBinding.effectBind)) admins.manageEffect();
        if(Core.input.keyTap(SBinding.itemBind)) admins.manageItem();
        if(Core.input.keyTap(SBinding.teamBind)) admins.manageTeam();
        if(Core.input.keyTap(SBinding.unitBind)){
            if(alt) admins.spawnUnits(); //с модификатором - сразу спавн вместо окна выбора юнита
            else admins.manageUnit();
        }
        if(Core.input.keyTap(SBinding.unitSpawnBind)) admins.spawnUnits();
        if(Core.input.keyTap(SBinding.teleportBind)) admins.teleport();
        if(Core.input.keyTap(SBinding.deletePlayerBind)) admins.deletePlayer();
        if(Core.input.keyTap(SBinding.rulesetterBind) && !admins.unusable()) rulesetter.show();
        if(Core.input.keyTap(SBinding.adminsCfgBind)) adminscfg.show();
        if(Core.input.keyTap(SBinding.renderCfgBind)) rendercfg.show();
        if(Core.input.keyTap(SBinding.layerBind)) SchematicLayers.nextLayer();
        if(Core.input.keyTap(SBinding.toggleCoreItemsBind)) settings.put("coreitems", !settings.getBool("coreitems"));
        if(Core.input.keyTap(SBinding.toggleBtBind)) hudfrag.building.flip();
        if(Core.input.keyTap(SBinding.returnBind)) flushLastRemoved(input);

        buildInput(input);
    }

    private static void buildInput(DesktopInput input){
        if(hudfrag == null || !hudfrag.building.fliped) build.setMode(Mode.none);
        if(build.mode == Mode.none) return;

        int cursorX = tileX(input);
        int cursorY = tileY(input);

        boolean has = hasMoved(cursorX, cursorY);
        if(has) build.plan.clear();

        if(using){
            if(build.mode == Mode.drop) build.drop(cursorX, cursorY);
            if(build.mode == Mode.replace) build.replace(cursorX, cursorY);
            if(build.mode == Mode.remove) build.remove(cursorX, cursorY);
            if(build.mode == Mode.connect){
                if(!(input.block instanceof PowerNode)) input.block = Blocks.powerNode;
                build.connect(cursorX, cursorY, (x, y) -> {
                    input.updateLine(x, y);
                    build.plan.addAll(input.linePlans).remove(0);
                });
            }

            if(build.mode == Mode.fill) build.fill(buildX, buildY, cursorX, cursorY, maxSchematicSize);
            if(build.mode == Mode.circle) build.circle(cursorX, cursorY);
            if(build.mode == Mode.square) build.square(cursorX, cursorY, (x1, y1, x2, y2) -> {
                input.updateLine(x1, y1, x2, y2);
                build.plan.addAll(input.linePlans);
            });

            if(build.mode == Mode.brush) admins.brush(cursorX, cursorY, build.size);

            lastX = cursorX;
            lastY = cursorY;
            lastSize = build.size;
            input.linePlans.clear();

            if(Core.input.keyRelease(Binding.select)){
                flushBuildingTools(input);

                if(build.mode == Mode.pick) tile.select(cursorX, cursorY);
                if(build.mode == Mode.edit){
                    NormalizeResult result = Placement.normalizeArea(buildX, buildY, cursorX, cursorY, 0, false, maxSchematicSize);
                    admins.fill(result.x, result.y, result.x2, result.y2);
                }
            }else build.resize(Core.input.axis(Binding.zoom));
        }

        if(Core.input.keyTap(Binding.select) && !scene.hasMouse()){
            buildX = cursorX;
            buildY = cursorY;
            using = true;
        }

        if(Core.input.keyRelease(Binding.select) || Core.input.keyTap(Binding.deselect) || Core.input.keyTap(Binding.breakBlock)){
            using = false;
            build.plan.clear();
        }
    }

    /** Вызывается из DesktopInput.drawTop() перед Draw.reset(). */
    public static void drawTop(DesktopInput input){
        if(!SchemeSizeMod.enabled() || build == null) return;

        int cursorX = tileX(input);
        int cursorY = tileY(input);

        if(using){
            if(build.mode == Mode.edit)
                drawEditSelection(buildX, buildY, cursorX, cursorY, maxSchematicSize);

            if(build.mode == Mode.connect && build.isPlacing())
                drawEditSelection(cursorX - build.size, cursorY - build.size, cursorX + build.size, cursorY + build.size, maxSchematicSize);
        }

        if(build.mode == Mode.brush)
            drawEditSelection(cursorX, cursorY, build.size);
    }

    public static void flushBuildingTools(InputHandler input){
        if(build.mode != Mode.remove) input.flushPlans(build.plan); //проклятые планы перехватит хук в flushPlans
        else if(player.unit() != null) build.plan.each(player.unit()::addBuild);
        build.plan.clear();
    }

    public static void flushLastRemoved(InputHandler input){
        input.flushPlans(build.removed); //проклятые планы перехватит хук в flushPlans
        build.removed.clear();
    }

    public static boolean hasMoved(int x, int y){
        return lastX != x || lastY != y || lastSize != build.size;
    }

    public static int tileX(InputHandler in){
        Vec2 vec = Core.input.mouseWorld(Core.input.mouseX(), Core.input.mouseY());
        if(in.selectedBlock()) vec.sub(in.block.offset, in.block.offset);
        return World.toTile(vec.x);
    }

    public static int tileY(InputHandler in){
        Vec2 vec = Core.input.mouseWorld(Core.input.mouseX(), Core.input.mouseY());
        if(in.selectedBlock()) vec.sub(in.block.offset, in.block.offset);
        return World.toTile(vec.y);
    }

    // some drawing methods

    public static void drawEditSelection(int x1, int y1, int x2, int y2, int maxLength){
        NormalizeDrawResult result = Placement.normalizeDrawArea(Blocks.air, x1, y1, x2, y2, false, maxLength, 1f);

        Lines.stroke(2f);

        Draw.color(Pal.darkerMetal);
        Lines.rect(result.x, result.y - 1, result.x2 - result.x, result.y2 - result.y);
        Draw.color(Pal.darkMetal);
        Lines.rect(result.x, result.y, result.x2 - result.x, result.y2 - result.y);
    }

    public static void drawEditSelection(int x, int y, int radius){
        Vec2[] polygons = Geometry.pixelCircle(radius, (index, cx, cy) -> Mathf.dst(cx, cy, index, index) < index);
        Lines.stroke(2f);

        Draw.color(Pal.darkerMetal);
        Lines.poly(polygons, x * tilesize - 4, y * tilesize - 5, tilesize);
        Draw.color(Pal.darkMetal);
        Lines.poly(polygons, x * tilesize - 4, y * tilesize - 4, tilesize);
    }
}
