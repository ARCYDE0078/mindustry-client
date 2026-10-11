package sonkaextras;

import arc.*;
import arc.math.*;
import arc.struct.*;
import arc.util.*;
import mindustry.core.GameState.State;
import mindustry.entities.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.input.*;
import mindustry.io.*;
import mindustry.io.SaveIO.SaveException;

import java.io.*;
import java.util.concurrent.*;
import java.util.zip.*;

import static mindustry.Vars.*;

/**
 * Отмотка времени в песочнице: хоткей ({@link Binding#rewindTime}) возвращает игру на N секунд назад
 * (N - слайдер {@link #secondsKey}), повторное нажатие уходит ещё дальше. Только одиночная игра в
 * режиме песочницы ({@code infiniteResources}, не кампания/редактор/сеть) и только десктоп.
 * <p>
 * <b>Как</b>: каждые {@link #baseStep} тиков (~1 с) делается полный снапшот мира через ту же
 * {@link SaveIO#write}, что и обычный сейв (то есть откат = загрузка сейва, со всеми его свойствами:
 * ничего, что не лежит в сейве, не откатывается - таймеры {@code Time.run}, планы стройки и т.п.).
 * Главный поток только сериализует в память, а сжатие (Deflater level 1) уходит в отдельный
 * поток - иначе на больших картах кадр со снапшотом был бы заметным фризом. Кольцо снапшотов
 * ограничено по времени ({@link #historyKey}) и по памяти ({@link #memoryCap}).
 * <p>
 * <b>Частота адаптивная</b>: если сериализация главного потока дорогая (большая карта), интервал
 * между снапшотами растёт, чтобы накладные расходы не превышали ~3% времени - откат тогда идёт к
 * ближайшему снапшоту не ближе N секунд назад (точность падает, а не FPS). Интервал не растёт
 * больше самого N: иначе откат на "5 секунд" мог бы улететь на минуту.
 * <p>
 * <b>Откат</b> ({@link #rewind}) выполняется отложенно ({@code Core.app.post}): загрузка мира
 * посреди {@code Trigger.update} трогала бы группы сущностей, по которым идёт обход. Снапшоты
 * новее выбранного выбрасываются (это "будущее" ушедшей ветки времени), а сжатия, ещё висящие в
 * потоке, обесцениваются счётчиком {@link #epoch}. Таймлайн - {@code state.tick}: он сохраняется в
 * сейве и растёт только вне паузы, то есть паузы в "N секунд" не входят.
 * <p>
 * Юнит игрока: он есть в сейве как обычный юнит, но после load без контроллера-игрока ядровой юнит
 * сам удаляется ({@code spawnedByCore}), и игрок оказался бы у ядра. Поэтому id юнита запоминается
 * при снапшоте и после загрузки игрок снова садится в ТОТ ЖЕ юнит.
 */
public final class TimeRewind{
    public static final String enabledKey = "sonka-rewind-enabled", secondsKey = "sonka-rewind-seconds", historyKey = "sonka-rewind-history";

    /** Минимальный интервал между снапшотами, тики (60 = 1 с). */
    private static final float baseStep = 60f;
    /** Доля времени, которую снапшоты могут съедать в главном потоке (см. {@link #stepTicks}). */
    private static final float overheadBudget = 0.03f;
    /** Сколько несжатых снапшотов может висеть в очереди потока-сжимателя, прежде чем новые пропускаются. */
    private static final int maxPending = 2;
    private static final long memoryCap = Math.min(768L << 20, Runtime.getRuntime().maxMemory() / 4);

    private static final Seq<Snap> snaps = new Seq<>();
    private static final ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "TimeRewind-Compress");
        t.setDaemon(true);
        return t;
    });

    private static int epoch, pending, failures;
    private static boolean restoring, rewindQueued;
    /** state.tick последнего снапшота (в т.ч. ещё сжимающегося) - от него отсчитывается интервал. */
    private static double lastTick = Double.NEGATIVE_INFINITY;
    /** Скользящее среднее стоимости сериализации в главном потоке, мс. */
    private static float costMs;
    private static int lastRawSize = 1 << 20;

    private static class Snap{
        final double tick;
        final int epoch, unitId;
        byte[] data;

        Snap(double tick, int epoch, int unitId){
            this.tick = tick;
            this.epoch = epoch;
            this.unitId = unitId;
        }
    }

    private TimeRewind(){
    }

    /** Вызывается из Main.kt - только вешает слушатели. */
    public static void init(){
        //чужая загрузка мира (новая карта, загрузка сейва) - прошлая линия времени больше не наша
        Events.on(WorldLoadEvent.class, e -> {
            if(!restoring) clear();
        });
        Events.run(Trigger.update, TimeRewind::update);
    }

    /** Песочница ли сейчас и можно ли вообще вести историю. */
    private static boolean usable(){
        return !mobile && state.isGame() && !net.active() && !state.isCampaign() && !state.isEditor()
            && state.rules.infiniteResources && !world.isInvalidMap() && Core.settings.getBool(enabledKey, true);
    }

    private static void clear(){
        snaps.clear();
        epoch++;
        lastTick = Double.NEGATIVE_INFINITY;
        failures = 0;
    }

    private static void update(){
        if(Core.scene == null || restoring) return;

        if(!usable()){
            if(snaps.any()) clear();
            return;
        }

        if(!rewindQueued && Core.input.keyTap(Binding.rewindTime) && !Core.scene.hasField() && !Core.scene.hasDialog()){
            rewindQueued = true;
            Core.app.post(() -> {
                rewindQueued = false;
                rewind();
            });
        }

        //тик пошёл назад без нашего участия (кто-то перезагрузил мир мимо WorldLoadEvent) - история чужая
        if(state.tick < lastTick - 1f) clear();

        if(state.isPaused() || failures >= 3) return;
        if(snaps.isEmpty() && pending == 0 || state.tick - lastTick >= stepTicks()){
            capture();
        }
    }

    /** Интервал между снапшотами: не чаще baseStep и так, чтобы цена сериализации укладывалась в бюджет. */
    private static float stepTicks(){
        float byCost = costMs / 1000f / overheadBudget * 60f;
        float cap = Math.max(baseStep, secondsSetting() * 60f);
        return Mathf.clamp(byCost, baseStep, cap);
    }

    private static int secondsSetting(){
        return Core.settings.getInt(secondsKey, 5);
    }

    private static float historyTicks(){
        //история короче самого отката бессмысленна: хотя бы N секунд + запас на интервал
        return Math.max(Core.settings.getInt(historyKey, 60), secondsSetting() + 5) * 60f;
    }

    private static void capture(){
        if(pending >= maxPending) return;

        long start = Time.nanos();
        ByteArrayOutputStream raw = new ByteArrayOutputStream(lastRawSize + 4096);
        try{
            SaveIO.write(raw, new SaveOptions());
        }catch(Throwable t){
            //не спамим каждый кадр: три подряд неудачи - история на этой карте выключена
            Log.err("[sonka] Не удалось записать снапшот отмотки (" + (++failures) + "/3)", t);
            lastTick = state.tick;
            return;
        }
        failures = 0;
        float ms = Time.timeSinceNanos(start) / 1_000_000f;
        costMs = costMs == 0f ? ms : Mathf.lerp(costMs, ms, 0.3f);
        lastRawSize = raw.size();
        lastTick = state.tick;

        Unit unit = player.unit();
        Snap snap = new Snap(state.tick, epoch, unit != null && !unit.dead() && unit.isValid() ? unit.id : -1);
        pending++;
        pool.execute(() -> {
            byte[] packed = null;
            try{
                ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(1024, raw.size() / 4));
                Deflater deflater = new Deflater(Deflater.BEST_SPEED);
                try(DeflaterOutputStream dos = new DeflaterOutputStream(out, deflater, 1 << 15)){
                    raw.writeTo(dos);
                }finally{
                    deflater.end();
                }
                packed = out.toByteArray();
            }catch(Throwable t){
                Log.err("[sonka] Не удалось сжать снапшот отмотки", t);
            }
            byte[] result = packed;
            Core.app.post(() -> {
                pending--;
                if(result == null || snap.epoch != epoch) return;
                snap.data = result;
                snaps.add(snap);
                trim();
            });
        });
    }

    private static long memory(){
        long sum = 0;
        for(Snap s : snaps) sum += s.data.length;
        return sum;
    }

    /** Выбросить слишком старые снапшоты и держать память в пределах. Последний снапшот не трогаем. */
    private static void trim(){
        double oldest = state.tick - historyTicks();
        while(snaps.size > 1 && snaps.first().tick < oldest) snaps.remove(0);
        long mem = memory();
        while(snaps.size > 1 && mem > memoryCap){
            mem -= snaps.remove(0).data.length;
        }
    }

    /** Откат на N секунд (или к началу истории, если она короче). Только из главного потока вне update. */
    private static void rewind(){
        if(!usable()) return;

        float seconds = secondsSetting();
        double want = state.tick - seconds * 60f;
        Snap target = null;
        for(int i = snaps.size - 1; i >= 0; i--){
            if(snaps.get(i).tick <= want){
                target = snaps.get(i);
                break;
            }
        }
        boolean atLimit = target == null;
        if(atLimit && snaps.any()) target = snaps.first();

        //нет истории, либо ближайший снапшот - это практически сейчас (начало игры / уже на краю истории)
        if(target == null || state.tick - target.tick < 30f){
            toast("client.sonka.rewind.none");
            return;
        }

        while(snaps.peek() != target) snaps.pop();
        epoch++;
        double from = state.tick;
        boolean wasPaused = state.isPaused();
        long start = Time.nanos();

        restoring = true;
        try{
            SaveIO.load(new InflaterInputStream(new ByteArrayInputStream(target.data)), world.context);
            state.rules.editor = false;
            state.rules.sector = null;
            state.set(wasPaused ? State.paused : State.playing);

            if(target.unitId != -1){
                Unit unit = Groups.unit.getByID(target.unitId);
                if(unit != null && unit.isValid() && !unit.isPlayer()){
                    player.unit(unit);
                }
            }
        }catch(SaveException e){
            Log.err("[sonka] Откат времени не удался", e);
            logic.reset();
            snaps.clear();
            ui.showErrorMessage("@save.corrupted");
            return;
        }finally{
            restoring = false;
        }

        lastTick = target.tick;

        int back = (int)Math.round((from - target.tick) / 60.0);
        toast(atLimit ? "client.sonka.rewind.limit" : "client.sonka.rewind.done", back);
    }

    private static void toast(String key, Object... args){
        ui.hudfrag.showToast(Icon.undo, args.length == 0 ? Core.bundle.get(key) : Core.bundle.format(key, args));
    }
}
