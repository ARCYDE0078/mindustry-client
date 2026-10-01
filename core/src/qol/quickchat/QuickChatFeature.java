package qol.quickchat;

import arc.Core;
import arc.Events;
import arc.scene.ui.layout.Table;
import arc.util.Timer;
import arc.util.serialization.Jval;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.gen.Icon;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.BaseDialog;
import mindustry.ui.dialogs.SettingsMenuDialog.SettingsTable;
import qol.core.ChatSender;
import qol.core.Feature;
import qol.ui.DragIconButton;

import static arc.Core.graphics;
import static mindustry.Vars.state;
import static mindustry.Vars.ui;

/**
 * Draggable HUD button opening a list of user-defined quick chat messages, ported from QoL Control.
 * Each entry can send several messages at once (one per line) and any line over the game's 150-char
 * chat limit is split into multiple sends. Entry 0 is always the special "Auto-Execute" slot: if
 * enabled, its text fires {@link #AUTOEXEC_DELAY}s after joining a world - handy for auto-running a
 * join command sequence on a server. Crash protection: a "running" flag is persisted for the whole
 * fire-and-settle window and checked at the next {@link WorldLoadEvent}; if it's still set, the previous
 * run never finished (game crashed/closed mid-execution) and Auto-Execute is disabled with a warning
 * instead of firing again, which would otherwise re-run whatever caused the crash in a loop.
 */
public class QuickChatFeature implements Feature{
    static final String SETTINGS_KEY = "qol-quickchat";
    static final String RUNNING_KEY = "qol-quickchat-running";
    static final float AUTOEXEC_DELAY = 1f, AUTOEXEC_SETTLE = 3f;

    BaseDialog dialog;
    Timer.Task execTask, clearTask;

    @Override
    public String id(){
        return "quick-chat";
    }

    @Override
    public String titleKey(){
        return "qol.feature.quick-chat.title";
    }

    @Override
    public void init(){
        DragIconButton btn = new DragIconButton("qol-quickchat-btn", Icon.chat, 44f, 20f, graphics.getHeight() - 250f, () -> {
            showMainMenu();
            dialog.show();
        });
        btn.visible(() -> isEnabled() && state.isGame() && ui.hudfrag.shown);

        Events.on(WorldLoadEvent.class, e -> {
            Jval data = loadData();
            Jval defCmd = data.asArray().first();

            if(Core.settings.getBool(RUNNING_KEY, false)){
                Core.settings.put(RUNNING_KEY, false);
                if(execTask != null) execTask.cancel();
                if(clearTask != null) clearTask.cancel();

                if(defCmd.getBool("enabled", false)){
                    defCmd.put("enabled", false);
                    saveData(data);
                    Timer.schedule(() -> ui.showInfo(Core.bundle.get("qol.quick-chat.crash-disabled",
                        "Auto-Execute was disabled because the game crashed or closed during its last run.")), 2f);
                }
                return;
            }

            String text = defCmd.getString("text", "");
            if(defCmd.getBool("enabled", false) && !text.isEmpty()){
                Core.settings.put(RUNNING_KEY, true);
                execTask = Timer.schedule(() -> {
                    ChatSender.send(text);
                    clearTask = Timer.schedule(() -> Core.settings.put(RUNNING_KEY, false), AUTOEXEC_SETTLE);
                }, AUTOEXEC_DELAY);
            }
        });
    }

    @Override
    public void buildSettings(SettingsTable table){
    }

    static Jval loadData(){
        Jval data;
        try{
            data = Jval.read(Core.settings.getString(SETTINGS_KEY, "[]"));
            if(!data.isArray()) data = Jval.newArray();
        }catch(Exception e){
            data = Jval.newArray();
        }

        Jval.JsonArray arr = data.asArray();
        if(arr.isEmpty() || !arr.first().getBool("isDefault", false)){
            Jval def = Jval.newObject();
            def.put("name", "[accent]Auto-Execute");
            def.put("text", "");
            def.put("isDefault", true);
            def.put("enabled", false);
            arr.insert(0, def);
            saveData(data);
        }
        return data;
    }

    static void saveData(Jval data){
        qol.core.SafeSettings.putString(SETTINGS_KEY, data.toString());
    }

    void showMainMenu(){
        if(dialog == null){
            dialog = new BaseDialog(Core.bundle.get("qol.quick-chat.title", "Quick Chat"));
            dialog.addCloseButton();
        }
        dialog.cont.clear();

        Jval data = loadData();
        Jval.JsonArray entries = data.asArray();

        Table list = new Table();
        list.top().left();

        for(int i = 0; i < entries.size; i++){
            int index = i;
            Jval cmd = entries.get(i);
            boolean isDefault = cmd.getBool("isDefault", false);
            String name = cmd.getString("name", "");

            Table row = new Table();
            row.button(b -> {
                b.left();
                b.add(name).left().growX().wrap();
            }, Styles.cleart, () -> {
                dialog.hide();
                ChatSender.send(cmd.getString("text", ""));
            }).size(isDefault ? 260f : 300f, 60f).left().padRight(6f);

            if(isDefault){
                boolean enabled = cmd.getBool("enabled", false);
                row.button(enabled ? Icon.ok : Icon.cancel, Styles.cleari, () -> {
                    cmd.put("enabled", !enabled);
                    saveData(data);
                    showMainMenu();
                }).size(45f, 60f);
            }

            row.button(Icon.edit, Styles.cleari, () -> showEditDialog(index, cmd)).size(45f, 60f);

            if(!isDefault){
                row.button(Icon.trash, Styles.cleari, () -> ui.showConfirm(
                    Core.bundle.get("qol.quick-chat.dialog.delete-title", "Delete Message"),
                    Core.bundle.format("qol.quick-chat.dialog.delete-confirm", name),
                    () -> {
                        Jval d2 = loadData();
                        d2.asArray().remove(index);
                        saveData(d2);
                        showMainMenu();
                    })).size(45f, 60f);
            }

            list.add(row).padBottom(5f).row();
        }

        dialog.cont.pane(list).width(440f).height(360f).row();

        dialog.cont.button(Core.bundle.get("qol.quick-chat.add", "Add Message"), Icon.add, () -> {
            Jval newCmd = Jval.newObject();
            newCmd.put("name", "");
            newCmd.put("text", "");
            newCmd.put("isDefault", false);
            newCmd.put("enabled", true);
            showEditDialog(-1, newCmd);
        }).size(440f, 50f).padTop(10f);
    }

    void showEditDialog(int index, Jval cmd){
        boolean isNew = index == -1;
        boolean isDefault = cmd.getBool("isDefault", false);
        BaseDialog d = new BaseDialog(Core.bundle.get(isNew ? "qol.quick-chat.dialog.add" : "qol.quick-chat.dialog.edit", isNew ? "Add Message" : "Edit Message"));

        String[] name = {cmd.getString("name", "")};
        String[] text = {cmd.getString("text", "")};

        Table t = new Table();
        t.add(Core.bundle.get("qol.quick-chat.dialog.name", "Name:")).padRight(5f).right();
        if(isDefault){
            t.add(name[0]).left().padLeft(5f);
            t.row();
        }else{
            t.field(name[0], v -> name[0] = v).size(240f, 50f).left();
            t.row();
        }

        t.add(Core.bundle.get("qol.quick-chat.dialog.text", "Text:")).padRight(5f).right().padTop(5f).top();
        t.area(text[0], v -> text[0] = v).size(360f, 200f).padTop(5f);
        t.row();

        d.cont.add(t).row();

        d.buttons.button(Core.bundle.get("qol.quick-chat.dialog.cancel", "Cancel"), Icon.cancel, d::hide).size(150f, 50f);
        d.buttons.button(Core.bundle.get("qol.quick-chat.dialog.ok", "OK"), Icon.ok, () -> {
            if(!isDefault && name[0].isEmpty()){
                ui.showInfo(Core.bundle.get("qol.quick-chat.dialog.name-required", "Name cannot be empty."));
                return;
            }
            Jval data = loadData();
            Jval.JsonArray entries = data.asArray();
            if(isNew){
                Jval newCmd = Jval.newObject();
                newCmd.put("name", name[0]);
                newCmd.put("text", text[0]);
                newCmd.put("isDefault", false);
                newCmd.put("enabled", true);
                entries.add(newCmd);
            }else{
                Jval existing = entries.get(index);
                existing.put("text", text[0]);
                if(!isDefault) existing.put("name", name[0]);
            }
            saveData(data);
            d.hide();
            showMainMenu();
        }).size(150f, 50f);

        d.show();
    }
}
