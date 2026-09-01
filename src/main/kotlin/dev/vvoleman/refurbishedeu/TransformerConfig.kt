package dev.vvoleman.refurbishedeu

import net.minecraftforge.common.ForgeConfigSpec
import java.util.EnumMap

/**
 * Server-side balance config, written to
 * `<world>/serverconfig/refurbished_eu-server.toml`.
 *
 * Registered as SERVER rather than COMMON so the values are per-world and
 * server-authoritative; Forge syncs them to connected clients automatically.
 *
 * Balance reference: Refurbished's own generator turns one fuel item into
 * `burnTime * fuelToPowerRatio` energy and spends it at 1/tick, so a single coal
 * (1600 burn ticks, default ratio 4) buys 6400 ticks of uptime.
 */
object TransformerConfig {

    class Tier(
        val maxPoweredDevices: ForgeConfigSpec.IntValue,
        val bufferEu: ForgeConfigSpec.IntValue,
        val sinkTier: ForgeConfigSpec.IntValue
    )

    val standbyEuPerTick: ForgeConfigSpec.IntValue
    val euPerActiveAppliance: ForgeConfigSpec.IntValue
    val loadCheckIntervalTicks: ForgeConfigSpec.IntValue
    private val tiers = EnumMap<TransformerTier, Tier>(TransformerTier::class.java)
    val SPEC: ForgeConfigSpec

    init {
        val builder = ForgeConfigSpec.Builder()
        builder.comment("EU Transformer balance settings").push("transformer")

        standbyEuPerTick = builder
            .comment(
                "EU drawn per tick while the network is energised but nothing is working.",
                "Set to 0 to make an idle network completely free."
            )
            .defineInRange("standbyEuPerTick", 1, 0, 1024)

        euPerActiveAppliance = builder
            .comment("Additional EU per tick for each connected appliance actually doing work.")
            .defineInRange("euPerActiveAppliance", 4, 0, 1024)

        loadCheckIntervalTicks = builder
            .comment("How often to rescan the network for active appliances, in ticks.")
            .defineInRange("loadCheckIntervalTicks", 10, 1, 200)

        for (tier in TransformerTier.values()) {
            builder.comment("Settings for the ${tier.configKey} tier transformer").push(tier.configKey)

            val maxDevices = builder
                .comment(
                    "Maximum devices this transformer can power. Exceeding it overloads",
                    "the block until you detach enough appliances."
                )
                .defineInRange("maxPoweredDevices", tier.defaultMaxDevices, 1, 1024)

            val buffer = builder
                .comment(
                    "Internal EU buffer. Rides out brief supply gaps so lights don't flicker.",
                    "Capped at 32767: the GUI syncs this through vanilla ContainerData,",
                    "which transmits shorts, and larger values would wrap in the readout."
                )
                .defineInRange("bufferEu", tier.defaultBufferEu, 100, 32767)

            val sink = builder
                .comment(
                    "IC2 voltage tier accepted. 1 = LV (32 EU/t), 2 = MV (128 EU/t),",
                    "3 = HV (512 EU/t), 4 = EV (2048 EU/t).",
                    "Feeding more than the tier allows will make IC2 explode the block."
                )
                .defineInRange("sinkTier", tier.defaultSinkTier, 1, 4)

            tiers[tier] = Tier(maxDevices, buffer, sink)
            builder.pop()
        }

        builder.pop()
        SPEC = builder.build()
    }

    /**
     * Config values throw if read before the spec loads, and IC2 can ask for the
     * sink tier while a world is still coming up, so every read falls back to the
     * default until the file is available.
     */
    private fun read(value: ForgeConfigSpec.IntValue, fallback: Int): Int =
        if (SPEC.isLoaded) value.get() else fallback

    fun standbyEu(): Int = read(standbyEuPerTick, 1)
    fun euPerActive(): Int = read(euPerActiveAppliance, 4)
    fun loadCheckInterval(): Long = read(loadCheckIntervalTicks, 10).toLong()

    fun maxDevices(tier: TransformerTier): Int =
        read(tiers.getValue(tier).maxPoweredDevices, tier.defaultMaxDevices)

    fun buffer(tier: TransformerTier): Int =
        read(tiers.getValue(tier).bufferEu, tier.defaultBufferEu)

    fun sinkTier(tier: TransformerTier): Int =
        read(tiers.getValue(tier).sinkTier, tier.defaultSinkTier)
}
