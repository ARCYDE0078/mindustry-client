package mindustrytool.services;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.concurrent.CompletableFuture;

import arc.util.Http;
import mindustrytool.Config;
import mindustrytool.Utils;
import mindustrytool.dto.ContentPatchDetailData;

/** Запросы к /content-patches на api.mindustry-tool.com (тот же хост, что у схем и карт, новых хостов нет). */
public class ContentPatchService {

    /** Патч - один текстовый файл, но бывает большим (пак из многих блоков) - таймаут щедрее, чем у списка. */
    private static final int DOWNLOAD_TIMEOUT_MS = 30_000;

    public static CompletableFuture<String> downloadPatch(String itemId) {
        CompletableFuture<String> future = new CompletableFuture<>();

        Http.get(Config.API_URL + "content-patches/" + encode(itemId) + "/data")
                .timeout(DOWNLOAD_TIMEOUT_MS)
                .error(future::completeExceptionally)
                .submit(response -> {
                    try {
                        future.complete(response.getResultAsString());
                    } catch (Exception e) {
                        future.completeExceptionally(e);
                    }
                });

        return future;
    }

    public static CompletableFuture<ContentPatchDetailData> findPatch(String itemId) {
        CompletableFuture<ContentPatchDetailData> future = new CompletableFuture<>();

        Http.get(Config.API_URL + "content-patches/" + encode(itemId))
                .timeout(DOWNLOAD_TIMEOUT_MS)
                .error(future::completeExceptionally)
                .submit(response -> {
                    try {
                        future.complete(Utils.fromJson(ContentPatchDetailData.class, response.getResultAsString()));
                    } catch (Exception e) {
                        future.completeExceptionally(e);
                    }
                });

        return future;
    }

    /** id приходит с сервера и вклеивается в путь - экранируем, чтобы он не мог выйти из сегмента пути. */
    private static String encode(String itemId) {
        try {
            return URLEncoder.encode(itemId, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e); // UTF-8 есть везде
        }
    }
}
