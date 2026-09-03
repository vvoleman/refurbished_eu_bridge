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
        // Held in a local as well as the field: the field is a var, so the
        // compiler cannot smart-cast the null check away on a platform-typed
        // Vec3i parameter.
        val destination = targetOf(mob)
        target = destination
        return destination != null && !mob.blockPosition().closerThan(destination, ARRIVAL_RANGE)
    }

    override fun canContinueToUse(): Boolean = canUse()

    override fun start() {
        planner.reset()
    }

    override fun tick() {
        val destination = target ?: return
        // Before shouldPath, so a mailman the stuck-hop just moved gets a fresh
        // search this tick instead of serving out a backoff earned where it no
        // longer is.
        planner.notePosition(mob.position())
        if (!planner.shouldPath(destination, mob.navigation.isDone)) return
        // GroundPathNavigation.canUpdatePath() - createPath returns null
        // without searching at all when this is false, so a failure here cost
        // nothing and is worth retrying at once. Read BEFORE moveTo, because
        // moveTo can leave the mob in a different state than it found it.
        val searched = mob.isOnGround || mob.isInWater || mob.isPassenger
        // moveTo reports false when it could not produce a path at all: either
        // the free airborne case above, or a real search that spent the whole
        // node budget and found nothing. The planner needs to tell them apart.
        val pathed = mob.navigation.moveTo(
            destination.x + 0.5,
            destination.y.toDouble(),
            destination.z + 0.5,
            1.0,
        )
        planner.onPathed(destination, pathed, searched)
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

    /**
     * The destination the crossing was planned toward. A crossing is only ever
     * worth making toward one place, so a changed target invalidates it - the
     * same rule [RepathPlanner] applies to a path being walked. Without this a
     * route flipped to RETURNING mid-crossing keeps steering to the far bank it
     * no longer wants, then walks the whole way back.
     */
    private var plannedFor: BlockPos? = null

    private var boat: MailBoatEntity? = null
    private var elapsed = 0
    private var scanCooldown = 0
    private val pilot = DirectBoatPilot()
    private val planner = RepathPlanner()

    init {
        flags = EnumSet.of(Flag.MOVE)
    }

    /**
     * Mob.serverAiStep() only ticks goals that ask for it on every other tick;
     * the rest get half the ticks they think they get. A goal steering a
     * vehicle needs all of them - otherwise DirectBoatPilot's per-tick turn
     * clamp is really half a clamp, and the crossing timeout counts in units
     * twice the size of the configured ones.
     */
    override fun requiresUpdateEveryTick(): Boolean = true

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
        plannedFor = target
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
        // The crossing was planned toward somewhere else, so it is answering a
        // question nobody is asking any more. Standing down re-scans from
        // wherever the mailman now is, toward wherever it now wants to go.
        if (target != plannedFor) return false
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
        plannedFor = null
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
        // See TravelToTargetGoal.tick: only a grounded failure actually spent a
        // search, and only that one is worth backing off from.
        val searched = mob.isOnGround || mob.isInWater || mob.isPassenger
        val pathed = mob.navigation.moveTo(
            plan.embark.x + 0.5,
            plan.embark.y.toDouble(),
            plan.embark.z + 0.5,
            1.0,
        )
        planner.onPathed(plan.embark, pathed, searched)
    }

    private fun board(plan: Crossing) {
        val level = mob.level as? ServerLevel ?: return
        val spawned = RefurbishedEuBridge.MAIL_BOAT.get().create(level) ?: return
        // ON TOP of the water column, not in it. moveTo places the feet, and a
        // boat spawned with its hitbox inside water reads as UNDER_WATER, which
        // in Boat.floatBoat() has a NEGATIVE terminal velocity - it sinks for
        // good and ejects its passenger after 60 out-of-control ticks.
        // Buoyancy lives only on floatBoat()'s IN_AIR -> water transition, so
        // the boat has to arrive from the air: one tick of falling and it snaps
        // to the surface, which is also how a player-placed boat behaves.
        spawned.moveTo(
            plan.launch.x + 0.5,
            plan.launch.y + 1.0,
            plan.launch.z + 0.5,
            mob.yRot,
            0.0f,
        )
        // startRiding does not require the vehicle to be in the level, so an
        // unchecked rejection here (a cancelled EntityJoinLevelEvent, say)
        // leaves the mailman riding an entity that will never tick: it cannot
        // move itself while a passenger, and canContinueToUse stays true, so it
        // would stand frozen for the whole crossing timeout.
        if (!level.addFreshEntity(spawned)) {
            spawned.discard()
            return
        }
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
        // A player may already have broken it. Entity.setRemoved is re-entrant
        // but re-runs the level callback, which logs "wasn't found in section"
        // for a boat that is already gone.
        if (!riding.isRemoved) riding.discard()
        boat = null
    }

    /**
     * Open water: a water column with air above it, so a boat would float there.
     *
     * A missing chunk counts as not water rather than loading it. getFluidState
     * would otherwise route through getChunk(load = true) and synchronously
     * generate terrain up to MAX_SCAN blocks out for every idle mailman - the
     * exact cost MailRouteService avoids with getChunkNow, on the principle
     * that a delivery must not drag chunks along behind it. Water the server
     * has not loaded is water nobody can see the mailman cross anyway.
     */
    private fun isSurfaceWater(pos: BlockPos): Boolean {
        val level = mob.level
        if (level.chunkSource.getChunkNow(pos.x shr 4, pos.z shr 4) == null) return false
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
