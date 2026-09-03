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
        val crossing = WaterCrossing.find(from, northEast, minWidth = 6, maxScan = 64) { it.x in 10 until 20 }
        // Stepping diagonally, x and z advance together.
        assertEquals(crossing!!.launch.x, crossing.launch.z)
    }

    @Test
    fun `a destination underfoot is not a crossing`() {
        assertNull(WaterCrossing.find(from, from, minWidth = 6, maxScan = 64) { true })
    }
}
