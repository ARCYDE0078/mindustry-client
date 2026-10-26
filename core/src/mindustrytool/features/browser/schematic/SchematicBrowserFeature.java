package mindustrytool.features.browser.schematic;

import java.util.Optional;

import arc.Core;
import arc.Events;
import arc.scene.ui.Button;
import arc.scene.ui.Dialog;
import arc.scene.ui.layout.Table;
import mindustry.Vars;
import mindustry.game.EventType.Trigger;
import mindustry.gen.Icon;
import mindustrytool.MdtKeybinds;
import mindustrytool.features.Feature;
import mindustrytool.features.FeatureMetadata;

public class SchematicBrowserFeature implements Feature {
    private SchematicDialog schematicDialog;
    private Button browseButton;

    /* перф: metadata неизменяема, а isEnabled()/UI зовут getMetadata() каждый кадр -
     * ленивая мемоизация вместо новой цепочки builder+Drawable на каждый вызов */
    private FeatureMetadata cachedMetadata;

    @Override
    public FeatureMetadata getMetadata() {
        if (cachedMetadata == null)
            cachedMetadata = FeatureMetadata.builder()
                .name("@feature.schematic-browser")
                .description("@feature.schematic-browser.description")
                .icon(Icon.paste)
                .order(2)
                .onDemand(true)
                .build();
        return cachedMetadata;
    }

    @Override
    public void init() {
        if (isEnabled()) {
            addBrowseButton();
        }

        //перф: общий диспатчер кейбиндов вместо своего Trigger.update-листенера
        MdtKeybinds.onKeyRelease(MdtKeybinds.schematicBrowserKb, () -> {
            if (isEnabled()) {
                Core.app.post(() -> dialog().ifPresent(Dialog::show));
            }
        });
    }

    private void addBrowseButton() {
        if (Vars.ui == null || Vars.ui.schematics == null) {
            return;
        }

        Table buttons = Vars.ui.schematics.buttons;
        if (browseButton == null || browseButton.parent == null) {
            browseButton = buttons.button("@browse", Icon.menu, () -> {
                Vars.ui.schematics.hide();
                dialog().ifPresent(Dialog::show);
            }).get();
        }
    }

    @Override
    public void onEnable() {
        addBrowseButton();
    }

    @Override
    public void onDisable() {
        if (browseButton != null) {
            browseButton.remove();
            browseButton = null;
        }
    }

    @Override
    public Optional<Dialog> dialog() {
        if (schematicDialog == null) {
            schematicDialog = new SchematicDialog();
        }

        return Optional.of(schematicDialog);
    }
}
