package dev.vvoleman.refurbishedeu.mail

import net.minecraft.core.BlockPos
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RepathPlannerTest {

    private val target = BlockPos(10, 64, 10)
    private val elsewhere = BlockPos(-40, 70, 12)

    /** Path once, successfully, so the planner is in its steady walking state. */
    private fun walking(): RepathPlanner = RepathPlanner().apply {
        shouldPath(target, navigationIdle = true)
        onPathed(target, succeeded = true)
    }

    @Test
    fun `paths on the first tick`() {
        assertTrue(RepathPlanner().shouldPath(target, navigationIdle = true))
    }

    /**
     * The regression this class exists for. GroundPathNavigation.createPath
     * returns null whenever the mob is airborne, and moveTo(null) DISCARDS the
     * live path - so a repath issued while a good path is still being walked
     * can stop the mailman dead for the whole interval. Never ask while busy.
     */
    @Test
    fun `never repaths while a live path is still being walked`() {
        val planner = walking()
        repeat(200) {
            assertFalse(planner.shouldPath(target, navigationIdle = false))
        }
    }

    @Test
    fun `repaths once the navigator runs out of path`() {
        val planner = walking()
        repeat(RepathPlanner.REPATH_FLOOR) { planner.shouldPath(target, navigationIdle = false) }
        assertTrue(planner.shouldPath(target, navigationIdle = true))
    }

    /** The return trip flips the destination mid-goal; that must not wait. */
    @Test
    fun `repaths immediately when the destination changes, even mid-path`() {
        assertTrue(walking().shouldPath(elsewhere, navigationIdle = false))
    }

    /**
     * A failed moveTo means the mob was airborne: nothing was lost, but nothing
     * was gained either, so it must retry at once rather than stand still for a
     * full interval waiting on a cooldown it never earned.
     */
    @Test
    fun `retries on the very next tick after a failed attempt`() {
        val planner = RepathPlanner()
        planner.shouldPath(target, navigationIdle = true)
        planner.onPathed(target, succeeded = false)
        assertTrue(planner.shouldPath(target, navigationIdle = true))
    }

    /** An unreachable destination yields a path that ends at once; cap the A* cost. */
    @Test
    fun `rate limits repeated idle repaths to the floor`() {
        val planner = walking()
        repeat(RepathPlanner.REPATH_FLOOR - 1) {
            assertFalse(planner.shouldPath(target, navigationIdle = true))
        }
        assertTrue(planner.shouldPath(target, navigationIdle = true))
    }

    @Test
    fun `reset returns the planner to its first-tick state`() {
        val planner = walking()
        planner.reset()
        assertTrue(planner.shouldPath(target, navigationIdle = false))
    }
}
