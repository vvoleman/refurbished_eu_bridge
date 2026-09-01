package dev.vvoleman.refurbishedeu

/**
 * Tuning knobs. Plain constants for now so the first builds carry no config
 * library dependency; these move into a Forge config spec once the numbers settle.
 *
 * Balance reference: Refurbished's own generator turns one fuel item into
 * `burnTime * fuelToPowerRatio` energy and spends it at 1/tick, so a single coal
 * (1600 burn ticks, default ratio 4) buys 6400 ticks of uptime.
 */
object TransformerConfig {
    /** Drawn every tick while energised, regardless of what is connected. */
    const val STANDBY_EU_PER_TICK = 1

    /** Added to the standby draw for each appliance actually doing work. */
    const val EU_PER_ACTIVE_APPLIANCE = 4

    /** Internal buffer, in EU. Rides out brief gaps so lights don't flicker. */
    const val BUFFER_EU = 10_000

    /** IC2 voltage tier this block accepts. 1 = LV (32 EU/t), 2 = MV (128 EU/t). */
    const val SINK_TIER = 1
}
