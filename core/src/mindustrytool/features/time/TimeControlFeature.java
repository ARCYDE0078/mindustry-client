package mindustrytool.features.time;

import java.util.Optional;

import arc.Core;
import arc.Events;
import arc.graphics.Color;
import arc.input.KeyCode;
import arc.math.Mathf;
import arc.scene.event.InputEvent;
import arc.scene.event.InputListener;
import arc.scene.event.Touchable;
import arc.scene.ui.Dialog;
import arc.scene.ui.Image;
import arc.scene.ui.layout.Table;
import arc.util.Log;
import arc.util.Time;
import mindustry.Vars;
import mindustry.game.EventType;
import mindustry.gen.Icon;
import mindustry.gen.Tex;
import mindustry.graphics.Pal;
import mindustry.ui.Styles;
import mindustrytool.Utils;
import mindustrytool.features.Feature;
import mindustrytool.features.FeatureMetadata;

public class TimeControlFeature extends Table implements Feature {

    private float selected = 1f;
    private boolean doubleSpeed = false;

    private static final float[] SPEEDS = { 0.125f, 0.5f, 1f, 4f, 16f };

    /** Верхняя граница шага симуляции за кадр: x16 при 60 fps = 16, запас до 32 на просадках кадра. */
    private static final float MAX_STEP = 32f;

    /**
     * Подменяли ли мы провайдер delta. Без флага onDisable затирал бы чужой провайдер (mi2u FpsController
     * тоже зовёт Time.setDeltaProvider), даже если ускорение мы вообще не включали.
     */
    private boolean providerApplied = false;

    /* перф: metadata неизменяема, а isEnabled()/UI зовут getMetadata() каждый кадр -
     * ленивая мемоизация вместо новой цепочки builder+Drawable на каждый вызов */
    private FeatureMetadata cachedMetadata;

    @Override
    public FeatureMetadata getMetadata() {
        if (cachedMetadata == null)
            cachedMetadata = FeatureMetadata.builder()
                .name("@feature.time-control")
                .description("@feature.time-control.description")
                .icon(Icon.rotate)
                .order(1)
                .enabledByDefault(false)
                .build();
        return cachedMetadata;
    }

    @Override
    public void init() {
        touchable = Touchable.childrenOnly;

        setPosition(TimeControlConfig.x(), TimeControlConfig.y());
        visible(() -> Vars.ui.hudfrag.shown && isEnabled() && !Vars.net.client());

        Events.on(EventType.ResizeEvent.class, e -> rebuild());

        // На сервере чужое время менять нельзя: ускорение рассинхронизирует клиент (а в HUD панель уже скрыта),
        // и старый провайдер продолжал бы крутить мир с множителем после захода в сеть - сбрасываем при загрузке мира.
        Events.on(EventType.WorldLoadEvent.class, e -> {
            if (Vars.net.client()) {
                resetSpeed();
            }
        });

        Core.app.post(this::rebuild);
    }

    void rebuild() {
        clear();

        Table container = new Table();
        container.background(Styles.black6);
        container.touchable = Touchable.enabled;

        float buttonSize = 48f;
        float margin = 6f;

        container.button(Icon.move, Styles.clearNonei, () -> {
        })
                .size(buttonSize)
                .margin(margin)
                .get()
                .addListener(new InputListener() {
                    float lastX, lastY;

                    @Override
                    public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
                        lastX = x;
                        lastY = y;
                        return true;
                    }

                    @Override
                    public void touchDragged(InputEvent event, float x, float y, int pointer) {
                        try {
                            moveBy(x - lastX, y - lastY);

                            float sw = Core.graphics.getWidth();
                            float sh = Core.graphics.getHeight();

                            x = Mathf.clamp(TimeControlFeature.this.x, 0, sw - 40f);
                            y = Mathf.clamp(TimeControlFeature.this.y, 0, sh - 40f);

                            TimeControlConfig.x(x);
                            TimeControlConfig.y(y);

                        } catch (Exception e) {
                            Log.err(e);
                        }
                    }
                });

        // separator
        Image sep = new Image(Tex.whiteui);
        sep.setColor(Pal.accent);
        container.add(sep).width(2f).fillY();

        Table content = new Table();

        for (float speed : SPEEDS) {
            Color color = speed == selected ? (doubleSpeed ? Pal.accent : Color.white) : Pal.gray;
            String speedString = "[#" + color.toString() + "]"
                    + String.valueOf((doubleSpeed && speed == selected) ? (speed >= 1 ? speed * 2f : speed / 2) : speed)
                    + "x";

            content.button(speedString, Styles.cleart, () -> {
                if (speed == selected) {
                    if (doubleSpeed) {
                        doubleSpeed = false;
                        applySpeed(speed);
                    } else {
                        doubleSpeed = true;
                        applySpeed(speed >= 1 ? speed * 2f : speed / 2);
                    }
                } else {
                    doubleSpeed = false;
                    applySpeed(speed);
                }
                selected = speed;
                rebuild();
            })
                    .wrapLabel(false)
                    .height(buttonSize)
                    .width(buttonSize * 1.5f)
                    .margin(margin);
        }

        container.add(content);

        add(container);
        pack();
    }

    private void applySpeed(float multipler) {
        if (Vars.net.client()) {
            return;
        }

        providerApplied = true;
        // NaN/Inf бывают при первом кадре после сворачивания окна; без зажима мир уходил в NaN и не возвращался
        Time.setDeltaProvider(() -> {
            float result = Core.graphics.getDeltaTime() * 60f * multipler;
            return (Float.isNaN(result) || Float.isInfinite(result)) ? 1f : Mathf.clamp(result, 0.0001f, MAX_STEP);
        });

        Log.info("Time speed set to: " + multipler);
    }

    /** Вернуть скорость 1x и ванильный провайдер delta (с тем же зажимом, что в ClientLauncher). */
    private void resetSpeed() {
        selected = 1f;
        doubleSpeed = false;

        if (providerApplied) {
            providerApplied = false;
            Time.setDeltaProvider(() -> {
                float result = Core.graphics.getDeltaTime() * 60f;
                return (Float.isNaN(result) || Float.isInfinite(result)) ? 1f
                        : Mathf.clamp(result, 0.0001f, Vars.maxDeltaClient);
            });
        }

        if (parent != null) {
            rebuild();
        }
    }

    @Override
    public void onEnable() {
        if (Vars.ui != null && Vars.ui.hudGroup != null) {
            remove();

            name = "time-control-hud";

            Core.app.post(() -> Vars.ui.hudGroup.addChild(this));
        }
    }

    @Override
    public void onDisable() {
        // выключенная фича не должна оставлять мир ускоренным: панели нет, а вернуть 1x нечем
        resetSpeed();
        remove();
    }

    @Override
    public Optional<Dialog> setting() {
        return Optional.empty();
    }
}
