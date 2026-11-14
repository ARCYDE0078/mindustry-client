package mindustrytool.features.chat.global;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

import arc.Core;
import arc.Events;
import arc.func.Cons;
import arc.struct.ObjectSet;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Timer;
import mindustrytool.features.chat.global.dto.ChannelDto;
import mindustrytool.features.chat.global.dto.ChatDeleteEvent;
import mindustrytool.features.chat.global.dto.ChatMessage;
import mindustrytool.features.chat.global.dto.ChatUser;
import mindustrytool.features.chat.global.events.ChatMessageDelete;
import mindustrytool.features.chat.global.events.ChatMessageReceive;
import mindustrytool.features.chat.global.events.ChatStateChange;

public class ChatService {
    private static ChatService instance;

    private final ChatStore store;
    private final ChatApiClient apiClient;
    private final ChatStreamClient streamClient;
    private final ChatStateManager stateManager;

    private ChatService() {
        store = ChatStore.getInstance();
        apiClient = new ChatApiClient();
        streamClient = new ChatStreamClient(this::handleIncomingMessages, this::handleDelete,
                this::broadcastConnectionStatus);
        stateManager = new ChatStateManager(apiClient, streamClient);
    }

    public synchronized static ChatService getInstance() {
        if (instance == null) {
            instance = new ChatService();
        }

        return instance;
    }

    public boolean isConnected() {
        return streamClient.isConnected();
    }

    /**
     * Включена ли фича чата (её выставляют ChatFeature.onEnable/onDisable). Без этого флага 60-секундный таймер ниже
     * переподключал SSE-поток через минуту после выключения чата, и клиент продолжал отчитываться о сервере/карте.
     */
    private volatile boolean active = true;

    public void setActive(boolean active) {
        this.active = active;
    }

    public void init() {
        Timer.schedule(() -> {
            if (active) connectStream();
        }, 0, 60);
        stateManager.init();
        fetchChannelsAndCurrentMessages();
    }

    public void fetchChannels() {
        getChannels(this::applyChannels, e -> Log.err("Failed to fetch channels", e));
    }

    public void fetchChannelsAndCurrentMessages() {
        apiClient.getChannels()
                .thenCompose(channels -> runOnApp(() -> applyChannels(channels)))
                .thenAccept(channelId -> {
                    if (channelId != null) {
                        fetchMessages(channelId, null);
                        fetchChatUsers(channelId);
                    }
                })
                .exceptionally(e -> {
                    Log.err("Failed to fetch channels", unwrap(e));
                    return null;
                });
    }

    public void connectStream() {
        streamClient.connect();
    }

    public void disconnectStream() {
        streamClient.disconnect();
    }

    public void getChannels(Cons<ChannelDto[]> onSuccess, Cons<Throwable> onError) {
        deliver(apiClient::getChannels, onSuccess, onError);
    }

    public CompletableFuture<ChatMessage> sendMessage(String channelId, String content, String replyTo,
            ContentType type) {
        return apiClient.sendMessage(channelId, content, replyTo, type);
    }

    public void getChatUsers(String channelId, Cons<ChatUser[]> onSuccess, Cons<Throwable> onError) {
        deliver(() -> apiClient.getChatUsers(channelId), onSuccess, onError);
    }

    public void getChatUserCount(String channelId, Cons<Integer> onSuccess, Cons<Throwable> onError) {
        deliver(() -> apiClient.getChatUserCount(channelId), onSuccess, onError);
    }

    public void fetchChatUsers(String channelId) {
        getChatUsers(channelId, users -> store.setUsers(channelId, users),
                e -> Log.err("Failed to fetch chat users for channel " + channelId, e));
    }

    public void fetchMessages(String channelId, String cursor, Cons<ChatMessage[]> onSuccess, Cons<Throwable> onError) {
        deliver(() -> apiClient.getMessages(channelId, cursor), onSuccess, onError);
    }

    public void fetchMessages(String channelId, String cursor) {
        if (!store.compareAndSetLoadingMessages(false, true)) {
            return;
        }

        fetchMessages(channelId, cursor,
                messages -> {
                    store.setLoadingMessages(false);
                    if (messages.length == 0) {
                        if (cursor == null) {
                            store.prependMessages(channelId, new Seq<>());
                        }
                        store.setFullyLoaded(channelId);
                        return;
                    }

                    store.prependMessages(channelId, new Seq<>(messages));
                },
                e -> {
                    store.setLoadingMessages(false);
                    Log.err("Failed to fetch messages for channel " + channelId, e);
                });
    }

    public void updateState(String state) {
        stateManager.updateState(state);
    }

    private void handleIncomingMessages(Seq<ChatMessage> messages) {
        Events.fire(new ChatMessageReceive(messages));
    }

    /** Сервер удалил сообщение: убираем из ленты, сдвигаем lastMessageId канала и даём UI сбросить цель ответа. */
    private void handleDelete(ChatDeleteEvent event) {
        if (event == null || event.id == null || event.channelId == null) {
            return;
        }

        ObjectSet<String> ids = new ObjectSet<>();
        ids.add(event.id);
        store.removeMessages(event.channelId, ids);

        if (event.lastMessageId != null) {
            ChannelDto channel = store.getChannels().find(c -> c.id.equals(event.channelId));
            if (channel != null) {
                channel.lastMessageId = event.lastMessageId;
            }
        }

        Events.fire(new ChatMessageDelete(event.channelId, event.id));
    }

    /** Было ли уже подключение за эту сессию: первое покрывает init() своей загрузкой, остальные - это разрыв. */
    private boolean connectedOnce = false;

    private void broadcastConnectionStatus(Boolean connected) {
        Events.fire(new ChatStateChange(connected));

        if (connected) {
            if (connectedOnce) {
                catchUp();
            }
            connectedOnce = true;
        }
    }

    /**
     * Догон после переподключения потока. Пока связи не было, сообщения не приходили (а удаления тем более),
     * и лента расходилась с сервером. Берём последнюю страницу активного канала: новые досыпаем через обычное
     * событие приёма (с непрочитанными), а то, что в окне страницы у нас есть, но на сервере уже нет, убираем.
     */
    private void catchUp() {
        String channelId = store.getCurrentChannelId();
        if (channelId == null) {
            return;
        }

        fetchChatUsers(channelId);

        if (store.getMessages(channelId).isEmpty()) {
            //ленты ещё нет (первая загрузка не удалась из-за обрыва) - это обычная загрузка, а не догон
            fetchMessages(channelId, null);
            return;
        }

        fetchMessages(channelId, null, page -> mergeLatestPage(channelId, page),
                e -> Log.err("Failed to catch up chat messages for channel " + channelId, e));
    }

    private void mergeLatestPage(String channelId, ChatMessage[] page) {
        if (page.length == 0) {
            return;
        }

        Seq<ChatMessage> known = store.getMessages(channelId);
        ObjectSet<String> pageIds = new ObjectSet<>();
        Seq<ChatMessage> fresh = new Seq<>();
        Instant oldest = null;
        Instant newest = null;

        //сервер отдаёт от новых к старым, а лента хранится от старых к новым
        for (int i = page.length - 1; i >= 0; i--) {
            ChatMessage message = page[i];
            if (message == null || message.id == null) {
                continue;
            }

            pageIds.add(message.id);
            if (message.channelId == null) {
                message.channelId = channelId;
            }

            Instant createdAt = parseInstant(message.createdAt);
            if (createdAt != null) {
                if (oldest == null || createdAt.isBefore(oldest)) oldest = createdAt;
                if (newest == null || createdAt.isAfter(newest)) newest = createdAt;
            }

            if (!known.contains(m -> m.id.equals(message.id))) {
                fresh.add(message);
            }
        }

        //удалённое за время разрыва: у нас есть, в окне страницы должно быть, но сервер его не вернул.
        //Окно строго между самым старым и самым новым сообщением страницы: пришедшее потоком после снимка страницы
        //и граничные (могли не влезть в страницу) не трогаем
        if (oldest != null) {
            ObjectSet<String> gone = new ObjectSet<>();
            for (ChatMessage message : known) {
                Instant createdAt = parseInstant(message.createdAt);
                if (createdAt != null && !pageIds.contains(message.id)
                        && createdAt.isAfter(oldest) && createdAt.isBefore(newest)) {
                    gone.add(message.id);
                }
            }
            store.removeMessages(channelId, gone);
        }

        if (!fresh.isEmpty()) {
            Events.fire(new ChatMessageReceive(fresh));
        }
    }

    private static Instant parseInstant(String value) {
        try {
            return value == null ? null : Instant.parse(value);
        } catch (Exception e) {
            return null;
        }
    }

    public void refreshCurrentChannelUsers() {
        String channelId = store.getCurrentChannelId();
        if (channelId != null) {
            fetchChatUsers(channelId);
        }
    }

    private String applyChannels(ChannelDto[] channels) {
        Seq<ChannelDto> channelSeq = new Seq<>(channels);
        store.setChannels(channelSeq);

        if (channels.length == 0) {
            return null;
        }

        String currentId = store.getCurrentChannelId();
        if (currentId != null && channelSeq.contains(channel -> channel.id.equals(currentId))) {
            return currentId;
        }

        String nextChannelId = channels[0].id;
        store.setCurrentChannelId(nextChannelId);
        return nextChannelId;
    }

    private <T> void deliver(Supplier<CompletableFuture<T>> futureSupplier, Cons<T> onSuccess,
            Cons<Throwable> onError) {
        CompletableFuture<T> future;

        try {
            future = futureSupplier.get();
        } catch (Throwable throwable) {
            if (onError != null) {
                Throwable error = unwrap(throwable);
                Core.app.post(() -> onError.get(error));
            }
            return;
        }

        future.thenAccept(result -> Core.app.post(() -> onSuccess.get(result)))
                .exceptionally(e -> {
                    if (onError != null) {
                        Throwable error = unwrap(e);
                        Core.app.post(() -> onError.get(error));
                    }
                    return null;
                });
    }

    private <T> CompletableFuture<T> runOnApp(Supplier<T> supplier) {
        CompletableFuture<T> future = new CompletableFuture<>();
        Core.app.post(() -> {
            try {
                future.complete(supplier.get());
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    private Throwable unwrap(Throwable throwable) {
        if (throwable instanceof CompletionException && throwable.getCause() != null) {
            return throwable.getCause();
        }
        return throwable;
    }
}
