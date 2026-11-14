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
import mindustry.world.Block;
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
 * ПРОЦЕССОРЫ: у чужого плана кода нет (превью планов {@code byte[]}-конфигов не несёт), поэтому код берётся через
 * {@link ProcessorShare}: из очереди владельца/кэша, а если там нет - запросом по клиентскому каналу у владельца с
 * Monolith. Ответ приходит с задержкой, потому процессор встаёт в очередь, когда код получен; нет ответа за
 * таймаут (у владельца нет Monolith, он вышел или запретил раздачу) - процессор пропускается с тостом.
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
    private static final Seq<BuildPlan> processors = new Seq<>(BuildPlan.class);
    private static final Seq<Player> processorOwners = new Seq<>(Player.class);

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
        if(plan == null || player.dead() || !buildable(plan)) return false;

        boolean force = Core.input.keyDown(Binding.forcePlaceModifier);

        if(plan.block instanceof LogicBlock){
            Player owner = control.input.hoveredOtherPlanOwner();
            if(owner == null) return false;

            Batch batch = new Batch(1);
            takeProcessor(owner, plan, force, batch);
            batch.announce();
            return true; //клик потрачен: либо процессор уже в очереди, либо ждём код (повторный клик не нужен)
        }

        found.clear();
        found.add(plan);
        return queue(found, force) > 0;
    }

    /**
     * B-выделение: поставить в очередь чужие планы, попавшие в рамку (тайловые координаты, те же, что у
     * {@code rebuildArea}: пересечение считается по габаритам блока, а не по центру).
     */
    public static void buildArea(int x1, int y1, int x2, int y2){
        if(!enabled() || player.dead()) return;

        area.set(x1 * tilesize, y1 * tilesize, (x2 - x1) * tilesize, (y2 - y1) * tilesize);

        found.clear();
        processors.clear();
        processorOwners.clear();
        for(Player other : Groups.player){
            //чужие планы рисуются и приходят только для игроков своей команды, остальным previewPlans чистятся
            if(other == null || other == player || other.team() != player.team()) continue;

            for(BuildPlan plan : other.getPreviewPlans()){
                if(plan == null || plan.block == null || plan.breaking) continue;
                if(!plan.block.bounds(plan.x, plan.y, planBounds).overlaps(area)) continue;
                if(!buildable(plan)) continue;

                if(plan.block instanceof LogicBlock){
                    processors.add(plan);
                    processorOwners.add(other);
                }else{
                    found.add(plan);
                }
            }
        }

        if(found.any()) queue(found, false);

        if(processors.any()){
            //takeProcessor может позвать колбэки сразу, а они не должны трогать общие списки - берём копии
            Seq<BuildPlan> plans = processors.copy();
            Seq<Player> owners = processorOwners.copy();
            Batch batch = new Batch(plans.size);
            for(int i = 0; i < plans.size; i++){
                takeProcessor(owners.get(i), plans.get(i), false, batch);
            }
            batch.announce();
        }
    }

    /**
     * Берёт чужой процессор в работу: код либо уже есть ({@link ProcessorShare#known}), либо запрашивается у
     * владельца. Копии полей снимаем сразу: объект плана из превью пересоздаётся каждым снимком, а ответ придёт позже.
     */
    private static void takeProcessor(Player owner, BuildPlan plan, boolean force, Batch batch){
        int x = plan.x, y = plan.y, rotation = plan.rotation;
        Block block = plan.block;

        byte[] known = ProcessorShare.known(owner, plan);
        if(known != null){
            placeProcessor(owner, x, y, rotation, block, known, force);
            batch.finish(true, false);
            return;
        }

        batch.waiting++;
        ProcessorShare.request(owner, plan, config -> {
            placeProcessor(owner, x, y, rotation, block, config, force);
            batch.finish(true, true);
        }, () -> batch.finish(false, true));
    }

    private static void placeProcessor(Player owner, int x, int y, int rotation, Block block, byte[] config, boolean force){
        if(!enabled() || player == null || player.dead()) return;
        //пока ждали код, владелец мог достроить/отменить план или выйти - призрак, которого уже нет, не строим
        if(!stillPlanned(owner, x, y, block)) return;

        Seq<BuildPlan> one = new Seq<>(BuildPlan.class);
        one.add(new BuildPlan(x, y, rotation, block, config));
        queue(one, force);
    }

    private static boolean stillPlanned(Player owner, int x, int y, Block block){
        if(owner == null || !owner.isAdded()) return false;
        for(BuildPlan plan : owner.getPreviewPlans()){
            if(plan != null && !plan.breaking && plan.x == x && plan.y == y && plan.block == block && !plan.isDone()) return true;
        }
        return false;
    }

    /** Счётчик одной пачки процессоров (клик или B-рамка): чтобы тостить один раз за пачку, а не на каждый. */
    private static final class Batch{
        final int total;
        /** Сколько запросов по сети ещё не вернулось. */
        int waiting;
        int finished, failed;

        Batch(int total){
            this.total = total;
        }

        void finish(boolean ok, boolean fromNetwork){
            if(fromNetwork) waiting--;
            finished++;
            if(!ok) failed++;
            if(finished == total && failed > 0) toast("client.otherplans.procfail", failed);
        }

        /** Вызывается после запуска всей пачки: если что-то ушло в сеть и ещё не вернулось - сообщаем игроку. */
        void announce(){
            if(waiting > 0) toast("client.otherplans.procrequest", waiting);
        }
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

    private static void toast(String key, int count){
        new Toast(2.5f).add(Core.bundle.format(key, count));
    }
}
