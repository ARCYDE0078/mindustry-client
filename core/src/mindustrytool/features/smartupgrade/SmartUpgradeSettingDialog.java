package mindustrytool.features.smartupgrade;

import arc.Core;
import arc.graphics.Color;
import arc.scene.ui.layout.Table;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.BaseDialog;

/* Настройки Smart Upgrade: правила обхода и лимит блоков за одно действие.
 * Апстримовый диалог строился на solim (реактивные Signal), здесь обычные Table + Core.settings -
 * те же три опции под нашими ключами mindustrytool.smart-upgrade.* */
public class SmartUpgradeSettingDialog extends BaseDialog {

    public SmartUpgradeSettingDialog() {
        super(Core.bundle.get("feature.smart-upgrade.settings", "Smart Upgrade settings"));
        addCloseButton();
        buttons.button(Core.bundle.get("feature.smart-upgrade.settings.reset", "Reset to defaults"), () -> {
            Core.settings.remove(SmartUpgradeFeature.KEY_MAX_UPDATES);
            Core.settings.remove(SmartUpgradeFeature.KEY_ONLY_SAME_TYPE);
            Core.settings.remove(SmartUpgradeFeature.KEY_TRAVERSE_BRIDGES);
            //значения слайдера/галочек живут в виджетах - проще пересобрать, чем синхронизировать
            setup();
        });
        setup();
    }

    private void setup() {
        cont.clear();
        cont.pane(root -> {
            root.margin(10);
            root.table(Styles.black6, t -> {
                t.top().left().margin(10);

                checkRow(t, "feature.smart-upgrade.settings.only-same-type",
                        "feature.smart-upgrade.settings.only-same-type.desc",
                        SmartUpgradeFeature.KEY_ONLY_SAME_TYPE, SmartUpgradeFeature.isOnlySameType());
                divider(t);
                checkRow(t, "feature.smart-upgrade.settings.traverse-bridges",
                        "feature.smart-upgrade.settings.traverse-bridges.desc",
                        SmartUpgradeFeature.KEY_TRAVERSE_BRIDGES, SmartUpgradeFeature.isTraverseBridges());
                divider(t);

                t.add(Core.bundle.get("feature.smart-upgrade.settings.max-upgrades", "Max upgraded blocks"))
                        .left().color(Color.white).row();
                t.add(Core.bundle.get("feature.smart-upgrade.settings.max-upgrades.desc",
                        "Maximum number of connected blocks changed in a single action."))
                        .left().color(Color.gray).wrap().growX().padBottom(4).row();
                t.table(s -> {
                    s.left();
                    s.label(() -> String.valueOf(SmartUpgradeFeature.getMaxUpdates())).padRight(10).width(50);
                    s.slider(50, 2000, 50, SmartUpgradeFeature.getMaxUpdates(),
                            v -> Core.settings.put(SmartUpgradeFeature.KEY_MAX_UPDATES, (int) v)).growX();
                }).growX();
            }).width(Math.min(Core.graphics.getWidth() * 0.9f, 450f)).pad(5).row();
        }).grow();
    }

    private void checkRow(Table t, String titleKey, String descKey, String settingKey, boolean value) {
        t.check(Core.bundle.get(titleKey), value, v -> Core.settings.put(settingKey, v)).left().row();
        t.add(Core.bundle.get(descKey)).left().color(Color.gray).wrap().growX().padLeft(8).row();
    }

    private void divider(Table t) {
        t.image().color(Color.gray).growX().height(2f).padTop(6).padBottom(6).row();
    }
}
