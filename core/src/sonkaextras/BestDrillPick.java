package sonkaextras;

import mindustry.content.Blocks;
import mindustry.game.Team;
import mindustry.type.Item;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.blocks.production.BeamDrill;
import mindustry.world.blocks.production.Drill;

import static mindustry.Vars.*;

/**
 * СКМ (pick/пипетка) по руде = автоматически выбрать ЛУЧШИЙ доступный бур для неё (просьба sonka).
 * Встраивается в {@code PlacementFragment.updatePick} тем же приёмом, каким форк уже подменяет
 * СКМ по ядру на "лучшее доступное ядро": если под курсором нет здания/плана, но есть руда -
 * подставляем бур вместо null.
 * <p>
 * "Лучший" = среди буров, которые (а) физически могут копать эту руду ({@code tier >= hardness}),
 * (б) доступны игроку сейчас ({@code isVisible && unlockedNow && !banned}) - берём максимальный
 * {@code tier}, при равенстве - меньший {@code drillTime} (быстрее). Напольная руда - семейство
 * {@link Drill} (включая эрекирские {@code BurstDrill}-наследники), стенная (Эрекир) -
 * {@link BeamDrill}. Контент-дривен: модовые буры участвуют автоматически.
 */
public final class BestDrillPick{

    /** Тумблер (по умолчанию ВКЛ): выключено - СКМ по руде ведёт себя как в ванили (ничего не выбирает). */
    public static final String settingKey = "sonka-bestdrillpick";

    private BestDrillPick(){
    }

    /** Лучший доступный бур для руды под курсором; null = тут не руда или бура нет. */
    public static Block bestFor(Tile tile, Team team){
        if(tile == null || !arc.Core.settings.getBool(settingKey, true)) return null;

        //напольная руда: только на пустом тайле (block=air), иначе пипетка и так взяла бы здание
        if(tile.block() == Blocks.air){
            Item drop = tile.drop();
            if(drop == null) return null;
            return best(drop, false, team);
        }

        //стенная руда Эрекира (wallDrop у рудной стены)
        Item wallDrop = tile.wallDrop();
        if(wallDrop != null) return best(wallDrop, true, team);

        return null;
    }

    static Block best(Item drop, boolean wall, Team team){
        //лучший, на который ХВАТАЕТ ресурсов ядра (просьба sonka) - тот же критерий, каким форк
        //выбирает ядро по СКМ (items().has(requirements, buildCostMultiplier), sandbox = всё можно)
        Block bestAffordable = null;
        int bestAffTier = -1;
        float bestAffTime = Float.MAX_VALUE;
        //запасной: самый ДЕШЁВЫЙ способный (минимальный tier), если не хватает ни на один -
        //призрак всё равно полезнее, чем "кнопка не сработала"
        Block cheapest = null;
        int cheapestTier = Integer.MAX_VALUE;
        float cheapestTime = Float.MAX_VALUE;

        boolean free = state.rules.infiniteResources;

        for(Block b : content.blocks()){
            int tier;
            float time;
            if(!wall && b instanceof Drill d){
                tier = d.tier;
                time = d.drillTime;
            }else if(wall && b instanceof BeamDrill d){
                tier = d.tier;
                time = d.drillTime;
            }else{
                continue;
            }

            if(tier < drop.hardness) continue;
            //ВАЖНО: без env-проверки на Серпуло выигрывал эрекирский eruption-drill (tier выше), а
            //финальный гейт пипетки (unlocked() во фрагменте: supportsEnv+environmentBuildable) его
            //молча отбрасывал - кнопка "не работала". Отбор обязан зеркалить тот гейт целиком.
            if(!b.isVisible() || !b.unlockedNow() || state.rules.isBanned(b)
                || !b.placeablePlayer || !b.environmentBuildable() || !b.supportsEnv(state.rules.env)) continue;

            boolean affordable = free || (team.core() != null && team.items().has(b.requirements, state.rules.buildCostMultiplier));

            //выше tier = лучше; при равном - быстрее (меньший drillTime)
            if(affordable && (tier > bestAffTier || (tier == bestAffTier && time < bestAffTime))){
                bestAffordable = b;
                bestAffTier = tier;
                bestAffTime = time;
            }
            if(tier < cheapestTier || (tier == cheapestTier && time < cheapestTime)){
                cheapest = b;
                cheapestTier = tier;
                cheapestTime = time;
            }
        }
        return bestAffordable != null ? bestAffordable : cheapest;
    }
}
