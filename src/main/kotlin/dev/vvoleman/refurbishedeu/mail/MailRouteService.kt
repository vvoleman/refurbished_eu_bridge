package dev.vvoleman.refurbishedeu.mail

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.Mth
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.phys.Vec3
import com.mrcrayfish.furniture.refurbished.blockentity.MailboxBlockEntity
import com.mrcrayfish.furniture.refurbished.mail.DeliveryService
import dev.vvoleman.refurbishedeu.RefurbishedEuBridge
import org.apache.logging.log4j.LogManager
import java.util.UUID

/**
 * Every delivery in flight, and the thing that decides whether each one is
 * currently a real entity or just arithmetic.
 */
class MailRouteService : SavedData() {

    private val routes = mutableListOf<MailRoute>()
    private var cachedMailboxes: List<MailboxRef> = emptyList()
    private var mailboxById: Map<UUID, MailboxRef> = emptyMap()
    private var indexAge = Int.MAX_VALUE

    /**
     * Throttles [sweep] (the periodic scan of every mailbox for outgoing
     * mail) the same way indexAge throttles refreshIndex.
     */
    private var tickCounter = 0

    /** A defensive copy: the backing list must only ever be mutated through this class. */
    fun routes(): List<MailRoute> = routes.toList()
    fun mailboxes(): List<MailboxRef> = cachedMailboxes

    fun add(route: MailRoute): Boolean {
        if (routes.size >= MailmanConfig.maxActiveRoutes()) return false
        routes.add(route)
        setDirty()
        return true
    }

    /**
     * Rebuilt on an interval rather than per query: save() allocates a tag for
     * every mailbox on the server, so calling it per lookup would be wasteful.
     */
    private fun refreshIndex(server: MinecraftServer) {
        if (indexAge++ < MailmanConfig.indexRefreshTicks()) return
        indexAge = 0
        val service = DeliveryService.get(server).orElse(null) ?: return
        cachedMailboxes = MailboxIndex.parse(service.save(CompoundTag()))
        mailboxById = cachedMailboxes.associateBy { it.id }
    }

    fun tick(server: MinecraftServer) {
        refreshIndex(server)
        tickCounter++
        // Runs to completion (and may append to routes via add()) before the
        // iterator below is even created, so it cannot invalidate that
        // iterator or otherwise interfere with the delivery loop it drives.
        // A route swept in this tick is simply ticked once already this same
        // tick, which is harmless - it starts driving one tick sooner.
        sweep(server)
        val iterator = routes.iterator()
        while (iterator.hasNext()) {
            val route = iterator.next()
            val level = server.getLevel(route.level)
            if (level == null) {
                // The dimension itself is gone - a removed datapack
                // dimension, most likely. There is nothing left to tick this
                // route against, and leaving it in the list would park it
                // against maxActiveRoutes forever, eventually wedging the
                // whole mail system once 32 of these accumulate. There is no
                // level to drop the carried stack into either, so a WARN log
                // naming the loss is the best this can do.
                if (!route.stack.isEmpty) {
                    LOGGER.warn(
                        "Mail route {} lost: dimension {} no longer exists - carried stack {} could not be recovered and was destroyed",
                        route.id, route.level.location(), route.stack,
                    )
                }
                iterator.remove()
                setDirty()
                continue
            }
            if (tickRoute(level, route)) {
                iterator.remove()
                setDirty()
            }
        }
    }

    /** @return true when the route is finished and should be dropped. */
    private fun tickRoute(level: ServerLevel, route: MailRoute): Boolean {
        val mailbox = destinationOf(route) ?: return handleUnresolvedDestination(level, route)
        val destination = mailbox.pos
        val destVec = Vec3(destination.x + 0.5, destination.y.toDouble(), destination.z + 0.5)

        // Whether an entity was already driving this route as of the end of
        // last tick, so a fresh materialisation can be told apart from a
        // route that has been walking for a while.
        val wasDriving = route.entity != null

        // materialise() returns false for every reason no entity ends up
        // driving the route this tick - the materialised-mailmen cap was
        // reached, EntityType.create() returned null, whatever. Dead
        // reckoning must pick up the slack whenever that happens, not only
        // when the route was never observable in the first place - otherwise
        // a route that is observable but capped out simply stops advancing,
        // its stall timer keeps running against an unmoving position, and it
        // is eventually deleted for "failing to progress" while frozen.
        val driving = if (isObservable(level, route.pos)) {
            materialise(level, route, mailbox)
        } else {
            dematerialise(level, route)
            false
        }

        if (!driving) {
            route.pos = Travel.advance(route.pos, destVec, MailmanConfig.blocksPerSecond(), 1)
            // Horizontal-only, so from here on route.pos.y is stale until
            // something re-anchors it to a real position (materialise's
            // create-branch snaps to the surface for exactly this reason).
            route.yTrustworthy = false
            // Dead reckoning is the only progress this route will ever make
            // without a materialised entity around to trigger setDirty()
            // itself (materialise/dematerialise/deliver all do). Without
            // this, a purely dead-reckoning route only gets saved once, when
            // add() first flags it dirty, and every restart after that
            // rewinds it to wherever it happened to be at the last autosave.
            setDirty()
        } else if (!wasDriving) {
            // Just materialised. Forget whatever stall bookkeeping dead
            // reckoning built up - a real mailman pathing around an obstacle
            // must be judged against where it started walking, not against
            // arithmetic's straight-line best.
            route.lastDistance = Travel.horizontalDistance(route.pos, destVec)
            route.stalledTicks = 0
        }

        if (Travel.horizontalDistance(route.pos, destVec) <= ARRIVAL_RANGE) {
            return deliver(level, route, destination)
        }
        return checkStall(level, route, destVec, driving)
    }

    private fun mailboxFor(id: UUID, level: ResourceKey<Level>): MailboxRef? =
        mailboxById[id]?.takeIf { it.level == level }

    /**
     * Turns addressed mail sitting in a mailbox into a route.
     *
     * The stack is removed from the mailbox as the route is created, so the mail
     * is in exactly one place at every moment - never both in a container and in
     * flight.
     */
    private fun sweep(server: MinecraftServer) {
        if (tickCounter % MailmanConfig.pickupScanTicks() != 0) return
        for (origin in cachedMailboxes) {
            val level = server.getLevel(origin.level) ?: continue
            // Refurbished's own allow-list, honoured on top of our
            // same-dimension rule: a dimension it considers undeliverable
            // must not gain routes either, even though nothing here reads
            // that flag anywhere else.
            if (!DeliveryService.isDeliverableDimension(level)) continue
            // Only mailboxes in loaded chunks are swept; an unloaded one has
            // nothing ticking to have put mail in it since the last sweep.
            if (level.chunkSource.getChunkNow(origin.pos.x shr 4, origin.pos.z shr 4) == null) continue
            val be = level.getBlockEntity(origin.pos) as? MailboxBlockEntity ?: continue
            sweepOne(be, origin)
        }
    }

    private fun sweepOne(be: MailboxBlockEntity, origin: MailboxRef) {
        // Hoisted out of the per-slot loop: this doesn't depend on any slot's
        // data, only on which mailbox is being swept, so recomputing it per
        // addressed stack found in a many-slot mailbox would be pure waste -
        // MailboxIndex.resolveDestination (via byName) allocates lists and
        // lowercases every candidate name on every call.
        val local = cachedMailboxes.filter { it.level == origin.level }
        for (slot in 0 until be.containerSize) {
            val stack = be.getItem(slot)
            if (stack.isEmpty) continue
            if (!isOurMail(stack)) continue
            val target = addressOf(stack) ?: continue
            if (target.trim().equals(origin.name?.trim(), ignoreCase = true)) continue

            // A stack carried back here by a previous failed delivery is left
            // alone until re-addressed to something other than what it just
            // failed to reach - see beginReturn's doc comment.
            val returnedFrom = MailAddress.returnedFrom(stack)
            if (returnedFrom != null && returnedFrom.trim().equals(target.trim(), ignoreCase = true)) continue

            // local is already restricted to this dimension; resolveDestination's
            // own filter over it is then just a cheap no-op pass, not a rescan of
            // every mailbox on the server. See its doc comment for why the filter
            // has to happen before byName's nearest-match logic at all.
            val destination = MailboxIndex.resolveDestination(local, origin, target) ?: continue

            val route = MailRoute(
                id = UUID.randomUUID(),
                stack = stack.copy(),
                // The live block entity's id, not origin.id from the (up to
                // indexRefreshTicks-stale) cached index: a mailbox broken and
                // replaced on the same spot gets a fresh random id from its
                // constructor, and recording the dead cached one here would
                // later make handleUnresolvedDestination treat this route's
                // own still-existing origin as gone and delete undeliverable
                // mail instead of returning it.
                originId = be.id,
                targetId = destination.id,
                level = origin.level,
                pos = Vec3(origin.pos.x + 0.5, origin.pos.y.toDouble(), origin.pos.z + 0.5),
                state = RouteState.TRAVELLING,
            )
            if (add(route)) {
                be.setItem(slot, ItemStack.EMPTY)
                be.setChanged()
            }
            return
        }
    }

    private fun isOurMail(stack: ItemStack): Boolean =
        stack.item == RefurbishedEuBridge.LETTER.get() || stack.item == RefurbishedEuBridge.PARCEL.get()

    private fun addressOf(stack: ItemStack): String? =
        MailAddress.target(stack) ?: LetterItem.targetFromName(stack)

    /**
     * Returns the whole mailbox, not just its position, because the mailman's
     * name tag needs the name too and this is the only place the two are
     * resolved together - looking the name up separately would repeat the
     * lookup on every tick of every route.
     */
    private fun destinationOf(route: MailRoute): MailboxRef? {
        val wanted = if (route.state == RouteState.RETURNING) route.originId else route.targetId
        return mailboxFor(wanted, route.level)
    }

    /**
     * The wanted mailbox (target, or origin while returning) can't be found -
     * broken by a player, most likely. Deleting the route outright would
     * destroy the mail even though the origin mailbox usually still exists
     * and is the obvious place to send it back to, so this only gives up
     * when the origin is unresolvable too.
     */
    private fun handleUnresolvedDestination(level: ServerLevel, route: MailRoute): Boolean {
        if (route.state == RouteState.RETURNING) {
            // Already heading home, and even that mailbox can't be found now.
            return finish(level, route, "the origin mailbox could not be resolved while returning")
        }
        if (mailboxFor(route.originId, route.level) == null) {
            return finish(level, route, "neither the target nor the origin mailbox could be resolved")
        }
        beginReturn(route)
        return false
    }

    /**
     * Loaded and close enough that somebody could see it. getChunkNow never
     * loads anything itself, which is the whole point - a delivery must not drag
     * chunks along behind it.
     */
    private fun isObservable(level: ServerLevel, pos: Vec3): Boolean {
        val chunkX = Mth.floor(pos.x) shr 4
        val chunkZ = Mth.floor(pos.z) shr 4
        if (level.chunkSource.getChunkNow(chunkX, chunkZ) == null) return false
        return level.players().any { it.distanceToSqr(pos.x, it.y, pos.z) < OBSERVE_RANGE_SQR }
    }

    /** @return true when an entity is now (or still) driving this route. */
    private fun materialise(level: ServerLevel, route: MailRoute, mailbox: MailboxRef): Boolean {
        val destination = mailbox.pos
        val existing = route.entity?.let { level.getEntity(it) as? MailmanEntity }
        if (existing != null && existing.isAlive) {
            route.pos = existing.position()
            route.yTrustworthy = true
            existing.destination = destination
            // Re-stamped every tick rather than only at spawn, which is what
            // makes beginReturn's flip to RETURNING show up above the head
            // without a separate hook: the state changes, and the next tick
            // through here relabels whatever is already walking.
            label(existing, route, mailbox)
            // Throttled, not skipped: a continuously-materialised route
            // never takes the dead-reckoning setDirty() path, so without
            // this its position and stall bookkeeping would only ever be
            // saved once (at add()) and rewind to that on every restart.
            if (tickCounter % PERSIST_THROTTLE_TICKS == 0) setDirty()
            return true
        }
        if (route.entity != null) {
            // Stale reference - whatever was here is gone. Nothing to clean
            // up beyond forgetting it; materialisedCount() already ignores it.
            route.entity = null
        }
        if (materialisedCount(level) >= MailmanConfig.maxMaterialisedMailmen()) return false

        val mob = RefurbishedEuBridge.MAILMAN.get().create(level) ?: return false
        val x = Mth.floor(route.pos.x)
        val z = Mth.floor(route.pos.z)
        // Only snap to the surface when route.pos.y is actually meaningless -
        // i.e. it has been dead-reckoned (horizontal-only) since this route
        // last had a real, trustworthy position. A y fresh off the origin
        // mailbox, or off a real entity's last stood position, must be kept
        // as-is: snapping it unconditionally is what used to spawn an indoor
        // or basement mailbox's mailman on the roof.
        val y = if (route.yTrustworthy) {
            route.pos.y
        } else {
            level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z).toDouble()
        }
        mob.moveTo(route.pos.x, y, route.pos.z, 0.0f, 0.0f)
        mob.routeId = route.id
        // A copy: route.stack and mob.carried must not be the same mutable
        // instance shared by two owners that both serialise it independently.
        mob.carried = route.stack.copy()
        mob.destination = destination
        label(mob, route, mailbox)
        level.addFreshEntity(mob)
        route.entity = mob.uuid
        route.yTrustworthy = true
        setDirty()
        return true
    }

    /**
     * Names the mailman after where it is going, so a player can see which
     * delivery they are looking at.
     *
     * The name is deliberately left NOT visible: MobRenderer.shouldShowName
     * renders a custom name either when the visible flag is set - always, and
     * through terrain - or, with the flag clear, only while the entity is
     * under the crosshair. The second is what we want; with up to
     * maxMaterialisedMailmen out at once, always-on tags would be noise.
     *
     * Assigned only when it actually differs. This runs every tick for every
     * materialised route, and CustomName is synched entity data - rewriting an
     * unchanged Component would still be a wasted comparison per mailman, and
     * relies on Component equality to avoid a packet.
     */
    private fun label(mob: MailmanEntity, route: MailRoute, mailbox: MailboxRef) {
        val wanted = MailmanLabel.text(
            mailbox = mailbox.name,
            pos = mailbox.pos,
            returning = route.state == RouteState.RETURNING,
        )
        if (mob.customName?.string == wanted) return
        mob.customName = Component.literal(wanted)
    }

    private fun dematerialise(level: ServerLevel, route: MailRoute) {
        val id = route.entity ?: return
        (level.getEntity(id) as? MailmanEntity)?.let {
            route.pos = it.position()
            route.yTrustworthy = true
            // A mailman mid-crossing owns a boat. Dropping the mailman without
            // it would strand the boat on the water: MailBoatEntity discards
            // itself when riderless, but only while its chunk still ticks, and
            // the route is the explicit owner here.
            (it.vehicle as? MailBoatEntity)?.discard()
            it.discard()
        }
        route.entity = null
        setDirty()
    }

    private fun materialisedCount(level: ServerLevel): Int =
        routes.count { it.entity != null && level.getEntity(it.entity!!) != null }

    private fun deliver(level: ServerLevel, route: MailRoute, destination: BlockPos): Boolean {
        val be = level.getBlockEntity(destination) as? MailboxBlockEntity
        if (be != null && be.deliverItem(route.stack.copy())) {
            dematerialise(level, route)
            return true
        }
        // Refused - queue full (mailboxes are only mailboxInventoryRows * 9
        // slots, and the sweep drains one item per mailbox per pickupScanTicks,
        // so this is the ordinary case, not an edge case), or the mailbox is
        // gone entirely. Take it home.
        if (route.state == RouteState.RETURNING) {
            // Origin refused it too; there is nowhere left to put it in a
            // mailbox. getBlockEntity above just force-loaded this chunk, so
            // the destination position is a good, loaded place to drop it.
            val dropPos = Vec3(destination.x + 0.5, destination.y.toDouble(), destination.z + 0.5)
            return finish(level, route, "the origin mailbox refused the returned item (full, or gone)", dropPos)
        }
        beginReturn(route)
        return false
    }

    private fun checkStall(level: ServerLevel, route: MailRoute, destVec: Vec3, driving: Boolean): Boolean {
        // Throttled, not skipped: unlike the dead-reckoning branch in
        // tickRoute, a driven route's lastDistance/stalledTicks bookkeeping
        // below has no other setDirty() call on its path, so without this a
        // continuously-materialised route would only ever be saved once (at
        // add()) and rewind its stall progress on every restart.
        if (driving && tickCounter % PERSIST_THROTTLE_TICKS == 0) setDirty()
        val distance = Travel.horizontalDistance(route.pos, destVec)
        // The progress threshold depends on what is actually moving the
        // route, and the two must not be conflated:
        //  - Dead reckoning always covers exactly Travel.perTickStep(bps)
        //    per tick, so progress must beat a fraction of THAT - a fixed
        //    constant bigger than the smallest legal speed's per-tick step
        //    (blocksPerSecond can be configured as low as 0.1) would mean
        //    dead reckoning could never be recognised as progressing at all.
        //  - A materialised mailman walks at its own entity speed
        //    (MOVEMENT_SPEED, unrelated to blocksPerSecond entirely), so
        //    tying its threshold to bps too means a fast-configured server
        //    (bps up to the documented 100) demands more per-tick progress
        //    than a walking mob can ever produce, and a driven route reads
        //    as permanently stalled. A small constant sized for a walking
        //    entity is what the threshold used to be before bps-derived
        //    epsilon existed, and it belongs here, not the dead-reckoning one.
        val epsilon = if (driving) {
            DRIVEN_PROGRESS_EPSILON
        } else {
            Travel.perTickStep(MailmanConfig.blocksPerSecond()) * DEAD_RECKONING_EPSILON_FACTOR
        }
        if (distance < route.lastDistance - epsilon) {
            route.lastDistance = distance
            route.stalledTicks = 0
            return false
        }
        route.stalledTicks++
        // Deliberately BEFORE the timeout check and deliberately without
        // resetting stalledTicks: a hop buys distance, not time. Resetting the
        // clock would let a mailman that cannot path anywhere hop to its
        // mailbox one nudge at a time and never be judged undeliverable.
        if (driving) hopIfStuck(level, route, destVec)
        // A materialised mailman legitimately moves away from its
        // destination sometimes - routing around a lake, backing out of a
        // dead end - in a way straight-line dead reckoning never does, so it
        // gets a longer leash before that reads as stalled rather than as a
        // detour.
        val timeout = MailmanConfig.stallTimeoutTicks() * (if (driving) MATERIALISED_STALL_MULTIPLIER else 1)
        if (route.stalledTicks < timeout) return false

        // No progress for long enough: open water, or a mailbox that can't be walked to.
        if (route.state == RouteState.RETURNING) {
            return finish(level, route, "the route stalled ($timeout ticks with no progress) while returning to origin")
        }
        beginReturn(route)
        return false
    }

    /**
     * Nudges a mailman that has stopped getting anywhere a short way along the
     * straight line to its mailbox.
     *
     * Raising the A* node budget makes the search see further round a ravine
     * wall, but some terrain has no walkable way out at all, and there the
     * mailman would spend the entire stall budget failing. This is the escape
     * hatch, and it is the same trick the system already relies on: a route
     * that nobody is watching advances by straight-line arithmetic, because
     * route.pos - not the entity - is the durable thing. This just applies one
     * step of that while somebody IS watching, and moves the entity to match.
     *
     * Silent and rare by design. It only fires once no progress has been made
     * for half the stall timeout, and then only every half-timeout after that.
     */
    private fun hopIfStuck(level: ServerLevel, route: MailRoute, destVec: Vec3) {
        val blocks = MailmanConfig.stuckHopBlocks()
        if (blocks <= 0) return
        val interval = MailmanConfig.stallTimeoutTicks() / HOP_STALL_DIVISOR
        if (interval <= 0 || route.stalledTicks % interval != 0) return
        val mob = route.entity?.let { level.getEntity(it) as? MailmanEntity } ?: return
        // A passenger cannot be repositioned out from under its vehicle without
        // leaving the boat behind; UseBoatGoal's own timeout covers a wedged
        // crossing.
        if (mob.isPassenger) return

        val target = Travel.hop(route.pos, destVec, blocks.toDouble())
        val preferred = BlockPos(Mth.floor(target.x), Mth.floor(route.pos.y), Mth.floor(target.z))
        val landing = LandingSpot.find(preferred, HOP_LANDING_RADIUS) { isStandable(level, it) } ?: return

        mob.moveTo(landing.x + 0.5, landing.y.toDouble(), landing.z + 0.5, mob.yRot, mob.xRot)
        // The path it was walking described the old position; keeping it would
        // steer the mailman back to where it just came from.
        mob.navigation.stop()
        route.pos = Vec3(landing.x + 0.5, landing.y.toDouble(), landing.z + 0.5)
        route.yTrustworthy = true
        setDirty()
    }

    /**
     * Whether a mailman could stand with its feet in this block.
     *
     * A missing chunk counts as unstandable rather than loading it, on the same
     * principle as isObservable and UseBoatGoal.isSurfaceWater: a delivery must
     * not drag chunks along behind it. Requiring air at both the feet and the
     * head also rules out water and lava, since neither is air.
     */
    private fun isStandable(level: ServerLevel, pos: BlockPos): Boolean {
        if (level.chunkSource.getChunkNow(pos.x shr 4, pos.z shr 4) == null) return false
        if (!level.getBlockState(pos).isAir) return false
        if (!level.getBlockState(pos.above()).isAir) return false
        val floor = pos.below()
        return level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP)
    }

    /**
     * The single place a route stops being tracked. Every path that ends a
     * route without having placed its stack in a mailbox goes through here,
     * so a future one cannot bypass the guarantee that a carried stack is
     * never simply discarded: it is dropped into the world as an item
     * instead, and a WARN is logged naming the route and why. [pos] defaults
     * to the route's own last known position, which is the best available
     * stand-in for "the mailbox" when no mailbox could actually be resolved.
     */
    private fun finish(level: ServerLevel, route: MailRoute, reason: String, pos: Vec3 = route.pos): Boolean {
        if (!route.stack.isEmpty) {
            LOGGER.warn(
                "Mail route {} lost: {} - dropping carried stack {} at {} in {}",
                route.id, reason, route.stack, pos, route.level.location(),
            )
            level.addFreshEntity(ItemEntity(level, pos.x, pos.y, pos.z, route.stack.copy()))
        }
        dematerialise(level, route)
        return true
    }

    /**
     * Flips a route from TRAVELLING to RETURNING and stamps its carried stack
     * with the target it just failed to reach.
     *
     * Without the stamp, a returned stack lands back in its origin mailbox
     * still addressed exactly as it was (the address tag/custom name is never
     * cleared), so the next sweep would pick it straight back up and send it
     * on the same doomed trip forever - one of [MailmanConfig.maxActiveRoutes]
     * permanently occupied and a mailman respawned every cycle, with the
     * player never told delivery failed. Keying the stamp on the target
     * string (rather than a bare "already returned" flag) means a player who
     * re-addresses the stack to somewhere else changes the very value being
     * compared, so it naturally becomes sweepable again with no separate
     * clearing step - while re-addressing it to the SAME failed target is
     * correctly still left alone.
     */
    private fun beginReturn(route: MailRoute) {
        addressOf(route.stack)?.let { MailAddress.markReturned(route.stack, it) }
        route.state = RouteState.RETURNING
        route.lastDistance = Double.MAX_VALUE
        route.stalledTicks = 0
        setDirty()
    }

    override fun save(tag: CompoundTag): CompoundTag {
        val list = ListTag()
        routes.forEach { list.add(it.save()) }
        tag.put("Routes", list)
        return tag
    }

    companion object {
        private const val ARRIVAL_RANGE = 2.0
        private const val OBSERVE_RANGE_SQR = 128.0 * 128.0
        private const val DEAD_RECKONING_EPSILON_FACTOR = 0.5
        // The old fixed epsilon, kept as-is for the driven case: a walking
        // mailman easily clears 0.05 blocks/tick at any normal entity speed,
        // and unlike the dead-reckoning epsilon this one has no dependency on
        // blocksPerSecond to get right.
        private const val DRIVEN_PROGRESS_EPSILON = 0.05
        private const val MATERIALISED_STALL_MULTIPLIER = 4

        /**
         * A stuck mailman is nudged every stallTimeoutTicks / this. Two means
         * the first nudge lands halfway to being declared undeliverable, which
         * leaves the second half of the budget to walk the rest normally.
         */
        private const val HOP_STALL_DIVISOR = 2

        /**
         * How far above or below the straight line a nudge may look for ground.
         * Wide enough to clear a ravine lip, narrow enough that it cannot drop
         * the mailman down a shaft it was standing beside.
         */
        private const val HOP_LANDING_RADIUS = 8
        // How often a continuously-materialised route's position and stall
        // bookkeeping get flagged dirty, in ticks. These fields have no other
        // setDirty() call on their path (unlike dead reckoning, which calls
        // it every tick), so without any throttle at all they would either
        // never persist or, at the other extreme, mark the whole service
        // dirty every single tick for every driven route purely to save a
        // handful of doubles that only matter across a restart. 100 ticks
        // (5 seconds) bounds how much of a walking mailman's progress a
        // crash can rewind without costing more than that.
        private const val PERSIST_THROTTLE_TICKS = 100
        private const val STORAGE_ID = "refurbished_eu_mail_routes"
        private val LOGGER = LogManager.getLogger()

        fun get(level: ServerLevel): MailRouteService =
            level.server.overworld().dataStorage.computeIfAbsent(
                { tag -> load(tag) },
                { MailRouteService() },
                STORAGE_ID,
            )

        private fun load(tag: CompoundTag): MailRouteService {
            val service = MailRouteService()
            tag.getList("Routes", Tag.TAG_COMPOUND.toInt()).forEach { entry ->
                MailRoute.load(entry as CompoundTag)?.let { service.routes.add(it) }
            }
            return service
        }
    }
}
