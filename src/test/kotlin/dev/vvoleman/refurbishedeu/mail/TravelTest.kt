package dev.vvoleman.refurbishedeu.mail

import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
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
}
