package dev.vvoleman.refurbishedeu

import net.minecraft.network.chat.Component

/**
 * What the block is doing, and how that reads on screen.
 *
 * Shared by the GUI and the hover label so the bolt is never green in one place
 * and grey in the other, and so "overloaded" only has to be defined once.
 *
 * The tints are floats for RenderSystem.setShaderColor, the indicator is ARGB
 * for fills, and the text colour is RGB because Font.draw ignores alpha.
 */
enum class TransformerStatus(
    val messageKey: String,
    val indicator: Int,
    val textColour: Int,
    val red: Float,
    val green: Float,
    val blue: Float
) {
    RUNNING("gui.refurbished_eu.status.running", GuiSkin.FILL_OK, GuiSkin.TEXT_OK, 0.35f, 0.83f, 0.36f),
    NO_POWER("gui.refurbished_eu.status.no_power", GuiSkin.FILL_IDLE, GuiSkin.TEXT_MUTED, 0.62f, 0.62f, 0.62f),
    OFF("gui.refurbished_eu.status.off", GuiSkin.FILL_IDLE, GuiSkin.TEXT_MUTED, 0.62f, 0.62f, 0.62f),
    OFF_REDSTONE("gui.refurbished_eu.status.off_redstone", GuiSkin.FILL_IDLE, GuiSkin.TEXT_MUTED, 0.62f, 0.62f, 0.62f),
    OVERLOADED("gui.refurbished_eu.status.overloaded", GuiSkin.FILL_BAD, GuiSkin.TEXT_BAD, 0.91f, 0.29f, 0.24f);

    val message: Component get() = Component.translatable(messageKey)

    val isFault: Boolean get() = this == OVERLOADED

    companion object {
        /**
         * Order matters: a fault outranks being switched off, and "no redstone
         * signal" is the honest wording when nobody switched anything.
         */
        fun of(enabled: Boolean, overloaded: Boolean, storedEu: Int, mode: ControlMode): TransformerStatus = when {
            overloaded -> OVERLOADED
            !enabled && mode == ControlMode.REDSTONE -> OFF_REDSTONE
            !enabled -> OFF
            storedEu <= 0 -> NO_POWER
            else -> RUNNING
        }
    }
}
