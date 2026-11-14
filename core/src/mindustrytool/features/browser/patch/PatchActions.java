package mindustrytool.features.browser.patch;

import java.util.concurrent.CompletableFuture;

import arc.Core;
import arc.files.Fi;
import mindustry.Vars;
import mindustrytool.services.ContentPatchService;

/** Копирование патча в буфер и сохранение в {@code <данные игры>/patches}. */
public final class PatchActions {

    private PatchActions() {
    }

    public static CompletableFuture<Void> copyToClipboard(String itemId) {
        return ContentPatchService.downloadPatch(itemId)
                .thenAccept(data -> Core.app.post(() -> copyTextToClipboard(data)))
                .exceptionally(PatchActions::notifyDownloadError);
    }

    public static void copyTextToClipboard(String data) {
        Core.app.setClipboardText(data != null ? data : "");
        Vars.ui.showInfoFade("@browser.patch.copied");
    }

    public static CompletableFuture<Void> saveToFile(String itemId, String patchName) {
        return ContentPatchService.downloadPatch(itemId)
                .thenAccept(data -> saveTextToFile(data, patchName))
                .exceptionally(PatchActions::notifyDownloadError);
    }

    public static void saveTextToFile(String data, String patchName) {
        try {
            //имя патча приходит с сервера: выкидываем разделители пути и спецсимволы файловых систем,
            //иначе "../x" вышло бы из каталога patches
            String safeName = patchName != null && !patchName.trim().isEmpty()
                    ? patchName.trim().replaceAll("[\\\\/:*?\"<>|]", "_").replace("..", "_")
                    : "patch_" + System.currentTimeMillis();

            Fi patchesDir = Vars.dataDirectory.child("patches");
            patchesDir.mkdirs();

            Fi file = patchesDir.child(safeName + ".hjson");
            file.writeString(data != null ? data : "");
            Core.app.post(() -> Vars.ui.showInfoFade(Core.bundle.format("browser.patch.saved", "patches/" + file.name())));
        } catch (Exception e) {
            Core.app.post(() -> Vars.ui.showErrorMessage(Core.bundle.format("browser.patch.save-error", e.getMessage())));
        }
    }

    private static Void notifyDownloadError(Throwable throwable) {
        Throwable cause = throwable.getCause() != null ? throwable.getCause() : throwable;
        String message = cause.getMessage() != null ? cause.getMessage() : cause.toString();
        Core.app.post(() -> Vars.ui.showErrorMessage(Core.bundle.format("error.prefix", message)));
        return null;
    }
}
