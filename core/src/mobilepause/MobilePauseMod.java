package mobilepause;

import arc.Core;
import arc.Events;
import arc.files.Fi;
import arc.math.Mathf;
import arc.scene.ui.layout.Table;
import arc.util.Log;
import mindustry.Vars;
import mindustry.client.ui.ModsSettings;
import mindustry.game.EventType.ClientLoadEvent;
import mindustry.gen.Icon;
import mindustry.ui.Styles;

import static mindustry.Vars.*;

/**
 * Порт мода "Mobile Pause" (Promiha27/ARCYDE, mobile-pause v1.2) - см. mod.hjson оригинала в
 * {@code ~/MindustryMods/MobilePause}.
 * <p>
 * Сама кнопка паузы мода не портирована - она была лишь обходом бага движка (мобильный ряд
 * "select" в {@code HudFragment.java} проверял {@code net.active()}, из-за чего кнопка паузы
 * превращалась в переключатель списка игроков даже когда САМ хостишь, а не только когда
 * подключён к чужому серверу). Раз это Java-код этого же форка, а не сторонний мод - пофикшено
 * напрямую в {@code HudFragment.java} (условие заменено на {@code net.client()}, как у десктопной
 * паузы в {@code Control.java}), и отдельная кнопка-оверлей больше не нужна.
 * <p>
 * Портирована только оставшаяся часть - кнопка скриншота всей карты рядом с (теперь работающей
 * штатно) кнопкой паузы, т.к. на мобиле нет клавиши {@code Binding.screenshot}. Зовёт ваниль-метод
 * {@link mindustry.core.Renderer#takeMapScreenshot()} - он уже сам проверяет память и путь
 * сохранения ({@link Vars#screenshotDirectory}), ничего заново не рендерим.
 * <p>
 * Ключи настроек - оригинальные из мода ({@code mhe-pause-x/y}, {@code mhe-screenshot-folder}),
 * чтобы у тех, кто раньше ставил его как обычный мод, позиция и папка скриншотов не сбросились
 * при переходе на этот вшитый порт.
 */
public class MobilePauseMod{

    public static final String name = "mobile-pause";

    private static final float BUTTON_SIZE = 64f;

    public MobilePauseMod(){
        //self-disable: настоящий Mobile Pause установлен как обычный мод - не дублируем кнопку скриншота.
        if(Vars.mods.locateMod(name) != null){
            Log.info("[mobilepause] External Mobile Pause mod is also loaded - baked-in copy is standing down.");
            return;
        }

        Events.on(ClientLoadEvent.class, e -> {
            try{
                init();
            }catch(Throwable t){
                Log.err("[mobilepause] failed to initialize", t);
            }
        });
    }

    void init(){
        applyScreenshotDir();
        buildScreenshotButton();
        addSettings();
    }

    /** Папка скриншотов: пусто - дефолт движка, путь с "/" (или "C:\" на десктопе) - абсолютный, иначе - относительно {@link Vars#dataDirectory}. */
    void applyScreenshotDir(){
        String sub = Core.settings.getString("mhe-screenshot-folder", "").trim();
        Fi dir = resolveScreenshotDir(sub);
        dir.mkdirs();
        screenshotDirectory = dir;
    }

    Fi resolveScreenshotDir(String sub){
        if(sub.isEmpty()) return dataDirectory.child("screenshots/");
        if(sub.charAt(0) == '/' || (sub.length() > 1 && sub.charAt(1) == ':')) return Fi.get(sub);
        return dataDirectory.child(sub);
    }

    void buildScreenshotButton(){
        Table table = new Table();
        table.setSize(BUTTON_SIZE, BUTTON_SIZE);
        table.defaults().size(BUTTON_SIZE);

        table.button(Icon.image, Styles.clearNonei, renderer::takeMapScreenshot)
        .name("mobilepause-screenshot").size(BUTTON_SIZE)
        .update(i -> {
            //позиция - доля ширины/высоты экрана (те же слайдеры, что раньше двигали кнопку
            //паузы мода), пересчитывается каждый кадр, чтобы не съезжать при повороте экрана.
            float w = Core.graphics.getWidth(), h = Core.graphics.getHeight();
            float fracX = Core.settings.getInt("mhe-pause-x", 93) / 100f;
            float fracY = Core.settings.getInt("mhe-pause-y", 88) / 100f;
            table.setPosition(
                Mathf.clamp(fracX * w - BUTTON_SIZE / 2f - BUTTON_SIZE - 8f, 0f, w - BUTTON_SIZE),
                Mathf.clamp(fracY * h - BUTTON_SIZE / 2f, 0f, h - BUTTON_SIZE)
            );
        })
        .visible(() -> Vars.mobile && Vars.state.isGame());

        Core.app.post(() -> Vars.ui.hudGroup.addChild(table));
    }

    void addSettings(){
        ModsSettings.section("modsec-mobilepause", t -> {
            t.sliderPref("mhe-pause-x", 93, 0, 100, 1, i -> i + "%");
            t.sliderPref("mhe-pause-y", 88, 0, 100, 1, i -> i + "%");
            t.textPref("mhe-screenshot-folder", "", value -> {
                applyScreenshotDir();
                Vars.ui.showInfoFade("Screenshots -> " + screenshotDirectory.absolutePath());
            });
        });
    }
}
