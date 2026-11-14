package mindustrytool.features.music;

public enum MusicType {
    AMBIENT,
    DARK,
    BOSS,
    /** Тема главного меню (Musics.menu). */
    MENU,
    /** Тема редактора карт (Musics.editor). */
    EDITOR;

    /**
     * MENU и EDITOR - не списки, из которых движок выбирает трек сам, а ОДИН статический слот (Musics.menu /
     * Musics.editor): свои треки подставляются в слот случайным выбором при входе в меню/редактор.
     */
    public boolean isSlot() {
        return this == MENU || this == EDITOR;
    }

    public String getKey() {
        return "mindustrytool.music." + name().toLowerCase();
    }
}
