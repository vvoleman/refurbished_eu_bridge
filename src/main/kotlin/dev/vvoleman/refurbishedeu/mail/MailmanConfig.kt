package dev.vvoleman.refurbishedeu.mail

import net.minecraftforge.common.ForgeConfigSpec

/**
 * Mailman balance settings, in their own server config file -
 * `refurbished_eu-mailman-server.toml` - separate from the transformer's.
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
    val useBoats: ForgeConfigSpec.BooleanValue
    val minWaterCrossingWidth: ForgeConfigSpec.IntValue
    val boatCrossingTimeoutTicks: ForgeConfigSpec.IntValue
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
    fun useBoats(): Boolean = read(useBoats, true)
    fun minWaterCrossingWidth(): Int = read(minWaterCrossingWidth, 6)
    fun boatCrossingTimeoutTicks(): Int = read(boatCrossingTimeoutTicks, 600)
}
