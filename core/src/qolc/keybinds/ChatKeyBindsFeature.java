package qolc.keybinds;

import arc.Core;
import arc.Events;
import arc.graphics.Color;
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
import qolc.CardUi;

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
 * v7.1 -&gt; v7.12: привязка бинда к серверу (поле {@code ips}, список {@code ip} / {@code ip:порт} через
 * запятую, пусто = везде). Раньше пропускалась: оригинал берёт адрес рефлексией из приватного
 * {@code NetClient.host}, а это рискованно портировать вслепую. В этом форке нужный адрес лежит в
 * ПУБЛИЧНОМ поле {@code ui.join.lastHost} (его же читают счётчик игроков и отчёт о крэше), так что
 * рефлексия не нужна. Запасной вариант оригинала - настройка {@code ip} (последний введённый в диалоге
 * присоединения адрес) - оставлен для случая, когда {@code lastHost} пуст (steam-сервер, ручное
 * подключение), но только при активном сетевом клиенте: оригинал сверял её и в одиночной игре, из-за
 * чего бинд, ограниченный сервером, срабатывал и в синглплеере, если когда-то вводили тот же адрес.
 * Второй запасной вариант оригинала ({@code qol-last-ip}) пишет только модуль server.js (быстрый вход
 * на сервер), который здесь не портирован, - читать его нечем.
 * <p>
 * Диалоги переехали на карточки ({@link CardUi}) как в оригинале v7.x: в форме правки теперь есть
 * галочка enabled и поле серверов.
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
        /** Список разрешённых серверов через запятую, пусто = везде (см. {@link #allowedOnServer}). */
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
        qol.core.SafeSettings.putString(settingsKey, root.toString());
    }

    private static void update(){
        if(binds.isEmpty() || !Vars.state.isGame()) return;
        if(Core.scene.hasKeyboard() || Core.scene.hasDialog() || Vars.ui.chatfrag.shown()) return;

        for(Bind bind : binds){
            if(!bind.enabled) continue; //v7.1: выключенный бинд не отрабатывает, но остаётся в списке
            if(!Core.input.keyTap(bind.key)) continue;
            if(bind.ctrl != Core.input.ctrl() || bind.alt != Core.input.alt() || bind.shift != Core.input.shift()) continue;
            if(!allowedOnServer(bind.ips)) continue;
            execute(bind.command);
        }
    }

    /**
     * Подходит ли текущий сервер под список {@code ip} / {@code ip:порт} (через запятую, без учёта регистра).
     * Запись без порта сверяется только с адресом, с портом - с парой целиком. Пустой список разрешает всё;
     * если адрес сервера определить не удалось - бинд с ограничением не срабатывает (безопаснее, чем
     * выполнить чужую команду не на том сервере).
     */
    static boolean allowedOnServer(String ipsStr){
        if(ipsStr == null || ipsStr.isBlank()) return true;

        String ipOnly = null, ipAndPort = null;
        if(Vars.net.client()){
            var host = Vars.ui.join.lastHost;
            if(host != null && host.address != null){
                ipOnly = stripAddress(host.address);
                ipAndPort = ipOnly + ":" + host.port;
            }else{
                //steam / ручное подключение: lastHost пуст, остаётся последний введённый в окне присоединения адрес
                String typed = Core.settings.getString("ip", "");
                if(!typed.isBlank()){
                    ipAndPort = stripLeadingSlash(typed);
                    ipOnly = stripAddress(typed);
                }
            }
        }
        if(ipOnly == null) return false;

        for(String pattern : ipsStr.split(",")){
            pattern = pattern.trim().toLowerCase();
            if(pattern.isEmpty()) continue;
            if(pattern.contains(":") ? pattern.equals(ipAndPort.toLowerCase()) : pattern.equals(ipOnly.toLowerCase())) return true;
        }
        return false;
    }

    private static String stripLeadingSlash(String address){
        return address.startsWith("/") ? address.substring(1) : address;
    }

    /** Адрес без ведущего "/" (как у InetAddress.toString) и без порта. */
    private static String stripAddress(String address){
        address = stripLeadingSlash(address);
        int colon = address.indexOf(':');
        return colon == -1 ? address : address.substring(0, colon);
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

        Table root = new Table();
        root.top().left().margin(10f);

        Table list = CardUi.card(root, Core.bundle.format("qolc.keybinds.configured", binds.size), "50fa7b");
        if(binds.isEmpty()){
            list.add(Core.bundle.get("qolc.keybinds.empty")).color(Color.lightGray).pad(10f).left().row();
        }else{
            for(Bind bind : binds){
                Table row = list.table().growX().pad(2f).get();
                //галочка вкл/выкл бинда, не удаляя его из списка
                row.check("", bind.enabled, b -> {
                    bind.enabled = b;
                    save();
                }).padRight(6f);

                //кнопка-строка: комбинация + превью команды, клик открывает правку
                var open = row.button(b -> {
                    b.left();
                    b.add("[accent]" + bind.raw).width(110f).left().padRight(10f).get().setEllipsis(true);
                    b.add("[white]" + bind.command.replace("\r", "").replace("\n", " | ")).left().growX().minWidth(0f).get().setEllipsis(true);
                }, Styles.grayt, () -> showEdit(bind));
                open.growX().height(48f).left().padRight(8f);

                row.button(Icon.pencil, Styles.cleari, () -> showEdit(bind)).size(40f, 48f).padRight(4f);
                row.button(Icon.trash, Styles.cleari, () -> Vars.ui.showConfirm("@confirm", Core.bundle.format("qolc.keybinds.delete-confirm", bind.raw), () -> {
                    binds.remove(bind);
                    save();
                    rebuild();
                })).size(40f, 48f);
                list.row();
            }
        }
        root.row();

        Table actions = root.table(Styles.black6).growX().padBottom(2f).get();
        actions.margin(8f);
        actions.button("[#73d216]" + Core.bundle.get("qolc.keybinds.add") + "[]", Icon.add, Styles.grayt, () -> showEdit(null)).size(220f, 40f);
        root.row();

        dialog.cont.add(scroll(root)).width(CardUi.width(0.95f, 680f)).height(CardUi.height(0.85f, 480f));
    }

    private static ScrollPane scroll(Table content){
        ScrollPane pane = new ScrollPane(content);
        pane.setScrollingDisabled(true, false);
        pane.setOverscroll(false, true);
        return pane;
    }

    private static void showEdit(Bind existing){
        BaseDialog d = new BaseDialog(Core.bundle.get(existing == null ? "qolc.keybinds.add" : "qolc.keybinds.edit"));
        d.addCloseButton();

        String[] combo = {existing == null ? "" : existing.raw};
        StringBuilder command = new StringBuilder(existing == null ? "" : existing.command);
        String[] ips = {existing == null ? "" : existing.ips};
        boolean[] enabled = {existing == null || existing.enabled};

        Table root = new Table();
        root.top().left().margin(10f);

        //1. комбинация клавиш + вкл/выкл
        Table keyCard = CardUi.card(root, Core.bundle.get("qolc.keybinds.card-key"), "50fa7b");
        Table keyRow = keyCard.table().growX().pad(2f).get();
        keyRow.add("[lightgray]" + Core.bundle.get("qolc.keybinds.key") + "[]").left().padRight(8f);
        var keyButton = keyRow.button(combo[0].isEmpty() ? "[gray]" + Core.bundle.get("qolc.keybinds.click-to-set") + "[]" : "[accent]" + combo[0] + "[]", Styles.grayt, () -> {}).height(40f).growX().get();
        keyButton.clicked(() -> showListening(res -> {
            combo[0] = res;
            keyButton.setText("[accent]" + res + "[]");
        }));
        keyCard.row();
        keyCard.check(Core.bundle.get("qolc.keybinds.enabled"), enabled[0], b -> enabled[0] = b).padTop(6f).left().row();
        root.row();

        //2. команда / текст
        Table cmdCard = CardUi.card(root, Core.bundle.get("qolc.keybinds.card-command"), "8be9fd");
        cmdCard.add("[lightgray]" + Core.bundle.get("qolc.keybinds.command") + "[]").left().padBottom(4f).row();
        cmdCard.area(command.toString(), txt -> {
            command.setLength(0);
            command.append(txt);
        }).growX().height(110f).row();
        cmdCard.add("[gray]" + Core.bundle.get("qolc.keybinds.tip") + "[]").fontScale(0.85f).left().padTop(4f).row();
        root.row();

        //3. привязка к серверам (необязательно)
        Table ipCard = CardUi.card(root, Core.bundle.get("qolc.keybinds.card-servers"), "ffd37f");
        ipCard.add("[lightgray]" + Core.bundle.get("qolc.keybinds.servers") + "[]").left().padBottom(4f).row();
        ipCard.field(ips[0], txt -> ips[0] = txt).growX().height(38f).row();
        root.row();

        //4. сохранить / отмена
        Table actions = root.table(Styles.black6).growX().padBottom(2f).get();
        actions.margin(8f);
        Table actionRow = actions.table().center().get();
        actionRow.button("[#73d216]" + Core.bundle.get("qolc.keybinds.save") + "[]", Icon.ok, Styles.grayt, () -> {
            if(combo[0].isEmpty() || command.length() == 0){
                Vars.ui.showInfo(Core.bundle.get("qolc.keybinds.incomplete"));
                return;
            }
            Bind parsed = parse(combo[0], command.toString());
            if(parsed == null){
                Vars.ui.showInfo(Core.bundle.get("qolc.keybinds.incomplete"));
                return;
            }
            parsed.enabled = enabled[0];
            parsed.ips = ips[0].trim();
            if(existing != null){
                //правка на месте (не remove+add) - сохраняет позицию в списке;
                //v7.1 оригинала специально разрешает НЕСКОЛЬКО биндов на одну комбинацию, поэтому не
                //стираем другие бинды с тем же combo, как раньше (см. javadoc класса)
                existing.raw = parsed.raw;
                existing.ctrl = parsed.ctrl;
                existing.alt = parsed.alt;
                existing.shift = parsed.shift;
                existing.key = parsed.key;
                existing.command = parsed.command;
                existing.enabled = parsed.enabled;
                existing.ips = parsed.ips;
            }else{
                binds.add(parsed);
            }
            save();
            d.hide();
            rebuild();
        }).size(170f, 40f).padRight(10f);
        actionRow.button(Core.bundle.get("cancel"), Icon.cancel, Styles.grayt, d::hide).size(140f, 40f);
        root.row();

        d.cont.add(scroll(root)).width(CardUi.width(0.95f, 640f)).height(CardUi.height(0.85f, 520f));
        d.show();
    }

    /** Диалог захвата комбинации: опрос клавиатуры в update() самого диалога - работает и на паузе, где Trigger.update молчит. */
    private static void showListening(arc.func.Cons<String> callback){
        BaseDialog d = new BaseDialog(Core.bundle.get("qolc.keybinds.listening"));
        d.addCloseButton();

        Table root = new Table();
        root.top().margin(16f);
        Table card = CardUi.card(root, Core.bundle.get("qolc.keybinds.press-combo-title"), "50fa7b");
        card.add("[lightgray]" + Core.bundle.get("qolc.keybinds.press-combo") + "[]").padBottom(10f).row();
        card.button(Core.bundle.get("cancel"), Icon.cancel, Styles.grayt, d::hide).size(160f, 42f);
        d.cont.add(root).width(CardUi.width(0.95f, 480f));

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
