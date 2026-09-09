package dev.vvoleman.refurbishedeu

/**
 * Number formatting shared by the GUI and the item tooltip.
 *
 * Lives apart from [GuiSkin] on purpose: the item class is loaded on a dedicated
 * server too, and GuiSkin names client-only types in its signatures.
 */
object EuFormat {

    /** Digit grouping, so a 10 000 EU buffer does not read as 10000. */
    fun grouped(value: Int): String {
        val digits = value.toString()
        val out = StringBuilder(digits.length + digits.length / 3)
        for ((index, ch) in digits.withIndex()) {
            if (index > 0 && (digits.length - index) % 3 == 0) out.append(' ')
            out.append(ch)
        }
        return out.toString()
    }
}
