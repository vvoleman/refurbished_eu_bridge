package dev.vvoleman.refurbishedeu.mail

import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.player.Inventory
import com.mojang.blaze3d.vertex.PoseStack

class ParcelScreen(
    menu: ParcelMenu,
    inventory: Inventory,
    title: Component,
) : AbstractContainerScreen<ParcelMenu>(menu, inventory, title) {

    private val texture = ResourceLocation("minecraft", "textures/gui/container/hopper.png")

    init {
        imageHeight = 133
        inventoryLabelY = imageHeight - 94
    }

    override fun render(poseStack: PoseStack, mouseX: Int, mouseY: Int, partialTick: Float) {
        renderBackground(poseStack)
        super.render(poseStack, mouseX, mouseY, partialTick)
        renderTooltip(poseStack, mouseX, mouseY)
    }

    override fun renderBg(poseStack: PoseStack, partialTick: Float, mouseX: Int, mouseY: Int) {
        RenderSystem.setShader { net.minecraft.client.renderer.GameRenderer.getPositionTexShader() }
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f)
        RenderSystem.setShaderTexture(0, texture)
        blit(poseStack, leftPos, topPos, 0, 0, imageWidth, imageHeight)
    }
}
