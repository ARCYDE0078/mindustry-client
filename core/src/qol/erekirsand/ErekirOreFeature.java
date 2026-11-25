package qol.erekirsand;

import arc.Core;
import arc.input.KeyBind;
import arc.input.KeyCode;
import mindustry.type.Item;
import mindustry.ui.dialogs.SettingsMenuDialog.SettingsTable;
import mindustry.world.Block;
import mindustry.world.blocks.production.BeamDrill;

import static mindustry.Vars.content;

/**
 * Авторасстановка плазменных буров Эрекира по НАСТЕННОЙ руде (бериллий, графит; с большим буром ещё вольфрам и
 * торий): буры лицом к стене + утки + лучевые узлы, один открытый выход. Как {@link ErekirSandFeature}, но бур тут
 * {@code BeamDrill}: луч идёт до первой твёрдой клетки, и между буром и рудой планировщик оставляет свободный
 * зазор (минимум 1 клетка в каждом столбце), который никогда не занимает: игрок потом протянет там трубы с водородом
 * (он ускоряет плазменный бур в 2.5 раза). Сами трубы фича НЕ ставит.
 * <p>
 * Большой плазменный бур требует водород всегда (не только для разгона), поэтому по умолчанию берётся обычный.
 */
public class ErekirOreFeature extends ErekirDrillFeature{
    static final KeyBind oreKey = KeyBind.add("erekir-ore-drills", KeyCode.home, "erekir-ore"); //слеш занят песком; Home в клиенте свободен

    /** Порядок руд в настройке «Какая руда»: 0 - любая (с наибольшей добычей), дальше по индексу. */
    static final String[] ORES = {"beryllium", "graphite", "tungsten", "thorium"};
    /** Потолок: одна утка (duct) тянет ~15 предметов/с, больше в один выход не соберёшь. */
    static final int MAX_TARGET = 15;

    @Override
    public String id(){
        return "erekir-ore";
    }

    @Override
    KeyBind key(){
        return oreKey;
    }

    @Override
    String tag(){
        return "erekir-ore";
    }

    @Override
    public void buildSettings(SettingsTable table){
        table.checkPref("erekir-ore-large", false);
        table.sliderPref("erekir-ore-item", 0, 0, ORES.length, 1, v -> v == 0 ? Core.bundle.get("qol.erekir-ore.item.auto") : oreName(v));
        table.sliderPref("erekir-ore-target", MAX_TARGET, 1, MAX_TARGET, 1, v -> v + "/s");
    }

    static String oreName(int v){
        Item it = content.item(ORES[v - 1]);
        return it == null ? ORES[v - 1] : it.localizedName;
    }

    @Override
    Block pickDrill(){
        return content.block(Core.settings.getBool("erekir-ore-large", false) ? "large-plasma-bore" : "plasma-bore");
    }

    @Override
    void configure(DrillPlanner.Params params){
        params.beam = true;
        params.targetRate = Core.settings.getInt("erekir-ore-target", MAX_TARGET);
        params.minGap = 1; //ровно столько нужно под трубу; больше допускается (maxGap), но штрафуется
        params.minOreCols = 2;
        if(drillBlock instanceof BeamDrill bd){
            params.size = bd.size;
            params.range = bd.range;
            params.tier = bd.tier;
            params.drillTime = bd.drillTime;
        }
        int forced = Core.settings.getInt("erekir-ore-item", 0);
        if(forced > 0){
            Item it = content.item(ORES[forced - 1]);
            if(it != null) params.oreItem = it.id + 1;
        }
    }

    @Override
    String doneText(DrillPlanner.Result res, DrillPlanner.Params params){
        Item it = res.item > 0 ? content.item(res.item - 1) : null;
        return Core.bundle.format("qol.erekir-ore.done", res.crushers, res.ducts, res.nodes,
            it == null ? "?" : it.localizedName, String.format("%.1f", res.rate), String.format("%.1f", params.targetRate));
    }
}
