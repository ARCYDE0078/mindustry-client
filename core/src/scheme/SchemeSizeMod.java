package scheme;

import arc.Events;
import arc.math.Mathf;
import arc.util.Log;
import mindustry.Vars;
import mindustry.client.ui.ModsSettings;
import mindustry.game.EventType.ClientLoadEvent;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.gen.Icon;
import mindustry.input.DesktopInput;
import mindustry.ui.dialogs.SettingsMenuDialog.SettingsTable;
import mindustry.ui.dialogs.SettingsMenuDialog.SettingsTable.Setting;
import scheme.input.SBinding;
import scheme.tools.MessageQueue;
import scheme.tools.RainbowTeam;
import scheme.tools.SchematicLayers;

import static arc.Core.*;
import static mindustry.Vars.*;
import static scheme.SchemeVars.*;

/**
 * Порт мода "Scheme Size Port" (00SunRay00/RE2b2m22 v3.0.0, внутренняя версия мода 2.3.0; оригинал xzxADIxzx) как нативный
 * пакет клиента - оркестратор по образцу {@link agzam4.AgzamMod}: конструктор вызывается из
 * mindustry.client.Main.init() ДО Events.fire(ClientLoadEvent), вся инициализация - внутри
 * ClientLoadEvent.
 * <p>
 * Портирован только реальный остаток мода (планка дублей максимальная, см. отчёт порта):
 * админ-инструменты (спавн/деспавн юнитов, смена команд + радужная, статус-эффекты, предметы
 * ядра, ядро под ногами, телепорт, удаление игрока; напрямую у хоста или через /js на серверах
 * с JSEval), Rule Setter (все правила карты на лету через рефлексию), строительные инструменты
 * (заливка/квадрат/круг/замена/снос цепочек/подключение к энергосети/сброс предметов/возврат
 * снесённого/мировые заливка и кисть), слои схем ("проклятые схемы"), рендер-остаток
 * (X-Ray/сетка/линейка/тьма/туман), плашка приближающейся волны, скорость панорамирования.
 * <p>
 * НЕ портировано, потому что уже есть в клиенте или вшитых пакетах (или инфраструктура мода):
 * <ul>
 * <li>лимит схем 512 - у форка уже 1024 и загрузчик без ограничений (limitSchematicSize=false);</li>
 * <li>загрузчик .mtls больших схем - мод сам отключал его на Foo's client (isFoos);</li>
 * <li>CLaJ v2 (xpdustry) - у клиента нативный CLaJ v1-протокола, второй стек не тащим;</li>
 * <li>SchemeUpdater/ServerIntegration/DisabledTools - инфраструктура мода и его серверных
 *     интеграций (вшитой копии нечего обновлять, интеграционных серверов у sonka нет);</li>
 * <li>SchemasDialog/теги/ImageParser - нативные SchematicsDialog+Browser богаче, картинка в
 *     схему-дисплей уже есть у agzam4 (DisplayGenerator, вплоть до GIF);</li>
 * <li>PlayerListFragment - список игроков форка уже расширен (spectate/trace/team/mute/assist);</li>
 * <li>CoreInfoFragment/PowerBars - vanilla coreitems + mi2u CoreInfoMindow/PowerGraphTable + eui;</li>
 * <li>GammaAI/NetMinerAI - Navigation (Assist/Mine/Build/RepairPath) + mi2u FullAI;</li>
 * <li>радиусы турелей/реакторов/овердрайва, хп-бары, скрытие юнитов, безрамочные дисплеи,
 *     статусы блоков, лазеры энергии, зум-множители - нативные настройки/бинды клиента;</li>
 * <li>forceTapTile - точно такая же фича уже вшита с mi2u;</li>
 * <li>MapResizeFix - у форка редактор уже до 2000;</li>
 * <li>мобильные панели/ModedGlyphLayout/шилд-бар - мобильная и косметическая обвязка;</li>
 * <li>добавленное в v3.0.0: dev-консоль (ConsoleFragment), TileInfo, Gamma-панель/выбор игрока для ИИ,
 *     Mindurka-бэкенд (Rule Setter и админ-инструменты у нас работают только через Internal/SlashJs),
 *     удаление CLaJ из апстрима (у нас его и не было).</li>
 * </ul>
 * Из v3.0.0 перенесено: ограниченная итеративная заливка, MessageQueue под сессию с лимитами,
 * boolean-результат правил (локальные правила меняются только если инструмент их принял),
 * новый Rule Setter (карточки/вкладки/поиск/пометки изменённого/сброс раздела/ID команды), редактируемый
 * слайдер с полем ввода количества, фильтры списка контента, ID команды в списке команд,
 * пересборка списков при смене патчей контента, Alt-комбо (бинд scheme_alternative, у нас без дефолта).
 * Ключи настроек и биндов - оригинальные, сохранённые настройки пользователей мода
 * подхватываются без миграции. Бандл-ключи UI перенесены под префикс "scheme.".
 */
public class SchemeSizeMod{

    public static final String name = "scheme-size";

    private static boolean enabled;

    public SchemeSizeMod(){
        //self-disable: настоящий Scheme Size установлен как обычный мод - не дублируемся.
        if(Vars.mods.locateMod(name) != null){
            Log.info("[scheme] External Scheme Size mod is also loaded - baked-in copy is standing down.");
            return;
        }

        Events.on(ClientLoadEvent.class, e -> {
            try{
                init();
            }catch(Throwable t){
                Log.err("[scheme] failed to initialize", t);
            }
        });
    }

    /** Готов ли порт к работе (инициализация прошла и внешний мод не установлен). */
    public static boolean enabled(){
        return enabled;
    }

    void init(){
        SBinding.load();
        MessageQueue.load();
        RainbowTeam.load();
        SchematicLayers.load();
        SchemeVars.load();

        hudfrag.build(ui.hudGroup);
        renderer.addEnvRenderer(0, render::draw);

        addSettings();
        Events.run(WorldLoadEvent.class, SchemeSizeMod::applyPanSpeed);
        applyPanSpeed();

        enabled = true;
    }

    /** Секция "Scheme Size" общей вкладки «Моды» - см. {@link ModsSettings}. */
    void addSettings(){
        ModsSettings.section("modsec-scheme", t -> {
            t.pref(new Setting("scheme-dialogs"){
                @Override
                public void add(SettingsTable table){
                    table.table(row -> {
                        row.defaults().growX().height(60f).pad(4f);
                        row.button("@scheme.admins.name", Icon.admin, adminscfg::show);
                        row.button("@scheme.render.name", Icon.image, rendercfg::show);
                        row.button("@scheme.rulesetter.name", Icon.fileText, () -> {
                            if(!admins.unusable()) rulesetter.show();
                        });
                    }).growX().row();
                }
            });

            t.sliderPref("panspeedmul", 4, 4, 20, v -> v / 4f + "x", v -> applyPanSpeed());
            t.checkPref("hardscheme", false);
            t.checkPref("approachenabled", true);
            //ключ оригинальный из мода ("mobile buttons") - на деле это админ-панель сверху-слева
            t.checkPref("mobilebuttons", false);
        });
    }

    /** Множитель скорости панорамирования камеры (у форка panSpeed захардкожен). */
    static void applyPanSpeed(){
        if(control.input instanceof DesktopInput input){
            float value = settings.getInt("panspeedmul", 4);
            input.panSpeed = 4.5f * value / 4f;
            input.panBoostSpeed = 15f * Mathf.sqrt(value / 4f + .1f);
        }
    }
}
