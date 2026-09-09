package dev.vvoleman.refurbishedeu

import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.gui.GuiComponent

/**
 * Flat drawing primitives for the transformer panel.
 *
 * Everything is built from axis-aligned fills rather than a background texture:
 * the panel has to grow with the device grid (a low transformer needs one row of
 * slots, a high one needs two, a reconfigured one needs a fallback bar), and a
 * fixed 176x166 blit cannot do that. Fills also keep us honest about the
 * vanilla/IC2 look the redesign asked for - hard 1px edges, no gradients, no
 * rounded corners, no shadows.
 *
 * Colours are ARGB for fills and plain RGB for text, because Font.draw assumes
 * opaque and would render an 0xFF-prefixed colour invisible... in the alpha it
 * ignores.
 */
object GuiSkin {

    // Panel chrome. BODY is vanilla's own GUI grey so the screen sits next to a
    // player inventory without looking foreign.
    val FRAME = 0xFF2B2B2B.toInt()
    val BODY = 0xFFC6C6C6.toInt()
    val INSET_BG = 0xFFECECE6.toInt()
    val INSET_BORDER = 0xFF9A9A9A.toInt()
    val DARK_BAR = 0xFF1B1B1B.toInt()

    // Meters and slots.
    val TROUGH = 0xFF565656.toInt()
    val TICK = 0xFF747474.toInt()
    val FILL_OK = 0xFF6FA83C.toInt()
    val FILL_WARN = 0xFFD08A1E.toInt()
    val FILL_IDLE = 0xFF7A7A7A.toInt()
    val FILL_BAD = 0xFFB03030.toInt()
    val SLOT_ACTIVE = 0xFF6FA83C.toInt()
    val SLOT_CONNECTED = 0xFF3C5F22.toInt()
    val SLOT_EMPTY = 0xFFBFBFB9.toInt()
    val SLOT_EMPTY_BORDER = 0xFF9A9A9A.toInt()

    /** Hover ring on a device square. */
    val HIGHLIGHT = 0xFFFFFFFF.toInt()

    // Buttons.
    val PRIMARY = 0xFF3E8E28.toInt()
    val PRIMARY_HOVER = 0xFF4CA531.toInt()
    val PRIMARY_OFF = 0xFF9A9A94.toInt()
    val SEGMENT_ON = 0xFFD6D6D0.toInt()
    val SEGMENT_OFF = 0xFF3B3B3B.toInt()
    val SEGMENT_OFF_HOVER = 0xFF4C4C4C.toInt()

    // Text (RGB).
    const val TEXT = 0x404040
    const val TEXT_MUTED = 0x707070
    const val TEXT_LIGHT = 0xE8E8E8
    const val TEXT_ON_DARK_MUTED = 0xA0A0A0

    /** For a label on [PRIMARY_OFF]: light grey on grey is unreadable. */
    const val TEXT_ON_DISABLED = 0x4A4A46
    const val TEXT_OK = 0x2E7D32
    const val TEXT_WARN = 0x8A5A0F
    const val TEXT_BAD = 0xB03030

    /** Dark 1px frame with a body fill inside it. */
    fun panel(pose: PoseStack, x: Int, y: Int, width: Int, height: Int) {
        GuiComponent.fill(pose, x, y, x + width, y + height, FRAME)
        GuiComponent.fill(pose, x + 1, y + 1, x + width - 1, y + height - 1, BODY)
    }

    /** Sunken content area: a lighter field behind a mid-grey border. */
    fun inset(pose: PoseStack, x: Int, y: Int, width: Int, height: Int) {
        GuiComponent.fill(pose, x, y, x + width, y + height, INSET_BORDER)
        GuiComponent.fill(pose, x + 1, y + 1, x + width - 1, y + height - 1, INSET_BG)
    }

    fun rect(pose: PoseStack, x: Int, y: Int, width: Int, height: Int, colour: Int) {
        GuiComponent.fill(pose, x, y, x + width, y + height, colour)
    }

    /** 1px outline, drawn as four fills so nothing bleeds into the middle. */
    fun outline(pose: PoseStack, x: Int, y: Int, width: Int, height: Int, colour: Int) {
        GuiComponent.fill(pose, x, y, x + width, y + 1, colour)
        GuiComponent.fill(pose, x, y + height - 1, x + width, y + height, colour)
        GuiComponent.fill(pose, x, y + 1, x + 1, y + height - 1, colour)
        GuiComponent.fill(pose, x + width - 1, y + 1, x + width, y + height - 1, colour)
    }

    /**
     * Horizontal meter. A non-zero reading always paints at least one pixel:
     * 18 EU in a 10 000 EU buffer rounds to nothing, and "empty" and "nearly
     * empty" are different enough to be worth a pixel.
     */
    fun meter(
        pose: PoseStack,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        filled: Int,
        capacity: Int,
        colour: Int,
        ticks: Boolean = false
    ) {
        outline(pose, x, y, width, height, FRAME)
        rect(pose, x + 1, y + 1, width - 2, height - 2, TROUGH)

        // Quarter marks, so a reading can be placed without doing arithmetic on
        // the numbers below. Drawn into the trough, so the fill covers them as it
        // passes - which is what makes them read as marks rather than as content.
        if (ticks) {
            val track = width - 2
            for (quarter in 1..3) {
                rect(pose, x + 1 + track * quarter / 4, y + 2, 1, height - 4, TICK)
            }
        }

        if (filled <= 0 || capacity <= 0) return
        val track = width - 2
        val exact = track.toFloat() * filled.toFloat() / capacity.toFloat()
        val painted = exact.toInt().coerceIn(1, track)
        rect(pose, x + 1, y + 1, painted, height - 2, colour)
    }

    /**
     * A small downward arrow, built from fills because the default font has no
     * glyph for one and a bitmap for five pixels would be silly.
     */
    fun arrowDown(pose: PoseStack, x: Int, y: Int, colour: Int, width: Int = 5) {
        var row = width
        var top = y
        while (row > 0) {
            rect(pose, x + (width - row) / 2, top, row, 1, colour)
            top++
            row -= 2
        }
    }
}
