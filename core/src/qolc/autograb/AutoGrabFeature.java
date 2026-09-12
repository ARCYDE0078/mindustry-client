package qolc.autograb;

import arc.Core;
import arc.Events;
import arc.math.Mathf;
import arc.struct.Seq;
import arc.util.Strings;
import arc.util.Time;
import arc.util.Tmp;
import mindustry.Vars;
import mindustry.client.CommandsKt;
import mindustry.content.Blocks;
import mindustry.game.EventType.Trigger;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.gen.Building;
import mindustry.gen.Call;
import mindustry.gen.Player;
import mindustry.gen.Unit;
import mindustry.graphics.Drawf;
import mindustry.graphics.Layer;
import mindustry.graphics.Pal;
import mindustry.type.Item;
import arc.graphics.g2d.Draw;

/**
 * Автосбор предмета из своих зданий вокруг юнита: {@code !grab <item>} - и юнит сам вытягивает,
 * например, кремний из всех плавилен в радиусе, пока есть место в трюме. Порт features/autograb.js.
 * <p>
 * Не дублирует нативный AutoTransfer клиента: тот РАЗДАЁТ предметы из ядра/контейнеров нуждающимся
 * блокам (и его экспериментальный drain сливает всё подряд в контейнеры), а здесь - целевой сбор
 * ОДНОГО выбранного предмета в собственный инвентарь. Транспортировка - штатный
 * {@code Call.requestItem}, тот же RPC, что клик по инвентарю здания, поэтому MP-безопасно.
 * <p>
 * Ключ подсветки {@code qol-grab-effects} оставлен как в оригинале - настройка игрока переживает порт.
 * <p>
 * v6.6 -&gt; v7.1: активность/предмет/минимум теперь переживают перезаход в мир И перезапуск клиента
 * (ключи {@code qol-grab-active}/{@code qol-grab-item}/{@code qol-grab-min}, как в оригинале) - раньше
 * авто-сбор сбрасывался на каждой загрузке карты. Разбор команды тоже приведён к оригиналу: явное
 * слово {@code toggle}/{@code t} убрано, теперь голое {@code !grab} и {@code !grab <1/0/on/off>}
 * переключают состояние напрямую (см. {@link #isBooleanArg}), а нераспознанный не-предмет аргумент
 * показывает usage вместо "предмет не найден".
 */
public final class AutoGrabFeature{
    /** Радиус поиска зданий в world-юнитах (27 тайлов, как в оригинале). */
    private static final float range = 216f;

    private static boolean active = false;
    private static Item item = null;
    private static int minAmount = 10;
    private static final Seq<Building> targets = new Seq<>();
    private static int index = 0;
    private static long lastGrab = 0, lastSearch = 0;

    private AutoGrabFeature(){
    }

    public static void init(){
        //восстановление состояния между сессиями - оригинал с v6.8 больше не сбрасывает грэб на роспуск
        active = Core.settings.getBool("qol-grab-active", false);
        minAmount = Core.settings.getInt("qol-grab-min", 10);
        String savedItem = Core.settings.getString("qol-grab-item", "");
        if(!savedItem.isEmpty()) item = findItem(savedItem);

        //цель ищется заново на каждой карте, но активность/предмет теперь переживают смену мира
        Events.on(WorldLoadEvent.class, e -> targets.clear());

        Events.run(Trigger.update, AutoGrabFeature::update);
        Events.run(Trigger.draw, AutoGrabFeature::draw);

        CommandsKt.register("grab [option] [value]", Core.bundle.get("client.command.grab.description"), AutoGrabFeature::runCommand);
        CommandsKt.register("gr [option] [value]", Core.bundle.get("client.command.grab.description"), AutoGrabFeature::runCommand);
    }

    private static void runCommand(String[] args, Player player){
        //голый вызов теперь переключает (было: показ usage) - оригинал v7.1, core/autograb.js
        if(args.length == 0){
            active = !active;
            Core.settings.put("qol-grab-active", active);
            player.sendMessage(Core.bundle.format("qolc.grab.toggled", onOff(active)));
            return;
        }

        switch(args[0]){
            case "effects", "e" -> {
                boolean effects = !Core.settings.getBool("qol-grab-effects", true);
                Core.settings.put("qol-grab-effects", effects);
                player.sendMessage(Core.bundle.format("qolc.grab.effects", onOff(effects)));
            }
            case "min" -> {
                if(args.length < 2 || !Strings.canParsePositiveInt(args[1]) || Strings.parseInt(args[1]) < 1){
                    player.sendMessage(Core.bundle.get("qolc.grab.bad-min"));
                    return;
                }
                minAmount = Strings.parseInt(args[1]);
                Core.settings.put("qol-grab-min", minAmount);
                player.sendMessage(Core.bundle.format("qolc.grab.min-set", minAmount));
            }
            case "status", "s" -> player.sendMessage(Core.bundle.format("qolc.grab.status",
                onOff(active), item == null ? "-" : item.localizedName, minAmount,
                onOff(Core.settings.getBool("qol-grab-effects", true))));
            default -> {
                Item found = findItem(args[0]);
                if(found != null){
                    item = found;
                    active = true;
                    Core.settings.put("qol-grab-item", found.name);
                    Core.settings.put("qol-grab-active", active);
                    player.sendMessage(Core.bundle.format("qolc.grab.enabled", found.emoji() + " " + found.localizedName));
                }else if(isBooleanArg(args[0])){
                    //явного "toggle"/"t" в оригинале с v6.8 больше нет - голый 1/0/on/off переключает сам
                    active = parseToggle(active, args[0]);
                    Core.settings.put("qol-grab-active", active);
                    player.sendMessage(Core.bundle.format("qolc.grab.toggled", onOff(active)));
                }else{
                    //не предмет и не булево - раньше здесь было "item-not-found", оригинал v7.1 показывает usage
                    player.sendMessage(Core.bundle.get("qolc.grab.usage"));
                }
            }
        }
    }

    private static Item findItem(String name){
        String lower = name.toLowerCase();
        Item exact = Vars.content.items().find(i -> i.name.equals(lower));
        if(exact != null) return exact;
        return Vars.content.items().find(i -> i.name.contains(lower) || i.localizedName.toLowerCase().contains(lower));
    }

    private static boolean parseToggle(boolean current, String arg){
        return switch(arg){
            case "1", "true", "on" -> true;
            case "0", "false", "off" -> false;
            default -> !current;
        };
    }

    /** Тот же набор литералов, что распознаёт {@link #parseToggle} - используется, чтобы отличить булев аргумент от имени предмета. */
    private static boolean isBooleanArg(String arg){
        return switch(arg){
            case "1", "0", "true", "false", "on", "off" -> true;
            default -> false;
        };
    }

    private static String onOff(boolean value){
        return Core.bundle.get(value ? "qolc.on" : "qolc.off");
    }

    private static void update(){
        if(!active || item == null || !Vars.state.isGame()) return;
        Unit unit = Vars.player.unit();
        if(unit == null || unit.dead()) return;

        long now = Time.millis();
        if(now > lastSearch){
            targets.clear();
            Vars.indexer.allBuildings(unit.x, unit.y, range, b -> {
                if(b.team == Vars.player.team() && b.items != null && b.block != Blocks.air) targets.add(b);
            });
            lastSearch = now + 1000;
        }

        if(targets.isEmpty() || now - lastGrab < 250) return;

        int space = unit.type.itemCapacity - unit.stack.amount;
        if(unit.stack.amount > 0 && unit.stack.item != item) space = 0;
        if(space <= 0) return;

        //по одному зданию за заход, круговым перебором - равномерно тянет со всех, как оригинал
        for(int checked = 0; checked < targets.size; checked++){
            index = (index + 1) % targets.size;
            Building b = targets.get(index);
            if(b == null || !b.isValid() || b.team != Vars.player.team()){
                targets.remove(index);
                if(targets.isEmpty()) return;
                index %= targets.size;
                checked--;
                continue;
            }
            if(unit.dst2(b) > range * range) continue;

            int has = b.items.get(item);
            if(has >= minAmount){
                Call.requestItem(Vars.player, b, item, Math.min(has, space));
                lastGrab = now;
                return;
            }
        }
    }

    private static void draw(){
        if(!active || item == null || targets.isEmpty()) return;
        if(!Core.settings.getBool("qol-grab-effects", true)) return;

        Draw.z(Layer.overlayUI);
        //Drawf.select сам ставит цвет (и сбрасывает Draw) - пульсацию передаём альфой через Tmp-цвет
        float alpha = Math.abs(Mathf.sin(Time.time / 15f));
        for(Building b : targets){
            if(b.isValid() && b.items.get(item) >= minAmount){
                Drawf.select(b.x, b.y, b.block.size * Vars.tilesize / 2f + 2f, Tmp.c1.set(Pal.accent).a(alpha));
            }
        }
        Draw.reset();
    }
}
