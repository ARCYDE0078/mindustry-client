package dustdustry.patcheditor;

import dustdustry.patcheditor.export.*;
import dustdustry.patcheditor.ui.*;
import arc.*;
import arc.util.*;
import mindustry.*;
import mindustry.game.EventType.*;
import mindustry.mod.*;
import rhino.*;

/**
 * Порт мода "PatchEditor" (minRi2/Dustdustry, v1.14.0 + фиксы до da610e2c, 2026-09-04) как нативный пакет клиента: in-game GUI
 * для НАТИВНОЙ системы контент-патчей движка v8 (mindustry.mod.DataPatcher + DataManager,
 * PatchAsset/ContentAsset в правилах карты/сейва) - никакой внешней мод-зависимости у него нет,
 * build.gradle оригинала зависит только от ваниль-jar v159.7, т.е. ровно от версии этого движка.
 * Визуальное создание/редактирование патчей: дерево всех полей контента с заметками (свои +
 * remote-заметки с github Dustdustry/PatchNotes), селекторы текстур/звуков/эффектов/цветов/
 * классов, операции над коллекциями, undo/redo, экспорт в HJSON/JSON, "магический экспорт"
 * ваниль-контента в патч. Встраивается в ваниль-UI (EditorMount): кнопка в меню паузы +
 * кнопки Edit в диалоге ассетов карты (MapAssetsDialog) - все reflection-якоря
 * (infoDialog/patches/list/currentType/rebuild, DataPatcher.root/parser/nameToType,
 * ContentParser.internalRead/parseProgressOp, MapContentView.contentIcons) сверены с этим
 * движком. Настройки - собственный диалог мода (EditorSettings, шестерёнка в редакторе
 * патчей, ключи patch-editor.* оригинальные) - контекстные на месте, как у mi2u, поэтому
 * секции в общей вкладке «Моды» нет; строка в справочнике фич ведёт в этот диалог.
 *
 * Адаптация по образцу HeliumMod/MI2UMod: без extends Mod, конструктор зовётся из
 * mindustry.client.Main.init() до ClientLoadEvent; self-disable guard по id мода
 * "patch-editor". Из EVars выброшен неиспользуемый thisMod (Vars.mods.getMod(Main.class)
 * у вшитой копии вернул бы null). Спрайтов у мода нет (иконки ваниль-атласа), бандл en+ru
 * (ru-перевод свой; оригинал шёл с en+zh_CN, китайский не вшивали).
 *
 * @author minri2
 * Create by 2024/2/14
 */
public class Main{

    public Main(){
        //self-disable: рядом установлен настоящий PatchEditor - двойной EditorMount продублировал
        //бы кнопки в меню паузы/диалоге ассетов; вшитая копия уступает (паттерн всех вшитых модов)
        if(Vars.mods.locateMod("patch-editor") != null){
            Log.info("[patcheditor] External PatchEditor mod is also loaded - baked-in copy is standing down.");
            return;
        }

        Events.on(ClientLoadEvent.class, e -> {
            try{
                VersionAdapter.init();

                EVars.init();
                EUI.init();
                EditorMount.mount();

                if(OS.hasProp("exposeExporterJS")){
                    Scripts scripts = Vars.mods.getScripts();
                    scripts.scope.put("PatchExporter", scripts.scope, new NativeJavaClass(scripts.scope, PatchExporter.class));
                }
            }catch(Throwable t){
                Log.err("[patcheditor] failed to initialize", t);
            }
        });
    }

    /** guard пройден и ClientLoadEvent отработал - для строки в справочнике фич */
    public static boolean enabled(){
        return EUI.settings != null;
    }
}
