package dev.vvoleman.refurbishedeu.mail

import net.minecraft.core.BlockPos
import net.minecraft.world.entity.ai.goal.Goal
import java.util.EnumSet

/**
 * Walks toward whatever position the supplier names. The supplier - not the
 * goal - knows about routes, which keeps this testable in isolation and lets
 * the same goal serve both delivery and the return trip.
 */
class TravelToTargetGoal(
    private val mob: MailmanEntity,
    private val targetOf: (MailmanEntity) -> BlockPos?,
) : Goal() {

    private var target: BlockPos? = null
    private var repathCooldown = 0

    init {
        flags = EnumSet.of(Flag.MOVE)
    }

    override fun canUse(): Boolean {
        target = targetOf(mob)
        return target != null && !mob.blockPosition().closerThan(target, ARRIVAL_RANGE)
    }

    override fun canContinueToUse(): Boolean = canUse()

    override fun start() {
        repathCooldown = 0
    }

    override fun tick() {
        val destination = target ?: return
        if (repathCooldown-- > 0) return
        repathCooldown = REPATH_INTERVAL
        mob.navigation.moveTo(
            destination.x + 0.5,
            destination.y.toDouble(),
            destination.z + 0.5,
            1.0,
        )
    }

    companion object {
        const val ARRIVAL_RANGE = 2.0
        const val REPATH_INTERVAL = 20
    }
}

/**
 * Stops the mailman once it is standing at its destination.
 *
 * It does NOT deliver. MailRouteService owns arrival, because a dead-reckoned
 * route has no entity to notice it arrived and delivery must work identically
 * either way. Two arrival mechanisms would be two things to keep in agreement.
 */
class DeliverMailGoal(
    private val mob: MailmanEntity,
    private val targetOf: (MailmanEntity) -> BlockPos?,
) : Goal() {

    override fun canUse(): Boolean {
        val target = targetOf(mob) ?: return false
        return mob.blockPosition().closerThan(target, TravelToTargetGoal.ARRIVAL_RANGE)
    }

    override fun start() {
        mob.navigation.stop()
    }
}
