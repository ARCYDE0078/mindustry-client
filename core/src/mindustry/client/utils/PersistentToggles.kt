package mindustry.client.utils

import arc.*
import mindustry.Vars.*
import mindustry.client.ClientVars.*
import mindustry.game.EventType.*

/**
 * Запоминает тумблеры панели режимов в левом верхнем углу (радиусы турелей/проекторов/катапульт, скрытие юнитов, блоков и
 * планов, отправка планов, заморозка очереди) между заходами на карты и перезапусками (sonka, 2026-11-22: «при перезаходе
 * на карту/на другую карту надо включать их заново»). Раньше это были просто статические поля ClientVars, а
 * [mindustry.client.ClientLogic] ещё и обнулял часть из них при каждой загрузке мира.
 *
 * Не перехватываем каждое место, где эти поля меняют (иконка HUD, хоткей, команда, моды), а раз в несколько кадров сверяем
 * значения с сохранёнными и пишем изменившиеся в [Core.settings]. Паузу стройки (`isBuilding`) намеренно не запоминаем: её
 * сами переключают автоматика и моды, и «стройка на паузе» с прошлого запуска выглядела бы как поломка.
 */
object PersistentToggles {
    private class Toggle(val key: String, val get: () -> Boolean, val set: (Boolean) -> Unit) { var saved = false }

    private val toggles = listOf(
        Toggle("hudmode-enemyturrets", { showingTurrets }, { showingTurrets = it }),
        Toggle("hudmode-allyturrets", { showingAllyTurrets }, { showingAllyTurrets = it }),
        Toggle("hudmode-invturrets", { showingInvTurrets }, { showingInvTurrets = it }),
        Toggle("hudmode-hidingunits", { hidingUnits }, { hidingUnits = it }),
        Toggle("hudmode-hidingair", { hidingAirUnits }, { hidingAirUnits = it }),
        Toggle("hudmode-hidingblocks", { hidingBlocks }, { hidingBlocks = it }),
        Toggle("hudmode-hidingplans", { hidingPlans }, { hidingPlans = it }),
        Toggle("hudmode-massdrivers", { showingMassDrivers }, { showingMassDrivers = it }),
        Toggle("hudmode-overdrives", { showingOverdrives }, { showingOverdrives = it }),
        Toggle("hudmode-sendplans", { dispatchingBuildPlans }, { dispatchingBuildPlans = it }),
        Toggle("hudmode-freezequeue", { control?.input?.isFreezeQueueing ?: false }, { control?.input?.isFreezeQueueing = it }),
    )
    private var loaded = false

    fun init() {
        Events.run(Trigger.update) { poll() }
    }

    private fun poll() {
        if (control?.input == null) return // InputHandler создаётся позже клиента - ждём его (нужен для заморозки очереди)
        if (!loaded) {
            loaded = true
            for (t in toggles) {
                t.saved = Core.settings.getBool(t.key, false)
                t.set(t.saved)
            }
            return
        }
        if (Core.graphics.frameId % 10L != 0L) return // булевых полей немного, но каждый кадр сверять незачем
        for (t in toggles) {
            val now = t.get()
            if (now != t.saved) {
                t.saved = now
                Core.settings.put(t.key, now)
            }
        }
    }
}
