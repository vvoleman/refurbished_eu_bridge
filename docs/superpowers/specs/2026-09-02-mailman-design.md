# Mailman — design

Issue: [#4](https://github.com/vvoleman/refurbished_eu_bridge/issues/4)
Date: 2026-09-02
Status: approved, Phase 1

## Summary

Add a physical postal service on top of MrCrayfish's Furniture Refurbished. A
player addresses a Letter or a Parcel to a named mailbox and leaves it in any
mailbox. A mailman walks it there. Delivery takes real time proportional to
real distance, and works between any two mailboxes in the same dimension —
including ones thousands of blocks apart, in chunks nobody has loaded.

Refurbished's own Post Box is untouched. It stays instant express mail; ours is
the slow path that you can watch happen.

## What Refurbished already provides

Read out of the 1.0.9 jar with `javap`; there are no sources.

```
DeliveryService extends SavedData          // per-server, persisted
  static get(MinecraftServer) -> Optional<DeliveryService>
  getMailboxAtPosition(Level, BlockPos)    -> Optional<Mailbox>
  sendMail(UUID target, ItemStack)         -> DeliveryResult   // instant
  save(CompoundTag)                        -> CompoundTag      // m_7176_, public
  static isDeliverableDimension(Level)     -> boolean

Mailbox implements IMailbox                // record
  id() levelKey() pos() customName() owner() queue()

MailboxBlockEntity extends RowedStorageBlockEntity
  getMailbox()               -> Mailbox
  deliverItem(ItemStack)     -> boolean
```

Two consequences shaped this design.

**Mailboxes are real containers.** `MailboxBlockEntity` extends
`RowedStorageBlockEntity`, so a mailman can take stacks out of one and
`deliverItem` into another through ordinary `Container` access. No mixin is
needed anywhere in this feature, which matters: the README already documents
mixins against these mods as a live hazard, and IC2 Classic's refmap already
makes `runClient` unusable.

**A global mailbox registry is reachable through public API.**
`DeliveryService.mailboxes` is private, and the only public enumeration —
`encodeMailboxes`/`decodeMailboxes` — downgrades to `IMailbox`, which carries
`getId`/`getOwner`/`getCustomName` but *no position*, so it cannot be walked to.
`save()` is public and writes every mailbox in the world with its position:

```
Mailboxes: ListTag[ CompoundTag {
    UUID:          UUID
    Level:         String     // dimension key, e.g. "minecraft:overworld"
    BlockPosition: Long       // BlockPos.asLong()
    CustomName:    String     // absent when never named
    Owner:         UUID       // absent when unowned
} ]
```

Calling it on a scratch tag only reads the map and fills the tag; it mutates
nothing. That is the registry, obtained without reflection.

## Scope

**Phase 1 — this spec.** Acceptance criteria 1–5: mailbox discovery, two items,
the custom entity, walking, and mailbox-to-mailbox delivery.

**Phase 2 — separate spec.** Acceptance criterion 6: boat traversal over water.

Phase 1 ships a mailman that walks. A route that turns out to need open water
cannot be detected when the letter is addressed — knowing it would mean
pathfinding through chunks nobody has loaded, which is exactly the cost this
design exists to avoid. It is detected by *failing to make progress* instead:
a route whose horizontal distance to target has not decreased for
`stallTimeoutTicks` flips to `RETURNING`, carries the mail back to its origin
mailbox, and the sender is told it was undeliverable. Phase 2 turns that stall
into a boat leg.

The same rule covers every other way a walk can be impossible — a mailbox walled
into bedrock, a route across a ravine — so it is not scaffolding for Phase 2, it
is the permanent failure path.

## Architecture

Three units, each independently testable.

### MailboxIndex

Owns "what mailboxes exist and what are they called". Wraps the `save()` read
above behind `byName(String): MailboxRef?` and `all(): List<MailboxRef>`, where

```kotlin
data class MailboxRef(
    val id: UUID,
    val pos: BlockPos,
    val level: ResourceKey<Level>,
    val name: String?,
)
```

Rebuilt on an interval (`indexRefreshTicks`, default 600 = 30s) rather than per
query, because `save()` allocates a tag for every mailbox on the server. Nothing
else in the mod touches `DeliveryService` for enumeration.

Names are not unique — Refurbished does not enforce it. `byName` resolves to the
nearest match to the origin and the addressing UI reports when a name was
ambiguous.

### MailRouteService

A `SavedData` of ours, `refurbished_eu_mail_routes`. Owns every delivery in
flight. This is the piece that makes distance work.

```kotlin
data class MailRoute(
    val id: UUID,
    val stack: ItemStack,          // the letter or parcel, in flight
    val originId: UUID,            // mailbox UUIDs, resolved through MailboxIndex
    val targetId: UUID,
    val level: ResourceKey<Level>,
    var pos: Vec3,                 // where the mailman currently is
    var state: State,              // TRAVELLING, DELIVERING, RETURNING
    var entity: UUID?,             // set only while materialised
)
```

An entity cannot walk through unloaded chunks — there is nothing ticking out
there — so the route is the durable thing and the entity is a *view* of it:

- **Observable**: the route's `pos` is in a loaded chunk within player entity
  tracking range. A `MailmanEntity` is spawned there, drives itself with real
  pathfinding, and its position is written back to the route each tick.
- **Unobservable**: the entity despawns and the route advances by dead
  reckoning toward the target at `blocksPerSecond` (default 4).

Dead reckoning tracks **horizontal position only**. Resolving a ground height
out there would mean reading chunks that are not loaded, and forcing them in
would defeat the entire point of simulating — a single long delivery would drag
a corridor of chunks through the server. `pos.y` is therefore meaningless while
unobserved and is resolved at materialisation time by snapping to the surface at
the route's `x`/`z`, using `getChunkNow` so the check never loads anything
itself.

A player near a delivery therefore sees a real mailman walking real terrain; a
delivery nobody is watching still completes. The carried stack lives in the
route, so it survives chunk unload, dimension change and server restart —
it is never dropped on the floor of an unloaded chunk.

Routes are created by a periodic sweep (`pickupScanTicks`) over indexed
mailboxes holding addressed mail. The stack is removed from the mailbox at that
moment, so it is in exactly one place at all times. On arrival the mailman calls
`MailboxBlockEntity.deliverItem`; if that fails — queue full, mailbox broken
while in flight — the route flips to `RETURNING` and carries the stack back to
its origin.

`maxActiveRoutes` (default 32) caps concurrent deliveries; beyond it, mail waits
in its mailbox. `maxMaterialisedMailmen` (default 8) caps how many are real
entities at once, so forty deliveries near spawn cannot become forty
pathfinding mobs.

### MailmanEntity

`MailmanEntity : PathfinderMob`. Deliberately **not** a `Villager` subclass:
`Villager` brings trading, breeding, gossip, and a Brain/POI system that would
have to be dismantled before any of this works. A plain `PathfinderMob` with
`GroundPathNavigation` and four goals is smaller and does not fight the base
game.

| Goal | Behaviour |
|---|---|
| `FloatGoal` | vanilla; keeps it from drowning in a puddle |
| `TravelToTargetGoal` | walk toward the route's target mailbox |
| `DeliverMailGoal` | on arrival, `deliverItem`; on refusal, flip to `RETURNING` |
| `LookAtPlayerGoal` | vanilla; cosmetic |

It renders on the vanilla villager model with a mailbag layer — no new rig. It
is passive, does not despawn naturally (its lifecycle belongs to the route), and
takes no damage from the world while carrying mail, so a delivery cannot be lost
to a stray skeleton in a chunk the player never sees.

## Items

Two items, ours end to end, sharing one `MailAddress` NBT helper:

```
{ Target: "<mailbox name>", Sender: "<player uuid>" }
```

- **Letter** — text mail. Right-click to write and to read. Addressed to a
  mailbox name. Stacks to 1 once written.
- **Parcel** — box mail. Opens a small container screen, holds items, addressed
  the same way.

Neither touches Refurbished's `PackageItem`. Both are inert without a `Target`;
an unaddressed letter left in a mailbox is ignored by the sweep rather than
picked up and carried nowhere.

## Behavioural limits

Stated here because players will notice them and they are design decisions, not
bugs.

1. **Same dimension only.** Walking between dimensions is meaningless. Cross-
   dimension addressing is refused at write time with a message pointing at the
   Post Box, which still does instant cross-dimension delivery. Refurbished's
   own `isDeliverableDimension` allow-list is honoured on top of that.
2. **No open water in Phase 1.** A route that cannot walk to its target stalls,
   returns the mail to its origin and tells the sender. Phase 2 turns a water
   stall into a boat leg.
3. **Delivery is slow by design.** At the default 4 blocks/second a 5,000-block
   delivery takes about 20 minutes. `blocksPerSecond` is configurable.
4. **Ambiguous mailbox names resolve to the nearest.** Refurbished permits
   duplicate names; we report the ambiguity rather than refusing.

## Configuration

Server config, alongside the existing `[transformer]` block in
`refurbished_eu-server.toml`:

```toml
[mailman]
    blocksPerSecond = 4          # simulated travel speed while unobserved
    maxActiveRoutes = 32         # concurrent deliveries server-wide
    maxMaterialisedMailmen = 8   # concurrent real entities
    indexRefreshTicks = 600      # mailbox index rebuild interval
    pickupScanTicks = 200        # outgoing-mail sweep interval
    stallTimeoutTicks = 1200     # no progress for this long => undeliverable
```

## Files

New, under `dev.vvoleman.refurbishedeu.mail`:

| File | Purpose |
|---|---|
| `MailboxIndex.kt` | global mailbox registry over `DeliveryService.save()` |
| `MailRouteService.kt` | `SavedData`; routes, ticking, materialisation |
| `MailRoute.kt` | the route record and its NBT |
| `MailmanEntity.kt` | the entity |
| `MailmanGoals.kt` | travel and deliver goals |
| `MailAddress.kt` | shared addressing NBT |
| `LetterItem.kt` / `ParcelItem.kt` | the two items |
| `ParcelMenu.kt` / `ParcelScreen.kt` | parcel container UI |
| `MailmanConfig.kt` | the config block above |

Modified: `RefurbishedEuBridge.kt` gains an `ENTITIES` register and reuses the
existing `ITEMS` and `MENUS`; `ClientSetup.kt` gains the entity renderer and the
parcel screen. Plus models, textures, lang keys and recipes.

## Verification

`runClient` and `runServer` crash before our code runs — IC2 Classic's mixin
refmap hardcodes SRG field names that do not exist in a Mojmap dev runtime. This
is documented in the README and is not fixable from here.

So there is no automated test for this feature and no dev world to try it in.
Verification is:

1. `JAVA_HOME=/home/vvoleman/.jdks/jdk-17.0.20.1+1 ./gradlew build --offline` —
   proves it compiles, nothing more.
2. Install `build/libs/refurbished_eu-<version>.jar` into the real pack and
   exercise it in game: name two mailboxes, address a letter, watch a mailman
   walk it; then repeat across a distance great enough that the route spends
   time unobserved, and confirm it still arrives.

A green build must not be reported as a working feature.
