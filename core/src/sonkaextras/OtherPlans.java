package sonkaextras;

import arc.Core;
import arc.math.geom.Rect;
import arc.struct.Seq;
import arc.util.Nullable;
import mindustry.client.ui.Toast;
import mindustry.entities.units.BuildPlan;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.input.Binding;
import mindustry.world.blocks.logic.LogicBlock;
import mindustry.world.blocks.storage.CoreBlock;

import static mindustry.Vars.*;

/**
 * Взаимодействие с ПЛАНАМИ ДРУГИХ ИГРОКОВ (просьба sonka): призраки, которые форк уже рисует через
 * {@code InputHandler.drawOtherBuildPlans}, теперь можно брать в работу:
 * <ul>
 *   <li>СКМ по чужому плану - скопировать блок (пипетка, как по своему плану; хук в PlacementFragment.updatePick);</li>
 *   <li>ЛКМ по чужому плану - построить именно этот блок (хук в DesktopInput, ветка Binding.select);</li>
 *   <li>выделение через B (rebuildSelect) - построить ВСЕ чужие планы внутри рамки, заодно с обычным
 *       восстановлением разрушенных блоков (хук в InputHandler.rebuildArea, он же работает и на телефоне).</li>
 * </ul>
 * ПРОЦЕССОРЫ пропускаем всегда: клиент-отправитель при {@code processorconfigs} не шлёт конфиг
 * логических блоков серверу ({@code configLocal} -> в пакете null), так что у чужого плана кода нет, а
 * пустой процессор на месте чужого "готового" - только мусор в очереди.
 * <p>
 * "Что под курсором" берём у самого рендера ({@code hoveredOtherPlan}): то, что подсвечено стрелкой,
 * рамкой и ником игрока, то и строится/копируется - никакой второй, расходящейся проверки попадания.
 * Всё выключается настройкой {@code otherplansclick} (по умолчанию включено).
 */
public final class OtherPlans{
    public static final String SETTING = "otherplansclick";

    /** Рамка B-выделения в мировых координатах; поле, чтобы не аллоцировать на каждое отпускание клавиши. */
    private static final Rect area = new Rect();
    private static final Rect planBounds = new Rect();
    private static final Seq<BuildPlan> found = new Seq<>(BuildPlan.class);

    private OtherPlans(){
    }

    public static boolean enabled(){
        return Core.settings.getBool(SETTING, true);
    }

    /** Чужой план под курсором, который можно взять (СКМ-копирование блока); null - нечего брать. */
    public static @Nullable BuildPlan hovered(){
        if(!enabled() || player == null || control.input == null) return null;
        BuildPlan plan = control.input.hoveredOtherPlan();
        return plan == null || plan.block == null || plan.breaking ? null : plan;
    }

    /**
     * ЛКМ: построить чужой план под курсором.
     * @return true, если план поставлен в очередь (клик потрачен); false - ничего не сделано и клик
     * должен пойти дальше по обычной цепочке (стрельба, настройка блока под призраком и т. д.)
     */
    public static boolean buildHovered(){
        BuildPlan plan = hovered();
        if(plan == null || player.dead()) return false;

        if(plan.block instanceof LogicBlock){
            toastSkipped(1);
            return false;
        }
        if(!buildable(plan)) return false;

        found.clear();
        found.add(plan);
        return queue(found, Core.input.keyDown(Binding.forcePlaceModifier)) > 0;
    }

    /**
     * B-выделение: поставить в очередь чужие планы, попавшие в рамку (тайловые координаты, те же, что у
     * {@code rebuildArea}: пересечение считается по габаритам блока, а не по центру).
     */
    public static void buildArea(int x1, int y1, int x2, int y2){
        if(!enabled() || player.dead()) return;

        area.set(x1 * tilesize, y1 * tilesize, (x2 - x1) * tilesize, (y2 - y1) * tilesize);

        found.clear();
        int skipped = 0;
        for(Player other : Groups.player){
            //чужие планы рисуются и приходят только для игроков своей команды, остальным previewPlans чистятся
            if(other == null || other == player || other.team() != player.team()) continue;

            for(BuildPlan plan : other.getPreviewPlans()){
                if(plan == null || plan.block == null || plan.breaking) continue;
                if(!plan.block.bounds(plan.x, plan.y, planBounds).overlaps(area)) continue;

                if(plan.block instanceof LogicBlock){
                    if(!plan.isDone()) skipped++;
                    continue;
                }
                if(buildable(plan)) found.add(plan);
            }
        }

        if(found.any()) queue(found, false);
        if(skipped > 0) toastSkipped(skipped);
    }

    /** Блок, который игрок в принципе может поставить, и план ещё не выполнен. */
    private static boolean buildable(BuildPlan plan){
        return !plan.isDone() && ((state.rules.editor || plan.block.isVisible()) || plan.block instanceof CoreBlock);
    }

    /**
     * Ставит КОПИИ планов через обычный {@code flushPlans} - тот же путь, что у вставки схемы: проверка
     * валидности, замена блоков, режим заморозки очереди. Копии нужны затем, что у чужого плана есть
     * прогресс/анимация другого клиента, а наша очередь должна начинать с нуля.
     * @return сколько планов реально попало в очередь (или в заморозку)
     */
    private static int queue(Seq<BuildPlan> plans, boolean force){
        Seq<BuildPlan> copies = new Seq<>(BuildPlan.class);
        for(BuildPlan plan : plans){
            copies.add(new BuildPlan(plan.x, plan.y, plan.rotation, plan.block, plan.config));
        }
        plans.clear();

        boolean freeze = control.input.isFreezeQueueing;
        int before = player.unit().plans.size + mindustry.client.ClientVars.frozenPlans.size;
        control.input.flushPlans(copies, freeze, force, freeze);
        return Math.max(0, player.unit().plans.size + mindustry.client.ClientVars.frozenPlans.size - before);
    }

    private static void toastSkipped(int count){
        new Toast(2.5f).add(Core.bundle.format("client.otherplans.processors", count));
    }
}
