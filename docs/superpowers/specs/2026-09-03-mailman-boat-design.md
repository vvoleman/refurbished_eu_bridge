# Mailman boats — design

Phase 2 of [#4](https://github.com/vvoleman/refurbished_eu_bridge/issues/4)
Date: 2026-09-03
Status: proposed

## Summary

Let a materialised mailman cross open water by boat. This is the Phase 2 named
in the Phase 1 spec's behavioural limits, which promised to "turn a water stall
into a boat leg".

The mailman walks to a shore, a boat appears under it, it steers across, steps
off on the far side and walks on. When anything about that fails, the boat is
discarded and the route continues exactly as it does today.

## What this actually fixes

Water behaves in two different ways today, and only one of them is cosmetic.

`BlockPathTypes.WATER` carries a malus of `8.0F` — positive, so water is
*passable but eight times as expensive as land*. For a stream or a pond the
pathfinder simply swims it, `FloatGoal` at priority 0 keeps the mailman's head
above water, and the crossing works. It only looks undignified.

For genuinely open water the malus makes the crossing lose to every alternative
and the A* budget runs out before reaching the far shore. The route makes no
progress, `checkStall` fires, and the mail is returned to its sender as
undeliverable. That is the Phase 1 limit as written.

So boats buy two different things: dignity on the narrow crossings, and
**deliveries that currently fail outright** on the wide ones.

One thing boats explicitly do *not* affect: a route more than 128 blocks from
any player is dematerialised and advances by `Travel.advance`, straight-line
arithmetic that ignores terrain entirely. Unobserved routes already cross
oceans. Boats matter only inside the observable window.

## What vanilla provides

Read from the 1.19.2 decompiled sources.

```
Boat.getControllingPassenger()  -> getFirstPassenger()      // no Player check
Entity.isControlledByLocalInstance()
    -> passenger instanceof Player ? player.isLocalPlayer()
                                   : !level.isClientSide    // TRUE on server
Boat.tick():
    if (isControlledByLocalInstance()) {
        floatBoat();                                        // buoyancy, friction
        if (level.isClientSide) { controlBoat(); ... }      // steering: CLIENT ONLY
        move(MoverType.SELF, getDeltaMovement());           // runs on server
    }
Boat.getMaxPassengers()         -> 2                        // protected
```

Three consequences shaped this design.

**A mob is a legitimate boat pilot.** `getControllingPassenger` has no `Player`
requirement, and for a non-Player passenger `isControlledByLocalInstance()` is
true on the server. So with the mailman aboard, the server runs the boat's
flotation and integrates its delta movement, and the result syncs to clients
for free. No packets, no client code.

**Vanilla supplies no steering.** `controlBoat()` — the method turning key
presses into yaw and delta movement — is inside `if (level.isClientSide)`, fed
by `LocalPlayer` input. There is no server-side equivalent, which is why no
vanilla mob has ever piloted a boat. We write that part: set `yRot` and
`setDeltaMovement` each tick. It is the only genuinely missing piece.

**A mob-piloted boat scoops up bystanders.** At `Boat.tick():311`, with a
non-Player pilot the boat auto-boards any colliding `LivingEntity` while
`passengers.size() < getMaxPassengers()`. Left alone, mail boats collect cows.

## Scope

In scope: shore detection, boat spawn and despawn, boarding, straight-line
steering, disembarking, and the failure paths.

Out of scope: real water pathfinding (the steering interface exists so it can be
added later without disturbing anything else); boats as items or as a resource
cost; rivers as navigable routes; any change to dead reckoning.

## Architecture

### MailBoatEntity

A registered `Boat` subclass rather than a plain boat. Three problems collapse
into one-line overrides, each mirroring a decision `MailmanEntity` already made
and documents.

| Override | Why |
|---|---|
| `shouldBeSaved() = false` | Kills orphaned boats at the root. The mailman already does this for exactly this reason: an entity whose lifetime belongs to a route must never be written into a chunk independently of that route. Without it, a chunk unload mid-crossing leaves a boat on the lake forever. |
| `getMaxPassengers() = 1` | One seat means `passengers.size() < getMaxPassengers()` is already false with the mailman aboard, so the auto-board branch never runs and no bystander is collected. |
| `tick()` self-discard | If no `MailmanEntity` is riding, nothing will ever clean this boat up, so it discards itself — the same belt-and-braces sweep `MailmanEntity.tick()` performs, pointing the other way. |

The renderer needs no new class. Forge's
`registerEntityRenderer(EntityType<? extends T>, EntityRendererProvider<T>)`
infers `T = Boat`, so `::BoatRenderer` binds directly to
`EntityType<MailBoatEntity>`. The fixed-generics wall that forced a custom
renderer for the mailman does not apply here.

### BoatPilot

The seam that keeps straight-line steering replaceable.

```kotlin
data class Steering(val yaw: Float, val forward: Double)

interface BoatPilot {
    /** @return how to steer this tick, or null when the target is reached. */
    fun steer(position: Vec3, yaw: Float, target: Vec3): Steering?
}
```

`DirectBoatPilot` implements it by turning toward the target and applying a
forward impulse, correcting each tick. It takes plain values rather than a
`Boat`, so it is pure math and unit-testable without a `Level` — the same shape
as `Travel` and `RepathPlanner`.

`UseBoatGoal` applies the result with `setYRot` / `setDeltaMovement` and lets
vanilla's `floatBoat` and `move` do the rest. Replacing straight-line steering
with real pathfinding later means one new `BoatPilot` and no change to
boarding, lifecycle or disembark.

### WaterCrossing

Decides whether a crossing is worth staging, and where it starts and ends.
Given the mailman's position and the route destination it answers: is there
water ahead wide enough to bother with, where is the embark point on this
shore, and where is the landing point on the far one.

Block lookups enter through a sampler function passed in by the caller rather
than a `Level` held as state, so the geometry is testable against a fake world.
Crossings narrower than a configured minimum are refused outright — staging a
boat launch over a two-block stream would look far worse than wading it.

### UseBoatGoal

Priority 2, with `TravelToTargetGoal` demoted from 2 to 3 so the boat goal wins
the `MOVE` flag whenever a crossing is on. Both declare `Flag.MOVE`, so the
`GoalSelector` runs exactly one of them.

`DeliverMailGoal` at priority 1 declares no flags at all, so it reserves
nothing and cannot arbitrate against either. That is pre-existing and harmless
today only because its `canUse()` range is mutually exclusive with
`TravelToTargetGoal`'s. It is not mutually exclusive with a crossing in
progress, so `UseBoatGoal` must not assume it holds `MOVE` uncontested: it
disembarks and discards before the mailman is ever inside delivery range.

Four phases:

1. **Seek shore** — walk to the embark point using the existing navigation.
2. **Embark** — spawn a `MailBoatEntity` on the water, `startRiding` it.
3. **Cross** — hand `BoatPilot` the landing point each tick and apply its
   steering.
4. **Disembark** — `stopRiding` at the far shore and discard the boat.

Every failure takes one path: discard the boat, drop back to
`TravelToTargetGoal`, let the mailman swim as it does today. Failures include a
crossing exceeding `boatCrossingTimeoutTicks`, a boat that stops making
progress, the mailman being dismounted by anything, and the route being
dematerialised.

### Interaction with MailRouteService

Only one change is needed: `dematerialise` must discard the mailman's vehicle as
well as the mailman. Everything else already falls out correctly.

- **Route position.** The mailman rides the boat, so its position follows the
  boat and `materialise`'s existing `route.pos = existing.position()` tracks the
  crossing with no new code.
- **Stall detection.** A wedged boat stops advancing `route.pos`, so the
  existing `checkStall` catches it, with `MATERIALISED_STALL_MULTIPLIER` already
  granting a driven route four times the leash.
- **Re-materialising mid-lake.** `route.yTrustworthy` is false after dead
  reckoning, so `materialise` snaps to `WORLD_SURFACE` — the water surface.
  `FloatGoal` holds the mailman up and `UseBoatGoal` boards a fresh boat.

## Behavioural limits

1. **Boats appear from nowhere.** A player watching a shore sees a boat
   materialise. This follows the mailman's own materialisation and costs no
   resources; making boats real items was considered and rejected as a much
   larger feature.
2. **Straight-line crossings only.** Winding rivers, inlets and islands in the
   path are not solved. The boat gets stuck, times out, is discarded, and the
   mailman swims — no worse than today. Real pathfinding is a later
   `BoatPilot`.
3. **No passengers.** The one-seat limit that stops cow-collection also means
   the mailman cannot ferry anything.
4. **Unobserved routes are unaffected.** Dead reckoning already ignores water.

## Configuration

Added to the existing `[mailman]` block:

```toml
useBoats = true              # false restores Phase 1 swim-or-stall behaviour
minWaterCrossingWidth = 6    # blocks; narrower water is waded, not boated
boatCrossingTimeoutTicks = 600  # 30s; a crossing longer than this is abandoned
```

The timeout is deliberately shorter than `stallTimeoutTicks` (1200) so a stuck
boat is abandoned and swum around while the route still has budget left, rather
than the route being returned as undeliverable with a boat still under it.

## Files

New, under `dev.vvoleman.refurbishedeu.mail`:

| File | Purpose |
|---|---|
| `MailBoatEntity.kt` | the route-owned boat |
| `BoatPilot.kt` | steering interface and `DirectBoatPilot` |
| `WaterCrossing.kt` | crossing decision, embark and landing geometry |

Modified: `MailmanGoals.kt` gains `UseBoatGoal` and demotes
`TravelToTargetGoal` to priority 3; `MailmanEntity.kt` registers it;
`RefurbishedEuBridge.kt` registers the boat entity type;
`ClientSetup.kt` registers `::BoatRenderer`; `MailRouteService.kt`
discards the vehicle in `dematerialise`; `MailmanConfig.kt` gains the two
settings above.

## Verification

Unit-testable, and it should be tested:

- `DirectBoatPilot` steering math — turns toward the target, reports arrival,
  behaves at the yaw wraparound.
- `WaterCrossing` geometry against a fake sampler — finds the embark and
  landing points, refuses crossings under the minimum width, refuses when there
  is no far shore.

Not testable here: boat spawning, riding, the goal state machine and the
lifecycle interlock all need a live `Level`. `runClient` still cannot start
(IC2 Classic's mixin refmap, documented in the README), so **no in-water
behaviour is verified by this repo at all.**

In-game verification is therefore required before this is called done:

1. A narrow crossing under `minWaterCrossingWidth` is waded, not boated.
2. A wide crossing boards, crosses, disembarks, and the boat disappears.
3. Walking away mid-crossing and returning leaves no boat behind on the water.
4. A delivery across water that previously returned as undeliverable arrives.

This feature is more sensitive to in-game tuning than anything before it —
crossing speed and landing accuracy are guesses until seen. A green build must
not be reported as a working feature.
