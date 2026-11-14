package mindustrytool.features.browser.patch;

import java.util.Optional;

import arc.Core;
import arc.scene.ui.Dialog;
import mindustry.gen.Icon;
import mindustrytool.MdtKeybinds;
import mindustrytool.features.Feature;
import mindustrytool.features.FeatureMetadata;

/**
 * Браузер патчей контента с mindustry-tool.com (апстрим v5.5.27): найти патч, скопировать hjson в буфер
 * или сохранить в {@code patches/}. Нативного аналога в клиенте нет. Открывается карточкой в окне
 * Mindustry Tool и по (по умолчанию не назначенной) клавише.
 */
public class PatchBrowserFeature implements Feature {
    private PatchDialog dialog;

    /* перф: metadata неизменяема, а isEnabled()/UI зовут getMetadata() каждый кадр -
     * ленивая мемоизация вместо новой цепочки builder+Drawable на каждый вызов */
    private FeatureMetadata cachedMetadata;

    @Override
    public FeatureMetadata getMetadata() {
        if (cachedMetadata == null)
            cachedMetadata = FeatureMetadata.builder()
                    .name("@feature.patch-browser")
                    .description("@feature.patch-browser.description")
                    .icon(Icon.wrench)
                    .order(3)
                    .onDemand(true)
                    .build();
        return cachedMetadata;
    }

    @Override
    public void init() {
        MdtKeybinds.onKeyRelease(MdtKeybinds.patchBrowserKb, () -> {
            if (isEnabled()) {
                Core.app.post(() -> dialog().ifPresent(Dialog::show));
            }
        });
    }

    @Override
    public void onDisable() {
        if (dialog != null) {
            dialog.hide();
        }
    }

    @Override
    public Optional<Dialog> dialog() {
        if (dialog == null) {
            dialog = new PatchDialog();
        }

        return Optional.of(dialog);
    }
}
