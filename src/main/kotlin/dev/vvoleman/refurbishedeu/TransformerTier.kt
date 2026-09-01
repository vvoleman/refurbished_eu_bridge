package dev.vvoleman.refurbishedeu

/**
 * The three transformer variants. Output capacity and accepted IC2 voltage rise
 * together, so upgrading is a single decision rather than two.
 *
 * IC2 tiers: 1 = LV (32 EU/t), 2 = MV (128 EU/t), 3 = HV (512 EU/t).
 */
enum class TransformerTier(
    val id: String,
    val defaultMaxDevices: Int,
    val defaultBufferEu: Int,
    val defaultSinkTier: Int
) {
    LOW("low_eu_transformer", 8, 10_000, 1),
    MEDIUM("medium_eu_transformer", 16, 20_000, 2),
    HIGH("high_eu_transformer", 32, 32_767, 3);

    /** Config section name, e.g. "low". */
    val configKey: String get() = name.lowercase()
}
