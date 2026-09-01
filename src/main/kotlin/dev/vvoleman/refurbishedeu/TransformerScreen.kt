package dev.vvoleman.refurbishedeu

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.player.Inventory

class TransformerScreen(
    menu: TransformerMenu,
    playerInventory: Inventory,
    title: Component
) : AbstractContainerScreen<TransformerMenu>(menu, playerInventory, title) {

    private var toggleButton: Button? = null

    init {
        imageWidth = 176
        imageHeight = 166
        inventoryLabelY = -1000 // slotless menu: hide the "Inventory" label
    }

    override fun init() {
        super.init()
        toggleButton = addRenderableWidget(
            Button(leftPos + 8, topPos + 124, imageWidth - 16, 20, buttonLabel()) {
                minecraft?.gameMode?.handleInventoryButtonClick(
                    menu.containerId, TransformerMenu.BUTTON_TOGGLE_POWER
                )
            }
        )
    }

    private fun buttonLabel(): Component = Component.translatable(
        if (menu.isEnabled) "gui.refurbished_eu.turn_off" else "gui.refurbished_eu.turn_on"
    )

    override fun render(poseStack: PoseStack, mouseX: Int, mouseY: Int, partialTick: Float) {
        renderBackground(poseStack)
        toggleButton?.message = buttonLabel()
        super.render(poseStack, mouseX, mouseY, partialTick)
        renderTooltip(poseStack, mouseX, mouseY)
    }

    override fun renderBg(poseStack: PoseStack, partialTick: Float, mouseX: Int, mouseY: Int) {
        RenderSystem.setShader(GameRenderer::getPositionTexShader)
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f)
        RenderSystem.setShaderTexture(0, TEXTURE)
        blit(poseStack, leftPos, topPos, 0, 0, imageWidth, imageHeight)
    }

    override fun renderLabels(poseStack: PoseStack, mouseX: Int, mouseY: Int) {
        font.draw(poseStack, title, 8f, 6f, 0x404040)

        val status: Component = when {
            menu.isOverloaded -> Component.translatable("gui.refurbished_eu.status.overloaded")
            !menu.isEnabled -> Component.translatable("gui.refurbished_eu.status.off")
            menu.storedEu <= 0 -> Component.translatable("gui.refurbished_eu.status.no_power")
            else -> Component.translatable("gui.refurbished_eu.status.running")
        }
        val statusColour = when {
            menu.isOverloaded -> 0xB03030
            !menu.isEnabled || menu.storedEu <= 0 -> 0x707070
            else -> 0x2E7D32
        }

        var y = 23f
        font.draw(poseStack, Component.translatable("gui.refurbished_eu.status", status), 13f, y, statusColour)
        y += 12f
        font.draw(
            poseStack,
            Component.translatable("gui.refurbished_eu.buffer", menu.storedEu, TransformerConfig.BUFFER_EU),
            13f, y, 0x404040
        )
        y += 12f
        font.draw(
            poseStack,
            Component.translatable("gui.refurbished_eu.connected", menu.connectedCount),
            13f, y, 0x404040
        )
        y += 12f
        font.draw(
            poseStack,
            Component.translatable("gui.refurbished_eu.active", menu.activeCount),
            13f, y, 0x404040
        )

        font.draw(
            poseStack,
            Component.translatable("gui.refurbished_eu.draw", menu.currentDraw),
            13f, 89f, 0x404040
        )
        font.draw(
            poseStack,
            Component.translatable("gui.refurbished_eu.tier", TransformerConfig.SINK_TIER),
            13f, 101f, 0x707070
        )
    }

    companion object {
        private val TEXTURE = ResourceLocation("refurbished_eu", "textures/gui/eu_transformer.png")
    }
}
