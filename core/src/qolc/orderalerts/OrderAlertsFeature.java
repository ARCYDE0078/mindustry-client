package qolc.orderalerts;

import arc.Core;
import arc.Events;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Lines;
import arc.math.Mathf;
import arc.math.geom.Vec2;
import arc.struct.IntMap;
import arc.struct.IntSet;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Strings;
import arc.util.Time;
import mindustry.Vars;
import mindustry.ai.types.CommandAI;
import mindustry.client.CommandsKt;
import mindustry.game.EventType.Trigger;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.game.Team;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.gen.Unit;
import mindustry.graphics.Layer;
import mindustry.type.UnitType;

/**
 * Оповещения о приказах RTS-юнитам других игроков: сообщение в чат + красное кольцо, расходящееся
 * по карте от точки приказа, + сообщение о новой точке сбора вражеских фабрик. Порт той части
 * features/track.js, у которой нет аналога в клиенте.
 * <p>
 * НЕ портировано из track.js (причины): слежка за курсором игрока ({@code !track <ник>}, диалог
 * цели) - курсоры игроков уже рисует нативный {@code drawcursors}; пунктирные линии от групп юнитов
 * к точкам приказа и от фабрик к точкам сбора - то же покрывает mi2u {@code RendererExt} (линии
 * команд); вся плашка настроек - заменена пунктами секции "QoL Control". Поэтому настройка
 * {@code qol-track-rts} здесь управляет только сканированием приказов (в оригинале ещё и линиями).
 * <p>
 * Как определяется "кто командовал": у юнита со строкой {@code lastCommanded} (имя игрока в момент
 * приказа - движок пишет её в {@code InputHandler}) ищется игрок по цветному или обычному имени;
 * для СВОИХ выделенных юнитов командующим считается локальный игрок. У оригинала были ещё ветки
 * {@code FormationAI}/{@code CommandAI.commander} - в движке 160.x их нет ({@code FormationAI}
 * удалён), поэтому остался единственный источник точки приказа: {@code CommandAI.targetPos}.
 * Оригинал, кроме него, брал "цель" любого другого ИИ ({@code AIController.target}); здесь такие
 * юниты не учитываются: цель стрельбы - не приказ, и их сообщение про "смену группы" только
 * шумело бы (поли с lastCommanded в старом сейве, например).
 * <p>
 * Сканирование раз в 15 тиков. Сообщение о приказе - только если точка сдвинулась больше чем на 3
 * тайла (иначе один и тот же приказ сообщался бы на каждом скане), с паузой 6 с на игрока; кольцо -
 * с паузой 6 с; смена состава группы - не чаще раза в 30 с. Точки сбора фабрик сканируются раз в
 * 180 тиков, сообщается только о вражеских командах и только о сдвинувшейся точке.
 */
public final class OrderAlertsFeature{
    /** Сколько живёт кольцо (мс) и его толщина - как в оригинале. */
    private static final long alertLife = 2400;
    private static final float alertStroke = 8f;
    private static final long alertCooldown = 6000, notifyCooldown = 6000, notifyUnitsCooldown = 30000;
    private static final Color alertColor = Color.valueOf("ff5555");

    private static float unitScanTimer = 0f, rallyScanTimer = 0f;
    private static final IntMap<History> history = new IntMap<>();
    private static final IntMap<Rally> lastRally = new IntMap<>();
    private static final Seq<Alert> alerts = new Seq<>();

    private static class History{
        int tx = -999, ty = -999;
        String unitsStr = "";
        //достаточно давно, чтобы первый приказ игрока сообщался сразу
        long notifyTime = -999999, circleTime = -999999;
    }

    private static class Rally{
        int tx, ty;
        long time;
    }

    private static class Alert{
        float x, y, radius;
        long time;
    }

    /** Группа юнитов одного командующего, собранная за один скан. */
    private static class Group{
        Player player;
        final Seq<Unit> units = new Seq<>();
        Vec2 order;
    }

    private OrderAlertsFeature(){
    }

    public static void init(){
        CommandsKt.register("track [option] [value]", Core.bundle.get("client.command.track.description"), OrderAlertsFeature::command);

        Events.on(WorldLoadEvent.class, e -> {
            history.clear();
            lastRally.clear();
            alerts.clear();
        });
        Events.run(Trigger.update, OrderAlertsFeature::update);
        Events.run(Trigger.draw, OrderAlertsFeature::draw);
    }

    private static boolean master(){
        return Core.settings.getBool("qol-track-enabled", false);
    }

    private static void command(String[] args, Player player){
        String option = args.length > 0 ? args[0].toLowerCase() : "";
        String value = args.length > 1 ? args[1].toLowerCase() : "";

        String key, label;
        switch(option){
            case "notify", "n" -> {
                key = "qol-track-rts-notify";
                label = "qolc.track.label.notify";
            }
            case "rts" -> {
                key = "qol-track-rts";
                label = "qolc.track.label.rts";
            }
            case "rec" -> {
                key = "qol-track-rec";
                label = "qolc.track.label.rec";
            }
            case "circle", "c" -> {
                key = "qol-track-rts-circle";
                label = "qolc.track.label.circle";
            }
            case "own", "o" -> {
                key = "qol-track-rts-own";
                label = "qolc.track.label.own";
            }
            default -> {
                //голое слово и "1/0/on/off" переключают главный выключатель; прочее - подсказка
                if(!option.isEmpty() && !isBooleanArg(option)){
                    player.sendMessage(Core.bundle.get("qolc.track.usage"));
                    return;
                }
                key = "qol-track-enabled";
                label = "qolc.track.label.master";
                value = option;
            }
        }

        boolean def = !key.equals("qol-track-enabled");
        boolean now = parseToggle(Core.settings.getBool(key, def), value);
        Core.settings.put(key, now);
        applySideEffects(key, now);
        player.sendMessage(Core.bundle.format("qolc.track.toggled", Core.bundle.get(label), Core.bundle.get(now ? "qolc.on" : "qolc.off")));
    }

    /** Выключение источника сообщений сбрасывает его память - иначе после включения сообщалось бы устаревшее. */
    private static void applySideEffects(String key, boolean now){
        if(now) return;
        switch(key){
            case "qol-track-enabled", "qol-track-rts" -> history.clear();
            case "qol-track-rts-notify" -> {
                history.clear();
                lastRally.clear();
            }
            case "qol-track-rts-circle" -> alerts.clear();
            default -> {
            }
        }
    }

    private static boolean parseToggle(boolean current, String arg){
        return switch(arg){
            case "1", "true", "on" -> true;
            case "0", "false", "off" -> false;
            default -> !current;
        };
    }

    private static boolean isBooleanArg(String arg){
        return switch(arg){
            case "1", "0", "true", "false", "on", "off" -> true;
            default -> false;
        };
    }

    private static void update(){
        if(!master() || !Vars.state.isGame() || Vars.player == null) return;

        if(Core.settings.getBool("qol-track-rts", true)){
            unitScanTimer += Time.delta;
            if(unitScanTimer > 15f){
                unitScanTimer = 0f;
                scanOrders();
            }
        }

        if(Core.settings.getBool("qol-track-rec", true)){
            rallyScanTimer += Time.delta;
            if(rallyScanTimer > 180f){
                rallyScanTimer = 0f;
                scanRallyPoints();
            }
        }
    }

    /** Собирает приказанных юнитов по игрокам и сообщает о новых приказах. */
    private static void scanOrders(){
        //имена -> игроки один раз за скан: coloredName() каждый раз собирает строку, а юнитов сотни
        ObjectMap<String, Player> byName = new ObjectMap<>();
        Groups.player.each(p -> {
            byName.put(p.coloredName(), p);
            byName.put(p.name, p);
        });

        Team myTeam = Vars.player.team();
        boolean own = Core.settings.getBool("qol-track-rts-own", true);
        var selected = Vars.control.input.selectedUnits;
        IntMap<Group> groups = new IntMap<>();

        Groups.unit.each(u -> {
            if(!(u.controller() instanceof CommandAI ai) || ai.targetPos == null) return;
            //рудокопы и юниты, уже стоящие у цели, - не "приказ идти", шум
            if(u.mining()) return;
            Vec2 pos = ai.targetPos;
            if(pos.isZero()) return;
            if(Math.abs(u.x - pos.x) + Math.abs(u.y - pos.y) <= 32f) return;

            Player commander = u.lastCommanded == null ? null : byName.get(u.lastCommanded);
            if(commander == null && u.team == myTeam && selected.contains(u)) commander = Vars.player;
            if(commander == null) return;
            if(commander.team() == myTeam && !own) return;

            Group group = groups.get(commander.id);
            if(group == null){
                group = new Group();
                group.player = commander;
                group.order = pos;
                groups.put(commander.id, group);
            }
            group.units.add(u);
        });

        long now = Time.millis();
        boolean notify = Core.settings.getBool("qol-track-rts-notify", true);
        boolean circle = Core.settings.getBool("qol-track-rts-circle", true);

        for(var entry : groups){
            Group group = entry.value;
            String unitsStr = describe(group.units);

            History hist = history.get(entry.key);
            if(hist == null){
                hist = new History();
                history.put(entry.key, hist);
            }

            int otx = (int)Math.floor(group.order.x / 8f);
            int oty = (int)Math.floor(group.order.y / 8f);
            boolean moved = Math.abs(otx - hist.tx) > 3 || Math.abs(oty - hist.ty) > 3;

            if(moved){
                //точка запоминается и когда ничего не сообщается - один и тот же приказ не сообщится дважды
                hist.tx = otx;
                hist.ty = oty;

                if(circle && now - hist.circleTime > alertCooldown){
                    Alert alert = new Alert();
                    alert.x = group.order.x;
                    alert.y = group.order.y;
                    alert.time = now;
                    alert.radius = mapRadius(alert.x, alert.y);
                    alerts.add(alert);
                    hist.circleTime = now;
                }

                if(notify && now - hist.notifyTime > notifyCooldown){
                    notifyOrder(group.player, unitsStr, otx, oty);
                    hist.notifyTime = now;
                }
            }

            //состав группы изменился (юнит влился или ушёл) - тоже стоит сообщения, но редко
            if(!unitsStr.equals(hist.unitsStr)){
                hist.unitsStr = unitsStr;
                if(notify && now - hist.notifyTime > notifyUnitsCooldown){
                    notifyOrder(group.player, unitsStr, otx, oty);
                    hist.notifyTime = now;
                }
            }
        }
    }

    /** "3[эмодзи], 1[эмодзи]" - состав группы; порядок по id типа стабилен между сканами, иначе смена порядка считалась бы сменой состава. */
    private static String describe(Seq<Unit> units){
        ObjectMap<UnitType, int[]> counts = new ObjectMap<>();
        for(Unit u : units) counts.get(u.type, () -> new int[1])[0]++;

        Seq<UnitType> types = counts.keys().toSeq();
        types.sort(t -> t.id);
        StringBuilder sb = new StringBuilder();
        for(UnitType type : types){
            if(sb.length() > 0) sb.append(", ");
            String emoji = type.emoji();
            sb.append("[accent]").append(counts.get(type)[0]).append("[lightgray]").append(emoji.isEmpty() ? type.localizedName : emoji);
        }
        return sb.toString();
    }

    private static String teamColor(Team team){
        return "[#" + team.color.toString().substring(0, 6) + "]";
    }

    /** Координаты - строками: MessageFormat вставил бы разделитель тысяч ("1,024") в число. */
    private static void notifyOrder(Player player, String unitsStr, int tx, int ty){
        Vars.player.sendMessage(Core.bundle.format("qolc.track.rts", teamColor(player.team()), Strings.stripColors(player.name), unitsStr, String.valueOf(tx), String.valueOf(ty)));
    }

    /** Радиус кольца, которого хватает, чтобы от точки приказа накрыть всю карту, - кольцо видно, куда бы игрок ни смотрел. */
    private static float mapRadius(float x, float y){
        float w = Vars.world.width() * Vars.tilesize;
        float h = Vars.world.height() * Vars.tilesize;
        //самый дальний из четырёх углов карты
        float max = Math.max(Math.max(Mathf.dst(x, y, 0f, 0f), Mathf.dst(x, y, w, 0f)), Math.max(Mathf.dst(x, y, 0f, h), Mathf.dst(x, y, w, h)));
        return max + 200f;
    }

    /** Сообщает о сдвинувшейся точке сбора фабрик ЧУЖИХ команд (своя точка и так видна игроку). */
    private static void scanRallyPoints(){
        boolean notify = Core.settings.getBool("qol-track-rts-notify", true);
        Team myTeam = Vars.player.team();
        IntSet seen = new IntSet();
        long now = Time.millis();

        Groups.build.each(b -> {
            if(b.team == myTeam || b.team == Team.derelict) return;
            Vec2 cp = b.getCommandPosition();
            if(cp == null || cp.isZero()) return;

            int key = b.pos();
            seen.add(key);
            if(!notify) return;

            int rx = (int)Math.floor(cp.x / 8f);
            int ry = (int)Math.floor(cp.y / 8f);
            Rally last = lastRally.get(key);
            boolean same = last != null && rx == last.tx && ry == last.ty;

            //сообщаем только о реально сдвинувшейся точке; в last пишем каждый раз - иначе одна и та же точка сообщалась бы снова и снова
            if(!same && (last == null || now - last.time > 4000)){
                String emoji = b.block.emoji();
                Vars.player.sendMessage(Core.bundle.format("qolc.track.rally", teamColor(b.team), b.team.name,
                    emoji.isEmpty() ? b.block.localizedName : emoji, String.valueOf(rx), String.valueOf(ry)));
            }
            Rally rally = last == null ? new Rally() : last;
            rally.tx = rx;
            rally.ty = ry;
            rally.time = now;
            lastRally.put(key, rally);
        });

        //забываем фабрики, которых больше нет или у которых точку сбора сняли
        var keys = lastRally.keys();
        while(keys.hasNext){
            if(!seen.contains(keys.next())) keys.remove();
        }
    }

    /** Красное кольцо расходится от точки приказа по всей карте: заметно, даже когда чат не читают. */
    private static void draw(){
        if(!master() || alerts.isEmpty() || !Vars.state.isGame()) return;
        if(!Core.settings.getBool("qol-track-rts-circle", true)){
            alerts.clear();
            return;
        }

        long now = Time.millis();
        Draw.z(Layer.max);
        for(int i = alerts.size - 1; i >= 0; i--){
            Alert alert = alerts.get(i);
            long age = now - alert.time;
            if(age >= alertLife){
                alerts.remove(i);
                continue;
            }
            float t = (float)age / alertLife;
            //кольцо сначала растёт быстро, чтобы успеть накрыть всю карту до затухания
            float grown = 1f - (1f - t) * (1f - t);
            float radius = 8f + grown * alert.radius;
            //держится, потом гаснет в конце
            float alpha = (t < 0.1f ? t / 0.1f : t < 0.7f ? 1f : 1f - (t - 0.7f) / 0.3f) * 0.85f;

            Draw.color(alertColor, alpha);
            Lines.stroke(alertStroke);
            Lines.circle(alert.x, alert.y, radius);
        }
        Draw.reset();
    }
}
