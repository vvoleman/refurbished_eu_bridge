package dev.vvoleman.refurbishedeu.mail

import net.minecraft.core.BlockPos
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A crossing worth staging.
 *
 * [embark] and [landing] sit on the caller's own plane - the mailman's feet
 * block - because they are walking targets. [launch] is the real water block
 * the scan found, which is normally BELOW that plane: the shore is flush with
 * the waterline, so the feet block of a mailman standing on the beach is the
 * air block above the water surface, not the water itself. Whoever puts the
 * boat in needs the actual water column, so [launch] carries its true y.
 *
 * @property embark the last dry block on this side - walk here
 * @property launch the water block to float a boat on - spawn one block above it
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

    /**
     * How far below the caller's plane to look for the water surface.
     *
     * Zero would only ever find water the mailman is already standing in.
     * A shore flush with the waterline puts the surface one block down; a shore
     * one block above the waterline puts it two. Deeper than that is not a
     * shore any more, so the scan stops looking rather than finding a pond at
     * the bottom of a ravine and walking off the edge toward it.
     */
    const val PROBE_DEPTH = 2

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
            if (embark == null) {
                // Still on dry land, probing down for the near shore.
                val surface = surfaceUnder(here, isWater)
                if (surface != null) {
                    embark = previous
                    launch = surface
                    width = 1
                }
            } else if (isWater(BlockPos(here.x, launch!!.y, here.z))) {
                // Follow the surface the near shore established rather than
                // re-probing: still water is one plane, and re-probing would
                // let the scan wander down a slope it cannot then steer.
                width++
            } else {
                // Far bank reached. Narrow water is waded: a boat launch over a
                // stream looks far worse than walking through it.
                return if (width >= minWidth) Crossing(embark, launch, here) else null
            }
            previous = here
        }

        // Ran out of scan still on water - open ocean, with no far bank to aim
        // at. Straight-line steering has nothing to target, so decline.
        return null
    }

    /**
     * The topmost water block in [pos]'s column, searching from [pos] down.
     *
     * @return that block, or null when the column holds no water within reach.
     */
    private fun surfaceUnder(pos: BlockPos, isWater: (BlockPos) -> Boolean): BlockPos? {
        for (drop in 0..PROBE_DEPTH) {
            val candidate = pos.below(drop)
            if (isWater(candidate)) return candidate
        }
        return null
    }
}
