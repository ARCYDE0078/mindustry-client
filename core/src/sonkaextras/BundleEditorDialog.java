package sonkaextras;

import arc.*;
import arc.graphics.*;
import arc.math.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.ctype.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.ui.*;
import mindustry.ui.dialogs.*;

import java.util.*;

import static mindustry.Vars.*;

/**
 * Редактор строк бандла: поиск по ключу/тексту, правка любой строки (названия блоков, юнитов,
 * предметов, подписи настроек...), сброс, импорт/экспорт через буфер обмена. Хранение и применение -
 * в {@link BundleOverrides}; здесь только интерфейс.
 * <p>
 * В списке сотни-тысячи ключей, поэтому показывается не больше {@link #maxRows} совпадений
 * (дальше "уточни поиск") - так диалог не строит тысячи Label-ов на каждую букву запроса.
 * Ширины строк заданы явно: wrap-Label без фиксированной ширины ячейки раздувает колонку до
 * ширины всего текста (та же ловушка, что была в карточках Mindustry Tool).
 */
public class BundleEditorDialog extends BaseDialog{
    private static final int maxRows = 100;

    private enum Filter{
        all, changed, block("block."), unit("unit."), item("item."), liquid("liquid."), status("status."), setting("setting.", "client.setting."), other;

        /** Пустой набор = фильтр не по префиксу (all/changed - особые, other - "всё, что не попало в остальные"). */
        final String[] prefixes;

        Filter(String... prefixes){
            this.prefixes = prefixes;
        }
    }

    private String query = "";
    private Filter filter = Filter.all;
    /** Скрывать description/details/credit: за "названиями" обычно приходят именно за ними. */
    private boolean hideLong = true;

    private Table list;
    private Label summary;
    private Seq<String> allKeys = new Seq<>();

    public BundleEditorDialog(){
        super("@client.bundleeditor.title");
        shown(() -> {
            collectKeys();
            setup();
        });
        onResize(this::setup);
    }

    /** Все ключи цепочки бандлов (лист + родители), по алфавиту. */
    private void collectKeys(){
        ObjectSet<String> set = new ObjectSet<>();
        for(var b = Core.bundle; b != null; b = b.getParent()){
            for(String key : b.getKeys()) set.add(key);
        }
        allKeys = set.toSeq();
        allKeys.sort(String.CASE_INSENSITIVE_ORDER);
    }

    private static boolean isLong(String key){
        return key.endsWith(".description") || key.endsWith(".details") || key.endsWith(".credit");
    }

    private boolean matches(String key, String[] tokens){
        if(filter == Filter.changed){
            if(!BundleOverrides.isOverridden(key)) return false;
        }else if(filter == Filter.other){
            for(Filter f : Filter.values()){
                for(String p : f.prefixes) if(key.startsWith(p)) return false;
            }
        }else if(filter.prefixes.length > 0){
            boolean ok = false;
            for(String p : filter.prefixes) ok |= key.startsWith(p);
            if(!ok) return false;
        }
        if(hideLong && isLong(key)) return false;

        if(tokens.length == 0) return true;
        String text = key.toLowerCase(Locale.ROOT);
        String value = Core.bundle.getOrNull(key);
        if(value != null) text += " " + Strings.stripColors(value).toLowerCase(Locale.ROOT);
        for(String t : tokens){
            if(!text.contains(t)) return false;
        }
        return true;
    }

    /** 0 - ключ-название с запросом в самом ключе, дальше хуже; внутри ранга порядок алфавитный. */
    private static int rank(String key, String[] tokens){
        String lower = key.toLowerCase(Locale.ROOT);
        boolean inKey = true;
        for(String t : tokens) inKey &= lower.contains(t);
        return (key.endsWith(".name") ? 0 : 2) + (inKey ? 0 : 1);
    }

    private void setup(){
        cont.clear();
        buttons.clear();

        float width = Math.min(Core.graphics.getWidth() / Scl.scl() * 0.92f, 1000f);

        cont.table(top -> {
            top.image(Icon.zoom).padRight(8f);
            TextField search = top.field(query, text -> {
                query = text;
                rebuildList();
            }).growX().get();
            search.setMessageText("@client.bundleeditor.search");
        }).width(width).padBottom(6f).row();

        cont.pane(Styles.noBarPane, chips -> {
            chips.left().defaults().height(40f).pad(2f).minWidth(60f);
            for(Filter f : Filter.values()){
                chips.button("@client.bundleeditor.filter." + f.name(), Styles.togglet, () -> {
                    filter = f;
                    rebuildList();
                }).update(b -> b.setChecked(filter == f));
            }
        }).width(width).height(46f).padBottom(4f).row();

        cont.table(info -> {
            summary = info.add("").color(Color.gray).left().growX().get();
            info.check("@client.bundleeditor.hidelong", hideLong, v -> {
                hideLong = v;
                rebuildList();
            }).right();
        }).width(width).row();

        cont.pane(Styles.noBarPane, t -> {
            list = t;
            t.top();
        }).width(width).grow().padBottom(4f).row();

        cont.add("@client.bundleeditor.hint").color(Color.gray).width(width).wrap().left();

        buttons.defaults().size(Math.min(width / 4f - 8f, 210f), 64f).pad(4f);
        buttons.button("@back", Icon.left, this::hide);
        buttons.button("@client.bundleeditor.export", Icon.copy, () -> {
            if(BundleOverrides.count() == 0){
                ui.showInfoToast("@client.bundleeditor.empty", 2f);
                return;
            }
            Core.app.setClipboardText(BundleOverrides.exportText());
            ui.showInfoToast("@copied", 2f);
        }).tooltip("@client.bundleeditor.export.tooltip");
        buttons.button("@client.bundleeditor.import", Icon.paste, this::importFromClipboard).tooltip("@client.bundleeditor.import.tooltip");
        buttons.button("@client.bundleeditor.resetall", Icon.trash, () -> {
            if(BundleOverrides.count() == 0) return;
            ui.showConfirm("@confirm", Core.bundle.format("client.bundleeditor.resetall.confirm", BundleOverrides.count()), () -> {
                BundleOverrides.resetAll();
                rebuildList();
            });
        });

        rebuildList();
    }

    private void importFromClipboard(){
        ObjectMap<String, String> map = BundleOverrides.parse(orEmpty(Core.app.getClipboardText()));
        if(map.isEmpty()){
            ui.showInfoToast("@client.bundleeditor.paste.none", 3f);
            return;
        }
        ui.showCustomConfirm("@confirm", Core.bundle.format("client.bundleeditor.paste.confirm", map.size),
            "@client.bundleeditor.paste.replace", "@client.bundleeditor.paste.merge", () -> applyImport(map, true), () -> applyImport(map, false));
    }

    private void applyImport(ObjectMap<String, String> map, boolean replace){
        int n = BundleOverrides.importAll(map, replace);
        ui.showInfoToast(Core.bundle.format("client.bundleeditor.paste.done", n, map.size), 3f);
        rebuildList();
    }

    private void rebuildList(){
        if(list == null) return;
        list.clear();

        String[] tokens = query.trim().isEmpty() ? new String[0] : query.trim().toLowerCase(Locale.ROOT).split("\\s+");
        float width = Math.min(Core.graphics.getWidth() / Scl.scl() * 0.92f, 1000f) - 24f;

        Seq<String> found = new Seq<>();
        for(String key : allKeys){
            if(matches(key, tokens)) found.add(key);
        }
        //при запросе названия (".name") и совпадения прямо в ключе - выше длинных текстов, где слово просто встретилось
        if(tokens.length > 0){
            found.sort(Structs.comps(Structs.comparingInt(k -> rank(k, tokens)), String.CASE_INSENSITIVE_ORDER::compare));
        }

        int total = found.size, shown = Math.min(total, maxRows);
        for(int i = 0; i < shown; i++){
            addRow(found.get(i), width);
        }

        if(total == 0){
            list.add("@client.bundleeditor.nothing").color(Color.gray).pad(20f);
        }
        summary.setText(Core.bundle.format("client.bundleeditor.summary", shown, total, BundleOverrides.count()));
    }

    /** Строка = [иконка] [ключ + текст (+ оригинал)] [правка] [сброс]. */
    private void addRow(String key, float width){
        boolean changed = BundleOverrides.isOverridden(key);
        UnlockableContent u = BundleOverrides.contentFor(key);
        float iconW = 48f, buttonsW = 100f, textW = width - iconW - buttonsW - 24f;

        list.table(Styles.grayPanel, row -> {
            row.margin(6f);
            if(u != null){
                row.image(u.uiIcon).size(40f).padRight(8f).top();
            }else{
                row.image(Icon.list).size(32f).color(Color.darkGray).padRight(8f).padLeft(4f).top();
            }

            row.table(info -> {
                info.left();
                info.add(key).color(changed ? Pal.accent : Color.gray).fontScale(0.8f).width(textW).wrap().left().row();
                info.add(display(Core.bundle.getOrNull(key))).width(textW).wrap().left().row();
                if(changed){
                    info.add(Core.bundle.format("client.bundleeditor.was", display(BundleOverrides.original(key)))).color(Color.gray).fontScale(0.8f).width(textW).wrap().left();
                }
            }).width(textW + 8f).top().left();

            row.table(btns -> {
                btns.button(Icon.pencil, Styles.clearNonei, () -> edit(key)).size(44f);
                btns.button(Icon.undo, Styles.clearNonei, () -> {
                    BundleOverrides.reset(key);
                    rebuildList();
                }).size(44f).disabled(b -> !BundleOverrides.isOverridden(key));
            }).top();
        }).width(width).pad(3f).row();
    }

    private static String orEmpty(String text){
        return text == null ? "" : text;
    }

    /** Текст для Label: строка, начинающаяся с "@", иначе была бы принята за ключ бандла. */
    private static String display(String text){
        if(text == null) return "[gray]-";
        return text.startsWith("@") ? "​" + text : text;
    }

    private void edit(String key){
        String current = orEmpty(Core.bundle.getOrNull(key));
        float width = Math.min(Core.graphics.getWidth() / Scl.scl() * 0.9f - 40f, 700f);
        boolean big = current.length() > 70 || current.contains("\n");

        BaseDialog d = new BaseDialog("@client.bundleeditor.edit");
        //без этого сквозь полупрозрачный фон просвечивают строки родительского списка и мешают читать
        d.setBackground(Styles.black);
        d.addCloseButton();

        d.cont.add(key).color(Color.gray).width(width).wrap().left().row();
        if(BundleOverrides.isOverridden(key)){
            d.cont.add(Core.bundle.format("client.bundleeditor.was", display(BundleOverrides.original(key)))).color(Color.gray).width(width).wrap().left().padBottom(6f).row();
        }

        TextArea area = new TextArea(current);
        area.setPrefRows(big ? 6 : 2);
        d.cont.add(area).width(width).padBottom(6f).row();

        d.cont.add("@client.bundleeditor.preview").color(Color.gray).left().row();
        Label preview = d.cont.add(display(current)).width(width).wrap().left().minHeight(40f).get();
        d.cont.row();

        String[] error = {null};
        Label err = d.cont.add("").color(Color.scarlet).width(width).wrap().left().get();
        area.changed(() -> {
            preview.setText(display(area.getText()));
            error[0] = BundleOverrides.validate(area.getText());
            err.setText(error[0] == null ? "" : Core.bundle.format("client.bundleeditor.invalid", error[0]));
        });

        d.buttons.button("@client.bundleeditor.save", Icon.ok, () -> {
            BundleOverrides.set(key, area.getText());
            d.hide();
            rebuildList();
        }).size(210f, 64f).disabled(b -> error[0] != null);

        d.buttons.button("@client.bundleeditor.reset", Icon.undo, () -> {
            BundleOverrides.reset(key);
            d.hide();
            rebuildList();
        }).size(210f, 64f).disabled(b -> !BundleOverrides.isOverridden(key));

        d.show();
    }
}
