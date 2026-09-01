package dev.vvoleman.refurbishedeu

/**
 * How a transformer decides whether it is switched on.
 *
 * Only MANUAL accepts external toggles. In REDSTONE the on/off state is owned by
 * the world, so the GUI button, Refurbished's Home Control app and the CC
 * peripheral all refuse to change it - otherwise a computer could set a state
 * that the next redstone update silently reverts.
 */
enum class ControlMode(val id: String) {

    /** Toggled by hand from the GUI, or by a computer. */
    MANUAL("manual"),

    /** Follows the block's redstone signal: powered means on. */
    REDSTONE("redstone");

    fun next(): ControlMode = values()[(ordinal + 1) % values().size]

    val translationKey: String get() = "gui.refurbished_eu.mode.$id"

    companion object {
        fun byOrdinal(ordinal: Int): ControlMode =
            values().getOrElse(ordinal) { MANUAL }

        fun byId(id: String): ControlMode? =
            values().firstOrNull { it.id.equals(id, ignoreCase = true) }
    }
}
