package eui.interact;

import arc.Core;
import arc.Events;
import arc.graphics.Color;
import arc.scene.ui.Label;
import arc.scene.ui.TextField.*;
import arc.scene.ui.layout.Table;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Align;
import arc.util.Log;
import arc.util.Strings;
import mindustry.game.EventType.ClientLoadEvent;
import mindustry.graphics.Pal;
import mindustry.type.Item;
import mindustry.ui.dialogs.BaseDialog;
import mindustry.world.blocks.defense.turrets.ItemTurret;

import static mindustry.Vars.content;

/**
 * Per-turret ammo priority + min-core editor (sonka's request, 2026-09-12), one level deeper than
 * {@link AutofillPriorityDialog}: for each turret that accepts more than one ammo item, ranks which
 * ammo {@code mindustry.client.utils.AutoTransfer} should fetch first (-2 excludes that ammo for that
 * turret entirely, default 0 - ties still fall back to DPS, see AutoTransfer.pickFetchItem's
 * ConsumeItemFilter branch) and sets a per-ammo minimum core stockpile required before that turret is
 * allowed to draw on it (blank field = no override, fall back to the global "Auto Transfer: Min Core
 * Items" slider). Config lives in two flat settings keys ("eui.autofill.ammo.priority" /
 * ".mincore"), each an {@code ObjectMap<"turretName|itemName", Integer>> - AutoTransfer reads them
 * directly and doesn't reference this dialog, matching how AutofillPriorityDialog's block-priority
 * config is consumed.
 */
public class AmmoPriorityDialog{
    private static final int MIN_PRIORITY = -2;
    private static final int MAX_PRIORITY = 5;
    private static final String PRIORITY_KEY = "eui.autofill.ammo.priority";
    private static final String MINCORE_KEY = "eui.autofill.ammo.mincore";

    /** Set by the constructor (there's exactly one, made by {@link eui.EUIMod}); mirrors {@link AutofillPriorityDialog#instance}. */
    public static AmmoPriorityDialog instance;

    private BaseDialog dialog;
    private Table list;
    private arc.scene.ui.TextField searchField;
    private final Seq<ItemTurret> turrets = new Seq<>();

    public AmmoPriorityDialog(){
        instance = this;
        Events.on(ClientLoadEvent.class, e -> {
            try{
                buildDialog();
            }catch(Throwable t){
                Log.err("[eui] ammo-priority buildDialog error", t);
            }
        });
    }

    public void show(){
        if(dialog == null) return;
        if(searchField != null) searchField.setText("");
        refreshList("");
        dialog.show();
    }

    static String key(String turretName, String itemName){
        return turretName + "|" + itemName;
    }

    static ObjectMap<String, Integer> loadPriorities(){
        return Core.settings.getJson(PRIORITY_KEY, ObjectMap.class, ObjectMap::new);
    }

    static ObjectMap<String, Integer> loadMinCores(){
        return Core.settings.getJson(MINCORE_KEY, ObjectMap.class, ObjectMap::new);
    }

    static boolean matchesSearch(ItemTurret turret, String filterLower){
        if(filterLower.isEmpty()) return true;
        return turret.localizedName.toLowerCase().contains(filterLower) || turret.name.toLowerCase().contains(filterLower);
    }

    Table buildAmmoRow(ItemTurret turret, Item item, ObjectMap<String, Integer> priorities, ObjectMap<String, Integer> minCores){
        String k = key(turret.name, item.name);
        Table rowTable = new Table();
        rowTable.image(item.uiIcon).size(24).padRight(6);
        rowTable.add(item.localizedName).left().width(140).wrap();

        Label[] priorityLabel = new Label[1];
        rowTable.button("-", () -> {
            ObjectMap<String, Integer> p = loadPriorities();
            int next = arc.math.Mathf.clamp(p.get(k, 0) - 1, MIN_PRIORITY, MAX_PRIORITY);
            p.put(k, next);
            Core.settings.putJson(PRIORITY_KEY, p);
            priorityLabel[0].setText(String.valueOf(next));
        }).size(32).padLeft(8);

        priorityLabel[0] = rowTable.add(String.valueOf(priorities.get(k, 0))).width(26).get();
        priorityLabel[0].setAlignment(Align.center);

        rowTable.button("+", () -> {
            ObjectMap<String, Integer> p = loadPriorities();
            int next = arc.math.Mathf.clamp(p.get(k, 0) + 1, MIN_PRIORITY, MAX_PRIORITY);
            p.put(k, next);
            Core.settings.putJson(PRIORITY_KEY, p);
            priorityLabel[0].setText(String.valueOf(next));
        }).size(32).padRight(12);

        rowTable.add(Core.bundle.get("eui.ammo-priority.mincore") + ":").padRight(4);
        Integer currentMin = minCores.get(k);
        rowTable.field(currentMin == null ? "" : String.valueOf(currentMin), TextFieldFilter.digitsOnly, text -> {
            ObjectMap<String, Integer> m = loadMinCores();
            if(text == null || text.isEmpty()){
                m.remove(k);
                Core.settings.putJson(MINCORE_KEY, m);
            }else if(Strings.canParsePositiveInt(text)){
                m.put(k, Strings.parseInt(text));
                Core.settings.putJson(MINCORE_KEY, m);
            }
        }).width(70);

        return rowTable;
    }

    void refreshList(String filterText){
        if(list == null) return;
        list.clearChildren();

        try{
            refreshListInner(filterText);
        }catch(Throwable t){
            Log.err("[eui] ammo-priority refreshList error", t);
            list.add("refreshList error: " + t).color(Color.scarlet).wrap().width(460).row();
        }
    }

    void refreshListInner(String filterText){
        //re-read on every refresh (not cached), same reasoning as AutofillPriorityDialog
        ObjectMap<String, Integer> priorities = loadPriorities();
        ObjectMap<String, Integer> minCores = loadMinCores();
        String filterLower = filterText == null ? "" : filterText.toLowerCase().trim();

        int shown = 0;
        for(ItemTurret turret : turrets){
            if(!matchesSearch(turret, filterLower)) continue;

            list.add(turret.localizedName).color(Pal.accent).left().padTop(shown == 0 ? 0 : 12).padBottom(4).row();
            for(Item item : turret.ammoTypes.keys()){
                list.add(buildAmmoRow(turret, item, priorities, minCores)).growX().padLeft(24).row();
            }
            shown++;
        }

        if(shown == 0){
            list.add(Core.bundle.get("eui.ammo-priority.no-results")).pad(12).row();
        }
    }

    void buildDialog(){
        dialog = new BaseDialog(Core.bundle.get("eui.ammo-priority.title"));
        dialog.addCloseButton();

        //only turrets with more than one ammo type have anything to prioritize
        turrets.clear();
        content.blocks().each(block -> {
            if(block instanceof ItemTurret t && block.uiIcon != null && block.uiIcon.found() && t.ammoTypes.size >= 2){
                turrets.add(t);
            }
        });
        turrets.sort((a, b) -> a.localizedName.compareTo(b.localizedName));

        dialog.cont.add(Core.bundle.get("eui.ammo-priority.hint")).width(460).wrap().pad(6).row();

        Table searchRow = new Table();
        searchRow.add(Core.bundle.get("eui.ammo-priority.search") + ":").padRight(6);
        searchField = searchRow.field("", this::refreshList).growX().get();
        dialog.cont.add(searchRow).growX().pad(4).row();

        list = new Table();
        dialog.cont.pane(list).width(460).height(420).row();
    }
}
