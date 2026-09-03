package dev.vvoleman.refurbishedeu.mail

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.PathfinderMob
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.ai.goal.FloatGoal
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.nbt.CompoundTag
import java.util.UUID

/**
 * The visible half of a delivery. Its lifetime belongs to a MailRoute, not to
 * the world: MailRouteService spawns one when a route becomes observable and
 * removes it when the route goes back to dead reckoning.
 */
class MailmanEntity(type: EntityType<out PathfinderMob>, level: Level) : PathfinderMob(type, level) {

    var routeId: UUID? = null
    var carried: ItemStack = ItemStack.EMPTY

    /** Set by MailRouteService when the mailman is materialised. */
    var destination: BlockPos? = null

    init {
        // PathNavigation's constructor sizes the node budget at
        // floor(FOLLOW_RANGE * 16) = 768 here, and A* explores in order of
        // g + h. Walking OUT of a ravine means first walking AWAY from the
        // mailbox, which raises h and sorts those nodes to the back of the
        // queue - so the budget is spent on the dead ends near the floor and
        // the search ends before it ever reaches the node the exit starts
        // from. That is the "there is a way out but it cannot see it" case.
        //
        // This costs nothing on a path that succeeds: PathFinder's budget is a
        // loop bound, and the loop breaks the moment a target is reached. It is
        // only spent where the search is currently failing - which is exactly
        // where we want more of it. RepathPlanner's backoff is what keeps a
        // failing search from being re-run every tick at this larger size.
        navigation.setMaxVisitedNodesMultiplier(PATH_SEARCH_MULTIPLIER)
    }

    override fun registerGoals() {
        goalSelector.addGoal(0, FloatGoal(this))
        goalSelector.addGoal(1, DeliverMailGoal(this) { it.destination })
        // Above TravelToTargetGoal: when there is water in the way, the boat
        // goal must win Flag.MOVE rather than the walking goal wading in.
        goalSelector.addGoal(2, UseBoatGoal(this) { it.destination })
        goalSelector.addGoal(3, TravelToTargetGoal(this) { it.destination })
        goalSelector.addGoal(9, LookAtPlayerGoal(this, Player::class.java, 6.0f))
    }

    /** Its lifecycle belongs to the route, so vanilla despawn rules must not touch it. */
    override fun removeWhenFarAway(distance: Double): Boolean = false

    /**
     * Always invulnerable, regardless of what it is carrying. A delivery must
     * not be lost to a skeleton in a chunk the sender will never visit; the
     * route is the authority on where the mail is, and a dead mailman would
     * orphan it.
     */
    override fun hurt(source: DamageSource, amount: Float): Boolean = false

    /**
     * Never written into the chunk on save. This entity is only ever a VIEW
     * of a MailRoute (MailRouteService owns the durable state), so letting
     * vanilla persist it independently is actively harmful: MailRouteService
     * finds its mailmen with ServerLevel.getEntity(UUID), which only sees
     * LOADED entities. If this were saved, a chunk unload before
     * MailRouteService.dematerialise runs would leave the entity written to
     * disk with route.entity cleared to null on the route side - an orphan
     * with no owner that removeWhenFarAway (false, above) will never despawn
     * and that /kill cannot touch, since hurt() is unconditionally false.
     * Worse, when that chunk reloads near a player, materialise() would spawn
     * a second mailman for the same route, since route.entity is null. Owning
     * persistence exclusively through the route sidesteps all of that.
     */
    override fun shouldBeSaved(): Boolean = false

    /**
     * A materialised mailman must not be able to leave route.level on its
     * own - walking through a nether portal would produce the same
     * two-entities-for-one-route outcome as the save-on-unload bug above,
     * just via a different door.
     */
    override fun canChangeDimensions(): Boolean = false

    /**
     * Belt-and-braces cleanup for any orphan already sitting in a world from
     * before shouldBeSaved()/canChangeDimensions() were locked down above, or
     * for any other way a route could end up not claiming the entity that
     * claims to belong to it: if nothing in MailRouteService currently
     * recognises this mailman as the entity driving its route, there is
     * nobody left who will ever call discard() on it, so it does so itself.
     */
    override fun tick() {
        super.tick()
        val id = routeId ?: return
        val serverLevel = level as? ServerLevel ?: return
        val claimed = MailRouteService.get(serverLevel).routes().any { it.id == id && it.entity == uuid }
        if (!claimed) discard()
    }

    override fun addAdditionalSaveData(tag: CompoundTag) {
        super.addAdditionalSaveData(tag)
        routeId?.let { tag.putUUID("RouteId", it) }
        if (!carried.isEmpty) tag.put("Carried", carried.save(CompoundTag()))
    }

    override fun readAdditionalSaveData(tag: CompoundTag) {
        super.readAdditionalSaveData(tag)
        routeId = if (tag.hasUUID("RouteId")) tag.getUUID("RouteId") else null
        carried = if (tag.contains("Carried")) ItemStack.of(tag.getCompound("Carried")) else ItemStack.EMPTY
    }

    companion object {
        /**
         * Multiplies the pathfinding node budget, taking it from A* giving up
         * after 768 visited nodes to 3072. Sized to see round a ravine wall
         * without letting one search dominate a tick: a vanilla zombie's whole
         * budget is 560, so a mailman mid-search is worth about five of them,
         * and only while it is failing to find a way out.
         */
        const val PATH_SEARCH_MULTIPLIER = 4.0f

        fun attributes(): AttributeSupplier.Builder = PathfinderMob.createMobAttributes()
            .add(Attributes.MAX_HEALTH, 20.0)
            .add(Attributes.MOVEMENT_SPEED, 0.5)
            .add(Attributes.FOLLOW_RANGE, 48.0)
    }
}
