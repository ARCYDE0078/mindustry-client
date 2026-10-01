package mindustrytool.dto;

import java.net.URLEncoder;
import java.util.List;

import arc.Core;
import arc.func.Cons;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.struct.ObjectMap.Entry;
import arc.util.Http;
import arc.util.Http.HttpResponse;
import arc.util.Http.HttpStatus;
import arc.util.Log;
import mindustrytool.Utils;

/**
 * Порт: apache httpclient (URIBuilder) заменён ручной сборкой query-строки —
 * клиенту не нужна ещё одна зависимость ради конкатенации параметров.
 */
public class PagingRequest<T> {

    private volatile boolean isLoading = false;
    private boolean hasMore = true;
    private boolean isError = false;
    private String error = "";

    private int size = 20;

    private int page = 0;

    private final String url;
    private final Class<T> clazz;

    private ObjectMap<String, Object> options = new ObjectMap<>();

    public PagingRequest(Class<T> clazz, String url) {
        this.url = url;
        this.clazz = clazz;
    }

    public synchronized void getPage(Cons<Seq<T>> listener) {
        if (isLoading)
            return;

        isError = false;
        isLoading = true;

        try {
            StringBuilder builder = new StringBuilder(url)
                    .append("?page=").append(page)
                    .append("&size=").append(Math.min(size, 100));

            for (Entry<String, Object> entry : options.entries()) {
                Object value = entry.value;

                if (value instanceof List<?> list) {
                    for (Object v : list) {
                        String str = String.valueOf(v);
                        if (str.isEmpty()) continue;
                        appendParam(builder, entry.key, str);
                    }
                } else {
                    if (value != null && !String.valueOf(value).isEmpty()) {
                        appendParam(builder, entry.key, String.valueOf(value));
                    }
                }
            }
            String uri = builder.toString();
            listener.get(null);

            Log.debug(uri);

            Http.get(uri)//
                    .timeout(1000 * 5)
                    .error(error -> handleError(listener, error, uri))//
                    .submit(response -> handleResult(response, size, listener));

        } catch (Exception e) {
            handleError(listener, e, url);
        }
    }

    private static void appendParam(StringBuilder builder, String key, String value) {
        builder.append('&').append(encode(key))
                .append('=').append(encode(value));
    }

    /** encode(String, Charset) появился только в Java 10 / Android API 33; вариант со строкой есть везде. */
    private static String encode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e); // UTF-8 поддерживается всегда
        }
    }

    public synchronized void handleError(Cons<Seq<T>> listener, Throwable e, String url) {
        Log.err(url, e);
        error = e.getMessage();

        isLoading = false;
        isError = true;

        listener.get(null);
    }

    public synchronized void setPage(int page) {
        this.page = page;
    }

    public synchronized void setOptions(ObjectMap<String, Object> options) {
        this.options = options;
    }

    public synchronized int getItemPerPage() {
        return size;
    }

    public synchronized void setItemPerPage(int size) {
        this.size = size;
    }

    public synchronized boolean hasMore() {
        return hasMore;
    }

    public synchronized boolean isLoading() {
        return isLoading;
    }

    public synchronized boolean isError() {
        return isError;
    }

    public synchronized String getError() {
        return error;
    }

    public synchronized int getPage() {
        return page;
    }

    public synchronized void nextPage(Cons<Seq<T>> listener) {
        if (isLoading)
            return;

        if (hasMore) {
            page++;
        }

        getPage(listener);
    }

    public synchronized void previousPage(Cons<Seq<T>> listener) {
        if (isLoading)
            return;

        if (page > 0) {
            page--;
        }

        getPage(listener);
    }

    private synchronized void handleResult(HttpResponse response, int size, Cons<Seq<T>> listener) {
        isLoading = false;
        isError = false;

        if (response.getStatus() != HttpStatus.OK) {
            isError = true;
            error = response.getResultAsString();
            listener.get(new Seq<>());
            return;
        }

        try {
            String data = response.getResultAsString();
            var items = Utils.fromJsonArray(clazz, data);

            hasMore = items.size() != 0;

            Core.app.post(() -> {
                listener.get(Seq.with(items));
            });
        } catch (Exception e) {
            handleError(listener, e, url);
        }
    }
}
