package qol.autopossess;

import arc.Core;
import arc.Events;
import arc.struct.Seq;
import arc.util.Time;
import mindustry.ai.types.LogicAI;
import mindustry.game.EventType.Trigger;
import mindustry.game.EventType.UnitCreateEvent;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Unit;
import mindustry.ui.dialogs.BaseDialog;
import mindustry.ui.dialogs.SettingsMenuDialog.SettingsTable;
import qol.core.ButtonSetting;
import qol.core.Feature;
import qol.core.SafeSettings;

import static mindustry.Vars.player;
import static mindustry.Vars.ui;

/**
 * Auto-possesses a unit as soon as one becomes available, ported from QoL Control's {@code !trace}.
 * Two independently-usable rules, checked in this order: (1) a specific configured type ("target") is
 * grabbed the instant one is free; (2) failing that, the best (lowest-index) type present in a priority
 * list replaces whatever you're currently piloting if it outranks it. Both react to freshly spawned
 * units immediately via {@link UnitCreateEvent} AND re-scan every {@link #RETRY_MS} (throttled by real
 * time, not ticks, so it behaves the same at any game speed) - the event alone would miss a unit that
 * was already on the field and only became free later (its pilot died, its logic-processor controller
 * was removed, ...).
 */
public class AutoPossessFeature implements Feature{
    static final String DEFAULT_PRIORITY = "vanquish,reign,vela,arkyid,scepter,obviate,precept,avert,quasar,cleroi";
    static final long RETRY_MS = 250;

    long nextTryAt;

    @Override
    public String id(){
        return "auto-possess";
    }

    @Override
    public String titleKey(){
        return "qol.feature.auto-possess.title";
    }

    @Override
    public void init(){
        Events.on(UnitCreateEvent.class, e -> {
            if(!isEnabled() || player == null || e.unit.team != player.team() || !isFree(e.unit)) return;
            //a fresh spawn should only preempt whatever's currently piloted if it actually outranks it -
            //reuse the same scan the tick loop does rather than possessing blindly
            scan();
        });

        Events.on(WorldLoadEvent.class, e -> nextTryAt = 0);

        Events.run(Trigger.update, () -> {
            if(!isEnabled() || player == null || Time.millis() < nextTryAt) return;
            //раньше nextTryAt ставился только в possess(): без цели scan() (split/trim/new Seq + проход по Groups.unit)
            //гонялся КАЖДЫЙ тик, вопреки документации "пересканировать раз в RETRY_MS"
            nextTryAt = Time.millis() + RETRY_MS;
            scan();
        });
    }

    @Override
    public void buildSettings(SettingsTable table){
        table.pref(new ButtonSetting("qol-auto-possess-configure", this::showConfigDialog));
    }

    static boolean isFree(Unit u){
        return u != null && !u.dead() && u.getPlayer() == null && !(u.controller() instanceof LogicAI);
    }

    void scan(){
        String target = SafeSettings.getString("autopossess-target", "").trim();
        if(!target.isEmpty()){
            Unit current = player.unit();
            if(current != null && !current.dead() && current.type.name.equals(target)) return;

            for(Unit u : Groups.unit){
                if(u.team != player.team() || !isFree(u) || !u.type.name.equals(target)) continue;
                possess(u);
                return;
            }
        }

        Seq<String> priority = loadPriority();
        if(priority.isEmpty()) return;

        Unit current = player.unit();
        int bestIdx = (current != null && !current.dead()) ? priority.indexOf(current.type.name) : -1;
        if(bestIdx == -1) bestIdx = Integer.MAX_VALUE;

        Unit best = null;
        for(Unit u : Groups.unit){
            if(u.team != player.team() || !isFree(u)) continue;
            int idx = priority.indexOf(u.type.name);
            if(idx != -1 && idx < bestIdx){
                best = u;
                bestIdx = idx;
            }
        }
        if(best != null) possess(best);
    }

    void possess(Unit u){
        Call.unitControl(player, u);
        Core.camera.position.set(u.x, u.y);
        nextTryAt = Time.millis() + RETRY_MS;
        ui.showInfoToast(Core.bundle.format("qol.auto-possess.possessed", u.type.localizedName), 2f);
    }

    static Seq<String> loadPriority(){
        String raw = SafeSettings.getString("autopossess-priority", DEFAULT_PRIORITY);
        Seq<String> out = new Seq<>();
        for(String s : raw.split(",")){
            s = s.trim();
            if(!s.isEmpty()) out.add(s);
        }
        return out;
    }

    void showConfigDialog(){
        BaseDialog dialog = new BaseDialog(Core.bundle.get("qol.auto-possess.dialog.title", "Auto-Possess"));
        dialog.addCloseButton();

        String[] target = {SafeSettings.getString("autopossess-target", "")};
        String[] priorityText = {SafeSettings.getString("autopossess-priority", DEFAULT_PRIORITY)};

        dialog.cont.pane(t -> {
            t.defaults().left().pad(4f);
            t.add(Core.bundle.get("qol.auto-possess.dialog.hint", "")).wrap().width(480f).row();

            t.add(Core.bundle.get("qol.auto-possess.dialog.target", "Target unit type (exact name, blank = unused):")).row();
            t.field(target[0], v -> target[0] = v.trim()).width(480f).row();

            t.add(Core.bundle.get("qol.auto-possess.dialog.priority", "Priority list (comma-separated, best first):")).padTop(10f).row();
            t.area(priorityText[0], v -> priorityText[0] = v).size(480f, 120f).row();
        }).width(520f).height(320f).row();

        dialog.cont.button(Core.bundle.get("qol.auto-possess.dialog.save", "Save"), () -> {
            Core.settings.put("autopossess-target", target[0]);
            Core.settings.put("autopossess-priority", priorityText[0]);
            dialog.hide();
        }).size(200f, 50f).padTop(10f);

        dialog.show();
    }
}
