package dev.vvoleman.refurbishedeu

import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

/**
 * Manual/Redstone as one segmented control rather than two buttons or a cycling
 * one. Both modes are visible at once, so the screen answers "which mode am I
 * in, and what else could I be in" without a click or a hover.
 *
 * Rendered from ControlMode.values() rather than two hard-coded halves, so a
 * third mode would lay itself out; picking a mode sends that mode's own button
 * id, so it stays a one-click choice instead of N cycles.
 */
class ModeSelector(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    private val currentMode: () -> ControlMode,
    private val onSelect: (ControlMode) -> Unit
) : AbstractWidget(x, y, width, height, Component.translatable("gui.refurbished_eu.control_mode")) {

    private val modes = ControlMode.values()

    /** Which segment a screen coordinate falls in, for hover text. */
    fun segmentAt(mouseX: Double, mouseY: Double): ControlMode? {
        if (mouseX < x || mouseX >= x + width || mouseY < y || mouseY >= y + height) return null
        val index = ((mouseX - x) / (width.toDouble() / modes.size)).toInt()
        return modes.getOrNull(index.coerceIn(0, modes.size - 1))
    }

    override fun onClick(mouseX: Double, mouseY: Double) {
        val target = segmentAt(mouseX, mouseY) ?: return
        // Re-picking the active mode is a no-op, not a round trip to the server.
        if (target != currentMode()) onSelect(target)
    }

    override fun renderButton(pose: PoseStack, mouseX: Int, mouseY: Int, partialTick: Float) {
        val font = Minecraft.getInstance().font
        val active = currentMode()
        val hovered = segmentAt(mouseX.toDouble(), mouseY.toDouble())

        for ((index, mode) in modes.withIndex()) {
            val left = x + width * index / modes.size
            val right = x + width * (index + 1) / modes.size
            val selected = mode == active

            val body = when {
                selected -> GuiSkin.SEGMENT_ON
                mode == hovered -> GuiSkin.SEGMENT_OFF_HOVER
                else -> GuiSkin.SEGMENT_OFF
            }
            GuiSkin.rect(pose, left, y, right - left, height, body)

            val label = Component.translatable(mode.translationKey)
            val icon = iconFor(mode)
            val iconWidth = if (icon == null) 0 else ICON + ICON_GAP
            val textWidth = font.width(label)
            val start = left + (right - left - iconWidth - textWidth) / 2

            if (icon != null) {
                // Vanilla's own redstone dust, so the segment needs no new asset
                // and reads instantly to anyone who has ever held the item.
                Minecraft.getInstance().itemRenderer.renderAndDecorateFakeItem(
                    icon, start, y + (height - ICON) / 2
                )
            }
            font.draw(
                pose, label,
                (start + iconWidth).toFloat(),
                (y + (height - font.lineHeight) / 2 + 1).toFloat(),
                if (selected) GuiSkin.TEXT else if (mode == hovered) GuiSkin.TEXT_LIGHT
                else GuiSkin.TEXT_ON_DARK_MUTED
            )

            // Divider between segments, drawn on the left edge of all but the first.
            if (index > 0) GuiSkin.rect(pose, left, y, 1, height, GuiSkin.FRAME)
        }

        GuiSkin.outline(pose, x, y, width, height, GuiSkin.FRAME)
    }

    private fun iconFor(mode: ControlMode): ItemStack? =
        if (mode == ControlMode.REDSTONE) REDSTONE else null

    override fun updateNarration(output: NarrationElementOutput) {
        // Reads out as "Control mode: Redstone", so the screen is usable without
        // relying on which segment happens to look lit.
        output.add(
            net.minecraft.client.gui.narration.NarratedElementType.TITLE,
            Component.translatable(
                "gui.refurbished_eu.mode.narration",
                Component.translatable(currentMode().translationKey)
            )
        )
    }

    private companion object {
        const val ICON = 16
        const val ICON_GAP = 2
        val REDSTONE: ItemStack = ItemStack(Items.REDSTONE)
    }
}
