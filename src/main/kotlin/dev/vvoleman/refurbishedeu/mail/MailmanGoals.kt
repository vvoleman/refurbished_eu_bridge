package dev.vvoleman.refurbishedeu.mail

import dev.vvoleman.refurbishedeu.RefurbishedEuBridge
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.FluidTags
import net.minecraft.util.Mth
import net.minecraft.world.entity.ai.goal.Goal
import net.minecraft.world.phys.Vec3
import java.util.EnumSet

/**
 * Walks toward whatever position the supplier names. The supplier - not the
 * goal - knows about routes, which keeps this testable in isolation and lets
 * the same goal serve both delivery and the return trip.
 */
class TravelToTargetGoal(
    private val mob: MailmanEntity,
    private val targetOf: (MailmanEntity) -> BlockPos?,
) : Goal() {

    private var target: BlockPos? = null

    /**
     * When it is safe to ask for a path. Asking on a timer instead used to
     * destroy the path being walked whenever the timer landed while the mailman
     * was airborne - see [RepathPlanner] for why.
     */
    private val planner = RepathPlanner()

    init {
        flags = EnumSet.of(Flag.MOVE)
    }

    override fun canUse(): Boolean {
        target = targetOf(mob)
        return target != null && !mob.blockPosition().closerThan(target, ARRIVAL_RANGE)
    }

    override fun canContinueToUse(): Boolean = canUse()

    override fun start() {
        planner.reset()
    }

    override fun tick() {
        val destination = target ?: return
        if (!planner.shouldPath(destination, mob.navigation.isDone)) return
        // moveTo reports false when it could not produce a path at all, which
        // for a grounded navigator mostly means "was in the air this tick".
        // The planner turns that into an immediate retry.
        val pathed = mob.navigation.moveTo(
            destination.x + 0.5,
            destination.y.toDouble(),
            destination.z + 0.5,
            1.0,
        )
        planner.onPathed(destination, pathed)
    }

    companion object {
        const val ARRIVAL_RANGE = 2.0
    }
}

/**
 * Stops the mailman once it is standing at its destination.
 *
 * It does NOT deliver. MailRouteService owns arrival, because a dead-reckoned
 * route has no entity to notice it arrived and delivery must work identically
 * either way. Two arrival mechanisms would be two things to keep in agreement.
 */
class DeliverMailGoal(
    private val mob: MailmanEntity,
    private val targetOf: (MailmanEntity) -> BlockPos?,
) : Goal() {

    override fun canUse(): Boolean {
        val target = targetOf(mob) ?: return false
        return mob.blockPosition().closerThan(target, TravelToTargetGoal.ARRIVAL_RANGE)
    }

    override fun start() {
        mob.navigation.stop()
    }
}

/**
 * Crosses open water by boat.
 *
 * Priority 2, above TravelToTargetGoal at 3; both hold Flag.MOVE so exactly one
 * runs. Every failure path is the same one: let go of the boat and fall back to
 * walking, which for water means swimming exactly as it did before boats
 * existed. The boat is never allowed to become the authority on the route - if
 * it goes wrong, the delivery still happens, just less elegantly.
 */
class UseBoatGoal(
    private val mob: MailmanEntity,
    private val targetOf: (MailmanEntity) -> BlockPos?,
) : Goal() {

    private var crossing: Crossing? = null
    private var boat: MailBoatEntity? = null
    private var elapsed = 0
    private var scanCooldown = 0
    private val pilot = DirectBoatPilot()
    private val planner = RepathPlanner()

    init {
        flags = EnumSet.of(Flag.MOVE)
    }

    override fun canUse(): Boolean {
        if (!MailmanConfig.useBoats()) return false
        if (mob.isPassenger) return false
        // GoalSelector calls canUse() every tick on every idle goal, and a scan
        // is up to MAX_SCAN block lookups. Throttling it keeps a shoreful of
        // mailmen from costing thousands of lookups a second to learn nothing
        // has changed.
        if (scanCooldown-- > 0) return false
        scanCooldown = SCAN_INTERVAL
        val target = targetOf(mob) ?: return false
        crossing = WaterCrossing.find(
            from = mob.blockPosition(),
            towards = target,
            minWidth = MailmanConfig.minWaterCrossingWidth(),
            maxScan = MAX_SCAN,
            isWater = ::isSurfaceWater,
        )
        return crossing != null
    }

    override fun canContinueToUse(): Boolean {
        if (crossing == null) return false
        // Abandoning on a timeout is what keeps a wedged boat from consuming
        // the route's whole stall budget.
        if (elapsed > MailmanConfig.boatCrossingTimeoutTicks()) return false
        // DeliverMailGoal declares no flags, so it does not reserve MOVE and
        // cannot arbitrate against this goal. Standing down once the target is
        // in delivery range is what keeps the two from overlapping - otherwise
        // a mailbox on the far bank could be delivered to from a boat, leaving
        // the boat behind.
        val target = targetOf(mob)
        if (target != null && mob.blockPosition().closerThan(target, TravelToTargetGoal.ARRIVAL_RANGE)) {
            return false
        }
        // Something dismounted us mid-crossing.
        return boat == null || (mob.vehicle === boat && boat!!.isAlive)
    }

    override fun start() {
        elapsed = 0
        planner.reset()
    }

    override fun stop() {
        release()
        crossing = null
        elapsed = 0
    }

    override fun tick() {
        elapsed++
        val plan = crossing ?: return
        val riding = boat
        if (riding == null) approachShore(plan) else cross(riding, plan)
    }

    /** Walk to the embark point, then put a boat in the water and get in. */
    private fun approachShore(plan: Crossing) {
        if (mob.blockPosition().closerThan(plan.embark, BOARD_RANGE)) {
            board(plan)
            return
        }
        if (!planner.shouldPath(plan.embark, mob.navigation.isDone)) return
        val pathed = mob.navigation.moveTo(
            plan.embark.x + 0.5,
            plan.embark.y.toDouble(),
            plan.embark.z + 0.5,
            1.0,
        )
        planner.onPathed(plan.embark, pathed)
    }

    private fun board(plan: Crossing) {
        val level = mob.level as? ServerLevel ?: return
        val spawned = RefurbishedEuBridge.MAIL_BOAT.get().create(level) ?: return
        spawned.moveTo(plan.launch.x + 0.5, plan.launch.y.toDouble(), plan.launch.z + 0.5, mob.yRot, 0.0f)
        level.addFreshEntity(spawned)
        if (!mob.startRiding(spawned)) {
            // Nothing will own this boat if the mailman could not get in.
            spawned.discard()
            return
        }
        mob.navigation.stop()
        boat = spawned
    }

    private fun cross(riding: MailBoatEntity, plan: Crossing) {
        val landing = Vec3(plan.landing.x + 0.5, plan.landing.y.toDouble(), plan.landing.z + 0.5)
        val steering = pilot.steer(
            riding.position(),
            riding.yRot,
            landing,
            MailmanConfig.blocksPerSecond() / 20.0,
        )
        if (steering == null) {
            // Arrived. Step off and let TravelToTargetGoal take it from here.
            release()
            crossing = null
            return
        }
        applyHelm(riding, steering)
    }

    /**
     * Vanilla integrates this for us: with a non-Player pilot, Boat.tick() runs
     * floatBoat() and move(SELF, deltaMovement) on the server, and the result
     * syncs to clients on its own.
     */
    private fun applyHelm(riding: MailBoatEntity, steering: Steering) {
        riding.yRot = steering.yaw
        riding.yRotO = steering.yaw
        val radians = steering.yaw * (Math.PI.toFloat() / 180.0f)
        val current = riding.deltaMovement
        riding.deltaMovement = Vec3(
            -Mth.sin(radians).toDouble() * steering.forward,
            current.y,
            Mth.cos(radians).toDouble() * steering.forward,
        )
    }

    /** The single cleanup path: out of the boat, and the boat gone with it. */
    private fun release() {
        val riding = boat ?: return
        mob.stopRiding()
        riding.discard()
        boat = null
    }

    /** Open water: a water column with air above it, so a boat would float there. */
    private fun isSurfaceWater(pos: BlockPos): Boolean {
        val level = mob.level
        return level.getFluidState(pos).`is`(FluidTags.WATER) && level.getBlockState(pos.above()).isAir
    }

    companion object {
        /** How far ahead to look for a crossing. */
        const val MAX_SCAN = 96

        /** Close enough to the embark block to put the boat in. */
        const val BOARD_RANGE = 2.0

        /** Ticks between crossing scans while no crossing is under way. */
        const val SCAN_INTERVAL = 40
    }
}
