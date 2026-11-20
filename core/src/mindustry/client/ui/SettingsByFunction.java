package mindustry.client.ui;

import arc.Core;
import arc.struct.ObjectMap;
import arc.struct.ObjectSet;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Strings;
import mindustry.ui.dialogs.SettingsMenuDialog;
import mindustry.ui.dialogs.SettingsMenuDialog.SettingsTable;
import mindustry.ui.dialogs.SettingsMenuDialog.SettingsTable.Category;
import mindustry.ui.dialogs.SettingsMenuDialog.SettingsTable.Setting;
import mindustry.ui.dialogs.SettingsMenuDialog.Tab;

/**
 * Страница «Настройки по функциям» (пункт 8 списка улучшений): ВСЕ настройки игры, клиента, вшитых модов и MI2U на одной
 * странице, разложенные по назначению (управление, строительство, автоматика, радиусы и оверлеи, интерфейс, чат...), а не по
 * тому, из какого мода они пришли. Раньше «Клиент», «Моды», «MI2U» и ванильные вкладки приходилось обходить по очереди.
 * <p>
 * Это НЕ перенос настроек, а второй взгляд на те же объекты {@link Setting}: ключи в Core.settings не меняются, сохранённые
 * значения и старые вкладки остаются как были (они в меню под заголовком «По источнику»), любая правка здесь сразу видна там
 * и наоборот (страница пересобирается при каждом открытии). Саму страницу рисует родной {@link SettingsTable} - значит
 * работают поиск по названию, сворачиваемые секции и «развернуть/свернуть всё».
 * <p>
 * Раскладка - данные, а не код: {@code assets/settings-groups.txt}. Секции {@code [fn-...]}, в них селекторы по убыванию
 * точности: {@code key:<ключ>} (или {@code key:<префикс>*}), {@code hdr:<ключ заголовка фичи>} (подзаголовок внутри секции
 * мода), {@code sec:<имя секции>} (секция вкладки Клиент / секция мода {@code modsec-*}), {@code tab:<game|graphics|sound|
 * dev|client|mi2u>}. Что не попало ни в один селектор - в группу «Прочее» (ничего не теряется). Новую настройку мода достаточно
 * дописать в файл одной строкой.
 */
public class SettingsByFunction{
    /** Порядок секций на странице; у каждой есть бандл-ключ {@code client.setting.<имя>.category}. */
    public static final String[] groups = {
        "fn-control", "fn-build", "fn-auto", "fn-units", "fn-overlay", "fn-hud", "fn-chat", "fn-warn",
        "fn-gfx", "fn-sound", "fn-campaign", "fn-system", "fn-dev"
    };
    public static final String fallback = "fn-other";

    private final SettingsMenuDialog dialog;
    private SettingsTable page;

    //таблицы выбора группы; заполняются loadRules()
    private final ObjectMap<String, String> keyExact = new ObjectMap<>(), keyPrefix = new ObjectMap<>(), hdr = new ObjectMap<>(),
        sec = new ObjectMap<>(), tab = new ObjectMap<>();
    private final Seq<String> prefixes = new Seq<>();
    private boolean rulesLoaded;

    public SettingsByFunction(SettingsMenuDialog dialog){
        this.dialog = dialog;
    }

    /** Одна настройка с её происхождением: вкладка, секция вкладки/мода, заголовок фичи. */
    private static class Item{
        final String tabId;
        final Category section;
        final Setting header;
        final Setting setting;
        String group;

        Item(String tabId, Category section, Setting header, Setting setting){
            this.tabId = tabId;
            this.section = section;
            this.header = header;
            this.setting = setting;
        }
    }

    /** Собирает страницу заново (настройки модов могли добавиться после старта) и отдаёт её для вставки в prefs. */
    public SettingsTable open(){
        loadRules();
        Seq<Item> items = collect();

        ObjectMap<String, Seq<Item>> byGroup = new ObjectMap<>();
        for(Item it : items){
            it.group = groupOf(it);
            byGroup.get(it.group, Seq::new).add(it);
        }

        page = new SettingsTable();
        page.allowReset = false; //«сбросить всё» на странице со ВСЕМИ настройками игры - слишком опасная кнопка; сброс остаётся в родных вкладках
        Seq<String> order = Seq.with(groups);
        order.add(fallback);
        for(String g : order){
            Seq<Item> list = byGroup.get(g);
            if(list == null || list.isEmpty()) continue;
            page.category(g);
            //сначала настройки без заголовка фичи (клиентские секции, ванильные вкладки), затем фичи модов - иначе после
            //подзаголовка нельзя было бы понять, где фича кончилась; порядок внутри каждой части - как в родных вкладках
            Setting lastHeader = null;
            for(Item it : list){
                if(it.header == null) page.getSettings().add(it.setting);
            }
            for(Item it : list){
                if(it.header == null) continue;
                if(it.header != lastHeader){
                    page.getSettings().add(it.header);
                    lastHeader = it.header;
                }
                page.getSettings().add(it.setting);
            }
        }
        page.rebuild();
        return page;
    }

    /** Все настройки всех вкладок плюс главный список MI2U (в его вкладке лежат только списки mindow-окон). */
    private Seq<Item> collect(){
        Seq<Item> out = new Seq<>();
        ObjectSet<String> seen = new ObjectSet<>(); //главный список MI2U лежит и в его категории диалога, и в settings.list - берём один раз
        for(Tab t : dialog.tabs()){
            String id = switch(t.index){
                case 0 -> "game";
                case 1 -> "graphics";
                case 2 -> "sound";
                case 3 -> "dev";
                case 4 -> "client";
                default -> "cat";
            };
            scan(id, t.table.getSettings(), out, seen);
        }
        if(mi2u.MI2UVars.mi2ui != null) scan("mi2u", mi2u.MI2UVars.mi2ui.settings.list, out, seen);
        return out;
    }

    private static void scan(String tabId, Iterable<? extends Setting> list, Seq<Item> out, ObjectSet<String> seen){
        Category section = null;
        Setting header = null;
        for(Setting s : list){
            if(s instanceof Category c){ //Category.isHeader() тоже true - поэтому проверяется первой
                section = c;
                header = null;
            }else if(s.isHeader()){
                header = s;
            }else if(s.name != null && !s.name.isEmpty() && s.title != null && !s.title.isEmpty()){
                //одинаковые «секция + заголовок + ключ» - одна и та же настройка, пришедшая вторым путём
                String id = (section == null ? "" : section.name) + "|" + (header == null ? "" : header.name) + "|" + s.name;
                if(seen.add(id)) out.add(new Item(tabId, section, header, s));
            }
        }
    }

    private String groupOf(Item it){
        String name = it.setting.name;
        String g = keyExact.get(name);
        if(g == null){
            for(String p : prefixes){ //длинные префиксы первыми - см. loadRules
                if(name.startsWith(p)){
                    g = keyPrefix.get(p);
                    break;
                }
            }
        }
        if(g == null && it.header != null) g = hdr.get(it.header.name);
        if(g == null && it.section != null) g = sec.get(it.section.name);
        if(g == null) g = tab.get(it.tabId);
        return g == null ? fallback : g;
    }

    private void loadRules(){
        if(rulesLoaded) return;
        rulesLoaded = true;
        String current = null;
        try{
            for(String raw : Core.files.internal("settings-groups.txt").readString("UTF-8").split("\n")){
                String line = raw.trim();
                int hash = line.indexOf('#');
                if(hash >= 0) line = line.substring(0, hash).trim();
                if(line.isEmpty()) continue;
                if(line.startsWith("[") && line.endsWith("]")){
                    current = line.substring(1, line.length() - 1);
                    continue;
                }
                if(current == null) continue;
                int colon = line.indexOf(':');
                if(colon < 0) continue;
                String kind = line.substring(0, colon), val = line.substring(colon + 1).trim();
                switch(kind){
                    case "key" -> {
                        if(val.endsWith("*")){
                            String p = val.substring(0, val.length() - 1);
                            keyPrefix.put(p, current);
                            prefixes.add(p);
                        }else keyExact.put(val, current);
                    }
                    case "hdr" -> hdr.put(val, current);
                    case "sec" -> sec.put(val, current);
                    case "tab" -> tab.put(val, current);
                    default -> Log.warn("[settings-groups] unknown selector '@'", line);
                }
            }
        }catch(Throwable t){
            Log.err("[settings-groups] cannot read settings-groups.txt, everything goes to the fallback group", t);
        }
        prefixes.sort((a, b) -> Integer.compare(b.length(), a.length()));
    }

    /**
     * Отладка раскладки: {@code -Dmonolith.dumpsettings=<файл>} пишет в файл «вкладка | секция | заголовок фичи | ключ | группа |
     * название» по каждой настройке - по нему дописывают {@code settings-groups.txt} под новые настройки (всё, что в
     * {@link #fallback}, - кандидаты на раскладку).
     */
    public void dump(String path){
        loadRules();
        StringBuilder sb = new StringBuilder();
        for(Item it : collect()){
            it.group = groupOf(it);
            sb.append(it.tabId).append(" | ")
              .append(it.section == null ? "-" : it.section.name).append(" | ")
              .append(it.header == null ? "-" : it.header.name).append(" | ")
              .append(it.setting.name).append(" | ").append(it.group).append(" | ")
              .append(Strings.stripColors(it.setting.title).replace('\n', ' ')).append('\n');
        }
        arc.Core.files.absolute(path).writeString(sb.toString());
        Log.info("[settings-groups] dumped to @", path);
    }
}
