package qol.cbinds;

import arc.Core;
import arc.Events;
import arc.func.Boolp;
import arc.scene.style.Drawable;
import arc.scene.style.TextureRegionDrawable;
import arc.scene.ui.Button;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import arc.util.serialization.Jval;
import mindustry.ctype.ContentType;
import mindustry.ctype.UnlockableContent;
import mindustry.game.EventType.Trigger;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.gen.Icon;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.BaseDialog;
import mindustry.ui.dialogs.SettingsMenuDialog.SettingsTable;
import qol.core.ButtonSetting;
import qol.core.ChatSender;
import qol.core.Feature;

import static mindustry.Vars.content;
import static mindustry.Vars.state;
import static mindustry.Vars.ui;

/**
 * User-defined draggable on-screen buttons that fire chat commands/messages, ported from QoL Control's
 * {@code !cbinds}. Each button's config (name, icon, size, commands, background, and its own dragged
 * position) is one object in the {@code qol-cbinds} JSON array; live {@link CustomBindButton} widgets
 * are fully torn down and rebuilt from that array on every edit rather than patched in place - simpler
 * to reason about than keeping N live widgets in sync with N config entries by hand, and cheap since
 * this only happens on explicit user edits, never every frame.
 */
public class CustomBindsFeature implements Feature{
    static final String SETTINGS_KEY = "qol-cbinds";
    static final String LOCKED_KEY = "qol-cbinds-locked";

    final Seq<CustomBindButton> active = new Seq<>();

    @Override
    public String id(){
        return "custom-binds";
    }

    @Override
    public String titleKey(){
        return "qol.feature.custom-binds.title";
    }

    @Override
    public void init(){
        rebuildHud();
        Events.on(WorldLoadEvent.class, e -> rebuildHud());
        Events.run(Trigger.update, () -> {
            boolean visible = isEnabled() && state.isGame() && ui.hudfrag.shown;
            for(CustomBindButton b : active) b.visible = visible;
        });
    }

    @Override
    public void buildSettings(SettingsTable table){
        table.checkPref(LOCKED_KEY, false, v -> rebuildHud());
        table.pref(new ButtonSetting("qol-custom-binds-configure", this::showConfigDialog));
    }

    static boolean locked(){
        return Core.settings.getBool(LOCKED_KEY, false);
    }

    static Jval loadButtons(){
        try{
            Jval data = Jval.read(Core.settings.getString(SETTINGS_KEY, "[]"));
            return data.isArray() ? data : Jval.newArray();
        }catch(Exception e){
            return Jval.newArray();
        }
    }

    static void saveButtons(Jval data){
        qol.core.SafeSettings.putString(SETTINGS_KEY, data.toString());
    }

    static Drawable resolveIcon(String iconName, String iconType){
        if(iconName == null || iconName.isEmpty()) return null;

        try{
            Object val = Icon.class.getField(iconName).get(null);
            if(val instanceof Drawable d) return d;
        }catch(Exception ignored){
        }

        ContentType[] types = "Block".equals(iconType) ? new ContentType[]{ContentType.block}
            : "Item".equals(iconType) ? new ContentType[]{ContentType.item}
            : "Liquid".equals(iconType) ? new ContentType[]{ContentType.liquid}
            : "Unit".equals(iconType) ? new ContentType[]{ContentType.unit}
            : new ContentType[]{ContentType.block, ContentType.item, ContentType.liquid, ContentType.unit};

        for(ContentType type : types){
            UnlockableContent c = content.getByName(type, iconName);
            if(c != null && c.uiIcon != null) return new TextureRegionDrawable(c.uiIcon);
        }
        return null;
    }

    void rebuildHud(){
        for(CustomBindButton b : active) b.remove();
        active.clear();

        Jval.JsonArray entries = loadButtons().asArray();
        Boolp lockedSupplier = CustomBindsFeature::locked;

        for(int i = 0; i < entries.size; i++){
            int index = i;
            Jval cfg = entries.get(i);

            String name = cfg.getString("name", "");
            String iconName = cfg.getString("iconName", "");
            String iconType = cfg.getString("iconType", "");
            float width = cfg.getFloat("width", 50f);
            float height = cfg.getFloat("height", 50f);
            float startX = cfg.getFloat("x", 100f);
            float startY = cfg.getFloat("y", 200f);
            boolean noBackground = cfg.getBool("noBackground", false);
            String commands = cfg.getString("commands", "");
            Drawable icon = resolveIcon(iconName, iconType);

            CustomBindButton btn = new CustomBindButton(b -> {
                b.clear();
                if(icon != null && !name.isEmpty()){
                    b.image(icon).size(24f).padRight(4f);
                    b.add(name);
                }else if(icon != null){
                    b.image(icon).grow();
                }else{
                    b.add(name.isEmpty() ? "?" : name).wrap();
                }
            }, noBackground ? Styles.cleart : Styles.defaultb, width, height, startX, startY,
                () -> ChatSender.send(commands), lockedSupplier, (nx, ny) -> {
                    Jval data = loadButtons();
                    Jval.JsonArray fresh = data.asArray();
                    if(index < fresh.size){
                        fresh.get(index).put("x", nx);
                        fresh.get(index).put("y", ny);
                        saveButtons(data);
                    }
                });

            active.add(btn);
        }
    }

    void showConfigDialog(){
        BaseDialog dialog = new BaseDialog(Core.bundle.get("qol.custom-binds.title", "Custom Screen Binds"));
        dialog.addCloseButton();
        rebuildConfigList(dialog);
        dialog.show();
    }

    void rebuildConfigList(BaseDialog dialog){
        dialog.cont.clear();

        Jval data = loadButtons();
        Jval.JsonArray entries = data.asArray();

        dialog.cont.check(Core.bundle.get("qol.custom-binds.lock", "Lock Positions (disable dragging)"), locked(), v -> {
            Core.settings.put(LOCKED_KEY, v);
            rebuildHud();
        }).left().padBottom(10f).row();

        Table list = new Table();
        list.top().left();

        for(int i = 0; i < entries.size; i++){
            int index = i;
            Jval cfg = entries.get(i);
            String name = cfg.getString("name", "");
            String iconName = cfg.getString("iconName", "");
            String iconType = cfg.getString("iconType", "");
            String label = !name.isEmpty() ? name : (!iconName.isEmpty() ? "[" + iconName + "]" : "?");
            Drawable icon = resolveIcon(iconName, iconType);
            String cmdPreview = cfg.getString("commands", "").replace("\n", " | ");

            Table row = new Table();
            row.button(b -> {
                b.left();
                if(icon != null) b.image(icon).size(24f).padRight(4f);
                b.add("[accent]" + label).width(140f).left().wrap();
                b.add("[white]" + cmdPreview).left().growX().minWidth(0f).wrap();
            }, Styles.cleart, () -> showEditDialog(dialog, index, cfg)).size(300f, 60f).left().padRight(6f);

            row.button(Icon.edit, Styles.cleari, () -> showEditDialog(dialog, index, cfg)).size(45f, 60f);
            row.button(Icon.trash, Styles.cleari, () -> ui.showConfirm(
                Core.bundle.get("qol.custom-binds.delete-title", "Delete Button"),
                Core.bundle.format("qol.custom-binds.delete-confirm", label),
                () -> {
                    Jval d2 = loadButtons();
                    d2.asArray().remove(index);
                    saveButtons(d2);
                    rebuildHud();
                    rebuildConfigList(dialog);
                })).size(45f, 60f);

            list.add(row).padBottom(5f).row();
        }

        dialog.cont.pane(list).width(440f).height(340f).row();

        dialog.cont.button(Core.bundle.get("qol.custom-binds.add", "Add Screen Button"), Icon.add, () -> {
            Jval fresh = Jval.newObject();
            fresh.put("name", "");
            fresh.put("iconName", "");
            fresh.put("iconType", "");
            fresh.put("width", 50);
            fresh.put("height", 50);
            fresh.put("commands", "");
            fresh.put("noBackground", false);
            fresh.put("x", (double)(arc.Core.graphics.getWidth() / 2));
            fresh.put("y", (double)(arc.Core.graphics.getHeight() / 2));
            showEditDialog(dialog, -1, fresh);
        }).size(440f, 50f).padTop(10f);
    }

    void showEditDialog(BaseDialog parent, int index, Jval cfg){
        boolean isNew = index == -1;
        BaseDialog d = new BaseDialog(Core.bundle.get(isNew ? "qol.custom-binds.dialog.add" : "qol.custom-binds.dialog.edit", isNew ? "Add Screen Button" : "Edit Screen Button"));

        String[] name = {cfg.getString("name", "")};
        String[] iconName = {cfg.getString("iconName", "")};
        String[] iconType = {cfg.getString("iconType", "")};
        int[] width = {cfg.getInt("width", 50)};
        int[] height = {cfg.getInt("height", 50)};
        String[] commands = {cfg.getString("commands", "")};
        boolean[] noBackground = {cfg.getBool("noBackground", false)};

        Table t = new Table();
        t.top().left();

        t.add(Core.bundle.get("qol.custom-binds.dialog.name", "Label:")).padRight(5f).right();
        t.field(name[0], v -> name[0] = v).size(220f, 45f).left().row();

        t.add(Core.bundle.get("qol.custom-binds.dialog.icon", "Icon name (block/item/liquid/unit/UI icon):")).padRight(5f).right().padTop(8f);
        Table iconRow = new Table();
        Table preview = new Table();
        Runnable refreshPreview = () -> {
            preview.clear();
            Drawable icon = resolveIcon(iconName[0], iconType[0]);
            if(icon != null) preview.image(icon).size(28f);
        };
        refreshPreview.run();
        iconRow.field(iconName[0], v -> {
            iconName[0] = v.trim();
            refreshPreview.run();
        }).size(180f, 45f);
        iconRow.add(preview).size(30f).padLeft(6f);
        t.add(iconRow).left().padTop(8f).row();

        t.add(Core.bundle.get("qol.custom-binds.dialog.icon-type", "Icon type hint (Block/Item/Liquid/Unit, optional):")).padRight(5f).right().padTop(8f);
        t.field(iconType[0], v -> {
            iconType[0] = v.trim();
            refreshPreview.run();
        }).size(220f, 45f).left().row();

        Table sizeRow = new Table();
        sizeRow.add(Core.bundle.get("qol.custom-binds.dialog.size", "Size (W x H):")).padRight(5f);
        sizeRow.field(String.valueOf(width[0]), v -> width[0] = parseIntOr(v, 50)).size(70f, 45f);
        sizeRow.add(" x ").padLeft(5f).padRight(5f);
        sizeRow.field(String.valueOf(height[0]), v -> height[0] = parseIntOr(v, 50)).size(70f, 45f);
        t.add(sizeRow).colspan(2).left().padTop(10f).row();

        t.check(Core.bundle.get("qol.custom-binds.dialog.no-background", "No background"), noBackground[0], v -> noBackground[0] = v)
            .colspan(2).left().padTop(10f).row();

        t.add(Core.bundle.get("qol.custom-binds.dialog.commands", "Commands/messages (one per line):")).colspan(2).left().padTop(12f).row();
        t.area(commands[0], v -> commands[0] = v).size(400f, 160f).colspan(2).padTop(5f).row();

        d.cont.pane(t).width(460f).height(420f).row();

        d.buttons.button(Core.bundle.get("qol.custom-binds.dialog.cancel", "Cancel"), Icon.cancel, d::hide).size(150f, 50f);
        d.buttons.button(Core.bundle.get("qol.custom-binds.dialog.ok", "OK"), Icon.ok, () -> {
            if(name[0].isEmpty() && iconName[0].isEmpty()){
                ui.showInfo(Core.bundle.get("qol.custom-binds.dialog.needs-content", "Button must have a label or an icon."));
                return;
            }

            Jval data = loadButtons();
            Jval.JsonArray entries = data.asArray();
            Jval target = isNew ? Jval.newObject() : entries.get(index);

            target.put("name", name[0]);
            target.put("iconName", iconName[0]);
            target.put("iconType", iconType[0]);
            target.put("width", width[0]);
            target.put("height", height[0]);
            target.put("commands", commands[0]);
            target.put("noBackground", noBackground[0]);
            if(isNew){
                target.put("x", cfg.getDouble("x", 100));
                target.put("y", cfg.getDouble("y", 200));
                entries.add(target);
            }

            saveButtons(data);
            rebuildHud();
            d.hide();
            rebuildConfigList(parent);
        }).size(150f, 50f);

        d.show();
    }

    static int parseIntOr(String s, int def){
        try{
            return Integer.parseInt(s.trim());
        }catch(Exception e){
            return def;
        }
    }
}
