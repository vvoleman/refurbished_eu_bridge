package dev.vvoleman.refurbishedeu.mail

import net.minecraft.core.BlockPos
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class WaterCrossingTest {

    private val from = BlockPos(0, 62, 0)
    private val east = BlockPos(100, 62, 0)

    /** Water occupying x in [start, endExclusive) along the +X axis. */
    private fun channel(start: Int, endExclusive: Int): (BlockPos) -> Boolean =
        { it.x in start until endExclusive }

    @Test
    fun `no water ahead is no crossing`() {
        assertNull(WaterCrossing.find(from, east, minWidth = 6, maxScan = 64) { false })
    }

    @Test
    fun `finds the shore, the launch point and the far bank`() {
        val crossing = WaterCrossing.find(from, east, minWidth = 6, maxScan = 64, isWater = channel(10, 20))!!
        assertEquals(9, crossing.embark.x, "embark is the last dry block")
        assertEquals(10, crossing.launch.x, "launch is the first water block")
        assertEquals(20, crossing.landing.x, "landing is the first dry block beyond")
    }

    @Test
    fun `refuses a crossing narrower than the minimum`() {
        assertNull(WaterCrossing.find(from, east, minWidth = 6, maxScan = 64, isWater = channel(10, 13)))
    }

    @Test
    fun `accepts a crossing exactly at the minimum`() {
        val crossing = WaterCrossing.find(from, east, minWidth = 6, maxScan = 64, isWater = channel(10, 16))
        assertEquals(16, crossing!!.landing.x)
    }

    /** Open ocean: water to the horizon has no far bank to aim at. */
    @Test
    fun `refuses when no far shore is found within the scan`() {
        assertNull(WaterCrossing.find(from, east, minWidth = 6, maxScan = 20, isWater = channel(5, 1000)))
    }

    @Test
    fun `handles water starting immediately underfoot`() {
        val crossing = WaterCrossing.find(from, east, minWidth = 6, maxScan = 64, isWater = channel(1, 12))!!
        assertEquals(0, crossing.embark.x)
        assertEquals(1, crossing.launch.x)
        assertEquals(12, crossing.landing.x)
    }

    @Test
    fun `scans along the diagonal toward the destination`() {
        val northEast = BlockPos(60, 62, 60)
        val crossing = WaterCrossing.find(from, northEast, minWidth = 6, maxScan = 64) { it.x in 10 until 20 }!!
        // Stepping diagonally, x and z advance together, so every point of the
        // crossing sits on the x == z line at the same index as on the +X axis.
        assertEquals(BlockPos(9, 62, 9), crossing.embark)
        assertEquals(BlockPos(10, 62, 10), crossing.launch)
        assertEquals(BlockPos(20, 62, 20), crossing.landing)
    }

    @Test
    fun `a destination underfoot is not a crossing`() {
        assertNull(WaterCrossing.find(from, from, minWidth = 6, maxScan = 64) { true })
    }

    /** Water occupying x in [start, endExclusive) on exactly one y plane. */
    private fun channelAt(y: Int, start: Int, endExclusive: Int): (BlockPos) -> Boolean =
        { it.y == y && it.x in start until endExclusive }

    /**
     * The normal case, and the one that used to find nothing at all: a beach
     * flush with the waterline. The mailman stands ON the sand, so its feet
     * block is the air one above every water block on the route.
     */
    @Test
    fun `finds water one block below the walking plane`() {
        val crossing = WaterCrossing.find(from, east, minWidth = 6, maxScan = 64, isWater = channelAt(61, 10, 20))!!
        assertEquals(BlockPos(9, 62, 0), crossing.embark, "embark stays on the walking plane")
        assertEquals(BlockPos(10, 61, 0), crossing.launch, "launch is the real water block")
        assertEquals(BlockPos(20, 62, 0), crossing.landing, "landing stays on the walking plane")
    }

    /** A shore standing a block above the waterline is off by two, not one. */
    @Test
    fun `finds water two blocks below the walking plane`() {
        val crossing = WaterCrossing.find(from, east, minWidth = 6, maxScan = 64, isWater = channelAt(60, 10, 20))!!
        assertEquals(60, crossing.launch.y)
        assertEquals(20, crossing.landing.x)
    }

    /** Deeper than a shore. A pond at the bottom of a ravine is not a crossing. */
    @Test
    fun `refuses water beyond probing depth`() {
        assertNull(WaterCrossing.find(from, east, minWidth = 6, maxScan = 64, isWater = channelAt(59, 10, 20)))
    }

    @Test
    fun `launches on the topmost water block of the column`() {
        val crossing = WaterCrossing.find(from, east, minWidth = 6, maxScan = 64) {
            it.x in 10 until 20 && it.y <= 62
        }!!
        assertEquals(62, crossing.launch.y, "a boat floats on the surface, not in the depths")
    }

    /**
     * The width count has to stay on the plane the near shore established.
     * Measuring on the feet plane instead would have found one block of water
     * here - the one the far bank stands in - and declined the crossing.
     */
    @Test
    fun `measures width on the plane the surface was found on`() {
        val crossing = WaterCrossing.find(from, east, minWidth = 6, maxScan = 64) {
            (it.y == 61 && it.x in 10 until 20) || (it.y == 62 && it.x == 19)
        }!!
        assertEquals(BlockPos(10, 61, 0), crossing.launch)
        assertEquals(20, crossing.landing.x)
    }
}
