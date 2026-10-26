package mindustrytool.features.playerconnect;

import java.util.Optional;

import arc.scene.ui.Dialog;
import arc.scene.ui.layout.Table;
import arc.util.Log;
import mindustry.Vars;
import mindustry.gen.Icon;
import mindustry.ui.IntFormat;
import mindustry.ui.Styles;
import mindustrytool.features.Feature;
import mindustrytool.features.FeatureMetadata;

public class PlayerConnectFeature implements Feature {
    private CreateRoomDialog createRoomDialog;
    private JoinRoomDialog joinRoomDialog;
    private PlayerConnectJoinInjector injector;
    private PlayerConnectSettingDialog settingDialog;

    /* перф: metadata неизменяема, а isEnabled()/UI зовут getMetadata() каждый кадр -
     * ленивая мемоизация вместо новой цепочки builder+Drawable на каждый вызов */
    private FeatureMetadata cachedMetadata;

    @Override
    public FeatureMetadata getMetadata() {
        if (cachedMetadata == null)
            cachedMetadata = FeatureMetadata.builder()
                .name("@feature.player-connect")
                .description("@feature.player-connect.description")
                .icon(Icon.planet)
                .order(3)
                .onDemand(true)
                .build();
        return cachedMetadata;
    }

    @Override
    public void init() {
        createRoomDialog = new CreateRoomDialog();
        joinRoomDialog = new JoinRoomDialog();
        injector = new PlayerConnectJoinInjector(joinRoomDialog);

        if (Vars.ui.join != null) {
            injector.inject();

            Vars.ui.join.shown(() -> {
                injector.inject();
            });
        }
    }

    @Override
    public void onEnable() {
        if (Vars.ui.hudGroup != null) {
            Table parent = Vars.ui.hudGroup.find("fps/ping");

            if (parent == null) {
                Log.err("fps/ping not found");
                return;
            }

            IntFormat ping = new IntFormat("ping");

            parent.label(() -> pingColor() + ping.get(PlayerConnect.ping))
                    .visible(() -> PlayerConnect.isHosting())
                    .left()
                    .style(Styles.outlineLabel)
                    .name("pc-ping").get();

            parent.row();
        }
    }

    private String pingColor() {
        if (PlayerConnect.ping <= 200) {
            return "";
        }

        if (PlayerConnect.ping <= 500) {
            return "[yellow]";
        }

        return "red";
    }

    @Override
    public void onDisable() {
        if (createRoomDialog != null)
            createRoomDialog.hide();
        if (joinRoomDialog != null)
            joinRoomDialog.hide();
    }

    @Override
    public Optional<Dialog> setting() {
        if (settingDialog == null) {
            settingDialog = new PlayerConnectSettingDialog();
        }
        return Optional.of(settingDialog);
    }
}
