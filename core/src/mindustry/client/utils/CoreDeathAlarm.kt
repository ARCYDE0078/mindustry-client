package mindustry.client.utils

import arc.*
import mindustry.Vars.*
import mindustry.game.EventType.*
import mindustry.world.blocks.storage.CoreBlock.CoreBuild

/**
 * Сообщение в локальный чат (и всплывашка для своего ядра), когда на карте взорвано ядро - своё или вражеское.
 * Идея из coredeathalarm клиента fk4b; в чат сервера НЕ пишем (там это уходило публичным сообщением), только локально.
 * В режиме захвата ядер (coreCapture) ядра переходят к другой команде постоянно, поэтому там по настройке молчим.
 */
object CoreDeathAlarm {
    init {
        Events.on(BlockDestroyEvent::class.java) {
            if (!Core.settings.getBool("coredeathalarm")) return@on
            if (state.rules.coreCapture && Core.settings.getBool("coredeathalarmrecap")) return@on

            val core = it.tile.build as? CoreBuild ?: return@on
            val x = it.tile.x.toString() // строкой, чтобы arc не форматировал число с разделителем тысяч
            val y = it.tile.y.toString()

            if (core.team == player.team()) {
                val msg = Core.bundle.format("client.corealarm.own", x, y)
                player.sendMessage(msg)
                ui.announce(msg, 4f)
            } else {
                player.sendMessage(Core.bundle.format("client.corealarm.enemy", "[#${core.team.color}]${core.team.name}[]", x, y))
            }
        }
    }

    /** Зовётся один раз, чтобы сработал init-блок объекта. */
    fun init() = Unit
}
