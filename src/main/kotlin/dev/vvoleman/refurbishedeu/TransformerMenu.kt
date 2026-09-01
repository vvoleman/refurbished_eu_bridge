package dev.vvoleman.refurbishedeu

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
    private val data: ContainerData
) : AbstractContainerMenu(RefurbishedEuBridge.EU_TRANSFORMER_MENU.get(), containerId) {

    /** Client-side constructor: no block entity, dummy data that the server fills. */
    constructor(containerId: Int, playerInventory: Inventory) :
        this(containerId, playerInventory, null, SimpleContainerData(TransformerBlockEntity.DATA_SIZE))

    init {
        addDataSlots(data)
    }

    val storedEu: Int get() = data[TransformerBlockEntity.DATA_STORED_EU]
    val connectedCount: Int get() = data[TransformerBlockEntity.DATA_CONNECTED]
    val activeCount: Int get() = data[TransformerBlockEntity.DATA_ACTIVE]
    val isEnabled: Boolean get() = data[TransformerBlockEntity.DATA_ENABLED] != 0
    val isOverloaded: Boolean get() = data[TransformerBlockEntity.DATA_OVERLOADED] != 0
    val currentDraw: Int get() = data[TransformerBlockEntity.DATA_DRAW]

    override fun clickMenuButton(player: Player, id: Int): Boolean {
        if (id == BUTTON_TOGGLE_POWER) {
            blockEntity?.togglePower()
            return true
        }
        return false
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
    }
}
