package qol.controlhelper.core;

import arc.Core;
import arc.Events;
import arc.math.geom.Position;
import arc.struct.Queue;
import mindustry.entities.units.BuildPlan;
import mindustry.game.EventType;
import mindustry.input.Binding;
import mindustry.type.Item;
import mindustry.type.ItemStack;
import qol.core.SafeSettings;

import java.util.function.BooleanSupplier;

import static mindustry.Vars.control;
import static mindustry.Vars.player;
import static mindustry.Vars.state;

/** While the "pause building" key is held near the core, auto-pauses/resumes building so the player can hand-mine whatever resource the build queue is still short on. */
public class HandMiner{
    public float delayBeforeStart = 0.5f;
    protected float curDelay;
    /**
     * Пауза стройки по E (Binding.pauseBuilding) - это тот же самый обычный тап-тоггл, которым игрок
     * ставит стройку на паузу вручную (см. DesktopInput.keyTap(pauseBuilding) -> isBuilding ^= true).
     * Раньше эта фича при добытом-по-требованию ресурсе снимала паузу (isBuilding = true) вообще
     * не проверяя, кто её поставил - из-за этого добыча "сама" снимала паузу, которую игрок поставил
     * тем же тапом E намеренно и по совсем другой причине (просто хотел приостановить стройку).
     * pausedByUs помнит, что паузу поставили именно мы ради добычи - только такую паузу и снимаем.
     */
    protected boolean pausedByUs;

    final BooleanSupplier masterEnabled;

    public HandMiner(BooleanSupplier masterEnabled){
        this.masterEnabled = masterEnabled;
    }

    public void Init(){
        Events.run(EventType.Trigger.update, () -> {
            if(!masterEnabled.getAsBoolean() || !IsEnabled() || !state.isGame() || player == null
                || player.unit() == null || player.dead() || player.team().cores().size == 0
                || !player.within((Position)player.unit().closestCore(), player.unit().range() - 1f)) return;

            if(!Core.input.keyDown(Binding.pauseBuilding)){
                curDelay = delayBeforeStart;
            }else if(curDelay > 0f){
                curDelay -= Core.graphics.getDeltaTime();
            }else if(player.unit().mineTile != null){
                Item mineItem = player.unit().mineTile.drop();
                int neededAmount = GetNeededAmount(mineItem);
                if(neededAmount > 0){
                    if(control.input.isBuilding){
                        control.input.isBuilding = false;
                        pausedByUs = true;
                    }
                }else if(pausedByUs){
                    if(!control.input.isBuilding) control.input.isBuilding = true;
                    pausedByUs = false;
                }
            }
        });
    }

    public int GetNeededAmount(Item targetItem){
        Queue<BuildPlan> plans = player.unit().plans;
        int leastAmount = Integer.MAX_VALUE;
        boolean needed = false;
        outer:
        for(BuildPlan plan : plans){
            if(plan.breaking || !plan.within((Position)player, player.unit().range())) continue;
            for(ItemStack itemStack : plan.block.requirements){
                if(itemStack.item != targetItem) continue;
                needed = true;
                if(itemStack.amount < leastAmount) leastAmount = itemStack.amount;
                continue outer;
            }
        }
        if(!needed) return 0;
        int neededAmount = leastAmount - GetCoreAmount(targetItem);
        return Math.max(neededAmount, 0);
    }

    public int GetCoreAmount(Item item){
        return player.team().cores().size == 0 ? 0 : player.team().core().items.get(item);
    }

    public boolean IsEnabled(){
        return SafeSettings.getBool("handMiner", true);
    }
}
