package dev.vvoleman.refurbishedeu.mail

import net.minecraft.core.BlockPos
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class LandingSpotTest {

    private val preferred = BlockPos(10, 64, 10)

    /** A world where only the listed positions can be stood in. */
    private fun world(vararg standable: BlockPos): (BlockPos) -> Boolean =
        { it in standable.toSet() }

    @Test
    fun `takes the preferred spot when it is standable`() {
        assertEquals(preferred, LandingSpot.find(preferred, RADIUS, world(preferred)))
    }

    /**
     * Down before up at the same distance. A hop lands on the straight line
     * between two mailboxes, which on any slope cuts into the hillside; the
     * ground is far more often below that line than above it, and a mailman
     * would fall to it anyway.
     */
    @Test
    fun `prefers below over above at equal distance`() {
        val below = preferred.below(3)
        val above = preferred.above(3)
        assertEquals(below, LandingSpot.find(preferred, RADIUS, world(below, above)))
    }

    @Test
    fun `climbs to a spot above when nothing below is standable`() {
        val above = preferred.above(5)
        assertEquals(above, LandingSpot.find(preferred, RADIUS, world(above)))
    }

    @Test
    fun `takes the nearest of several candidates`() {
        val near = preferred.below(2)
        val far = preferred.below(7)
        assertEquals(near, LandingSpot.find(preferred, RADIUS, world(near, far)))
    }

    /**
     * Refusing is a real answer, not a failure: the caller leaves the mailman
     * where it is and lets the existing stall handling run. Teleporting into
     * the first hole found ten blocks down would be worse than not moving.
     */
    @Test
    fun `gives up rather than reaching past the radius`() {
        assertNull(LandingSpot.find(preferred, RADIUS, world(preferred.below(RADIUS + 1))))
    }

    @Test
    fun `gives up in a solid world`() {
        assertNull(LandingSpot.find(preferred, RADIUS, world()))
    }

    private companion object {
        const val RADIUS = 8
    }
}
