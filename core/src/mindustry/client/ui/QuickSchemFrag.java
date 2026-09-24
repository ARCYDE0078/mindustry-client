package mindustry.client.ui;

import arc.*;
import arc.graphics.*;
import arc.input.*;
import arc.math.*;
import arc.scene.*;
import arc.scene.event.*;
import arc.scene.style.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import arc.util.serialization.*;
import mindustry.Vars;
import mindustry.ctype.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.ui.*;
import mindustry.ui.dialogs.*;

import static mindustry.Vars.ui;

/**
 * Панель быстрых схем: вкладки с сеткой кнопок, каждая кнопка вставляет схему в руку (как выбор в таблице схем).
 * ПКМ по кнопке - выбор схемы/иконки, ПКМ по вкладке - её настройки, ПКМ по значку перемещения - общие настройки.
 * Идея и раскладка взяты из QuickSchemFrag клиента fk4b; показ управляется настройкой "quickschems".
 */
public class QuickSchemFrag extends Table{
    private final Table container = new Table();
    private Seq<QuickTab> tabs = new Seq<>();
    private int currentTab = 0;
    private Table tabTable;
    private final Json json = new Json();

    private float lastX = 0, lastY = 0;
    private boolean centered = false;

    public static class QuickSlot{
        public String schemName = "";
        public String iconName = "none";
        public boolean isContent = false;

        public QuickSlot(){}
    }

    public static class QuickTab{
        public String name = "Tab";
        public String iconName = "infoSmall";
        public String defaultSlotIcon = "none";
        public boolean defaultSlotIsContent = false;
        public boolean useIcon = false;
        public boolean isContent = false;
        public Seq<QuickSlot> slots = new Seq<>();

        public QuickTab(){}
        public QuickTab(String name){ this.name = name; }

        /** Старый/битый конфиг может оставить null в полях - лечим при загрузке. */
        public void validate(){
            if(name == null) name = "Tab";
            if(iconName == null) iconName = "infoSmall";
            if(defaultSlotIcon == null) defaultSlotIcon = "none";

            if(slots == null){
                slots = new Seq<>();
            }else{
                slots.remove(s -> s == null);
                for(QuickSlot slot : slots){
                    if(slot.iconName == null) slot.iconName = "none";
                    if(slot.schemName == null) slot.schemName = "";
                }
            }
        }
    }

    private static float btnSize(){
        return Core.settings.getFloat("qs-btn-size", 48f);
    }

    public void build(Group parent){
        parent.addChild(this);
        loadData();

        visible(() -> ui.hudfrag.shown && Core.settings.getBool("quickschems", false));

        background(Styles.black6);
        table(main -> {
            main.table(t -> tabTable = t).growX().left().row();
            main.image().growX().height(2f).color(Pal.coalBlack).row();
            main.add(container).top().left();
        });

        update(() -> {
            if(!centered && Core.graphics.getWidth() > 0){
                float sx = Core.settings.getFloat("schemfrag-x", Core.graphics.getWidth() / 2f - width / 2f);
                float sy = Core.settings.getFloat("schemfrag-y", Core.graphics.getHeight() / 2f - height / 2f);
                setPosition(Mathf.clamp(sx, 0, Math.max(0, Core.graphics.getWidth() - width)),
                    Mathf.clamp(sy, 0, Math.max(0, Core.graphics.getHeight() - height)));
                centered = true;
            }
        });
        rebuild();
    }

    private void rebuildTabs(){
        if(tabTable == null) return;

        float size = btnSize();
        tabTable.clear();
        tabTable.left().top().defaults().pad(2).size(size);

        int tabsPerRow = Math.max(2, Core.settings.getInt("qs-tbs-cols", 6));
        int currentInRow = 0;
        boolean dragAdded = false;

        for(int i = 0; i < tabs.size; i++){
            int index = i;
            QuickTab tab = tabs.get(i);

            Button btn = tabTable.button(b -> {
                if(tab.useIcon) b.image(getIconDrawable(tab.iconName, tab.isContent)).size(size * 0.7f);
                else b.add(tab.name).fontScale(0.5f).ellipsis(true);
            }, Styles.flatBordert, () -> {
                currentTab = index;
                rebuild();
            }).checked(currentTab == index).get();

            btn.addListener(new InputListener(){
                @Override public boolean touchDown(InputEvent e, float x, float y, int p, KeyCode b){
                    if(b == KeyCode.mouseRight){
                        showTabSettings(tab, index);
                        return true;
                    }
                    return false;
                }
            });

            currentInRow++;

            //кнопка перемещения занимает последнюю ячейку первого ряда
            if(!dragAdded && currentInRow == tabsPerRow - 1){
                addDragButton(tabTable);
                tabTable.row();
                currentInRow = 0;
                dragAdded = true;
            }else if(dragAdded && currentInRow == tabsPerRow){
                tabTable.row();
                currentInRow = 0;
            }
        }

        if(!dragAdded){
            while(currentInRow < tabsPerRow - 1){
                tabTable.add().size(size);
                currentInRow++;
            }
            addDragButton(tabTable);
        }
    }

    private void addDragButton(Table t){
        ImageButton drag = t.button(Icon.move, Styles.cleari, () -> {}).size(btnSize()).get();
        drag.addListener(new InputListener(){
            boolean isRightClick = false;

            @Override public boolean touchDown(InputEvent e, float x, float y, int p, KeyCode b){
                if(b == KeyCode.mouseRight){
                    isRightClick = true;
                    showSettings();
                    return true;
                }
                isRightClick = false;
                lastX = e.stageX;
                lastY = e.stageY;
                return true;
            }

            @Override public void touchDragged(InputEvent e, float x, float y, int p){
                if(!isRightClick){
                    moveBy(e.stageX - lastX, e.stageY - lastY);
                    lastX = e.stageX;
                    lastY = e.stageY;
                }
            }

            @Override public void touchUp(InputEvent e, float x, float y, int p, KeyCode b){
                if(!isRightClick){
                    Core.settings.put("schemfrag-x", QuickSchemFrag.this.x);
                    Core.settings.put("schemfrag-y", QuickSchemFrag.this.y);
                }
            }
        });
    }

    public void rebuild(){
        if(tabs.isEmpty()) return;

        currentTab = Math.max(0, Math.min(currentTab, tabs.size - 1));
        float size = btnSize();

        container.clear();
        container.top().left();

        QuickTab tab = tabs.get(currentTab);
        syncSlots(tab);
        int cols = Core.settings.getInt("qs-cols", 5);

        int count = 0;
        for(QuickSlot slot : tab.slots){
            String icon = slot.iconName;
            boolean isContent = slot.isContent;

            //иконка слота -> иконка по умолчанию вкладки -> глобальная
            if("none".equals(icon)){
                icon = tab.defaultSlotIcon;
                isContent = tab.defaultSlotIsContent;

                if("none".equals(icon)){
                    icon = Core.settings.getString("qs-default-icon", "infoSmall");
                    isContent = Core.settings.getBool("qs-default-iscontent", false);
                }
            }

            final String finalIcon = icon;
            final boolean finalIsContent = isContent;

            Button btn = container.button(b -> {
                Image img = b.image(getIconDrawable(finalIcon, finalIsContent)).size(size * 0.6f).get();
                if("none".equals(finalIcon)) img.color.a = 0f;
            }, Styles.flatBordert, () -> useSchematic(slot.schemName)).size(size).get();

            btn.color.a = slot.schemName.isEmpty() ? 0.3f : 1f;

            btn.addListener(new InputListener(){
                @Override public boolean touchDown(InputEvent e, float x, float y, int p, KeyCode b){
                    if(b == KeyCode.mouseRight){
                        showEditDialog(slot);
                        return true;
                    }
                    return false;
                }
            });

            if(!slot.schemName.isEmpty()){
                Schematic schem = findSchematic(slot.schemName);
                if(schem != null){
                    btn.addListener(new Tooltip(t -> {
                        t.background(Styles.black8);
                        t.margin(10f);

                        t.add(schem.width + "x" + schem.height + ", " + schem.tiles.size + " " + Core.bundle.get("quickschems.blocks"))
                            .style(Styles.outlineLabel).padBottom(4f).row();

                        t.add(new SchematicsDialog.SchematicImage(schem)).size(Math.min(schem.width * 16, 250f), Math.min(schem.height * 16, 250f)).pad(4f).row();

                        t.table(stats -> {
                            stats.left().defaults().left();

                            stats.table(items -> {
                                int idx = 0;
                                for(var stack : schem.requirements()){
                                    items.image(stack.item.uiIcon).size(16f).padRight(4f);
                                    items.add(String.valueOf(stack.amount)).color(Color.lightGray).padRight(10f);
                                    if(++idx % 4 == 0) items.row();
                                }
                            }).row();

                            float power = (schem.powerProduction() - schem.powerConsumption()) * 60f;
                            if(Math.abs(power) > 0.01f){
                                stats.table(p -> {
                                    p.image(Icon.power).color(power > 0 ? Pal.accent : Pal.remove).size(16f).padRight(4f);
                                    p.add((power > 0 ? "+" : "") + Strings.fixed(power, 2)).color(power > 0 ? Pal.accent : Pal.remove);
                                }).padTop(4f);
                            }
                        });
                    }));
                }
            }

            if(++count % cols == 0) container.row();
        }

        rebuildTabs();
        invalidateHierarchy();
        pack();
    }

    private void showTabSettings(QuickTab tab, int tabIndex){
        BaseDialog dialog = new BaseDialog("@quickschems.tabsettings");
        dialog.cont.table(t -> {
            t.table(tn -> {
                tn.add("@quickschems.tabname").left().padRight(6f);
                tn.field(tab.name, val -> {
                    tab.name = val;
                    saveData();
                    rebuild();
                }).growX();
            }).growX().row();

            t.check("@quickschems.useicon", tab.useIcon, val -> {
                tab.useIcon = val;
                saveData();
                rebuild();
            }).left().row();

            t.button("@quickschems.picktabicon", () -> showIconPicker(null, tab, false, dialog)).size(240, 45).row();

            t.table(di -> {
                di.add("@quickschems.defaultslot").left().padTop(10).padRight(6f);
                di.button(getIconDrawable(tab.defaultSlotIcon, tab.defaultSlotIsContent), () -> showIconPicker(null, tab, true, dialog)).size(45);
            }).row();

            t.button("@quickschems.deletetab", Icon.trash, () -> {
                if(tabs.size > 1){
                    tabs.remove(tabIndex);
                    currentTab = Math.min(currentTab, tabs.size - 1);
                    saveData();
                    rebuild();
                    dialog.hide();
                }
            }).width(280f).height(50).color(Pal.remove).padTop(10f).row();
        });
        dialog.addCloseButton();
        dialog.hidden(() -> {
            saveData();
            rebuild();
        });
        dialog.show();
    }

    private void showSettings(){
        BaseDialog dialog = new BaseDialog("@quickschems.globalsettings");
        setupSettingsContent(dialog);
        dialog.addCloseButton();
        dialog.show();
    }

    private void setupSettingsContent(BaseDialog dialog){
        dialog.cont.clear();

        dialog.cont.pane(p -> {
            p.defaults().left().growX();

            p.table(t -> {
                t.label(() -> Core.bundle.get("quickschems.columns") + ": " + Core.settings.getInt("qs-cols", 5)).left().row();
                t.slider(1, 15, 1, Core.settings.getInt("qs-cols", 5), val -> {
                    Core.settings.put("qs-cols", (int)val);
                    rebuild();
                }).left().growX();
            }).row();

            p.table(t -> {
                t.label(() -> Core.bundle.get("quickschems.rows") + ": " + Core.settings.getInt("qs-rows", 4)).left().row();
                t.slider(1, 15, 1, Core.settings.getInt("qs-rows", 4), val -> {
                    Core.settings.put("qs-rows", (int)val);
                    rebuild();
                }).left().growX();
            }).row();

            p.table(t -> {
                t.label(() -> Core.bundle.get("quickschems.tabsrow") + ": " + Core.settings.getInt("qs-tbs-cols", 6)).left().row();
                t.slider(2, 15, 1, Core.settings.getInt("qs-tbs-cols", 6), val -> {
                    Core.settings.put("qs-tbs-cols", (int)val);
                    rebuild();
                }).left().growX();
            }).row();

            p.table(t -> {
                t.label(() -> Core.bundle.get("quickschems.btnsize") + ": " + (int)Core.settings.getFloat("qs-btn-size", 48f)).left().row();
                t.slider(16, 128, 4, Core.settings.getFloat("qs-btn-size", 48f), val -> {
                    Core.settings.put("qs-btn-size", val);
                    rebuild();
                }).left().growX();
            }).row();

            p.table(t -> {
                t.add("@quickschems.defaulticon").left().padRight(6f);
                String defName = Core.settings.getString("qs-default-icon", "infoSmall");
                boolean defIsCont = Core.settings.getBool("qs-default-iscontent", false);
                t.button(getIconDrawable(defName, defIsCont), () -> showIconPicker(null, null, false, dialog)).size(45);
            }).left().row();

            p.image().height(2).color(Pal.accent).row();

            p.label(() -> Core.bundle.get("quickschems.managetabs")).color(Pal.accent).padBottom(10).row();
            p.table(tabsTable -> {
                tabsTable.defaults().pad(2);

                for(int i = 0; i < tabs.size; i++){
                    int index = i;
                    QuickTab tab = tabs.get(i);

                    tabsTable.table(Styles.black3, row -> {
                        row.button(Icon.upOpen, Styles.cleari, () -> {
                            if(index > 0){
                                tabs.swap(index, index - 1);
                                if(currentTab == index) currentTab--;
                                else if(currentTab == index - 1) currentTab++;
                                saveData();
                                rebuild();
                                setupSettingsContent(dialog);
                            }
                        }).size(35).disabled(index == 0);

                        row.button(Icon.downOpen, Styles.cleari, () -> {
                            if(index < tabs.size - 1){
                                tabs.swap(index, index + 1);
                                if(currentTab == index) currentTab++;
                                else if(currentTab == index + 1) currentTab--;
                                saveData();
                                rebuild();
                                setupSettingsContent(dialog);
                            }
                        }).size(35).disabled(index == tabs.size - 1);

                        row.image(getIconDrawable(tab.iconName, tab.isContent)).size(24).padRight(10);
                        row.add(tab.name).growX().ellipsis(true);
                    }).growX().row();
                }
            }).growX().row();

            p.button("@quickschems.addtab", Icon.add, () -> {
                QuickTab nt = new QuickTab("New");
                nt.iconName = Core.settings.getString("qs-default-icon", "infoSmall");
                nt.isContent = Core.settings.getBool("qs-default-iscontent", false);
                tabs.add(nt);
                saveData();
                rebuild();
                setupSettingsContent(dialog);
            }).height(50).row();
        }).grow();
    }

    private TextureRegionDrawable getIconDrawable(String name, boolean isContent){
        if(name == null || name.equals("none")) return (TextureRegionDrawable)Icon.none;

        if(!isContent) return Icon.icons.get(name, (TextureRegionDrawable)Icon.none);

        for(ContentType type : ContentType.all){
            var content = Vars.content.getByName(type, name);
            if(content instanceof UnlockableContent uc) return new TextureRegionDrawable(uc.uiIcon);
        }

        return (TextureRegionDrawable)Icon.none;
    }

    /** Один пикер на все случаи: иконка слота, иконка вкладки, иконка слотов вкладки по умолчанию, глобальная иконка по умолчанию. */
    private void showIconPicker(QuickSlot slot, QuickTab tab, boolean editDefault, BaseDialog parent){
        BaseDialog picker = new BaseDialog("@quickschems.selecticon");

        Table listTable = new Table();

        picker.cont.table(t -> {
            t.add("@quickschems.search").padRight(8f);
            t.field("", text -> rebuildIconList(listTable, text.toLowerCase(), slot, tab, editDefault, picker, parent)).growX();
        }).growX().pad(10).row();

        picker.cont.pane(listTable).grow().scrollX(false).scrollY(true);

        rebuildIconList(listTable, "", slot, tab, editDefault, picker, parent);

        picker.addCloseButton();
        picker.show();
    }

    private void applyIcon(QuickSlot slot, QuickTab tab, boolean editDefault, String name, boolean isContent){
        if(slot != null){
            slot.iconName = name;
            slot.isContent = isContent;
        }else if(tab != null){
            if(editDefault){
                tab.defaultSlotIcon = name;
                tab.defaultSlotIsContent = isContent;
            }else{
                tab.iconName = name;
                tab.isContent = isContent;
            }
        }else{
            Core.settings.put("qs-default-icon", name);
            Core.settings.put("qs-default-iscontent", isContent);
        }
    }

    private void rebuildIconList(Table t, String query, QuickSlot slot, QuickTab tab, boolean editDefault, BaseDialog picker, BaseDialog parent){
        t.clear();
        t.top().left();
        t.defaults().size(48f).pad(2f);
        int count = 0;
        int columns = Math.max(1, (int)((Core.graphics.getWidth() * 0.8f) / 54f) - 1);

        Runnable done = () -> {
            saveData();
            picker.hide();
            rebuild();
            //меняли глобальную иконку - обновляем окно настроек, из которого открыли пикер
            if(slot == null && tab == null && parent != null) setupSettingsContent(parent);
        };

        if(query.isEmpty() || "none".contains(query)){
            t.button(Icon.none, () -> {
                applyIcon(slot, tab, editDefault, "none", false);
                done.run();
            }).tooltip("@quickschems.noicon");
            if(++count % columns == 0) t.row();
        }

        for(java.lang.reflect.Field field : Icon.class.getFields()){
            if(field.getType() != TextureRegionDrawable.class) continue;
            String name = field.getName();
            if(!query.isEmpty() && !name.toLowerCase().contains(query)) continue;

            t.button(getIconDrawable(name, false), () -> {
                applyIcon(slot, tab, editDefault, name, false);
                done.run();
            });
            if(++count % columns == 0) t.row();
        }

        if(count > 0){
            t.row();
            t.image().height(4).color(Pal.accent).fillX().colspan(columns).pad(10).row();
            count = 0;
        }

        for(ContentType type : new ContentType[]{ContentType.block, ContentType.unit, ContentType.item, ContentType.liquid, ContentType.status, ContentType.planet, ContentType.weather}){
            for(var content : Vars.content.getBy(type)){
                if(!(content instanceof UnlockableContent uc)) continue;

                if(!query.isEmpty() && !uc.name.toLowerCase().contains(query) && !uc.localizedName.toLowerCase().contains(query)) continue;

                t.button(new TextureRegionDrawable(uc.uiIcon), () -> {
                    applyIcon(slot, tab, editDefault, uc.name, true);
                    done.run();
                });
                if(++count % columns == 0) t.row();
            }
        }
    }

    private void showEditDialog(QuickSlot slot){
        BaseDialog dialog = new BaseDialog("@quickschems.editslot");
        dialog.cont.add("@quickschems.schemname").left().row();
        dialog.cont.field(slot.schemName, val -> {
            slot.schemName = val;
            saveData();
        }).growX().width(300f).row();
        dialog.cont.button("@quickschems.pickicon", () -> showIconPicker(slot, null, false, dialog)).size(200, 50);
        dialog.addCloseButton();
        dialog.hidden(this::rebuild);
        dialog.show();
    }

    private Schematic findSchematic(String name){
        return Vars.schematics.all().find(s -> s.name().equals(name));
    }

    private void useSchematic(String name){
        if(name == null || name.isEmpty()) return;
        var schem = findSchematic(name);
        if(schem != null){
            Vars.control.input.useSchematic(schem);
        }else{
            Vars.ui.showInfoFade(Core.bundle.format("quickschems.notfound", name));
        }
    }

    private void loadData(){
        var file = Vars.dataDirectory.child("quickschems.json");
        try{
            if(file.exists()) tabs = json.fromJson(Seq.class, QuickTab.class, file.readString());
        }catch(Exception e){
            Log.err("Failed to load quickschems.json", e);
        }

        if(tabs == null || tabs.isEmpty()){
            tabs = new Seq<>();
            tabs.add(new QuickTab("General"));
        }
        tabs.remove(tab -> tab == null);
        for(QuickTab tab : tabs) tab.validate();
    }

    private void saveData(){
        try{
            Vars.dataDirectory.child("quickschems.json").writeString(json.prettyPrint(tabs));
        }catch(Exception e){
            Log.err("Failed to save quickschems.json", e);
        }
    }

    private void syncSlots(QuickTab tab){
        int total = Core.settings.getInt("qs-cols", 5) * Core.settings.getInt("qs-rows", 4);

        //при уменьшении сетки лишние слоты обрезаются (схемы в них теряются, как и в оригинале)
        if(tab.slots.size > total) tab.slots.truncate(total);
        while(tab.slots.size < total) tab.slots.add(new QuickSlot());
    }
}
