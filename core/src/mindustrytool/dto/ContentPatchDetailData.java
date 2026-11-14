package mindustrytool.dto;

import java.util.List;

/** Подробности патча (/content-patches/{id}); поле data - сам hjson-текст патча. */
public class ContentPatchDetailData {
    String id;
    String itemId;
    String name;
    String type;
    String description;
    String data;
    String createdBy;
    Long likes = 0L;
    Long downloads = 0L;
    Long comments = 0L;
    List<TagData> tags;

    public String getId() { return id; }
    public String getItemId() { return itemId; }
    public String getName() { return name; }
    public String getType() { return type; }
    public String getDescription() { return description; }
    public String getData() { return data; }
    public String getCreatedBy() { return createdBy; }
    public Long getLikes() { return likes; }
    public Long getDownloads() { return downloads; }
    public Long getComments() { return comments; }
    public List<TagData> getTags() { return tags; }
}
