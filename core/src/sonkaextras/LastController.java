package sonkaextras;

import arc.Core;
import arc.Events;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.graphics.g2d.Font;
import arc.graphics.g2d.GlyphLayout;
import arc.scene.ui.layout.Scl;
import arc.struct.IntMap;
import arc.struct.IntSeq;
import arc.util.Align;
import arc.util.Time;
import arc.util.Tmp;
import arc.util.pooling.Pools;
import mindustry.game.EventType.*;
import mindustry.gen.Player;
import mindustry.gen.Unit;
import mindustry.graphics.Drawf;
import mindustry.graphics.Layer;
import mindustry.ui.Fonts;

import static mindustry.Vars.*;

/**
 * Метка над юнитом с ником игрока, который управлял им ПОСЛЕДНИМ (идея sonka): кто угнал/переставил
 * юнита - видно и после того, как игрок его отпустил. Пока юнит под прямым управлением, ваниль сама
 * рисует живой ник ({@code PlayerComp.drawName}) - наша метка показывается только после отпускания.
 * <p>
 * Главный сценарий (уточнение sonka): RTS-ПРИКАЗЫ ПАЧКОЙ - remote-тела {@code InputHandler.commandUnits}
 * и {@code setUnitCommand} зовут {@link #record} на каждого затронутого юнита (на хосте - для всех,
 * на чужом сервере - для всех других игроков, см. ниже). Плюс прямое вселение:
 * <ul>
 * <li>{@link UnitControlEvent} - стреляет {@code InputHandler.unitControl} (remote, called=server,
 *     forward=true): на хосте/в одиночке срабатывает для всех, на чужом сервере приходит для ВСЕХ
 *     ДРУГИХ игроков (сервер не пересылает пакет отправителю - свои захваты через событие на клиенте
 *     не видны).</li>
 * <li>поэтому для ЛОКАЛЬНОГО игрока - прямое отслеживание смены {@code player.unit()} раз в тик:
 *     новый юнит, не заспавненный ядром ({@code spawnedByCore}), = мы его взяли под контроль.</li>
 * </ul>
 * Ник и цвет копируются в момент захвата (игрок может выйти с сервера - метка останется).
 * <p>
 * ВАЖНО (урок первой версии): юнит хранится ПРЯМОЙ ССЫЛКОЙ, а не ищется через
 * {@code Groups.unit.getByID} - на этом форке реестр Groups бывает неполным (фог/кэширование, см.
 * аналогичные заметки в qol), и поиск по id возвращал null, из-за чего метка гасла мгновенно.
 * <p>
 * Настройки: {@link #settingKey} (вкл/выкл), {@link #timeoutKey} - через сколько секунд метка гаснет
 * (0 = пока юнит жив). Рисуется в стиле ванильного ника (Fonts.outline, scale 0.25, Layer.playerName),
 * приглушённо, с камера-куллингом и без аллокаций в кадре.
 */
public final class LastController{
    public static final String settingKey = "sonka-lastcontroller";
    public static final String timeoutKey = "sonka-lastcontroller-timeout";
    /** Показывать ли метки СВОИХ приказов (на своей армии это шум; на чужих серверах свои и так не приходят). */
    public static final String selfKey = "sonka-lastcontroller-self";

    static class Entry{
        Unit unit;
        boolean self; //метка локального игрока (скрывается тоглом selfKey)
        String label;
        final Color color = new Color();
        long since;
    }

    static final IntMap<Entry> byUnit = new IntMap<>();
    static final IntSeq toRemove = new IntSeq();
    /** Последний известный юнит локального игрока - для детекта своих захватов на чужих серверах. */
    static Unit lastLocalUnit;

    private LastController(){
    }

    /** Вызывается из Main.kt до ClientLoadEvent - только вешает слушатели. */
    public static void init(){
        Events.on(UnitControlEvent.class, e -> {
            if(e.player != null && e.unit != null) record(e.player, e.unit);
        });

        Events.run(Trigger.update, () -> {
            if(player == null || state.isMenu()) return;
            Unit u = player.unit();
            if(u != lastLocalUnit){
                lastLocalUnit = u;
                //свежий юнит из ядра - респавн, а не захват; остальное = взяли под контроль
                if(u != null && !u.spawnedByCore) record(player, u);
            }
        });

        Events.on(UnitDestroyEvent.class, e -> {
            if(e.unit != null) byUnit.remove(e.unit.id);
        });
        Events.on(WorldLoadEvent.class, e -> {
            byUnit.clear();
            lastLocalUnit = null;
        });

        Events.run(Trigger.drawOver, LastController::draw);
    }

    /** Кэш готовых строк метки по нику: RTS-приказ на 200 юнитов не должен плодить 200 строк. */
    static final arc.struct.ObjectMap<String, String> labelCache = new arc.struct.ObjectMap<>();

    /**
     * Запомнить, что игрок {@code p} управлял юнитом. Зовётся из {@link UnitControlEvent}, локального
     * детекта своего юнита и из remote-тел {@code InputHandler.commandUnits}/{@code setUnitCommand}
     * (RTS-приказы пачкой - главный сценарий sonka).
     */
    public static void record(Player p, Unit unit){
        if(p == null || unit == null || p.name == null) return;
        Entry entry = byUnit.get(unit.id);
        if(entry == null){
            entry = new Entry();
            byUnit.put(unit.id, entry);
        }
        entry.unit = unit;
        entry.self = p == player;
        //"↺ Ник" - стрелка-возврат как знак "здесь побывал"
        String label = labelCache.get(p.name);
        if(label == null){
            label = "↺ " + p.name;
            labelCache.put(p.name, label);
        }
        entry.label = label;
        entry.color.set(p.color);
        entry.since = Time.millis();
    }

    /**
     * Когда юнит в последний раз получил приказ/управление от игрока ({@link Time#millis()}), 0 - не записан.
     * Источник «новой команды»: {@code record} зовётся из remote-тел commandUnits/setUnitCommand ровно на каждый приказ
     * игрока, а заводские точки сбора и сам ИИ его не вызывают - в отличие от скорости юнита или смены targetPos
     * (застрявший в толчее юнит «пытается долететь» всю игру, и по ним это выглядело как свежее командование).
     */
    public static long lastOrderAt(Unit unit){
        if(unit == null) return 0L;
        Entry entry = byUnit.get(unit.id);
        return entry == null || entry.unit != unit ? 0L : entry.since;
    }

    static void draw(){
        if(byUnit.isEmpty() || state.isMenu() || !Core.settings.getBool(settingKey, true)) return;

        long timeoutMs = Core.settings.getInt(timeoutKey, 0) * 1000L;
        boolean showSelf = Core.settings.getBool(selfKey, true);
        long now = Time.millis();
        Core.camera.bounds(Tmp.r1);

        Font font = Fonts.outline;
        GlyphLayout layout = Pools.obtain(GlyphLayout.class, GlyphLayout::new);
        boolean ints = font.usesIntegerPositions();
        font.setUseIntegerPositions(false);
        font.getData().setScale(0.25f / Scl.scl(1f));

        Draw.z(Layer.playerName);
        float z = Drawf.text();

        toRemove.clear();
        for(IntMap.Entry<Entry> kv : byUnit){
            Entry entry = kv.value;
            Unit unit = entry.unit;
            if(unit == null || !unit.isValid()){
                toRemove.add(kv.key);
                continue;
            }
            if(timeoutMs > 0 && now - entry.since > timeoutMs){
                toRemove.add(kv.key);
                continue;
            }
            //пока юнитом управляет игрок - ваниль рисует живой ник, наша метка не нужна
            if(unit.isPlayer()) continue;
            if(entry.self && !showSelf) continue;

            float clip = unit.type.hitSize * 2f;
            if(!Tmp.r1.overlaps(unit.x - clip / 2f, unit.y - clip / 2f, clip, clip)) continue;
            if(unit.inFogTo(player.team())) continue;

            final float nameHeight = 11f;
            layout.setText(font, entry.label);

            Draw.color(0f, 0f, 0f, 0.25f);
            Fill.rect(unit.x, unit.y + nameHeight - layout.height / 2, layout.width + 2, layout.height + 3);
            Draw.color();
            //приглушённо: это след, а не живой игрок
            font.setColor(entry.color.r, entry.color.g, entry.color.b, 0.7f);
            font.draw(entry.label, unit.x, unit.y + nameHeight, 0, Align.center, false);
        }

        Draw.reset();
        Pools.free(layout);
        font.getData().setScale(1f);
        font.setColor(Color.white);
        font.setUseIntegerPositions(ints);
        Draw.z(z);

        for(int i = 0; i < toRemove.size; i++) byUnit.remove(toRemove.get(i));
    }
}
