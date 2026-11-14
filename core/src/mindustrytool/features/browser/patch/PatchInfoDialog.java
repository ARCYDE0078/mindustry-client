package mindustrytool.features.browser.patch;

import arc.Core;
import arc.graphics.Color;
import arc.scene.ui.Label;
import arc.scene.ui.layout.Table;
import arc.util.Align;
import mindustry.gen.Icon;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.BaseDialog;
import mindustrytool.Config;
import mindustrytool.dto.ContentPatchDetailData;
import mindustrytool.ui.DetailStats;
import mindustrytool.ui.TagContainer;
import mindustrytool.ui.UserCard;

/** Подробности патча: автор, статистика, теги, описание и сам hjson-текст с копированием/сохранением. */
public class PatchInfoDialog extends BaseDialog {

    /**
     * Метка с мегабайтным текстом строит огромную геометрию глифов и вешает интерфейс, а читать столько в окне
     * всё равно никто не будет: показываем начало, а в буфер и в файл уходит патч целиком.
     */
    private static final int MAX_PREVIEW_CHARS = 30_000;

    public PatchInfoDialog() {
        super("");

        setFillParent(true);
        addCloseListener();
    }

    public void show(ContentPatchDetailData data) {
        cont.clear();
        cont.top().left();

        title.setText("[[" + Core.bundle.get("browser.patch.label") + "] " + displayName(data));

        String patchData = data.getData() != null ? data.getData() : "";

        if (Core.graphics.isPortrait()) {
            cont.pane(t -> {
                buildDetails(t, data);
                t.row();
                buildCode(t, patchData);
            }).top().left().grow().scrollX(false).pad(10);
        } else {
            cont.table(main -> {
                main.top().left();
                main.pane(t -> buildDetails(t, data)).width(Core.graphics.getWidth() / 4f).top().left().growY()
                        .scrollX(false).pad(10);
                main.table(code -> buildCode(code, patchData)).grow().pad(10);
            }).grow();
        }

        buttons.clearChildren();
        buttons.defaults().size(Core.graphics.isPortrait() ? 150f : 210f, 64f);
        buttons.button("@browser.patch.copy", Icon.copy, () -> PatchActions.copyTextToClipboard(patchData)).pad(4);
        buttons.button("@browser.patch.save", Icon.download,
                () -> PatchActions.saveTextToFile(patchData, data.getName())).pad(4);
        buttons.button("@open", Icon.link,
                () -> Core.app.openURI(Config.WEB_URL + "/content-patches/" + data.getItemId())).pad(4);
        buttons.button("@back", Icon.left, this::hide);

        show();
    }

    private void buildDetails(Table card, ContentPatchDetailData data) {
        card.top().left().defaults().top().left();

        if (data.getCreatedBy() != null && !data.getCreatedBy().isEmpty()) {
            card.table(t -> {
                t.left();
                t.add(Core.bundle.format("message.author")).marginRight(4).padRight(4);
                UserCard.draw(t, data.getCreatedBy());
            }).fillX().padBottom(4).top().left().row();
        }

        card.table(t -> {
            t.left();
            t.add("@browser.patch.type").color(Color.lightGray).padRight(6);
            t.add(typeLabel(data.getType())).color(isPack(data.getType()) ? Color.royal : Color.coral);
        }).fillX().padBottom(4).top().left().row();

        card.table(stats -> DetailStats.draw(stats, data.getLikes(), data.getComments(), data.getDownloads()))
                .fillX().padBottom(4).top().left().row();

        if (data.getTags() != null && !data.getTags().isEmpty()) {
            card.table(container -> TagContainer.draw(container, data.getTags()))
                    .fillX().padBottom(4).top().left().row();
        }

        if (data.getDescription() != null && !data.getDescription().trim().isEmpty()) {
            card.add(escape(data.getDescription().trim()))
                    .left()
                    .wrap()
                    .wrapLabel(true)
                    .growX()
                    .labelAlign(Align.topLeft)
                    .top().left();
        }
    }

    private void buildCode(Table parent, String patchData) {
        parent.top().left();
        parent.add("@browser.patch.data-section").color(Color.lightGray).left().padBottom(4).row();

        boolean truncated = patchData.length() > MAX_PREVIEW_CHARS;
        String preview = truncated ? patchData.substring(0, MAX_PREVIEW_CHARS) + "\n..." : patchData;

        //квадратные скобки в hjson (массивы) иначе читаются как цветовая разметка метки
        Label code = new Label(escape(preview), Styles.monoLabel);
        code.setWrap(false);
        parent.pane(Styles.noBarPane, code).grow().left().top();
    }

    static String displayName(ContentPatchDetailData data) {
        return data.getName() != null && !data.getName().trim().isEmpty()
                ? data.getName().trim()
                : Core.bundle.get("browser.patch.unnamed");
    }

    static boolean isPack(String type) {
        return "DATAPACK".equalsIgnoreCase(type);
    }

    static String typeLabel(String type) {
        return Core.bundle.get(isPack(type) ? "browser.patch.type.datapack" : "browser.patch.type.data");
    }

    /** Текст с сервера выводим как есть, без разбора "[...]" как цветовых тегов Mindustry. */
    static String escape(String text) {
        return text.replace("[", "[[");
    }
}
