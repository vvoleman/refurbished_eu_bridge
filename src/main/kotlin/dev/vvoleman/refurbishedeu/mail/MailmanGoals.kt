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

    /**
     * When it is safe to ask for a path. Asking on a timer instead used to
     * destroy the path being walked whenever the timer landed while the mailman
     * was airborne - see [RepathPlanner] for why.
     */
    private val planner = RepathPlanner()

    init {
        flags = EnumSet.of(Flag.MOVE)
    }

    override fun canUse(): Boolean {
        target = targetOf(mob)
        return target != null && !mob.blockPosition().closerThan(target, ARRIVAL_RANGE)
    }

    override fun canContinueToUse(): Boolean = canUse()

    override fun start() {
        planner.reset()
    }

    override fun tick() {
        val destination = target ?: return
        if (!planner.shouldPath(destination, mob.navigation.isDone)) return
        // moveTo reports false when it could not produce a path at all, which
        // for a grounded navigator mostly means "was in the air this tick".
        // The planner turns that into an immediate retry.
        val pathed = mob.navigation.moveTo(
            destination.x + 0.5,
            destination.y.toDouble(),
            destination.z + 0.5,
            1.0,
        )
        planner.onPathed(destination, pathed)
    }

    companion object {
        const val ARRIVAL_RANGE = 2.0
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
