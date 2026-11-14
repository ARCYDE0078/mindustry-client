package scheme.tools.admins;

import arc.math.geom.Position;
import arc.math.geom.Vec2;
import arc.struct.ObjectSet;
import arc.struct.Seq;
import mindustry.Vars;
import mindustry.entities.Units;
import mindustry.entities.units.BuildPlan;
import mindustry.game.Team;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.type.Item;
import mindustry.type.UnitType;

import static arc.Core.*;
import static mindustry.Vars.*;

/**
 * Интерфейс "читерских" инструментов Scheme Size. Две реализации:
 * {@link Internal} - прямые вызовы движка (хост/локальная игра),
 * {@link SlashJs} - спам /js в чат (сервера с плагином JSEval).
 * Реализация Mindurka (кастомные команды сервера mindurka.tk) не портирована - требует
 * их серверной интеграции (ServerIntegration тоже не портирован).
 */
public interface AdminsTools{

    AdminsTools[] implementations = {new Internal(), new SlashJs()};

    String keyName();

    //true - правило применено или отправлено; локальные rules диалог обновляет только тогда,
    //иначе при отказе (нет прав, ошибка поля) окно показывало бы значение, которого в игре нет
    boolean manageRuleBool(boolean value, String name);

    boolean manageRuleStr(String value, String name);

    boolean manageTeamRuleBool(int teamId, boolean value, String name);

    boolean manageTeamRuleStr(int teamId, String value, String name);

    boolean manageRuleObjectSet(String fieldName, ObjectSet<?> value);

    void manageUnit();

    void spawnUnits();

    void manageEffect();

    void manageItem();

    void manageTeam();

    void manageTeam(Team team, Player player);

    void placeCore();

    void despawn(Player target);

    default void despawn(){
        despawn(player);
    }

    void teleport(Position pos);

    default Position getTeleportPosition(){
        if(mobile) return new Vec2(camera.position.x, camera.position.y);
        return new Vec2(player.mouseX, player.mouseY);
    }

    default void teleport(){
        teleport(getTeleportPosition());
    }

    /** Взгляд юнита на курсор: за кадр сервер/движок сглаживает поворот, поэтому несколько вызовов подряд. */
    default void look(){
        for(int i = 0; i < 10; i++) player.unit().lookAt(input.mouseWorld());
    }

    default void deletePlayer(){
        Vec2 mouse = new Vec2(player.mouseX(), player.mouseY());
        Groups.player.each(target -> {
            if(!mouse.within(target.x, target.y, 3 * tilesize) || target.equals(Vars.player)) return;
            manageTeam(Team.derelict, target);
            despawn(target);
        });
    }

    void fill(int sx, int sy, int ex, int ey);

    void brush(int x, int y, int radius);

    void flush(Seq<BuildPlan> plans);

    boolean unusable();

    default int fixAmount(Item item, float amount){
        int items = player.core().items.get(item);
        return amount == 0f || items + amount < 0 ? -items : (int)amount;
    }

    default boolean canCreate(Team team, UnitType type){
        boolean can = Units.canCreate(team, type);
        if(!can) ui.showInfoFade("@scheme.admins.nounit");
        return can;
    }

    default boolean hasCore(Team team){
        boolean has = team.core() != null;
        if(!has) ui.showInfoFade("@scheme.admins.nocore");
        return has;
    }
}
