package mindustrytool.dto;

/** Элемент списка патчей контента (/content-patches). lombok не используем - обычные поля + геттеры. */
public class ContentPatchData {
    String id;
    String itemId;
    String name;
    String type;
    String description;
    Long likes = 0L;
    Long downloads = 0L;
    Long comments = 0L;

    public String getId() { return id; }
    public String getItemId() { return itemId; }
    public String getName() { return name; }
    public String getType() { return type; }
    public String getDescription() { return description; }
    public Long getLikes() { return likes; }
    public Long getDownloads() { return downloads; }
    public Long getComments() { return comments; }
}
