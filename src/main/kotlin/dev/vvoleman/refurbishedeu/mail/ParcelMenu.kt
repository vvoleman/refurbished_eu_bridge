package dev.vvoleman.refurbishedeu.mail

import net.minecraft.network.chat.Component
import net.minecraft.world.MenuProvider
import net.minecraft.world.SimpleContainer
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack
import dev.vvoleman.refurbishedeu.RefurbishedEuBridge

class ParcelMenu(
    containerId: Int,
    playerInventory: Inventory,
    private val parcel: ItemStack,
    private val contents: SimpleContainer,
) : AbstractContainerMenu(RefurbishedEuBridge.PARCEL_MENU.get(), containerId) {

    init {
        for (col in 0 until ParcelItem.SIZE) {
            addSlot(Slot(contents, col, 8 + col * 18, 20))
        }
        for (row in 0 until 3) {
            for (col in 0 until 9) {
                addSlot(Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, 51 + row * 18))
            }
        }
        for (col in 0 until 9) {
            addSlot(Slot(playerInventory, col, 8 + col * 18, 109))
        }
    }

    /** The parcel is held in hand, so it must not be movable while its own screen is open. */
    override fun quickMoveStack(player: Player, index: Int): ItemStack = ItemStack.EMPTY

    override fun stillValid(player: Player): Boolean = player.isAlive

    override fun removed(player: Player) {
        super.removed(player)
        ParcelItem.store(parcel, contents)
    }

    companion object {
        fun provider(stack: ItemStack, hand: net.minecraft.world.InteractionHand): MenuProvider =
            object : MenuProvider {
                override fun getDisplayName(): Component = stack.hoverName
                override fun createMenu(id: Int, inv: Inventory, player: Player): AbstractContainerMenu =
                    ParcelMenu(id, inv, stack, ParcelItem.asContainer(stack))
            }
    }
}
