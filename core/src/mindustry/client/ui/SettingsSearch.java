package mindustry.client.ui;

import arc.Core;
import arc.scene.ui.TextField;
import arc.scene.ui.layout.Scl;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Nullable;
import arc.util.Strings;
import mindustry.Vars;
import mindustry.client.utils.ClientUtils;
import mindustry.gen.Icon;
import mindustry.graphics.Pal;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.SettingsMenuDialog;
import mindustry.ui.dialogs.SettingsMenuDialog.SettingsTable;
import mindustry.ui.dialogs.SettingsMenuDialog.SettingsTable.Category;
import mindustry.ui.dialogs.SettingsMenuDialog.SettingsTable.Setting;
import mindustry.ui.dialogs.SettingsMenuDialog.Tab;

/**
 * Глобальный поиск по настройкам ВСЕХ вкладок сразу (Game/Graphics/Sound/Dev/Client/«Моды»/...).
 * <p>
 * Раньше поиск жил внутри каждой вкладки отдельно, и чтобы найти, скажем, «прозрачность лазеров»,
 * надо было знать, в какой из пяти-шести вкладок она лежит (а лежит она то в ванильной Graphics,
 * то в Client -> Graphics, то в «Моды»). Здесь настройки собираются из {@code SettingsTable.getSettings()}
 * каждой вкладки - того же самого списка, из которого вкладка сама себя рисует, поэтому в поиск попадает
 * ровно то, что зарегистрировано «трекаемым» путём (pref/checkPref/sliderPref/ButtonSetting/LabelSetting...).
 * Сырые {@code table.add(...)} мимо списка (как у mi2u) не находятся - так же, как и во внутрипоисковой строке.
 * <p>
 * Найденные настройки рисуются НАСТОЯЩИМИ контролами (тот же {@code Setting.add(table)}), а не ссылками:
 * менять можно прямо из выдачи. Над каждой - «хлебная крошка» {@code Вкладка > Секция > Фича}; клик по
 * ней открывает вкладку и разворачивает секцию, в которой настройка живёт.
 */
public class SettingsSearch{
    private static final int maxResults = 40;
    /** Сколько «ближайших по написанию» показывать, если точных совпадений нет (опечатка). */
    private static final int maxFuzzy = 8;

    private final SettingsMenuDialog dialog;
    private final Table page = new Table();
    /** Приёмник, в который настройки дорисовывают свои строки; сам себя не перестраивает (см. {@link SettingsTable#detached}). */
    private final SettingsTable results = SettingsTable.detached();
    private final Seq<Entry> index = new Seq<>();
    private int tabCount;
    private TextField field;

    public SettingsSearch(SettingsMenuDialog dialog){
        this.dialog = dialog;

        page.top().left();
        page.table(bar -> {
            bar.left();
            bar.image(Icon.zoom).padRight(6f);
            field = bar.field("", text -> rebuildResults()).growX().get();
            field.setMessageText("@client.settings.searchall.hint");
        }).width(Math.min(500f, Core.graphics.getWidth() / 1.2f / Scl.scl(1f))).left().padBottom(6f);
        page.row();
        page.add(results).left().growX();
    }

    /** Переиндексирует настройки (мод мог добавить свои после старта), сбрасывает запрос и отдаёт страницу для вставки в prefs. */
    public Table open(){
        reindex();
        field.setText("");
        rebuildResults();
        //фокус - в следующем кадре: страница ещё не вставлена в сцену; на мобильных клавиатура сама по себе не всплывает
        Core.app.post(() -> {
            if(!Vars.mobile) Core.scene.setKeyboardFocus(field);
        });
        return page;
    }

    private void reindex(){
        index.clear();
        Seq<Tab> tabs = dialog.tabs();
        tabCount = tabs.size;
        for(Tab tab : tabs){
            Category section = null;
            String feature = null;
            for(Setting s : tab.table.getSettings()){
                if(s instanceof Category c){ //Category.isHeader() тоже true - поэтому проверяется первой
                    section = c;
                    feature = null;
                }else if(s.isHeader()){
                    feature = s.title;
                }else if(s.title != null && !s.title.isEmpty()){
                    index.add(new Entry(tab, section, feature, s));
                }
            }
        }
    }

    private void rebuildResults(){
        results.clearChildren();
        String query = field.getText().trim().toLowerCase();

        if(query.isEmpty()){
            results.add(Core.bundle.format("client.settings.searchall.stats", index.size, tabCount), Pal.lightishGray).left().padTop(8f).row();
            return;
        }

        String[] tokens = query.split("\\s+");
        Seq<Entry> found = new Seq<>();
        for(Entry e : index){
            e.rank = e.score(tokens);
            if(e.rank >= 0) found.add(e);
        }
        //стабильная сортировка: внутри одного ранга порядок остаётся таким, каким настройки идут во вкладках
        found.sort(e -> e.rank);

        boolean fuzzy = found.isEmpty();
        if(fuzzy && query.length() >= 3){
            //точных совпадений нет - скорее всего опечатка; та же метрика, что у поиска внутри вкладки
            Seq<Entry> all = index.copy();
            all.sort(e -> ClientUtils.biasedLevenshtein(query, e.title, true, true));
            all.truncate(maxFuzzy);
            found = all;
        }

        if(found.isEmpty()){
            results.add("@client.settings.searchall.none", Pal.lightishGray).left().padTop(8f).row();
            return;
        }
        if(fuzzy) results.add("@client.settings.searchall.fuzzy", Pal.lightishGray).left().padTop(8f).row();

        int shown = Math.min(found.size, maxResults);
        for(int i = 0; i < shown; i++){
            Entry e = found.get(i);
            results.button(e.crumb, Icon.rightOpen, Styles.flatt, 16f, () -> jump(e)).left().height(30f).padTop(10f).row();
            try{
                e.setting.add(results);
            }catch(Throwable t){ //одна кривая настройка не должна ронять всю выдачу
                Log.err("[settings-search] failed to draw '@'", e.setting.name);
                Log.err(t);
            }
        }
        if(found.size > shown){
            results.add(Core.bundle.format("client.settings.searchall.more", found.size - shown), Pal.lightishGray).left().padTop(10f).row();
        }
    }

    /** Открывает родную вкладку настройки, предварительно развернув её секцию. */
    private void jump(Entry e){
        if(e.section != null) e.section.setCollapsed(false);
        dialog.visible(e.tab.index);
    }

    private static class Entry{
        final Tab tab;
        final @Nullable Category section;
        final Setting setting;
        /** Уже приведённое к нижнему регистру, без цветовой разметки. */
        final String title, name, description, context;
        final String crumb;
        int rank;

        Entry(Tab tab, Category section, String feature, Setting setting){
            this.tab = tab;
            this.section = section;
            this.setting = setting;
            title = clean(setting.title);
            name = setting.name == null ? "" : setting.name.toLowerCase();
            description = clean(setting.description);

            String sectionTitle = section == null ? null : clean(section.title);
            String featureTitle = feature == null ? null : clean(feature);
            context = tab.name.toLowerCase() + " " + (sectionTitle == null ? "" : sectionTitle) + " " + (featureTitle == null ? "" : featureTitle);

            StringBuilder sb = new StringBuilder("[lightgray]").append(Strings.stripColors(tab.name));
            if(section != null) sb.append(" > ").append(Strings.stripColors(section.title));
            if(feature != null) sb.append(" > ").append(Strings.stripColors(feature));
            crumb = sb.append("[]").toString();
        }

        private static String clean(String s){
            return s == null ? "" : Strings.stripColors(s).toLowerCase();
        }

        /**
         * -1 - не подходит; 0 - все слова запроса в названии (или ключе) настройки; 1 - все в названии + вкладке/секции/фиче
         * (например «моды bridge»); 2 - остальное совпало только через описание.
         */
        int score(String[] tokens){
            boolean allInTitle = true, allInTitleOrContext = true;
            for(String t : tokens){
                boolean inTitle = title.contains(t) || name.contains(t);
                boolean inContext = context.contains(t);
                if(!inTitle && !inContext && !description.contains(t)) return -1;
                if(!inTitle) allInTitle = false;
                if(!inTitle && !inContext) allInTitleOrContext = false;
            }
            return allInTitle ? 0 : allInTitleOrContext ? 1 : 2;
        }
    }
}
