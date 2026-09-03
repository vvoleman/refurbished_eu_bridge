package dev.vvoleman.refurbishedeu.mail

import net.minecraft.core.BlockPos
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A crossing worth staging.
 *
 * @property embark the last dry block on this side - walk here
 * @property launch the first water block - put the boat here
 * @property landing the first dry block on the far side - steer here, get out
 */
data class Crossing(val embark: BlockPos, val launch: BlockPos, val landing: BlockPos)

/**
 * Decides whether open water on the way to the destination is worth a boat.
 *
 * Block lookups arrive as a sampler rather than a Level so the geometry can be
 * tested against a fake world. The walk is a straight line toward the
 * destination, which matches what DirectBoatPilot will actually do - there is
 * no point finding a crossing the pilot cannot then steer.
 */
object WaterCrossing {

    fun find(
        from: BlockPos,
        towards: BlockPos,
        minWidth: Int,
        maxScan: Int,
        isWater: (BlockPos) -> Boolean,
    ): Crossing? {
        val dx = towards.x - from.x
        val dz = towards.z - from.z
        val steps = max(abs(dx), abs(dz))
        if (steps == 0) return null

        var previous = from
        var embark: BlockPos? = null
        var launch: BlockPos? = null
        var width = 0

        for (i in 1..min(steps, maxScan)) {
            val here = BlockPos(from.x + dx * i / steps, from.y, from.z + dz * i / steps)
            if (isWater(here)) {
                if (embark == null) {
                    embark = previous
                    launch = here
                }
                width++
            } else if (embark != null) {
                // Far bank reached. Narrow water is waded: a boat launch over a
                // stream looks far worse than walking through it.
                return if (width >= minWidth) Crossing(embark, launch!!, here) else null
            }
            previous = here
        }

        // Ran out of scan still on water - open ocean, with no far bank to aim
        // at. Straight-line steering has nothing to target, so decline.
        return null
    }
}
