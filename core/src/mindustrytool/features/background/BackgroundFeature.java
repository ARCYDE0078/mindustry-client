package mindustrytool.features.background;

import arc.Core;
import arc.files.Fi;
import arc.graphics.Texture;
import arc.graphics.g2d.Draw;
import arc.scene.ui.Dialog;
import arc.util.Log;
import arc.util.Reflect;
import arc.util.Time;
import mindustry.Vars;
import mindustry.gen.Icon;
import mindustry.graphics.MenuRenderer;
import mindustrytool.MindustryToolMod;
import mindustrytool.features.Feature;
import mindustrytool.features.FeatureMetadata;

import java.util.Optional;
import arc.graphics.g2d.TextureRegion;

public class BackgroundFeature implements Feature {
    static final String SETTING_KEY = "mindustrytool.background.path";
    static final String SETTING_OPACITY_KEY = "mindustrytool.background.opacity";
    static final String SETTING_SCALING_KEY = "mindustrytool.background.scaling";
    private MenuRenderer originalRenderer;
    private MenuRenderer customRenderer;

    /* перф: metadata неизменяема, а isEnabled()/UI зовут getMetadata() каждый кадр -
     * ленивая мемоизация вместо новой цепочки builder+Drawable на каждый вызов */
    private FeatureMetadata cachedMetadata;

    @Override
    public FeatureMetadata getMetadata() {
        if (cachedMetadata == null)
            cachedMetadata = FeatureMetadata.builder()
                .name("@feature.background")
                .description("@feature.background.description")
                .icon(Icon.image)
                .build();
        return cachedMetadata;
    }

    @Override
    public void onEnable() {
        String path = Core.settings.getString(SETTING_KEY, null);

        if (path != null) {
            Fi file = MindustryToolMod.backgroundsDir.child(path);

            if (!file.exists()) {
                file = Core.files.absolute(path);
            }

            if (file.exists() && !file.isDirectory()) {
                applyBackground(file);
            }
        }
    }

    @Override
    public void onDisable() {
        restoreOriginalRenderer();
    }

    private void restoreOriginalRenderer() {
        if (originalRenderer != null) {
            try {
                Reflect.set(Vars.ui.menufrag, "renderer", originalRenderer);
                if (customRenderer != null) {
                    customRenderer.dispose();
                    customRenderer = null;
                }
            } catch (Exception e) {
                Log.err("Failed to restore background", e);
            }
        }
    }

    /** Вернуть стандартный фон меню и сбросить все настройки фона (файл, прозрачность, масштабирование). */
    void resetBackground() {
        restoreOriginalRenderer();

        Core.settings.remove(SETTING_KEY);
        Core.settings.remove(SETTING_OPACITY_KEY);
        Core.settings.remove(SETTING_SCALING_KEY);
        Core.settings.forceSave();
    }

    void applyBackground(Fi file) {
        if (!file.exists() || file.isDirectory()) {
            Core.app.post(() -> {
                Vars.ui.showInfo(Core.bundle.format("feature.background.error.invalid-file", file.absolutePath()));
            });
            return;
        }

        try {
            if (originalRenderer == null) {
                originalRenderer = Reflect.get(Vars.ui.menufrag, "renderer");
            }

            if (customRenderer != null) {
                customRenderer.dispose();
                customRenderer = null;
            }

            if (file.extension().equalsIgnoreCase("gif")) {
                GifBackgroundLoader.Result gif = GifBackgroundLoader.load(file);
                if (gif.frames.length == 0) throw new IllegalStateException("gif has no frames");
                if (gif.truncated) {
                    Core.app.post(() -> Vars.ui.showInfo("This GIF has a lot of frames - only the first part of the animation is used to avoid excessive VRAM/load time."));
                }
                customRenderer = new GifMenuRenderer(gif, originalRenderer);
            } else {
                Texture texture = new Texture(file);
                customRenderer = new CustomMenuRenderer(texture, originalRenderer);
            }
            Reflect.set(Vars.ui.menufrag, "renderer", customRenderer);
        } catch (Exception e) {
            Core.app.post(() -> {
                Vars.ui.showException(Core.bundle.get("feature.background.error.apply"), e);
            });
        }
    }

    @Override
    public Optional<Dialog> setting() {
        return Optional.of(new BackgroundSettingsDialog(this));
    }

    /** Рисует регион фона по центру экрана в режиме масштабирования из настроек (обрезка по умолчанию). */
    static void drawScaled(TextureRegion region) {
        float screenWidth = Core.graphics.getWidth();
        float screenHeight = Core.graphics.getHeight();
        boolean crop = BackgroundScaling.isCrop();

        Draw.rect(region, screenWidth / 2f, screenHeight / 2f,
                BackgroundScaling.drawWidth(region.width, region.height, screenWidth, screenHeight, crop),
                BackgroundScaling.drawHeight(region.width, region.height, screenWidth, screenHeight, crop));
    }

    // sonkaextras.MenuUnitDialog применяет выбор юнита фона меню к ОРИГИНАЛЬНОМУ рендеру внутри
    // такой обёртки (он рисуется под картинкой/гифкой при opacity < 100%) - общий интерфейс вместо
    // instanceof на конкретный класс, чтобы это работало и для статичной, и для gif-подмены разом.
    public interface WrapsMenuRenderer {
        MenuRenderer originalRenderer();
    }

    public static class CustomMenuRenderer extends MenuRenderer implements WrapsMenuRenderer {
        private final Texture texture;
        private final TextureRegion region;
        public final MenuRenderer originalRenderer;

        public CustomMenuRenderer(Texture texture, MenuRenderer originalRenderer) {
            super();
            this.texture = texture;
            this.region = new TextureRegion(texture);
            this.originalRenderer = originalRenderer;
        }

        /**
         * Конструктор MenuRenderer вызывает refresh(), который строит весь 3D-фон меню (кэш плиток + фреймбуфер теней),
         * а наш render() им не пользуется: каждое применение фона зря утекало бы видеопамятью. В конструкторе
         * (originalRenderer ещё null) не строим ничего; позже (смена cursednesslevel в настройках) обновляем
         * настоящий фон под картинкой, а не этот пустой.
         */
        @Override
        public void refresh() {
            if (originalRenderer != null) {
                originalRenderer.refresh();
            }
        }

        @Override
        public MenuRenderer originalRenderer(){
            return originalRenderer;
        }

        @Override
        public void render() {
            try {
                int opacity = Core.settings.getInt(SETTING_OPACITY_KEY, 100);

                if (opacity < 100 && originalRenderer != null) {
                    originalRenderer.render();
                }

                Draw.reset();
                if (opacity < 100) {
                    Draw.alpha(opacity / 100f);
                }

                drawScaled(region);
            } catch (Exception e) {
                Log.err(e);
            } finally {
                Draw.reset();
            }
        }

        @Override
        public void dispose() {
            if (texture != null) {
                texture.dispose();
            }
        }
    }

    /** Same as {@link CustomMenuRenderer} but cycles through decoded GIF frames instead of a single static image. */
    public static class GifMenuRenderer extends MenuRenderer implements WrapsMenuRenderer {
        private final Texture[] frames;
        private final TextureRegion[] regions;
        private final int[] delaysMs;
        private final int totalDurationMs;
        public final MenuRenderer originalRenderer;
        private float elapsedMs;

        public GifMenuRenderer(GifBackgroundLoader.Result gif, MenuRenderer originalRenderer) {
            super();
            this.frames = gif.frames;
            this.regions = new TextureRegion[frames.length];
            for (int i = 0; i < frames.length; i++) regions[i] = new TextureRegion(frames[i]);
            this.delaysMs = gif.delaysMs;
            this.totalDurationMs = gif.totalDurationMs();
            this.originalRenderer = originalRenderer;
        }

        /**
         * Конструктор MenuRenderer вызывает refresh(), который строит весь 3D-фон меню (кэш плиток + фреймбуфер теней),
         * а наш render() им не пользуется: каждое применение фона зря утекало бы видеопамятью. В конструкторе
         * (originalRenderer ещё null) не строим ничего; позже (смена cursednesslevel в настройках) обновляем
         * настоящий фон под картинкой, а не этот пустой.
         */
        @Override
        public void refresh() {
            if (originalRenderer != null) {
                originalRenderer.refresh();
            }
        }

        @Override
        public MenuRenderer originalRenderer(){
            return originalRenderer;
        }

        private TextureRegion currentFrame() {
            elapsedMs = (elapsedMs + Time.delta / Time.toSeconds * 1000f) % totalDurationMs;
            float acc = 0;
            for (int i = 0; i < delaysMs.length; i++) {
                acc += delaysMs[i];
                if (elapsedMs < acc || i == delaysMs.length - 1) return regions[i];
            }
            return regions[0];
        }

        @Override
        public void render() {
            try {
                int opacity = Core.settings.getInt(SETTING_OPACITY_KEY, 100);

                if (opacity < 100 && originalRenderer != null) {
                    originalRenderer.render();
                }

                Draw.reset();
                if (opacity < 100) {
                    Draw.alpha(opacity / 100f);
                }

                drawScaled(currentFrame());
            } catch (Exception e) {
                Log.err(e);
            } finally {
                Draw.reset();
            }
        }

        @Override
        public void dispose() {
            for (Texture t : frames) t.dispose();
        }
    }
}
