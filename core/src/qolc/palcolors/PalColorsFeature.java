package qolc.palcolors;

import arc.Core;
import arc.graphics.Color;
import arc.scene.ui.Image;
import arc.scene.ui.ScrollPane;
import arc.scene.ui.TextField;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import arc.util.Log;
import mindustry.gen.Icon;
import mindustry.gen.Tex;
import mindustry.graphics.Pal;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.BaseDialog;
import qolc.CardUi;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * Кастомизация всей палитры {@link Pal} (цвет команды-акцента, лазеров, щитов, хила...) с
 * сохранением между сессиями. Порт core/colors.js.
 * <p>
 * qol-suite'овский Beam Colors перекрашивает три конкретных луча скобочной подменой на время
 * отрисовки; здесь другой уровень - постоянная подмена ЗНАЧЕНИЯ любого поля Pal (сам объект Color
 * мутируется через {@code set()}, ссылки не трогаются, так что кеширующие ссылку потребители видят
 * новый цвет). Скобочная подмена Beam Colors захватывает и восстанавливает текущее значение
 * НА КАЖДОМ кадре, поэтому обе фичи сосуществуют: перекрашенный здесь Pal.heal просто станет для неё
 * новой "ванилью".
 * <p>
 * Ключи настроек {@code qol-pal-<имя>} - как у JS-оригинала, пользовательская палитра переживает порт.
 * v7.1 -&gt; v7.12: диалог на карточках ({@link CardUi}) и кнопка «сбросить всё».
 * <p>
 * Некоторые поля Pal ссылаются на ОДИН экземпляр Color (например пары свет/тень) - правка одного
 * меняет оба; оригинал вёл себя так же.
 */
public final class PalColorsFeature{
    private static final String prefix = "qol-pal-";
    private static final Seq<Entry> colors = new Seq<>();

    static class Entry{
        String name;
        Color color;
        String def; //hex-строка дефолта, снятая ДО применения сохранёнок - для кнопки сброса

        Entry(String name, Color color, String def){
            this.name = name;
            this.color = color;
            this.def = def;
        }
    }

    private PalColorsFeature(){
    }

    public static void init(){
        try{
            for(Field f : Pal.class.getDeclaredFields()){
                if(f.getType() != Color.class || !Modifier.isStatic(f.getModifiers())) continue;
                Color obj = (Color)f.get(null);
                if(obj == null) continue;
                Entry entry = new Entry(f.getName(), obj, obj.toString());

                String saved = Core.settings.getString(prefix + entry.name, null);
                if(saved != null && isHex(saved)){
                    try{
                        obj.set(Color.valueOf(saved));
                    }catch(Throwable ignored){
                    }
                }
                colors.add(entry);
            }
            colors.sort((a, b) -> a.name.compareTo(b.name));
        }catch(Throwable t){
            Log.err("[qol-control] failed to init Pal colors", t);
        }
    }

    private static boolean isHex(String s){
        if(s.length() != 6 && s.length() != 8) return false;
        for(int i = 0; i < s.length(); i++){
            char c = s.charAt(i);
            if(!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) return false;
        }
        return true;
    }

    public static void showDialog(){
        BaseDialog dialog = new BaseDialog(Core.bundle.get("qolc.palcolors.title"));
        dialog.addCloseButton();

        Table root = new Table();
        root.top().left().margin(10f);

        Table swatches = CardUi.card(root, Core.bundle.get("qolc.palcolors.card"), "f1fa8c");
        for(Entry entry : colors){
            Table row = swatches.table().growX().pad(2f).get();

            Image swatch = new Image(Tex.whiteui);
            swatch.setColor(entry.color);
            row.table(Styles.black5, box -> box.add(swatch).size(26f)).size(32f).padRight(8f);

            row.add("[lightgray]" + entry.name + "[]").growX().minWidth(140f).padRight(8f).left();

            TextField field = new TextField(entry.color.toString());
            field.setMaxLength(8);
            field.changed(() -> {
                String text = field.getText();
                if(isHex(text)){
                    try{
                        entry.color.set(Color.valueOf(text));
                        swatch.setColor(entry.color);
                        Core.settings.put(prefix + entry.name, text);
                    }catch(Throwable ignored){
                    }
                }
            });
            row.add(field).width(110f).height(34f).padRight(8f);

            row.button(Icon.cancel, Styles.clearNonei, () -> {
                entry.color.set(Color.valueOf(entry.def));
                swatch.setColor(entry.color);
                field.setText(entry.def);
                Core.settings.remove(prefix + entry.name);
            }).size(32f);

            swatches.row();
        }
        root.row();

        //сброс всей палитры одной кнопкой (v7.x оригинала): откатывает каждое поле к значению, снятому ДО сохранёнок
        Table actions = root.table(Styles.black6).growX().padBottom(2f).get();
        actions.margin(8f);
        actions.button("[accent]" + Core.bundle.get("qolc.palcolors.reset-all") + "[]", Styles.grayt, () -> {
            for(Entry entry : colors){
                entry.color.set(Color.valueOf(entry.def));
                Core.settings.remove(prefix + entry.name);
            }
            //проще пересоздать диалог, чем синхронизировать сотню текстовых полей и образцов
            dialog.hide();
            showDialog();
        }).size(180f, 36f);
        root.row();

        ScrollPane scroll = new ScrollPane(root);
        scroll.setScrollingDisabled(true, false);
        scroll.setOverscroll(false, true);

        dialog.cont.add(scroll).width(CardUi.width(0.95f, 660f)).height(CardUi.height(0.85f, 520f));
        dialog.show();
    }
}
