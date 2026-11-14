package mi2u;

import arc.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.util.*;
import mi2u.graphics.*;
import mi2u.input.*;
import mindustry.*;
import mindustry.game.EventType.*;

import static mi2u.MI2UVars.*;
import static mindustry.Vars.*;

/**
 * Порт мода "MI2-Utilities Java" (BlackDeluxeCat, v1.16.2) как нативный пакет клиента.
 * Оригинальный главный класс {@code mi2u.MI2Utilities extends Mod} превращён в оркестратор
 * по образцу QolSuiteMod/EUIMod: конструктор вызывается из mindustry.client.Main.init()
 * ДО Events.fire(ClientLoadEvent), поэтому здесь можно (и нужно) только вешать слушатели.
 * <p>
 * Ключи настроек и бандла оставлены оригинальными ("MI2UI.*", "CoreInfo.*", "MI2U.ai.modes",
 * keybind-ы "ctrl_build_bypass" и т.д.) - сохранённые настройки sonka из времён загрузки
 * MI2 как обычного мода подхватываются без миграции.
 * <p>
 * Отличия от оригинала:
 * <ul>
 * <li>self-disable guard: если настоящий мод всё ещё лежит в папке модов, вшитая копия молчит
 *     (двойная регистрация задублировала бы окна, слушатели и подмену input-хендлера);</li>
 * <li>спрайты ui-*.png зарегистрированы в атлас вручную из core/assets/mi2u/ под оригинальными
 *     именами "mi2-utilities-java-ui-*" - весь UI-код мода продолжает находить их как раньше;</li>
 * <li>шейдеры зон грузятся из core/assets/shaders/ (internal), а не из файлового дерева мода,
 *     и не на FileTreeInitEvent (он для вшитого кода уже отгремел), а в начале ClientLoadEvent;</li>
 * <li>ModUpdateChecker выброшен - вшитой копии нечего обновлять с GitHub мода.</li>
 * </ul>
 * <p>
 * Версия 1.16.2. Апстрим 1.16.x требует движок 160 и использует предвычисленные
 * {@code MapMarkers.worldMarkers/mapMarkers} вместо перебора с проверкой {@code marker.world/minimap} -
 * клиент собран на 160.7, где эти поля есть, так что цикл в RendererExt/MinimapMindow как в апстриме.
 * Остальные фиксы (снятие logicMemory из списка мониторов, пересборка кэша иконок PowerGraphTable
 * на WorldLoadEvent вместо ContentInitEvent) портированы как есть. Единственное осознанное отличие:
 * флаг анимированных зон берётся из {@code renderer.animateShields} (апстрим читает animateWater,
 * но в этом форке именно animateShields включает буфер шейдера щитов, куда рисуются зоны).
 * </p>
 */
public class MI2UMod{
    /** Оригинальные имена регионов атласа мода: префикс = имя мода из mod.hjson. */
    public static final String atlasPrefix = "mi2-utilities-java-";

    public MI2UMod(){
        //self-disable: настоящий MI2 установлен - не дублируемся.
        if(Vars.mods.locateMod("mi2-utilities-java") != null){
            Log.info("[mi2u] External MI2-Utilities mod is also loaded - baked-in copy is standing down.");
            return;
        }

        Events.on(ClientLoadEvent.class, e -> {
            try{
                //шейдеры до RendererExt.init: на ClientLoadEvent мы в GL-потоке, контекст готов.
                MI2UShaders.load();
                loadSprites();

                MI2UVars.init();
                InputUtils.init();
                //форс-инициализация MBinding: KeyBind.add(...) в его статике должен отработать
                //до первого открытия диалога управления (иначе категории "MI2U" там не будет),
                //а сохранённые значения биндов - подтянуться из settings (Vars.loadSettings()
                //прогнал KeyBind.all[].load() ещё до нашей регистрации)
                MBinding.load();

                //anyone need max size < vanilla size, open an issue on Github (комментарий автора мода)
                maxSchematicSize = Math.max(maxSchematicSize, mi2ui.settings.getInt("maxSchematicSize", 64));
                mi2ui.settings.putInt("maxSchematicSize", maxSchematicSize);

                //DesktopInputExt/MobileInputExt вшиты В ЭТОТ форк и наследуют клиентский
                //DesktopInput/MobileInput со всеми его фичами (freecam, autotarget, навигация...),
                //так что подмена не теряет клиентское поведение - только добавляет каналы
                //InputOverwrite для FullAI/миникарты/панорамирования.
                if(mi2ui.settings.getBool("inputReplace")){
                    control.setInput(mobile ? MobileInputExt.getInstance() : DesktopInputExt.getInstance());
                }

                Time.runTask(40f, () -> {
                    //sonka: главная панель (Sync/DG/RB/Time Scale) теперь отключаемая, на свежей установке её нет (FirstRun); у остальных по-прежнему true
                    if(mi2ui.settings.getBool("showMain", true)) mi2ui.addTo(Core.scene.root);
                    if(mi2ui.settings.getBool("showEmojis")) emojis.addTo(emojis.hasParent() ? emojis.parent : Core.scene.root);
                    if(mi2ui.settings.getBool("showCoreInfo")) coreInfo.addTo(coreInfo.hasParent() ? coreInfo.parent : Core.scene.root);
                    if(mi2ui.settings.getBool("showMinimap")) mindowmap.addTo(mindowmap.hasParent() ? mindowmap.parent : Core.scene.root);
                    if(mi2ui.settings.getBool("showLogicHelper")) logicHelper.addTo(logicHelper.hasParent() ? logicHelper.parent : ui.logic);

                    RendererExt.init();
                    ModifyFuncs.modifyVanilla();
                });
            }catch(Throwable t){
                Log.err("[mi2u] failed to initialize", t);
            }
        });
    }

    /**
     * Регистрирует спрайты мода в атласе под их оригинальными именами.
     * У обычного мода это делает спрайт-пакер Mods; вшитая копия несёт png в core/assets/mi2u/
     * и добавляет их как отдельные текстуры (иконки мелкие, оверхед несущественный).
     */
    private static void loadSprites(){
        for(String name : new String[]{"ui-ammo", "ui-centermove", "ui-speed", "ui-customai", "ui-shoot", "ui-ai"}){
            String region = atlasPrefix + name;
            if(Core.atlas.has(region)) continue;
            Texture tex = new Texture(Core.files.internal("mi2u/" + name + ".png"));
            tex.setFilter(Texture.TextureFilter.linear);
            Core.atlas.addRegion(region, new TextureRegion(tex));
        }
    }
}
