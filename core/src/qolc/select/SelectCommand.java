package qolc.select;

import arc.Core;
import arc.Events;
import arc.struct.ObjectSet;
import mindustry.Vars;
import mindustry.client.CommandsKt;
import mindustry.game.EventType.Trigger;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.gen.Unit;
import mindustry.input.InputHandler;

/**
 * {@code !select <тип...>} - выделить в RTS-режиме ВСЕХ своих юнитов перечисленных типов на карте
 * ({@code !select poly mega}) и сразу включить режим командования. Порт features/select.js.
 * <p>
 * Нативного аналога нет: ваниль выделяет рамкой на экране (и юнитов того же типа что под курсором,
 * но только в пределах рамки/экрана), а здесь - по именам и по всей карте, то есть "собери всех
 * поли в одну группу и отправь" без прокрутки камеры.
 * <p>
 * Отличия от оригинала: сопоставление и по внутреннему имени ({@code poly}), и по локализованному
 * ({@code Поли}) без учёта регистра; выделяются только управляемые RTS юниты
 * ({@link Unit#isCommandable}) - остальным выделение ничего не даёт, а счётчик в сообщении вводил бы
 * в заблуждение; после смены выделения стреляет {@code Trigger.unitCommandChange}, как у нативной
 * рамки, чтобы панель команд обновилась. Сообщения - через {@code player.sendMessage}, вместо
 * {@code logger.notify} оригинала. Если в настройках включено "удерживать для командования"
 * ({@code commandmodehold}), режим сам погаснет в следующем кадре, как только клавишу отпустят -
 * это поведение ванильного ввода, обходить его незачем.
 */
public final class SelectCommand{
    private SelectCommand(){
    }

    public static void init(){
        CommandsKt.register("select [types...]", Core.bundle.get("client.command.select.description"), SelectCommand::run);
    }

    private static void run(String[] args, Player player){
        String joined = args.length == 0 ? "" : args[0].trim();
        if(joined.isEmpty()){
            player.sendMessage(Core.bundle.get("qolc.select.usage"));
            return;
        }

        //одним аргументом приходит весь хвост строки (variadic) - режем сами
        ObjectSet<String> wanted = new ObjectSet<>();
        for(String token : joined.split("\\s+")) wanted.add(token.toLowerCase());

        InputHandler input = Vars.control.input;
        input.selectedUnits.clear();
        input.commandBuildings.clear();

        int[] count = {0};
        Groups.unit.each(u -> {
            //свой юнит игрока ванильная проверка выделения всё равно выкинет в следующем кадре - не считаем его
            if(u.team != player.team() || u.dead() || u.isPlayer() || !u.isCommandable()) return;
            if(wanted.contains(u.type.name.toLowerCase()) || wanted.contains(u.type.localizedName.toLowerCase())){
                input.selectedUnits.add(u);
                count[0]++;
            }
        });

        if(count[0] == 0){
            player.sendMessage(Core.bundle.get("qolc.select.none"));
            return;
        }
        input.commandMode = true;
        Events.fire(Trigger.unitCommandChange);
        player.sendMessage(Core.bundle.format("qolc.select.done", count[0]));
    }
}
