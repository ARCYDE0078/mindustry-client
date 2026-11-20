package eui.ui.blocks;

import arc.Core;
import arc.Events;
import arc.graphics.g2d.Draw;
import arc.math.geom.Vec2;
import arc.scene.ui.layout.Table;
import eui.util.Formatting;
import mindustry.game.EventType.ClientLoadEvent;
import mindustry.game.EventType.Trigger;
import mindustry.gen.Building;
import mindustry.graphics.Drawf;
import mindustry.graphics.Layer;
import mindustry.logic.Ranged;
import mindustry.world.blocks.defense.OverdriveProjector.OverdriveBuild;
import sonkaextras.UiStyle;
import mindustry.world.blocks.power.PowerGraph;
import mindustry.world.modules.ItemModule;
import mindustry.world.modules.PowerModule;

import static mindustry.Vars.player;
import static mindustry.Vars.ui;
import static mindustry.Vars.world;

/**
 * Bottom-left HUD panel showing whatever the mouse is hovering over - power balance/stored%, carried
 * items (icon + count, up to 50 shown for your own buildings, unlimited for enemy ones), and a
 * text-shaped config value - plus a dashed range circle drawn over an enemy {@link Ranged} building.
 * "eui-ShowBlockInfo". Ported from ui/blocks/block-info-ui.js.
 * <p>
 * The source rebuilt this table's contents from scratch every single frame while a building's hovered -
 * self-described in its own comment as maybe unnecessary, but it actually is needed there: its labels
 * closed over plain snapshotted numbers (the amount at the instant the row was built), not a live
 * re-read, so without the constant rebuild the numbers would freeze. Ported directly here as
 * per-frame-refreshed {@code Label} suppliers reading straight from {@link #hovered} instead - same
 * always-live behaviour, without needing to tear down and rebuild the widget tree every frame; the tree
 * itself is only rebuilt when the hovered building's *identity* changes (a different building, or none).
 * <p>
 * {@code hovered.range}/{@code .realRange} were duck-typed JS property probes ("does this Java object
 * expose a callable named range/realRange at all") - the natural Java equivalent is the {@link Ranged}
 * interface this client fork already added to every block type that has a meaningful range (turrets,
 * shield/mend/overdrive projectors, repair towers, radar, logic blocks - see {@code Radar.java}'s own
 * "Client: implement Ranged" comment), with {@link OverdriveBuild#realRange()} as the one type that has a
 * boosted range beyond its own {@code range()}.
 */
public class BlockInfoUi{
    private Table contentTable;
    private Building hovered;
    private boolean isPlayerTeam;
    private boolean built = false;

    public BlockInfoUi(){
        Events.on(ClientLoadEvent.class, e -> {
            ui.hudGroup.fill(null, t -> {
                //фон/отступ - из единого style-гайда UiStyle (те же black3 и 4px, что и раньше)
                contentTable = t.table(UiStyle.windowBg()).margin(UiStyle.PANEL_MARGIN).get();
                contentTable.visibility = () -> ui.hudfrag.shown && built;
                t.bottom().left();
                t.pack();
            });
        });

        Events.run(Trigger.update, this::update);
        Events.run(Trigger.draw, this::draw);
    }

    void update(){
        if(!Core.settings.getBool("eui-ShowBlockInfo", false)){ //default OFF - see EUIMod.buildSettings
            if(built) clearTable();
            hovered = null;
            return;
        }
        if(contentTable == null) return;

        Vec2 pos = Core.input.mouseWorld(Core.input.mouseX(), Core.input.mouseY());
        var mouseTile = world.tileWorld(pos.x, pos.y);
        if(mouseTile == null) return;

        Building build = mouseTile.build;
        if(build == null){
            if(built) clearTable();
            hovered = null;
            return;
        }

        //a different building than last frame (or the first one) - rebuild the row layout; the same
        //building stays live via the label suppliers below without needing a rebuild
        if(build != hovered){
            isPlayerTeam = build.team == player.team();
            hovered = build;
            rebuildTable();
        }
    }

    void draw(){
        if(hovered == null || hovered.dead || isPlayerTeam || !(hovered instanceof Ranged ranged)) return;
        if(sonkaextras.TurretRanges.coveredByZone(hovered)) return; //уже есть MI2U-зона этой турели - второй круг не нужен

        //перф: Draw.z + прямой вызов вместо Draw.draw(лямбда) - без аллокации на кадр при наведении
        float realRange = hovered instanceof OverdriveBuild ob ? ob.realRange() : ranged.range();
        float prevZ = Draw.z();
        Draw.z(Layer.overlayUI + 0.01f);
        Drawf.dashCircle(hovered.x, hovered.y, realRange, hovered.team.color);
        Draw.z(prevZ);
    }

    void rebuildTable(){
        clearTable();
        buildTable();
    }

    void clearTable(){
        if(!built) return;
        contentTable.clearChildren();
        built = false;
    }

    void buildTable(){
        Building b = hovered;
        PowerModule power = b.power;
        ItemModule items = b.items;
        Object config = b.config();

        boolean displayPower = power != null && !isPlayerTeam;
        boolean displayItems = items != null && items.total() > 0 && (!isPlayerTeam || items.total() <= 50);
        boolean displayConfig = config instanceof String && !isPlayerTeam;
        if(!displayPower && !displayItems && !displayConfig) return;

        if(displayPower){
            Table powerTable = contentTable.table().get();
            PowerGraph graph = power.graph;

            powerTable.label(() -> Core.bundle.get("block-info.power") + ": " + Formatting.powerToString(graph.getPowerBalance(), 1)).row();
            powerTable.label(() -> {
                float max = graph.getTotalBatteryCapacity();
                return max <= 0 ? "" : Core.bundle.get("block-info.stored") + ": " + Math.round(graph.getBatteryStored() / max * 100) + "%";
            });
            contentTable.row();
        }
        if(displayItems){
            Table resourcesTable = contentTable.table().get();
            int[] i = {0};
            items.each((item, amount) -> {
                resourcesTable.image(item.uiIcon).left();
                resourcesTable.label(() -> String.valueOf(b.items == null ? 0 : b.items.get(item))).padLeft(2f).left().padRight(4f);

                if(++i[0] % 4 == 0) resourcesTable.row();
            });
            contentTable.row();
        }
        if(displayConfig){
            Table configTable = contentTable.table().get();
            configTable.label(() -> String.valueOf(b.config())).row();
            contentTable.row();
        }
        built = true;
    }
}
