package mindustrytool;

import arc.Core;
import arc.Events;
import arc.input.KeyBind;
import arc.input.KeyCode;
import arc.struct.Seq;
import mindustry.game.EventType.Trigger;

public class MdtKeybinds {

    public static KeyBind mapBrowserKb = KeyBind.add("mapBrowser", KeyCode.unset, "MindustryTool"),
            schematicBrowserKb = KeyBind.add("schematicBrowser", KeyCode.unset, "MindustryTool"),
            patchBrowserKb = KeyBind.add("patchBrowser", KeyCode.unset, "MindustryTool"),
            chatKb = KeyBind.add("chatOverlay", KeyCode.unset, "MindustryTool");

    /**
     * Порт: вшитый код регистрирует бинды ПОСЛЕ Vars.loadSettings() (который уже
     * прогнал KeyBind.load() по существующим), поэтому сохранённые значения
     * перечитываем вручную — та же оговорка, что у mi2u/agzam4/scheme.
     */
    public static void load() {
        mapBrowserKb.load();
        schematicBrowserKb.load();
        patchBrowserKb.load();
        chatKb.load();
    }

    /* перф: один Trigger.update-диспатчер на все кейбинды мода вместо отдельного листенера
     * (и отдельной проверки Core.scene.hasField) на каждую фичу; неиспользуемый
     * addFeatureKeyBind(feature, keyBind) убран при консолидации */
    private static final Seq<KeyBindAction> actions = new Seq<>();
    private static boolean dispatcherHooked = false;

    /** Регистрирует действие на отпускание кейбинда (срабатывает только когда нет фокуса в текстовом поле). */
    public static void onKeyRelease(KeyBind keyBind, Runnable action) {
        actions.add(new KeyBindAction(keyBind, action));
        if (!dispatcherHooked) {
            dispatcherHooked = true;
            Events.run(Trigger.update, () -> {
                if (Core.scene.hasField()) return;
                for (int i = 0; i < actions.size; i++) {
                    KeyBindAction a = actions.get(i);
                    if (Core.input.keyRelease(a.keyBind)) a.action.run();
                }
            });
        }
    }

    private static class KeyBindAction {
        final KeyBind keyBind;
        final Runnable action;

        KeyBindAction(KeyBind keyBind, Runnable action) {
            this.keyBind = keyBind;
            this.action = action;
        }
    }
}
