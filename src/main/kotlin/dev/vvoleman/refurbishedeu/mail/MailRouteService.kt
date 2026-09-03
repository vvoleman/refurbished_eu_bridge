package dev.vvoleman.refurbishedeu.mail

import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.MobSpawnType
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.phys.Vec3
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
    private var indexAge = Int.MAX_VALUE
    private var tickCounter = 0

    fun routes(): List<MailRoute> = routes
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
    }

    fun tick(server: MinecraftServer) {
        refreshIndex(server)
        tickCounter++
        val iterator = routes.iterator()
        while (iterator.hasNext()) {
            val route = iterator.next()
            val level = server.getLevel(route.level)
            if (level == null) continue
            if (tickRoute(level, route)) {
                iterator.remove()
                setDirty()
            }
        }
    }

    /** @return true when the route is finished and should be dropped. */
    private fun tickRoute(level: ServerLevel, route: MailRoute): Boolean {
        val destination = destinationOf(route) ?: return finish(level, route)
        val destVec = Vec3(destination.x + 0.5, destination.y.toDouble(), destination.z + 0.5)

        if (isObservable(level, route.pos)) {
            materialise(level, route, destination)
        } else {
            dematerialise(level, route)
            route.pos = Travel.advance(route.pos, destVec, MailmanConfig.blocksPerSecond(), 1)
        }

        if (Travel.horizontalDistance(route.pos, destVec) <= ARRIVAL_RANGE) {
            return deliver(level, route, destination)
        }
        return checkStall(level, route, destVec)
    }

    private fun destinationOf(route: MailRoute): BlockPos? {
        val wanted = if (route.state == RouteState.RETURNING) route.originId else route.targetId
        return cachedMailboxes.firstOrNull { it.id == wanted }?.pos
    }

    /**
     * Loaded and close enough that somebody could see it. getChunkNow never
     * loads anything itself, which is the whole point - a delivery must not drag
     * chunks along behind it.
     */
    private fun isObservable(level: ServerLevel, pos: Vec3): Boolean {
        val chunkX = (pos.x.toInt()) shr 4
        val chunkZ = (pos.z.toInt()) shr 4
        if (level.chunkSource.getChunkNow(chunkX, chunkZ) == null) return false
        return level.players().any { it.distanceToSqr(pos.x, it.y, pos.z) < OBSERVE_RANGE_SQR }
    }

    private fun materialise(level: ServerLevel, route: MailRoute, destination: BlockPos) {
        val existing = route.entity?.let { level.getEntity(it) as? MailmanEntity }
        if (existing != null && existing.isAlive) {
            route.pos = existing.position()
            existing.destination = destination
            return
        }
        if (materialisedCount(level) >= MailmanConfig.maxMaterialisedMailmen()) return

        val mob = RefurbishedEuBridge.MAILMAN.get().create(level) ?: return
        // y is meaningless while dead-reckoned, so it is resolved here.
        val surface = level.getHeight(Heightmap.Types.WORLD_SURFACE, route.pos.x.toInt(), route.pos.z.toInt())
        mob.moveTo(route.pos.x, surface.toDouble(), route.pos.z, 0.0f, 0.0f)
        mob.routeId = route.id
        mob.carried = route.stack
        mob.destination = destination
        level.addFreshEntity(mob)
        route.entity = mob.uuid
        setDirty()
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

    private fun checkStall(level: ServerLevel, route: MailRoute, destVec: Vec3): Boolean {
        val distance = Travel.horizontalDistance(route.pos, destVec)
        if (distance < route.lastDistance - STALL_EPSILON) {
            route.lastDistance = distance
            route.stalledTicks = 0
            return false
        }
        route.stalledTicks++
        if (route.stalledTicks < MailmanConfig.stallTimeoutTicks()) return false

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
        private const val STALL_EPSILON = 0.05
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
