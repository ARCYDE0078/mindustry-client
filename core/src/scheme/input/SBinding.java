package scheme.input;

import arc.input.*;

/**
 * Бинды порта Scheme Size. Имена ключей - оригинальные ("scheme_*"), чтобы сохранённые
 * настройки пользователей мода подхватывались без миграции. Все дефолты - unset, как в моде
 * (никаких коллизий с богатой раскладкой клиента). Категория "SchemeSize" - своя секция
 * в меню управления.
 * <p>
 * Отличия от мода: выброшен scheme_ai (GammaAI не портирован - у клиента Navigation/AssistPath,
 * у mi2u FullAI), добавлены бинды на диалоги, до которых мод добирался только через мобильную
 * панель (она не портирована): rulesetter/adminscfg/rendercfg, плюс смена слоя схем и
 * тумблер панели ресурсов ядра. Бинд alternative (v3.0.0) - у нас без дефолта, см. поле.
 */
public class SBinding{
    public static final KeyBind

    teleportBind = KeyBind.add("scheme_teleport", KeyCode.unset, "SchemeSize"),
    despawnBind = KeyBind.add("scheme_despawn", KeyCode.unset, "SchemeSize"),
    teamBind = KeyBind.add("scheme_team", KeyCode.unset, "SchemeSize"),
    coreBind = KeyBind.add("scheme_core", KeyCode.unset, "SchemeSize"),
    unitBind = KeyBind.add("scheme_unit", KeyCode.unset, "SchemeSize"),
    unitSpawnBind = KeyBind.add("scheme_unit_spawn", KeyCode.unset, "SchemeSize"),
    effectBind = KeyBind.add("scheme_effect", KeyCode.unset, "SchemeSize"),
    itemBind = KeyBind.add("scheme_item", KeyCode.unset, "SchemeSize"),
    deletePlayerBind = KeyBind.add("scheme_delete_player", KeyCode.unset, "SchemeSize"),
    rulesetterBind = KeyBind.add("scheme_rulesetter", KeyCode.unset, "SchemeSize"),
    adminsCfgBind = KeyBind.add("scheme_adminscfg", KeyCode.unset, "SchemeSize"),
    renderCfgBind = KeyBind.add("scheme_rendercfg", KeyCode.unset, "SchemeSize"),
    layerBind = KeyBind.add("scheme_layer", KeyCode.unset, "SchemeSize"),
    toggleCoreItemsBind = KeyBind.add("scheme_toggle_core_items", KeyCode.unset, "SchemeSize"),
    toggleBtBind = KeyBind.add("scheme_toggle_bt", KeyCode.unset, "SchemeSize"),
    returnBind = KeyBind.add("scheme_return", KeyCode.unset, "SchemeSize"),
    /**
     * Клавиша-модификатор из v3.0.0: вместе с ванильными клавишами даёт альтернативные действия
     * (Alt+респавн = despawn, Alt+движение мышью = телепорт и т. д., см. SchemeInput.altCombo).
     * В моде дефолт altLeft, но у форка на altLeft уже висит около десятка биндов (команды, выделение
     * через экран, force place, мост...) - молча занять её значило бы сломать их, поэтому здесь unset:
     * пока sonka не назначит клавишу, комбо выключены.
     */
    alternative = KeyBind.add("scheme_alternative", KeyCode.unset, "SchemeSize");

    /**
     * Вшитая копия регистрирует бинды позже, чем Vars.loadSettings() прогоняет
     * KeyBind.all[].load(), поэтому сохранённые значения подтягиваем сами
     * (паттерн mi2u.input.MBinding / agzam4.ModWork.KeyBinds).
     */
    public static void load(){
        teleportBind.load();
        despawnBind.load();
        teamBind.load();
        coreBind.load();
        unitBind.load();
        unitSpawnBind.load();
        effectBind.load();
        itemBind.load();
        deletePlayerBind.load();
        rulesetterBind.load();
        adminsCfgBind.load();
        renderCfgBind.load();
        layerBind.load();
        toggleCoreItemsBind.load();
        toggleBtBind.load();
        returnBind.load();
        alternative.load();
    }
}
