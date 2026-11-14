package qolc.planrange;

import arc.Core;
import arc.Events;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Lines;
import arc.math.geom.Point2;
import arc.struct.IntSet;
import mindustry.Vars;
import mindustry.entities.units.BuildPlan;
import mindustry.game.EventType.Trigger;
import mindustry.gen.Building;
import mindustry.gen.Unit;
import mindustry.graphics.Layer;
import mindustry.graphics.Pal;
import mindustry.world.Block;
import mindustry.world.blocks.defense.ForceProjector;
import mindustry.world.blocks.defense.MendProjector;
import mindustry.world.blocks.defense.OverdriveProjector;
import mindustry.world.blocks.defense.turrets.BaseTurret;
import mindustry.world.blocks.distribution.MassDriver;

/**
 * Радиусы у ЗАПЛАНИРОВАННЫХ блоков + радиусы всех построенных оверлайвов/масс-драйверов, пока в руке
 * такой же блок. Порт features/plan_range.js.
 * <p>
 * Остаток после нативных возможностей: ваниль рисует радиус только у блока ПОД КУРСОРОМ при
 * размещении ({@code drawPlace}), форковый {@code showdomes} - только у плана-призрака самого
 * оверлайва; радиусы турелей клиент рисует только у ПОСТРОЕННЫХ. Здесь же видно покрытие всей
 * спланированной, но ещё не построенной обороны (очередь юнита + выделение + линия).
 * <p>
 * v7.1 -&gt; v7.12: радиусы обесточенных/сломанных/выключенных построенных блоков рисуются серым
 * ({@link #isBlockIdle}).
 * <p>
 * Категорийный фильтр оригинала (logic/power/distribution/liquid) заменён явными instanceof - у
 * {@link Block} нет общего поля range, а перечисленные типы и есть всё, что оригинал реально рисовал.
 */
public final class PlanRangeFeature{
    private static final Color turretColor = Color.valueOf("f25555");
    private static final Color overdriveColor = Color.valueOf("feb380");
    /** v7.x: радиус простаивающего (обесточенного/сломанного/выключенного) блока рисуется серым. */
    private static final Color idleColor = Color.valueOf("909090");
    private static final IntSet seen = new IntSet();

    private PlanRangeFeature(){
    }

    public static void init(){
        Events.run(Trigger.draw, PlanRangeFeature::draw);
    }

    private static boolean enabled(){
        return Core.settings.getBool("qolc-planrange", false);
    }

    private static void draw(){
        if(!enabled() || !Vars.state.isGame() || Vars.player == null) return;

        Draw.z(Layer.overlayUI);
        Lines.stroke(1f);

        seen.clear();
        for(BuildPlan plan : Vars.control.input.selectPlans) drawPlan(plan);
        for(BuildPlan plan : Vars.control.input.linePlans) drawPlan(plan);
        Unit unit = Vars.player.unit();
        if(unit != null){
            for(BuildPlan plan : unit.plans()) drawPlan(plan);
        }

        drawHeldBlockPeers();

        Draw.reset();
    }

    /** Радиусы всех ПОСТРОЕННЫХ блоков того же типа, пока в руке овердрайв или масс-драйвер - чтобы видеть дыры в покрытии, доставляя следующий. */
    private static void drawHeldBlockPeers(){
        Block held = Vars.control.input.block;
        if(!(held instanceof OverdriveProjector) && !(held instanceof MassDriver)) return;

        for(Building b : Vars.player.team().data().getBuildings(held)){
            float range = b instanceof OverdriveProjector.OverdriveBuild ob ? ob.range() : ((MassDriver)held).range;
            //серый для тех, кто на деле не работает - сразу видно, какие из них реально дают покрытие
            Draw.color(isBlockIdle(b) ? idleColor : held instanceof OverdriveProjector ? overdriveColor : Pal.accent, 0.5f);
            Lines.circle(b.x, b.y, range);
        }
    }

    /**
     * Блок реально ничего не делает? Те же признаки, что у оригинала (v7.9+): выключен логикой,
     * купол в состоянии broken, у овердрайва - сглаженная эффективность почти нулевая, иначе
     * обычная эффективность. У овердрайва берём именно {@code smoothEfficiency}, а не мгновенную:
     * она плавно угасает/нарастает, поэтому кольцо не мигает на каждой просадке питания.
     * Rhino-оговорки оригинала про "метод вместо поля" в Java не нужны - типы статические.
     */
    private static boolean isBlockIdle(Building b){
        if(!b.enabled) return true;
        if(b instanceof ForceProjector.ForceBuild fb && fb.broken) return true;
        if(b instanceof OverdriveProjector.OverdriveBuild ob) return ob.smoothEfficiency <= 0.02f;
        return b.efficiency <= 0.001f;
    }

    private static void drawPlan(BuildPlan plan){
        if(plan.breaking || plan.block == null) return;
        if(!seen.add(Point2.pack(plan.x, plan.y))) return;

        float x = plan.x * Vars.tilesize + plan.block.offset;
        float y = plan.y * Vars.tilesize + plan.block.offset;

        if(plan.block instanceof ForceProjector fp){
            Draw.color(Pal.shield, 0.7f);
            Lines.poly(x, y, fp.sides, fp.radius, fp.shieldRotation);
        }else if(plan.block instanceof MendProjector mp){
            Draw.color(Pal.heal, 0.7f);
            Lines.circle(x, y, mp.range);
        }else if(plan.block instanceof OverdriveProjector op){
            Draw.color(overdriveColor, 0.7f);
            Lines.circle(x, y, op.range);
        }else if(plan.block instanceof BaseTurret bt){
            Draw.color(turretColor, 0.7f);
            Lines.circle(x, y, bt.range);
        }
    }
}
