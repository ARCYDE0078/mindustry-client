package mindustry.client.utils

import arc.*
import arc.math.*
import arc.struct.*
import arc.util.*
import eui.interact.InteractTimer
import mindustry.Vars.*
import mindustry.client.ClientVars.*
import mindustry.client.navigation.*
import mindustry.client.ui.Toast
import mindustry.content.*
import mindustry.entities.bullet.*
import mindustry.gen.*
import mindustry.graphics.*
import mindustry.type.*
import mindustry.world.Block
import mindustry.world.blocks.defense.turrets.*
import mindustry.world.blocks.power.NuclearReactor.*
import mindustry.world.blocks.production.*
import mindustry.world.blocks.production.Drill.*
import mindustry.world.blocks.production.GenericCrafter.*
import mindustry.world.blocks.storage.*
import mindustry.world.blocks.storage.Unloader.*
import mindustry.world.consumers.*
import kotlin.math.*

/** An auto transfer setup based on Ferlern/extended-ui */
class AutoTransfer {
    companion object Settings {
        // All of these settings (aside from debug) are overwritten on init()
        @JvmField var enabled = false
        var fromCores = false
        @JvmField var fromContainers = false
        @JvmField var minCoreItems = -1
        var debug = false
        @JvmField var minTransferTotal = -1
        @JvmField var minTransfer = -1
        var drain = false
        var drainToContainers = false

        /** Blocks whose [priority] is at (or below) this value are excluded from auto transfer entirely. */
        const val EXCLUDE_PRIORITY = -2

        /**
         * Per-block-type service priorities, shared with [eui.interact.AutofillPriorityDialog] (settings
         * key `eui.autofill.priority`, range -2..5, default 0): within a transfer round, higher-priority
         * blocks are serviced first (before falling back to the usual accepted-stack ordering), and
         * [EXCLUDE_PRIORITY] (-2) keeps a block from being filled at all. Merged here from the baked-in
         * Extended UI++ port's own `eui.interact.AutoFill` loop when that duplicate of this class was
         * removed - the priority dialog now configures this native AutoTransfer instead (it's reachable
         * from the eui bottom panel and from Settings > Client > "Auto Transfer block priorities").
         * Note the priority only affects service *order* and exclusion, not which item gets fetched from
         * the core - item choice stays needed-amount-based. Values are re-read once per transfer round
         * (delay-gated, ~1/s), so dialog edits apply immediately without a listener.
         */
        @Suppress("UNCHECKED_CAST")
        fun loadPriorities(): ObjectMap<String, Any?> =
            Core.settings.getJson("eui.autofill.priority", ObjectMap::class.java) { ObjectMap<String, Any?>() } as ObjectMap<String, Any?>

        /** Defensive [Number] cast: the dialog writes Integers, but JSON round-trips are untrusted. */
        fun priority(priorities: ObjectMap<String, Any?>, build: Building): Int =
            (priorities.get(build.block.name) as? Number)?.toInt() ?: 0

        /**
         * Per-turret, per-ammo settings shared with [eui.interact.AmmoPriorityDialog] (sonka's
         * request, 2026-09-12): keyed by `"<turret block name>|<ammo item name>"`.
         * - `eui.autofill.ammo.priority` (range -2..5, default 0): among a turret's eligible ammo
         *   types, the highest-priority one wins over [getAmmoScore]'s DPS estimate - ties (including
         *   the common all-default-0 case) still fall back to DPS, so an unconfigured turret behaves
         *   exactly as before. [EXCLUDE_PRIORITY] (-2) keeps that specific ammo from ever being
         *   fetched for that turret, even if it's the only thing available.
         * - `eui.autofill.ammo.mincore` (default -1 = "no override"): overrides the caller's
         *   `minItems` (normally the global [minCoreItems] slider) for this one turret+ammo pair, so
         *   e.g. a scarce ammo type can require a much bigger core stockpile before turrets start
         *   eating it, without raising the floor for every other item.
         * Both re-read once per candidate scan (delay-gated, ~1/s via the same round as
         * [loadPriorities]), so dialog edits apply immediately without a listener.
         */
        @Suppress("UNCHECKED_CAST")
        fun loadAmmoPriorities(): ObjectMap<String, Any?> =
            Core.settings.getJson("eui.autofill.ammo.priority", ObjectMap::class.java) { ObjectMap<String, Any?>() } as ObjectMap<String, Any?>

        @Suppress("UNCHECKED_CAST")
        fun loadAmmoMinCores(): ObjectMap<String, Any?> =
            Core.settings.getJson("eui.autofill.ammo.mincore", ObjectMap::class.java) { ObjectMap<String, Any?>() } as ObjectMap<String, Any?>

        private fun ammoKey(turret: Block, item: Item) = "${turret.name}|${item.name}"

        fun ammoPriority(config: ObjectMap<String, Any?>, turret: Block, item: Item): Int =
            (config.get(ammoKey(turret, item)) as? Number)?.toInt() ?: 0

        /** Returns -1 (no override) if unset, matching [eui.interact.AmmoPriorityDialog]'s "use the global minimum" default. */
        fun ammoMinCore(config: ObjectMap<String, Any?>, turret: Block, item: Item): Int =
            (config.get(ammoKey(turret, item)) as? Number)?.toInt() ?: -1

        // Reactive backoff: on top of InteractTimer's fixed "eui-action-delay" pacing (which only honors
        // what the user configured, not what a given server actually allows), stretch the delay further
        // whenever the server itself warns that we're interacting too fast (Administration's antispam
        // action filter sends "[scarlet]You are interacting with blocks too quickly." at most once per
        // ~2s while over its interactRateLimit but under the interactRateKick threshold - see
        // NetClient.sendMessage, which forwards that specific message to [onServerRateLimitWarning]).
        // Multiplies the *effective* delay rather than replacing it, and eases back off on its own once
        // the warnings stop, so a server with generous limits is never slowed down for no reason.
        private var rateLimitMultiplier = 1f
        private var lastRateLimitWarning = -1000f
        private var rateLimitCooldownUntil = 0f
        private const val RATE_LIMIT_GROWTH = 1.5f
        private const val RATE_LIMIT_MAX_MULTIPLIER = 8f
        private const val RATE_LIMIT_DECAY_AFTER = 5f // seconds of no fresh warning before easing off
        private const val RATE_LIMIT_DECAY = 0.7f

        @JvmStatic
        fun onServerRateLimitWarning() {
            rateLimitMultiplier = (rateLimitMultiplier * RATE_LIMIT_GROWTH).coerceAtMost(RATE_LIMIT_MAX_MULTIPLIER)
            lastRateLimitWarning = Time.time
            if (debug) Log.info("AutoTransfer: server rate-limit warning, backing off to @x delay", rateLimitMultiplier)
        }

        /** Eases [rateLimitMultiplier] back towards 1 once warnings have stopped for a while. */
        private fun decayRateLimitBackoff() {
            if (rateLimitMultiplier <= 1f) return
            if (Time.time - lastRateLimitWarning <= RATE_LIMIT_DECAY_AFTER) return
            rateLimitMultiplier = (rateLimitMultiplier * RATE_LIMIT_DECAY).coerceAtLeast(1f)
            lastRateLimitWarning = Time.time // restart the quiet window for the next decay step
        }

        /**
         * Call after every actual transfer/fetch action instead of a bare [InteractTimer.increase] -
         * additionally stretches [rateLimitCooldownUntil] while [rateLimitMultiplier] is backed off, so
         * the *next* round waits roughly `eui-action-delay * rateLimitMultiplier` instead of the plain
         * configured delay.
         */
        private fun markActionTaken() {
            InteractTimer.increase()
            if (rateLimitMultiplier > 1f) {
                val baseDelaySeconds = Core.settings.getInt("eui-action-delay", 500) / 1000f
                rateLimitCooldownUntil = Time.time + Time.toSeconds * baseDelaySeconds * (rateLimitMultiplier - 1f)
            }
        }

        fun init() {
            // Main settings
            enabled = Core.settings.getBool("autotransfer", false)
            fromCores = Core.settings.getBool("autotransfer-fromcores", true)
            fromContainers = Core.settings.getBool("autotransfer-fromcontainers", true)
            minCoreItems = Core.settings.getInt("autotransfer-mincoreitems", 100)
            minTransferTotal = Core.settings.getInt("autotransfer-mintransfertotal", 10)
            minTransfer = Core.settings.getInt("autotransfer-mintransfer", 2)
            // Drain settings, undocumented for now as drain is still experimental
            drain = Core.settings.getBool("autotransfer-drain", false)
            drainToContainers = Core.settings.getBool("autotransfer-draintocontainers", false)
        }
    }

    val builds = Seq<Building>(false) // Not ordered as we sort *after* mutation is finished.
    val containers = Seq<Building>()
    var item: Item? = null
    val counts = IntArray(content.items().size)
    val ammoCounts = IntArray(content.items().size)
    val dpsCounts = FloatArray(content.items().size)
    var core: Building? = null
    var justTransferred = false

    fun draw() {
        if (!debug || player.unit().item() == null) return
        builds.forEach {
            val accepted = it.acceptStack(player.unit().item(), player.unit().stack.amount, player.unit())
            Drawf.select(it.x, it.y, it.block.size * tilesize / 2f + 2f, if (accepted >= Mathf.clamp(player.unit().stack.amount, 1, 5)) Pal.place else Pal.noplace)
        }
    }

    fun update() {
        if (!enabled) return
        if (state.rules.onlyDepositCore) {
            // Only catches a rule pushed mid-game (NetClient.setRules/setRule) after the toggle was
            // already on - the join-time computation in ClientLogic's WorldLoadEvent listener already
            // keeps AutoTransfer off from the start on maps/sectors that start with this rule set (e.g.
            // several campaign planets default to it, see Planets.java), so flip the toggle itself off
            // here too (not just skip this round) and say so once, instead of leaving a checkbox that
            // looks on but silently does nothing.
            enabled = false
            Core.settings.put("autotransfer", false)
            Toast(4f).add(Core.bundle.get("client.autotransfer.disabled-onlydepositcore"))
            return
        }
        decayRateLimitBackoff()
        // Gated by eui's own action cooldown ("eui-action-delay", also used by AutoUnit) instead of a
        // homegrown ticks-based timer, mounted straight from eui.interact.AutoFill.update() (2026-08-27,
        // at sonka's request) - see transfer()'s doc comment
        if (!InteractTimer.canInteract()) return
        if (Time.time < rateLimitCooldownUntil) return // extra room while backed off from a server warning
        if (player.dead()) return
        player.unit()?.item() ?: return
        counts.fill(0) // reset needed item counters
        ammoCounts.fill(0)
        dpsCounts.fill(0f)
        if (!justTransferred && drain && drain()) return
        justTransferred = false
        transfer()
    }

    /**
     * Transfers items from core/containers into buildings. Picks exactly ONE best target per round -
     * mounted straight from the deleted `eui.interact.AutoFill.update()` (2026-08-27, at sonka's
     * request: the settings-slider-based ticks/delay rework wasn't actually eui's own code, still felt
     * "off", and its "speed doesn't change" complaint traces back to gating this on a from-scratch
     * timer/ratelimit combo instead of the real cooldown eui.AutoFill used). Both the decision algorithm
     * (single best candidate wins the moment it's found, instead of aggregating every building's needs
     * first - dedupe #1's approach) AND the pacing (eui.interact.InteractTimer's real per-action
     * cooldown, "eui-action-delay", called via [InteractTimer.increase] below - not a periodic
     * ticks-since-last-round timer) now match the source. Depositing what the player already carries
     * always wins over fetching at equal priority, matching the source.
     */
    private fun transfer() {
        core = if (fromCores) player.closestCore() else null
        if (Navigation.currentlyFollowing is MinePath) { // Only allow autotransfer + minepath when within mineTransferRange
            if (core != null && (Navigation.currentlyFollowing as MinePath).tile?.within(core, mineTransferRange - tilesize * 10) != true) return
        } // Ngl this looks spaghetti

        val buildTree = player.team().data().buildingTree ?: return
        val stack = player.unit().stack

        buildTree.intersect(player.x - itemTransferRange, player.y - itemTransferRange, itemTransferRange * 2, itemTransferRange * 2, builds.clear()) // grab all buildings in range

        if (fromContainers && (core == null || !player.within(core, itemTransferRange))) core = containers.selectFrom(builds) { it.block is StorageBlock && (item == null || it.items.has(item)) }.min { it -> it.dst(player) }
        val fetchCore = core

        val priorities = loadPriorities()
        var bestPriority = -1
        var bestBuild: Building? = null
        var bestFetchItem: Item? = null

        builds.forEach {
            if (it.block.findConsumer<Consume?> { c -> c is ConsumeItems || c is ConsumeItemFilter || c is ConsumeItemDynamic } == null
                || it is NuclearReactorBuild || !player.within(it, itemTransferRange)) return@forEach

            val blockPriority = priority(priorities, it)
            if (blockPriority < bestPriority) return@forEach
            if (blockPriority == bestPriority && bestBuild != null) return@forEach // a deposit target at this priority already wins ties

            if (stack.amount > 0 && canDeposit(it) && it.acceptStack(stack.item, stack.amount, player.unit()) >= minTransfer) {
                bestBuild = it
                bestFetchItem = null
                bestPriority = blockPriority
                return@forEach
            }

            if (blockPriority <= bestPriority || fetchCore == null) return@forEach
            val minItems = if (fetchCore is CoreBlock.CoreBuild) minCoreItems else 1 // FINISHME: Is this else 1 right? It seems odd...
            val request = pickFetchItem(it, fetchCore, minItems)
            if (request != null) {
                bestFetchItem = request
                bestBuild = null
                bestPriority = blockPriority
            }
        }

        // Copy out of the mutable vars the forEach above closed over - Kotlin won't smart-cast a var
        // captured by a lambda that reassigns it, even after that lambda has finished running.
        val depositTarget = bestBuild
        val fetchItem = bestFetchItem
        item = fetchItem
        if (depositTarget != null) {
            depositIntoBuilding(depositTarget, stack.amount)
            markActionTaken()
            justTransferred = true
            item = null
        } else if (fetchItem != null && fetchCore != null && player.within(fetchCore, itemTransferRange)) {
            if (stack.amount > 0) Call.transferInventory(player, fetchCore) // Holding something unwanted here - drop it off first, fetch next round
            else Call.requestItem(player, fetchCore, fetchItem, Int.MAX_VALUE)
            markActionTaken()
            justTransferred = true
            item = null
        }
        // Nothing to do this round: no `timer = delay` reset needed anymore - update() is gated purely by
        // InteractTimer, so with no action taken it's untouched and the next tick just retries immediately.
    }

    /**
     * Picks the single best item for [build] to fetch from [core] next (highest-DPS ammo for turrets,
     * first eligible requirement otherwise) - the fetch half of the eui-ported greedy scan in
     * [transfer], reusing [processTransferTarget]'s consumer-type branching shape but returning one
     * winner instead of accumulating counts.
     */
    private fun pickFetchItem(build: Building, core: Building, minItems: Int): Item? {
        fun hasMinItems(item: Item, min: Int = minItems) = minItems == 0 || core.items.has(item, min)

        return when (val cons = build.block.findConsumer<Consume> { (it is ConsumeItems || it is ConsumeItemFilter || it is ConsumeItemDynamic) && it !is ConsumeItemExplode } ?: build.block.findConsumer { it is ConsumeItems || it is ConsumeItemFilter || it is ConsumeItemDynamic }) {
            is ConsumeItems -> {
                if (cons.booster) null // Don't boost menders, projectors or overdrives
                else cons.items.firstOrNull { i -> build.acceptStack(i.item, build.getMaximumAccepted(i.item), player.unit()) >= minTransfer && hasMinItems(i.item, max(i.amount, minItems)) }?.item
            }
            is ConsumeItemFilter -> {
                if (build.block is ItemTurret) {
                    // Per-turret ammo priority + min-core override (sonka's request, 2026-09-12) - see
                    // loadAmmoPriorities()/loadAmmoMinCores()'s doc comment. Priority dominates the
                    // comparison (scaled well above any realistic DPS value) and DPS only breaks ties,
                    // so a turret with no configured ammo priorities (the common case) picks exactly
                    // the same highest-DPS ammo as before.
                    val ammoPriorities = loadAmmoPriorities()
                    val ammoMinCores = loadAmmoMinCores()
                    var best: Item? = null
                    var bestScore = Float.NEGATIVE_INFINITY
                    content.items().each { i ->
                        if (i == Items.blastCompound || !build.block.consumesItem(i) || build.acceptStack(i, Int.MAX_VALUE, player.unit()) < minTransfer) return@each
                        val ammoPriority = ammoPriority(ammoPriorities, build.block, i)
                        if (ammoPriority <= EXCLUDE_PRIORITY) return@each // this ammo is excluded for this turret
                        val minCoreOverride = ammoMinCore(ammoMinCores, build.block, i)
                        val effectiveMin = if (minCoreOverride >= 0) minCoreOverride else minItems
                        // Deliberately independent of hasMinItems()'s "global minItems==0 bypasses
                        // everything" quirk (used by the other branches below) - a per-ammo override must
                        // still apply its own floor even when the global Min Core Items slider is 0.
                        if (!(effectiveMin == 0 || core.items.has(i, effectiveMin))) return@each
                        val score = ammoPriority * 1e6f + getAmmoScore((build.block as ItemTurret).ammoTypes[i])
                        if (score > bestScore) { best = i; bestScore = score }
                    }
                    best
                } else {
                    var best: Item? = null
                    content.items().each { i ->
                        if (best == null && i != Items.blastCompound && build.block.consumesItem(i) && hasMinItems(i) && build.acceptStack(i, Int.MAX_VALUE, player.unit()) >= minTransfer) best = i
                    }
                    best
                }
            }
            is ConsumeItemDynamic -> cons.items.get(build).firstOrNull { i -> build.getMaximumAccepted(i.item) - build.items.get(i.item) >= minTransfer && hasMinItems(i.item, max(i.amount, minItems)) }?.item
            else -> null
        }
    }

    /** Transfers outputs from blocks into core/containers */
    private fun drain(): Boolean { // FINISHME: Until this class is refactored to have a more generic input output system I'm just gonna copy a lot of code into this function
        core = player.closestCore() ?: return false
        val nearCore = player.within(core, itemTransferRange)
        if (!nearCore) core = null

        val buildTree = player.team().data().buildingTree ?: return false
        buildTree.intersect(player.x - itemTransferRange, player.y - itemTransferRange, itemTransferRange * 2, itemTransferRange * 2, builds.clear()) // grab all buildings in range

        val bestContainers = if (!nearCore && drainToContainers) findDrainDestinations() else emptyArray() // This uses the nearby builds so we do this after the intersect

        val priorities = loadPriorities()
        val nonContainerBuilds = builds.select { it.block.findConsumer<Consume?> { it is ConsumeItems || it is ConsumeItemFilter || it is ConsumeItemDynamic } != null && it !is NuclearReactorBuild && player.within(it, itemTransferRange) && priority(priorities, it) > EXCLUDE_PRIORITY }
            .sort { b -> priority(priorities, b) * -1e5F - b.acceptStack(player.unit().item(), player.unit().stack.amount, player.unit()).toFloat() }

        nonContainerBuilds.each { processTransferTarget(it, 0) }

        val nonContainerDrainCounts = counts.copyOf() // Direct to factory drain targets. This is scuffed but oh well
        for ((index, i) in ammoCounts.withIndex()) nonContainerDrainCounts[index] += i // Include turret ammo counts as they're separate FINISHME: hack

        // Find the drainable items
        counts.fill(0)
        processDrainSources()

        var maxID = -1
        var maxCount = 0
        if (nearCore) { // Draining to core: Drain as much as possible always (without overfilling cores)
            val playerCap = player.unit().itemCapacity()
            val reasonableCoreLimit = (core!! as CoreBlock.CoreBuild).storageCapacity - 300 - playerCap * 3 // Why is this reasonable? Because it feels right. There is no other reason.
            for (i in counts.indices) {
                val count = counts[i]
                if (count > maxCount && core!!.items.get(i) < reasonableCoreLimit) {
                    maxID = i
                    maxCount = count
                }
            }
        } else { // Draining to multiple inventories: Drain the thing that is most needed FINISHME: We should make the whole autotransfer/drain system smart enough to select the highest average items per transfer instead of just moving the most items. Moving 10 items to 3 inventories is less worth it than moving 9 items to 2 inventories as it will exhaust more ratelimit.
            for (i in nonContainerDrainCounts.indices) {
                val count = nonContainerDrainCounts[i]
                if (count > maxCount && counts[i] > minTransferTotal) {
                    maxID = i
                    maxCount = count
                }
            }
            // Nothing to drain to factories: Drain to a single container with a configured unloader
            if (maxID == -1 && drainToContainers) {
                for (i in counts.indices) {
                    val count = counts[i]
                    if (count > maxCount && bestContainers[i] != null) {
                        maxID = i
                        maxCount = count
                    }
                }
                if (maxID != -1) core = bestContainers[maxID]
            }
        }
        if (maxID == -1) return false // No core/container/factory was found, perform a normal transfer round instead

        item = if (counts[maxID] >= minTransferTotal) content.item(maxID) else null
        if (item == null) return false

        maxCount = maxCount.coerceAtMost(player.unit().maxAccepted(item))
        builds.sort { b -> b.items[maxID].toFloat() - b.getMaximumAccepted(item) }.forEach {
            if (ratelimitRemaining <= 1 || it.items[maxID] < minTransfer || maxCount < minTransfer) return@forEach // No ratelimit left or this building doesn't have enough of the item or the player unit is full

            Call.requestItem(player, it, item, maxCount)
            maxCount -= it.items[maxID]
        }

        // eui-action-delay is in ms; AutoTransfer no longer keeps its own tick-based delay field (see
        // update()'s doc comment), so convert on the spot for this one scheduled follow-up.
        Time.run(Core.settings.getInt("eui-action-delay", 500) / 1000f * 60f / 2f) {
            if (player.unit() == null) return@run // FINISHME: Should we reset the delay?
            if (core != null) { // Standard single target drain
                if (ratelimitRemaining > 1 && (maxCount != player.unit().maxAccepted(item) || maxCount == 0)) { // If theres ratelimit remaining and the player has grabbed anything or if the player is holding something else
                    if (maxCount == 0) { // We're holding something else and we need to dispose of it somehow
                        justTransferred = true // Force an autotransfer next time, draining probably won't fix much
                    }
                    if (core!!.getMaximumAccepted(item) > 0) Call.transferInventory(player, core) // Drain to the block
                }
            } else { // Drain into (possibly) multiple buildings
                var held = counts[maxID] // FINISHME: This number will probably be wrong, we should somehow fix this to save ratelimit
                nonContainerBuilds.forEach {
                    if (ratelimitRemaining <= 1) return@forEach
                    held = depositIntoBuilding(it, held)
                }
            }
        }

        return true
    }

    /** Gathers the counts for all drainable buildings. */
    private fun processDrainSources() {
        for (i in builds.size - 1 downTo 0) {
            when (val build = builds[i]) {
                is GenericCrafterBuild -> { // Crafters that are near full
                    if (!build.block.outputsItems()) builds.remove(i)
                    else if ((build.block as GenericCrafter).outputItems.any { (build.items[it.item] + it.amount) >= build.block.itemCapacity }) (build.block as GenericCrafter).outputItems.forEach { counts[it.item.id.toInt()] += build.items[it.item.id.toInt()] } // FINISHME: Use the item cap instead of shouldConsume as shouldConsume is false for disabled blocks which will cause transfer attempts not to mention that shouldConsume does more work than needed.

                }
                is DrillBuild -> { // Drills that are full
                    if (build.dominantItem == null || build.items.total() < build.block.itemCapacity) builds.remove(i)
                    else counts[build.dominantItem.id.toInt()] += build.items.total() // FINISHME: This can likely be wrong but it shouldn't matter, right?
                }
                else -> builds.remove(i)
            }
        }
    }

    /** Finds the best StorageBlock to drain to for each building. */
    private fun findDrainDestinations(): Array<Building?> {
        val has = BooleanArray(content.items().size) // We don't really care about these allocations, honestly
        val loadables = arrayOfNulls<Building>(content.items().size) // Array of the most empty container for each item type
        builds.each {
            if (it.block !is StorageBlock || it.block is CoreBlock || !player.within(it, itemTransferRange)) return@each
            has.fill(false)
            for (i in 0 ..< it.proximity.size) { // Returning from a Seq loop creates garbage, using a for i loop solves this
                val prox = it.proximity[i]
                if (prox !is UnloaderBuild) continue
                if (prox.sortItem == null) return@each // Nulloaders will cause issues, ignore containers with them FINISHME: Instead of fully ignoring them, we should just insert items that are already in the container
                has[prox.sortItem.id.toInt()] = true
            }

            val cap = it.block.itemCapacity
            for (i in has.indices) { // Iterate all containers for this item
                if (has[i]) {
                    if (loadables[i] == null) { // First container for this item, set it up
                        loadables[i] = it
                        continue
                    }
                    val container = loadables[i]!!
                    if (cap - it.items[i] > container.block.itemCapacity - container.items[i]) loadables[i] = it
                }
            }
        }
        return loadables
    }

    /** Whether depositing the player's currently-held item into [build] is safe (no self-destructing explosives, no feeding boosters). */
    private fun canDeposit(build: Building): Boolean {
        val heldItem = player.unit().item() ?: return false
        return !(heldItem == Items.blastCompound && build.block.findConsumer<ConsumeItems> { it is ConsumeItemExplode } != null // Don't explode things
            || build.block.findConsumer<ConsumeItems> { it.booster && it is ConsumeItems && it.items.any { it.item == heldItem } } != null) // Don't provide boosters
    }

    /** Attempts to make a deposit. Returns the remaining [held] value. */
    private fun depositIntoBuilding(build: Building, held: Int): Int {
        if (held <= 0 || !canDeposit(build)) return held
        val accepted = build.acceptStack(player.unit().item(), player.unit().stack.amount, player.unit())

        if (accepted <= 0) return held // FINISHME: Shouldn't we be enforcing minTransfer here too?
        Call.transferInventory(player, build)
        return held - accepted
    }

    /** Adds the possible deposits for the [build] to [counts], [ammoCounts], and [dpsCounts] as needed. */
    private fun processTransferTarget(build: Building, minItems: Int) {
        fun hasMinItems(item: Item, min: Int = minItems) = minItems == 0 || core!!.items.has(item, min)

        when (val cons = build.block.findConsumer<Consume> { (it is ConsumeItems || it is ConsumeItemFilter || it is ConsumeItemDynamic) && it !is ConsumeItemExplode } ?: build.block.findConsumer { it is ConsumeItems || it is ConsumeItemFilter || it is ConsumeItemDynamic }) { // Cursed af
            is ConsumeItems -> {
                cons.items.forEach { i ->
                    if (cons.booster) return@forEach // Don't boost menders, projectors or overdrives
                    val acceptedC = build.acceptStack(i.item, build.getMaximumAccepted(i.item), player.unit())
                    if (acceptedC >= minTransfer && hasMinItems(i.item, max(i.amount, minItems))) {
                        counts[i.item.id.toInt()] += acceptedC
                    }
                }
            }
            is ConsumeItemFilter -> {
                content.items().each { i ->
                    val acceptedC = if (i == Items.blastCompound && build.block.findConsumer<Consume> { it is ConsumeItemExplode } != null) 0 else build.acceptStack(i, Int.MAX_VALUE, player.unit())
                    if (acceptedC >= minTransfer && build.block.consumesItem(i) && hasMinItems(i)) {
                        if (build.block is ItemTurret) { // Turrets have varying ammo, add an offset to prioritize some than others
                            ammoCounts[i.id.toInt()] += acceptedC
                            dpsCounts[i.id.toInt()] += acceptedC * getAmmoScore((build.block as? ItemTurret)?.ammoTypes?.get(i))
                        } else {
                            counts[i.id.toInt()] += acceptedC
                        }
                    }
                }
            }
            is ConsumeItemDynamic -> {
                cons.items.get(build).forEach { i -> // Get the current requirements
                    val acceptedC = build.getMaximumAccepted(i.item) - build.items.get(i.item)
                    if (acceptedC >= minTransfer && hasMinItems(i.item, max(i.amount, minItems))) {
                        counts[i.item.id.toInt()] += acceptedC
                    }
                }
            }
            else -> throw IllegalStateException("This should never happen. Report this.")
        }
    }

    private fun getAmmoScore(ammo: BulletType?): Float {
        return ammo?.estimateDPS() ?: 0f
        /* Commented out for future reference in case I do need my own dps estimation function
//        return (((ammo.damage * if (ammo.pierceBuilding || ammo.pierce) ammo.pierceCap else 1) +
//                    ammo.splashDamage +
//                    ammo.fragBullets * getAmmoScore(ammo.fragBullet)
//                ) * ammo.ammoMultiplier * ammo.reloadMultiplier).toInt()
         */
    }
}
