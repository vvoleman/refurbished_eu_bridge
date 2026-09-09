package dev.vvoleman.refurbishedeu

import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.Button
import net.minecraft.network.chat.Component

/**
 * The panel's primary action, drawn flat instead of with vanilla's bevelled
 * widget sprite so it matches the rest of the redesigned screen.
 *
 * Disabled is flat grey rather than vanilla's washed-out text, because here
 * "disabled" has a specific meaning - redstone owns the switch - and the hover
 * tooltip that explains it only reads as an answer if the button first reads as
 * deliberately locked.
 */
class FlatButton(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    message: Component,
    onPress: Button.OnPress
) : Button(x, y, width, height, message, onPress) {

    override fun renderButton(pose: PoseStack, mouseX: Int, mouseY: Int, partialTick: Float) {
        val body = when {
            !active -> GuiSkin.PRIMARY_OFF
            isHoveredOrFocused -> GuiSkin.PRIMARY_HOVER
            else -> GuiSkin.PRIMARY
        }

        GuiSkin.rect(pose, x, y, width, height, body)
        GuiSkin.outline(pose, x, y, width, height, GuiSkin.FRAME)

        val font = Minecraft.getInstance().font
        drawCenteredString(
            pose, font, message,
            x + width / 2, y + (height - font.lineHeight) / 2 + 1,
            if (active) GuiSkin.TEXT_LIGHT else GuiSkin.TEXT_ON_DISABLED
        )
    }
}
