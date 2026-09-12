package eui.interact;

import arc.Core;
import arc.Events;
import arc.graphics.Color;
import arc.scene.ui.CheckBox;
import arc.scene.ui.TextField;
import arc.scene.ui.layout.Table;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Log;
import mindustry.game.EventType.ClientLoadEvent;
import mindustry.graphics.Pal;
import mindustry.type.Category;
import mindustry.ui.dialogs.BaseDialog;
import mindustry.world.Block;
import mindustry.world.blocks.storage.CoreBlock;
import mindustry.world.blocks.storage.StorageBlock;

import static mindustry.Vars.content;

/**
 * Picks which {@link StorageBlock} types {@code mindustry.client.utils.AutoTransfer} is allowed to draw
 * from when it needs a fill source other than the core (sonka's request, 2026-09-12: "выбор блоков из
 * которых можно забирать ресурсы для заполнения"). Cores are excluded from this list entirely - they're
 * governed by the separate "Auto Transfer: From Cores" checkbox, not this one, same as they already were
 * before this dialog existed. Config lives in one flat settings key ("eui.autofill.sourceblocks"), an
 * {@code ObjectMap<blockName, Boolean>} where a missing entry defaults to {@code true} (eligible) so an
 * unconfigured install behaves exactly as before - only unchecking a box excludes that block type. Read
 * directly by {@code AutoTransfer.sourceAllowed}, same wiring as {@link AutofillPriorityDialog}'s config.
 */
public class SourceBlocksDialog{
    private static final String SETTINGS_KEY = "eui.autofill.sourceblocks";

    /** Set by the constructor (there's exactly one, made by {@link eui.EUIMod}); mirrors {@link AutofillPriorityDialog#instance}. */
    public static SourceBlocksDialog instance;

    private BaseDialog dialog;
    private Table list;
    private TextField searchField;
    private final Seq<Block> blocks = new Seq<>();

    public SourceBlocksDialog(){
        instance = this;
        Events.on(ClientLoadEvent.class, e -> {
            try{
                buildDialog();
            }catch(Throwable t){
                Log.err("[eui] source-blocks buildDialog error", t);
            }
        });
    }

    public void show(){
        if(dialog == null) return;
        if(searchField != null) searchField.setText("");
        refreshList("");
        dialog.show();
    }

    static ObjectMap<String, Boolean> loadConfig(){
        return Core.settings.getJson(SETTINGS_KEY, ObjectMap.class, ObjectMap::new);
    }

    static boolean matchesSearch(Block block, String filterLower){
        if(filterLower.isEmpty()) return true;
        return block.localizedName.toLowerCase().contains(filterLower) || block.name.toLowerCase().contains(filterLower);
    }

    Table buildRow(Block block, ObjectMap<String, Boolean> config){
        Table rowTable = new Table();
        rowTable.image(block.uiIcon).size(32).padRight(8);
        rowTable.add(block.localizedName).left().growX().wrap();

        CheckBox box = new CheckBox("");
        box.setChecked(config.get(block.name, true));
        box.changed(() -> {
            ObjectMap<String, Boolean> c = loadConfig();
            c.put(block.name, box.isChecked());
            Core.settings.putJson(SETTINGS_KEY, c);
        });
        rowTable.add(box).padLeft(8);

        return rowTable;
    }

    void refreshList(String filterText){
        if(list == null) return;
        list.clearChildren();

        try{
            refreshListInner(filterText);
        }catch(Throwable t){
            Log.err("[eui] source-blocks refreshList error", t);
            list.add("refreshList error: " + t).color(Color.scarlet).wrap().width(400).row();
        }
    }

    void refreshListInner(String filterText){
        //re-read on every refresh, same reasoning as AutofillPriorityDialog
        ObjectMap<String, Boolean> config = loadConfig();
        String filterLower = filterText == null ? "" : filterText.toLowerCase().trim();

        ObjectMap<Category, Seq<Block>> groups = new ObjectMap<>();
        for(Block block : blocks){
            if(!matchesSearch(block, filterLower)) continue;
            groups.get(block.category, Seq::new).add(block);
        }

        int shown = 0;
        for(Category category : Category.all){
            Seq<Block> bucket = groups.get(category);
            if(bucket == null || bucket.isEmpty()) continue;

            list.add(Core.bundle.get("eui.category." + category.name()))
                .color(Pal.accent).left().padTop(shown == 0 ? 0 : 12).padBottom(4).row();

            for(Block block : bucket){
                list.add(buildRow(block, config)).growX().row();
                shown++;
            }
        }

        if(shown == 0){
            list.add(Core.bundle.get("eui.source-blocks.no-results")).pad(12).row();
        }
    }

    void buildDialog(){
        dialog = new BaseDialog(Core.bundle.get("eui.source-blocks.title"));
        dialog.addCloseButton();

        blocks.clear();
        content.blocks().each(block -> {
            //cores are handled by the separate "From Cores" checkbox, not this list
            if(block instanceof StorageBlock && !(block instanceof CoreBlock) && block.uiIcon != null && block.uiIcon.found()){
                blocks.add(block);
            }
        });
        blocks.sort((a, b) -> a.localizedName.compareTo(b.localizedName));

        dialog.cont.add(Core.bundle.get("eui.source-blocks.hint")).width(420).wrap().pad(6).row();

        Table searchRow = new Table();
        searchRow.add(Core.bundle.get("eui.source-blocks.search") + ":").padRight(6);
        searchField = searchRow.field("", this::refreshList).growX().get();
        dialog.cont.add(searchRow).growX().pad(4).row();

        list = new Table();
        dialog.cont.pane(list).width(420).height(420).row();
    }
}
