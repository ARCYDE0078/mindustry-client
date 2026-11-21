package qol.bridgetocore;

import arc.graphics.g2d.TextureRegion;
import arc.scene.style.TextureRegionDrawable;
import arc.scene.ui.ImageButton;
import arc.scene.ui.Tooltip;
import arc.scene.ui.layout.Table;
import mindustry.content.Blocks;
import mindustry.ui.Styles;
import mindustry.world.Block;
import qol.ui.QolWindow;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static arc.Core.graphics;
import static arc.Core.settings;
import static mindustry.Vars.state;
import static mindustry.Vars.ui;

/** Bridge To Core's mode-picker panel, now a {@link QolWindow} instead of a bespoke draggable Table. */
public class ToolbarWindow extends QolWindow{
    public enum Mode{ BRIDGE, JUNCTION, TITANIUM }

    /** Currently selected mode, or null if none is selected. */
    public static Mode active = Mode.BRIDGE;

    /**
     * Independent of {@link #active} - not placement modes of their own, just on/off overlays. While
     * one of these is true, any block of the matching type placed through the game's normal placement
     * UI (drag or click, not just this mod's L-hotkey route) gets rewritten into the same optimized
     * conveyor/sorter/inverted-sorter pattern - all three share {@link BridgeToCoreFeature#applyLinePattern}
     * (same corner/cadence logic, just a different straight-run block: titanium conveyor, plain
     * conveyor, or junction). Junction-build matches the pathfound Junction mode's own pattern too -
     * junction isn't instant-transfer, so both use the same lead-in-pair-turns-plus-cadence treatment
     * titanium/conveyor mode get, and not by coincidence: {@code junctionBuildActive} below is the exact
     * field {@code JunctionRouteFinder} reads (as {@code sortersEnabled}) to decide whether its own
     * pathfound route gets that treatment too, so the two stay in lockstep even when this toggle is off.
     * See {@link BridgeToCoreFeature#onLineConfirm}.
     */
    public static boolean titaniumBuildActive = false;
    public static boolean conveyorBuildActive = false;
    public static boolean junctionBuildActive = false;

    static final float BUTTON_SIZE = 42f;

    public ToolbarWindow(){
        super("bridge-to-core", "qol.feature.bridge-to-core.title");
        //overrides QolWindow's own default (Vars.ui.hudfrag.shown alone) - needs state.isGame() too,
        //same as Hub - so keep both conditions here rather than just adding hudfrag.shown on top of
        //whatever the base class already installed.
        visible(() -> state.isGame() && ui.hudfrag.shown);
        rebuild();
    }

    @Override
    protected float defaultX(){
        return 20f;
    }

    @Override
    protected float defaultY(){
        return graphics.getHeight() - 260f;
    }

    @Override
    protected void setupCont(Table cont){
        cont.defaults().size(BUTTON_SIZE).pad(2f);
        addModeButton(cont, Blocks.itemBridge, Mode.BRIDGE, "@bridgetocore-mode-bridge");
        addModeButton(cont, Blocks.junction, Mode.JUNCTION, "@bridgetocore-mode-junction");
        addModeButton(cont, Blocks.titaniumConveyor, Mode.TITANIUM, "@bridgetocore-mode-titanium");
        cont.row();
        //icons deliberately don't reuse Blocks.titaniumConveyor/junction's own uiIcon - both are
        //already the mode-picker buttons directly above, and reusing them here would make this row
        //look like a duplicate of that one at a glance. Blocks.conveyor's icon isn't used anywhere
        //else in this toolbar, so it's a safe pick for the conveyor-build toggle; invertedSorter isn't
        //either, and stays in the same "instant transfer corner" family as sorter (titanium-build's
        //existing icon), which is the actual shared mechanism all three toggles rewrite corners into.
        addBuildToggleButton(cont, Blocks.sorter.uiIcon, "@bridgetocore-titanium-build", () -> titaniumBuildActive, v -> titaniumBuildActive = v);
        addBuildToggleButton(cont, Blocks.conveyor.uiIcon, "@bridgetocore-conveyor-build", () -> conveyorBuildActive, v -> conveyorBuildActive = v);
        addBuildToggleButton(cont, Blocks.invertedSorter.uiIcon, "@bridgetocore-junction-build", () -> junctionBuildActive, v -> junctionBuildActive = v);
        cont.row();
        //доводка конца моста сортировщиками - тот же ключ, что и флажок в настройках (одно состояние на двоих, читается каждый кадр)
        addBuildToggleButton(cont, Blocks.coreShard.uiIcon, "@bridgetocore-endfinish", () -> settings.getBool("bridgetocore-endfinish", true), v -> settings.put("bridgetocore-endfinish", v));
    }

    static void addModeButton(Table buttons, Block block, Mode mode, String tooltip){
        ImageButton btn = new ImageButton(new TextureRegionDrawable(block.uiIcon), Styles.squareTogglei);
        btn.clicked(() -> active = active == mode ? null : mode);
        btn.update(() -> btn.setChecked(active == mode));
        btn.addListener(new Tooltip(t -> t.background(null).add(tooltip)));
        buttons.add(btn);
    }

    static void addBuildToggleButton(Table buttons, TextureRegion icon, String tooltip, BooleanSupplier getChecked, Consumer<Boolean> onToggle){
        ImageButton btn = new ImageButton(new TextureRegionDrawable(icon), Styles.squareTogglei);
        btn.clicked(() -> onToggle.accept(!getChecked.getAsBoolean()));
        btn.update(() -> btn.setChecked(getChecked.getAsBoolean()));
        btn.addListener(new Tooltip(t -> t.background(null).add(tooltip)));
        buttons.add(btn);
    }
}
