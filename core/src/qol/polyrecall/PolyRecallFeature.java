package qol.polyrecall;

import arc.Events;
import arc.math.geom.Vec2;
import arc.struct.IntMap;
import arc.struct.IntSeq;
import arc.util.Time;
import mindustry.ai.UnitCommand;
import mindustry.game.EventType.Trigger;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.gen.Unit;
import mindustry.ai.types.CommandAI;
import mindustry.ui.dialogs.SettingsMenuDialog.SettingsTable;
import qol.assistshare.AssistShareFeature;
import qol.core.Feature;
import qol.core.ManualControl;
import qol.core.SafeSettings;
import qol.core.UnitClaims;

import static mindustry.Vars.player;
import static mindustry.Vars.state;
import static mindustry.Vars.tilesize;

/**
 * «Возврат поли»: юнит с командой «помогать» (assist), который оказался далеко от игрока, иногда тупит и не
 * летит за ним сразу (BuilderAI в режиме помощи цепляется за ближайшего строителя, а ближайший - не обязательно
 * ты). Фича в два шага через те же RPC, что и обычный интерфейс команд: отправляет такого юнита командой
 * «двигаться» к игроку, а как только он долетел (или вышло время) - возвращает ему «помогать».
 * <p>
 * Что не трогаем:
 * <ul>
 *   <li>юнитов, которыми управляет Assist Split ({@link AssistShareFeature#managedExpected}) - у них своя
 *       парковка у игрока;</li>
 *   <li>юнитов под {@link UnitClaims} (core-heal, poly-split) - у них уже есть хозяин;</li>
 *   <li>юнитов, которым игрок только что отдал приказ вручную ({@link ManualControl#isRecent}) - и во время
 *       возврата: если юнит уже не на нашей «двигаться» (игрок перехватил), мы его отпускаем и «помогать» не шлём;</li>
 *   <li>юнитов, которые рядом с ДРУГИМ строителем команды (дальше порога только от ВСЕХ строителей) - иначе
 *       мы бы уводили помощников от тиммейта;</li>
 *   <li>не летающих (шагающим/прыгающим понадобился бы свой буст-манёвр).</li>
 * </ul>
 */
public class PolyRecallFeature implements Feature{
    static final float SCAN_INTERVAL_TICKS = 30f;
    /** Приказ ещё летит до сервера и назад: пока не прошло столько мс, чужую «не нашу» команду не считаем перехватом. */
    static final long COMMAND_SYNC_GRACE_MS = 2000L;
    /** Достаточно близко к игроку, чтобы вернуть «помогать». */
    static final float ARRIVE_TILES = 8f;
    /** Игрок ушёл от точки приказа дальше - обновляем точку. */
    static final float RETARGET_TILES = 15f;
    /** Предохранитель: через столько мс возврата «помогать» возвращаем в любом случае. */
    static final long RECALL_TIMEOUT_MS = 30_000L;

    static class Recall{
        long sentMs;
        long retargetMs;
        final Vec2 target = new Vec2();
    }

    final IntMap<Recall> recalling = new IntMap<>();
    final IntSeq goIds = new IntSeq();
    final IntSeq restoreIds = new IntSeq();
    final IntSeq dropIds = new IntSeq();
    float timer;

    @Override
    public String id(){
        return "poly-recall";
    }

    @Override
    public String titleKey(){
        return "qol.feature.poly-recall.title";
    }

    @Override
    public void init(){
        Events.on(WorldLoadEvent.class, e -> {
            recalling.clear();
            timer = 0f;
        });
        Events.run(Trigger.update, this::update);
    }

    @Override
    public void buildSettings(SettingsTable table){
        table.sliderPref("polyrecall-dist", 30, 10, 150, 5, v -> v + " " + arc.Core.bundle.get("client.sonka.antisplash.tiles"));
    }

    float recallDist(){
        return SafeSettings.getInt("polyrecall-dist", 30) * (float)tilesize;
    }

    void update(){
        if(!state.isGame() || player == null){
            return;
        }
        timer += Time.delta;
        if(timer < SCAN_INTERVAL_TICKS) return;
        timer = 0f;

        Unit me = player.dead() ? null : player.unit();
        if(!isEnabled() || me == null){
            //выключили или умерли: тех, кого уже отправили, вернём на «помогать», чтобы не оставить их стоять на «двигаться»
            if(recalling.size > 0) finishAll();
            return;
        }

        long now = Time.millis();
        float far = recallDist();
        float far2 = far * far;

        goIds.clear();
        restoreIds.clear();
        dropIds.clear();

        //1) уже отправленные: долетели / вышло время / игрок перехватил / юнит погиб
        for(IntMap.Entry<Recall> e : recalling){
            Unit u = Groups.unit.getByID(e.key);
            Recall r = e.value;
            if(u == null || u.dead || !(u.controller() instanceof CommandAI ai)){
                dropIds.add(e.key);
                continue;
            }
            boolean onOurMove = ai.currentCommand() == UnitCommand.moveCommand;
            if(!onOurMove && now - r.sentMs > COMMAND_SYNC_GRACE_MS){
                dropIds.add(e.key); //перехватил игрок (или другая фича) - отпускаем, «помогать» не навязываем
                continue;
            }
            if(u.within(me, ARRIVE_TILES * tilesize) || now - r.sentMs > RECALL_TIMEOUT_MS){
                restoreIds.add(e.key);
                continue;
            }
            //игрок ушёл далеко от точки, куда мы целили - обновляем цель
            if(now - r.retargetMs > COMMAND_SYNC_GRACE_MS && me.dst2(r.target.x, r.target.y) > RETARGET_TILES * RETARGET_TILES * tilesize * tilesize){
                r.target.set(me.x, me.y);
                r.retargetMs = now;
                Call.commandUnits(player, new int[]{e.key}, null, null, new Vec2(me.x, me.y), false, true);
            }
        }
        for(int i = 0; i < dropIds.size; i++) recalling.remove(dropIds.get(i));
        if(restoreIds.size > 0){
            Call.setUnitCommand(player, restoreIds.toArray(), UnitCommand.assistCommand);
            for(int i = 0; i < restoreIds.size; i++) recalling.remove(restoreIds.get(i));
        }

        //2) новые: assist-юниты, далеко от всех строителей команды
        long hold = SafeSettings.getInt("assistshare-manual-hold", 20) * 1000L;
        for(Unit u : Groups.unit){
            if(u.team != player.team() || u.dead || !u.type.flying) continue;
            if(recalling.containsKey(u.id)) continue;
            if(!(u.controller() instanceof CommandAI ai) || ai.currentCommand() != UnitCommand.assistCommand) continue;
            if(AssistShareFeature.managedExpected(u.id) != null || UnitClaims.isClaimed(u.id)) continue;
            if(hold > 0 && ManualControl.isRecent(u.id, hold)) continue;
            if(u.dst2(me) <= far2) continue;
            if(nearOtherBuilder(u, far2)) continue;

            Recall r = new Recall();
            r.sentMs = now;
            r.retargetMs = now;
            r.target.set(me.x, me.y);
            recalling.put(u.id, r);
            goIds.add(u.id);
        }
        if(goIds.size > 0){
            Call.commandUnits(player, goIds.toArray(), null, null, new Vec2(me.x, me.y), false, true);
        }
    }

    /** Есть ли другой живой строитель нашей команды ближе порога - тогда юнит помогает ему, не трогаем. */
    boolean nearOtherBuilder(Unit u, float far2){
        for(Player p : Groups.player){
            if(p == player || p.dead() || p.team() != player.team() || !p.isBuilder()) continue;
            Unit pu = p.unit();
            if(pu != null && u.dst2(pu) <= far2) return true;
        }
        return false;
    }

    /** Фича выключена/игрок мёртв: отпустить всех отправленных обратно на «помогать». */
    void finishAll(){
        IntSeq ids = new IntSeq();
        for(IntMap.Entry<Recall> e : recalling){
            Unit u = Groups.unit.getByID(e.key);
            if(u != null && !u.dead && u.controller() instanceof CommandAI ai && ai.currentCommand() == UnitCommand.moveCommand) ids.add(e.key);
        }
        recalling.clear();
        if(ids.size > 0 && player != null && !state.isMenu()) Call.setUnitCommand(player, ids.toArray(), UnitCommand.assistCommand);
    }
}
