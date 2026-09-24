package mindustry.client.utils

import arc.*
import arc.math.*
import arc.struct.*
import arc.util.*
import mindustry.Vars.*
import mindustry.core.World
import mindustry.game.*
import mindustry.game.EventType.*
import mindustry.gen.Groups
import mindustry.type.*
import mindustry.world.*
import mindustry.world.blocks.defense.turrets.*

/**
 * Карта "красных зон": тайлы в радиусе поражения вражеских турелей и вооружённых вражеских юнитов + запас в 5 тайлов.
 * Турельный слой пересчитывается редко (они не двигаются), юниты сверху - раз в секунду.
 * Идея из safeMining/MinersFDAI клиента fk4b, но без остального ИИ - только зоны и поиск руды вне них.
 */
object DangerZones {
    private const val MARGIN_TILES = 5
    private const val TURRET_SCAN_MS = 10_000L
    private const val UNIT_SCAN_MS = 1_000L

    private var w = 0
    private var h = 0
    private var turretZone = BooleanArray(0)
    private var zone = BooleanArray(0)
    private var turretAt = 0L
    private var unitAt = 0L

    init {
        Events.on(WorldLoadEvent::class.java) { turretAt = 0L } // новая карта - старая сетка недействительна даже при том же размере
    }

    private fun enemy(team: Team) = team != player.team() && team != Team.derelict

    private fun ensure() {
        if (world == null || world.width() <= 0) return
        val sizeOk = w == world.width() && h == world.height()
        if (!sizeOk || Time.timeSinceMillis(turretAt) >= TURRET_SCAN_MS) {
            scanTurrets()
        } else if (Time.timeSinceMillis(unitAt) >= UNIT_SCAN_MS) {
            System.arraycopy(turretZone, 0, zone, 0, zone.size)
            paintUnits()
        }
    }

    private fun scanTurrets() {
        w = world.width()
        h = world.height()
        if (turretZone.size != w * h) {
            turretZone = BooleanArray(w * h)
            zone = BooleanArray(w * h)
        } else turretZone.fill(false)

        val margin = MARGIN_TILES * tilesize
        Groups.build.each { b ->
            if (b.isValid && enemy(b.team) && b is BaseTurret.BaseTurretBuild) {
                paint(turretZone, b.x, b.y, b.range() + margin)
            }
        }

        System.arraycopy(turretZone, 0, zone, 0, zone.size)
        paintUnits()
        turretAt = Time.millis()
    }

    private fun paintUnits() {
        val margin = MARGIN_TILES * tilesize
        Groups.unit.each { u ->
            if (u.isValid && enemy(u.team) && u.type.hasWeapons()) {
                val range = maxOf(u.range(), u.type.maxRange).let { if (it <= 0f) u.hitSize else it }
                paint(zone, u.x, u.y, range + margin)
            }
        }
        unitAt = Time.millis()
    }

    private fun paint(grid: BooleanArray, px: Float, py: Float, range: Float) {
        if (range <= 0f) return
        val r2 = range * range
        val rTiles = Mathf.ceil(range / tilesize) + 1
        val cx = World.toTile(px)
        val cy = World.toTile(py)
        for (ty in (cy - rTiles).coerceAtLeast(0)..(cy + rTiles).coerceAtMost(h - 1)) {
            for (tx in (cx - rTiles).coerceAtLeast(0)..(cx + rTiles).coerceAtMost(w - 1)) {
                val dx = tx * tilesize + tilesize / 2f - px
                val dy = ty * tilesize + tilesize / 2f - py
                if (dx * dx + dy * dy <= r2) grid[ty * w + tx] = true
            }
        }
    }

    fun isDangerous(tile: Tile?): Boolean {
        if (tile == null) return false
        ensure()
        return tile.x < w && tile.y < h && zone.isNotEmpty() && zone[tile.y * w + tile.x]
    }
}

/** Выбор руды для MinePath так, чтобы юнит не летел копать под вражеские турели/юниты. */
object SafeMining {
    private const val RECHECK_MS = 1_000L

    private class Entry(val tile: Tile?, val at: Long)
    private val cache = ObjectMap<Item, Entry>()
    private val notified = ObjectSet<Item>()

    init {
        Events.on(WorldLoadEvent::class.java) {
            cache.clear()
            notified.clear()
        }
    }

    val enabled get() = Core.settings.getBool("safemining")

    /** Ближайшая к юниту руда предмета вне красной зоны или null, если вся руда этого предмета под огнём. Результат кэшируется на секунду. */
    fun oreFor(unit: mindustry.gen.Unit, item: Item): Tile? {
        val cached = cache[item]
        if (cached != null && Time.timeSinceMillis(cached.at) < RECHECK_MS) {
            val t = cached.tile
            if (t == null || !DangerZones.isDangerous(t)) return t
        }

        val tile = indexer.findClosestMineableOre(unit, item) { !DangerZones.isDangerous(it) }
        cache.put(item, Entry(tile, Time.millis()))
        return tile
    }

    fun notifyUnavailable(item: Item) {
        if (notified.add(item)) player.sendMessage(Core.bundle.format("client.path.miner.nosafe", item.localizedName))
    }
}
