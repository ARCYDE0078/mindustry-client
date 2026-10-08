package mindustrytool.features.settings;

import arc.Core;
import arc.Events;
import arc.graphics.Color;
import arc.scene.ui.layout.Scl;
import arc.scene.ui.layout.Table;
import mindustry.ui.dialogs.BaseDialog;
import mindustrytool.MdtInitEvent;
import mindustrytool.Utils;
import mindustrytool.features.Feature;
import mindustrytool.features.FeatureManager;
import mindustrytool.features.WebFeature;

/**
 * Порт: выкинуты вкладка Development (vercel-трекер задач мода), кнопка Changelog
 * (GitHub-релизы мода) и Report bug (их Discord — за баги порта они не отвечают),
 * а также карточка Icon Browser (у клиента уже есть mi2u EmojiMindow).
 */
public class FeatureSettingDialog extends BaseDialog {

    private String filter = "";
    private Table paneTable;

    public FeatureSettingDialog() {
        super("Mindustry Tool");

        addCloseButton();

        shown(this::rebuild);
        resized(this::rebuild);

        Events.on(MdtInitEvent.class, e -> {
            rebuild();
        });
    }

    private void rebuild() {
        cont.clear();
        cont.top();

        cont.table(s -> {
            s.left();
            s.image(mindustry.gen.Icon.zoom).padRight(8);
            s.field(filter, f -> {
                filter = f;
                rebuildPane();
            }).growX();
        }).growX().pad(10).row();

        cont.pane(table -> {
            this.paneTable = table;
            rebuildPane();
        }).scrollX(false).grow();
    }

    private void rebuildPane() {
        if (paneTable == null) {
            return;
        }

        paneTable.clear();
        paneTable.top().left();

        float screenWidth = (Core.graphics.getWidth() / Scl.scl() * 0.9f - 40f);
        int cols = Math.max(1, (int) (screenWidth / 340f));
        float cardWidth = screenWidth / cols;

        paneTable.row();
        paneTable.button("@reeanable", () -> {
            FeatureManager.getInstance().reEnable();
            rebuildPane();
        }).width(250).top().left().pad(10).tooltip("Used after a crash");

        paneTable.row();

        int i = 0;
        // Toggleable Features
        for (Feature feature : FeatureManager.getInstance().getFeatures()) {
            if (!filter.isEmpty()
                    && !Utils.getString(feature.getMetadata().name()).toLowerCase().contains(filter.toLowerCase())) {
                continue;
            }

            FeatureCard.buildToggle(paneTable, feature, cardWidth, this::rebuildPane);

            if (++i % cols == 0) {
                paneTable.row();
            }
        }

        if (i % cols != 0) {
            paneTable.row();
        }

        paneTable.image().color(Color.gray).growX().height(4f).colspan(cols).pad(10).row();

        paneTable.add("@feature").padLeft(10).top().left().row();

        i = 0;

        // Features with Dialogs
        for (Feature feature : FeatureManager.getInstance().getEnableds()) {
            if (!feature.dialog().isPresent()) {
                continue;
            }

            if (!filter.isEmpty()
                    && !Utils.getString(feature.getMetadata().name()).toLowerCase().contains(filter.toLowerCase())) {
                continue;
            }

            FeatureCard.buildLink(paneTable, feature, cardWidth);

            if (++i % cols == 0) {
                paneTable.row();
            }
        }

        // Web Features
        for (WebFeature webFeature : WebFeature.defaults) {
            if (!filter.isEmpty() && !Utils.getString(webFeature.name()).toLowerCase().contains(filter.toLowerCase()))
                continue;

            FeatureCard.buildLink(paneTable, webFeature, cardWidth);
            if (++i % cols == 0)
                paneTable.row();
        }

        paneTable.table().growX().row();
    }
}
