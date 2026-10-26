package mindustrytool.features.browser.map;

import java.util.Optional;

import arc.Core;
import arc.Events;
import arc.scene.ui.Dialog;
import mindustry.game.EventType.Trigger;
import mindustry.gen.Icon;
import mindustrytool.MdtKeybinds;
import mindustrytool.features.Feature;
import mindustrytool.features.FeatureMetadata;

public class MapBrowserFeature implements Feature {
    private MapDialog mapDialog;

    /* перф: metadata неизменяема, а isEnabled()/UI зовут getMetadata() каждый кадр -
     * ленивая мемоизация вместо новой цепочки builder+Drawable на каждый вызов */
    private FeatureMetadata cachedMetadata;

    @Override
    public FeatureMetadata getMetadata() {
        if (cachedMetadata == null)
            cachedMetadata = FeatureMetadata.builder()
                .name("@feature.map-browser")
                .description("@feature.map-browser.description")
                .icon(Icon.map)
                .order(1)
                .onDemand(true)
                .build();
        return cachedMetadata;
    }

    @Override
    public void init() {
        //перф: общий диспатчер кейбиндов вместо своего Trigger.update-листенера
        MdtKeybinds.onKeyRelease(MdtKeybinds.mapBrowserKb, () -> {
            if (isEnabled()) {
                Core.app.post(() -> dialog().ifPresent(Dialog::show));
            }
        });
    }

    @Override
    public void onEnable() {
        // If we could dynamically add to menu, we would.
        // But MenuFragment is static-ish.
        // We rely on the button checking the enabled state.
    }

    @Override
    public void onDisable() {
        if (mapDialog != null) {
            mapDialog.hide();
        }
    }

    @Override
    public Optional<Dialog> dialog() {
        if (mapDialog == null) {
            mapDialog = new MapDialog();
        }

        return Optional.of(mapDialog);
    }
}
