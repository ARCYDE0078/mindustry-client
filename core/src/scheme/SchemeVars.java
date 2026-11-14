package scheme;

import arc.Events;
import mindustry.game.EventType.WorldLoadEndEvent;
import mindustry.type.Item;
import mindustry.type.StatusEffect;
import mindustry.type.UnitType;
import scheme.tools.*;
import scheme.tools.admins.AdminsTools;
import scheme.ui.HudFragment;
import scheme.ui.dialogs.*;

import static arc.Core.*;
import static mindustry.Vars.*;

/**
 * Общие переменные порта Scheme Size (сильно урезанный SchemeVars мода: остались только
 * реально портированные инструменты - см. javadoc {@link SchemeSizeMod} за списком выпилов).
 */
public class SchemeVars{

    public static AdminsTools admins;
    public static RendererTools render;
    public static BuildingTools build;

    public static RuleSetterDialog rulesetter;
    public static AdminsConfigDialog adminscfg;
    public static RendererConfigDialog rendercfg;

    public static TeamSelectDialog team;
    public static TileSelectDialog tile;

    public static ContentSelectDialog<UnitType> unit;
    public static ContentSelectDialog<StatusEffect> effect;
    public static ContentSelectDialog<Item> item;

    public static WaveApproachingDialog approaching;
    public static HudFragment hudfrag;

    public static void load(){
        admins = AdminsConfigDialog.getTools();
        render = new RendererTools();
        build = new BuildingTools();

        rulesetter = new RuleSetterDialog();
        adminscfg = new AdminsConfigDialog();
        rendercfg = new RendererConfigDialog();

        team = new TeamSelectDialog();
        tile = new TileSelectDialog();

        updateContent();

        //сохранение или сервер могут применить патчи контента (state.data) до конца загрузки карты,
        //поэтому списки юнитов/эффектов/предметов пересобираем под пропатченный контент
        Events.on(WorldLoadEndEvent.class, event -> {
            updateContent();
            //авто-выбор способа (3) решается по тому, подключены ли мы к серверу: без переоценки на входе
            //на сервер оставались бы прямые вызовы движка, которые там не работают
            if(settings.getInt("adminsway", 0) == 3) admins = AdminsConfigDialog.detectTools();
        });

        approaching = new WaveApproachingDialog();
        hudfrag = new HudFragment();
    }

    private static void updateContent(){
        //само число - в редактируемом поле рядом с подписью, поэтому в подписи остаётся только единица измерения
        unit = new ContentSelectDialog<>("@scheme.select.unit", content.units(), 0, 100, 1, value -> value == 0 ? "@scheme.select.unit.clear" : unitName("scheme.select.units"));
        effect = new ContentSelectDialog<>("@scheme.select.effect", content.statusEffects(), 0, 500 * 3600, 60, value -> value == 0 ? "@scheme.select.effect.clear" : unitName("scheme.select.seconds"));
        effect.inputScale = 60f; //слайдер держит тики, а вводим секунды
        effect.inputFloor = 0f; //0 снимает эффект, отрицательной длительности не бывает
        item = new ContentSelectDialog<>("@scheme.select.item", content.items(), -1000000, 1000000, 500, value -> value == 0 ? "@scheme.select.item.clear" : unitName("scheme.select.items"));
    }

    /** "{0} секунд" -> "секунд": существующие ключи, переведённые во всех бандлах, без числа. */
    private static String unitName(String key){
        return bundle.format(key, "").trim();
    }
}
