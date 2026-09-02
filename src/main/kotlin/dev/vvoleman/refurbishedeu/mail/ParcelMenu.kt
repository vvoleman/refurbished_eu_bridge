package dev.vvoleman.refurbishedeu.mail

import net.minecraft.network.chat.Component
import net.minecraft.world.MenuProvider
import net.minecraft.world.SimpleContainer
import net.minecraft.world.Container
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ClickType
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack
import dev.vvoleman.refurbishedeu.RefurbishedEuBridge

class ParcelMenu(
    containerId: Int,
    playerInventory: Inventory,
    private val parcel: ItemStack,
    private val contents: SimpleContainer,
) : AbstractContainerMenu(RefurbishedEuBridge.PARCEL_MENU.get(), containerId) {

    /**
     * The parcel is held in the main hand, so it sits in this exact hotbar slot
     * (identified by index, not by comparing ItemStack identity) for as long as
     * this screen is open.
     */
    private val heldHotbarIndex = playerInventory.selected

    /** Local slot-list index of the hotbar slot computed above, once slots are added below. */
    private val heldSlotId = ParcelItem.SIZE + 27 + heldHotbarIndex

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
            if (col == heldHotbarIndex) {
                // Still rendered in its normal hotbar position; just refuses to give up the parcel.
                addSlot(HeldParcelSlot(playerInventory, col, 8 + col * 18, 109))
            } else {
                addSlot(Slot(playerInventory, col, 8 + col * 18, 109))
            }
        }
    }

    /**
     * Vanilla's number-key swap (ClickType.SWAP) reads and writes the player's real
     * inventory directly by index, bypassing this menu's Slot objects entirely on the
     * hotbar side - so the HeldParcelSlot guard below can't stop it by itself. Refusing
     * any swap targeting the held hotbar index closes that path too.
     */
    override fun clicked(slotId: Int, button: Int, clickType: ClickType, player: Player) {
        if (slotId == heldSlotId) return
        if (clickType == ClickType.SWAP && button == heldHotbarIndex) return
        super.clicked(slotId, button, clickType, player)
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

/**
 * The hotbar slot that actually holds the open parcel. It renders and reports its
 * contents exactly like a normal slot, but refuses to give the parcel up (or accept a
 * replacement) while the screen is open - otherwise a plain click could drop the parcel
 * into its own cargo hold, where it would serialize itself into its own NBT and vanish.
 */
private class HeldParcelSlot(container: Container, index: Int, x: Int, y: Int) : Slot(container, index, x, y) {
    override fun mayPickup(player: Player): Boolean = false
    override fun mayPlace(stack: ItemStack): Boolean = false
}
