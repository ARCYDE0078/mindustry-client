package qol.core;

import arc.struct.IntMap;
import arc.util.Time;

/**
 * Реестр "игрок только что вручную командовал этим юнитом". Вызывается ТОЛЬКО из ручных путей ввода
 * (клик-приказ в InputHandler, кнопки/хоткеи команд в PlacementFragment) - собственные RPC фич
 * (core-heal, assist-share и т.д.) сюда не попадают, поэтому "ручное" отличимо от "автоматического"
 * без гадания по совпадению текущей команды (move/repair совпадали с командой фичи, и ручной приказ
 * летел в хил-цикл незамеченным). Авто-фичи не должны трогать юнита, пока {@link #isRecent} true.
 * Сбрасывается на загрузке мира (QolSuiteMod через clear()).
 */
public final class ManualControl{
    private static final IntMap<Long> lastManual = new IntMap<>();
    /** То же для ручного переключения stance (boost/holdFire/...) из UI команд. */
    private static final IntMap<Long> lastManualStance = new IntMap<>();

    private ManualControl(){
    }

    public static void mark(int[] unitIds){
        long now = Time.millis();
        for(int id : unitIds) lastManual.put(id, now);
    }

    /** true, если юнит получал ручную команду не раньше чем holdMs назад. holdMs <= 0 = приоритет выключен. */
    public static boolean isRecent(int unitId, long holdMs){
        if(holdMs <= 0) return false;
        return Time.timeSinceMillis(lastManual.get(unitId, 0L)) <= holdMs && lastManual.containsKey(unitId);
    }

    /** Помечает юнитов ручной сменой stance и возвращает тот же массив (удобно оборачивать аргумент Call). */
    public static int[] markStance(int[] unitIds){
        long now = Time.millis();
        for(int id : unitIds) lastManualStance.put(id, now);
        return unitIds;
    }

    public static boolean isStanceRecent(int unitId, long holdMs){
        if(holdMs <= 0) return false;
        return lastManualStance.containsKey(unitId) && Time.timeSinceMillis(lastManualStance.get(unitId, 0L)) <= holdMs;
    }

    public static void clear(){
        lastManualStance.clear();
        lastManual.clear();
    }
}
