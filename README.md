# Refurbished EU Bridge

A Forge 1.19.2 mod that lets an **IC2 Classic** EU grid power **MrCrayfish's
Furniture Refurbished** electricity networks. Built for
[Tekkit Classic Reloaded](https://www.curseforge.com/minecraft/modpacks/tekkit-classic-reloaded).

Refurbished's own generators burn fuel whether or not anything is switched on.
The transformer doesn't: it energises the network whenever its buffer is above
the standby cost, but only *bills* EU for appliances actually doing work.

---

## What it adds

Three transformer blocks, in a crafting progression (each one feeds into the
next). All values are configurable — see [Configuration](#configuration).

| Block | Devices | Buffer | IC2 input |
|---|---|---|---|
| Low EU Transformer | 8 | 10,000 EU | LV — 32 EU/t |
| Medium EU Transformer | 16 | 20,000 EU | MV — 128 EU/t |
| High EU Transformer | 32 | 32,767 EU | HV — 512 EU/t |

> **Feeding a transformer above its tier will make IC2 explode it.** Step the
> voltage down first, or use a higher tier.

The device cap counts every node on the network, not just the ones that draw
power — a lightswitch takes a slot the same as a lamp does. That is Refurbished's
own accounting; the counter matches it so the displayed maximum is a number you
can actually reach without overloading.

Each transformer has a GUI showing buffer, connected and active device counts,
current draw and accepted voltage tier. You can give it a name, which appears
everywhere the transformer is listed.

Looking at a transformer also puts a label under the crosshair, styled after
Refurbished's own "Missing power" indicator: a status bolt — green running, grey
off, red overloaded — then the circuit name and its device load, `3/8`.

An overloaded transformer announces itself without being looked at, too: the hum
drops to a slow grinding motor and it smokes and sparks from the top face. Both
stop the moment the network is back inside its limit, or the transformer is
switched off.

### Control

A transformer is in one of two control modes, cycled from a button in its GUI:

- **Manual** — on/off by hand, from the GUI or from a computer.
- **Redstone** — follows the block's redstone signal. Powered means on.

Under redstone control the world owns the state, so *every* other way of
changing it refuses: the GUI button greys out, Refurbished's Home Control
toggles do nothing, and the Lua setters return `false`. Switching the mode
itself is never blocked — that's how a computer hands control back to itself.

### Computers

Transformers show up in **Refurbished's Computer → Home Control** app alongside
lamps and other appliances, with their name and an on/off toggle.

They are also **CC: Tweaked peripherals** of type `eu_transformer`:

```lua
local t = peripheral.find("eu_transformer")

print(t.getName(), t.getStoredEu() .. "/" .. t.getBufferCapacity())
print(t.getActiveDevices() .. " of " .. t.getConnectedDevices() .. " active")

if not t.setEnabled(false) then
  print("refused - it's under redstone control")
end
```

| Method | Returns |
|---|---|
| `getName()` | name, or `nil` if never named |
| `setName(name)` | — (empty string clears it) |
| `getTierName()` | `"low"`, `"medium"` or `"high"` |
| `getTier()` | IC2 voltage tier: `1` = LV, `2` = MV, `3` = HV |
| `isEnabled()` | boolean |
| `setEnabled(bool)` | `false` if refused (redstone mode) |
| `toggle()` | `false` if refused (redstone mode) |
| `getControlMode()` | `"manual"` or `"redstone"` |
| `setControlMode(mode)` | — (errors on an unknown mode) |
| `getStoredEu()` | number |
| `getBufferCapacity()` | number |
| `getConnectedDevices()` | everything wired up, reachable or not |
| `getActiveDevices()` | the subset currently doing work |
| `getMaxDevices()` | cap before the transformer overloads |
| `getDraw()` | EU per tick at the current load |
| `isOverloaded()` | boolean |
| `getStatus()` | all of the above in one table |

CC: Tweaked is optional. Without it the peripheral is simply never registered.

---

## Mail

A second, slower delivery path alongside Refurbished's own Post Box, built on
two new items and a walking courier.

**Letter** and **Parcel** are addressed by **naming the stack in an anvil** —
there is no addressing screen for either item. Whatever you rename the stack
to is the target mailbox's name. A Parcel also has its own screen (right-click
it), but that screen is for its nine slots of *contents*, not its address —
name it in an anvil the same as a Letter.

Drop an addressed Letter or Parcel into **any mailbox** — the mailbox it's
posted from does not itself need a name — and it is picked up automatically,
without needing to open the mailbox's own UI. It is the **destination** that
must be named: the mail is then carried — physically, over real time, at real
distance — to whichever mailbox matches that name, by a **Mailman** entity
that spawns near the mail and walks it there. While no player is nearby to
watch it walk, the delivery still advances by dead reckoning (see
`blocksPerSecond` below) so it doesn't stall just because nobody's looking; it
only materialises into an actual entity once a player is close enough to see
one.

**Refurbished's own Post Box is untouched.** It still delivers instantly, by
UUID, through its own UI — that path is unchanged and unaffected by any of
this. Mail and Post Box packages are two independent systems that happen to
share the mailbox block; this one is the deliberately slow one.

### Behavioural limits

- **Same dimension only.** A route can't cross dimensions; Refurbished's
  mailboxes are matched by name only within the origin's own dimension.
- **A route that can't reach its target stalls, then returns to sender.** If a
  delivery makes no progress for long enough (open water, an unreachable
  mailbox, whatever the obstruction), it gives up and carries the mail back to
  the mailbox it was posted from. It is **not** automatically retried — the
  returned stack keeps its old address and is ignored by future sweeps until
  you re-address it, which an anvil rename is the *only* way to do (editing a
  Parcel's contents does not touch its address). If the return trip *also*
  fails — the origin mailbox destroyed or unreachable in the meantime — the
  mail is lost rather than sitting anywhere; this needs the origin itself to
  go bad, so it's unlikely, but it can happen.
- **Delivery is slow by design.** This is the point of the feature, not a
  bug — see `blocksPerSecond` below; a several-thousand-block trip is meant to
  take real minutes.
- **Duplicate mailbox names resolve to the nearest one.** Refurbished allows
  two mailboxes with the same name; if that happens, mail addressed to that
  name goes to whichever one is closer to the mailbox it was posted from.

### The `[mailman]` config block

Mailman settings live in their **own** server config file, separate from the
transformer's — `<world>/serverconfig/refurbished_eu-mailman-server.toml`:

```toml
[mailman]
    blocksPerSecond = 4.0        # dead-reckoning speed while nobody is watching
    maxActiveRoutes = 32         # concurrent deliveries server-wide
    maxMaterialisedMailmen = 8   # real mailman entities that may exist at once
    indexRefreshTicks = 600      # how often the mailbox index is rebuilt
    pickupScanTicks = 200        # how often mailboxes are swept for outgoing mail
    stallTimeoutTicks = 1200     # ticks without progress before a route gives up
```

`blocksPerSecond` only governs dead reckoning — a materialised mailman walks
at its own entity speed instead. At the default of 4, a 5000-block delivery
takes roughly 20 minutes.

---

## Configuration

The config is a **server** config, so it lives per-world at
`<world>/serverconfig/refurbished_eu-server.toml` and is created on first world
load. On a dedicated server that's `<server>/world/serverconfig/`.

It syncs to clients, and you can edit it without rebuilding the jar.

```toml
[transformer]
    standbyEuPerTick = 1        # drawn while energised but idle; 0 makes idle free
    euPerActiveAppliance = 4    # extra EU/t per appliance actually working
    loadCheckIntervalTicks = 10 # how often to rescan the network

    [transformer.low]
        maxPoweredDevices = 8
        bufferEu = 10000
        sinkTier = 1
```

`bufferEu` is capped at **32767**. The GUI syncs it through vanilla
`ContainerData`, which transmits shorts, so anything larger would wrap in the
readout.

---

## Building

Requires **JDK 17** (Temurin 17.0.20.1+1 is what this was developed against).

### 1. Point the build at your JDK

`gradle.properties` has a hardcoded `org.gradle.java.home`. Change it to your
own path:

```properties
org.gradle.java.home=/home/you/.jdks/jdk-17.0.20.1+1
```

That setting only applies *after* the Gradle JVM starts, so the wrapper still
needs `JAVA_HOME` set in your environment to launch at all:

```bash
export JAVA_HOME=~/.jdks/jdk-17.0.20.1+1
```

Without it you get `ERROR: JAVA_HOME is not set and no 'java' command could be
found in your PATH`.

### 2. Populate the local Maven repository

The mods this bridges aren't on any public Maven, so the build reads them from a
local repository at `localmaven/`, which is **gitignored** — you have to
populate it yourself from the pack's jars.

`fg.deobf()` needs a real group/artifact/version layout to remap into.
`files(...)` is silently ignored ("Cannot deobfuscate dependency of type
DefaultSelfResolvingDependency") and `flatDir` can't serve the remapped artifact
back, so the paths below have to be exact.

| From the pack | Goes to |
|---|---|
| `refurbished_furniture-forge-1.19.2-1.0.9.jar` | `localmaven/packmods/refurbished_furniture/1.0.9/refurbished_furniture-1.0.9.jar` |
| `framework-forge-1.19.2-0.7.12.jar` | `localmaven/packmods/framework/0.7.12/framework-0.7.12.jar` |
| `IC2Classic-1.19.2-2.1.2.1.jar` | `localmaven/packmods/ic2classic/2.1.2.1/ic2classic-2.1.2.1.jar` |
| `cc-tweaked-1.19.2-1.101.4.jar` | `localmaven/packmods/cctweaked/1.101.4/cctweaked-1.101.4.jar` |

With the pack's mods in `original_mods/`:

```bash
set -e
m=localmaven/packmods
mkdir -p $m/{refurbished_furniture/1.0.9,framework/0.7.12,ic2classic/2.1.2.1,cctweaked/1.101.4}
cp original_mods/refurbished_furniture-forge-1.19.2-1.0.9.jar $m/refurbished_furniture/1.0.9/refurbished_furniture-1.0.9.jar
cp original_mods/framework-forge-1.19.2-0.7.12.jar           $m/framework/0.7.12/framework-0.7.12.jar
cp original_mods/IC2Classic-1.19.2-2.1.2.1.jar               $m/ic2classic/2.1.2.1/ic2classic-2.1.2.1.jar
cp original_mods/cc-tweaked-1.19.2-1.101.4.jar               $m/cctweaked/1.101.4/cctweaked-1.101.4.jar
```

If a version here ever changes, update `build.gradle` to match — the coordinates
are written out there.

### 3. Build

```bash
./gradlew build
```

The jar lands in `build/libs/refurbished_eu-<version>.jar`. It is reobfuscated
for production, so drop it straight into the pack's `mods/` folder.

All four dependencies are `compileOnly`: compiled against, never bundled.

---

## Testing: dev runs do not work

`runClient` and `runServer` crash on startup. This is not fixable from here.

IC2 Classic's mixin refmap (`mixins.ic2.refmap.json`) ships only a `searge`
mapping set, and its `server.BlockMixin` `@Accessor` hardcodes the SRG field
name `f_60439_`. That field doesn't exist in a Mojmap dev runtime, where it is
`BlockBehaviour.properties`, so mixin application fails with *"No candidates
were found matching f_60439_"* before any of our code runs.

The three pack mods are therefore deliberately kept off the dev runtime
classpath, which leaves nothing to run in dev.

There are, however, JUnit tests covering the pure-logic layer (mailbox
resolution, route state, distance/timing math) that need none of that
classpath. Run them with:

```bash
./gradlew test
```

A green build and green tests mean the logic they cover is correct and
everything compiles — they are not a substitute for running the game. Nothing
in this mod has ever been run in an actual client or server; the game itself
still cannot be started in dev for the reason above.

**Exercise the game by building the jar and installing it into the real
pack**, where SRG names are correct.

---

## Notes for future work

**Kotlin nullability is a live hazard here.** When overriding a Java method,
Kotlin turns platform types into non-null parameters and emits an intrinsic null
check — which crashes if the caller ever passes null. This has already bitten
once: Refurbished stores audio in a fastutil custom-hash map whose
`Strategy.equals` compares candidates against `null`, and a non-null
`isAudioEqual(other: ILevelAudio)` took down the client tick. Mark such
parameters nullable. `TransformerPeripheral.equals(IPeripheral?)` is nullable
for the same reason.

**`mods.toml` runs through Gradle's `expand`.** A literal `${...}` anywhere in
that file — including in a comment — will be substituted or will fail the build.

**IC2 Classic's version string is Minecraft-prefixed** (`1.19.2-2.1.2.1`), so a
dependency range like `[2.1.2,)` never matches: `1.19.2-…` sorts below `2.1.2`.
The range in `mods.toml` is `[1.19.2,)` for that reason.
