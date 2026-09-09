package dev.vvoleman.refurbishedeu

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiComponent
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraftforge.client.event.RenderGuiOverlayEvent
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay
import net.minecraftforge.eventbus.api.IEventBus

/**
 * Draws a transformer's circuit name, status and device load while the player is
 * looking at it.
 *
 * Deliberately mirrors Refurbished's own NodeIndicatorOverlay styling - 3px
 * padding, a 15px tall box built from three fills at 0x77000000 to fake rounded
 * corners, a 10px icon on the left and white shadowed text - so it reads as part
 * of the same UI as their "Missing power" label rather than something bolted on.
 */
object TransformerHudOverlay {

    private const val PADDING = 3
    private const val ICON_SIZE = 10
    private const val LINE_HEIGHT = 9
    private const val BACKGROUND = 0x77000000

    /**
     * Refurbished centres its own label at +50. Sitting at +32 keeps both readable
     * when a wrench is in hand and both want to draw at once.
     */
    private const val VERTICAL_OFFSET = 32

    /**
     * Our own bolt, and our own silhouette: Refurbished's is baked orange in both
     * gui/icons.png and their icon font, and a shader tint multiplies, so no
     * colour we pass could ever turn it green or grey.
     *
     * White body, black drop shadow down and right. White takes the tint cleanly
     * and black stays black, and spending the edge on a shadow rather than a full
     * outline leaves two more pixels of body - which at 10px is the difference
     * between a bolt and a smudge. The text beside it is drawn with a shadow for
     * the same reason.
     */
    private val ICONS = ResourceLocation("refurbished_eu", "textures/gui/status_bolt.png")

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

        // Both fields are read straight off the client's block entity, which the
        // server refreshes whenever either moves - so the label is live, not a
        // snapshot from when the chunk arrived.
        val connected = blockEntity.connectedCount
        val max = TransformerConfig.maxDevices(blockEntity.tier)

        // Same tints as the GUI panel, so the bolt never disagrees with itself.
        val status = when {
            blockEntity.isNodeOverloaded -> TransformerStatus.OVERLOADED
            blockEntity.isNodePowered -> TransformerStatus.RUNNING
            else -> TransformerStatus.OFF
        }

        val devices = Component.translatable("hud.refurbished_eu.devices", connected, max)
            .withStyle(if (connected > max) ChatFormatting.RED else ChatFormatting.GRAY)
        val label = Component.empty()
            // deviceName is the player's name for it, or the block's own if unnamed.
            .append(blockEntity.deviceName)
            .append(Component.literal(" "))
            .append(devices)

        drawLabel(minecraft, event.poseStack, label, status)
    }

    private fun drawLabel(minecraft: Minecraft, poseStack: PoseStack, text: Component, status: TransformerStatus) {
        val font = minecraft.font
        val boxWidth = PADDING + ICON_SIZE + PADDING + font.width(text) + PADDING
        val boxHeight = PADDING + LINE_HEIGHT + PADDING

        val x = (minecraft.window.guiScaledWidth - boxWidth) / 2
        val y = (minecraft.window.guiScaledHeight - boxHeight) / 2 + VERTICAL_OFFSET

        // Three rects rather than one, so the corners read as rounded.
        GuiComponent.fill(poseStack, x, y + 1, x + 1, y + boxHeight - 1, BACKGROUND)
        GuiComponent.fill(poseStack, x + 1, y, x + boxWidth - 1, y + boxHeight, BACKGROUND)
        GuiComponent.fill(poseStack, x + boxWidth - 1, y + 1, x + boxWidth, y + boxHeight - 1, BACKGROUND)

        RenderSystem.setShader(GameRenderer::getPositionTexShader)
        RenderSystem.setShaderTexture(0, ICONS)
        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()
        RenderSystem.setShaderColor(status.red, status.green, status.blue, 1.0f)
        GuiComponent.blit(
            poseStack, x + PADDING, y + PADDING, 0f, 0f,
            ICON_SIZE, ICON_SIZE, 16, 16
        )
        // Left set, the tint would bleed into every later element on the HUD.
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f)

        font.drawShadow(
            poseStack, text,
            (x + PADDING + ICON_SIZE + PADDING).toFloat(), (y + PADDING).toFloat(),
            0xFFFFFF
        )
    }
}
