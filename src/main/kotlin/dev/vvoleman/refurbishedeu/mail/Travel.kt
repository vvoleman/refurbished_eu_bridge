package dev.vvoleman.refurbishedeu.mail

import net.minecraft.world.phys.Vec3
import kotlin.math.sqrt

/**
 * Dead reckoning for a route nobody is watching.
 *
 * Horizontal only, on purpose. Resolving a ground height out in unloaded chunks
 * would mean loading them, and a single long delivery would then drag a corridor
 * of chunks through the server - which is exactly the cost the route model
 * exists to avoid. Height is resolved when the mailman materialises.
 */
object Travel {

    private const val TICKS_PER_SECOND = 20.0

    fun horizontalDistance(a: Vec3, b: Vec3): Double {
        val dx = b.x - a.x
        val dz = b.z - a.z
        return sqrt(dx * dx + dz * dz)
    }

    fun advance(from: Vec3, to: Vec3, blocksPerSecond: Double, ticks: Int): Vec3 {
        val remaining = horizontalDistance(from, to)
        if (remaining <= 1e-9) return from
        val step = blocksPerSecond * (ticks / TICKS_PER_SECOND)
        if (step >= remaining) return Vec3(to.x, from.y, to.z)
        val scale = step / remaining
        return Vec3(from.x + (to.x - from.x) * scale, from.y, from.z + (to.z - from.z) * scale)
    }

    /**
     * How far one tick of dead reckoning covers at the given speed. Exposed
     * so callers judging "did this route make progress" can compare against
     * the actual step size instead of a fixed constant - a fixed threshold
     * bigger than the smallest legal speed's per-tick step (blocksPerSecond
     * can be configured as low as 0.1) would mean dead reckoning could never
     * be recognised as progressing at all.
     */
    fun perTickStep(blocksPerSecond: Double): Double = blocksPerSecond / TICKS_PER_SECOND
}
