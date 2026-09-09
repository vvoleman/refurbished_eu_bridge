package dev.vvoleman.refurbishedeu

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import org.lwjgl.glfw.GLFW

/**
 * The transformer panel.
 *
 * Reads top-down as state, then load, then controls: a tinted bolt and a status
 * square beside the title, the buffer as a meter, the powered devices as a slot
 * grid, then the circuit name and the two controls. Every number still exists as
 * text beside its indicator - the graphics are there to be glanceable, not to be
 * the only copy of the information.
 *
 * Drawn from fills rather than a background texture because the panel height
 * depends on the device grid, which depends on the tier's configured device cap.
 * See [GuiSkin].
 */
class TransformerScreen(
    menu: TransformerMenu,
    playerInventory: Inventory,
    title: Component
) : AbstractContainerScreen<TransformerMenu>(menu, playerInventory, title) {

    private lateinit var nameField: EditBox
    private var powerButton: FlatButton? = null
    private var modeSelector: ModeSelector? = null
    private var lastSentName: String = ""

    /** Survives the widget rebuild that a changed device cap triggers. */
    private var draftName: String? = null

    private var layout = Layout(TransformerTier.LOW.defaultMaxDevices)

    /** A second of buffer readings, for the supply-trend warning. */
    private val bufferHistory = IntArray(TREND_SAMPLES)
    private var sampleIndex = 0
    private var samples = 0

    /** The network as the client sees it, rescanned on a timer. */
    private var roster = DeviceRoster.EMPTY
    private var scanCooldown = 0
    private var scannedAt = -1

    override fun init() {
        // Both must be final before super.init(), which centres the panel from them.
        layout = Layout(slotCount())
        imageWidth = PANEL_W
        imageHeight = layout.height
        super.init()

        val existing = draftName ?: currentName()
        lastSentName = currentName()

        // Unbordered: the dark strip behind it is ours, drawn in renderBg together
        // with the name-tag icon that the field has to leave room for.
        nameField = EditBox(
            font, leftPos + NAME_TEXT_X, topPos + layout.nameBarY + NAME_TEXT_DY,
            NAME_TEXT_W, LINE - 1,
            Component.translatable("gui.refurbished_eu.name")
        )
        nameField.setBordered(false)
        nameField.setTextColor(GuiSkin.TEXT_LIGHT)
        nameField.setMaxLength(ModNetwork.MAX_NAME_LENGTH)
        nameField.value = existing
        addRenderableWidget(nameField)

        powerButton = addRenderableWidget(
            FlatButton(
                leftPos + INSET_X, topPos + layout.buttonY, INSET_W, BUTTON_H, powerLabel()
            ) {
                minecraft?.gameMode?.handleInventoryButtonClick(
                    menu.containerId, TransformerMenu.BUTTON_TOGGLE_POWER
                )
            }
        )

        modeSelector = addRenderableWidget(
            ModeSelector(
                leftPos + INSET_X, topPos + layout.modeY, INSET_W, SEGMENT_H,
                { menu.controlMode },
                { mode ->
                    minecraft?.gameMode?.handleInventoryButtonClick(
                        menu.containerId, TransformerMenu.buttonForMode(mode)
                    )
                }
            )
        )
    }

    /**
     * How many slots the grid draws. ContainerData is still zeroed on the frame
     * the screen opens - the server only broadcasts it on its next tick - so the
     * first layout comes from the block entity's own tier, which the client
     * already has, along with the synced config.
     */
    private fun slotCount(): Int {
        if (menu.maxDevices > 0) return menu.maxDevices
        val be = minecraft?.level?.getBlockEntity(menu.blockPos) as? TransformerBlockEntity
        return be?.let { TransformerConfig.maxDevices(it.tier) }
            ?: TransformerTier.LOW.defaultMaxDevices
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

    private fun powerLabel(): Component = Component.translatable(
        if (menu.isEnabled) "gui.refurbished_eu.turn_off" else "gui.refurbished_eu.turn_on"
    )

    private fun status(): TransformerStatus =
        TransformerStatus.of(menu.isEnabled, menu.isOverloaded, menu.storedEu, menu.controlMode)

    override fun containerTick() {
        super.containerTick()

        // A reloaded config can change the device cap under us, which changes the
        // number of slot rows and so the height of the whole panel.
        if (layout.slots != slotCount()) {
            draftName = nameField.value
            rebuildWidgets()
            draftName = null
            return
        }

        bufferHistory[sampleIndex] = menu.storedEu
        sampleIndex = (sampleIndex + 1) % bufferHistory.size
        if (samples < bufferHistory.size) samples++

        // Walking the network every frame would be wasteful, so it happens on the
        // same cadence the server rescans on - but a changed device count means a
        // link was just made or broken, and that should show up at once.
        if (--scanCooldown <= 0 || scannedAt != menu.connectedCount) {
            val be = minecraft?.level?.getBlockEntity(menu.blockPos) as? TransformerBlockEntity
            roster = be?.let { DeviceRoster.scan(it) } ?: DeviceRoster.EMPTY
            scannedAt = menu.connectedCount
            scanCooldown = TransformerConfig.loadCheckInterval().toInt()
        }

        nameField.tick()
        nameField.setSuggestion(
            if (nameField.value.isEmpty()) {
                Component.translatable("gui.refurbished_eu.name_hint").string
            } else null
        )
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        // The name strip is 18px tall but the field inside it is one line high, so
        // clicking the strip has to focus it - otherwise the obvious target for
        // "rename this" does nothing on most of its surface.
        if (button == 0 && overNameBar(mouseX, mouseY)) {
            val clampedX = mouseX.coerceIn(
                (leftPos + NAME_TEXT_X).toDouble(),
                (leftPos + NAME_TEXT_X + NAME_TEXT_W - 1).toDouble()
            )
            val handled = nameField.mouseClicked(
                clampedX, (topPos + layout.nameBarY + NAME_TEXT_DY).toDouble(), button
            )
            // Typed characters arrive through charTyped, which the screen routes to
            // whatever getFocused() returns - and only the normal click path
            // through super sets that. Handing the click straight to the field
            // gives it a cursor but no keystrokes unless we set this ourselves.
            if (handled) setFocused(nameField)
            return handled
        }
        return super.mouseClicked(mouseX, mouseY, button)
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
        powerButton?.let {
            it.message = powerLabel()
            // Under redstone control the world owns the state; the server refuses
            // the packet anyway, this just stops the button from lying.
            it.active = menu.controlMode == ControlMode.MANUAL
        }
        super.render(poseStack, mouseX, mouseY, partialTick)
        renderTooltip(poseStack, mouseX, mouseY)
        renderHints(poseStack, mouseX, mouseY)
    }

    /** The whole panel. Everything here is in screen coordinates. */
    override fun renderBg(poseStack: PoseStack, partialTick: Float, mouseX: Int, mouseY: Int) {
        val state = status()

        GuiSkin.panel(poseStack, leftPos, topPos, imageWidth, imageHeight)
        GuiSkin.inset(poseStack, leftPos + INSET_X, topPos + INSET_Y, INSET_W, layout.insetHeight)

        drawTitle(poseStack, state)
        drawStatusRow(poseStack, state)
        drawBuffer(poseStack, state)
        drawDevices(poseStack, state, mouseX, mouseY)
        drawNameBar(poseStack)

        font.draw(
            poseStack, Component.translatable("gui.refurbished_eu.control_mode"),
            (leftPos + INSET_X).toFloat(), (topPos + layout.modeLabelY).toFloat(),
            GuiSkin.TEXT_MUTED
        )

        // The item goes last: the item renderer leaves shader state of its own
        // behind, and it draws in front of the fills regardless of call order.
        minecraft?.itemRenderer?.renderAndDecorateFakeItem(
            NAME_TAG, leftPos + NAME_ICON_X, topPos + layout.nameBarY + 1
        )
    }

    private fun drawTitle(poseStack: PoseStack, state: TransformerStatus) {
        RenderSystem.setShader(GameRenderer::getPositionTexShader)
        RenderSystem.setShaderTexture(0, BOLT)
        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()
        RenderSystem.setShaderColor(state.red, state.green, state.blue, 1.0f)
        blit(poseStack, leftPos + 8, topPos + 4, 0f, 0f, BOLT_SIZE, BOLT_SIZE, 16, 16)
        // Left set, the tint would bleed into every later element on the screen.
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f)

        // Clipped rather than allowed to run off the panel: block names come from
        // a language file, and a longer translation must not spill over the edge.
        val shown = fit(title.string, INSET_X + INSET_W - TITLE_X)
        font.draw(poseStack, shown, (leftPos + TITLE_X).toFloat(), (topPos + 5).toFloat(), GuiSkin.TEXT)
    }

    private fun drawStatusRow(poseStack: PoseStack, state: TransformerStatus) {
        val y = topPos + layout.statusY

        // A square as well as a colour: the reading has to survive both a
        // greyscale screenshot and a colourblind player, and the words beside it
        // are what actually carry the meaning.
        GuiSkin.rect(poseStack, leftPos + CONTENT_X, y, DOT, DOT, state.indicator)
        GuiSkin.outline(poseStack, leftPos + CONTENT_X, y, DOT, DOT, GuiSkin.FRAME)

        font.draw(
            poseStack, statusLabel(state),
            (leftPos + CONTENT_X + DOT + 5).toFloat(), y.toFloat(), state.textColour
        )

        // Draw rate belongs with the status, not down with the tier trivia. The
        // "EU/t" is the whole label; spelling out that it is a draw would cost
        // the width that a long status wording on the same row needs.
        val draw = drawReading()
        font.draw(
            poseStack, draw,
            (leftPos + CONTENT_X + CONTENT_W - font.width(draw)).toFloat(), y.toFloat(),
            if (menu.currentDraw > 0) GuiSkin.TEXT else GuiSkin.TEXT_MUTED
        )
    }

    private fun drawReading(): Component =
        Component.translatable("gui.refurbished_eu.draw", menu.currentDraw)

    /**
     * The status shares its row with the draw reading, and translations are free
     * to be longer than the English. Clipping keeps them from colliding; the full
     * wording is in the hover text either way.
     */
    private fun statusLabel(state: TransformerStatus): String = fit(
        state.message.string,
        CONTENT_W - DOT - 5 - font.width(drawReading()) - GAP
    )

    /** Trims text to a pixel width, with an ellipsis when it has to cut. */
    private fun fit(text: String, width: Int): String {
        if (width <= 0) return ""
        if (font.width(text) <= width) return text
        val room = (width - font.width(ELLIPSIS)).coerceAtLeast(0)
        return font.plainSubstrByWidth(text, room) + ELLIPSIS
    }

    private fun drawBuffer(poseStack: PoseStack, state: TransformerStatus) {
        val labelY = (topPos + layout.bufferLabelY).toFloat()
        var labelRoom = CONTENT_W

        // The trend warning rides on the label row, which is otherwise empty
        // space, so warning about undersupply costs the panel no height.
        if (isDraining()) {
            val warning = Component.translatable("gui.refurbished_eu.buffer_draining")
            val warningWidth = font.width(warning)
            val right = leftPos + CONTENT_X + CONTENT_W
            font.draw(
                poseStack, warning, (right - warningWidth).toFloat(), labelY,
                GuiSkin.TEXT_WARN
            )
            GuiSkin.arrowDown(
                poseStack, right - warningWidth - ARROW_W - 3,
                topPos + layout.bufferLabelY + 2, GuiSkin.FILL_WARN
            )
            labelRoom = CONTENT_W - warningWidth - ARROW_W - 3 - GAP
        }

        font.draw(
            poseStack,
            fit(Component.translatable("gui.refurbished_eu.buffer_label").string, labelRoom),
            (leftPos + CONTENT_X).toFloat(), labelY, GuiSkin.TEXT_MUTED
        )
        // The bar keeps the running colour: it reports the level, and the warning
        // beside it reports the trend. One colour cannot mean both.
        GuiSkin.meter(
            poseStack, leftPos + CONTENT_X, topPos + layout.barY, CONTENT_W, METER_H,
            menu.storedEu, menu.bufferMax, meterColour(state), ticks = true
        )
        font.draw(
            poseStack, bufferValue(),
            (leftPos + CONTENT_X).toFloat(), (topPos + layout.bufferValueY).toFloat(),
            GuiSkin.TEXT_MUTED
        )
    }

    /**
     * True while the buffer has been falling for a full second of running.
     *
     * The level and the draw rate are both already on screen, but neither answers
     * the question the player actually has - is the supply keeping up - and a
     * falling buffer is exactly that answer, a good while before it bottoms out
     * into "No EU". Sampled on the client from values ContainerData already
     * syncs, so it costs the server nothing.
     */
    private fun isDraining(): Boolean {
        if (samples < bufferHistory.size) return false
        if (status() != TransformerStatus.RUNNING) return false
        // sampleIndex points at the slot due to be overwritten next: the oldest.
        return menu.storedEu < bufferHistory[sampleIndex]
    }

    private fun meterColour(state: TransformerStatus): Int = when {
        state.isFault -> GuiSkin.FILL_BAD
        state == TransformerStatus.RUNNING -> GuiSkin.FILL_OK
        else -> GuiSkin.FILL_IDLE
    }

    private fun bufferValue(): Component = Component.translatable(
        "gui.refurbished_eu.buffer_value",
        EuFormat.grouped(menu.storedEu), EuFormat.grouped(menu.bufferMax)
    )

    private fun drawDevices(
        poseStack: PoseStack,
        state: TransformerStatus,
        mouseX: Int,
        mouseY: Int
    ) {
        val slots = layout.slots
        val connected = menu.connectedCount
        val overCapacity = connected > slots

        // Occupancy rides on the section label's own row, which is otherwise empty
        // space. Spelling all three numbers out under the grid does not fit the
        // content column once the counts reach two digits, let alone in a
        // translation - so the row carries "connected / slots" as bare numbers,
        // the same shorthand the hover label already uses, and the full sentence
        // lives in the hover text.
        val count = Component.translatable("gui.refurbished_eu.devices_count", connected, slots)
        val countWidth = font.width(count)
        // Amber before red: the last free slot is worth seeing before it is gone,
        // and a tenth of the cap keeps the warning proportional to the tier.
        val headroom = slots - connected
        val countColour = when {
            overCapacity -> GuiSkin.TEXT_BAD
            headroom <= maxOf(1, slots / 10) -> GuiSkin.TEXT_WARN
            else -> GuiSkin.TEXT_MUTED
        }
        font.draw(
            poseStack,
            fit(
                Component.translatable("gui.refurbished_eu.devices_label").string,
                CONTENT_W - countWidth - GAP
            ),
            (leftPos + CONTENT_X).toFloat(), (topPos + layout.devicesLabelY).toFloat(),
            GuiSkin.TEXT_MUTED
        )
        font.draw(
            poseStack, count,
            (leftPos + CONTENT_X + CONTENT_W - countWidth).toFloat(),
            (topPos + layout.devicesLabelY).toFloat(),
            countColour
        )

        if (layout.useGrid) {
            drawDeviceGrid(poseStack, slots, connected, overCapacity, mouseX, mouseY)
        } else {
            // A cap in the hundreds has no countable grid; the meter carries the
            // same proportion and the caption carries the numbers.
            GuiSkin.meter(
                poseStack, leftPos + CONTENT_X, topPos + layout.gridY, CONTENT_W, METER_H,
                connected.coerceAtMost(slots), slots,
                if (overCapacity) GuiSkin.FILL_BAD else meterColour(state)
            )
        }

        // Over capacity, the excess claims the caption row first: being over the
        // cap outranks knowing how many are powered, and the hover text keeps the
        // full picture either way.
        val excess = if (overCapacity) {
            Component.translatable("gui.refurbished_eu.devices_over", connected - slots)
        } else null
        val excessRoom = excess?.let { font.width(it) + GAP } ?: 0

        // With a trustworthy roster the bright squares mean "under power", so the
        // caption leads with that number and carries the server's working count
        // beside it. Without one there is nothing to explain, so it says only what
        // the server told us.
        val mapped = mappedRoster()
        val caption = if (mapped != null && excess == null) {
            Component.translatable(
                "gui.refurbished_eu.devices_summary", mapped.poweredCount, menu.activeCount
            )
        } else {
            Component.translatable("gui.refurbished_eu.devices_working", menu.activeCount)
        }
        font.draw(
            poseStack, fit(caption.string, CONTENT_W - excessRoom),
            (leftPos + CONTENT_X).toFloat(), (topPos + layout.captionY).toFloat(),
            GuiSkin.TEXT_MUTED
        )

        if (excess != null) {
            font.draw(
                poseStack, excess,
                (leftPos + CONTENT_X + CONTENT_W - font.width(excess)).toFloat(),
                (topPos + layout.captionY).toFloat(),
                GuiSkin.TEXT_BAD
            )
        }
    }

    private fun drawDeviceGrid(
        poseStack: PoseStack,
        slots: Int,
        connected: Int,
        over: Boolean,
        mouseX: Int,
        mouseY: Int
    ) {
        val mapped = mappedRoster()
        val active = menu.activeCount.coerceIn(0, slots)
        val filled = connected.coerceIn(0, slots)
        val hovered = cellAt(mouseX, mouseY)

        for (index in 0 until slots) {
            val x = leftPos + CONTENT_X + (index % layout.perRow) * layout.pitch
            val y = topPos + layout.gridY + (index / layout.perRow) * layout.pitch

            // Three states, not two: a device that is connected but unpowered is
            // not the same thing as a free slot.
            //
            // With a roster each square belongs to one device and keeps its place
            // between scans, so the pattern is worth reading. Without one all we
            // can do is fill from the left, which shuffles as devices switch.
            val occupied: Boolean
            val colour: Int
            if (mapped != null) {
                val device = mapped[index]
                occupied = device != null
                colour = when {
                    device == null -> GuiSkin.SLOT_EMPTY
                    device.powered -> GuiSkin.SLOT_ACTIVE
                    else -> GuiSkin.SLOT_CONNECTED
                }
            } else {
                occupied = index < filled
                colour = when {
                    index < active -> GuiSkin.SLOT_ACTIVE
                    index < filled -> GuiSkin.SLOT_CONNECTED
                    else -> GuiSkin.SLOT_EMPTY
                }
            }

            GuiSkin.rect(poseStack, x, y, layout.cell, layout.cell, colour)
            GuiSkin.outline(
                poseStack, x, y, layout.cell, layout.cell,
                if (occupied) GuiSkin.FRAME else GuiSkin.SLOT_EMPTY_BORDER
            )
            // Only squares that can name their device get a hover highlight,
            // so the highlight never promises hover text that will not come.
            if (index == hovered && mapped?.get(index) != null) {
                GuiSkin.outline(
                    poseStack, x - 1, y - 1, layout.cell + 2, layout.cell + 2,
                    GuiSkin.HIGHLIGHT
                )
            }
        }

        // Devices past the cap have no square to live in, so the grid itself gets
        // flagged and the count in the caption turns red.
        if (over) {
            GuiSkin.outline(
                poseStack,
                leftPos + CONTENT_X - 2, topPos + layout.gridY - 2,
                layout.gridWidth + 4, layout.gridHeight + 4,
                GuiSkin.FILL_BAD
            )
        }
    }

    /**
     * The roster, but only when it describes the same set of devices the server
     * counted. Any disagreement and the grid goes back to bare counts rather than
     * drawing a map that names the wrong squares.
     */
    private fun mappedRoster(): DeviceRoster? =
        roster.takeIf { layout.useGrid && it.agreesWith(menu.connectedCount) }

    /** Which grid square a screen position falls in, gaps excluded. */
    private fun cellAt(mouseX: Int, mouseY: Int): Int? {
        if (!layout.useGrid) return null
        val dx = mouseX - (leftPos + CONTENT_X)
        val dy = mouseY - (topPos + layout.gridY)
        if (dx < 0 || dy < 0) return null
        val column = dx / layout.pitch
        val row = dy / layout.pitch
        if (column >= layout.perRow || row >= layout.rows) return null
        // Inside the cell, not in the gap after it.
        if (dx % layout.pitch >= layout.cell || dy % layout.pitch >= layout.cell) return null
        val index = row * layout.perRow + column
        return index.takeIf { it < layout.slots }
    }

    private fun drawNameBar(poseStack: PoseStack) {
        val y = topPos + layout.nameBarY
        GuiSkin.rect(poseStack, leftPos + INSET_X, y, INSET_W, NAME_H, GuiSkin.DARK_BAR)
        GuiSkin.outline(
            poseStack, leftPos + INSET_X, y, INSET_W, NAME_H,
            if (nameField.isFocused) GuiSkin.FILL_OK else GuiSkin.FRAME
        )
    }

    /** The title is drawn in renderBg, in screen space, with everything else. */
    override fun renderLabels(poseStack: PoseStack, mouseX: Int, mouseY: Int) = Unit

    /**
     * Hover text, done by hand rather than through the widgets' own tooltips
     * because a disabled button never reports itself as hovered - and explaining
     * *why* the power button is disabled is the whole point.
     */
    private fun renderHints(poseStack: PoseStack, mouseX: Int, mouseY: Int) {
        val lines = hintLines(mouseX, mouseY)
        if (lines.isNotEmpty()) renderComponentTooltip(poseStack, lines, mouseX, mouseY)
    }

    private fun hintLines(mouseX: Int, mouseY: Int): List<Component> {
        val state = status()

        val statusWidth = DOT + 5 + font.width(statusLabel(state))
        if (over(mouseX, mouseY, CONTENT_X, layout.statusY, statusWidth, DOT)) {
            val lines = mutableListOf(state.message)
            // The row only has room for a short status, so who owns the switch is
            // explained here rather than in the label.
            if (menu.controlMode == ControlMode.REDSTONE) {
                lines += Component.translatable("gui.refurbished_eu.locked_by_redstone")
            }
            lines += Component.translatable("gui.refurbished_eu.tier", menu.sinkTier)
            return lines
        }

        val drawWidth = font.width(drawReading())
        if (over(mouseX, mouseY, CONTENT_X + CONTENT_W - drawWidth, layout.statusY, drawWidth, DOT)) {
            return listOf(
                Component.translatable("gui.refurbished_eu.draw_tooltip", menu.currentDraw)
            )
        }

        if (over(mouseX, mouseY, CONTENT_X, layout.barY, CONTENT_W, METER_H)) {
            val percent = if (menu.bufferMax > 0) menu.storedEu * 100 / menu.bufferMax else 0
            val lines = mutableListOf(
                bufferValue(),
                Component.translatable("gui.refurbished_eu.buffer_percent", percent)
            )
            if (isDraining()) {
                lines += Component.translatable("gui.refurbished_eu.buffer_draining_hint")
            }
            return lines
        }

        // A square that belongs to a device names it. This is the whole point of
        // mapping devices to squares: the grid answers "which one", not just
        // "how many".
        val cell = cellAt(mouseX, mouseY)
        val device = if (cell != null) mappedRoster()?.get(cell) else null
        if (device != null) {
            return listOf(
                device.name,
                Component.translatable(
                    if (device.powered) "gui.refurbished_eu.device_powered"
                    else "gui.refurbished_eu.device_unpowered"
                ),
                Component.translatable(
                    "gui.refurbished_eu.device_position",
                    device.pos.x, device.pos.y, device.pos.z
                )
            )
        }

        // The whole device block, label row through caption: this is where the
        // three counts are spelled out in full, since a tooltip has the room the
        // content column does not.
        val blockHeight = layout.captionY + LINE - layout.devicesLabelY
        if (over(mouseX, mouseY, CONTENT_X, layout.devicesLabelY, CONTENT_W, blockHeight)) {
            val lines = mutableListOf<Component>(
                Component.translatable(
                    "gui.refurbished_eu.devices_tooltip",
                    menu.activeCount, menu.connectedCount, layout.slots
                )
            )
            if (menu.connectedCount > layout.slots) {
                lines += Component.translatable("gui.refurbished_eu.devices_over_hint")
            }
            return lines
        }

        if (menu.controlMode != ControlMode.MANUAL &&
            over(mouseX, mouseY, INSET_X, layout.buttonY, INSET_W, BUTTON_H)
        ) {
            return listOf(Component.translatable("gui.refurbished_eu.locked_by_redstone"))
        }

        val segment = modeSelector?.segmentAt(mouseX.toDouble(), mouseY.toDouble())
        if (segment != null) {
            return listOf(
                Component.translatable(segment.translationKey),
                Component.translatable(segment.translationKey + ".tooltip")
            )
        }

        return emptyList()
    }

    private fun overNameBar(mouseX: Double, mouseY: Double): Boolean =
        over(mouseX.toInt(), mouseY.toInt(), INSET_X, layout.nameBarY, INSET_W, NAME_H)

    private fun over(mouseX: Int, mouseY: Int, x: Int, y: Int, width: Int, height: Int): Boolean =
        mouseX >= leftPos + x && mouseX < leftPos + x + width &&
            mouseY >= topPos + y && mouseY < topPos + y + height

    /**
     * Vertical geometry, derived once per device cap rather than hard-coded,
     * because the slot grid is the only thing on the panel that changes size.
     */
    private class Layout(val slots: Int) {

        val useGrid: Boolean = slots in 1..MAX_GRID_SLOTS

        /**
         * Two cell sizes rather than one, switching at a single row's worth of
         * big cells: 8 slots stay chunky and countable, 16 drop to 7px and still
         * fit one row, and 32 need two. Without the step, a medium transformer
         * would end up a taller panel than a high one.
         */
        val perRow: Int = if (slots <= BIG_CELL_LIMIT) 8 else 16
        val cell: Int = if (slots <= BIG_CELL_LIMIT) 10 else 7
        val pitch: Int = cell + 2
        val rows: Int = if (useGrid) (slots + perRow - 1) / perRow else 0

        val gridHeight: Int = if (useGrid) rows * pitch - 2 else METER_H
        val gridWidth: Int = if (useGrid) minOf(slots, perRow) * pitch - 2 else CONTENT_W

        val statusY: Int
        val bufferLabelY: Int
        val barY: Int
        val bufferValueY: Int
        val devicesLabelY: Int
        val gridY: Int
        val captionY: Int
        val insetHeight: Int
        val nameBarY: Int
        val buttonY: Int
        val modeLabelY: Int
        val modeY: Int
        val height: Int

        init {
            // A label and its own reading are one unit (SNUG); the step between
            // two sections is wider (LOOSE). Tighter than the first pass, which
            // left the panel airier than a vanilla screen.
            var y = INSET_Y + PAD
            statusY = y
            y += LOOSE
            bufferLabelY = y
            y += SNUG
            barY = y
            y += METER_H + 3
            bufferValueY = y
            y += LOOSE
            devicesLabelY = y
            y += SNUG
            gridY = y
            y += gridHeight + 3
            captionY = y
            y += LINE

            insetHeight = y + 4 - INSET_Y
            nameBarY = INSET_Y + insetHeight + PAD
            buttonY = nameBarY + NAME_H + PAD
            modeLabelY = buttonY + BUTTON_H + PAD
            modeY = modeLabelY + LINE + 1
            height = modeY + SEGMENT_H + PAD
        }
    }

    companion object {
        private val BOLT = ResourceLocation("refurbished_eu", "textures/gui/status_bolt.png")
        private val NAME_TAG = ItemStack(Items.NAME_TAG)

        private const val PANEL_W = 176

        // Sunken content area, and the padded column of content inside it.
        private const val INSET_X = 7
        private const val INSET_W = 162
        private const val INSET_Y = 19
        private const val CONTENT_X = 12
        private const val CONTENT_W = 152

        private const val LINE = 9
        private const val BOLT_SIZE = 10
        private const val TITLE_X = 22

        /** Breathing room between a left label and a right-aligned reading. */
        private const val GAP = 6
        private const val ARROW_W = 5

        // Vertical rhythm: inset padding, label-to-reading, section-to-section.
        private const val PAD = 5
        private const val SNUG = 9
        private const val LOOSE = 11

        /** A second of client ticks: long enough that a blip is not a warning. */
        private const val TREND_SAMPLES = 20
        private const val ELLIPSIS = "..."
        private const val DOT = 8
        private const val METER_H = 9
        private const val NAME_H = 18
        private const val BUTTON_H = 20
        private const val SEGMENT_H = 18

        private const val NAME_ICON_X = 9
        private const val NAME_TEXT_X = 29
        private const val NAME_TEXT_W = 135
        private const val NAME_TEXT_DY = 5

        /**
         * Above this the squares stop being countable, so the grid gives way to a
         * meter. All three default caps (8/16/32) stay inside it; only a
         * hand-edited config falls through.
         */
        private const val MAX_GRID_SLOTS = 32
        private const val BIG_CELL_LIMIT = 8
    }
}
