package dev.vvoleman.refurbishedeu.mail

import net.minecraft.core.BlockPos

/**
 * Picks somewhere a mailman can actually be put down.
 *
 * Used by the stuck-hop, which advances a route along the straight line to its
 * mailbox. That line is arithmetic and knows nothing about terrain, so the
 * block it names is as likely to be inside a hill or in mid-air as on the
 * ground. This searches its column for a spot the mailman can stand in.
 *
 * Takes a sampler rather than a Level for the same reason [WaterCrossing]
 * does: the choice is geometry, and geometry is worth testing without a world.
 */
object LandingSpot {

    /**
     * @param preferred where the line says to go - the straight-line y, NOT
     *  the surface. Snapping to the world surface instead is what used to put
     *  a basement mailbox's mailman on the roof.
     * @param radius how far up or down to look before giving up.
     * @param standable whether a mailman can stand with its feet in a block.
     * @return the closest standable block in the column, or null when there is
     *  none within [radius] - a real answer, meaning "do not move it".
     */
    fun find(preferred: BlockPos, radius: Int, standable: (BlockPos) -> Boolean): BlockPos? {
        if (standable(preferred)) return preferred
        for (distance in 1..radius) {
            // Below before above at equal distance: the line between two
            // mailboxes cuts into any slope it crosses, so the ground is far
            // more often under it than over it - and a mailman put down a
            // block high simply falls, while one put down a block low is
            // inside the hill.
            val below = preferred.below(distance)
            if (standable(below)) return below
            val above = preferred.above(distance)
            if (standable(above)) return above
        }
        return null
    }
}
