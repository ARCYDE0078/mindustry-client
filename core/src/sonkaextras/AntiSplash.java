package sonkaextras;

import arc.Core;
import arc.Events;
import arc.input.KeyBind;
import arc.input.KeyCode;
import arc.math.geom.Vec2;
import arc.struct.Seq;
import arc.util.Time;
import mindustry.game.EventType.Trigger;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Teamc;
import mindustry.gen.Unit;
import mindustry.world.Tile;

import static mindustry.Vars.*;

/**
 * «Анти-сплэш» построение для RTS-приказа «двигаться»: юниты встают не кучей, а соты-решёткой с заданным
 * расстоянием между центрами, чтобы один выстрел с площадным уроном не накрывал сразу пачку.
 * <p>
 * Почему клиентская раздача точек, а не правка {@code UnitGroup}: на чужом сервере ИИ юнитов (CommandAI) и
 * их формация считаются сервером - локальная правка формации ничего бы не изменила. Поэтому каждому юниту
 * отправляется свой обычный {@code Call.commandUnits} с его личной точкой: сервер видит пачку одиночных
 * приказов (формация для одного юнита не строится), а значит сервер не нужно менять и сервер ничего не
 * знает про «режим». Цена - по пакету на юнит; поэтому лимит {@link #MAX_UNITS} (больше - обычная
 * ванильная формация) и растягивание отправки на несколько тиков, чтобы не упереться в антиспам сервера.
 * <p>
 * Настройки (секция Sonka Extras): {@link #enabledKey} (по умолчанию ВЫКЛ - меняет привычное поведение
 * приказов) и {@link #distKey} - расстояние между юнитами в тайлах.
 */
public final class AntiSplash{
    public static final String enabledKey = "sonka-antisplash", distKey = "sonka-antisplash-dist";

    /** Больше этого числа юнитов - ваниль (по пакету на юнита уже слишком дорого). */
    private static final int MAX_UNITS = 150;
    /** Сколько приказов отправлять за один тик. */
    private static final int PER_TICK = 25;

    /** Хоткей-переключатель (по умолчанию не назначен - назначается в настройках управления, категория «sonka»). */
    public static final KeyBind toggleKey = KeyBind.add("sonka_antisplash_toggle", KeyCode.unknown, "sonka");

    private AntiSplash(){
    }

    /** Из Main.kt: тянет статическую регистрацию хоткея ДО загрузки раскладки клавиш + слушает нажатие. */
    public static void init(){
        if(headless) return;
        Events.run(Trigger.update, () -> {
            if(state.isGame() && !Core.scene.hasKeyboard() && Core.input.keyTap(toggleKey)) toggle();
        });
    }

    public static boolean enabled(){
        return Core.settings.getBool(enabledKey, false);
    }

    /** Переключатель для хоткея/кнопки; возвращает новое состояние. */
    public static boolean toggle(){
        boolean now = !enabled();
        Core.settings.put(enabledKey, now);
        if(ui != null) ui.showInfoFade(Core.bundle.get(now ? "client.sonka.antisplash.on" : "client.sonka.antisplash.off"));
        return now;
    }

    /**
     * Пытается отправить приказ «двигаться в точку» построением; true - приказ отправлен здесь (ванильный
     * вызов делать не нужно). false - построение не применимо (выключено, цель-враг, мало/много юнитов).
     */
    public static boolean dispatch(int[] ids, Teamc attackTarget, Vec2 target, boolean queue){
        if(attackTarget != null || target == null || ids == null || ids.length < 2 || ids.length > MAX_UNITS || !enabled()) return false;
        if(player == null) return false;

        Seq<Unit> units = new Seq<>();
        float maxHit = 0f, cx = 0f, cy = 0f;
        for(int id : ids){
            Unit u = Groups.unit.getByID(id);
            if(u == null || u.team != player.team()) continue;
            units.add(u);
            maxHit = Math.max(maxHit, u.hitSize);
            cx += u.x;
            cy += u.y;
        }
        if(units.size < 2) return false;
        final float fcx = cx / units.size, fcy = cy / units.size;

        //расстояние между центрами: настройка, но не меньше габарита самого большого юнита (+10%), иначе он сам себе мешает
        float spacing = Math.max(Core.settings.getInt(distKey, 3) * tilesize, maxHit * 1.1f);

        //1) узлы шестиугольной решётки вокруг цели, по возрастанию расстояния; берём с запасом на непроходимые
        int n = units.size;
        int extra = n * 3;
        int k = (int)Math.ceil(Math.sqrt(extra)) + 1;
        Seq<Vec2> lattice = new Seq<>();
        for(int r = -k; r <= k; r++){
            for(int c = -k; c <= k; c++){
                lattice.add(new Vec2((c + (r & 1) * 0.5f) * spacing, r * spacing * 0.8660254f));
            }
        }
        lattice.sort((a, b) -> Float.compare(a.len2(), b.len2()));
        if(lattice.size > extra) lattice.truncate(extra);

        //2) ставим юнитов на первые n узлов так, чтобы взаимное расположение сохранилось (слева остаётся слева):
        //сортируем и юнитов, и узлы по поперечной координате относительно направления движения
        Vec2 dir = new Vec2(target.x - fcx, target.y - fcy);
        if(dir.isZero(0.001f)) dir.set(1f, 0f);
        dir.nor();
        Vec2 perp = new Vec2(-dir.y, dir.x);

        units.sort((a, b) -> Float.compare(
            (a.x - fcx) * perp.x + (a.y - fcy) * perp.y + ((a.x - fcx) * dir.x + (a.y - fcy) * dir.y) * 0.001f,
            (b.x - fcx) * perp.x + (b.y - fcy) * perp.y + ((b.x - fcx) * dir.x + (b.y - fcy) * dir.y) * 0.001f));
        Seq<Vec2> firstN = lattice.copy();
        if(firstN.size > n) firstN.truncate(n);
        firstN.sort((a, b) -> Float.compare(a.x * perp.x + a.y * perp.y + (a.x * dir.x + a.y * dir.y) * 0.001f,
                                            b.x * perp.x + b.y * perp.y + (b.x * dir.x + b.y * dir.y) * 0.001f));

        boolean[] used = new boolean[lattice.size];
        Vec2[] result = new Vec2[n];
        for(int i = 0; i < n; i++){
            Unit u = units.get(i);
            Vec2 want = firstN.get(Math.min(i, firstN.size - 1));
            int idx = lattice.indexOf(want, true);
            if(idx >= 0 && !used[idx] && reachable(u, target, want)){
                used[idx] = true;
                result[i] = new Vec2(target.x + want.x, target.y + want.y);
                continue;
            }
            //узел занят/непроходим (стена, глубина): ближайший свободный проходимый, иначе просто центр цели
            result[i] = new Vec2(target);
            for(int j = 0; j < lattice.size; j++){
                if(used[j]) continue;
                Vec2 cand = lattice.get(j);
                if(reachable(u, target, cand)){
                    used[j] = true;
                    result[i].set(target.x + cand.x, target.y + cand.y);
                    break;
                }
            }
        }

        //3) отправка: по приказу на юнита, партиями по PER_TICK за тик
        for(int i = 0; i < n; i++){
            int id = units.get(i).id;
            Vec2 pos = result[i];
            float delay = (i / PER_TICK) * 1f;
            if(delay <= 0f){
                send(id, pos, queue);
            }else{
                Time.run(delay, () -> send(id, pos, queue));
            }
        }
        return true;
    }

    private static void send(int id, Vec2 pos, boolean queue){
        if(player == null || state.isMenu()) return;
        Call.commandUnits(player, new int[]{id}, null, null, pos, queue, true);
    }

    /** Может ли юнит стоять в точке (цель + смещение): внутри карты и проходимо для его типа. */
    private static boolean reachable(Unit u, Vec2 target, Vec2 off){
        float wx = target.x + off.x, wy = target.y + off.y;
        if(wx < 0 || wy < 0 || wx > world.unitWidth() || wy > world.unitHeight()) return false;
        if(u.type.flying) return true;
        Tile t = world.tileWorld(wx, wy);
        if(t == null) return false;
        return u.canPass(t.x, t.y);
    }
}
