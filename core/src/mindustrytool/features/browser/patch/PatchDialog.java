package mindustrytool.features.browser.patch;

import java.util.concurrent.TimeUnit;

import arc.Core;
import arc.graphics.Color;
import arc.math.Mathf;
import arc.scene.ui.Button;
import arc.scene.ui.Label;
import arc.scene.ui.TextField;
import arc.scene.ui.layout.Scl;
import arc.scene.ui.layout.Table;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Align;
import mindustry.Vars;
import mindustry.gen.Icon;
import mindustry.gen.Tex;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.BaseDialog;
import mindustrytool.Config;
import mindustrytool.dto.ContentPatchData;
import mindustrytool.dto.PagingRequest;
import mindustrytool.dto.Sort;
import mindustrytool.services.ContentPatchService;
import mindustrytool.ui.Debouncer;
import mindustrytool.ui.DetailStats;

/**
 * Браузер патчей контента (mindustry-tool.com/content-patches): поиск, сортировка, постраничный список,
 * копирование hjson в буфер / сохранение в {@code patches/} / подробности. Построен на том же PagingRequest,
 * что и браузер схем: общий механизм страниц вместо отдельного реактивного состояния апстрима.
 */
public class PatchDialog extends BaseDialog {
    private static final float TARGET_WIDTH = 300f;
    private static final float CARD_MARGIN = 4f;
    private static final float CARD_HEIGHT = 210f;
    private static final float BUTTON_HEIGHT = 40f;
    private static final float BUTTON_WIDTH = 100f;
    private static final float ICON_BUTTON_WIDTH = 60f;
    private static final float ACTION_BUTTON_SIZE = 50f;

    private final PatchInfoDialog infoDialog = new PatchInfoDialog();
    private final Debouncer debouncer = new Debouncer(500, TimeUnit.MILLISECONDS);

    private final PagingRequest<ContentPatchData> request;
    private final ObjectMap<String, Object> options = new ObjectMap<>();

    private Seq<ContentPatchData> patches = new Seq<>();
    private Sort sort = Config.sorts.get(0);
    private String searchText = "";

    private Table searchTable;
    private Table contentTable;
    private Table footerTable;
    private TextField searchField;

    private int cols;
    private float cardWidth;
    private boolean loaded = false;

    public PatchDialog() {
        super("@browser.patch.title");

        request = new PagingRequest<>(ContentPatchData.class, Config.API_URL + "content-patches");
        //как в браузере схем - только проверенные патчи
        options.put("verification", "VERIFIED");
        options.put("sort", sort.getValue());
        request.setOptions(options);

        updateLayoutMetrics();
        initializeUI();

        onResize(() -> {
            updateLayoutMetrics();
            rebuildPatches();
        });

        //запрос уходит при первом открытии, а не при создании окна: фича создаёт диалог заранее, и без этого
        //каждый запуск клиента ходил бы на сервер за списком, который никто не открывал
        shown(() -> {
            rebuildAll();
            if (!loaded) {
                loaded = true;
                request.getPage(this::handleResult);
            }
        });
    }

    private void initializeUI() {
        searchTable = new Table();
        contentTable = new Table();
        footerTable = new Table();

        clear();
        addCloseListener();

        add(searchTable).fillX().expandX();
        row();
        add(contentTable).expand().fill();
        row();
        add(footerTable).fillX().expandX();

        rebuildSearch();
        rebuildFooter();
    }

    private void updateLayoutMetrics() {
        float availableWidth = Core.graphics.getWidth() / Scl.scl() * 0.9f;
        float targetW = Math.min(availableWidth, TARGET_WIDTH);

        cols = Mathf.clamp((int) (availableWidth / targetW), 1, 20);
        cardWidth = availableWidth / cols;

        int rows = Math.max((int) (Core.graphics.getHeight() / Scl.scl(CARD_HEIGHT + 20f)), 1) + 1;
        request.setItemPerPage(Math.max(cols * rows, 20));
    }

    private void rebuildAll() {
        rebuildSearch();
        rebuildPatches();
        rebuildFooter();
    }

    private void rebuildSearch() {
        searchTable.clear();

        searchTable.table(bar -> {
            bar.button("@back", Icon.leftSmall, this::hide).width(150).pad(2);

            bar.table(wrapper -> {
                wrapper.left();
                searchField = wrapper.field(searchText, this::handleSearchInput).growX().get();
                searchField.setMessageText("@browser.patch.search");
            }).growX().pad(2);

            bar.button(Icon.zoomSmall, () -> loadingWrapper(() -> request.getPage(this::handleResult)))
                    .width(ICON_BUTTON_WIDTH).pad(2);
        }).fillX().expandX();

        searchTable.row();

        //сортировка - ряд переключателей под строкой поиска (набор сортировок общий с браузером схем)
        searchTable.pane(sorts -> {
            sorts.left();
            sorts.add("@message.sort").color(Color.lightGray).padRight(6);
            for (Sort s : Config.sorts) {
                sorts.button(s.getName(), Styles.togglet, () -> {
                    sort = s;
                    options.put("sort", s.getValue());
                    reloadPatches();
                    rebuildSearch();
                }).checked(s.equals(sort)).height(36f).minWidth(100f).padRight(4);
            }
        }).scrollY(false).fillX().expandX();
    }

    private void handleSearchInput(String text) {
        searchText = text;
        if (text.isEmpty()) {
            options.remove("name");
        } else {
            options.put("name", text);
        }
        debouncer.debounce(this::reloadPatches);
    }

    private void rebuildPatches() {
        contentTable.clear();

        if (request.isLoading()) {
            contentTable.labelWrap("@message.loading").labelAlign(Align.center).center().expand().fill();
            return;
        }

        if (request.isError()) {
            contentTable.button(Core.bundle.format("error.prefix", request.getError()), Styles.nonet,
                    () -> request.getPage(this::handleResult))
                    .center().labelAlign(Align.center).expand().fill();
            return;
        }

        if (patches.isEmpty()) {
            contentTable.add("@message.no-result").center();
            return;
        }

        contentTable.pane(container -> {
            container.top();

            for (int i = 0; i < patches.size; i++) {
                addCard(container, patches.get(i));

                if ((i + 1) % cols == 0) {
                    container.row();
                }
            }
        }).scrollY(true).expand().fill();
    }

    private void addCard(Table container, ContentPatchData data) {
        Button[] buttonRef = { null };

        buttonRef[0] = container.button(card -> {
            card.top().margin(8f);

            String name = data.getName() != null && !data.getName().trim().isEmpty()
                    ? data.getName().trim()
                    : Core.bundle.get("browser.patch.unnamed");

            card.table(header -> {
                header.left();
                Label label = header.add(PatchInfoDialog.escape(name)).style(Styles.outlineLabel).color(Color.white)
                        .growX().left().get();
                label.setEllipsis(true);
                header.add(PatchInfoDialog.typeLabel(data.getType()))
                        .color(PatchInfoDialog.isPack(data.getType()) ? Color.royal : Color.coral).padLeft(6);
            }).growX().row();

            String description = data.getDescription() != null ? data.getDescription().trim() : "";
            Label desc = new Label(PatchInfoDialog.escape(description), Styles.defaultLabel);
            desc.setWrap(true);
            desc.setEllipsis(true);
            desc.setAlignment(Align.topLeft);
            desc.setColor(Color.lightGray);
            //фиксированная высота под ~4 строки: иначе карточки разной длины рвут сетку
            card.add(desc).growX().height(80f).top().left().padTop(4).row();

            card.table(buttons -> {
                buttons.center().defaults().size(ACTION_BUTTON_SIZE);
                buttons.button(Icon.copy, Styles.emptyi, () -> PatchActions.copyToClipboard(data.getItemId()))
                        .pad(2).tooltip("@browser.patch.copy");
                buttons.button(Icon.download, Styles.emptyi,
                        () -> PatchActions.saveToFile(data.getItemId(), data.getName()))
                        .pad(2).tooltip("@browser.patch.save");
                buttons.button(Icon.info, Styles.emptyi, () -> showDetails(data)).pad(2)
                        .tooltip("@browser.patch.details");
            }).growX().padTop(4).row();

            card.table().expandY().row();
            card.table(stats -> DetailStats.draw(stats, data.getLikes(), data.getComments(), data.getDownloads()));
        }, () -> {
            //клик по кнопкам внутри карточки не должен ещё и открывать подробности
            if (buttonRef[0].childrenPressed()) {
                return;
            }
            showDetails(data);
        }).pad(CARD_MARGIN).style(Styles.flati).width(cardWidth).height(CARD_HEIGHT).get();

        buttonRef[0].getStyle().up = Tex.pane;
    }

    private void showDetails(ContentPatchData data) {
        ContentPatchService.findPatch(data.getItemId())
                .thenAccept(detail -> Core.app.post(() -> infoDialog.show(detail)))
                .exceptionally(err -> {
                    Throwable cause = err.getCause() != null ? err.getCause() : err;
                    Core.app.post(() -> Vars.ui.showErrorMessage(Core.bundle.format("error.prefix", cause.getMessage())));
                    return null;
                });
    }

    private void rebuildFooter() {
        footerTable.clear();
        footerTable.defaults().margin(4).pad(4).height(BUTTON_HEIGHT);

        footerTable.button(Icon.left, () -> request.previousPage(this::handleResult))
                .width(BUTTON_WIDTH)
                .disabled(shouldDisableNav() || request.getPage() == 0);

        footerTable.table(Tex.buttonDisabled, t -> t.labelWrap(String.valueOf(request.getPage() + 1))
                .width(50).style(Styles.defaultLabel)
                .labelAlign(Align.center).center().fill()).width(60);

        footerTable.button(Icon.edit, this::showPageSelectDialog)
                .width(BUTTON_WIDTH)
                .disabled(shouldDisableNav() || !request.hasMore());

        footerTable.button(Icon.right, () -> request.nextPage(this::handleResult))
                .width(BUTTON_WIDTH)
                .disabled(shouldDisableNav() || !request.hasMore());

        footerTable.button("@upload", () -> Core.app.openURI(Config.UPLOAD_PATCH_URL))
                .width(BUTTON_WIDTH);
    }

    private boolean shouldDisableNav() {
        return request.isLoading() || request.isError();
    }

    private void showPageSelectDialog() {
        Vars.ui.showTextInput("@select-page", "", "", input -> {
            try {
                int page = Integer.parseInt(input);

                if (page > 0)
                    page--;
                request.setPage(page);
                loadingWrapper(() -> request.getPage(this::handleResult));
            } catch (NumberFormatException e) {
                Vars.ui.showInfo("@invalid-input");
            }
        });
    }

    private void loadingWrapper(Runnable action) {
        Core.app.post(() -> {
            if (request.isLoading()) {
                Vars.ui.showInfoFade("@loading");
            } else {
                action.run();
            }
        });
    }

    private void reloadPatches() {
        request.setPage(0);
        loadingWrapper(() -> request.getPage(this::handleResult));
    }

    /** PagingRequest зовёт слушателя с null в момент старта запроса (показать "Загрузка") и со списком по ответу. */
    private void handleResult(Seq<ContentPatchData> result) {
        this.patches = result != null ? result : new Seq<>();
        rebuildPatches();
        rebuildFooter();
    }
}
