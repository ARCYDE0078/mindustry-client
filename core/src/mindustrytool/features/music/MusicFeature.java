package mindustrytool.features.music;

import arc.Core;
import arc.Events;
import arc.audio.Music;
import arc.files.Fi;
import arc.scene.ui.Dialog;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Nullable;
import arc.util.Reflect;
import arc.util.Timer;
import mindustry.Vars;
import mindustry.gen.Icon;
import mindustry.gen.Musics;
import mindustrytool.MindustryToolMod;
import mindustrytool.features.Feature;
import mindustrytool.features.FeatureMetadata;
import mindustrytool.features.music.dto.MusicRegisterEvent;

import java.util.Optional;

public class MusicFeature implements Feature {
    private final ObjectMap<MusicType, Seq<Music>> originalMusic = new ObjectMap<>();
    private final ObjectMap<MusicType, Seq<Music>> allMusic = new ObjectMap<>();
    private final ObjectMap<String, Music> musicCache = new ObjectMap<>();

    /* Родные треки меню/редактора: слот Musics.menu/editor - обычные статические поля, и без запомненного оригинала
     * после выключения фичи в меню навсегда осталась бы чужая мелодия (а при пустом списке нечем было бы заполнить слот) */
    private @Nullable Music originalMenu;
    private @Nullable Music originalEditor;
    private boolean wasInMenu;
    private boolean wasInEditor;

    public static final Seq<String> validExtensions = Seq.with("mp3", "ogg", "wav");

    /* перф: metadata неизменяема, а isEnabled()/UI зовут getMetadata() каждый кадр -
     * ленивая мемоизация вместо новой цепочки builder+Drawable на каждый вызов */
    private FeatureMetadata cachedMetadata;

    @Override
    public FeatureMetadata getMetadata() {
        if (cachedMetadata == null)
            cachedMetadata = FeatureMetadata.builder()
                .name("@feature.music")
                .description("@feature.music.description")
                .icon(Icon.play)
                .build();
        return cachedMetadata;
    }

    @Override
    public void init() {
        Events.on(MusicRegisterEvent.class, e -> {
            captureOriginalMusic();
            loadAllCustomMusic();
        });

        captureOriginalMusic();

        //слот меню/редактора выбирает новый трек при КАЖДОМ входе в меню/редактор, а не при каждом кадре:
        //движок вызывает play(Musics.menu) постоянно, и смена слота посреди меню обрывала бы играющий трек
        Timer.schedule(() -> {
            if (!isEnabled() || Vars.state == null) {
                return;
            }

            boolean inMenu = Vars.state.isMenu();
            if (inMenu && !wasInMenu) {
                Core.app.post(() -> assignSlot(MusicType.MENU, true));
            }
            wasInMenu = inMenu;

            boolean inEditor = Vars.state.rules != null && Vars.state.rules.editor;
            if (inEditor && !wasInEditor) {
                Core.app.post(() -> assignSlot(MusicType.EDITOR, true));
            }
            wasInEditor = inEditor;
        }, 0, 0.1f);
    }

    @Override
    public void onEnable() {
        captureOriginalMusic();
        loadAllCustomMusic();
    }

    @Override
    public void onDisable() {
        restoreOriginalMusic();
    }

    private void captureOriginalMusic() {
        if (!originalMusic.containsKey(MusicType.AMBIENT) && Vars.control.sound.ambientMusic.size != 0) {
            originalMusic.put(MusicType.AMBIENT, new Seq<>(Vars.control.sound.ambientMusic));
        }

        if (!originalMusic.containsKey(MusicType.DARK) && Vars.control.sound.darkMusic.size != 0) {
            originalMusic.put(MusicType.DARK, new Seq<>(Vars.control.sound.darkMusic));
        }

        if (!originalMusic.containsKey(MusicType.BOSS) && Vars.control.sound.bossMusic.size != 0) {
            originalMusic.put(MusicType.BOSS, new Seq<>(Vars.control.sound.bossMusic));
        }

        //запоминаем только один раз: после подмены слота Musics.menu уже НАШ трек, а не родной
        if (originalMenu == null) {
            originalMenu = Musics.menu;
        }

        if (originalEditor == null) {
            originalEditor = Musics.editor;
        }
    }

    private void restoreOriginalMusic() {
        if (originalMenu != null) {
            Musics.menu = originalMenu;
        }

        if (originalEditor != null) {
            Musics.editor = originalEditor;
        }

        //раньше set(null) на тип без запомненных оригиналов (список был пуст при захвате) падал с NPE
        for (MusicType type : MusicType.values()) {
            Seq<Music> originals = originalMusic.get(type);
            Seq<Music> targetList = getTargetList(type);

            if (originals != null && targetList != null) {
                targetList.set(originals);
            }
        }

        //играющий сейчас свой трек остался бы звучать после выключения фичи, пока не закончится
        if (Vars.control != null && Vars.control.sound != null) {
            Vars.control.sound.stop();
        }
    }

    public void loadAllCustomMusic() {
        for (MusicType type : MusicType.values()) {
            loadMusicType(type);
        }
    }

    private void loadMusicType(MusicType type) {
        Seq<Music> targetList = getTargetList(type);
        Seq<Music> originals = originalMusic.get(type);
        Seq<String> customPaths = MusicConfig.getPaths(type);
        Seq<String> disabled = MusicConfig.getDisabledSounds();
        Seq<String> deleted = new Seq<>();

        //у слотов нет списка движка и нет "оригинальных" треков в списке - родной трек служит запасным
        if (!type.isSlot() && (targetList == null || originals == null)) {
            return;
        }

        Seq<Music> completeList = new Seq<>();
        if (originals != null) {
            completeList.addAll(originals);
        }

        for (String path : customPaths) {
            try {
                Fi file = MindustryToolMod.musicsDir.child(path);
                if (!file.exists()) {
                    file = Core.files.absolute(path);
                }

                if (file.exists()) {
                    String absPath = file.absolutePath();
                    Music music = musicCache.get(absPath);

                    if (music == null) {
                        try {
                            music = new Music(file);
                            musicCache.put(absPath, music);
                        } catch (Exception e) {
                            Vars.ui.showException("Failed to create music instance for " + path
                                    + ", maybe its your filename is too long", e);
                            deleted.add(path);
                            continue;
                        }
                    }
                    completeList.add(music);
                } else {
                    Log.warn("Music file not found: @", path);
                    deleted.add(path);
                }
            } catch (Exception e) {
                Log.err("Failed to load music: " + path, e);
            }
        }

        if (!deleted.isEmpty()) {
            customPaths.removeAll(deleted);
            MusicConfig.savePaths(type, customPaths);
        }

        allMusic.put(type, completeList);

        if (type.isSlot()) {
            assignSlot(type, false);
            return;
        }

        // Update target list
        targetList.clear();
        for (Music m : completeList) {
            if (!disabled.contains(getMusicName(m))) {
                targetList.add(m);
            }
        }
    }

    /**
     * Кладёт в слот меню/редактора случайный включённый свой трек (или родной, если своих нет/все выключены).
     *
     * @param reroll true при входе в меню/редактор - выбрать новый трек; false при правке списка - оставить
     *               играющий, если он всё ещё разрешён (иначе добавление трека в настройках обрывало бы музыку).
     */
    private void assignSlot(MusicType type, boolean reroll) {
        Music original = type == MusicType.MENU ? originalMenu : originalEditor;
        Music current = type == MusicType.MENU ? Musics.menu : Musics.editor;
        Seq<Music> complete = allMusic.get(type);
        Seq<String> disabled = MusicConfig.getDisabledSounds();

        Seq<Music> playable = new Seq<>();
        if (complete != null) {
            for (Music m : complete) {
                if (!disabled.contains(getMusicName(m))) {
                    playable.add(m);
                }
            }
        }

        Music pick;
        if (playable.isEmpty()) {
            pick = original;
        } else if (!reroll && playable.contains(current)) {
            pick = current;
        } else if (playable.size == 1) {
            pick = playable.first();
        } else {
            //не повторяем только что игравший трек - при двух-трёх треках иначе часто выпадал бы тот же
            Seq<Music> others = playable.select(m -> m != current);
            pick = others.isEmpty() ? playable.first() : others.random();
        }

        if (pick == null) {
            return;
        }

        if (type == MusicType.MENU) {
            Musics.menu = pick;
        } else {
            Musics.editor = pick;
        }
    }

    public Seq<Music> getMusicList(MusicType type) {
        if (!allMusic.containsKey(type)) {
            return originalMusic.get(type, new Seq<>());
        }
        return allMusic.get(type);
    }

    private @Nullable Seq<Music> getTargetList(MusicType type) {
        return switch (type) {
            case AMBIENT -> Vars.control.sound.ambientMusic;
            case DARK -> Vars.control.sound.darkMusic;
            case BOSS -> Vars.control.sound.bossMusic;
            case MENU, EDITOR -> null;
        };
    }

    public void addTrack(MusicType type, Fi file) {
        if (file == null || !file.exists()) {
            Vars.ui.showErrorMessage("Invalid file, " + file);
            return;
        }

        if (!validExtensions.contains(file.extension())) {
            Vars.ui.showErrorMessage("Invalid file type, supported types: " + validExtensions);
            return;
        }

        try {
            Fi copy = MindustryToolMod.musicsDir.child(file.name());

            file.copyTo(copy);

            Seq<String> paths = MusicConfig.getPaths(type);

            if (!paths.contains(copy.name())) {
                paths.add(copy.name());
                MusicConfig.savePaths(type, paths);
                loadMusicType(type);
            }
        } catch (Exception e) {
            Vars.ui.showException(e);
        }
    }

    public void removeTrack(MusicType type, Music music) {
        Fi file = getMusicFile(music);
        if (file == null)
            return;

        Seq<String> paths = MusicConfig.getPaths(type);
        boolean removed = paths.remove(file.name()) || paths.remove(file.absolutePath());

        if (removed) {
            if (music.isPlaying())
                music.stop();

            // Dispose and remove from cache
            String absPath = file.absolutePath();
            if (musicCache.containsKey(absPath)) {
                musicCache.remove(absPath);
                music.dispose();
            }

            MusicConfig.savePaths(type, paths);
            loadMusicType(type);
        }
    }

    public void disableAllOriginals(MusicType type) {
        Seq<Music> originals = originalMusic.get(type);
        if (originals == null) {
            return;
        }

        Seq<String> disabled = MusicConfig.getDisabledSounds();
        boolean changed = false;

        for (Music m : originals) {
            String name = getMusicName(m);
            if (!disabled.contains(name)) {
                disabled.add(name);
                changed = true;
            }
        }

        if (changed) {
            MusicConfig.saveDisabledSounds(disabled);
            loadMusicType(type);
            Vars.control.sound.playRandom();
        }
    }

    public void removeAllCustom(MusicType type) {
        Seq<String> paths = MusicConfig.getPaths(type);

        // Dispose all custom music for this type
        for (String path : paths) {
            Fi file = MindustryToolMod.musicsDir.child(path);
            if (file.exists()) {
                String absPath = file.absolutePath();
                Music m = musicCache.get(absPath);
                if (m != null) {
                    if (m.isPlaying())
                        m.stop();
                    m.dispose();
                    musicCache.remove(absPath);
                }
            }
        }

        paths.clear();
        MusicConfig.savePaths(type, paths);
        loadMusicType(type);

        //playRandom() запускает игровой (ambient/dark) трек - для слота меню/редактора это включило бы игровую музыку в меню
        if (!type.isSlot()) {
            Vars.control.sound.playRandom();
        }
    }

    public void toggleTrack(Music music) {
        String name = getMusicName(music);
        Seq<String> disabled = MusicConfig.getDisabledSounds();

        if (disabled.contains(name)) {
            disabled.remove(name);
        } else {
            disabled.add(name);
            if (music.isPlaying())
                music.stop();
        }

        MusicConfig.saveDisabledSounds(disabled);
        loadAllCustomMusic();
    }

    public boolean isTrackDisabled(Music music) {
        return MusicConfig.getDisabledSounds().contains(getMusicName(music));
    }

    public boolean isCustomTrack(Music music) {
        Fi file = getMusicFile(music);
        if (file == null)
            return false;
        return file.parent().equals(MindustryToolMod.musicsDir);
    }

    public String getMusicName(Music music) {
        Fi file = getMusicFile(music);
        if (file == null)
            return "Unknown";
        return file.nameWithoutExtension();
    }

    public Fi getMusicFile(Music music) {
        try {
            return Reflect.get(music, "file");
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public Optional<Dialog> setting() {
        return Optional.of(new MusicSettingsDialog(this));
    }
}
