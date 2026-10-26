package mindustry.client;

import arc.Core;

/**
 * Первый запуск клиента: настройки вшитых модов (окна, оверлеи, автоматика) на СВЕЖЕЙ установке
 * выключены, чтобы новичка не встречали два десятка окон сразу - он включает нужное сам, по одному.
 * <p>
 * Почему это не «поменять true на false в литералах»: у Arc {@code getBool(name, def)} дефолт зашит в
 * каждом месте чтения (их ~170 по всем модам), а значение в settings появляется только после put -
 * т.е. смена литерала молча выключила бы фичи у ВСЕХ, кто их ни разу не трогал, включая нашу
 * собственную установку. Поэтому дефолт «выкл» применяется только на свежей установке и сразу
 * ЗАПИСЫВАЕТСЯ в settings явным {@code false} ({@link #offIfFresh}): на втором запуске установка уже
 * не свежая, а значение лежит в файле и никуда не девается. Существующие установки не меняются вообще.
 * <p>
 * Что именно гасится:
 * <ul>
 * <li>каждая {@code qol.core.Feature} и {@code mindustrytool.features.Feature} - там, где ключ
 * строится (один раз на фичу), а значит новые фичи покрываются сами; исключение у mindustrytool -
 * {@code onDemand}-фичи без окон (браузеры карт/схем, Player Connect): они живут кнопкой в меню;</li>
 * <li>главные переключатели остальных модов - список {@link #offKeys}. Это только «мастера»: то, что
 * само рисует окно/оверлей/кнопку на HUD или что-то делает без спроса. Подпереключатели (формат
 * заголовка окна, списки блоков в фильтрах mi2u, опции редактора консоли и т.п.) НЕ трогаются -
 * они ничего не показывают, пока мастер выключен, а дефолт «выкл» у них сломал бы сами окна
 * (например {@code *.hastitle} - флаг заголовка Mindow).</li>
 * </ul>
 * Нашли ещё один мод, который с порога лезет на экран, - добавьте его мастер-ключ в {@link #offKeys}.
 */
public final class FirstRun{
    /** Метка «этот клиент уже запускался»: ставится при ЛЮБОМ запуске, чтобы свежесть определялась ровно один раз за жизнь установки. */
    private static final String markerKey = "monolith-firstrun-v1";

    /**
     * true только на самом первом запуске клиента с этими настройками. Считается один раз при загрузке
     * класса - {@code Main.init()} трогает его первым делом, до инстанцирования модов, поэтому никто не
     * успевает прочитать настройку раньше, чем она обнулена.
     */
    public static final boolean fresh = detect();

    /**
     * Мастер-переключатели модов, у которых нет общего места, где можно было бы погасить их автоматически
     * (ключи - ровно те, что читает сам мод; формат у каждого свой). Список по модам:
     * mi2u (префикс {@code MI2UI.}), eui, campaign utils, mapping utils, helium, console, tmi, scheme,
     * agzam4 (префикс {@code agzam4mod.settings.}), sonkaextras.
     */
    private static final String[] offKeys = {
        //mi2u: главная панель, курсоры игроков, зона спавна, подсветка распределителей, полоски хп и подмена ввода
        "MI2UI.showMain", "MI2UI.enPlayerCursor", "MI2UI.enSpawnZone", "MI2UI.enDistributionReveal", "MI2UI.enBlockHpBar", "MI2UI.enUnitHpBar", "MI2UI.inputReplace",
        //eui: панели HUD, алерты, прогресс заводов и нижняя панель
        "eui-ShowAlerts", "eui-ShowSchematicsTable", "eui-ShowUnitTable", "eui-showFactoryProgress", "eui-showInteractSettings", "eui-showPowerBar",
        //campaign utils: кнопки кампании и авто-перенаправление пусковых площадок
        "campaignutils-auto-redirect-pads", "campaignutils-show-eye-button", "campaignutils-show-restart-button", "campaignutils-show-stats-button",
        //mapping utils: подмена диалогов правил/размера карты
        "mu_rules_mod", "mu_resize_mod",
        //helium: блюр интерфейса, своё меню построек и своё окно модов
        "he-enableblur", "he-enablebetterplacement", "he-enablebettermodsdialog",
        //консоль: плавающая кнопка на HUD (опции самого редактора кода остаются как были)
        "newconsole.floating-button",
        "tmi_button",
        "approachenabled",
        //agzam4: подсказки блоков, расчёты выделения и планов, цифры хп, авто-сообщения афк
        "agzam4mod.settings.show-blocks-tooltip", "agzam4mod.settings.selection-calculations", "agzam4mod.settings.buildplans-calculations",
        "agzam4mod.settings.show-units-health", "agzam4mod.settings.afk-ping",
        //sonkaextras: предупреждение о цепочках, «последний контролёр», отмотка, автосейв волн, окно «что нового»
        "sonka-chain-warn", "sonka-lastcontroller", "sonka-rewind-enabled", "sonka-wave-autosave", "monolith-whatsnew-auto"
    };

    private FirstRun(){
    }

    private static boolean detect(){
        boolean used = Core.settings.has(markerKey);
        if(!used){
            //установка без метки, но уже запускавшая клиент (метку ввели позже): узнаём по ключам самого клиента.
            //Одного lastBuild мало - он есть и у ванильного игрока, впервые ставящего Monolith, а ему как раз нужен спокойный старт
            for(String key : Core.settings.keys()){
                if(key.startsWith("monolith-") || key.startsWith("qol") || key.startsWith("mi2u") || key.startsWith("MI2U")
                || key.startsWith("settingscategory-") || key.startsWith("eui") || key.contains(".qolwin2.")){
                    used = true;
                    break;
                }
            }
        }
        Core.settings.put(markerKey, true);
        return !used;
    }

    /** Нужно вызвать до инстанцирования модов: сама загрузка класса фиксирует свежесть, а здесь гасятся мастера из {@link #offKeys}. */
    public static void init(){
        if(!fresh) return;
        for(String key : offKeys) offIfFresh(key);
    }

    /** На свежей установке записывает {@code false} под ключ, если пользователь его ещё не трогал. */
    public static void offIfFresh(String key){
        if(fresh && !Core.settings.has(key)) Core.settings.put(key, false);
    }

    /** Как {@link #offIfFresh}, но только для чекбокса, который по умолчанию включён (выключенные трогать незачем). */
    public static void offIfFresh(String key, boolean def){
        if(def) offIfFresh(key);
    }
}
