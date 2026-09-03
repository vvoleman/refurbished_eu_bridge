package dev.vvoleman.refurbishedeu.mail

import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.vehicle.Boat
import net.minecraft.world.level.Level

/**
 * The boat a mailman crosses water in. Its lifetime belongs to the crossing,
 * not to the world.
 */
class MailBoatEntity(type: EntityType<out Boat>, level: Level) : Boat(type, level) {

    /**
     * Never written into the chunk on save, for the reason MailmanEntity
     * documents at length: an entity owned by a route must not be persisted
     * independently of it. A chunk unload mid-crossing would otherwise leave a
     * boat sitting on the lake with nobody left to discard it.
     */
    override fun shouldBeSaved(): Boolean = false

    /**
     * One seat, which is load-bearing rather than cosmetic. Boat.tick() boards
     * any colliding LivingEntity while `passengers.size() < getMaxPassengers()`
     * whenever the pilot is not a Player - and ours never is. With a single
     * seat that test is already false with the mailman aboard, so mail boats
     * do not collect livestock on the way across.
     */
    override fun getMaxPassengers(): Int = 1

    /**
     * Drops nothing. The boat belongs to the delivery, not to the world, so
     * there is no oak boat in it to hand out - vanilla Boat.destroy would give
     * a player one free boat per mailman, and one hit in creative at that.
     */
    override fun destroy(source: DamageSource) = Unit

    /**
     * Belt-and-braces cleanup, mirroring MailmanEntity.tick() from the other
     * side: a mail boat with no mailman in it has no owner who will ever
     * discard it, so it does so itself. The grace period covers the gap between
     * being spawned and being mounted, which spans at least one tick.
     */
    override fun tick() {
        super.tick()
        if (level.isClientSide) return
        if (tickCount < BOARDING_GRACE_TICKS) return
        if (passengers.none { it is MailmanEntity }) discard()
    }

    companion object {
        const val BOARDING_GRACE_TICKS = 20
    }
}
