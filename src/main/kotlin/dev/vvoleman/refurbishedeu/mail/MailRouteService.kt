package dev.vvoleman.refurbishedeu.mail

import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.Mth
import net.minecraft.world.level.Level
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.phys.Vec3
import net.minecraft.world.item.ItemStack
import com.mrcrayfish.furniture.refurbished.blockentity.MailboxBlockEntity
import com.mrcrayfish.furniture.refurbished.mail.DeliveryService
import dev.vvoleman.refurbishedeu.RefurbishedEuBridge
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
                // whole mail system once 32 of these accumulate.
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
        val destination = destinationOf(route) ?: return handleUnresolvedDestination(level, route)
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
            materialise(level, route, destination)
        } else {
            dematerialise(level, route)
            false
        }

        if (!driving) {
            route.pos = Travel.advance(route.pos, destVec, MailmanConfig.blocksPerSecond(), 1)
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
            // Only mailboxes in loaded chunks are swept; an unloaded one has
            // nothing ticking to have put mail in it since the last sweep.
            if (level.chunkSource.getChunkNow(origin.pos.x shr 4, origin.pos.z shr 4) == null) continue
            val be = level.getBlockEntity(origin.pos) as? MailboxBlockEntity ?: continue
            sweepOne(be, origin)
        }
    }

    private fun sweepOne(be: MailboxBlockEntity, origin: MailboxRef) {
        for (slot in 0 until be.containerSize) {
            val stack = be.getItem(slot)
            if (stack.isEmpty) continue
            if (!isOurMail(stack)) continue
            val target = addressOf(stack) ?: continue
            if (target.equals(origin.name, ignoreCase = true)) continue

            // Restrict to this dimension BEFORE resolving the name. byName picks the
            // nearest match and compares raw block positions, so a same-named mailbox in
            // another dimension could otherwise win on a meaningless coordinate distance
            // and the mail would be dropped here - even though a valid local one exists.
            val local = cachedMailboxes.filter { it.level == origin.level }
            val destination = MailboxIndex.byName(local, target, origin.pos) ?: continue
            if (destination.id == origin.id) continue

            val route = MailRoute(
                id = UUID.randomUUID(),
                stack = stack.copy(),
                originId = origin.id,
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

    private fun destinationOf(route: MailRoute): BlockPos? {
        val wanted = if (route.state == RouteState.RETURNING) route.originId else route.targetId
        return mailboxFor(wanted, route.level)?.pos
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
            return finish(level, route)
        }
        if (mailboxFor(route.originId, route.level) == null) {
            return finish(level, route)
        }
        route.state = RouteState.RETURNING
        route.lastDistance = Double.MAX_VALUE
        route.stalledTicks = 0
        setDirty()
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
    private fun materialise(level: ServerLevel, route: MailRoute, destination: BlockPos): Boolean {
        val existing = route.entity?.let { level.getEntity(it) as? MailmanEntity }
        if (existing != null && existing.isAlive) {
            route.pos = existing.position()
            existing.destination = destination
            return true
        }
        if (route.entity != null) {
            // Stale reference - whatever was here is gone. Nothing to clean
            // up beyond forgetting it; materialisedCount() already ignores it.
            route.entity = null
        }
        if (materialisedCount(level) >= MailmanConfig.maxMaterialisedMailmen()) return false

        val mob = RefurbishedEuBridge.MAILMAN.get().create(level) ?: return false
        // y is meaningless while dead-reckoned, so it is resolved here.
        val x = Mth.floor(route.pos.x)
        val z = Mth.floor(route.pos.z)
        val surface = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z)
        mob.moveTo(route.pos.x, surface.toDouble(), route.pos.z, 0.0f, 0.0f)
        mob.routeId = route.id
        // A copy: route.stack and mob.carried must not be the same mutable
        // instance shared by two owners that both serialise it independently.
        mob.carried = route.stack.copy()
        mob.destination = destination
        level.addFreshEntity(mob)
        route.entity = mob.uuid
        setDirty()
        return true
    }

    private fun dematerialise(level: ServerLevel, route: MailRoute) {
        val id = route.entity ?: return
        (level.getEntity(id) as? MailmanEntity)?.let {
            route.pos = it.position()
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
        // Refused - queue full, or the mailbox is gone. Take it home.
        if (route.state == RouteState.RETURNING) {
            // Origin refused it too; there is nowhere left to put it.
            dematerialise(level, route)
            return true
        }
        route.state = RouteState.RETURNING
        route.lastDistance = Double.MAX_VALUE
        route.stalledTicks = 0
        setDirty()
        return false
    }

    private fun checkStall(level: ServerLevel, route: MailRoute, destVec: Vec3, driving: Boolean): Boolean {
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
        // A materialised mailman legitimately moves away from its
        // destination sometimes - routing around a lake, backing out of a
        // dead end - in a way straight-line dead reckoning never does, so it
        // gets a longer leash before that reads as stalled rather than as a
        // detour.
        val timeout = MailmanConfig.stallTimeoutTicks() * (if (driving) MATERIALISED_STALL_MULTIPLIER else 1)
        if (route.stalledTicks < timeout) return false

        // No progress for long enough: open water, or a mailbox that can't be walked to.
        if (route.state == RouteState.RETURNING) return finish(level, route)
        route.state = RouteState.RETURNING
        route.lastDistance = Double.MAX_VALUE
        route.stalledTicks = 0
        setDirty()
        return false
    }

    private fun finish(level: ServerLevel, route: MailRoute): Boolean {
        dematerialise(level, route)
        return true
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
        private const val STORAGE_ID = "refurbished_eu_mail_routes"

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
