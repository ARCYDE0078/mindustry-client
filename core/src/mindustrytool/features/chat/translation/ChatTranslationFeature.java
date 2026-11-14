package mindustrytool.features.chat.translation;

import arc.Core;
import arc.func.Cons;
import arc.scene.ui.Dialog;
import arc.util.Log;
import arc.util.Strings;
import mindustry.Vars;
import mindustry.core.NetClient;
import mindustry.gen.SendMessageCallPacket;
import mindustry.gen.Icon;
import mindustry.gen.SendMessageCallPacket2;
import mindustrytool.MindustryToolMod;
import mindustrytool.features.Feature;
import mindustrytool.features.FeatureMetadata;
import arc.struct.Seq;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class ChatTranslationFeature implements Feature {
    private final Seq<TranslationProvider> providers = new Seq<>();
    private final TranslationProvider defaultTranslationProvider = new GeminiTranslationProvider();
    private String lastError = null;
    private TranslationProvider currentProvider = defaultTranslationProvider;

    /* перф: metadata неизменяема, а isEnabled()/UI зовут getMetadata() каждый кадр -
     * ленивая мемоизация вместо новой цепочки builder+Drawable на каждый вызов */
    private FeatureMetadata cachedMetadata;

    @Override
    public FeatureMetadata getMetadata() {
        if (cachedMetadata == null)
            cachedMetadata = FeatureMetadata.builder()
                .name("chat-translation")
                .description("chat-translation.description")
                .icon(mindustry.gen.Icon.chat)
                .build();
        return cachedMetadata;
    }

    public class SendTranslatedMessageCallPacket extends SendMessageCallPacket {

        @Override
        public void handleClient() {
            handleMessage(this.message, translated -> {
                NetClient.sendMessage(translated);
            });
        }
    }

    public class SendTranslatedMessageCallPacket2 extends SendMessageCallPacket2 {
        @Override
        public void handleClient() {
            if (Vars.player != this.playersender) {
                handleMessage(this.message, translated -> {
                    NetClient.sendMessage(translated, this.unformatted,
                            this.playersender);
                });
            } else {
                NetClient.sendMessage(this.message, this.unformatted,
                        this.playersender);
            }
        }
    }

    @Override
    public void init() {
        MindustryToolMod.registerPacketPlacement(SendMessageCallPacket.class, SendTranslatedMessageCallPacket::new);
        MindustryToolMod.registerPacketPlacement(SendMessageCallPacket2.class, SendTranslatedMessageCallPacket2::new);

        providers.add(defaultTranslationProvider);
        // providers.add(new GeminiTranslationProvider());
        providers.add(new DeepLTranslationProvider());

        providers.each(TranslationProvider::init);

        loadProvider();

    }

    /* Тост об ошибке перевода не чаще раза в 20 с: на упавшем провайдере КАЖДОЕ входящее сообщение давало
     * бы тост (в чат раньше падала простыня с текстом ошибки под каждой строкой) */
    private static final long ERROR_TOAST_COOLDOWN_MS = 20_000L;
    private long lastErrorToastTime = 0L;

    private void notifyErrorThrottled(String errorMsg) {
        long now = System.currentTimeMillis();
        if (now - lastErrorToastTime < ERROR_TOAST_COOLDOWN_MS) {
            return;
        }
        lastErrorToastTime = now;
        String toastMsg = Core.bundle.format("chat-translation.error.incoming-failed", errorMsg);
        Core.app.post(() -> {
            if (Vars.ui != null && Vars.ui.hudfrag != null) {
                Vars.ui.hudfrag.showToast(Icon.warning, "[scarlet]" + toastMsg);
            }
        });
    }

    public void handleMessage(String message, Cons<String> cons) {
        if (!isEnabled() || message == null || message.trim().isEmpty()) {
            cons.get(message);
            return;
        }

        //координаты, ссылки, схемы, "gg" и серверные уведомления переводить незачем - показываем как есть
        if (!ChatFilter.shouldTranslateIncoming(message)) {
            cons.get(message);
            return;
        }

        String original = Strings.stripColors(message).trim();

        translateContent(message)
                .thenAccept(translated -> Core.app.post(() -> {
                    //перевод совпал с оригиналом (чат уже на нужном языке) - дубль "текст (текст)" не нужен
                    if (translated == null || translated.trim().isEmpty() || translated.equalsIgnoreCase(original)) {
                        cons.get(message);
                    } else {
                        cons.get(Strings.format("@ [gold](@)[white]", message, translated));
                    }
                }))
                .exceptionally(e -> {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    String reason = cause.getMessage() != null ? cause.getMessage() : "Unknown error";
                    lastError = reason;

                    Log.err("Chat translation failed: " + reason);
                    notifyErrorThrottled(reason);
                    //сообщение игрока не теряем и не засоряем ошибкой: ошибка видна в настройках и тостом
                    Core.app.post(() -> cons.get(message));
                    return null;
                });

    }

    public CompletableFuture<String> translateContent(String message) {
        if (!isEnabled()) {
            throw new IllegalArgumentException("ChatTranslationFeature is not enabled");
        }

        return currentProvider.translate(Strings.stripColors(message))
                .whenComplete((translated, error) -> {
                    if (error != null) {
                        Throwable cause = error.getCause() != null ? error.getCause() : error;
                        lastError = cause.getMessage();
                    }
                });
    }

    private void loadProvider() {
        String id = ChatTranslationConfig.getProviderId();
        currentProvider = providers.find(p -> p.getId().equals(id));

        if (currentProvider == null) {
            currentProvider = defaultTranslationProvider;
        }
    }

    @Override
    public Optional<Dialog> setting() {
        return Optional.of(new ChatTranslationSettingsDialog(this));
    }

    Seq<TranslationProvider> getProviders() {
        return providers;
    }

    TranslationProvider getCurrentProvider() {
        return currentProvider;
    }

    void setCurrentProvider(TranslationProvider currentProvider) {
        this.currentProvider = currentProvider;
    }

    String getLastError() {
        return lastError;
    }
}
