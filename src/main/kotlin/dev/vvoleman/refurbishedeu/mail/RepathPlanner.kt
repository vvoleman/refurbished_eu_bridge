package dev.vvoleman.refurbishedeu.mail

import net.minecraft.core.BlockPos

/**
 * Decides when the mailman may ask its navigator for a fresh path.
 *
 * This exists because asking at the wrong moment is destructive, not merely
 * wasteful. GroundPathNavigation.canUpdatePath() is false whenever the mob is
 * off the ground, so PathNavigation.createPath returns null mid-jump - and
 * moveTo(null, speed) assigns that null over the path being walked. Recomputing
 * on a plain timer therefore threw away a perfectly good path every time the
 * interval happened to land while the mailman was airborne, leaving it standing
 * still until the next interval came round. That was the visible stutter.
 *
 * The rule that replaces the timer: only ever path when there is nothing to
 * lose - when the navigator has run out of path, or when the destination itself
 * has changed. Deliberately free of Minecraft state so the decision can be
 * tested without a Level, in the same spirit as [Travel].
 */
class RepathPlanner {

    private var cooldown = 0
    private var pathedTo: BlockPos? = null

    /** Ticks the NEXT failed grounded search will wait. Doubles per failure. */
    private var backoff = REPATH_FLOOR

    /**
     * @param navigationIdle whether the navigator has no path left to walk.
     * @return true when the caller should ask for a new path this tick.
     */
    fun shouldPath(target: BlockPos, navigationIdle: Boolean): Boolean {
        if (cooldown > 0) cooldown--
        // A changed destination invalidates whatever is being walked, so this
        // outranks both the cooldown and the busy check. The return trip flips
        // the destination without restarting the goal, and waiting out a
        // cooldown there would walk the mailman further the wrong way.
        if (target != pathedTo) return true
        if (!navigationIdle) return false
        return cooldown <= 0
    }

    /**
     * Report back what the navigator did with the request.
     *
     * @param searched whether A* actually ran. The two ways to come back
     *  without a path cost wildly different amounts and must not be treated
     *  alike: mid-jump, canUpdatePath() is false and createPath returns null
     *  having searched NOTHING, so retrying next tick is free. Grounded, the
     *  navigator really does spend its whole node budget and really does fail,
     *  and asking again next tick spends it again, every tick, for as long as
     *  the mailman stays stuck. Callers derive this from the same expression
     *  GroundPathNavigation.canUpdatePath() uses.
     */
    fun onPathed(target: BlockPos, succeeded: Boolean, searched: Boolean) {
        if (succeeded) {
            pathedTo = target
            cooldown = REPATH_FLOOR
            backoff = REPATH_FLOOR
            return
        }
        if (!searched) {
            // Nothing was lost, but nothing was gained either. Clearing
            // pathedTo asks again on the very next tick rather than serving
            // out a cooldown that bought no path.
            pathedTo = null
            cooldown = 0
            return
        }
        // A real search failed. pathedTo is deliberately KEPT here: shouldPath
        // treats a changed destination as outranking the cooldown, so clearing
        // it - as the airborne branch does - would let the very next tick
        // straight through and make the backoff unreachable.
        pathedTo = target
        cooldown = backoff
        backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF)
    }

    fun reset() {
        cooldown = 0
        pathedTo = null
        backoff = REPATH_FLOOR
    }

    companion object {
        /**
         * Fewest ticks between two successful path computations.
         *
         * Only a floor, not a schedule: the trigger is an idle navigator. It
         * caps the cost of the one case that would otherwise recompute every
         * tick - an unreachable mailbox, where every A* yields a partial path
         * that ends immediately. Sized like vanilla MeleeAttackGoal's 4-11
         * tick recalculation, which is this same rate against a moving target.
         */
        const val REPATH_FLOOR = 4

        /**
         * Longest a repeatedly-failing search is made to wait.
         *
         * Five seconds: long enough that a mailman wedged somewhere A* cannot
         * solve costs a fraction of one search per tick instead of a whole one,
         * and short enough that it resumes promptly once a player opens the way
         * out or the chunk it needed finishes loading.
         */
        const val MAX_BACKOFF = 100
    }
}
