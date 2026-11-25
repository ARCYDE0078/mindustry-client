package qol.erekirsand;

import arc.Core;
import arc.input.KeyBind;
import arc.input.KeyCode;
import mindustry.world.Block;
import mindustry.world.blocks.production.WallCrafter;

import static mindustry.Vars.content;

/**
 * Авторасстановка песчаных буров Эрекира: скальные дробители ({@code cliff-crusher}) лицом к песчаной стене + утки +
 * лучевые узлы, один открытый выход песка. Печей и прочего фича НЕ ставит: куда вести песок дальше, решает игрок.
 * Вся механика (рамка, поток, очередь) - в {@link ErekirDrillFeature}, расчёт - в {@link DrillPlanner}.
 */
public class ErekirSandFeature extends ErekirDrillFeature{
    static final KeyBind sandKey = KeyBind.add("erekir-sand-drills", KeyCode.slash, "erekir-sand"); //K в клиенте занят ванильным auto_mine, а свободных букв не осталось - «/»

    /**
     * Сколько песка набирать: на 3 кремниевые дуговые печи, которые игрок поставит сам. Печь - GenericCrafter
     * siliconArcFurnace: consumeItems(sand 4, graphite 1), craftTime 50 тиков => 4.8/с; одна утка тянет 15/с, как раз 3 печи.
     */
    static final float TARGET_SAND = 3 * 4f * 60f / 50f;

    @Override
    public String id(){
        return "erekir-sand";
    }

    @Override
    KeyBind key(){
        return sandKey;
    }

    @Override
    String tag(){
        return "erekir-sand";
    }

    @Override
    Block pickDrill(){
        return content.block("cliff-crusher");
    }

    @Override
    void configure(DrillPlanner.Params params){
        params.targetRate = TARGET_SAND;
        if(drillBlock instanceof WallCrafter wc) params.drillTime = wc.drillTime;
    }

    @Override
    String doneText(DrillPlanner.Result res, DrillPlanner.Params params){
        return Core.bundle.format("qol.erekir-sand.done", res.crushers, res.ducts, res.nodes,
            String.format("%.1f", res.rate), String.format("%.1f", params.targetRate));
    }
}
