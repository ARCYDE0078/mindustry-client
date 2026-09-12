package qolc;

import arc.Core;
import arc.Events;
import arc.util.Log;
import mindustry.Vars;
import mindustry.client.ui.ModsSettings;
import mindustry.game.EventType.ClientLoadEvent;
import qol.core.ButtonSetting;
import qol.core.LabelSetting;
import qolc.autograb.AutoGrabFeature;
import qolc.keybinds.ChatKeyBindsFeature;
import qolc.mlog.MlogLibrary;
import qolc.multitask.MultitaskFeature;
import qolc.palcolors.PalColorsFeature;
import qolc.planrange.PlanRangeFeature;
import qolc.wave.WaveSkipCommand;

/**
 * Оркестратор порта стороннего JS-мода "QoL Control" (TyT-xexebe, v7.1), вшитого в клиент нативным
 * кодом - тот же подход, что {@link qol.QolSuiteMod}/{@link eui.EUIMod}/{@link sectorstats.CampaignUtilsMod},
 * см. {@code mindustry.client.Main.init()} (место инстанцирования и почему именно там).
 * <p>
 * ВАЖНО: сюда портирован только реальный остаток мода. Большая часть его 37 модулей НЕ портирована,
 * потому что она уже есть в этом клиенте в другом виде:
 * <ul>
 * <li>уже вшито через qol-suite ({@code core/src/qol/}): camera-lock/build-pause (QuickToggles),
 *     auto-possess (=trace.js), quick-chat, экранные кнопки cbinds, минимап-виджет, распределение
 *     шахтёров (=mining.js, MineDefaults), рекрут юнитов в помощь (=assist.js, poly-split/assist-share),
 *     панель ресурсов чужих команд (=ui/core.js, EnemyMonitor);</li>
 * <li>уже вшито через Extended UI++ ({@code core/src/eui/}): таблица схем (=ui/table.js), автозаполнение
 *     турелей (=autofill.js), инфо-панель блока под курсором (=ui/binfo.js), HP-бары юнитов (=hp.js);</li>
 * <li>нативные фичи самого клиента: !lookat/!here/!cursor/!mapinfo/!mute/!clearghosts (=lookat/here/
 *     cghost/map/mute.js), autotarget (=aimbot.js), MinePath/BuildPath (=ai.js), радиусы турелей и юнитов
 *     (=trange/urange.js), курсоры игроков (drawcursors, =часть track.js), локальная конфигурация
 *     процессорных планов processorconfigs (=logicfix.js), кнопка/бинд пропуска волны для админа;</li>
 * <li>намеренно вырезанный из этого клиента анти-гриф: features/logger.js (=TileRecords/undo),
 *     detector.js (regex-поиск и перезапись вирусных процессоров = вырезанный procfind/fixcode);</li>
 * <li>читы, которые sonka явно не хочет: aimbot.js, unlocker.js (анлок контента + rotateSpeed 9999),
 *     глобальные твики main.js (omniMovement/rotateSpeed всем юнитам).</li>
 * </ul>
 * Ключи настроек портированных фич сохраняют префиксы оригинала ({@code qol-grab-effects},
 * {@code qol-binds}, {@code qol-pal-*}, папка {@code qol/mlog/}), чтобы настройки и файлы игрока,
 * накопленные под JS-модом, продолжили работать.
 */
public class QolControlMod{

    public QolControlMod(){
        //self-disable: если оригинальный JS-мод всё ещё лежит в папке модов, вшитая копия молчит -
        //двойная регистрация задублировала бы команды (!wave и т.д.) и обработчики событий.
        if(Vars.mods.locateMod("qol-control") != null){
            Log.info("[qol-control] External qol-control JS mod is also loaded - baked-in copy is standing down.");
            return;
        }

        Events.on(ClientLoadEvent.class, e -> {
            try{
                MultitaskFeature.init();
                PlanRangeFeature.init();
                AutoGrabFeature.init();
                ChatKeyBindsFeature.init();
                PalColorsFeature.init();
                MlogLibrary.init();
                WaveSkipCommand.init();

                buildSettings();
            }catch(Throwable t){
                Log.err("[qol-control] failed to initialize", t);
            }
        });
    }

    /**
     * Секция "QoL Control" общей вкладки «Моды» (см. {@link ModsSettings}) c заголовками per фича -
     * тот же паттерн (и те же tracked-классы {@link LabelSetting}/{@link ButtonSetting}), что у
     * {@link qol.QolSuiteMod}, см. его javadoc про сломанный поиск настроек при untracked-строках.
     */
    private void buildSettings(){
        ModsSettings.section("modsec-qolc", table -> {
            table.pref(new LabelSetting("qolc-multitask-header", Core.bundle.get("qolc.multitask.title")));
            table.checkPref("qolc-multitask", false);

            table.pref(new LabelSetting("qolc-planrange-header", Core.bundle.get("qolc.planrange.title")));
            table.checkPref("qolc-planrange", false);

            table.pref(new LabelSetting("qolc-grab-header", Core.bundle.get("qolc.grab.title")));
            table.checkPref("qol-grab-effects", true);

            table.pref(new LabelSetting("qolc-keybinds-header", Core.bundle.get("qolc.keybinds.title")));
            table.pref(new ButtonSetting("qolc-keybinds-configure", ChatKeyBindsFeature::showDialog));

            table.pref(new LabelSetting("qolc-palcolors-header", Core.bundle.get("qolc.palcolors.title")));
            table.pref(new ButtonSetting("qolc-palcolors-configure", PalColorsFeature::showDialog));
        });
    }
}
