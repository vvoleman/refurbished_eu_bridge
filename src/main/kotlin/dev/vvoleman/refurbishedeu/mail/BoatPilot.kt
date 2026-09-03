package dev.vvoleman.refurbishedeu.mail

import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3
import kotlin.math.abs

/** One tick of helm: where to point, and how hard to drive. */
data class Steering(val yaw: Float, val forward: Double)

/**
 * Turns "the boat is here, facing this way, and wants to be there" into one
 * tick of helm.
 *
 * This exists because vanilla has no server-side equivalent. Boat.controlBoat()
 * is confined to `if (level.isClientSide)` and is fed by player key input, so
 * no mob has ever steered one. Everything else about a mob-piloted boat already
 * works server-side: with a non-Player passenger, isControlledByLocalInstance()
 * is true on the server, so Boat.tick() runs floatBoat() and moves the boat by
 * its delta movement. Supplying yaw and thrust is the whole job.
 *
 * The interface is the seam for replacing straight-line steering with real
 * water pathfinding later; it deals in plain values rather than a Boat so the
 * arithmetic can be tested without a Level.
 */
interface BoatPilot {
    /** @return the helm for this tick, or null once the target is reached. */
    fun steer(position: Vec3, yaw: Float, target: Vec3, speed: Double): Steering?
}

/** Points at the target and drives, correcting every tick. */
class DirectBoatPilot(private val arrivalRange: Double = ARRIVAL_RANGE) : BoatPilot {

    override fun steer(position: Vec3, yaw: Float, target: Vec3, speed: Double): Steering? {
        // Horizontal only. The boat is on the surface and the target is a shore
        // block that may be well above it; counting height would mean never
        // arriving.
        val dx = target.x - position.x
        val dz = target.z - position.z
        if (dx * dx + dz * dz <= arrivalRange * arrivalRange) return null

        // Minecraft yaw has 0 at +Z and grows clockwise. Same conversion
        // Bat.customServerAiStep uses to face its drift target.
        val desired = (Mth.atan2(dz, dx) * (180.0 / Math.PI)).toFloat() - 90.0f
        val error = Mth.wrapDegrees(desired - yaw)
        val newYaw = Mth.wrapDegrees(yaw + error.coerceIn(-MAX_TURN_PER_TICK, MAX_TURN_PER_TICK))

        // Ease off until roughly on heading, so it pivots rather than carving a
        // wide arc it then has to unwind - which on a narrow crossing means
        // landing on the wrong shore.
        val aligned = abs(Mth.wrapDegrees(desired - newYaw)) < ALIGNED_DEGREES
        return Steering(newYaw, if (aligned) speed else speed * OFF_HEADING_THROTTLE)
    }

    companion object {
        const val ARRIVAL_RANGE = 2.0
        const val MAX_TURN_PER_TICK = 5.0f
        const val ALIGNED_DEGREES = 30.0f
        const val OFF_HEADING_THROTTLE = 0.25
    }
}
