package mindustry.editor;

import arc.struct.*;
import mindustry.game.*;
import mindustry.type.*;
import mindustry.core.*;

import static mindustry.Vars.*;

/** Текстовая сводка одной волны для подсказки на графике (с эмодзи контента). */
public class WaveStats{

    public static String describe(Seq<SpawnGroup> groups, int wave){
        int spawnPoints = Math.max(spawner.countSpawns(), 1);
        ObjectIntMap<UnitType> unitCount = new ObjectIntMap<>(), payloadCount = new ObjectIntMap<>();
        ObjectIntMap<Item> itemCount = new ObjectIntMap<>();
        float hp = 0f, payloadHp = 0f, shield = 0f;
        int units = 0, payloadUnits = 0;
        Seq<SpawnGroup> active = new Seq<>();

        for(SpawnGroup g : groups){
            int n = g.getSpawned(wave);
            if(n <= 0) continue;
            active.add(g);
            //группа без выбранной точки спавнится на каждой точке
            int total = n * (g.spawn == -1 ? spawnPoints : 1);

            units += total;
            unitCount.increment(g.type, 0, total);
            hp += g.type.health * total;
            shield += g.getShield(wave) * total;

            if(g.items != null && g.items.amount > 0) itemCount.increment(g.items.item, 0, g.items.amount * total);

            if(g.payloads != null && g.type.payloadCapacity > 0){
                for(UnitType pt : g.payloads){
                    if(pt == null) continue;
                    payloadUnits += total;
                    payloadCount.increment(pt, 0, total);
                    payloadHp += pt.health * total;
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("[accent]Wave ").append(wave + 1).append("[] (spawns: ").append(spawnPoints).append(")\n");
        if(active.isEmpty()){
            return sb.append("[lightgray]-").toString();
        }

        sb.append("Units: ").append(units).append("  ");
        for(var e : unitCount) sb.append(e.key.emoji()).append(e.value).append(' ');
        sb.append('\n');

        if(payloadUnits > 0){
            sb.append("Payload: ").append(payloadUnits).append("  ");
            for(var e : payloadCount) sb.append(e.key.emoji()).append(e.value).append(' ');
            sb.append('\n');
        }
        if(!itemCount.isEmpty()){
            sb.append("Items: ");
            for(var e : itemCount) sb.append(e.key.emoji()).append(e.value).append(' ');
            sb.append('\n');
        }

        sb.append("HP: ").append(UI.formatAmount((long)hp));
        if(payloadUnits > 0) sb.append(" + payload ").append(UI.formatAmount((long)payloadHp));
        sb.append("\nShield: ").append(UI.formatAmount((long)shield));
        sb.append("\n[accent]Total HP+shield: ").append(UI.formatAmount((long)(hp + payloadHp + shield))).append("[]\n");

        //по группам: щит и статусы на юнит, что несёт
        for(SpawnGroup g : active){
            int n = g.getSpawned(wave) * (g.spawn == -1 ? spawnPoints : 1);
            sb.append("\n").append(g.type.emoji()).append(" x").append(n)
              .append(" [lightgray]hp ").append((int)g.type.health).append(" sh ").append((int)g.getShield(wave)).append("[]");
            var effs = g.effects();
            if(effs.any()){
                sb.append(" ");
                for(StatusEffect e : effs) sb.append(e.emoji());
            }
            if(g.items != null && g.items.amount > 0) sb.append(" ").append(g.items.item.emoji()).append(g.items.amount);
            if(g.payloads != null && g.payloads.any() && g.type.payloadCapacity > 0){
                sb.append(" [[");
                for(UnitType pt : g.payloads) if(pt != null) sb.append(pt.emoji());
                sb.append("]");
            }
        }
        return sb.toString();
    }
}
