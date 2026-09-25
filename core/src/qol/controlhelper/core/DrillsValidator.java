package qol.controlhelper.core;

import arc.Events;
import arc.struct.ObjectIntMap;
import arc.struct.ObjectSet;
import arc.struct.Queue;
import arc.struct.Seq;
import mindustry.entities.units.BuildPlan;
import mindustry.game.EventType;
import mindustry.content.Blocks;
import mindustry.type.Item;
import mindustry.content.Items;
import mindustry.world.Tile;
import mindustry.world.blocks.production.Drill;
import qol.core.SafeSettings;

import java.util.function.BooleanSupplier;

import static mindustry.Vars.player;
import static mindustry.Vars.state;

/** When several different-output drills get queued at once, keeps only whichever single ore type dominates the batch - avoids scattering miners across every ore in a patch instead of focusing one. */
public class DrillsValidator{
    public float drillsThreashold = 0.6f;
    public Queue<BuildPlan> deltaPlans = new Queue<>();
    /* перф: зеркало deltaPlans для O(1) membership (Queue.contains в цикле давал O(n²) на кадр)
     * + переиспользуемые буферы вместо new Queue/new ObjectSet каждый кадр */
    private final ObjectSet<BuildPlan> deltaSet = new ObjectSet<>();
    private final Queue<BuildPlan> newPlansTmp = new Queue<>();
    private final ObjectSet<BuildPlan> plansSetTmp = new ObjectSet<>();

    final BooleanSupplier masterEnabled;

    public DrillsValidator(BooleanSupplier masterEnabled){
        this.masterEnabled = masterEnabled;
    }

    public void Init(){
        Events.run(EventType.Trigger.update, () -> {
            if(!masterEnabled.getAsBoolean() || !IsEnabled() || !state.isGame() || player == null || player.unit() == null) return;

            Queue<BuildPlan> plans = player.unit().plans;
            Queue<BuildPlan> newPlans = newPlansTmp;
            newPlans.clear();
            for(BuildPlan plan : plans){
                if(deltaSet.contains(plan)) continue;
                newPlans.add(plan);
            }
            if(newPlans.size != 0){
                //редкая ветка (появились новые планы); tmpPlans обязан быть свежим объектом -
                //он становится очередью юнита
                Queue<BuildPlan> tmpPlans = new Queue<>();
                plansSetTmp.clear();
                for(BuildPlan plan : plans) plansSetTmp.add(plan);
                for(BuildPlan plan : deltaPlans){
                    if(plansSetTmp.contains(plan)) tmpPlans.add(plan);
                }
                plansSetTmp.clear(); //не держим ссылки на чужие BuildPlan между кадрами
                for(BuildPlan plan : ValidatePlans(newPlans)) tmpPlans.add(plan);
                player.unit().plans = tmpPlans;
                deltaPlans.clear();
                deltaSet.clear();
                for(BuildPlan plan : tmpPlans){
                    deltaPlans.add(plan);
                    deltaSet.add(plan);
                }
            }
        });
    }

    public Queue<BuildPlan> ValidatePlans(Queue<BuildPlan> plans){
        plans = DropPoorMechanicalDrills(plans);
        Queue<BuildPlan> anotherBlocks = new Queue<>();
        Queue<DVDrill> drills = new Queue<>();
        for(BuildPlan plan : plans){
            if(plan.breaking) continue;
            if(!(plan.block instanceof Drill drill)){
                anotherBlocks.add(plan);
                continue;
            }
            Item returnItem = GetDrillReturnItem(drill, plan.tile());
            int id = drills.indexOf(i -> i.returnItem == returnItem);
            if(id == -1){
                DVDrill dvDrill = new DVDrill(returnItem);
                dvDrill.plans.add(plan);
                drills.add(dvDrill);
            }else{
                drills.get(id).plans.add(plan);
            }
        }

        int drillsCount = 0;
        for(DVDrill dvDrill : drills) drillsCount += dvDrill.plans.size;
        if(drillsCount == 0) return plans;

        for(DVDrill dvDrill : drills) dvDrill.relativeReturnItem = (float)dvDrill.plans.size / drillsCount;
        int id = drills.indexOf(i -> i.relativeReturnItem > drillsThreashold);
        if(id == -1) return plans;

        Queue<BuildPlan> newPlans = new Queue<>();
        DVDrill winner = drills.get(id);
        plans.each(i -> {
            if(winner.plans.contains(i) || anotherBlocks.contains(i)) newPlans.add(i);
        });
        return newPlans;
    }

    /** Пока меди в ядре < 1000, механический бур на единственной клетке руды не окупает свои 12 меди - выкидываем такие планы. */
    Queue<BuildPlan> DropPoorMechanicalDrills(Queue<BuildPlan> plans){
        if(!SafeSettings.getBool("dropOneTileMechDrills", true)) return plans;
        var core = player.core();
        if(core == null || core.items.get(Items.copper) >= 1000) return plans;
        Queue<BuildPlan> kept = new Queue<>();
        Seq<Tile> temp = new Seq<>();
        for(BuildPlan plan : plans){
            if(!plan.breaking && plan.block == Blocks.mechanicalDrill && plan.tile() != null){
                Item item = GetDrillReturnItem((Drill)plan.block, plan.tile());
                int count = 0;
                if(item != null){
                    for(Tile other : plan.tile().getLinkedTilesAs((Drill)plan.block, temp)){
                        if(((Drill)plan.block).canMine(other) && other.drop() == item) count++;
                    }
                }
                if(count == 1) continue;
            }
            kept.add(plan);
        }
        return kept;
    }

    public Item GetDrillReturnItem(Drill drill, Tile tile){
        ObjectIntMap<Item> oreCount = new ObjectIntMap<>();
        Seq<Item> itemArray = new Seq<>();
        Seq<Tile> temp = new Seq<>();
        for(Tile other : tile.getLinkedTilesAs(drill, temp)){
            if(!drill.canMine(other)) continue;
            oreCount.increment(other.drop(), 0, 1);
        }
        for(Item item : oreCount.keys()) itemArray.add(item);
        itemArray.sort((item1, item2) -> {
            int type = Boolean.compare(!item1.lowPriority, !item2.lowPriority);
            if(type != 0) return type;
            int amounts = Integer.compare(oreCount.get(item1, 0), oreCount.get(item2, 0));
            return amounts != 0 ? amounts : Integer.compare(item1.id, item2.id);
        });
        return itemArray.size == 0 ? null : itemArray.peek();
    }

    public boolean IsEnabled(){
        return SafeSettings.getBool("drillsValidator", true);
    }

    public static class DVDrill{
        public Item returnItem;
        public Queue<BuildPlan> plans = new Queue<>();
        public float relativeReturnItem = 0f;

        public DVDrill(Item returnItem){
            this.returnItem = returnItem;
        }
    }
}
