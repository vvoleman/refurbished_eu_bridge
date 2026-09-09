package dev.vvoleman.refurbishedeu

import net.minecraft.core.BlockPos
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ContainerData
import net.minecraft.world.inventory.SimpleContainerData
import net.minecraft.world.item.ItemStack

/**
 * Slotless menu: the transformer has no inventory, it only reports numbers and
 * takes a single on/off button. State reaches the client through ContainerData
 * (vanilla's short-sized sync), and the button comes back via clickMenuButton,
 * so no custom packets are needed.
 */
class TransformerMenu(
    containerId: Int,
    playerInventory: Inventory,
    private val blockEntity: TransformerBlockEntity?,
    private val data: ContainerData,
    /** Lets the client screen find the block entity, which carries the name. */
    val blockPos: BlockPos
) : AbstractContainerMenu(RefurbishedEuBridge.EU_TRANSFORMER_MENU.get(), containerId) {

    /** Client-side constructor: no block entity, dummy data that the server fills. */
    constructor(containerId: Int, playerInventory: Inventory, pos: BlockPos) :
        this(containerId, playerInventory, null, SimpleContainerData(TransformerBlockEntity.DATA_SIZE), pos)

    init {
        addDataSlots(data)
    }

    val storedEu: Int get() = data[TransformerBlockEntity.DATA_STORED_EU]
    val connectedCount: Int get() = data[TransformerBlockEntity.DATA_CONNECTED]
    val activeCount: Int get() = data[TransformerBlockEntity.DATA_ACTIVE]
    val isEnabled: Boolean get() = data[TransformerBlockEntity.DATA_ENABLED] != 0
    val isOverloaded: Boolean get() = data[TransformerBlockEntity.DATA_OVERLOADED] != 0
    val currentDraw: Int get() = data[TransformerBlockEntity.DATA_DRAW]
    val bufferMax: Int get() = data[TransformerBlockEntity.DATA_BUFFER_MAX]
    val sinkTier: Int get() = data[TransformerBlockEntity.DATA_TIER]
    val maxDevices: Int get() = data[TransformerBlockEntity.DATA_MAX_DEVICES]
    val controlMode: ControlMode
        get() = ControlMode.byOrdinal(data[TransformerBlockEntity.DATA_CONTROL_MODE])

    override fun clickMenuButton(player: Player, id: Int): Boolean = when (id) {
        BUTTON_TOGGLE_POWER -> {
            // Refused outright under redstone control - the client greys the button
            // out, but a hand-crafted packet must not get past it either.
            blockEntity?.togglePower()
            true
        }
        BUTTON_CYCLE_MODE -> {
            blockEntity?.cycleControlMode()
            true
        }
        else -> selectMode(id)
    }

    /**
     * The segmented control names the mode it wants instead of cycling to it.
     * Cycling is one click while there are two modes and a race as soon as there
     * are three, and it also means two quick clicks cannot land on a mode nobody
     * asked for.
     */
    private fun selectMode(id: Int): Boolean {
        val mode = ControlMode.values().getOrNull(id - BUTTON_MODE_BASE) ?: return false
        blockEntity?.setControlMode(mode)
        return true
    }

    override fun stillValid(player: Player): Boolean {
        val be = blockEntity ?: return true
        return !be.isRemoved && player.distanceToSqr(
            be.blockPos.x + 0.5, be.blockPos.y + 0.5, be.blockPos.z + 0.5
        ) <= 64.0
    }

    /** No slots, so nothing can ever be shift-clicked. */
    override fun quickMoveStack(player: Player, index: Int): ItemStack = ItemStack.EMPTY

    companion object {
        const val BUTTON_TOGGLE_POWER = 0

        /** Kept for the Home Control app and anything else that just wants "next". */
        const val BUTTON_CYCLE_MODE = 1

        /** Ids from here up name a ControlMode by ordinal. */
        private const val BUTTON_MODE_BASE = 10

        fun buttonForMode(mode: ControlMode): Int = BUTTON_MODE_BASE + mode.ordinal
    }
}
