package sonkaextras;

import arc.Core;
import arc.Events;
import arc.func.Cons;
import arc.math.geom.Point2;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Nullable;
import arc.util.Time;
import mindustry.client.Main;
import mindustry.client.communication.ProcessorConfigTransmission;
import mindustry.entities.units.BuildPlan;
import mindustry.game.EventType;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.gen.Unit;
import mindustry.world.Block;
import mindustry.world.blocks.logic.LogicBlock;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.InflaterInputStream;

import static mindustry.Vars.*;

/**
 * Обмен КОДОМ ПРОЦЕССОРОВ между клиентами Monolith (просьба sonka: "чтобы процессоры не пропускать").
 * <p>
 * Зачем отдельный канал. Превью чужих планов (clientPlanSnapshot) {@code byte[]}-конфигов не несёт вообще, а
 * очередь юнита ({@code unit.plans}) отдаёт их другим клиентам лишь в узком бюджете (~500 байт на пакет) и не
 * отдаёт совсем, если у владельца включено {@code processorconfigs} (configLocal). Так что у чужого плана процессора
 * кода, как правило, нет. Поэтому:
 * <ol>
 *   <li>сначала смотрим, не прислал ли код сам сервер ({@code owner.unit().plans}) или не лежит ли он уже в кэше;</li>
 *   <li>иначе шлём по клиентскому каналу форка запрос {@link ProcessorConfigTransmission} - его ретранслирует
 *       сервер как обычный трафик (Block/Plugin/BuildPlan-система). Клиент-владелец с Monolith находит этот план
 *       в СВОЕЙ очереди (там конфиг всегда настоящий) и отвечает тем же каналом.</li>
 * </ol>
 * Всё состояние живёт в главном потоке: {@code Main.kt} переносит сюда обработку через {@code Core.app.post}.
 * <p>
 * Защита (чужие пакеты - недоверенный ввод):
 * <ul>
 *   <li>кому верим, определяет канал, а не содержимое: id отправителя ставит сервер/игра. Ответ принимается только
 *       как "код от игрока S" и применяется лишь к плану, чей владелец - тот же S;</li>
 *   <li>отвечаем и принимаем только в своей команде, только на блоки-процессоры, конфиг не длиннее
 *       {@link LogicBlock#maxCompressedLen} и обязан разворачиваться в пределах {@link LogicBlock#maxByteLen};</li>
 *   <li>раздача ограничена (окно + дедупликация): иначе любой тиммейт мог бы забить наш канал запросами;</li>
 *   <li>кэш ограничен по числу записей, байтам и времени; запрос без ответа умирает по таймауту;</li>
 *   <li>раздаём только по настройке {@value #SERVE_SETTING} (по умолчанию включена).</li>
 * </ul>
 */
public final class ProcessorShare{
    public static final String SERVE_SETTING = "procshareserve";

    private static final long CACHE_TTL_MS = 10 * 60_000L;
    private static final int CACHE_MAX_ENTRIES = 64;
    private static final int CACHE_MAX_BYTES = 1 << 20;
    /** Ответ идёт по каналу со скоростью 0,5-1 пакета в секунду, 16 КБ - это ~35 пакетов, плюс очередь других передач. */
    private static final float TIMEOUT_TICKS = 60f * 45f;
    private static final int SERVE_LIMIT = 30;
    private static final long SERVE_WINDOW_MS = 60_000L;
    private static final long SERVE_DEDUPE_MS = 5_000L;

    /** owner - id игрока-владельца плана; для таблицы дедупликации раздачи owner не нужен и равен 0. */
    private record Key(int owner, int pos, String block){}
    private record Cached(byte[] config, long time){}
    private record Waiter(Cons<byte[]> done, Runnable failed){}

    /** Порядок вставки = порядок вытеснения (самые старые уходят первыми). */
    private static final LinkedHashMap<Key, Cached> cache = new LinkedHashMap<>();
    private static final HashMap<Key, Seq<Waiter>> pending = new HashMap<>();
    private static final HashMap<Key, Long> served = new HashMap<>();
    /** Кольцо времён последних SERVE_LIMIT ответов: если самый старый моложе окна, лимит исчерпан. */
    private static final long[] serveLog = new long[SERVE_LIMIT];
    private static int serveHead;

    static{
        //чужие координаты и владельцы не переживают смену мира: тот же (x, y) на другой карте - другой план
        Events.on(EventType.WorldLoadEvent.class, e -> clear());
    }

    private ProcessorShare(){
    }

    public static boolean serveEnabled(){
        return Core.settings.getBool(SERVE_SETTING, true);
    }

    private static void clear(){
        cache.clear();
        pending.clear();
        served.clear();
    }

    private static Key key(Player owner, BuildPlan plan){
        return new Key(owner.id, Point2.pack(plan.x, plan.y), plan.block.name);
    }

    /**
     * Код процессора, который уже есть на руках (без сети): присланный сервером в очереди владельца либо
     * закэшированный ответ. null - надо спрашивать ({@link #request}).
     */
    public static @Nullable byte[] known(Player owner, BuildPlan plan){
        if(owner == null || plan == null || !(plan.block instanceof LogicBlock)) return null;

        Unit unit = owner.unit();
        if(unit != null && unit.plans != null){
            //индексом, а не for-each: итератор Queue один на очередь и не переносит вложенных обходов
            for(int i = 0; i < unit.plans.size; i++){
                BuildPlan p = unit.plans.get(i);
                if(p != null && !p.breaking && p.x == plan.x && p.y == plan.y && p.block == plan.block
                    && p.config instanceof byte[] b && validConfig(b)){
                    return b;
                }
            }
        }

        Key key = key(owner, plan);
        Cached hit = cache.get(key);
        if(hit == null) return null;
        if(Time.millis() - hit.time() > CACHE_TTL_MS){
            cache.remove(key);
            return null;
        }
        return hit.config();
    }

    /**
     * Просит у владельца плана код процессора по клиентскому каналу. Ровно один из колбэков будет вызван
     * (возможно сразу, если запрос невозможен): {@code done} с кодом либо {@code failed} при таймауте/ошибке.
     * Одинаковые запросы (тот же владелец, клетка и блок) делят один пакет и один таймаут.
     */
    public static void request(Player owner, BuildPlan plan, Cons<byte[]> done, Runnable failed){
        if(owner == null || plan == null || !(plan.block instanceof LogicBlock) || !net.active()){
            failed.run();
            return;
        }

        Key key = key(owner, plan);
        Seq<Waiter> waiters = pending.get(key);
        if(waiters != null){
            waiters.add(new Waiter(done, failed));
            return;
        }

        Seq<Waiter> mine = new Seq<>();
        mine.add(new Waiter(done, failed));
        pending.put(key, mine);

        try{
            Main.INSTANCE.send(new ProcessorConfigTransmission(key.pos(), key.block()), null);
        }catch(Throwable t){
            Log.err(t);
            pending.remove(key);
            failed.run();
            return;
        }

        Time.run(TIMEOUT_TICKS, () -> {
            //ответ уже пришёл (запись убрана) или её заменил новый запрос - тогда это не наш таймаут
            if(pending.get(key) != mine) return;
            pending.remove(key);
            for(Waiter w : mine) w.failed().run();
        });
    }

    /** Входящая передача от игрока {@code senderId}; только главный поток. */
    public static void handle(ProcessorConfigTransmission t, int senderId){
        if(player == null || senderId < 0 || senderId == player.id || !net.active()) return;

        Player sender = null;
        for(Player p : Groups.player){
            if(p.id == senderId){
                sender = p;
                break;
            }
        }
        //чужим командам код не раздаём и от них не берём: их планы мы и не видим
        if(sender == null || sender.team() != player.team()) return;

        if(t.getResponse()) accept(sender, t);
        else serve(t);
    }

    /** Нас просят код процессора из нашей очереди. */
    private static void serve(ProcessorConfigTransmission t){
        if(!serveEnabled() || player.dead()) return;

        Block block = content.block(t.getBlock());
        if(!(block instanceof LogicBlock)) return;

        Unit unit = player.unit();
        if(unit == null || unit.plans == null) return;

        BuildPlan found = null;
        for(int i = 0; i < unit.plans.size; i++){
            BuildPlan p = unit.plans.get(i);
            if(p != null && !p.breaking && p.block == block && Point2.pack(p.x, p.y) == t.getPos()){
                found = p;
                break;
            }
        }
        //отдаём лишь то, что реально стоит у нас в очереди: это и есть тот самый план, который тиммейт видит призраком
        if(found == null || !(found.config instanceof byte[] config) || !validConfig(config)) return;

        long now = Time.millis();
        Key dedupe = new Key(0, t.getPos(), block.name);
        Long last = served.get(dedupe);
        //ответ уходит всем сразу (канал общий), повторять его на каждый запрос в окне незачем
        if(last != null && now - last < SERVE_DEDUPE_MS) return;
        if(now - serveLog[serveHead] < SERVE_WINDOW_MS) return;

        serveLog[serveHead] = now;
        serveHead = (serveHead + 1) % SERVE_LIMIT;
        if(served.size() > 256) served.values().removeIf(v -> now - v > SERVE_DEDUPE_MS);
        served.put(dedupe, now);

        try{
            Main.INSTANCE.send(new ProcessorConfigTransmission(t.getPos(), block.name, config), null);
        }catch(Throwable e){
            Log.err(e);
        }
    }

    /** Пришёл код процессора от {@code sender}; кэшируем под ключом "этот игрок + клетка + блок" и будим ждущих. */
    private static void accept(Player sender, ProcessorConfigTransmission t){
        byte[] config = t.getConfig();
        Block block = content.block(t.getBlock());
        if(!(block instanceof LogicBlock) || !validConfig(config)) return;

        //кэшируем и то, о чём просил не мы: код мог понадобиться позже; ключ привязан к отправителю, так что
        //подменить чужой план нельзя - применим ответ лишь тому плану, чей владелец именно этот игрок
        Key key = new Key(sender.id, t.getPos(), block.name);
        remember(key, config);

        Seq<Waiter> waiters = pending.remove(key);
        if(waiters != null){
            for(Waiter w : waiters) w.done().get(config);
        }
    }

    private static void remember(Key key, byte[] config){
        cache.remove(key);
        cache.put(key, new Cached(config, Time.millis()));

        int bytes = 0;
        for(Cached c : cache.values()) bytes += c.config().length;

        Iterator<Map.Entry<Key, Cached>> it = cache.entrySet().iterator();
        while(it.hasNext() && (cache.size() > CACHE_MAX_ENTRIES || bytes > CACHE_MAX_BYTES)){
            bytes -= it.next().getValue().config().length;
            it.remove();
        }
    }

    /**
     * Похоже ли на настоящий сжатый код процессора: влезает в лимит, разворачивается, длина кода в границах.
     * Проверяем и своё (перед раздачей), и чужое (перед кэшем): в мир такой конфиг попадёт как настоящий.
     */
    public static boolean validConfig(@Nullable byte[] data){
        if(data == null || data.length == 0 || data.length > LogicBlock.maxCompressedLen) return false;

        try(DataInputStream stream = new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(data)))){
            stream.read(); //версия
            int length = stream.readInt();
            //читаем ровно заявленное: раздутый "zip-бомбой" поток дальше length мы не разворачиваем
            if(length < 0 || length > LogicBlock.maxByteLen) return false;
            stream.readFully(new byte[length]);
            return true;
        }catch(Exception e){
            return false;
        }
    }
}
