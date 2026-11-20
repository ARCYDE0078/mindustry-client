package eui.interact;

import arc.Core;
import arc.Events;
import arc.input.KeyBind;
import arc.input.KeyCode;
import arc.math.Mathf;
import arc.scene.actions.Actions;
import arc.scene.ui.Label;
import arc.util.Align;
import mindustry.game.EventType.ClientLoadEvent;
import mindustry.game.EventType.Trigger;

import static mindustry.Vars.state;

/**
 * Alt + a rebindable pair of keys (default {@code =}/{@code -}) adjusts "eui-action-delay" (the pause
 * between auto-fill/auto-unit actions, see {@link InteractTimer}) right in-game, without opening
 * Settings. Same 0-3000ms/25ms-step range as the slider in the settings category.
 * <p>
 * Ported from interact/action-delay-hotkey.js. The JS version had to go out of its way to register this
 * through {@code KeyBind.add(name, KeybindValue, category)} with an explicitly constructed
 * {@link KeyBind.Axis} - Rhino couldn't otherwise tell a bare {@code KeyCode} apart from this client
 * fork's extra {@code KeyBind.add(String, KeyCode, KeyCode...)} overload and threw instead of picking
 * one (see memory: reference-rhino-overload-ambiguity). That's purely a Rhino dynamic-dispatch problem -
 * the Java compiler resolves overloads statically, so a bare {@code KeyCode} argument here would compile
 * to the exact same {@code add(String, KeybindValue, String)} call unambiguously. The explicit
 * {@code new KeyBind.Axis(...)} wrapper is kept anyway only because it's what the resulting default
 * keybind actually is (a single key, not a min/max pair) - not to work around anything.
 */
public class ActionDelayHotkey{
    private static final int MIN_DELAY = 0;
    private static final int MAX_DELAY = 3000;
    private static final int STEP = 25;

    private static final float FEEDBACK_VISIBLE_TIME = 0.8f; //seconds, full visibility
    private static final float FEEDBACK_FADE_TIME = 0.4f; //seconds, fade out

    //sonka: та же категория, что у EuiBinding ("extended-ui" -> category.extended-ui.name) - раньше литерал
    //"Extended UI++" давал вторую безымянную секцию в «Управлении»
    private static final String KEYBIND_CATEGORY = "extended-ui";

    public static final KeyBind increaseBind = KeyBind.add("eui-action-delay-increase", new KeyBind.Axis(KeyCode.equals), KEYBIND_CATEGORY);
    public static final KeyBind decreaseBind = KeyBind.add("eui-action-delay-decrease", new KeyBind.Axis(KeyCode.minus), KEYBIND_CATEGORY);

    private Label feedbackLabel;

    public ActionDelayHotkey(){
        Events.on(ClientLoadEvent.class, e -> {
            //plain Label, no Table/background - no frame around it
            feedbackLabel = new Label("");
            feedbackLabel.color.a = 0; //invisible until the first keypress
            Core.scene.add(feedbackLabel);

            feedbackLabel.update(() -> feedbackLabel.setPosition(Core.graphics.getWidth() / 2f, 200, Align.center));
        });

        Events.run(Trigger.update, this::update);
    }

    void update(){
        if(feedbackLabel == null) return;
        if(!Core.input.alt()) return;
        if(!Core.settings.getBool("eui-ActionDelayHotkey", true)) return;
        if(!state.isGame() || Core.scene.hasDialog() || Core.scene.hasKeyboard()) return;

        //keyTap ("just pressed"), not keyDown ("held") - otherwise the value would fly by several
        //hundred ms per single keypress. Alt stays a separate condition from the bind itself (the bind
        //is rebindable, but can't itself be bound to a modifier) - that was the original idea: "alt +
        //something, without opening settings"
        if(Core.input.keyTap(increaseBind)) adjustDelay(STEP);
        else if(Core.input.keyTap(decreaseBind)) adjustDelay(-STEP);
    }

    int currentDelay(){
        return Core.settings.getInt("eui-action-delay", 500);
    }

    void adjustDelay(int delta){
        int value = Mathf.clamp(currentDelay() + delta, MIN_DELAY, MAX_DELAY);
        Core.settings.putInt("eui-action-delay", value);
        showFeedback(value);
    }

    void showFeedback(int value){
        if(feedbackLabel == null) return;
        feedbackLabel.setText(Core.bundle.format("eui.action-delay.adjust-feedback", value));
        feedbackLabel.pack();
        feedbackLabel.clearActions();
        feedbackLabel.color.a = 1;
        feedbackLabel.actions(Actions.delay(FEEDBACK_VISIBLE_TIME), Actions.fadeOut(FEEDBACK_FADE_TIME));
    }
}
