package dev.vvoleman.refurbishedeu

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.player.Inventory
import org.lwjgl.glfw.GLFW

class TransformerScreen(
    menu: TransformerMenu,
    playerInventory: Inventory,
    title: Component
) : AbstractContainerScreen<TransformerMenu>(menu, playerInventory, title) {

    private lateinit var nameField: EditBox
    private var toggleButton: Button? = null
    private var modeButton: Button? = null
    private var lastSentName: String = ""

    init {
        imageWidth = 176
        imageHeight = 166
        inventoryLabelY = -1000 // slotless menu: hide the "Inventory" label
    }

    override fun init() {
        super.init()

        val existing = currentName()
        lastSentName = existing

        nameField = EditBox(
            font, leftPos + 9, topPos + 120, imageWidth - 18, 16,
            Component.translatable("gui.refurbished_eu.name")
        )
        nameField.setMaxLength(ModNetwork.MAX_NAME_LENGTH)
        nameField.value = existing
        addRenderableWidget(nameField)

        // Two buttons on one row: power on the left, control mode on the right.
        toggleButton = addRenderableWidget(
            Button(leftPos + POWER_X, topPos + BUTTON_Y, POWER_W, BUTTON_H, buttonLabel()) {
                minecraft?.gameMode?.handleInventoryButtonClick(
                    menu.containerId, TransformerMenu.BUTTON_TOGGLE_POWER
                )
            }
        )

        modeButton = addRenderableWidget(
            Button(leftPos + MODE_X, topPos + BUTTON_Y, MODE_W, BUTTON_H, modeLabel()) {
                minecraft?.gameMode?.handleInventoryButtonClick(
                    menu.containerId, TransformerMenu.BUTTON_CYCLE_MODE
                )
            }
        )
    }

    /** The name lives on the block entity, not in ContainerData, which carries ints only. */
    private fun currentName(): String {
        val be = minecraft?.level?.getBlockEntity(menu.blockPos)
        return (be as? TransformerBlockEntity)?.customName.orEmpty()
    }

    private fun commitName() {
        val value = nameField.value.trim()
        if (value == lastSentName) return
        lastSentName = value
        ModNetwork.CHANNEL.sendToServer(SetNamePacket(menu.blockPos, value))
    }

    private fun buttonLabel(): Component = Component.translatable(
        if (menu.isEnabled) "gui.refurbished_eu.turn_off" else "gui.refurbished_eu.turn_on"
    )

    private fun modeLabel(): Component = Component.translatable(menu.controlMode.translationKey)

    override fun containerTick() {
        super.containerTick()
        nameField.tick()
        nameField.setSuggestion(
            if (nameField.value.isEmpty()) {
                Component.translatable("gui.refurbished_eu.name_hint").string
            } else null
        )
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        // Escape must still close, but every other key has to reach the text field
        // first - otherwise typing "e" closes the screen via the inventory keybind.
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            commitName()
            minecraft?.player?.closeContainer()
            return true
        }
        if (nameField.isFocused) {
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                commitName()
                return true
            }
            return nameField.keyPressed(keyCode, scanCode, modifiers) || nameField.canConsumeInput()
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    override fun removed() {
        commitName()
        super.removed()
    }

    override fun render(poseStack: PoseStack, mouseX: Int, mouseY: Int, partialTick: Float) {
        renderBackground(poseStack)
        toggleButton?.let {
            it.message = buttonLabel()
            // Under redstone control the world owns the state; the server refuses
            // the packet anyway, this just stops the button from lying.
            it.active = menu.controlMode == ControlMode.MANUAL
        }
        modeButton?.message = modeLabel()
        super.render(poseStack, mouseX, mouseY, partialTick)
        renderTooltip(poseStack, mouseX, mouseY)
        renderButtonHints(poseStack, mouseX, mouseY)
    }

    /**
     * Hover text for the two buttons. Done by hand rather than through Button's
     * OnTooltip because a disabled button never reports itself as hovered, and
     * explaining *why* the power button is disabled is the whole point.
     */
    private fun renderButtonHints(poseStack: PoseStack, mouseX: Int, mouseY: Int) {
        if (menu.controlMode != ControlMode.MANUAL &&
            isOver(mouseX, mouseY, POWER_X, POWER_W)
        ) {
            renderTooltip(
                poseStack,
                Component.translatable("gui.refurbished_eu.locked_by_redstone"),
                mouseX, mouseY
            )
        } else if (isOver(mouseX, mouseY, MODE_X, MODE_W)) {
            renderTooltip(
                poseStack,
                Component.translatable("gui.refurbished_eu.mode.tooltip"),
                mouseX, mouseY
            )
        }
    }

    private fun isOver(mouseX: Int, mouseY: Int, x: Int, width: Int): Boolean =
        mouseX >= leftPos + x && mouseX < leftPos + x + width &&
            mouseY >= topPos + BUTTON_Y && mouseY < topPos + BUTTON_Y + BUTTON_H

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
            // "Switched off" would be misleading when nobody switched anything.
            !menu.isEnabled && menu.controlMode == ControlMode.REDSTONE ->
                Component.translatable("gui.refurbished_eu.status.off_redstone")
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
            Component.translatable("gui.refurbished_eu.buffer", menu.storedEu, menu.bufferMax),
            13f, y, 0x404040
        )
        y += 12f
        font.draw(
            poseStack,
            Component.translatable("gui.refurbished_eu.connected", menu.connectedCount, menu.maxDevices),
            13f, y,
            if (menu.connectedCount > menu.maxDevices) 0xB03030 else 0x404040
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
            Component.translatable("gui.refurbished_eu.tier", menu.sinkTier),
            13f, 101f, 0x707070
        )
    }

    companion object {
        private val TEXTURE = ResourceLocation("refurbished_eu", "textures/gui/eu_transformer.png")

        // Button row, relative to the panel's top-left corner.
        private const val BUTTON_Y = 140
        private const val BUTTON_H = 18
        private const val POWER_X = 8
        private const val POWER_W = 104
        private const val MODE_X = 116
        private const val MODE_W = 52
    }
}
