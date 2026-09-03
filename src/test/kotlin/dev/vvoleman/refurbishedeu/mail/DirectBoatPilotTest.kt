package dev.vvoleman.refurbishedeu.mail

import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class DirectBoatPilotTest {

    private val pilot = DirectBoatPilot()
    private val origin = Vec3(0.0, 62.0, 0.0)

    @Test
    fun `reports arrival by returning null`() {
        assertNull(pilot.steer(origin, 0.0f, Vec3(1.0, 62.0, 0.0), 0.2))
    }

    /** Height must not count: the boat is on the surface, the target on shore. */
    @Test
    fun `arrival ignores vertical distance`() {
        assertNull(pilot.steer(origin, 0.0f, Vec3(1.0, 90.0, 0.0), 0.2))
    }

    @Test
    fun `turns toward a target and keeps turning over successive ticks`() {
        val east = Vec3(50.0, 62.0, 0.0)
        val first = pilot.steer(origin, 0.0f, east, 0.2)!!
        val second = pilot.steer(origin, first.yaw, east, 0.2)!!
        // Minecraft yaw: 0 is +Z, and +X (east) is -90.
        assertTrue(first.yaw < 0.0f, "expected a turn toward -90, got ${first.yaw}")
        assertTrue(second.yaw < first.yaw, "expected continued turning")
    }

    @Test
    fun `clamps the turn rate per tick`() {
        val steering = pilot.steer(origin, 0.0f, Vec3(50.0, 62.0, 0.0), 0.2)!!
        assertTrue(
            abs(Mth.wrapDegrees(steering.yaw - 0.0f)) <= DirectBoatPilot.MAX_TURN_PER_TICK + 1e-4,
            "turned ${steering.yaw} in one tick",
        )
    }

    @Test
    fun `throttles back while off heading and opens up once aligned`() {
        val east = Vec3(50.0, 62.0, 0.0)
        val turning = pilot.steer(origin, 0.0f, east, 0.2)!!
        val aligned = pilot.steer(origin, -90.0f, east, 0.2)!!
        assertTrue(turning.forward < aligned.forward, "expected to slow while turning")
        assertEquals(0.2, aligned.forward, 1e-9)
    }

    /**
     * The wraparound. From 170 to a heading of -170 is 20 degrees the short way
     * across 180, not 340 the long way round.
     */
    @Test
    fun `takes the short way around the yaw wraparound`() {
        // A target just west of due north sits at a desired yaw near -170.
        val target = Vec3(-1.0, 62.0, -50.0)
        val steering = pilot.steer(origin, 170.0f, target, 0.2)!!
        assertNotNull(steering)
        assertTrue(
            steering.yaw > 170.0f || steering.yaw < -170.0f,
            "expected to turn positively through 180, got ${steering.yaw}",
        )
    }
}
