package qolc.keybinds;

import arc.Core;
import arc.Events;
import arc.input.KeyCode;
import arc.scene.ui.ScrollPane;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.serialization.Jval;
import mindustry.Vars;
import mindustry.game.EventType.Trigger;
import mindustry.gen.Icon;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.BaseDialog;
import mindustry.ui.fragments.ChatFragment;

/**
 * Пользовательские КЛАВИАТУРНЫЕ бинды (ctrl/alt/shift + клавиша), запускающие чат-текст или команды.
 * Порт core/bind.js.
 * <p>
 * Не путать с {@code qol.cbinds.CustomBindsFeature} (экранные КНОПКИ из того же исходного мода,
 * ui/cbinds.js) - здесь именно горячие клавиши без UI на экране. Хранение - JSON в настройке
 * {@code qol-binds}, ТОТ ЖЕ ключ и формат, что у JS-оригинала: бинды, настроенные игроком в моде,
 * подхватываются портом как есть.
 * <p>
 * Исполнение строк идёт через {@link ChatFragment#handleClientCommand(String)} - ровно тот путь,
 * которым уходит текст из чат-бокса: клиентские {@code !}-команды исполняются, серверные
 * {@code /}-команды и обычный текст шлются на сервер с подписью клиента. Оригинал слал всё сырым
 * {@code Call.sendChatMessage}, из-за чего его биндами нельзя было дёргать команды самого мода -
 * в нативном порте это ограничение снято.
 * <p>
 * v6.6 -&gt; v7.1: оригинал (core/bind.js) переехал с хранения "один бинд на комбинацию" (JSON-объект
 * {@code {"ctrl+K": "команда"}}) на список объектов - это разрешает НЕСКОЛЬКО биндов на одну и ту же
 * комбинацию клавиш и добавляет чекбокс {@code enabled} у каждого бинда (выключить, не удаляя). Порт
 * читает оба формата (старый JSON-объект молча мигрируется в список при первой загрузке) и всегда
 * пишет новый формат - настройки от JS-мода (и наоборот) переживают переключение self-disable guard'ом.
 * Здесь это далось даром: {@link Bind} и так хранился в {@link Seq}, а не в мапе, так что дублирующиеся
 * комбинации технически не ломались - удалена только строчка, которая раньше СТИРАЛА старый бинд с той
 * же комбинацией при добавлении нового (эмулировала объектную уникальность оригинала до v6.8).
 * <p>
 * НЕ портировано: привязка бинда к конкретным IP серверов (поле {@code ips} у оригинала, задаётся в
 * форме редактирования) - оригинал определяет текущий IP через рефлексию в приватное поле
 * {@code host} инстанса {@code NetClient} (или запасные варианты через настройки {@code qol-last-ip}/
 * {@code ip}), это внутренности движка, которые рискованно портировать вслепую без компиляции в этом
 * форке клиента. Поле {@code ips} читается/пишется в JSON (чтобы не терять данные игрока при
 * миграции/переключении на реальный мод), но не проверяется и не показывается в форме редактирования -
 * т.е. в этом порте ни один бинд не ограничен сервером, все выполняются везде, как раньше.
 */
public final class ChatKeyBindsFeature{
    private static final String settingsKey = "qol-binds";

    /** Распарсенный кеш биндов - перечитывается только при сохранении. */
    private static final Seq<Bind> binds = new Seq<>();
    private static BaseDialog dialog;

    static class Bind{
        String raw; //"ctrl+alt+K" - как хранится в JSON
        boolean ctrl, alt, shift;
        KeyCode key;
        String command;
        /** v7.1: можно выключить бинд, не удаляя - галочка в списке. */
        boolean enabled = true;
        /** v7.1 оригинала: список разрешённых IP через запятую. Не проверяется в порте (см. javadoc класса) - хранится только для round-trip. */
        String ips = "";
    }

    private ChatKeyBindsFeature(){
    }

    public static void init(){
        load();
        Events.run(Trigger.update, ChatKeyBindsFeature::update);
    }

    private static void load(){
        binds.clear();
        try{
            Jval root = Jval.read(Core.settings.getString(settingsKey, "[]"));
            if(root.isArray()){
                //текущий формат оригинала (v6.8+): массив объектов {key, cmd, enabled, ips}
                for(Jval item : root.asArray()){
                    Bind bind = parse(item.getString("key", ""), item.getString("cmd", ""));
                    if(bind != null){
                        bind.enabled = item.getBool("enabled", true);
                        bind.ips = item.getString("ips", "");
                        binds.add(bind);
                    }
                }
            }else if(root.isObject()){
                //старый формат (до v6.8): объект {"ctrl+K": "команда"} - одноразовая миграция в новый список
                for(var entry : root.asObject()){
                    Bind bind = parse(entry.key, entry.value.asString());
                    if(bind != null) binds.add(bind);
                }
                save();
            }
        }catch(Throwable t){
            Log.err("[qol-control] failed to parse " + settingsKey, t);
        }
    }

    private static Bind parse(String raw, String command){
        Bind bind = new Bind();
        bind.raw = raw;
        bind.command = command;
        String[] parts = raw.split("\\+");
        for(int i = 0; i < parts.length - 1; i++){
            switch(parts[i]){
                case "ctrl" -> bind.ctrl = true;
                case "alt" -> bind.alt = true;
                case "shift" -> bind.shift = true;
            }
        }
        try{
            bind.key = KeyCode.valueOf(parts[parts.length - 1]);
        }catch(IllegalArgumentException e){
            return null;
        }
        return bind;
    }

    private static void save(){
        //v7.1: пишем массив, а не объект - иначе несколько биндов на одну комбинацию схлопнутся в один ключ
        Jval root = Jval.newArray();
        for(Bind bind : binds){
            Jval obj = Jval.newObject();
            obj.put("key", bind.raw);
            obj.put("cmd", bind.command);
            obj.put("enabled", bind.enabled);
            obj.put("ips", bind.ips);
            root.add(obj);
        }
        Core.settings.put(settingsKey, root.toString());
    }

    private static void update(){
        if(binds.isEmpty() || !Vars.state.isGame()) return;
        if(Core.scene.hasKeyboard() || Core.scene.hasDialog() || Vars.ui.chatfrag.shown()) return;

        for(Bind bind : binds){
            if(!bind.enabled) continue; //v7.1: выключенный бинд не отрабатывает, но остаётся в списке
            if(!Core.input.keyTap(bind.key)) continue;
            if(bind.ctrl != Core.input.ctrl() || bind.alt != Core.input.alt() || bind.shift != Core.input.shift()) continue;
            execute(bind.command);
        }
    }

    private static void execute(String text){
        for(String line : text.split("\n")){
            while(line.length() > 150){
                ChatFragment.handleClientCommand(line.substring(0, 150));
                line = line.substring(150);
            }
            if(!line.isEmpty()) ChatFragment.handleClientCommand(line);
        }
    }

    public static void showDialog(){
        if(dialog == null){
            dialog = new BaseDialog(Core.bundle.get("qolc.keybinds.title"));
            dialog.addCloseButton();
        }
        rebuild();
        dialog.show();
    }

    private static void rebuild(){
        dialog.cont.clear();

        Table list = new Table();
        list.top().left();

        if(binds.isEmpty()){
            list.add(Core.bundle.get("qolc.keybinds.empty")).color(arc.graphics.Color.lightGray).pad(10f).row();
        }else{
            for(Bind bind : binds){
                Table row = new Table();
                //v7.1: галочка вкл/выкл бинда, не удаляя его из списка
                row.check("", bind.enabled, b -> {
                    bind.enabled = b;
                    save();
                }).padRight(5f);
                row.add("[accent]" + bind.raw).width(150f).left().padRight(10f);
                var preview = row.add(bind.command.replace("\n", " | ")).left().growX().minWidth(0f).get();
                preview.setEllipsis(true);
                row.button(Icon.pencil, Styles.cleari, () -> showEdit(bind)).size(45f);
                row.button(Icon.trash, Styles.cleari, () -> Vars.ui.showConfirm("@confirm", Core.bundle.format("qolc.keybinds.delete-confirm", bind.raw), () -> {
                    binds.remove(bind);
                    save();
                    rebuild();
                })).size(45f);
                list.add(row).growX().padBottom(5f).row();
            }
        }

        dialog.cont.add(new ScrollPane(list)).width(520f).height(340f).row();
        dialog.cont.button(Core.bundle.get("qolc.keybinds.add"), Icon.add, () -> showEdit(null)).size(520f, 50f).padTop(10f);
    }

    private static void showEdit(Bind existing){
        BaseDialog d = new BaseDialog(Core.bundle.get(existing == null ? "qolc.keybinds.add" : "qolc.keybinds.edit"));

        String[] combo = {existing == null ? "" : existing.raw};
        StringBuilder command = new StringBuilder(existing == null ? "" : existing.command);

        Table t = new Table();
        t.add(Core.bundle.get("qolc.keybinds.key")).padRight(5f).right();
        var keyButton = t.button(combo[0].isEmpty() ? Core.bundle.get("qolc.keybinds.click-to-set") : combo[0], () -> {}).size(280f, 50f).get();
        keyButton.clicked(() -> showListening(res -> {
            combo[0] = res;
            keyButton.setText(res);
        }));
        t.row();

        t.add(Core.bundle.get("qolc.keybinds.command")).colspan(2).padTop(10f).left().row();
        t.area(command.toString(), txt -> {
            command.setLength(0);
            command.append(txt);
        }).size(420f, 200f).colspan(2).padTop(5f).row();

        d.cont.add(t).row();
        d.buttons.button("@cancel", Icon.cancel, d::hide).size(150f, 50f);
        d.buttons.button("@ok", Icon.ok, () -> {
            if(combo[0].isEmpty() || command.length() == 0){
                Vars.ui.showInfo(Core.bundle.get("qolc.keybinds.incomplete"));
                return;
            }
            Bind parsed = parse(combo[0], command.toString());
            if(parsed == null){
                Vars.ui.showInfo(Core.bundle.get("qolc.keybinds.incomplete"));
                return;
            }
            if(existing != null){
                //правка на месте (не remove+add) - сохраняет позицию в списке и enabled/ips существующего бинда;
                //v7.1 оригинала специально разрешает НЕСКОЛЬКО биндов на одну комбинацию, поэтому больше не
                //стираем другие бинды с тем же combo, как раньше (см. javadoc класса)
                existing.raw = parsed.raw;
                existing.ctrl = parsed.ctrl;
                existing.alt = parsed.alt;
                existing.shift = parsed.shift;
                existing.key = parsed.key;
                existing.command = parsed.command;
            }else{
                binds.add(parsed);
            }
            save();
            d.hide();
            rebuild();
        }).size(150f, 50f);

        d.show();
    }

    /** Диалог захвата комбинации: опрос клавиатуры в update() самого диалога - работает и на паузе, где Trigger.update молчит. */
    private static void showListening(arc.func.Cons<String> callback){
        BaseDialog d = new BaseDialog(Core.bundle.get("qolc.keybinds.listening"));
        d.cont.add(Core.bundle.get("qolc.keybinds.press-combo")).row();
        d.cont.button("@cancel", d::hide).size(150f, 50f).padTop(10f);
        d.update(() -> {
            for(KeyCode k : KeyCode.all){
                if(k == KeyCode.controlLeft || k == KeyCode.controlRight
                    || k == KeyCode.shiftLeft || k == KeyCode.shiftRight
                    || k == KeyCode.altLeft || k == KeyCode.altRight
                    || k == KeyCode.unknown || k == KeyCode.escape || k == KeyCode.back
                    || k == KeyCode.mouseLeft || k == KeyCode.mouseRight || k == KeyCode.mouseMiddle
                    || k == KeyCode.mouseBack || k == KeyCode.mouseForward) continue;

                if(Core.input.keyTap(k)){
                    StringBuilder res = new StringBuilder();
                    if(Core.input.ctrl()) res.append("ctrl+");
                    if(Core.input.alt()) res.append("alt+");
                    if(Core.input.shift()) res.append("shift+");
                    res.append(k.name());
                    d.hide();
                    callback.get(res.toString());
                    return;
                }
            }
        });
        d.show();
    }
}
