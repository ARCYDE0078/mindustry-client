package mindustrytool.features.chat.global.dto;

/** Тело серверного события удаления сообщения из SSE-потока: {id, channelId, lastMessageId}. */
public class ChatDeleteEvent {
    public String id;
    public String channelId;
    /** Новый последний id канала после удаления (null, если канал опустел) - нужен для порядка каналов и непрочитанных. */
    public String lastMessageId;
}
