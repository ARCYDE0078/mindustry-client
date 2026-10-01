package mindustry.client.utils;

import arc.*;
import arc.math.*;
import arc.scene.ui.layout.*;
import arc.util.*;
import mindustry.*;
import mindustry.core.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.ui.*;
import mindustry.world.blocks.power.*;

public class PowerInfo {
    private @Nullable static PowerGraph found;
    public static PowerGraph selected; // The hovered or selected graph

    /** HUD-полоски питания созданы (HudFragment строит их только при включённой powerinfo). Без них `found` никому не нужен. */
    private static boolean barsBuilt;
    private static long lastScan;

    public static void update() {
        // Полный проход по Groups.powerGraph раньше шёл каждый тик, даже когда полосок нет; теперь - только для них и ~10 раз/с
        if (barsBuilt && Time.timeSinceMillis(lastScan) >= 100) {
            lastScan = Time.millis();
            var max = Groups.powerGraph.array.max(up -> up.graph().all.size > 0 && up.graph().all.first().team == Vars.player.team(), up -> up.graph().all.size);
            found = max == null ? null : max.graph();
        }
        var hoverTile = Vars.control.input.cursorTile();
        selected =
            Core.settings.getBool("highlightselectedgraph") && Vars.control.input.config.isShown() && Vars.control.input.config.getSelected().block instanceof PowerBlock ? Vars.control.input.config.getSelected().power.graph :
            Core.settings.getBool("highlighthoveredgraph") && hoverTile != null && hoverTile.block() instanceof PowerBlock ? hoverTile.build.power.graph :
            null;
    }

    public static void getBars(Table power) { // FINISHME: What in the world
        barsBuilt = true;
        Bar powerBar = new Bar(
            () -> Core.bundle.format("bar.powerbalance", found != null ? (found.powerBalance.rawMean() >= 0 ? "+" : "") + UI.formatAmount((long)(found.getPowerBalance() * 60)) : "+0"),
            () -> Pal.powerBar,
            () -> found != null ? found.getSatisfaction() : 0
        );
        Bar batteryBar = new Bar(
            () -> Core.bundle.format("bar.powerstored", found != null ? UI.formatAmount((long)found.getLastPowerStored()) : 0, found != null ? UI.formatAmount((long)found.getLastCapacity()) : 0),
            () -> Pal.powerBar,
            () -> found != null && found.getLastCapacity() != 0f ? Mathf.clamp(found.getLastPowerStored() / found.getLastCapacity()) : 0
        );

        power.add(powerBar).height(18).growX().padBottom(6);
        power.row();
        power.add(batteryBar).height(18).growX().padBottom(6);

    }
}
