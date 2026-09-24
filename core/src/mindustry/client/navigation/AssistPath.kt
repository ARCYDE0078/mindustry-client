package mindustry.client.navigation

import arc.*
import arc.math.*
import arc.math.geom.*
import arc.struct.*
import arc.util.*
import mindustry.Vars.*
import mindustry.client.ClientVars.*
import mindustry.client.communication.*
import mindustry.entities.units.*
import mindustry.game.*
import mindustry.gen.*
import mindustry.input.*
import mindustry.world.blocks.defense.turrets.BaseTurret
import java.util.*
import kotlin.math.abs
import kotlin.math.max

class AssistPath(val assisting: Player?, val type: Type = Type.Regular, var circling: Boolean = false) : Path() { // FINISHME: This class should handle ratelimits (simply ignore actions when at the limit)
    private var show: Boolean = true
    private var plans = Seq<BuildPlan>()
    private var tolerance = 0F
    private var aStarTolerance = 0F
    private var buildPath: BuildPath? = if (type == Type.BuildPath) BuildPath.Self() else null
    private var theta: Float = 0F
    private var orbitRadius = 0f
    private val orbitPos = Vec2()
    /** Круг-ассист заморожен: орбита зашла бы в радиус вражеской турели, стоим в безопасной точке. */
    private var orbitPaused = false
    private val safeHoldOffset = Vec2()
    private val transferTimer = Interval()

    init {
        lastAssisted = null
        lastType = type
    }

    companion object { // Events.remove is weird, so we just create the hooks once instead
        private var lastType: Type = Type.Regular

        enum class OrbitShape {
            /** Classic circular orbit. */
            circle,
            /** Square orbit (chebyshev projection of circle). */
            square,
            /** 5-point star orbit. */
            star,
            /** Stay fixed behind the assisted unit (no orbit motion needed). */
            hold,
            /** Walk back and forth along the unit's facing line. */
            line,
            /** Figure-8 (lemniscate) around the assisted unit. */
            figure8;

            companion object {
                fun fromSetting(): OrbitShape {
                    val raw = Core.settings.getString("circleassistshape", "circle")
                        ?.lowercase(Locale.ROOT)
                        ?.replace("-", "")
                        ?.replace("_", "")
                        ?: "circle"
                    // aliases
                    val key = when (raw) {
                        "8", "eight", "fig8", "figureeight", "lemniscate" -> "figure8"
                        "holdpos", "stay" -> "hold"
                        "lines", "patrol" -> "line"
                        else -> raw
                    }
                    return entries.find { it.name.equals(key, ignoreCase = true) } ?: circle
                }
            }
        }

        /**
         * Offset on the assist route for the current shape.
         * @param facingDeg assisted unit rotation in degrees (used by hold/line)
         */
        fun orbitOffset(
            theta: Float,
            radius: Float,
            shape: OrbitShape = OrbitShape.fromSetting(),
            out: Vec2 = Vec2(),
            facingDeg: Float = 0f
        ): Vec2 {
            if (radius <= 0f) return out.setZero()
            val face = facingDeg * Mathf.degRad
            return when (shape) {
                OrbitShape.circle -> out.set(Mathf.cos(theta) * radius, Mathf.sin(theta) * radius)
                OrbitShape.square -> {
                    val cx = Mathf.cos(theta)
                    val cy = Mathf.sin(theta)
                    val m = max(abs(cx), abs(cy)).coerceAtLeast(1e-4f)
                    out.set(cx * radius / m, cy * radius / m)
                }
                OrbitShape.star -> {
                    val points = 10 // 5 tips * 2
                    val t = Mathf.mod(theta / Mathf.PI2, 1f) * points
                    val i0 = t.toInt() % points
                    val i1 = (i0 + 1) % points
                    val f = t - Mathf.floor(t)
                    val inner = radius * 0.38f
                    fun rad(i: Int) = if (i % 2 == 0) radius else inner
                    fun ang(i: Int) = -Mathf.PI / 2f + i * (Mathf.PI / 5f)
                    out.set(
                        Mathf.lerp(Mathf.cos(ang(i0)) * rad(i0), Mathf.cos(ang(i1)) * rad(i1), f),
                        Mathf.lerp(Mathf.sin(ang(i0)) * rad(i0), Mathf.sin(ang(i1)) * rad(i1), f)
                    )
                }
                // Fixed position behind the assisted unit
                OrbitShape.hold -> out.set(-Mathf.cos(face) * radius, -Mathf.sin(face) * radius)
                // Patrol along facing axis: -radius … +radius
                OrbitShape.line -> {
                    val along = Mathf.sin(theta) * radius
                    out.set(Mathf.cos(face) * along, Mathf.sin(face) * along)
                }
                // Lemniscate of Gerono (∞), rotated to unit facing
                OrbitShape.figure8 -> {
                    val s = Mathf.sin(theta)
                    val c = Mathf.cos(theta)
                    val lx = s * radius
                    val ly = s * c * radius
                    // rotate local (lx, ly) by facing
                    out.set(
                        lx * Mathf.cos(face) - ly * Mathf.sin(face),
                        lx * Mathf.sin(face) + ly * Mathf.cos(face)
                    )
                }
            }
        }
        private var lastAssisted: String? = null
        init {
            Events.on(EventType.DepositEvent::class.java) {
                val assisting = (Navigation.currentlyFollowing as? AssistPath)?.assisting ?: return@on
                if (it.player != assisting || ratelimitRemaining <= 1) return@on
                Call.transferInventory(player, it.tile)
            }
            Events.on(EventType.WithdrawEvent::class.java) {
                val assisting = (Navigation.currentlyFollowing as? AssistPath)?.assisting ?: return@on
                if (it.player != assisting || ratelimitRemaining <= 1) return@on
                Call.requestItem(player, it.tile, it.item, it.amount)
            }
            Events.on(EventType.UnitChangeEventClient::class.java) {
                if (it.player.hasLoadedMap || it.oldUnit != null || it.player.name != lastAssisted) return@on
                if (Navigation.currentlyFollowing == null && lastAssisted != null && (control.input as? DesktopInput)?.moved == false) {
                    Navigation.follow(AssistPath(it.player, lastType, Core.settings.getBool("circleassist")))
                }
            }
        }
    }

    override fun reset() {}

    override fun setShow(show: Boolean) {
        this.show = show
    }

    override fun getShow() = show

    /**
     * Advance the orbit only while the next point is outside enemy turret range.
     * If the circle would enter a turret, freeze rotation and stand at a safe
     * point still inside [orbitRadius] of the assisted player (so we can keep helping).
     */
    private fun updateSafeOrbit() {
        val ax = assisting!!.x
        val ay = assisting.y
        val shape = OrbitShape.fromSetting()
        val speed = Core.settings.getFloat("circleassistspeed", 0f)

        var tryTheta = theta
        if (shape != OrbitShape.hold) {
            // Заданную скорость режем до возможностей юнита: точка орбиты не должна убегать быстрее, чем он летит/плывёт.
            // Периметр фигуры в радиусах: круг 2π, остальные фигуры длиннее - берём с запасом 8.
            val perimeter = orbitRadius * (if (shape == OrbitShape.circle) Mathf.PI2 else 8f)
            val maxRevs = if (perimeter > 0f) player.unit().speed() * 60f * 0.8f / perimeter else speed
            var revs = Math.min(Math.abs(speed), maxRevs) * Math.signum(speed)
            // Юнит отстал от точки орбиты - ждём его, чтобы он не гонялся за убегающей целью и не дёргался
            val lag = Mathf.dst(player.x, player.y, ax + orbitPos.x, ay + orbitPos.y)
            if (lag > tilesize * 3f + player.unit().hitSize) revs = 0f
            tryTheta = Mathf.mod(theta + Time.delta / 60f * revs * Mathf.PI2, Mathf.PI2)
        }

        orbitOffset(tryTheta, orbitRadius, out = orbitPos, facingDeg = assisting.unit().rotation)
        val nx = ax + orbitPos.x
        val ny = ay + orbitPos.y

        if (enemyTurretCovers(nx, ny)) {
            val holdX = ax + safeHoldOffset.x
            val holdY = ay + safeHoldOffset.y
            val holdStillGood = orbitPaused
                && safeHoldOffset.len() <= orbitRadius + tilesize
                && !enemyTurretCovers(holdX, holdY)

            if (!holdStillGood) {
                if (findSafeAssistPoint(ax, ay, orbitRadius, player.x, player.y, safeHoldOffset)) {
                    safeHoldOffset.sub(ax, ay)
                } else {
                    // No safe help spot in radius — stay put rather than fly into the turret
                    safeHoldOffset.set(player.x - ax, player.y - ay)
                    if (safeHoldOffset.len() > orbitRadius) safeHoldOffset.setLength(orbitRadius)
                }
            }
            orbitPos.set(safeHoldOffset)
            orbitPaused = true
            return
        }

        if (orbitPaused) {
            // Path is clear again — resume from our current angle so we do not jump
            theta = Mathf.mod(Mathf.atan2(player.x - ax, player.y - ay), Mathf.PI2)
            orbitPaused = false
            orbitOffset(theta, orbitRadius, out = orbitPos, facingDeg = assisting.unit().rotation)
            if (enemyTurretCovers(ax + orbitPos.x, ay + orbitPos.y)) {
                // Snapped angle is still in the turret (edge case) — stay frozen
                orbitPaused = true
                orbitPos.set(safeHoldOffset)
                return
            }
        } else {
            theta = tryTheta
        }
    }

    /** True if an enemy turret that can hit our unit covers (x, y). */
    private fun enemyTurretCovers(x: Float, y: Float): Boolean {
        val u = player.unit() ?: return false
        val flying = u.isFlying
        val extra = u.hitSize * 0.5f + tilesize
        val self = player.team()
        var covered = false
        Groups.build.each { b ->
            if (covered || b == null || !b.isValid) return@each
            if (b.team == self || b.team == Team.derelict) return@each
            val tb = b as? BaseTurret.BaseTurretBuild ?: return@each
            if (flying && !tb.targetAir()) return@each
            if (!flying && !tb.targetGround()) return@each
            val r = tb.range() + extra
            if (b.within(x, y, r)) covered = true
        }
        return covered
    }

    /**
     * Find a point within [radius] of (ax, ay) that is not in enemy turret range.
     * Prefers staying near (fromX, fromY) so we do not run through the turret to the far side.
     * @return true and writes world coords into [out]
     */
    private fun findSafeAssistPoint(
        ax: Float,
        ay: Float,
        radius: Float,
        fromX: Float,
        fromY: Float,
        out: Vec2
    ): Boolean {
        if (Mathf.dst(fromX, fromY, ax, ay) <= radius + tilesize && !enemyTurretCovers(fromX, fromY)) {
            out.set(fromX, fromY)
            return true
        }

        var bestD = Float.POSITIVE_INFINITY
        var found = false
        val steps = 24
        val rings = floatArrayOf(0.3f, 0.55f, 0.8f, 1f)
        for (ring in rings) {
            val r = radius * ring
            for (i in 0 until steps) {
                val ang = i * Mathf.PI2 / steps
                val px = ax + Mathf.cos(ang) * r
                val py = ay + Mathf.sin(ang) * r
                if (enemyTurretCovers(px, py)) continue
                val d = Mathf.dst2(fromX, fromY, px, py)
                if (d < bestD) {
                    bestD = d
                    out.set(px, py)
                    found = true
                }
            }
        }
        return found
    }

    @Synchronized
    override fun follow() {
        if (player?.dead() != false) return
        assisting?.unit() ?: return // We don't care if they are dead

        aStarTolerance = assisting.unit().hitSize * Core.settings.getFloat("assistdistance", 5f) + tilesize * 5
        tolerance = if(circling) Math.max(1f, player.unit().speed() * 1.5f) else assisting.unit().hitSize * Core.settings.getFloat("assistdistance", 5f)
        orbitRadius = if(circling) assisting.unit().hitSize / 2 + 8 * Core.settings.getFloat("assistdistance", 5f) else 0f

        handleInput()

        if (player.unit() is Minerc && assisting.unit() is Minerc) { // Code stolen from formationAi.java, matches player mine state to assisting
            val mine = player.unit() as Minerc
            val com = assisting.unit() as Minerc
            if (com.mineTile() != null && mine.validMine(com.mineTile())) {
                mine.mineTile(com.mineTile())

                val core = player.unit().team.core()

                if (core != null && com.mineTile().drop() != null && player.unit().within(core, player.unit().type.range) && !player.unit().acceptsItem(com.mineTile().drop())) {
                    if (core.acceptStack(player.unit().stack.item, player.unit().stack.amount, player.unit()) > 0 && transferTimer.get(60F)) {
                        Call.transferInventory(player, core)
                    }
                }
            } else {
                mine.mineTile(null)
            }
        }

        if (assisting.isBuilder && player.isBuilder && assisting.unit().updateBuilding && assisting.team() == player.team()) {
            plans.forEach { player.unit().removeBuild(it.x, it.y, it.breaking) }
            plans.clear()
            for (plan in assisting.unit().plans) {
                if (BuildPlanCommunicationSystem.isNetworking(plan)) continue
                plans.add(plan)
                player.unit().addBuild(plan, false)
            }
        } else { // The player isn't building, we shouldn't be either.
            plans.forEach { player.unit().removeBuild(it.x, it.y, it.breaking) }
            plans.clear()
        }

    }

    private fun handleInput() {
        if (player?.dead() != false) return
        assisting?.unit() ?: return // We don't care if they are dead

        val unit = player.unit()
        val shouldShoot =
            type != Type.BuildPath &&
            (assisting.unit().isShooting || // Target shooting
            player.shooting && Core.input.keyDown(Binding.select)) // Player not following and shooting
        val aimPos =
            if ((type == Type.Regular || type == Type.Cursor) && assisting.unit().isShooting) Tmp.v1.set(assisting.unit().aimX, assisting.unit().aimY) // Following or shooting
            else if (unit.type.faceTarget) Core.input.mouseWorld() else Tmp.v1.trns(unit.rotation, Core.input.mouseWorld().dst(unit)).add(unit.x, player.unit().y) // Not following, not shooting
        if (circling && orbitRadius > 0f) updateSafeOrbit()
        else {
            orbitPaused = false
            orbitPos.setZero()
        }

        val lookPos =
            if (assisting.unit().isShooting && unit.type.faceTarget) player.angleTo(assisting.unit().aimX, assisting.unit().aimY) // Assisting is shooting and player has fixed weapons
            else if (unit.type.omniMovement && player.shooting && unit.type.hasWeapons() && unit.type.faceTarget && !(unit is Mechc && unit.isFlying())) Angles.mouseAngle(unit.x, unit.y)
            // sonka: во время ассиста (с орбитой или без) держим курс на точку, куда идём, а не prefRotation()/vel().angle() -
            // prefRotation() при isLocal()==true берёт угол вектора скорости, а на подходе/остановке скорость почти
            // нулевая и её угол дёргается на случайные значения (часто к 0°/"вправо"), из-за чего юнит дёргался
            else if (type == Type.Regular || type == Type.Cursor) {
                val gx = if (type == Type.Cursor) assisting.mouseX + orbitPos.x else assisting.x + orbitPos.x
                val gy = if (type == Type.Cursor) assisting.mouseY + orbitPos.y else assisting.y + orbitPos.y
                if (unit.within(gx, gy, tilesize.toFloat())) player.angleTo(assisting.x, assisting.y) else player.angleTo(gx, gy)
            }
            else player.unit().prefRotation() // Anything else (free move, build path)

        player.shooting(shouldShoot)
        unit.aim(aimPos)
        unit.lookAt(lookPos)

        when (type) {
            Type.Regular -> goTo(assisting.x + orbitPos.x, assisting.y + orbitPos.y, tolerance, aStarTolerance + tilesize * 5)
            Type.FreeMove -> {
                val input = control.input
                if (input is DesktopInput) {
                    if (input.movement.epsilonEquals(0f, 0f)) {
                        if (Core.settings.getBool("zerodrift")) unit.vel.setZero()
                        else if (Core.settings.getBool("decreasedrift") && unit.vel().len() > 3.5) unit.vel.set(unit.vel().scl(0.95f))
                    }
                    else player.unit().moveAt(input.movement)
                } else player.unit().moveAt((input as MobileInput).movement)
            }
            Type.Cursor -> goTo(assisting.mouseX + orbitPos.x, assisting.mouseY + orbitPos.y, tolerance, tolerance + tilesize * 5)
            Type.BuildPath -> if (!plans.isEmpty || !(unit.plans?.isEmpty?: true)) buildPath?.follow() else goTo(assisting.x + orbitPos.x, assisting.y + orbitPos.y, tolerance, aStarTolerance + tilesize * 5) // Follow build path if plans exist, otherwise follow player
        }
    }

    @Synchronized
    override fun draw() {
        assisting ?: return
        if (type != Type.FreeMove && player.dst(assisting) > aStarTolerance) waypoints.draw()

        // if (Spectate.pos != assisting) assisting.unit()?.drawBuildPlans() // Don't draw plans twice
    }

    override fun progress(): Float {
        if (assisting?.isAdded == false) {
            (control.input as? DesktopInput)?.moved = false
            lastAssisted = assisting.name
        }
        return if (assisting == null || !assisting.isAdded) 1f else 0f
    }

    override fun next(): Position? {
        return null
    }

    enum class Type {
        Regular,
        FreeMove,
        Cursor,
        BuildPath,
    }
}
