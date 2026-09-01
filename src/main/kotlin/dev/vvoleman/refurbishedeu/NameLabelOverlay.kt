package dev.vvoleman.refurbishedeu

import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiComponent
import net.minecraft.network.chat.Component
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraftforge.client.event.RenderGuiOverlayEvent
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay
import net.minecraftforge.eventbus.api.IEventBus

/**
 * Draws a named transformer's label while the player is looking at it.
 *
 * Deliberately mirrors Refurbished's own NodeIndicatorOverlay styling - 3px
 * padding, a 15px tall box built from three fills at 0x77000000 to fake rounded
 * corners, and white shadowed text - so it reads as part of the same UI as their
 * "Missing power" label rather than something bolted on.
 */
object NameLabelOverlay {

    private const val PADDING = 3
    private const val LINE_HEIGHT = 9
    private const val BACKGROUND = 0x77000000

    /**
     * Refurbished centres its own label at +50. Sitting at +32 keeps both readable
     * when a wrench is in hand and both want to draw at once.
     */
    private const val VERTICAL_OFFSET = 32

    fun register(bus: IEventBus) {
        bus.addListener(::onRenderOverlay)
    }

    private fun onRenderOverlay(event: RenderGuiOverlayEvent.Post) {
        if (event.overlay != VanillaGuiOverlay.CROSSHAIR.type()) return

        val minecraft = Minecraft.getInstance()
        if (minecraft.options.hideGui) return

        val hit = minecraft.hitResult as? BlockHitResult ?: return
        if (hit.type != HitResult.Type.BLOCK) return

        val blockEntity = minecraft.level?.getBlockEntity(hit.blockPos) as? TransformerBlockEntity ?: return
        val name = blockEntity.customName ?: return

        drawLabel(minecraft, event.poseStack, Component.literal(name))
    }

    private fun drawLabel(minecraft: Minecraft, poseStack: PoseStack, text: Component) {
        val font = minecraft.font
        val boxWidth = PADDING + font.width(text) + PADDING
        val boxHeight = PADDING + LINE_HEIGHT + PADDING

        val x = (minecraft.window.guiScaledWidth - boxWidth) / 2
        val y = (minecraft.window.guiScaledHeight - boxHeight) / 2 + VERTICAL_OFFSET

        // Three rects rather than one, so the corners read as rounded.
        GuiComponent.fill(poseStack, x, y + 1, x + 1, y + boxHeight - 1, BACKGROUND)
        GuiComponent.fill(poseStack, x + 1, y, x + boxWidth - 1, y + boxHeight, BACKGROUND)
        GuiComponent.fill(poseStack, x + boxWidth - 1, y + 1, x + boxWidth, y + boxHeight - 1, BACKGROUND)

        font.drawShadow(
            poseStack, text,
            (x + PADDING).toFloat(), (y + PADDING).toFloat(),
            0xFFFFFF
        )
    }
}
