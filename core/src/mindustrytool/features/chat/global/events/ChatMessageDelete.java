package mindustrytool.features.chat.global.events;

/** Сообщение удалено на сервере (пришло по потоку). UI по нему сбрасывает цель ответа, если отвечали на него. */
public class ChatMessageDelete {
    public final String channelId;
    public final String messageId;

    public ChatMessageDelete(String channelId, String messageId) {
        this.channelId = channelId;
        this.messageId = messageId;
    }
}
