package dev.vvoleman.refurbishedeu.mail

import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TravelTest {

    @Test
    fun `advances toward the target at the configured speed`() {
        // 4 blocks/second for 20 ticks (1 second) = 4 blocks along +x
        val next = Travel.advance(Vec3(0.0, 64.0, 0.0), Vec3(100.0, 64.0, 0.0), 4.0, 20)
        assertEquals(4.0, next.x, 1e-6)
        assertEquals(0.0, next.z, 1e-6)
    }

    @Test
    fun `never overshoots the target`() {
        val next = Travel.advance(Vec3(0.0, 64.0, 0.0), Vec3(1.0, 64.0, 0.0), 4.0, 20)
        assertEquals(1.0, next.x, 1e-6)
    }

    @Test
    fun `carries y through untouched`() {
        // y is meaningless while unobserved; it must not be interpolated.
        val next = Travel.advance(Vec3(0.0, 64.0, 0.0), Vec3(100.0, 200.0, 0.0), 4.0, 20)
        assertEquals(64.0, next.y, 1e-6)
    }

    @Test
    fun `distance ignores height`() {
        assertEquals(3.0, Travel.horizontalDistance(Vec3(0.0, 0.0, 0.0), Vec3(3.0, 999.0, 0.0)), 1e-6)
    }

    @Test
    fun `standing on the target does not move or divide by zero`() {
        val here = Vec3(5.0, 64.0, 5.0)
        assertEquals(here, Travel.advance(here, Vec3(5.0, 70.0, 5.0), 4.0, 20))
    }

    @Test
    fun `one dead-reckoning tick beats the stall epsilon at both ends of the configured speed range`() {
        // Pins the property whose absence caused Critical C2: MailRouteService's
        // checkStall() only counts a route as progressing when its distance to the
        // target drops by more than an epsilon derived from perTickStep(). If
        // advance()'s step formula and perTickStep() ever disagree, a dead-reckoning
        // route would register zero progress every tick and eventually be deleted -
        // exactly the C2 failure, which only showed up outside the default speed.
        // So this checks both ends of the documented 0.1-100.0 blocksPerSecond range,
        // not just a comfortable middle value.
        //
        // The target is kept far enough away (1000 blocks) that neither speed clamps
        // the step to the remaining distance - this test is about the step formula,
        // not about arrival.
        val from = Vec3(0.0, 64.0, 0.0)
        val to = Vec3(1000.0, 64.0, 0.0)
        for (blocksPerSecond in listOf(0.1, 100.0)) {
            val before = Travel.horizontalDistance(from, to)
            val after = Travel.horizontalDistance(Travel.advance(from, to, blocksPerSecond, 1), to)
            val epsilon = Travel.perTickStep(blocksPerSecond) * 0.5
            assertTrue(
                after < before - epsilon,
                "one tick at $blocksPerSecond blocks/second did not beat its own stall " +
                    "epsilon: before=$before after=$after epsilon=$epsilon"
            )
        }
    }

    /**
     * The stuck-hop uses this rather than advance(): a hop is a fixed DISTANCE,
     * not a speed multiplied by a duration, and expressing it as the latter
     * would tie how far a stuck mailman escapes to blocksPerSecond, which is a
     * dead-reckoning setting and nothing to do with it.
     */
    @Test
    fun `hop steps the requested distance along the line`() {
        val moved = Travel.hop(Vec3(0.0, 64.0, 0.0), Vec3(100.0, 64.0, 0.0), 20.0)
        assertEquals(20.0, moved.x, 1e-9)
        assertEquals(0.0, moved.z, 1e-9)
    }

    @Test
    fun `hop stops at the destination rather than overshooting it`() {
        val moved = Travel.hop(Vec3(0.0, 64.0, 0.0), Vec3(5.0, 64.0, 0.0), 20.0)
        assertEquals(5.0, moved.x, 1e-9)
    }

    /** Horizontal only, exactly like advance - the caller picks the landing y. */
    @Test
    fun `hop keeps the y it started with`() {
        val moved = Travel.hop(Vec3(0.0, 64.0, 0.0), Vec3(100.0, 11.0, 0.0), 20.0)
        assertEquals(64.0, moved.y, 1e-9)
    }

    @Test
    fun `hop returns the start when already at the destination`() {
        val from = Vec3(3.0, 64.0, 3.0)
        assertEquals(from, Travel.hop(from, from, 20.0))
    }
}
