package qol.zoomdisplay;

import arc.Events;
import arc.scene.ui.Label;
import arc.scene.ui.layout.Scl;
import arc.util.Strings;
import arc.util.Time;
import mindustry.game.EventType.Trigger;
import mindustry.ui.dialogs.SettingsMenuDialog.SettingsTable;
import qol.core.Feature;
import qol.core.HudToasts;

import static mindustry.Vars.renderer;

/**
 * Plain "2x" / "0.5x" toast (see {@link HudToasts}) - fully visible right after the zoom actually
 * changes, then fades out over {@link #FADE_TICKS} once it's been sitting on the same value for
 * {@link #HOLD_TICKS}.
 */
public class ZoomDisplayFeature implements Feature{
    static final float HOLD_TICKS = 90f; // fully visible for ~1.5s after the value last changed
    static final float FADE_TICKS = 40f; // then fades out over ~0.67s

    String lastText = "";
    float lastMult = Float.NaN;
    float idleTicks = HOLD_TICKS + FADE_TICKS; // start hidden - nothing's changed yet

    @Override
    public String id(){
        return "zoom-display";
    }

    @Override
    public String titleKey(){
        return "qol.feature.zoom-display.title";
    }

    @Override
    public void init(){
        Label label = HudToasts.addToast();

        Events.run(Trigger.update, () -> {
            if(!isEnabled()){
                label.color.a = 0f;
                return;
            }

            //строку и label.setText (он сбрасывает раскладку текста) трогаем только когда зум реально сменился:
            //раньше и то и другое делалось каждый тик
            boolean changed = false;
            float mult = zoomMult();
            if(mult != lastMult){
                lastMult = mult;
                String text = format(mult);
                if(!text.equals(lastText)){
                    lastText = text;
                    changed = true;
                }
            }

            if(changed){
                idleTicks = 0f;
                label.setText(lastText);
            }else{
                idleTicks += Time.delta;
            }

            label.color.a = idleTicks < HOLD_TICKS ? 1f : 1f - Math.min(1f, (idleTicks - HOLD_TICKS) / FADE_TICKS);
        });
    }

    @Override
    public void buildSettings(SettingsTable table){
    }

    static float zoomMult(){
        return renderer.getDisplayScale() / Scl.scl(4f);
    }

    static String format(float mult){
        return Strings.autoFixed(mult, 3) + "x";
    }

    static String zoomText(){
        return format(zoomMult());
    }
}
