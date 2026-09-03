package dev.vvoleman.refurbishedeu.mail

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

    override fun registerGoals() {
        goalSelector.addGoal(0, FloatGoal(this))
        goalSelector.addGoal(9, LookAtPlayerGoal(this, Player::class.java, 6.0f))
        // Travel and delivery goals are added in Task 11.
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
        fun attributes(): AttributeSupplier.Builder = PathfinderMob.createMobAttributes()
            .add(Attributes.MAX_HEALTH, 20.0)
            .add(Attributes.MOVEMENT_SPEED, 0.5)
            .add(Attributes.FOLLOW_RANGE, 48.0)
    }
}
