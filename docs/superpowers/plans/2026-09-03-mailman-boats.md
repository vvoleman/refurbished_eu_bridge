# Mailman Boats Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a materialised mailman cross open water by boat, turning the Phase 1 "open water is undeliverable" limit into a boat leg.

**Architecture:** A registered `Boat` subclass owned by the route (never saved, one seat, self-discarding) carries the mailman. Vanilla already runs boat flotation and movement server-side for a non-Player pilot; the only missing piece is steering, which we supply each tick through a `BoatPilot` interface so straight-line steering can later be swapped for real pathfinding. Crossing geometry and steering math are pure functions with injected block sampling, so both are unit-tested without a `Level`.

**Tech Stack:** Kotlin 1.8.21, Forge 1.19.2 (43.4.0), ForgeGradle 5.1, Kotlin for Forge 3.12.0, JDK 17, JUnit 5.

**Spec:** `docs/superpowers/specs/2026-09-03-mailman-boat-design.md` — read it before Task 1. The plan argues from the spec; where they disagree, the spec wins.

## Global Constraints

- Build: `JAVA_HOME=/home/vvoleman/.jdks/jdk-17.0.20.1+1 ./gradlew build --offline`. There is no `java` on PATH; the prefix is mandatory.
- Test: `JAVA_HOME=/home/vvoleman/.jdks/jdk-17.0.20.1+1 ./gradlew test --offline`.
- `runClient` cannot start in this repo (IC2 Classic mixin refmap). **A green build is never a working feature.** Never report otherwise.
- All new code goes in package `dev.vvoleman.refurbishedeu.mail`.
- Mojang official mappings. Vanilla method names are the Mojmap ones (`shouldBeSaved`, `getMaxPassengers`, `setDeltaMovement`).
- Tests touching `ItemStack` or registries must bootstrap (`SharedConstants.setVersion` + `Bootstrap.bootStrap`). Tests using only `Vec3`, `BlockPos` and `Mth` must NOT — keep the new tests bootstrap-free.
- Every commit message ends with `Claude-Session: https://claude.ai/code/session_01QwoDxyZCiDLeZasALDLxAV`.

## File Structure

| File | Responsibility |
|---|---|
| `mail/BoatPilot.kt` (new) | `Steering`, `BoatPilot` interface, `DirectBoatPilot` — pure steering math |
| `mail/WaterCrossing.kt` (new) | `Crossing`, `WaterCrossing.find` — pure crossing geometry |
| `mail/MailBoatEntity.kt` (new) | the route-owned boat |
| `mail/MailmanGoals.kt` (modify) | add `UseBoatGoal` |
| `mail/MailmanEntity.kt` (modify) | register the goal, demote `TravelToTargetGoal` to 3 |
| `mail/MailRouteService.kt` (modify) | `dematerialise` discards the vehicle |
| `mail/MailmanConfig.kt` (modify) | three new settings |
| `RefurbishedEuBridge.kt` (modify) | register `MAIL_BOAT` entity type |
| `ClientSetup.kt` (modify) | register `BoatRenderer` for it |

---

### Task 1: Config

**Files:**
- Modify: `src/main/kotlin/dev/vvoleman/refurbishedeu/mail/MailmanConfig.kt`
- Test: `src/test/kotlin/dev/vvoleman/refurbishedeu/mail/MailmanConfigTest.kt` (create)

**Interfaces:**
- Consumes: nothing.
- Produces: `MailmanConfig.useBoats(): Boolean`, `MailmanConfig.minWaterCrossingWidth(): Int`, `MailmanConfig.boatCrossingTimeoutTicks(): Int`.

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/dev/vvoleman/refurbishedeu/mail/MailmanConfigTest.kt`:

```kotlin
package dev.vvoleman.refurbishedeu.mail

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The accessors fall back to their defaults when the spec is not loaded, which
 * is always the case in a unit test. That fallback is what the rest of the
 * suite leans on, so it is worth pinning.
 */
class MailmanConfigTest {

    @Test
    fun `boat settings fall back to their documented defaults`() {
        assertTrue(MailmanConfig.useBoats())
        assertEquals(6, MailmanConfig.minWaterCrossingWidth())
        assertEquals(600, MailmanConfig.boatCrossingTimeoutTicks())
    }

    @Test
    fun `the crossing timeout is shorter than the stall timeout`() {
        // A stuck boat must be abandoned while the route still has budget to
        // swim, rather than the route dying with a boat under it.
        assertTrue(MailmanConfig.boatCrossingTimeoutTicks() < MailmanConfig.stallTimeoutTicks())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/home/vvoleman/.jdks/jdk-17.0.20.1+1 ./gradlew test --offline --tests '*MailmanConfigTest*'`
Expected: FAIL — `Unresolved reference: useBoats`.

- [ ] **Step 3: Write minimal implementation**

In `MailmanConfig.kt`, add three fields to the `val` block beside `stallTimeoutTicks`:

```kotlin
    val useBoats: ForgeConfigSpec.BooleanValue
    val minWaterCrossingWidth: ForgeConfigSpec.IntValue
    val boatCrossingTimeoutTicks: ForgeConfigSpec.IntValue
```

Inside `init`, after the `stallTimeoutTicks` block and before `builder.pop()`:

```kotlin
        useBoats = builder
            .comment(
                "Let a mailman cross open water by boat.",
                "False restores the old behaviour: wide water is undeliverable and",
                "the mail is carried back to where it was posted."
            )
            .define("useBoats", true)

        minWaterCrossingWidth = builder
            .comment(
                "Water narrower than this many blocks is waded, not boated.",
                "Staging a boat launch over a stream looks worse than walking it."
            )
            .defineInRange("minWaterCrossingWidth", 6, 2, 64)

        boatCrossingTimeoutTicks = builder
            .comment(
                "Abandon a crossing that takes longer than this and swim instead.",
                "Keep it below stallTimeoutTicks so a stuck boat is dropped while",
                "the route still has budget left to find another way."
            )
            .defineInRange("boatCrossingTimeoutTicks", 600, 100, 24000)
```

Add three accessors at the foot of the object, beside the existing ones:

```kotlin
    fun useBoats(): Boolean = read(useBoats, true)
    fun minWaterCrossingWidth(): Int = read(minWaterCrossingWidth, 6)
    fun boatCrossingTimeoutTicks(): Int = read(boatCrossingTimeoutTicks, 600)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=/home/vvoleman/.jdks/jdk-17.0.20.1+1 ./gradlew test --offline --tests '*MailmanConfigTest*'`
Expected: PASS, 2 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/vvoleman/refurbishedeu/mail/MailmanConfig.kt \
        src/test/kotlin/dev/vvoleman/refurbishedeu/mail/MailmanConfigTest.kt
git commit -m "Add boat crossing config

Claude-Session: https://claude.ai/code/session_01QwoDxyZCiDLeZasALDLxAV"
```

---

### Task 2: DirectBoatPilot

The steering vanilla only does client-side, as pure math.

**Files:**
- Create: `src/main/kotlin/dev/vvoleman/refurbishedeu/mail/BoatPilot.kt`
- Test: `src/test/kotlin/dev/vvoleman/refurbishedeu/mail/DirectBoatPilotTest.kt` (create)

**Interfaces:**
- Consumes: nothing.
- Produces: `data class Steering(val yaw: Float, val forward: Double)`; `interface BoatPilot { fun steer(position: Vec3, yaw: Float, target: Vec3, speed: Double): Steering? }`; `class DirectBoatPilot(arrivalRange: Double = ARRIVAL_RANGE) : BoatPilot` with `DirectBoatPilot.ARRIVAL_RANGE`, `MAX_TURN_PER_TICK`, `ALIGNED_DEGREES`.

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/dev/vvoleman/refurbishedeu/mail/DirectBoatPilotTest.kt`:

```kotlin
package dev.vvoleman.refurbishedeu.mail

import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class DirectBoatPilotTest {

    private val pilot = DirectBoatPilot()
    private val origin = Vec3(0.0, 62.0, 0.0)

    @Test
    fun `reports arrival by returning null`() {
        assertNull(pilot.steer(origin, 0.0f, Vec3(1.0, 62.0, 0.0), 0.2))
    }

    /** Height must not count: the boat is on the surface, the target on shore. */
    @Test
    fun `arrival ignores vertical distance`() {
        assertNull(pilot.steer(origin, 0.0f, Vec3(1.0, 90.0, 0.0), 0.2))
    }

    @Test
    fun `turns toward a target and keeps turning over successive ticks`() {
        val east = Vec3(50.0, 62.0, 0.0)
        val first = pilot.steer(origin, 0.0f, east, 0.2)!!
        val second = pilot.steer(origin, first.yaw, east, 0.2)!!
        // Minecraft yaw: 0 is +Z, and +X (east) is -90.
        assertTrue(first.yaw < 0.0f, "expected a turn toward -90, got ${first.yaw}")
        assertTrue(second.yaw < first.yaw, "expected continued turning")
    }

    @Test
    fun `clamps the turn rate per tick`() {
        val steering = pilot.steer(origin, 0.0f, Vec3(50.0, 62.0, 0.0), 0.2)!!
        assertTrue(
            abs(Mth.wrapDegrees(steering.yaw - 0.0f)) <= DirectBoatPilot.MAX_TURN_PER_TICK + 1e-4,
            "turned ${steering.yaw} in one tick",
        )
    }

    @Test
    fun `throttles back while off heading and opens up once aligned`() {
        val east = Vec3(50.0, 62.0, 0.0)
        val turning = pilot.steer(origin, 0.0f, east, 0.2)!!
        val aligned = pilot.steer(origin, -90.0f, east, 0.2)!!
        assertTrue(turning.forward < aligned.forward, "expected to slow while turning")
        assertEquals(0.2, aligned.forward, 1e-9)
    }

    /**
     * The wraparound. From 170 to a heading of -170 is 20 degrees the short way
     * across 180, not 340 the long way round.
     */
    @Test
    fun `takes the short way around the yaw wraparound`() {
        // A target just west of due north sits at a desired yaw near -170.
        val target = Vec3(-1.0, 62.0, -50.0)
        val steering = pilot.steer(origin, 170.0f, target, 0.2)!!
        assertNotNull(steering)
        assertTrue(
            steering.yaw > 170.0f || steering.yaw < -170.0f,
            "expected to turn positively through 180, got ${steering.yaw}",
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/home/vvoleman/.jdks/jdk-17.0.20.1+1 ./gradlew test --offline --tests '*DirectBoatPilotTest*'`
Expected: FAIL — `Unresolved reference: DirectBoatPilot`.

- [ ] **Step 3: Write minimal implementation**

Create `src/main/kotlin/dev/vvoleman/refurbishedeu/mail/BoatPilot.kt`:

```kotlin
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=/home/vvoleman/.jdks/jdk-17.0.20.1+1 ./gradlew test --offline --tests '*DirectBoatPilotTest*'`
Expected: PASS, 6 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/vvoleman/refurbishedeu/mail/BoatPilot.kt \
        src/test/kotlin/dev/vvoleman/refurbishedeu/mail/DirectBoatPilotTest.kt
git commit -m "Add boat steering, the part vanilla only does client-side

Claude-Session: https://claude.ai/code/session_01QwoDxyZCiDLeZasALDLxAV"
```

---

### Task 3: WaterCrossing

**Files:**
- Create: `src/main/kotlin/dev/vvoleman/refurbishedeu/mail/WaterCrossing.kt`
- Test: `src/test/kotlin/dev/vvoleman/refurbishedeu/mail/WaterCrossingTest.kt` (create)

**Interfaces:**
- Consumes: nothing.
- Produces: `data class Crossing(val embark: BlockPos, val launch: BlockPos, val landing: BlockPos)`; `WaterCrossing.find(from: BlockPos, towards: BlockPos, minWidth: Int, maxScan: Int, isWater: (BlockPos) -> Boolean): Crossing?`.

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/dev/vvoleman/refurbishedeu/mail/WaterCrossingTest.kt`:

```kotlin
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/home/vvoleman/.jdks/jdk-17.0.20.1+1 ./gradlew test --offline --tests '*WaterCrossingTest*'`
Expected: FAIL — `Unresolved reference: WaterCrossing`.

- [ ] **Step 3: Write minimal implementation**

Create `src/main/kotlin/dev/vvoleman/refurbishedeu/mail/WaterCrossing.kt`:

```kotlin
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=/home/vvoleman/.jdks/jdk-17.0.20.1+1 ./gradlew test --offline --tests '*WaterCrossingTest*'`
Expected: PASS, 8 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/vvoleman/refurbishedeu/mail/WaterCrossing.kt \
        src/test/kotlin/dev/vvoleman/refurbishedeu/mail/WaterCrossingTest.kt
git commit -m "Add crossing geometry: where to board, launch and land

Claude-Session: https://claude.ai/code/session_01QwoDxyZCiDLeZasALDLxAV"
```

---

### Task 4: MailBoatEntity and its registration

No unit test: this is entity registration, which needs a running game. Verified by compiling.

**Files:**
- Create: `src/main/kotlin/dev/vvoleman/refurbishedeu/mail/MailBoatEntity.kt`
- Modify: `src/main/kotlin/dev/vvoleman/refurbishedeu/RefurbishedEuBridge.kt` (beside `MAILMAN`, around line 89)
- Modify: `src/main/kotlin/dev/vvoleman/refurbishedeu/ClientSetup.kt` (in `onRegisterRenderers`)

**Interfaces:**
- Consumes: nothing.
- Produces: `MailBoatEntity(type: EntityType<out Boat>, level: Level)`, `MailBoatEntity.BOARDING_GRACE_TICKS`; `RefurbishedEuBridge.MAIL_BOAT: RegistryObject<EntityType<MailBoatEntity>>`.

- [ ] **Step 1: Create the entity**

Create `src/main/kotlin/dev/vvoleman/refurbishedeu/mail/MailBoatEntity.kt`:

```kotlin
package dev.vvoleman.refurbishedeu.mail

import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.vehicle.Boat
import net.minecraft.world.level.Level

/**
 * The boat a mailman crosses water in. Its lifetime belongs to the crossing,
 * not to the world.
 */
class MailBoatEntity(type: EntityType<out Boat>, level: Level) : Boat(type, level) {

    /**
     * Never written into the chunk on save, for the reason MailmanEntity
     * documents at length: an entity owned by a route must not be persisted
     * independently of it. A chunk unload mid-crossing would otherwise leave a
     * boat sitting on the lake with nobody left to discard it.
     */
    override fun shouldBeSaved(): Boolean = false

    /**
     * One seat, which is load-bearing rather than cosmetic. Boat.tick() boards
     * any colliding LivingEntity while `passengers.size() < getMaxPassengers()`
     * whenever the pilot is not a Player - and ours never is. With a single
     * seat that test is already false with the mailman aboard, so mail boats
     * do not collect livestock on the way across.
     */
    override fun getMaxPassengers(): Int = 1

    /**
     * Belt-and-braces cleanup, mirroring MailmanEntity.tick() from the other
     * side: a mail boat with no mailman in it has no owner who will ever
     * discard it, so it does so itself. The grace period covers the gap between
     * being spawned and being mounted, which spans at least one tick.
     */
    override fun tick() {
        super.tick()
        if (level.isClientSide) return
        if (tickCount < BOARDING_GRACE_TICKS) return
        if (passengers.none { it is MailmanEntity }) discard()
    }

    companion object {
        const val BOARDING_GRACE_TICKS = 20
    }
}
```

- [ ] **Step 2: Register the entity type**

In `RefurbishedEuBridge.kt`, directly after the `MAILMAN` block (around line 93), add:

```kotlin
    /** Dimensions and tracking range copied from vanilla EntityType.BOAT. */
    val MAIL_BOAT: RegistryObject<EntityType<MailBoatEntity>> = ENTITIES.register("mail_boat") {
        EntityType.Builder.of(::MailBoatEntity, MobCategory.MISC)
            .sized(1.375f, 0.5625f)
            .clientTrackingRange(10)
            .build("mail_boat")
    }
```

Add the import beside the existing mail imports at the top of the file:

```kotlin
import dev.vvoleman.refurbishedeu.mail.MailBoatEntity
```

- [ ] **Step 3: Register the renderer**

In `ClientSetup.kt`, inside `onRegisterRenderers`, after the mailman registration:

```kotlin
        // BoatRenderer is EntityRenderer<Boat>, but registerEntityRenderer takes
        // EntityType<? extends T> against EntityRendererProvider<T>, so T infers
        // as Boat and EntityType<MailBoatEntity> satisfies it. The fixed-generics
        // wall that forced a custom renderer for the mailman does not apply.
        event.registerEntityRenderer(RefurbishedEuBridge.MAIL_BOAT.get()) { context ->
            BoatRenderer(context, false)
        }
```

Add the import:

```kotlin
import net.minecraft.client.renderer.entity.BoatRenderer
```

- [ ] **Step 4: Verify it compiles**

Run: `JAVA_HOME=/home/vvoleman/.jdks/jdk-17.0.20.1+1 ./gradlew build --offline`
Expected: BUILD SUCCESSFUL. If `registerEntityRenderer` fails to infer `T`, write the lambda as
`EntityRendererProvider<Boat> { context -> BoatRenderer(context, false) }` and register against
`MAIL_BOAT.get()` explicitly — do NOT introduce a custom renderer class.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/vvoleman/refurbishedeu/mail/MailBoatEntity.kt \
        src/main/kotlin/dev/vvoleman/refurbishedeu/RefurbishedEuBridge.kt \
        src/main/kotlin/dev/vvoleman/refurbishedeu/ClientSetup.kt
git commit -m "Add the route-owned mail boat

Claude-Session: https://claude.ai/code/session_01QwoDxyZCiDLeZasALDLxAV"
```

---

### Task 5: UseBoatGoal

**Files:**
- Modify: `src/main/kotlin/dev/vvoleman/refurbishedeu/mail/MailmanGoals.kt` (append)
- Modify: `src/main/kotlin/dev/vvoleman/refurbishedeu/mail/MailmanEntity.kt:32-35`

**Interfaces:**
- Consumes: `Crossing`, `WaterCrossing.find`, `DirectBoatPilot`, `Steering`, `MailBoatEntity`, `RefurbishedEuBridge.MAIL_BOAT`, `RepathPlanner`, `MailmanConfig.useBoats/minWaterCrossingWidth/boatCrossingTimeoutTicks/blocksPerSecond`.
- Produces: `UseBoatGoal(mob: MailmanEntity, targetOf: (MailmanEntity) -> BlockPos?)`.

- [ ] **Step 1: Append the goal**

Append to `MailmanGoals.kt`:

```kotlin
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
```

Add these imports to the top of `MailmanGoals.kt`:

```kotlin
import dev.vvoleman.refurbishedeu.RefurbishedEuBridge
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.FluidTags
import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3
```

- [ ] **Step 2: Wire it into the entity**

In `MailmanEntity.kt`, replace the body of `registerGoals` (lines 32-35) with:

```kotlin
        goalSelector.addGoal(0, FloatGoal(this))
        goalSelector.addGoal(1, DeliverMailGoal(this) { it.destination })
        // Above TravelToTargetGoal: when there is water in the way, the boat
        // goal must win Flag.MOVE rather than the walking goal wading in.
        goalSelector.addGoal(2, UseBoatGoal(this) { it.destination })
        goalSelector.addGoal(3, TravelToTargetGoal(this) { it.destination })
        goalSelector.addGoal(9, LookAtPlayerGoal(this, Player::class.java, 6.0f))
```

- [ ] **Step 3: Verify it compiles and nothing regressed**

Run: `JAVA_HOME=/home/vvoleman/.jdks/jdk-17.0.20.1+1 ./gradlew build --offline`
Expected: BUILD SUCCESSFUL, all existing tests still pass.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/dev/vvoleman/refurbishedeu/mail/MailmanGoals.kt \
        src/main/kotlin/dev/vvoleman/refurbishedeu/mail/MailmanEntity.kt
git commit -m "Cross water by boat

Claude-Session: https://claude.ai/code/session_01QwoDxyZCiDLeZasALDLxAV"
```

---

### Task 6: Discard the boat when the route dematerialises

The one change `MailRouteService` needs. Without it, walking 128 blocks away mid-crossing
discards the mailman and leaves the boat — which `MailBoatEntity.tick()` would eventually
clean up, but only in a loaded chunk, and the spec's rule is that the route owns its entities
explicitly rather than relying on the sweep.

**Files:**
- Modify: `src/main/kotlin/dev/vvoleman/refurbishedeu/mail/MailRouteService.kt` (the `dematerialise` function, around line 321)

**Interfaces:**
- Consumes: `MailBoatEntity`.
- Produces: nothing new.

- [ ] **Step 1: Make the change**

In `dematerialise`, replace the `let` block with:

```kotlin
        (level.getEntity(id) as? MailmanEntity)?.let {
            route.pos = it.position()
            route.yTrustworthy = true
            // A mailman mid-crossing owns a boat. Dropping the mailman without
            // it would strand the boat on the water: MailBoatEntity discards
            // itself when riderless, but only while its chunk still ticks, and
            // the route is the explicit owner here.
            (it.vehicle as? MailBoatEntity)?.discard()
            it.discard()
        }
```

- [ ] **Step 2: Verify it compiles and nothing regressed**

Run: `JAVA_HOME=/home/vvoleman/.jdks/jdk-17.0.20.1+1 ./gradlew build --offline`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/dev/vvoleman/refurbishedeu/mail/MailRouteService.kt
git commit -m "Take the boat with the mailman when a route dematerialises

Claude-Session: https://claude.ai/code/session_01QwoDxyZCiDLeZasALDLxAV"
```

---

### Task 7: Documentation and version

**Files:**
- Modify: `README.md` (the Mail section)
- Modify: `gradle.properties` (`mod_version`)
- Modify: `docs/superpowers/specs/2026-09-02-mailman-design.md` (behavioural limit 2)

- [ ] **Step 1: Update the Phase 1 spec's stale limit**

In `docs/superpowers/specs/2026-09-02-mailman-design.md`, behavioural limit 2 currently reads
"No open water in Phase 1 … Phase 2 turns a water stall into a boat leg." Replace with:

```markdown
2. **Open water is crossed by boat.** Implemented in Phase 2; see
   `2026-09-03-mailman-boat-design.md`. A crossing that cannot be staged or
   completed still falls back to the Phase 1 behaviour: the route stalls,
   returns the mail to its origin and tells the sender.
```

- [ ] **Step 2a: Correct the stale README limit**

In `README.md`, in `### Behavioural limits` (around line 131), the second
bullet begins "**A route that can't reach its target stalls, then returns to
sender.**" and cites "(open water, an unreachable mailbox, whatever the
obstruction)". Open water is no longer an example of this. Change that
parenthetical to read:

```markdown
  (an unreachable mailbox, water too wide or too tangled to boat across,
  whatever the obstruction)
```

Change nothing else in that bullet.

- [ ] **Step 2b: Document the new settings in the README config block**

In `README.md`, in `### The `[mailman]` config block` (around line 157), the
TOML sample lists six settings. Add the three new ones so the sample matches
what the mod actually writes:

```toml
    stallTimeoutTicks = 1200     # ticks without progress before a route gives up
    useBoats = true              # cross open water by boat
    minWaterCrossingWidth = 6    # water narrower than this is waded, not boated
    boatCrossingTimeoutTicks = 600  # abandon a crossing taking longer than this
```

(The `stallTimeoutTicks` line is shown only to locate the insertion point —
it already exists; add the three lines after it, inside the same code fence.)

- [ ] **Step 2c: Document the feature in the README**

In `README.md`, insert a new `### Crossing water` section in the `## Mail`
section, immediately BEFORE the `### Behavioural limits` heading:

```markdown
### Crossing water

A mailman that meets open water at least `minWaterCrossingWidth` blocks across
walks to the shore, launches a boat, steers across and carries on from the far
bank. The boat belongs to the delivery: it appears when the crossing starts and
is gone when it ends, and it holds one passenger so it cannot pick up livestock
on the way.

Narrower water is waded rather than boated. If a crossing takes longer than
`boatCrossingTimeoutTicks` the boat is abandoned and the mailman swims, which is
what it did before boats existed. Set `useBoats = false` to restore that
everywhere.

Steering is straight-line, so winding rivers and islands mid-channel are not
solved — the boat times out and the mailman swims.
```

- [ ] **Step 3: Bump the version**

In `gradle.properties`, change `mod_version=0.3.0` to `mod_version=0.4.0`.

- [ ] **Step 4: Verify the whole thing builds**

Run: `JAVA_HOME=/home/vvoleman/.jdks/jdk-17.0.20.1+1 ./gradlew build --offline --rerun-tasks`
Expected: BUILD SUCCESSFUL, jar at `build/libs/refurbished_eu-0.4.0.jar`.

- [ ] **Step 5: Commit**

```bash
git add README.md gradle.properties docs/superpowers/specs/2026-09-02-mailman-design.md
git commit -m "Document boat crossings and bump to 0.4.0

Claude-Session: https://claude.ai/code/session_01QwoDxyZCiDLeZasALDLxAV"
```

---

## In-game verification — the only thing that validates this

Nothing above proves a boat works. `runClient` cannot start in this repo, so no
code here has ever been in water. Install `build/libs/refurbished_eu-0.4.0.jar`
into the real pack and check, in order:

1. **Narrow water is waded.** A 3-block stream between two mailboxes: the
   mailman walks through it, no boat appears.
2. **A wide crossing works.** A 20-block lake: the mailman walks to the shore, a
   boat appears, it crosses, it gets out, the boat disappears.
3. **No litter.** Walk more than 128 blocks away mid-crossing, come back. No
   boat on the water.
4. **The delivery that used to fail now arrives.** Two mailboxes separated by
   water wide enough to have returned the mail as undeliverable before.
5. **No livestock aboard.** Cross a lake with cows on the bank; the boat carries
   only the mailman.
6. **The timeout works.** A crossing with an island in the middle: the boat gets
   stuck, is abandoned within 30 seconds, and the mailman swims on.

Expect a tuning pass. Crossing speed (`blocksPerSecond / 20` per tick),
`MAX_TURN_PER_TICK` and `ALIGNED_DEGREES` are all guesses until seen in water.
