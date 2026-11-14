package mindustrytool.features.background;

import arc.Core;
import arc.files.Fi;
import arc.scene.ui.layout.Table;
import mindustry.Vars;
import mindustry.gen.Icon;
import mindustry.ui.FileChooser;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.BaseDialog;
import mindustrytool.MindustryToolMod;

public class BackgroundSettingsDialog extends BaseDialog {
    private final BackgroundFeature backgroundFeature;

    public BackgroundSettingsDialog(BackgroundFeature backgroundFeature) {
        super("Background Settings");
        this.backgroundFeature = backgroundFeature;

        addCloseButton();
        name = "backgroundSettingDialog";

        rebuild();
    }

    /* Перестраивается целиком после сброса: слайдер прозрачности и переключатель режима читают значение
     * из настроек только при создании, иначе после "Сбросить фон" показывали бы старые значения */
    private void rebuild() {
        Table table = cont;
        table.clear();

        table.button("Select Background Image/GIF", Icon.file, () -> {
            FileChooser.open("png", "jpg", "jpeg", "gif").submit(file -> {
                try {
                    if (file != null) {
                        Fi dest = MindustryToolMod.backgroundsDir.child(file.name());
                        file.copyTo(dest);
                        Core.settings.put(BackgroundFeature.SETTING_KEY, dest.name());
                        Core.settings.forceSave();
                        backgroundFeature.applyBackground(dest);
                    }
                } catch (Exception e) {
                    Vars.ui.showException(Core.bundle.get("feature.background.error.apply"), e);
                }
            });
        }).size(250, 60);

        table.row();
        table.slider(5, 100, 5, Core.settings.getInt(BackgroundFeature.SETTING_OPACITY_KEY, 100), value -> {
            Core.settings.put(BackgroundFeature.SETTING_OPACITY_KEY, (int) value);
        }).width(180).padTop(10);
        table.label(() -> Core.settings.getInt(BackgroundFeature.SETTING_OPACITY_KEY, 100) + "%").padTop(10)
                .padLeft(10);

        table.row();
        table.add("@feature.background.settings.scaling").padTop(10).colspan(2).row();
        table.table(modes -> {
            modes.button("@feature.background.settings.scaling.crop", Styles.togglet,
                    () -> setScaling(BackgroundScaling.CROP))
                    .update(b -> b.setChecked(BackgroundScaling.isCrop())).size(180, 50);
            modes.button("@feature.background.settings.scaling.stretch", Styles.togglet,
                    () -> setScaling(BackgroundScaling.STRETCH))
                    .update(b -> b.setChecked(!BackgroundScaling.isCrop())).size(180, 50);
        }).colspan(2).padTop(4).row();

        table.button("@feature.background.settings.reset", Icon.refresh, () -> {
            backgroundFeature.resetBackground();
            rebuild();
        }).size(250, 60).colspan(2).padTop(14);
    }

    private void setScaling(String mode) {
        Core.settings.put(BackgroundFeature.SETTING_SCALING_KEY, mode);
    }
}
