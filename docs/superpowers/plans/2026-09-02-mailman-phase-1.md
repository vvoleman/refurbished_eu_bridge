# Mailman Phase 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver addressed mail between named Refurbished mailboxes by having a custom mailman entity physically walk it there, with delivery that still completes across distances nobody has loaded.

**Architecture:** A cached global mailbox index reads Refurbished's `DeliveryService.save()` (public, no reflection). Deliveries live in our own `SavedData` as *routes*, not as entities: a route ticks server-side regardless of chunk loading, and a `MailmanEntity` is spawned as a *view* of the route only while it is observable. Two new items carry a shared addressing NBT tag.

**Tech Stack:** Kotlin 1.8.21, Forge 1.19.2 (43.4.0), ForgeGradle 5.1, Kotlin for Forge 3.12.0, JDK 17, JUnit 5. Refurbished Furniture 1.0.9 / Framework 0.7.12 / IC2 Classic 2.1.2.1 / CC:Tweaked 1.101.4 are all `compileOnly`.

**Spec:** `docs/superpowers/specs/2026-09-02-mailman-design.md` — read it before Task 1. The plan argues from the spec; where they disagree, the spec wins.

## Execution status (updated 2026-09-03)

**Tasks 1-9 complete and reviewed clean. Tasks 10-14 remain.** Branch `mailman`, off
`main` @ 816c2be. HEAD at handoff: `a573d9b`. **The branch has never been pushed.**

Verified at handoff: `./gradlew build --offline` succeeds and `./gradlew test --offline
--rerun-tasks` gives **22 tests, 0 failures, 0 errors** across 6 test classes. That means
the logic layer is correct and everything compiles. **Nothing has been run in a game** —
see Verification at the foot of this plan.

| Task | Commit | Notes |
|---|---|---|
| 1 Test harness | `3782494` | first tests in this repo; ItemStack works in tests |
| 2 MailAddress | `08b3737` | |
| 3 Mailbox registry parse | `8263cb2` | |
| 4 Lookup by name | `11d7bd6` | |
| 5 Travel arithmetic | `43fcae3` | |
| 6 MailRoute NBT | `326d1e7` | |
| 7 Config | `6c4ed4c` | |
| 8 Letter item | `07ac6f8` | no review findings at any severity |
| 9 Parcel + screen | `0e38dc2`, `a573d9b` | one Critical found and fixed — see below |
| 10-14 | — | not started; briefs for 10 and 11 already extracted |

Four defects in this plan were found and corrected before their tasks ran (commit
`4cbca1d`), and two more during execution (`e3b3782`, `0ffefeb`). The plan text below
already incorporates all six. The two worth knowing:

- **Task 13 must filter mailboxes to the origin's dimension BEFORE calling `byName`**
  (`0ffefeb`). `byName` compares raw block positions, so a same-named mailbox in another
  dimension could win on a meaningless distance and the mail would then be silently
  dropped by the same-dimension check.
- **Task 9's `ParcelMenu` originally let the parcel be placed inside itself and
  destroyed.** The player's hotbar was added as ordinary slots, but the parcel being
  edited lives in the hotbar. Fixed in `a573d9b` with a non-interactive `HeldParcelSlot`
  plus a `clicked()` override, because `ClickType.SWAP` bypasses `Slot.mayPickup`
  entirely — vanilla's `doClick` reads and writes the real inventory by button index.

A full execution ledger, including every ruling and nine deferred minor findings for the
final review, is at `.superpowers/sdd/2026-09-02-mailman-phase-1/progress.md`. That
directory is git-ignored scratch — if it is lost, this section and `git log` are the record.

---

## Global Constraints

- **Build command is `JAVA_HOME=/home/vvoleman/.jdks/jdk-17.0.20.1+1 ./gradlew build --offline`.** There is no `java` on `PATH`; the wrapper reads `org.gradle.java.home` too late to help. Dependencies are cached in `localmaven/`, so `--offline` works.
- **`runClient` and `runServer` do not work and will not be fixed.** IC2 Classic's mixin refmap hardcodes SRG field names absent from a Mojmap dev runtime. No task may claim in-game verification.
- **A green build means "it compiles", never "it works".** Say so in every commit and report.
- **No mixins into Refurbished, IC2 or Framework.** Every base-mod interaction in this plan uses public API only. If a task appears to need a mixin, stop and escalate.
- **Mark overridden Java parameters nullable.** Kotlin turns platform types into non-null params and emits an intrinsic null check that crashes when a Java caller passes null. This has already taken down the client tick once in this repo (`isAudioEqual`). See the README's "Notes for future work".
- **Never put a literal `${...}` in `mods.toml`,** including in comments — it runs through Gradle's `expand`.
- **Mod id is `refurbished_eu`.** New registry entries go through the existing `DeferredRegister`s in `RefurbishedEuBridge.kt`.
- **Package for all new code:** `dev.vvoleman.refurbishedeu.mail`.
- **Config lives in the existing server spec**, as a new `[mailman]` block.

Config defaults, copied verbatim from the spec:

```toml
[mailman]
    blocksPerSecond = 4
    maxActiveRoutes = 32
    maxMaterialisedMailmen = 8
    indexRefreshTicks = 600
    pickupScanTicks = 200
    stallTimeoutTicks = 1200
```

## A note on testing, and what Task 1 is for

This repo has no tests and no test source set. Most of this feature is pure
logic — NBT parsing, name lookup, travel arithmetic — that does not need a
running game, so Task 1 stands up a JUnit source set and Tasks 2–7 are written
test-first against it.

That harness is a genuine risk: Minecraft classes like `ItemStack` need
`Bootstrap.bootStrap()` before registries exist. Task 1 exists to find that out
in five minutes rather than at Task 9. **If Task 1 cannot produce a passing
test, stop and report** — do not silently continue without tests. The fallback
(drop the `ItemStack` round-trip test in Task 6, keep the rest) is written into
Task 1's last step.

Tasks 8–14 build entities, screens and renderers, which cannot be unit tested
without a running client. Those tasks verify by compiling, and each one ends
with an explicit in-game checklist for the human to run against the real pack.

## File Structure

All new files under `src/main/kotlin/dev/vvoleman/refurbishedeu/mail/`:

| File | Responsibility |
|---|---|
| `MailAddress.kt` | read/write the `{Target, Sender}` NBT tag on a stack |
| `MailboxRef.kt` | value type: a mailbox's id, position, dimension, name |
| `MailboxIndex.kt` | cached global registry over `DeliveryService.save()` |
| `Travel.kt` | dead-reckoning arithmetic and stall detection (pure) |
| `MailRoute.kt` | one in-flight delivery + its NBT |
| `MailRouteService.kt` | `SavedData`; owns routes, ticking, materialisation |
| `MailmanEntity.kt` | the entity |
| `MailmanGoals.kt` | `TravelToTargetGoal`, `DeliverMailGoal` |
| `LetterItem.kt` | text mail |
| `ParcelItem.kt` | box mail |
| `ParcelMenu.kt` / `ParcelScreen.kt` | parcel container UI |
| `MailmanConfig.kt` | the `[mailman]` config block |

Tests under `src/test/kotlin/dev/vvoleman/refurbishedeu/mail/`.

Modified: `RefurbishedEuBridge.kt` (new `ENTITIES` register, item and menu
registrations, config registration, server tick hook), `ClientSetup.kt` (entity
renderer, parcel screen), `build.gradle` (test source set), plus lang, models,
textures and recipes.

---

### Task 1: Test harness

**Files:**
- Modify: `build.gradle` (dependencies and a `test` task block)
- Create: `src/test/kotlin/dev/vvoleman/refurbishedeu/mail/HarnessTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: a working `./gradlew test`. Every later test task depends on this.

- [ ] **Step 1: Add the test source set and JUnit 5 to `build.gradle`**

Add to the `dependencies` block:

```groovy
    testImplementation 'org.junit.jupiter:junit-jupiter:5.9.3'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
```

And after the `tasks.withType(...KotlinCompile)` block:

```groovy
tasks.named('test', Test) {
    useJUnitPlatform()
}
```

`mavenCentral()` is already in `repositories`, so JUnit resolves. Note this
means the first build of this task cannot use `--offline`.

- [ ] **Step 2: Write the harness test**

This deliberately tests two things separately: plain NBT (no registries needed)
and `ItemStack` (needs `Bootstrap`). The second is the one that might fail.

```kotlin
package dev.vvoleman.refurbishedeu.mail

import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.Bootstrap
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class HarnessTest {
    companion object {
        @JvmStatic
        @BeforeAll
        fun bootstrap() {
            Bootstrap.bootStrap()
        }
    }

    @Test
    fun `plain nbt works without registries`() {
        val tag = CompoundTag()
        tag.putLong("BlockPosition", BlockPos(1, 2, 3).asLong())
        assertEquals(BlockPos(1, 2, 3), BlockPos.of(tag.getLong("BlockPosition")))
    }

    @Test
    fun `itemstack round trips after bootstrap`() {
        val stack = ItemStack(Items.PAPER, 1)
        val restored = ItemStack.of(stack.save(CompoundTag()))
        assertEquals(Items.PAPER, restored.item)
    }
}
```

- [ ] **Step 3: Run the tests**

Run: `JAVA_HOME=/home/vvoleman/.jdks/jdk-17.0.20.1+1 ./gradlew test --tests '*HarnessTest*' -i`
Expected: both tests PASS.

- [ ] **Step 4: Decide the fallback if `ItemStack` failed**

If `plain nbt works without registries` passes but `itemstack round trips` fails
(typically `Registry` or `SharedConstants` errors even after `Bootstrap`):

1. Add `SharedConstants.setVersion(DetectedVersion.BUILT_IN)` **before**
   `Bootstrap.bootStrap()` and re-run. This is the usual fix.
2. If it still fails, delete the `itemstack round trips` test, keep the rest,
   and record in the commit message that Task 6's NBT round-trip test is
   dropped for this reason. **Do not delete the whole harness** — Tasks 2, 3, 4,
   5 need no registries and must still be test-driven.

If `plain nbt works without registries` also fails, **stop and report.** The
plan's testing strategy is invalid and needs rethinking before Task 2.

- [ ] **Step 5: Commit**

```bash
git add build.gradle src/test
git commit -m "Add JUnit test source set

First tests in this repo. Pure-logic mail code is unit testable even though
the game itself cannot run in dev."
```

---

### Task 2: MailAddress

**Files:**
- Create: `src/main/kotlin/dev/vvoleman/refurbishedeu/mail/MailAddress.kt`
- Test: `src/test/kotlin/dev/vvoleman/refurbishedeu/mail/MailAddressTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `MailAddress.target(ItemStack): String?`,
  `MailAddress.sender(ItemStack): UUID?`,
  `MailAddress.apply(ItemStack, target: String, sender: UUID)`,
  `MailAddress.isAddressed(ItemStack): Boolean`.
  Tasks 8, 9 and 13 all call these.

- [ ] **Step 1: Write the failing test**

```kotlin
package dev.vvoleman.refurbishedeu.mail

import net.minecraft.DetectedVersion
import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.util.UUID

class MailAddressTest {
    companion object {
        @JvmStatic
        @BeforeAll
        fun bootstrap() {
            SharedConstants.setVersion(DetectedVersion.BUILT_IN)
            Bootstrap.bootStrap()
        }
    }

    private val sender = UUID.fromString("00000000-0000-0000-0000-0000000000aa")

    @Test
    fun `a bare stack is not addressed`() {
        val stack = ItemStack(Items.PAPER)
        assertFalse(MailAddress.isAddressed(stack))
        assertNull(MailAddress.target(stack))
        assertNull(MailAddress.sender(stack))
    }

    @Test
    fun `apply then read round trips`() {
        val stack = ItemStack(Items.PAPER)
        MailAddress.apply(stack, "Town Hall", sender)
        assertTrue(MailAddress.isAddressed(stack))
        assertEquals("Town Hall", MailAddress.target(stack))
        assertEquals(sender, MailAddress.sender(stack))
    }

    @Test
    fun `a blank target is not an address`() {
        val stack = ItemStack(Items.PAPER)
        MailAddress.apply(stack, "   ", sender)
        assertFalse(MailAddress.isAddressed(stack))
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `JAVA_HOME=/home/vvoleman/.jdks/jdk-17.0.20.1+1 ./gradlew test --tests '*MailAddressTest*'`
Expected: FAIL — `Unresolved reference: MailAddress`.

- [ ] **Step 3: Implement**

```kotlin
package dev.vvoleman.refurbishedeu.mail

import net.minecraft.nbt.CompoundTag
import net.minecraft.world.item.ItemStack
import java.util.UUID

/**
 * The addressing tag shared by Letter and Parcel.
 *
 * Deliberately ours rather than Refurbished's: their PackageItem addresses a
 * mailbox by UUID through the Post Box UI, which is the instant path. Ours
 * addresses by name so a player can write a letter without a Post Box.
 */
object MailAddress {

    private const val ROOT = "RefurbishedEuMail"
    private const val TARGET = "Target"
    private const val SENDER = "Sender"

    fun apply(stack: ItemStack, target: String, sender: UUID) {
        val root = CompoundTag()
        root.putString(TARGET, target)
        root.putUUID(SENDER, sender)
        stack.getOrCreateTag().put(ROOT, root)
    }

    fun target(stack: ItemStack): String? {
        val root = stack.tag?.getCompound(ROOT) ?: return null
        val value = root.getString(TARGET)
        return value.ifBlank { null }
    }

    fun sender(stack: ItemStack): UUID? {
        val root = stack.tag?.getCompound(ROOT) ?: return null
        return if (root.hasUUID(SENDER)) root.getUUID(SENDER) else null
    }

    fun isAddressed(stack: ItemStack): Boolean = target(stack) != null
}
```

- [ ] **Step 4: Run the tests**

Run: `JAVA_HOME=/home/vvoleman/.jdks/jdk-17.0.20.1+1 ./gradlew test --tests '*MailAddressTest*'`
Expected: all three PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/vvoleman/refurbishedeu/mail/MailAddress.kt src/test
git commit -m "Add shared mail addressing tag"
```

---

### Task 3: MailboxRef and index parsing

**Files:**
- Create: `src/main/kotlin/dev/vvoleman/refurbishedeu/mail/MailboxRef.kt`
- Create: `src/main/kotlin/dev/vvoleman/refurbishedeu/mail/MailboxIndex.kt`
- Test: `src/test/kotlin/dev/vvoleman/refurbishedeu/mail/MailboxIndexTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `MailboxRef(id: UUID, pos: BlockPos, level: ResourceKey<Level>, name: String?)`
  and `MailboxIndex.parse(tag: CompoundTag): List<MailboxRef>`.
  Task 4 adds lookup on top; Tasks 12 and 13 consume `MailboxRef`.

This task parses the tag only. It does not talk to a server, which is exactly
why it is testable: the schema below was read out of Refurbished's bytecode and
can be synthesised by hand.

- [ ] **Step 1: Write the failing test**

```kotlin
package dev.vvoleman.refurbishedeu.mail

import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.UUID

class MailboxIndexTest {

    private val idA = UUID.fromString("00000000-0000-0000-0000-00000000000a")
    private val idB = UUID.fromString("00000000-0000-0000-0000-00000000000b")

    /** Mirrors what DeliveryService.save() writes, per the spec. */
    private fun mailboxTag(id: UUID, level: String, pos: BlockPos, name: String?): CompoundTag {
        val tag = CompoundTag()
        tag.putUUID("UUID", id)
        tag.putString("Level", level)
        tag.putLong("BlockPosition", pos.asLong())
        if (name != null) tag.putString("CustomName", name)
        return tag
    }

    private fun serviceTag(vararg boxes: CompoundTag): CompoundTag {
        val list = ListTag()
        boxes.forEach { list.add(it) }
        val root = CompoundTag()
        root.put("Mailboxes", list)
        return root
    }

    @Test
    fun `parses a named mailbox`() {
        val tag = serviceTag(
            mailboxTag(idA, "minecraft:overworld", BlockPos(10, 64, -20), "Town Hall")
        )
        val refs = MailboxIndex.parse(tag)
        assertEquals(1, refs.size)
        assertEquals(idA, refs[0].id)
        assertEquals(BlockPos(10, 64, -20), refs[0].pos)
        assertEquals("Town Hall", refs[0].name)
        assertEquals("minecraft:overworld", refs[0].level.location().toString())
    }

    @Test
    fun `an unnamed mailbox parses with a null name`() {
        val tag = serviceTag(mailboxTag(idB, "minecraft:overworld", BlockPos.ZERO, null))
        assertNull(MailboxIndex.parse(tag)[0].name)
    }

    @Test
    fun `an empty service yields nothing`() {
        assertEquals(emptyList<MailboxRef>(), MailboxIndex.parse(CompoundTag()))
    }

    @Test
    fun `a malformed entry is skipped rather than throwing`() {
        val broken = CompoundTag().also { it.putString("Level", "minecraft:overworld") }
        val tag = serviceTag(broken, mailboxTag(idA, "minecraft:overworld", BlockPos.ZERO, "Ok"))
        val refs = MailboxIndex.parse(tag)
        assertEquals(1, refs.size)
        assertEquals("Ok", refs[0].name)
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `JAVA_HOME=/home/vvoleman/.jdks/jdk-17.0.20.1+1 ./gradlew test --tests '*MailboxIndexTest*'`
Expected: FAIL — `Unresolved reference: MailboxIndex`.

- [ ] **Step 3: Implement `MailboxRef.kt`**

```kotlin
package dev.vvoleman.refurbishedeu.mail

import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level
import java.util.UUID

/** One mailbox as the mail system needs to see it: where it is and what it's called. */
data class MailboxRef(
    val id: UUID,
    val pos: BlockPos,
    val level: ResourceKey<Level>,
    val name: String?,
)
```

- [ ] **Step 4: Implement `MailboxIndex.parse`**

```kotlin
package dev.vvoleman.refurbishedeu.mail

import net.minecraft.core.BlockPos
import net.minecraft.core.Registry
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.Level

/**
 * The world's mailboxes, read out of Refurbished's DeliveryService.
 *
 * DeliveryService keeps its mailbox map private, and the only public
 * enumeration - encodeMailboxes/decodeMailboxes - downgrades to IMailbox, which
 * has no position and so cannot be walked to. save() is public and writes every
 * mailbox with its position, and only reads the map to do it, so calling it on a
 * scratch tag is a safe way to enumerate. No reflection, no mixin.
 */
object MailboxIndex {

    fun parse(tag: CompoundTag): List<MailboxRef> =
        tag.getList("Mailboxes", Tag.TAG_COMPOUND.toInt()).mapNotNull { entry ->
            parseOne(entry as CompoundTag)
        }

    /**
     * A mailbox we can't make sense of is skipped, not fatal: this tag comes
     * from another mod and one bad entry must not take out the whole index.
     */
    private fun parseOne(tag: CompoundTag): MailboxRef? {
        if (!tag.hasUUID("UUID")) return null
        if (!tag.contains("BlockPosition")) return null
        val levelId = ResourceLocation.tryParse(tag.getString("Level")) ?: return null
        val name = if (tag.contains("CustomName")) tag.getString("CustomName").ifBlank { null } else null
        return MailboxRef(
            id = tag.getUUID("UUID"),
            pos = BlockPos.of(tag.getLong("BlockPosition")),
            level = ResourceKey.create(Registry.DIMENSION_REGISTRY, levelId),
            name = name,
        )
    }
}
```

- [ ] **Step 5: Run the tests**

Run: `JAVA_HOME=/home/vvoleman/.jdks/jdk-17.0.20.1+1 ./gradlew test --tests '*MailboxIndexTest*'`
Expected: all four PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/dev/vvoleman/refurbishedeu/mail src/test
git commit -m "Parse Refurbished's mailbox registry

DeliveryService.save() is the only public API that exposes mailbox positions;
encodeMailboxes strips them."
```

---

### Task 4: Mailbox lookup by name

**Files:**
- Modify: `src/main/kotlin/dev/vvoleman/refurbishedeu/mail/MailboxIndex.kt`
- Test: `src/test/kotlin/dev/vvoleman/refurbishedeu/mail/MailboxLookupTest.kt`

**Interfaces:**
- Consumes: `MailboxRef`, `MailboxIndex.parse` (Task 3).
- Produces: `MailboxIndex.byName(refs: List<MailboxRef>, name: String, from: BlockPos): MailboxRef?`
  and `MailboxIndex.named(refs: List<MailboxRef>): List<MailboxRef>`.
  Task 13 calls `byName` to resolve a letter's target.

Names are not unique — Refurbished does not enforce it — so ties resolve to the
nearest to the origin, per the spec.

- [ ] **Step 1: Write the failing test**

```kotlin
package dev.vvoleman.refurbishedeu.mail

import net.minecraft.DetectedVersion
import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.core.Registry
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.Bootstrap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.util.UUID

class MailboxLookupTest {

    companion object {
        // Touching Registry.DIMENSION_REGISTRY throws "Not bootstrapped" without this.
        @JvmStatic
        @BeforeAll
        fun bootstrap() {
            SharedConstants.setVersion(DetectedVersion.BUILT_IN)
            Bootstrap.bootStrap()
        }
    }

    private val overworld =
        ResourceKey.create(Registry.DIMENSION_REGISTRY, ResourceLocation("minecraft:overworld"))

    private fun ref(name: String?, pos: BlockPos) =
        MailboxRef(UUID.randomUUID(), pos, overworld, name)

    @Test
    fun `finds a mailbox by exact name`() {
        val target = ref("Town Hall", BlockPos(100, 64, 0))
        val refs = listOf(ref("Shop", BlockPos.ZERO), target)
        assertEquals(target, MailboxIndex.byName(refs, "Town Hall", BlockPos.ZERO))
    }

    @Test
    fun `name matching ignores case and surrounding space`() {
        val target = ref("Town Hall", BlockPos(100, 64, 0))
        assertEquals(target, MailboxIndex.byName(listOf(target), "  town hall ", BlockPos.ZERO))
    }

    @Test
    fun `duplicate names resolve to the nearest`() {
        val near = ref("Depot", BlockPos(10, 64, 0))
        val far = ref("Depot", BlockPos(900, 64, 0))
        assertEquals(near, MailboxIndex.byName(listOf(far, near), "Depot", BlockPos.ZERO))
    }

    @Test
    fun `an unknown name resolves to nothing`() {
        assertNull(MailboxIndex.byName(listOf(ref("Shop", BlockPos.ZERO)), "Nowhere", BlockPos.ZERO))
    }

    @Test
    fun `unnamed mailboxes are never addressable`() {
        assertNull(MailboxIndex.byName(listOf(ref(null, BlockPos.ZERO)), "", BlockPos.ZERO))
        assertEquals(emptyList<MailboxRef>(), MailboxIndex.named(listOf(ref(null, BlockPos.ZERO))))
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `JAVA_HOME=/home/vvoleman/.jdks/jdk-17.0.20.1+1 ./gradlew test --tests '*MailboxLookupTest*'`
Expected: FAIL — `Unresolved reference: byName`.

- [ ] **Step 3: Implement — add to `MailboxIndex`**

```kotlin
    /** Only named mailboxes can be addressed; the rest are invisible to the mail system. */
    fun named(refs: List<MailboxRef>): List<MailboxRef> = refs.filter { it.name != null }

    /**
     * Refurbished allows duplicate mailbox names, so this can be genuinely
     * ambiguous. Nearest to the sender wins, which is both predictable and
     * usually what was meant.
     */
    fun byName(refs: List<MailboxRef>, name: String, from: BlockPos): MailboxRef? {
        val wanted = name.trim().lowercase()
        if (wanted.isEmpty()) return null
        return named(refs)
            .filter { it.name!!.trim().lowercase() == wanted }
            .minByOrNull { it.pos.distSqr(from) }
    }
```

- [ ] **Step 4: Run the tests**

Run: `JAVA_HOME=/home/vvoleman/.jdks/jdk-17.0.20.1+1 ./gradlew test --tests '*MailboxLookupTest*'`
Expected: all five PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/vvoleman/refurbishedeu/mail/MailboxIndex.kt src/test
git commit -m "Resolve mailboxes by name, nearest wins on ties"
```

---

### Task 5: Travel arithmetic

**Files:**
- Create: `src/main/kotlin/dev/vvoleman/refurbishedeu/mail/Travel.kt`
- Test: `src/test/kotlin/dev/vvoleman/refurbishedeu/mail/TravelTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `Travel.advance(from: Vec3, to: Vec3, blocksPerSecond: Double, ticks: Int): Vec3`
  and `Travel.horizontalDistance(a: Vec3, b: Vec3): Double`.
  Task 12 calls both.

Per the spec, dead reckoning is **horizontal only** — resolving ground height in
unloaded chunks would mean loading them, which defeats the whole design. `y` is
carried through untouched and resolved at materialisation.

- [ ] **Step 1: Write the failing test**

```kotlin
package dev.vvoleman.refurbishedeu.mail

import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TravelTest {

    @Test
    fun `advances toward the target at the configured speed`() {
        // 4 blocks/second for 20 ticks (1 second) = 4 blocks along +x
        val next = Travel.advance(Vec3(0.0, 64.0, 0.0), Vec3(100.0, 64.0, 0.0), 4.0, 20)
        assertEquals(4.0, next.x, 1e-6)
        assertEquals(0.0, next.z, 1e-6)
    }

    @Test
    fun `never overshoots the target`() {
        val next = Travel.advance(Vec3(0.0, 64.0, 0.0), Vec3(1.0, 64.0, 0.0), 4.0, 20)
        assertEquals(1.0, next.x, 1e-6)
    }

    @Test
    fun `carries y through untouched`() {
        // y is meaningless while unobserved; it must not be interpolated.
        val next = Travel.advance(Vec3(0.0, 64.0, 0.0), Vec3(100.0, 200.0, 0.0), 4.0, 20)
        assertEquals(64.0, next.y, 1e-6)
    }

    @Test
    fun `distance ignores height`() {
        assertEquals(3.0, Travel.horizontalDistance(Vec3(0.0, 0.0, 0.0), Vec3(3.0, 999.0, 0.0)), 1e-6)
    }

    @Test
    fun `standing on the target does not move or divide by zero`() {
        val here = Vec3(5.0, 64.0, 5.0)
        assertEquals(here, Travel.advance(here, Vec3(5.0, 70.0, 5.0), 4.0, 20))
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `JAVA_HOME=/home/vvoleman/.jdks/jdk-17.0.20.1+1 ./gradlew test --tests '*TravelTest*'`
Expected: FAIL — `Unresolved reference: Travel`.

- [ ] **Step 3: Implement**

```kotlin
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
}
```

- [ ] **Step 4: Run the tests**

Run: `JAVA_HOME=/home/vvoleman/.jdks/jdk-17.0.20.1+1 ./gradlew test --tests '*TravelTest*'`
Expected: all five PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/vvoleman/refurbishedeu/mail/Travel.kt src/test
git commit -m "Add dead-reckoning travel arithmetic"
```

---

### Task 6: MailRoute and its NBT

**Files:**
- Create: `src/main/kotlin/dev/vvoleman/refurbishedeu/mail/MailRoute.kt`
- Test: `src/test/kotlin/dev/vvoleman/refurbishedeu/mail/MailRouteTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `MailRoute` with fields `id, stack, originId, targetId, level, pos, state, entity, lastDistance, stalledTicks`;
  `enum class RouteState { TRAVELLING, RETURNING }`;
  `MailRoute.save(): CompoundTag` and `MailRoute.load(tag: CompoundTag): MailRoute?`.
  Task 12 owns a collection of these.

> If Task 1 Step 4 dropped the `ItemStack` test, drop `a route round trips
> through nbt`'s stack assertion here too and assert only the scalar fields.
> Keep the rest of the test.

- [ ] **Step 1: Write the failing test**

```kotlin
package dev.vvoleman.refurbishedeu.mail

import net.minecraft.DetectedVersion
import net.minecraft.SharedConstants
import net.minecraft.core.Registry
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.Bootstrap
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.util.UUID

class MailRouteTest {
    companion object {
        @JvmStatic
        @BeforeAll
        fun bootstrap() {
            SharedConstants.setVersion(DetectedVersion.BUILT_IN)
            Bootstrap.bootStrap()
        }
    }

    private val overworld =
        ResourceKey.create(Registry.DIMENSION_REGISTRY, ResourceLocation("minecraft:overworld"))

    private fun route() = MailRoute(
        id = UUID.fromString("00000000-0000-0000-0000-000000000001"),
        stack = ItemStack(Items.PAPER, 1),
        originId = UUID.fromString("00000000-0000-0000-0000-00000000000a"),
        targetId = UUID.fromString("00000000-0000-0000-0000-00000000000b"),
        level = overworld,
        pos = Vec3(1.0, 64.0, 2.0),
        state = RouteState.TRAVELLING,
    )

    @Test
    fun `a route round trips through nbt`() {
        val restored = MailRoute.load(route().save())!!
        assertEquals(route().id, restored.id)
        assertEquals(route().originId, restored.originId)
        assertEquals(route().targetId, restored.targetId)
        assertEquals(route().pos, restored.pos)
        assertEquals(RouteState.TRAVELLING, restored.state)
        assertEquals(overworld, restored.level)
        assertEquals(Items.PAPER, restored.stack.item)
    }

    @Test
    fun `state survives the round trip`() {
        val returning = route().also { it.state = RouteState.RETURNING }
        assertEquals(RouteState.RETURNING, MailRoute.load(returning.save())!!.state)
    }

    @Test
    fun `an unparseable route loads as null rather than throwing`() {
        assertNull(MailRoute.load(net.minecraft.nbt.CompoundTag()))
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `JAVA_HOME=/home/vvoleman/.jdks/jdk-17.0.20.1+1 ./gradlew test --tests '*MailRouteTest*'`
Expected: FAIL — `Unresolved reference: MailRoute`.

- [ ] **Step 3: Implement**

```kotlin
package dev.vvoleman.refurbishedeu.mail

import net.minecraft.core.Registry
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import java.util.UUID

enum class RouteState { TRAVELLING, RETURNING }

/**
 * One delivery in flight.
 *
 * The route - not the entity - is the durable thing. An entity cannot walk
 * through unloaded chunks because nothing ticks out there, so the mailman is
 * spawned as a view of this record only while somebody is close enough to see
 * it. The carried stack lives here, which is why a delivery survives chunk
 * unload and server restart instead of being dropped in an unloaded chunk.
 */
class MailRoute(
    val id: UUID,
    val stack: ItemStack,
    val originId: UUID,
    val targetId: UUID,
    val level: ResourceKey<Level>,
    var pos: Vec3,
    var state: RouteState,
    var entity: UUID? = null,
    /** Horizontal distance at the last progress check; drives stall detection. */
    var lastDistance: Double = Double.MAX_VALUE,
    var stalledTicks: Int = 0,
) {

    fun save(): CompoundTag {
        val tag = CompoundTag()
        tag.putUUID("Id", id)
        tag.put("Stack", stack.save(CompoundTag()))
        tag.putUUID("Origin", originId)
        tag.putUUID("Target", targetId)
        tag.putString("Level", level.location().toString())
        tag.putDouble("X", pos.x)
        tag.putDouble("Y", pos.y)
        tag.putDouble("Z", pos.z)
        tag.putString("State", state.name)
        tag.putDouble("LastDistance", lastDistance)
        tag.putInt("StalledTicks", stalledTicks)
        entity?.let { tag.putUUID("Entity", it) }
        return tag
    }

    companion object {
        /** A route we can't read is dropped, not fatal - one bad record must not lose the rest. */
        fun load(tag: CompoundTag): MailRoute? {
            if (!tag.hasUUID("Id") || !tag.hasUUID("Origin") || !tag.hasUUID("Target")) return null
            val levelId = ResourceLocation.tryParse(tag.getString("Level")) ?: return null
            val state = runCatching { RouteState.valueOf(tag.getString("State")) }.getOrNull() ?: return null
            return MailRoute(
                id = tag.getUUID("Id"),
                stack = ItemStack.of(tag.getCompound("Stack")),
                originId = tag.getUUID("Origin"),
                targetId = tag.getUUID("Target"),
                level = ResourceKey.create(Registry.DIMENSION_REGISTRY, levelId),
                pos = Vec3(tag.getDouble("X"), tag.getDouble("Y"), tag.getDouble("Z")),
                state = state,
                entity = if (tag.hasUUID("Entity")) tag.getUUID("Entity") else null,
                lastDistance = tag.getDouble("LastDistance"),
                stalledTicks = tag.getInt("StalledTicks"),
            )
        }
    }
}
```

- [ ] **Step 4: Run the tests**

Run: `JAVA_HOME=/home/vvoleman/.jdks/jdk-17.0.20.1+1 ./gradlew test --tests '*MailRouteTest*'`
Expected: all three PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/dev/vvoleman/refurbishedeu/mail/MailRoute.kt src/test
git commit -m "Add the in-flight mail route record"
```

---

### Task 7: Mailman config

**Files:**
- Create: `src/main/kotlin/dev/vvoleman/refurbishedeu/mail/MailmanConfig.kt`
- Modify: `src/main/kotlin/dev/vvoleman/refurbishedeu/RefurbishedEuBridge.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `MailmanConfig.SPEC`, and readers `blocksPerSecond(): Double`,
  `maxActiveRoutes(): Int`, `maxMaterialisedMailmen(): Int`,
  `indexRefreshTicks(): Int`, `pickupScanTicks(): Int`, `stallTimeoutTicks(): Int`.
  Tasks 12 and 13 read these.

Follow `TransformerConfig.kt` exactly, including its `read()` fallback: config
values throw if read before the spec loads, and the mail tick can fire while a
world is still coming up.

- [ ] **Step 1: Implement the config**

```kotlin
package dev.vvoleman.refurbishedeu.mail

import net.minecraftforge.common.ForgeConfigSpec

/**
 * Mailman balance settings, in the same server config file as [transformer].
 *
 * Server-side so the values are per-world and authoritative; delivery timing
 * must not depend on what a client thinks.
 */
object MailmanConfig {

    val blocksPerSecond: ForgeConfigSpec.DoubleValue
    val maxActiveRoutes: ForgeConfigSpec.IntValue
    val maxMaterialisedMailmen: ForgeConfigSpec.IntValue
    val indexRefreshTicks: ForgeConfigSpec.IntValue
    val pickupScanTicks: ForgeConfigSpec.IntValue
    val stallTimeoutTicks: ForgeConfigSpec.IntValue
    val SPEC: ForgeConfigSpec

    init {
        val builder = ForgeConfigSpec.Builder()
        builder.comment("Mailman delivery settings").push("mailman")

        blocksPerSecond = builder
            .comment(
                "How fast mail travels while nobody is watching it, in blocks per second.",
                "A materialised mailman walks at its own entity speed instead.",
                "At 4, a 5000-block delivery takes about 20 minutes."
            )
            .defineInRange("blocksPerSecond", 4.0, 0.1, 100.0)

        maxActiveRoutes = builder
            .comment("Concurrent deliveries server-wide. Beyond this, mail waits in its mailbox.")
            .defineInRange("maxActiveRoutes", 32, 1, 1024)

        maxMaterialisedMailmen = builder
            .comment(
                "How many mailmen may exist as real entities at once.",
                "Caps the pathfinding cost when many deliveries pass one place."
            )
            .defineInRange("maxMaterialisedMailmen", 8, 1, 128)

        indexRefreshTicks = builder
            .comment("How often to rebuild the mailbox index, in ticks.")
            .defineInRange("indexRefreshTicks", 600, 20, 24000)

        pickupScanTicks = builder
            .comment("How often to sweep mailboxes for outgoing mail, in ticks.")
            .defineInRange("pickupScanTicks", 200, 20, 24000)

        stallTimeoutTicks = builder
            .comment(
                "If a route gets no closer to its target for this many ticks it is",
                "undeliverable - open water, or a mailbox that can't be walked to -",
                "and the mail is carried back to where it was posted."
            )
            .defineInRange("stallTimeoutTicks", 1200, 100, 72000)

        builder.pop()
        SPEC = builder.build()
    }

    private fun <T> read(value: ForgeConfigSpec.ConfigValue<T>, fallback: T): T =
        if (SPEC.isLoaded) value.get() else fallback

    fun blocksPerSecond(): Double = read(blocksPerSecond, 4.0)
    fun maxActiveRoutes(): Int = read(maxActiveRoutes, 32)
    fun maxMaterialisedMailmen(): Int = read(maxMaterialisedMailmen, 8)
    fun indexRefreshTicks(): Int = read(indexRefreshTicks, 600)
    fun pickupScanTicks(): Int = read(pickupScanTicks, 200)
    fun stallTimeoutTicks(): Int = read(stallTimeoutTicks, 1200)
}
```

- [ ] **Step 2: Register the spec**

In `RefurbishedEuBridge.kt`'s `init` block, directly after the existing
`registerConfig` line:

```kotlin
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, MailmanConfig.SPEC, "refurbished_eu-mailman-server.toml")
```

A separate file rather than merging into the transformer spec: Forge allows one
spec per `(mod, type)` pair only when the file name differs, and keeping the
files apart means a broken mail config cannot stop transformers loading.

- [ ] **Step 3: Verify it compiles**

Run: `JAVA_HOME=/home/vvoleman/.jdks/jdk-17.0.20.1+1 ./gradlew build --offline`
Expected: BUILD SUCCESSFUL. This proves compilation only — the config file
itself is not written until a world loads in the real pack.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/dev/vvoleman/refurbishedeu
git commit -m "Add mailman server config"
```

---

### Task 8: Letter item

**Files:**
- Create: `src/main/kotlin/dev/vvoleman/refurbishedeu/mail/LetterItem.kt`
- Modify: `src/main/kotlin/dev/vvoleman/refurbishedeu/RefurbishedEuBridge.kt`
- Create: `src/main/resources/assets/refurbished_eu/models/item/letter.json`
- Create: `src/main/resources/assets/refurbished_eu/textures/item/letter.png` (16x16)
- Create: `src/main/resources/data/refurbished_eu/recipes/letter.json`
- Modify: `src/main/resources/assets/refurbished_eu/lang/en_us.json`

**Interfaces:**
- Consumes: `MailAddress` (Task 2).
- Produces: `RefurbishedEuBridge.LETTER: RegistryObject<Item>`. Task 13's sweep
  matches on it; Task 14's README documents it.

A letter is addressed by its display name: name it `Town Hall` in an anvil and
it is addressed to the mailbox called `Town Hall`. That needs no new screen, no
packet and no client code, which is why this task is one file plus assets.

- [ ] **Step 1: Implement the item**

```kotlin
package dev.vvoleman.refurbishedeu.mail

import net.minecraft.network.chat.Component
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.level.Level

/**
 * Text mail.
 *
 * Addressed by naming the stack - an anvil rename sets the target mailbox. That
 * avoids a bespoke screen and a packet for what is one string, and it matches
 * how players already address things in vanilla.
 */
class LetterItem(properties: Properties) : Item(properties) {

    /**
     * Nullable parameters throughout: Kotlin would otherwise emit intrinsic null
     * checks on these Java overrides and crash when the game passes null, which
     * has already happened once in this mod.
     */
    override fun appendHoverText(
        stack: ItemStack,
        level: Level?,
        tooltip: MutableList<Component>,
        flag: TooltipFlag,
    ) {
        val target = MailAddress.target(stack) ?: targetFromName(stack)
        if (target != null) {
            tooltip.add(Component.translatable("tooltip.refurbished_eu.addressed_to", target))
        } else {
            tooltip.add(Component.translatable("tooltip.refurbished_eu.unaddressed"))
        }
    }

    companion object {
        /** A renamed stack is an addressed stack; the sweep in MailRouteService reads this. */
        fun targetFromName(stack: ItemStack): String? =
            if (stack.hasCustomHoverName()) stack.hoverName.string.trim().ifBlank { null } else null
    }
}
```

- [ ] **Step 2: Register it**

In `RefurbishedEuBridge.kt`, after `TRANSFORMER_ITEMS`:

```kotlin
    val LETTER: RegistryObject<Item> = ITEMS.register("letter") {
        LetterItem(Item.Properties().stacksTo(16).tab(CreativeModeTab.TAB_MISC))
    }
```

- [ ] **Step 3: Add the model**

`src/main/resources/assets/refurbished_eu/models/item/letter.json`:

```json
{
  "parent": "minecraft:item/generated",
  "textures": { "layer0": "refurbished_eu:item/letter" }
}
```

Create `textures/item/letter.png` as a 16x16 envelope. A flat white rectangle
with a grey diagonal fold is enough; this is not a blocker for correctness.

- [ ] **Step 4: Add the recipe**

`src/main/resources/data/refurbished_eu/recipes/letter.json`:

```json
{
  "type": "minecraft:crafting_shapeless",
  "ingredients": [
    { "item": "minecraft:paper" },
    { "item": "minecraft:paper" }
  ],
  "result": { "item": "refurbished_eu:letter", "count": 1 }
}
```

- [ ] **Step 5: Add lang keys**

Add to `assets/refurbished_eu/lang/en_us.json`:

```json
  "item.refurbished_eu.letter": "Letter",
  "tooltip.refurbished_eu.addressed_to": "To: %s",
  "tooltip.refurbished_eu.unaddressed": "Name it in an anvil to address it"
```

- [ ] **Step 6: Verify it compiles**

Run: `JAVA_HOME=/home/vvoleman/.jdks/jdk-17.0.20.1+1 ./gradlew build --offline`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add src/main
git commit -m "Add the Letter item

Addressed by naming the stack, so no new screen or packet is needed.
Compiles; not yet tested in game."
```

**In-game checklist (for the human, against the real pack):** craft a letter;
name it in an anvil; confirm the tooltip reads `To: <name>`; confirm an unnamed
letter reads the unaddressed hint.

---

### Task 9: Parcel item and its container

**Files:**
- Create: `src/main/kotlin/dev/vvoleman/refurbishedeu/mail/ParcelItem.kt`
- Create: `src/main/kotlin/dev/vvoleman/refurbishedeu/mail/ParcelMenu.kt`
- Create: `src/main/kotlin/dev/vvoleman/refurbishedeu/mail/ParcelScreen.kt`
- Modify: `RefurbishedEuBridge.kt`, `ClientSetup.kt`
- Create: model, texture, recipe; modify lang

**Interfaces:**
- Consumes: `MailAddress` (Task 2), `LetterItem.targetFromName` (Task 8).
- Produces: `RefurbishedEuBridge.PARCEL: RegistryObject<Item>`,
  `RefurbishedEuBridge.PARCEL_MENU: RegistryObject<MenuType<ParcelMenu>>`,
  `ParcelItem.contents(stack: ItemStack): NonNullList<ItemStack>`.
  Task 13's sweep matches on `PARCEL`.

Addressed the same way as the letter — by stack name — so the two items behave
identically from the mail system's point of view. The parcel additionally holds
9 item slots, stored on the stack as a vanilla `Items` list tag.

- [ ] **Step 1: Implement `ParcelItem`**

```kotlin
package dev.vvoleman.refurbishedeu.mail

import net.minecraft.core.NonNullList
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.world.Container
import net.minecraft.world.ContainerHelper
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.SimpleContainer
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.level.Level

/** Box mail: nine slots, addressed by naming the stack, exactly like a Letter. */
class ParcelItem(properties: Properties) : Item(properties) {

    override fun use(
        level: Level,
        player: Player,
        hand: InteractionHand,
    ): InteractionResultHolder<ItemStack> {
        val stack = player.getItemInHand(hand)
        if (!level.isClientSide && player is net.minecraft.server.level.ServerPlayer) {
            net.minecraftforge.network.NetworkHooks.openScreen(player, ParcelMenu.provider(stack, hand))
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide)
    }

    override fun appendHoverText(
        stack: ItemStack,
        level: Level?,
        tooltip: MutableList<Component>,
        flag: TooltipFlag,
    ) {
        val target = MailAddress.target(stack) ?: LetterItem.targetFromName(stack)
        if (target != null) {
            tooltip.add(Component.translatable("tooltip.refurbished_eu.addressed_to", target))
        } else {
            tooltip.add(Component.translatable("tooltip.refurbished_eu.unaddressed"))
        }
        val filled = contents(stack).count { !it.isEmpty }
        tooltip.add(Component.translatable("tooltip.refurbished_eu.parcel_contents", filled, SIZE))
    }

    companion object {
        const val SIZE = 9

        fun contents(stack: ItemStack): NonNullList<ItemStack> {
            val items = NonNullList.withSize(SIZE, ItemStack.EMPTY)
            val tag = stack.tag ?: return items
            ContainerHelper.loadAllItems(tag.getCompound("Parcel"), items)
            return items
        }

        fun store(stack: ItemStack, container: Container) {
            val items = NonNullList.withSize(SIZE, ItemStack.EMPTY)
            for (i in 0 until SIZE) items[i] = container.getItem(i)
            val tag = CompoundTag()
            ContainerHelper.saveAllItems(tag, items)
            stack.getOrCreateTag().put("Parcel", tag)
        }

        fun asContainer(stack: ItemStack): SimpleContainer {
            val container = SimpleContainer(SIZE)
            contents(stack).forEachIndexed { i, s -> container.setItem(i, s) }
            return container
        }
    }
}
```

- [ ] **Step 2: Implement `ParcelMenu`**

```kotlin
package dev.vvoleman.refurbishedeu.mail

import net.minecraft.network.chat.Component
import net.minecraft.world.MenuProvider
import net.minecraft.world.SimpleContainer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack
import dev.vvoleman.refurbishedeu.RefurbishedEuBridge

class ParcelMenu(
    containerId: Int,
    playerInventory: Inventory,
    private val parcel: ItemStack,
    private val contents: SimpleContainer,
) : AbstractContainerMenu(RefurbishedEuBridge.PARCEL_MENU.get(), containerId) {

    init {
        for (col in 0 until ParcelItem.SIZE) {
            addSlot(Slot(contents, col, 8 + col * 18, 20))
        }
        for (row in 0 until 3) {
            for (col in 0 until 9) {
                addSlot(Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, 51 + row * 18))
            }
        }
        for (col in 0 until 9) {
            addSlot(Slot(playerInventory, col, 8 + col * 18, 109))
        }
    }

    /** The parcel is held in hand, so it must not be movable while its own screen is open. */
    override fun quickMoveStack(player: Player, index: Int): ItemStack = ItemStack.EMPTY

    override fun stillValid(player: Player): Boolean = player.isAlive

    override fun removed(player: Player) {
        super.removed(player)
        ParcelItem.store(parcel, contents)
    }

    companion object {
        fun provider(stack: ItemStack, hand: net.minecraft.world.InteractionHand): MenuProvider =
            object : MenuProvider {
                override fun getDisplayName(): Component = stack.hoverName
                override fun createMenu(id: Int, inv: Inventory, player: Player): AbstractContainerMenu =
                    ParcelMenu(id, inv, stack, ParcelItem.asContainer(stack))
            }
    }
}
```

- [ ] **Step 3: Implement `ParcelScreen`**

```kotlin
package dev.vvoleman.refurbishedeu.mail

import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.player.Inventory
import com.mojang.blaze3d.vertex.PoseStack

class ParcelScreen(
    menu: ParcelMenu,
    inventory: Inventory,
    title: Component,
) : AbstractContainerScreen<ParcelMenu>(menu, inventory, title) {

    private val texture = ResourceLocation("minecraft", "textures/gui/container/hopper.png")

    init {
        imageHeight = 133
        inventoryLabelY = imageHeight - 94
    }

    override fun render(poseStack: PoseStack, mouseX: Int, mouseY: Int, partialTick: Float) {
        renderBackground(poseStack)
        super.render(poseStack, mouseX, mouseY, partialTick)
        renderTooltip(poseStack, mouseX, mouseY)
    }

    override fun renderBg(poseStack: PoseStack, partialTick: Float, mouseX: Int, mouseY: Int) {
        RenderSystem.setShader { net.minecraft.client.renderer.GameRenderer.getPositionTexShader() }
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f)
        RenderSystem.setShaderTexture(0, texture)
        blit(poseStack, leftPos, topPos, 0, 0, imageWidth, imageHeight)
    }
}
```

Reusing vanilla's hopper texture keeps this task free of a bespoke GUI sheet.
If the layout reads badly in game, a dedicated texture is a follow-up, not a
blocker.

- [ ] **Step 4: Register item, menu and screen**

In `RefurbishedEuBridge.kt`:

```kotlin
    val PARCEL: RegistryObject<Item> = ITEMS.register("parcel") {
        ParcelItem(Item.Properties().stacksTo(1).tab(CreativeModeTab.TAB_MISC))
    }

    val PARCEL_MENU: RegistryObject<MenuType<ParcelMenu>> =
        MENUS.register("parcel") {
            IForgeMenuType.create { containerId, inventory, _ ->
                val held = inventory.player.mainHandItem
                ParcelMenu(containerId, inventory, held, ParcelItem.asContainer(held))
            }
        }
```

In `ClientSetup.onClientSetup`, inside the existing `enqueueWork`:

```kotlin
            MenuScreens.register(RefurbishedEuBridge.PARCEL_MENU.get()) {
                menu, inventory, title -> ParcelScreen(menu, inventory, title)
            }
```

- [ ] **Step 5: Add model, texture, recipe and lang**

Model `models/item/parcel.json` mirrors the letter's, pointing at
`refurbished_eu:item/parcel`; texture is a 16x16 cardboard box.

Recipe `data/refurbished_eu/recipes/parcel.json`:

```json
{
  "type": "minecraft:crafting_shapeless",
  "ingredients": [
    { "item": "minecraft:paper" },
    { "item": "minecraft:paper" },
    { "item": "minecraft:paper" },
    { "item": "minecraft:paper" }
  ],
  "result": { "item": "refurbished_eu:parcel", "count": 1 }
}
```

Lang additions:

```json
  "item.refurbished_eu.parcel": "Parcel",
  "tooltip.refurbished_eu.parcel_contents": "%s/%s slots used",
  "container.refurbished_eu.parcel": "Parcel"
```

- [ ] **Step 6: Verify it compiles**

Run: `JAVA_HOME=/home/vvoleman/.jdks/jdk-17.0.20.1+1 ./gradlew build --offline`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add src/main
git commit -m "Add the Parcel item and its container screen

Compiles; not yet tested in game."
```

**In-game checklist:** craft a parcel; right-click to open; put items in; close;
reopen and confirm the items are still there; confirm the tooltip slot count
updates; name it in an anvil and confirm the address tooltip.

---

### Task 10: MailmanEntity

**Files:**
- Create: `src/main/kotlin/dev/vvoleman/refurbishedeu/mail/MailmanEntity.kt`
- Modify: `RefurbishedEuBridge.kt` (new `ENTITIES` register, attributes, spawn egg)
- Modify: `ClientSetup.kt` (renderer)
- Modify: lang

**Interfaces:**
- Consumes: nothing yet; goals arrive in Task 11.
- Produces: `MailmanEntity` with `var routeId: UUID?` and
  `var carried: ItemStack`; `RefurbishedEuBridge.MAILMAN: RegistryObject<EntityType<MailmanEntity>>`.
  Tasks 11 and 12 use both.

Not a `Villager` subclass: `Villager` drags in trading, breeding, gossip and a
Brain/POI system that would have to be dismantled first. A `PathfinderMob` with
`GroundPathNavigation` is smaller and fights nothing.

- [ ] **Step 1: Implement the entity**

```kotlin
package dev.vvoleman.refurbishedeu.mail

import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.PathfinderMob
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.ai.goal.FloatGoal
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.nbt.CompoundTag
import java.util.UUID

/**
 * The visible half of a delivery. Its lifetime belongs to a MailRoute, not to
 * the world: MailRouteService spawns one when a route becomes observable and
 * removes it when the route goes back to dead reckoning.
 */
class MailmanEntity(type: EntityType<out PathfinderMob>, level: Level) : PathfinderMob(type, level) {

    var routeId: UUID? = null
    var carried: ItemStack = ItemStack.EMPTY

    override fun registerGoals() {
        goalSelector.addGoal(0, FloatGoal(this))
        goalSelector.addGoal(9, LookAtPlayerGoal(this, Player::class.java, 6.0f))
        // Travel and delivery goals are added in Task 11.
    }

    /** Its lifecycle belongs to the route, so vanilla despawn rules must not touch it. */
    override fun removeWhenFarAway(distance: Double): Boolean = false

    /**
     * Carrying mail makes it invulnerable. A delivery must not be lost to a
     * skeleton in a chunk the sender will never visit; the route is the
     * authority on where the mail is, and a dead mailman would orphan it.
     */
    override fun hurt(source: DamageSource?, amount: Float): Boolean = false

    override fun addAdditionalSaveData(tag: CompoundTag) {
        super.addAdditionalSaveData(tag)
        routeId?.let { tag.putUUID("RouteId", it) }
        if (!carried.isEmpty) tag.put("Carried", carried.save(CompoundTag()))
    }

    override fun readAdditionalSaveData(tag: CompoundTag) {
        super.readAdditionalSaveData(tag)
        routeId = if (tag.hasUUID("RouteId")) tag.getUUID("RouteId") else null
        carried = if (tag.contains("Carried")) ItemStack.of(tag.getCompound("Carried")) else ItemStack.EMPTY
    }

    companion object {
        fun attributes(): AttributeSupplier.Builder = PathfinderMob.createMobAttributes()
            .add(Attributes.MAX_HEALTH, 20.0)
            .add(Attributes.MOVEMENT_SPEED, 0.5)
            .add(Attributes.FOLLOW_RANGE, 48.0)
    }
}
```

- [ ] **Step 2: Register the entity type, attributes and spawn egg**

In `RefurbishedEuBridge.kt`, add the register alongside the others:

```kotlin
    private val ENTITIES = DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, ID)

    val MAILMAN: RegistryObject<EntityType<MailmanEntity>> = ENTITIES.register("mailman") {
        EntityType.Builder.of(::MailmanEntity, MobCategory.MISC)
            .sized(0.6f, 1.95f)
            .clientTrackingRange(10)
            .build("mailman")
    }

    val MAILMAN_EGG: RegistryObject<Item> = ITEMS.register("mailman_spawn_egg") {
        ForgeSpawnEggItem(MAILMAN, 0x3F3F5C, 0xB08040, Item.Properties().tab(CreativeModeTab.TAB_MISC))
    }
```

Register it on the bus in `init` (`ENTITIES.register(MOD_BUS)`) and add an
attribute listener next to `commonSetup`:

```kotlin
        MOD_BUS.addListener(::onEntityAttributes)
...
    private fun onEntityAttributes(event: EntityAttributeCreationEvent) {
        event.put(MAILMAN.get(), MailmanEntity.attributes().build())
    }
```

The spawn egg is for creative and debugging only — a hand-spawned mailman has no
route and will stand still. That is expected, not a bug.

- [ ] **Step 3: Register the renderer**

In `ClientSetup.onRegisterRenderers`:

```kotlin
        event.registerEntityRenderer(RefurbishedEuBridge.MAILMAN.get()) { context ->
            net.minecraft.client.renderer.entity.VillagerRenderer(context)
        }
```

Reusing `VillagerRenderer` means no new model or texture. `MailmanEntity` is not
a `Villager`, so if the generics refuse this, fall back to
`HumanoidMobRenderer` with `ModelLayers.PLAYER` — record which one was used in
the commit message.

- [ ] **Step 4: Add lang keys**

```json
  "entity.refurbished_eu.mailman": "Mailman",
  "item.refurbished_eu.mailman_spawn_egg": "Mailman Spawn Egg"
```

- [ ] **Step 5: Verify it compiles**

Run: `JAVA_HOME=/home/vvoleman/.jdks/jdk-17.0.20.1+1 ./gradlew build --offline`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add src/main
git commit -m "Add the mailman entity

PathfinderMob rather than Villager: no trading, breeding or Brain to dismantle.
Compiles; not yet tested in game."
```

**In-game checklist:** spawn one with the egg; confirm it renders, stands still,
takes no damage, and survives a reload.

---

### Task 11: Travel and delivery goals

**Files:**
- Create: `src/main/kotlin/dev/vvoleman/refurbishedeu/mail/MailmanGoals.kt`
- Modify: `MailmanEntity.registerGoals`

**Interfaces:**
- Consumes: `MailmanEntity` (Task 10), `MailboxRef` (Task 3).
- Produces: `TravelToTargetGoal(mob: MailmanEntity, targetOf: (MailmanEntity) -> BlockPos?)`
  and `DeliverMailGoal(mob: MailmanEntity, targetOf: (MailmanEntity) -> BlockPos?)`.
  Both read the destination through a lambda, which keeps the goals free of any
  dependency on the route service. Neither delivers — MailRouteService owns
  arrival, so dead-reckoned and walked routes behave identically.

- [ ] **Step 1: Implement the goals**

```kotlin
package dev.vvoleman.refurbishedeu.mail

import net.minecraft.core.BlockPos
import net.minecraft.world.entity.ai.goal.Goal
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
    private var repathCooldown = 0

    init {
        flags = EnumSet.of(Flag.MOVE)
    }

    override fun canUse(): Boolean {
        target = targetOf(mob)
        return target != null && !mob.blockPosition().closerThan(target, ARRIVAL_RANGE)
    }

    override fun canContinueToUse(): Boolean = canUse()

    override fun start() {
        repathCooldown = 0
    }

    override fun tick() {
        val destination = target ?: return
        if (repathCooldown-- > 0) return
        repathCooldown = REPATH_INTERVAL
        mob.navigation.moveTo(
            destination.x + 0.5,
            destination.y.toDouble(),
            destination.z + 0.5,
            1.0,
        )
    }

    companion object {
        const val ARRIVAL_RANGE = 2.0
        const val REPATH_INTERVAL = 20
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
```

- [ ] **Step 2: Wire them into the entity**

`MailmanEntity` needs the two hooks the service will fill in. Add fields:

```kotlin
    /** Set by MailRouteService when the mailman is materialised. */
    var destination: BlockPos? = null
```

and replace the `registerGoals` comment with:

```kotlin
        goalSelector.addGoal(1, DeliverMailGoal(this) { it.destination })
        goalSelector.addGoal(2, TravelToTargetGoal(this) { it.destination })
```

- [ ] **Step 3: Verify it compiles**

Run: `JAVA_HOME=/home/vvoleman/.jdks/jdk-17.0.20.1+1 ./gradlew build --offline`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/main
git commit -m "Add mailman travel and delivery goals

Goals take the destination as a lambda so they know nothing about routes.
Compiles; not yet tested in game."
```

---

### Task 12: MailRouteService

**Files:**
- Create: `src/main/kotlin/dev/vvoleman/refurbishedeu/mail/MailRouteService.kt`
- Modify: `RefurbishedEuBridge.kt` (server tick hook)

**Interfaces:**
- Consumes: `MailRoute`/`RouteState` (Task 6), `Travel` (Task 5),
  `MailboxIndex`/`MailboxRef` (Tasks 3–4), `MailmanConfig` (Task 7),
  `MailmanEntity` (Task 10).
- Produces: `MailRouteService.get(level: ServerLevel): MailRouteService`,
  `MailRouteService.tick(server: MinecraftServer)`,
  `MailRouteService.add(route: MailRoute): Boolean`,
  `MailRouteService.mailboxes(): List<MailboxRef>`.
  Task 13 calls `add` and `mailboxes`.

This is the biggest task in the plan and the one to review most carefully. It
owns the materialise/simulate switch described in the spec.

- [ ] **Step 1: Implement the service**

```kotlin
package dev.vvoleman.refurbishedeu.mail

import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.MobSpawnType
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.phys.Vec3
import com.mrcrayfish.furniture.refurbished.blockentity.MailboxBlockEntity
import com.mrcrayfish.furniture.refurbished.mail.DeliveryService
import dev.vvoleman.refurbishedeu.RefurbishedEuBridge
import java.util.UUID

/**
 * Every delivery in flight, and the thing that decides whether each one is
 * currently a real entity or just arithmetic.
 */
class MailRouteService : SavedData() {

    private val routes = mutableListOf<MailRoute>()
    private var cachedMailboxes: List<MailboxRef> = emptyList()
    private var indexAge = Int.MAX_VALUE
    private var tickCounter = 0

    fun routes(): List<MailRoute> = routes
    fun mailboxes(): List<MailboxRef> = cachedMailboxes

    fun add(route: MailRoute): Boolean {
        if (routes.size >= MailmanConfig.maxActiveRoutes()) return false
        routes.add(route)
        setDirty()
        return true
    }

    /**
     * Rebuilt on an interval rather than per query: save() allocates a tag for
     * every mailbox on the server, so calling it per lookup would be wasteful.
     */
    private fun refreshIndex(server: MinecraftServer) {
        if (indexAge++ < MailmanConfig.indexRefreshTicks()) return
        indexAge = 0
        val service = DeliveryService.get(server).orElse(null) ?: return
        cachedMailboxes = MailboxIndex.parse(service.save(CompoundTag()))
    }

    fun tick(server: MinecraftServer) {
        refreshIndex(server)
        tickCounter++
        val iterator = routes.iterator()
        while (iterator.hasNext()) {
            val route = iterator.next()
            val level = server.getLevel(route.level)
            if (level == null) continue
            if (tickRoute(level, route)) {
                iterator.remove()
                setDirty()
            }
        }
    }

    /** @return true when the route is finished and should be dropped. */
    private fun tickRoute(level: ServerLevel, route: MailRoute): Boolean {
        val destination = destinationOf(route) ?: return finish(level, route)
        val destVec = Vec3(destination.x + 0.5, destination.y.toDouble(), destination.z + 0.5)

        if (isObservable(level, route.pos)) {
            materialise(level, route, destination)
        } else {
            dematerialise(level, route)
            route.pos = Travel.advance(route.pos, destVec, MailmanConfig.blocksPerSecond(), 1)
        }

        if (Travel.horizontalDistance(route.pos, destVec) <= ARRIVAL_RANGE) {
            return deliver(level, route, destination)
        }
        return checkStall(level, route, destVec)
    }

    private fun destinationOf(route: MailRoute): BlockPos? {
        val wanted = if (route.state == RouteState.RETURNING) route.originId else route.targetId
        return cachedMailboxes.firstOrNull { it.id == wanted }?.pos
    }

    /**
     * Loaded and close enough that somebody could see it. getChunkNow never
     * loads anything itself, which is the whole point - a delivery must not drag
     * chunks along behind it.
     */
    private fun isObservable(level: ServerLevel, pos: Vec3): Boolean {
        val chunkX = (pos.x.toInt()) shr 4
        val chunkZ = (pos.z.toInt()) shr 4
        if (level.chunkSource.getChunkNow(chunkX, chunkZ) == null) return false
        return level.players().any { it.distanceToSqr(pos.x, it.y, pos.z) < OBSERVE_RANGE_SQR }
    }

    private fun materialise(level: ServerLevel, route: MailRoute, destination: BlockPos) {
        val existing = route.entity?.let { level.getEntity(it) as? MailmanEntity }
        if (existing != null && existing.isAlive) {
            route.pos = existing.position()
            existing.destination = destination
            return
        }
        if (materialisedCount(level) >= MailmanConfig.maxMaterialisedMailmen()) return

        val mob = RefurbishedEuBridge.MAILMAN.get().create(level) ?: return
        // y is meaningless while dead-reckoned, so it is resolved here.
        val surface = level.getHeight(Heightmap.Types.WORLD_SURFACE, route.pos.x.toInt(), route.pos.z.toInt())
        mob.moveTo(route.pos.x, surface.toDouble(), route.pos.z, 0.0f, 0.0f)
        mob.routeId = route.id
        mob.carried = route.stack
        mob.destination = destination
        level.addFreshEntity(mob)
        route.entity = mob.uuid
        setDirty()
    }

    private fun dematerialise(level: ServerLevel, route: MailRoute) {
        val id = route.entity ?: return
        (level.getEntity(id) as? MailmanEntity)?.let {
            route.pos = it.position()
            it.discard()
        }
        route.entity = null
        setDirty()
    }

    private fun materialisedCount(level: ServerLevel): Int =
        routes.count { it.entity != null && level.getEntity(it.entity!!) != null }

    private fun deliver(level: ServerLevel, route: MailRoute, destination: BlockPos): Boolean {
        val be = level.getBlockEntity(destination) as? MailboxBlockEntity
        if (be != null && be.deliverItem(route.stack.copy())) {
            dematerialise(level, route)
            return true
        }
        // Refused - queue full, or the mailbox is gone. Take it home.
        if (route.state == RouteState.RETURNING) {
            // Origin refused it too; there is nowhere left to put it.
            dematerialise(level, route)
            return true
        }
        route.state = RouteState.RETURNING
        route.lastDistance = Double.MAX_VALUE
        route.stalledTicks = 0
        setDirty()
        return false
    }

    private fun checkStall(level: ServerLevel, route: MailRoute, destVec: Vec3): Boolean {
        val distance = Travel.horizontalDistance(route.pos, destVec)
        if (distance < route.lastDistance - STALL_EPSILON) {
            route.lastDistance = distance
            route.stalledTicks = 0
            return false
        }
        route.stalledTicks++
        if (route.stalledTicks < MailmanConfig.stallTimeoutTicks()) return false

        // No progress for long enough: open water, or a mailbox that can't be walked to.
        if (route.state == RouteState.RETURNING) return finish(level, route)
        route.state = RouteState.RETURNING
        route.lastDistance = Double.MAX_VALUE
        route.stalledTicks = 0
        setDirty()
        return false
    }

    private fun finish(level: ServerLevel, route: MailRoute): Boolean {
        dematerialise(level, route)
        return true
    }

    override fun save(tag: CompoundTag): CompoundTag {
        val list = ListTag()
        routes.forEach { list.add(it.save()) }
        tag.put("Routes", list)
        return tag
    }

    companion object {
        private const val ARRIVAL_RANGE = 2.0
        private const val OBSERVE_RANGE_SQR = 128.0 * 128.0
        private const val STALL_EPSILON = 0.05
        private const val STORAGE_ID = "refurbished_eu_mail_routes"

        fun get(level: ServerLevel): MailRouteService =
            level.server.overworld().dataStorage.computeIfAbsent(
                { tag -> load(tag) },
                { MailRouteService() },
                STORAGE_ID,
            )

        private fun load(tag: CompoundTag): MailRouteService {
            val service = MailRouteService()
            tag.getList("Routes", Tag.TAG_COMPOUND.toInt()).forEach { entry ->
                MailRoute.load(entry as CompoundTag)?.let { service.routes.add(it) }
            }
            return service
        }
    }
}
```

- [ ] **Step 2: Hook it to the server tick**

In `RefurbishedEuBridge.kt`, register a Forge-bus listener (the Forge bus, not
the mod bus — `ServerTickEvent` is a game event):

```kotlin
        FORGE_BUS.addListener<TickEvent.ServerTickEvent> { event ->
            if (event.phase == TickEvent.Phase.END) {
                val level = event.server.overworld()
                MailRouteService.get(level).tick(event.server)
            }
        }
```

- [ ] **Step 3: Verify it compiles**

Run: `JAVA_HOME=/home/vvoleman/.jdks/jdk-17.0.20.1+1 ./gradlew build --offline`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/main
git commit -m "Add the mail route service

Routes tick regardless of chunk loading; the entity is spawned only as a view
of a route somebody can see. Compiles; not yet tested in game."
```

---

### Task 13: Pickup sweep

**Files:**
- Modify: `src/main/kotlin/dev/vvoleman/refurbishedeu/mail/MailRouteService.kt`

**Interfaces:**
- Consumes: everything above.
- Produces: `MailRouteService.sweep(server: MinecraftServer)`, called from `tick`.

This closes the loop: addressed mail sitting in a mailbox becomes a route.

- [ ] **Step 1: Implement the sweep**

Add to `MailRouteService`:

```kotlin
    /**
     * Turns addressed mail sitting in a mailbox into a route.
     *
     * The stack is removed from the mailbox as the route is created, so the mail
     * is in exactly one place at every moment - never both in a container and in
     * flight.
     */
    private fun sweep(server: MinecraftServer) {
        if (tickCounter % MailmanConfig.pickupScanTicks() != 0) return
        for (origin in cachedMailboxes) {
            val level = server.getLevel(origin.level) ?: continue
            // Only mailboxes in loaded chunks are swept; an unloaded one has
            // nothing ticking to have put mail in it since the last sweep.
            if (level.chunkSource.getChunkNow(origin.pos.x shr 4, origin.pos.z shr 4) == null) continue
            val be = level.getBlockEntity(origin.pos) as? MailboxBlockEntity ?: continue
            sweepOne(level, be, origin)
        }
    }

    private fun sweepOne(level: ServerLevel, be: MailboxBlockEntity, origin: MailboxRef) {
        for (slot in 0 until be.containerSize) {
            val stack = be.getItem(slot)
            if (stack.isEmpty) continue
            if (!isOurMail(stack)) continue
            val target = addressOf(stack) ?: continue
            if (target.equals(origin.name, ignoreCase = true)) continue

            // Restrict to this dimension BEFORE resolving the name. byName picks the
            // nearest match and compares raw block positions, so a same-named mailbox in
            // another dimension could otherwise win on a meaningless coordinate distance
            // and the mail would be dropped here - even though a valid local one exists.
            val local = cachedMailboxes.filter { it.level == origin.level }
            val destination = MailboxIndex.byName(local, target, origin.pos) ?: continue
            if (destination.id == origin.id) continue

            val route = MailRoute(
                id = UUID.randomUUID(),
                stack = stack.copy(),
                originId = origin.id,
                targetId = destination.id,
                level = origin.level,
                pos = Vec3(origin.pos.x + 0.5, origin.pos.y.toDouble(), origin.pos.z + 0.5),
                state = RouteState.TRAVELLING,
            )
            if (add(route)) {
                be.setItem(slot, net.minecraft.world.item.ItemStack.EMPTY)
                be.setChanged()
            }
            return
        }
    }

    private fun isOurMail(stack: net.minecraft.world.item.ItemStack): Boolean =
        stack.item == RefurbishedEuBridge.LETTER.get() || stack.item == RefurbishedEuBridge.PARCEL.get()

    private fun addressOf(stack: net.minecraft.world.item.ItemStack): String? =
        MailAddress.target(stack) ?: LetterItem.targetFromName(stack)
```

- [ ] **Step 2: Call it from `tick`**

In `tick`, immediately after `refreshIndex(server)` and `tickCounter++`:

```kotlin
        sweep(server)
```

- [ ] **Step 3: Verify it compiles**

Run: `JAVA_HOME=/home/vvoleman/.jdks/jdk-17.0.20.1+1 ./gradlew build --offline`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/main
git commit -m "Sweep mailboxes for outgoing mail

Compiles; not yet tested in game."
```

---

### Task 14: Documentation and the full build

**Files:**
- Modify: `README.md`
- Modify: `gradle.properties` (version bump)

**Interfaces:**
- Consumes: everything.
- Produces: a jar to install in the real pack.

- [ ] **Step 1: Add a README section**

After the transformer's "Computers" section, add a `## Mail` section covering:
what Letter and Parcel are and how they are addressed (anvil rename); that mail
is picked up from any named mailbox and physically walked to the target; that
Refurbished's Post Box still does instant delivery and is untouched; the
`[mailman]` config table with all six keys and their defaults; and the four
behavioural limits from the spec — same dimension only, no open water in Phase
1, deliveries are slow by design, duplicate names resolve to nearest.

- [ ] **Step 2: Bump the version**

In `gradle.properties`, `mod_version=0.3.0`. A new feature, not a fix.

- [ ] **Step 3: Full build and test run**

Run: `JAVA_HOME=/home/vvoleman/.jdks/jdk-17.0.20.1+1 ./gradlew clean build --offline`
Expected: BUILD SUCCESSFUL, all unit tests from Tasks 1–6 passing, jar at
`build/libs/refurbished_eu-0.3.0.jar`.

- [ ] **Step 4: Commit**

```bash
git add README.md gradle.properties
git commit -m "Document the mail system and bump to 0.3.0"
```

- [ ] **Step 5: Hand off for in-game testing**

Report honestly: the build is green and the unit tests pass, which means the
logic layer is correct and everything compiles. **Nothing has been run in a
game.** The human must install `build/libs/refurbished_eu-0.3.0.jar` into the
real pack and work through this list:

1. Place two mailboxes and name them (`Home`, `Shop`).
2. Craft a letter, anvil-rename it to `Shop`, put it in `Home`.
3. Within ~10 seconds the letter leaves the mailbox and a mailman appears.
4. Watch it walk; confirm it arrives and the letter is in `Shop`.
5. Repeat with a parcel containing items; confirm contents survive.
6. Repeat over ~1000 blocks, fly away so the route is unobserved, come back;
   confirm the mail still arrives.
7. Address a letter to a name that does not exist; confirm it stays put.
8. Address one across an ocean; confirm it comes back after the stall timeout.
9. Restart the server mid-delivery; confirm the route resumes.

---

## Self-Review

**Spec coverage.** Summary → Tasks 8, 9, 12, 13. `save()` registry → Task 3.
Container access → Task 13. Scope/stall rule → Tasks 12 (`checkStall`), 7
(`stallTimeoutTicks`). MailboxIndex → Tasks 3, 4, 12 (`refreshIndex`).
MailRouteService, materialise/dematerialise, dead reckoning, caps → Tasks 5, 6,
12. MailmanEntity and goals → Tasks 10, 11. Items → Tasks 8, 9. Behavioural
limits: same-dimension enforced in Task 13's `sweep`; water stall in Task 12;
slow-by-design in Task 7; ambiguous names in Task 4. Config → Task 7.
Verification → Tasks 1 and 14. **No gaps found.**

**Known deviation from the spec, accepted:** the spec describes addressing
through the items' own UI. The plan addresses by anvil rename instead (Tasks 8,
9), which needs no screen, no packet and no client code, and is a smaller
change with the same result. The parcel still gets a container screen for its
*contents*. If in-game testing shows anvil renaming is too obscure, a dedicated
addressing screen is a follow-up.

**Placeholder scan.** No TBD/TODO. The two "if it fails, do X" branches (Task 1
Step 4, Task 10 Step 3) are deliberate contingencies with concrete fallbacks
named, not deferred decisions. Textures are described rather than drawn, which
is the one genuine hand-wave — they are 16x16 art assets and cannot be written
as code.

**Type consistency.** `MailboxRef(id, pos, level, name)` used identically in
Tasks 3, 4, 12, 13. `MailRoute` field names match between Task 6's definition
and Task 12's use (`pos`, `state`, `entity`, `lastDistance`, `stalledTicks`).
`MailAddress.target/sender/apply/isAddressed` consistent across Tasks 2, 8, 9,
13. `LetterItem.targetFromName` defined in Task 8, used in Tasks 9 and 13.
`TravelToTargetGoal.ARRIVAL_RANGE` defined in Task 11 and referenced from
`DeliverMailGoal` in the same file; `MailRouteService` has its own private
`ARRIVAL_RANGE` for the dead-reckoned check, which is a separate concern —
intentional, not a collision.
