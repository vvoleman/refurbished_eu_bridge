package dev.vvoleman.refurbishedeu.mail

import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RepathPlannerTest {

    private val target = BlockPos(10, 64, 10)
    private val elsewhere = BlockPos(-40, 70, 12)

    /** Path once, successfully, so the planner is in its steady walking state. */
    private fun walking(): RepathPlanner = RepathPlanner().apply {
        shouldPath(target, navigationIdle = true)
        onPathed(target, succeeded = true, searched = true)
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
    fun `retries on the very next tick when no search actually ran`() {
        val planner = RepathPlanner()
        planner.shouldPath(target, navigationIdle = true)
        planner.onPathed(target, succeeded = false, searched = false)
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

    /**
     * Counts the calls that are refused before one is allowed, with the
     * navigator idle and the destination unchanged - i.e. how long the backoff
     * actually holds the caller off.
     */
    private fun refusalsBeforeRetry(planner: RepathPlanner): Int {
        var refused = 0
        while (!planner.shouldPath(target, navigationIdle = true)) refused++
        return refused
    }

    /** Fails one grounded search, having first consumed the free opening path. */
    private fun failedGroundedSearch(): RepathPlanner = RepathPlanner().apply {
        shouldPath(target, navigationIdle = true)
        onPathed(target, succeeded = false, searched = true)
    }

    /**
     * The reason the search budget can be raised at all. A grounded mailman
     * that A* could not path for - stuck in a ravine, say - used to be asked
     * again on the very NEXT tick, burning a whole exhausted search per tick
     * for as long as it stayed stuck. Multiplying the node budget would have
     * multiplied that too.
     */
    @Test
    fun `backs off after a grounded search fails`() {
        assertFalse(failedGroundedSearch().shouldPath(target, navigationIdle = true))
    }

    @Test
    fun `lengthens the backoff while grounded searches keep failing`() {
        val planner = failedGroundedSearch()
        val first = refusalsBeforeRetry(planner)
        planner.onPathed(target, succeeded = false, searched = true)
        assertTrue(refusalsBeforeRetry(planner) > first)
    }

    @Test
    fun `caps the backoff`() {
        val planner = failedGroundedSearch()
        repeat(20) { planner.onPathed(target, succeeded = false, searched = true) }
        assertTrue(refusalsBeforeRetry(planner) < RepathPlanner.MAX_BACKOFF)
    }

    /**
     * A backoff is about one unreachable destination, not about the mailman.
     * beginReturn flips the target mid-walk, and that deserves a fresh search
     * immediately rather than serving out a wait earned by somewhere else.
     */
    @Test
    fun `a changed destination overrides a backoff`() {
        assertTrue(failedGroundedSearch().shouldPath(elsewhere, navigationIdle = true))
    }

    @Test
    fun `a path that succeeds clears the backoff`() {
        val planner = failedGroundedSearch()
        repeat(5) { planner.onPathed(target, succeeded = false, searched = true) }
        planner.onPathed(target, succeeded = true, searched = true)
        assertEquals(RepathPlanner.REPATH_FLOOR - 1, refusalsBeforeRetry(planner))
    }

    /**
     * The stuck-hop moves the mailman without the destination changing, so
     * nothing else here would notice. Without this the mailman would arrive at
     * its new spot and stand there serving out a backoff earned somewhere it
     * no longer is - up to MAX_BACKOFF of doing nothing after a nudge whose
     * entire point was to get it moving again.
     */
    @Test
    fun `a jump in position clears the backoff`() {
        val planner = failedGroundedSearch()
        repeat(5) { planner.onPathed(target, succeeded = false, searched = true) }
        planner.notePosition(Vec3(0.0, 64.0, 0.0))
        planner.notePosition(Vec3(20.0, 64.0, 0.0))
        assertTrue(planner.shouldPath(target, navigationIdle = true))
    }

    @Test
    fun `ordinary walking does not clear the backoff`() {
        val planner = failedGroundedSearch()
        planner.notePosition(Vec3(0.0, 64.0, 0.0))
        planner.notePosition(Vec3(0.25, 64.0, 0.0))
        assertFalse(planner.shouldPath(target, navigationIdle = true))
    }

    @Test
    fun `the first position noted is not a jump`() {
        val planner = failedGroundedSearch()
        planner.notePosition(Vec3(1000.0, 64.0, 1000.0))
        assertFalse(planner.shouldPath(target, navigationIdle = true))
    }
}
