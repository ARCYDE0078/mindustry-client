package sonkaextras.accounts;

import arc.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.util.*;
import mindustry.gen.*;
import mindustry.ui.*;
import mindustry.ui.dialogs.*;
import sonkaextras.accounts.Accounts.*;

import java.text.*;
import java.util.*;

import static mindustry.Vars.*;

/**
 * Диалог «Аккаунты (UUID)»: список личностей (имя, активная, uuid, ID для серверов, сколько серверов запомнено,
 * когда использовалась); на строку - переключить / изменить / сбросить / удалить, внизу - новый аккаунт.
 * Смена аккаунта действует со следующего подключения - диалог об этом говорит, а если игрок уже на сервере,
 * предупреждает отдельной строкой. Модель и правила (почему системный аккаунт нельзя сбросить) - в {@link Accounts}.
 * <p>
 * {@link #showPicker()} - компактный выбор аккаунта в одно касание: кнопка рядом с ником в окне «Играть по сети».
 */
public class AccountsDialog extends BaseDialog{
    static final DateFormat dateFormat = SimpleDateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);

    public AccountsDialog(){
        super("@client.sonka.accounts.title");
        addCloseButton();
        shown(this::setup);
        onResize(this::setup);
    }

    void setup(){
        cont.clear();

        cont.add("@client.sonka.accounts.hint").width(660f).wrap().pad(6f).row();
        if(net.active()){
            cont.add("[gold]" + Core.bundle.get("client.sonka.accounts.connected")).width(660f).wrap().pad(4f).row();
        }
        if(Accounts.recoveredFromCorrupt){
            cont.add("[scarlet]" + Core.bundle.get("client.sonka.accounts.corrupt")).width(660f).wrap().pad(4f).row();
        }

        cont.pane(list -> {
            list.top().defaults().growX().pad(4f);
            for(Account a : Accounts.list()){
                list.table(Tex.button, row -> buildRow(row, a)).row();
            }
        }).growX().maxHeight(Core.graphics.getHeight() * 0.55f).row();

        cont.table(b -> {
            b.defaults().height(48f).pad(3f).growX();
            b.button("@client.sonka.accounts.new", Icon.add, () -> ui.showTextInput("@client.sonka.accounts.new", "@client.sonka.accounts.name", 40, "", name -> {
                Account made = Accounts.create(name);
                setup();
                //сразу открываем правку: чаще всего тут же хочется вписать свой uuid или скопировать новый
                new AccountEditDialog(made, this::setup).show();
            }));
        }).growX().row();
    }

    void buildRow(Table row, Account a){
        boolean active = Accounts.isActive(a);
        row.left().margin(8f);
        row.table(info -> {
            info.left().defaults().left();
            String title = (active ? "[accent]" : "") + a.name
                + (a.stock ? "  [lightgray](" + Core.bundle.get("client.sonka.accounts.system") + ")" : "")
                + (active ? "  [lightgray](" + Core.bundle.get("client.sonka.accounts.active") + ")" : "");
            info.add(title).row();
            String uuid = a.effectiveUuid();
            info.add("[lightgray]" + Core.bundle.format("client.sonka.accounts.ids", uuid, Accounts.serverId(uuid))).row();
            String last = a.lastUsed == 0 ? Core.bundle.get("client.sonka.accounts.never") : dateFormat.format(new Date(a.lastUsed));
            info.add("[lightgray]" + Core.bundle.format("client.sonka.accounts.details", a.serverCount(), last)).row();
        }).growX().left();

        row.table(b -> {
            b.defaults().size(46f).pad(2f);
            b.button(Icon.play, Styles.clearNonei, () -> {
                Accounts.activate(a);
                ui.showInfoFade(Core.bundle.format("client.sonka.accounts.switched", a.name));
                setup();
            }).disabled(bt -> active).tooltip("@client.sonka.accounts.switch");
            b.button(Icon.pencil, Styles.clearNonei, () -> new AccountEditDialog(a, this::setup).show()).tooltip("@client.sonka.accounts.edit");
            b.button(Icon.refresh, Styles.clearNonei, () -> ui.showConfirm("@client.sonka.accounts.reset", Core.bundle.format("client.sonka.accounts.reset.confirm", a.name), () -> {
                Accounts.reset(a);
                setup();
            })).disabled(bt -> a.stock).tooltip(a.stock ? "@client.sonka.accounts.stock.locked" : "@client.sonka.accounts.reset");
            b.button(Icon.trash, Styles.clearNonei, () -> ui.showConfirm("@confirm", Core.bundle.format("client.sonka.accounts.delete.confirm", a.name), () -> {
                Accounts.delete(a);
                setup();
            })).disabled(bt -> a.stock || active).tooltip(a.stock ? "@client.sonka.accounts.stock.locked" : "@client.sonka.accounts.delete");
        }).right();
    }

    /** Компактный выбор: по кнопке на аккаунт + «Управление…». Выбор применяется со следующего подключения. */
    public static void showPicker(){
        BaseDialog d = new BaseDialog("@client.sonka.accounts.title");
        d.addCloseButton();
        d.cont.add("@client.sonka.accounts.picker.hint").width(420f).wrap().pad(6f).row();
        if(net.active()){
            d.cont.add("[gold]" + Core.bundle.get("client.sonka.accounts.connected")).width(420f).wrap().pad(4f).row();
        }
        d.cont.pane(list -> {
            list.top().defaults().growX().height(54f).pad(3f);
            for(Account a : Accounts.list()){
                boolean active = Accounts.isActive(a);
                list.button((active ? "[accent]" : "") + a.name, Styles.flatt, () -> {
                    if(!active){
                        Accounts.activate(a);
                        ui.showInfoFade(Core.bundle.format("client.sonka.accounts.switched", a.name));
                    }
                    d.hide();
                }).row();
            }
        }).width(420f).maxHeight(Core.graphics.getHeight() * 0.5f).row();
        d.cont.button("@client.sonka.accounts.picker.manage", Icon.settings, () -> {
            d.hide();
            new AccountsDialog().show();
        }).size(420f, 50f).pad(6f);
        d.show();
    }

    /**
     * Правка одного аккаунта: имя, uuid (вручную - например, перенос с другого устройства), случайный uuid, копирование
     * обоих видов идентификатора, забыть usid серверов. У системного аккаунта uuid только для чтения (он штатный).
     * Применяется кнопкой «Сохранить»; при смене uuid usid серверов стираются (они были выданы под прежний).
     */
    public static class AccountEditDialog extends BaseDialog{
        final Account acc;
        final Runnable after;
        String name, uuid;

        public AccountEditDialog(Account acc, Runnable after){
            super("@client.sonka.accounts.edit");
            this.acc = acc;
            this.after = after;
            this.name = acc.name;
            this.uuid = acc.effectiveUuid();
            addCloseButton();
            buttons.button("@client.sonka.accounts.save", Icon.ok, this::save);
            shown(this::setup);
            onResize(this::setup);
        }

        void setup(){
            cont.clear();
            cont.defaults().pad(4f);

            cont.add("@client.sonka.accounts.name").left().row();
            cont.field(name, text -> name = text).width(460f).maxTextLength(40).row();

            cont.add("@client.sonka.accounts.uuid").left().row();
            cont.table(t -> {
                TextField field = t.field(uuid, text -> uuid = text.trim()).width(300f).maxTextLength(12).get();
                field.setDisabled(acc.stock);
                t.button(Icon.refresh, Styles.clearNonei, () -> {
                    uuid = Accounts.generateUuid();
                    field.setText(uuid);
                }).size(46f).disabled(bt -> acc.stock).tooltip("@client.sonka.accounts.random");
                t.button(Icon.copy, Styles.clearNonei, () -> copy(uuid)).size(46f).tooltip("@client.sonka.accounts.copy.uuid");
            }).row();
            //живая проверка: красный - не 8 байт в base64, пакет с таким uuid клиент собрать не сможет
            cont.label(() -> Accounts.validUuid(uuid) ? "[lightgray]" + Core.bundle.get("client.sonka.accounts.valid")
                : "[scarlet]" + Core.bundle.get("client.sonka.accounts.invalid")).left().row();

            cont.add("@client.sonka.accounts.serverid").left().row();
            cont.table(t -> {
                //24 символа base64 («ID на сервере») не влезают в 300 px - кнопка копирования налезала на текст (стенд 2026-11-23)
                t.label(() -> "[lightgray]" + (Accounts.validUuid(uuid) ? Accounts.serverId(uuid) : "-")).width(400f).left();
                t.button(Icon.copy, Styles.clearNonei, () -> {
                    if(Accounts.validUuid(uuid)) copy(Accounts.serverId(uuid));
                }).size(46f).tooltip("@client.sonka.accounts.copy.serverid");
            }).row();

            if(acc.stock){
                cont.add("[lightgray]" + Core.bundle.get("client.sonka.accounts.stock.note")).width(460f).wrap().left().row();
            }else{
                cont.button(Core.bundle.format("client.sonka.accounts.forget", acc.usids.size), Icon.trash, () ->
                    ui.showConfirm("@confirm", "@client.sonka.accounts.forget.confirm", () -> {
                        Accounts.forgetUsids(acc);
                        setup();
                    })).size(460f, 48f).disabled(bt -> acc.usids.isEmpty()).row();
                cont.add("[lightgray]" + Core.bundle.get("client.sonka.accounts.forget.note")).width(460f).wrap().left().row();
            }
        }

        void copy(String text){
            Core.app.setClipboardText(text);
            ui.showInfoFade("@client.sonka.accounts.copied");
        }

        void save(){
            String n = name == null ? "" : name.trim();
            if(!acc.stock && !uuid.equals(acc.uuid)){
                String err = Accounts.setUuid(acc, uuid);
                if(err != null){
                    ui.showErrorMessage("@" + err);
                    return;
                }
            }
            Accounts.rename(acc, n);
            hide();
            after.run();
        }
    }
}
