package mindustrytool.features.chat.global;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import arc.Core;
import arc.func.Cons;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.serialization.Jval;
import mindustrytool.Config;
import mindustrytool.Utils;
import mindustrytool.features.auth.AuthService;
import mindustrytool.features.chat.global.dto.ChatMessage;

public class ChatStreamClient {
    private static final String STREAM_ENDPOINT = "chats/stream";
    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String CHAT_ID_HEADER = "x-chat-id";
    private static final String ACCEPT_HEADER = "Accept";
    private static final String EVENT_STREAM = "text/event-stream";
    private static final String CONNECTED_MESSAGE = "Connected";
    private static final String DATA_EVENT = "data";
    private static final String MESSAGE_EVENT = "message";
    private static final String HEARTBEAT_EVENT = "heartbeat";
    private static final String CHAT_ID_SETTING_KEY = "mindustrytool.chat.chat-id";
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final long RECONNECT_DELAY_MS = 5000L;

    private final Cons<Seq<ChatMessage>> onMessages;
    private final Cons<Boolean> onConnectionChange;
    private final AtomicBoolean isStreaming = new AtomicBoolean(false);
    private final AtomicBoolean isConnected = new AtomicBoolean(false);

    private volatile Thread streamThread;
    private volatile HttpURLConnection currentConnection;
    /**
     * Поколение потока: connect() его увеличивает, disconnect() тоже. Поток живёт, пока его номер == текущему, иначе
     * после быстрого disconnect()->connect() старый поток (isStreaming снова true) продолжал бы крутиться рядом с новым:
     * две SSE-сессии, задвоенные сообщения/непрочитанные и сброс "connected" из старого потока.
     */
    private volatile int generation = 0;

    public ChatStreamClient(Cons<Seq<ChatMessage>> onMessages, Cons<Boolean> onConnectionChange) {
        this.onMessages = onMessages;
        this.onConnectionChange = onConnectionChange;
    }

    public boolean isConnected() {
        return isConnected.get();
    }

    public synchronized void connect() {
        if (isStreaming.get()) {
            return;
        }

        isStreaming.set(true);
        final int gen = ++generation;
        streamThread = new Thread(() -> runStreamLoop(gen), "ChatStreamThread");
        streamThread.setDaemon(true);
        streamThread.start();
    }

    public synchronized void disconnect() {
        isStreaming.set(false);
        generation++;
        updateConnection(false);

        HttpURLConnection connection = currentConnection;
        if (connection != null) {
            Log.info("Disconnecting chat stream");
            currentConnection = null;
            //без этого readLine() в потоке (readTimeout=0) висит вечно, interrupt его не будит: соединение и поток
            //оставались живыми после каждого выключения/перелогина
            try {
                connection.disconnect();
            } catch (Exception ignored) {
            }
        }

        if (streamThread != null) {
            streamThread.interrupt();
            streamThread = null;
        }
    }

    private void runStreamLoop(int gen) {
        while (isStreaming.get() && gen == generation) {
            HttpURLConnection connection = null;

            try {
                Log.info("Connecting to chat stream");
                AuthService.getInstance().refreshTokenIfNeeded().get();

                connection = openConnection();
                if (gen != generation) {
                    return; //пока открывали соединение, нас уже отключили; finally закроет connection
                }
                currentConnection = connection;

                int status = connection.getResponseCode();
                if (status != HttpURLConnection.HTTP_OK) {
                    Log.err("Chat stream failed: " + status);
                    if (gen == generation) updateConnection(false);
                    waitBeforeReconnect();
                    continue;
                }

                if (gen != generation) {
                    return;
                }
                updateConnection(true);
                Log.info("Chat stream connected");
                readEvents(connection, gen);
            } catch (Exception e) {
                if (isStreaming.get() && gen == generation) {
                    Log.err("Chat stream error", e);
                    updateConnection(false);
                }
            } finally {
                //устаревший поток не трогает общее состояние: оно уже принадлежит новому
                if (gen == generation) {
                    updateConnection(false);
                }

                if (connection != null) {
                    connection.disconnect();
                }

                if (gen == generation) {
                    currentConnection = null;
                }
            }

            waitBeforeReconnect();
        }
    }

    private HttpURLConnection openConnection() throws Exception {
        URL url = new URL(Config.API_v4_URL + STREAM_ENDPOINT);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setRequestProperty(ACCEPT_HEADER, EVENT_STREAM);
        connection.setRequestProperty(CHAT_ID_HEADER, getOrCreateChatId());
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(0);

        String accessToken = AuthService.getInstance().getAccessToken();
        if (accessToken != null) {
            connection.setRequestProperty(AUTHORIZATION_HEADER, "Bearer " + accessToken);
        }

        return connection;
    }

    private String getOrCreateChatId() {
        String chatId = Core.settings.getString(CHAT_ID_SETTING_KEY, null);
        if (chatId != null && !chatId.trim().isEmpty()) {
            return chatId;
        }

        String generatedChatId = UUID.randomUUID().toString();
        Core.settings.put(CHAT_ID_SETTING_KEY, generatedChatId);
        Core.settings.forceSave();
        return generatedChatId;
    }

    private void readEvents(HttpURLConnection connection, int gen) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
        String eventName = DATA_EVENT;
        StringBuilder dataBuilder = new StringBuilder();
        String line;

        while (isStreaming.get() && gen == generation && (line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                dispatchEvent(eventName, dataBuilder.toString());
                eventName = DATA_EVENT;
                dataBuilder.setLength(0);
                continue;
            }

            if (line.startsWith(":")) {
                continue;
            }

            if (line.startsWith("event:")) {
                eventName = line.substring("event:".length()).trim();
                continue;
            }

            if (line.startsWith("data:")) {
                if (dataBuilder.length() > 0) {
                    dataBuilder.append('\n');
                }
                dataBuilder.append(line.substring("data:".length()).trim());
            }
        }

        if (dataBuilder.length() > 0 || !eventName.isEmpty()) {
            dispatchEvent(eventName, dataBuilder.toString());
        }
    }

    private void dispatchEvent(String eventName, String data) {
        StreamEventType eventType = StreamEventType.from(eventName, data);

        if (eventType == StreamEventType.HEARTBEAT) {
            updateConnection(true);
            return;
        }

        if (eventType != StreamEventType.DATA || data == null || data.isEmpty()) {
            return;
        }

        try {
            Jval json = Jval.read(data);

            if (json.isString() && CONNECTED_MESSAGE.equals(json.asString())) {
                updateConnection(true);
                return;
            }

            Seq<ChatMessage> messages = parseMessages(json, data);
            Core.app.post(() -> onMessages.get(messages));
        } catch (Exception e) {
            Log.err("Failed to parse chat message", e);
        }
    }

    /** Порт: JsonIO (arc Json) заменён общим Jval-маппером Utils. */
    private Seq<ChatMessage> parseMessages(Jval json, String data) {
        if (json.isArray()) {
            return Seq.with(Utils.fromJsonArray(ChatMessage.class, data));
        }

        ChatMessage message = Utils.fromJson(ChatMessage.class, data);
        return Seq.with(message);
    }

    private void updateConnection(boolean connected) {
        boolean changed = isConnected.getAndSet(connected) != connected;
        if (changed) {
            Core.app.post(() -> onConnectionChange.get(connected));
        }
    }

    private void waitBeforeReconnect() {
        if (!isStreaming.get()) {
            return;
        }

        try {
            Thread.sleep(RECONNECT_DELAY_MS);
        } catch (InterruptedException ignored) {
        }
    }

    private enum StreamEventType {
        HEARTBEAT,
        DATA,
        UNKNOWN;

        private static StreamEventType from(String eventName, String data) {
            if (HEARTBEAT_EVENT.equalsIgnoreCase(eventName) || CONNECTED_MESSAGE.equals(data)) {
                return HEARTBEAT;
            }

            if (eventName == null || eventName.isEmpty() || DATA_EVENT.equalsIgnoreCase(eventName)
                    || MESSAGE_EVENT.equalsIgnoreCase(eventName)) {
                return DATA;
            }

            return UNKNOWN;
        }
    }
}
