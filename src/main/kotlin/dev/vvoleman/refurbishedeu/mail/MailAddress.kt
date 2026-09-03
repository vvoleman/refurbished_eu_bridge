package dev.vvoleman.refurbishedeu.mail

import net.minecraft.nbt.CompoundTag
import net.minecraft.world.item.ItemStack
import java.util.UUID

/**
 * The addressing tag shared by Letter and Parcel.
 *
 * Deliberately ours rather than Refurbished's: their PackageItem addresses a
 * mailbox by UUID through the Post Box UI, which is the instant path. Ours
 * addresses by name so a player can write a letter without a Post Box.
 */
object MailAddress {

    private const val ROOT = "RefurbishedEuMail"
    private const val TARGET = "Target"
    private const val SENDER = "Sender"
    private const val RETURNED_FROM = "ReturnedFrom"

    fun apply(stack: ItemStack, target: String, sender: UUID) {
        val root = CompoundTag()
        root.putString(TARGET, target)
        root.putUUID(SENDER, sender)
        stack.getOrCreateTag().put(ROOT, root)
    }

    fun target(stack: ItemStack): String? {
        val root = stack.tag?.getCompound(ROOT) ?: return null
        val value = root.getString(TARGET)
        return value.ifBlank { null }
    }

    fun sender(stack: ItemStack): UUID? {
        val root = stack.tag?.getCompound(ROOT) ?: return null
        return if (root.hasUUID(SENDER)) root.getUUID(SENDER) else null
    }

    fun isAddressed(stack: ItemStack): Boolean = target(stack) != null

    /**
     * Records the target a delivery failed to reach and was carried back from.
     *
     * Keyed on the target string itself, not a bare boolean: the sweep skips a
     * stack whose [returnedFrom] still equals its current target, but a player
     * who re-addresses the stack (anvil rename, or editing a parcel) changes
     * the target that is compared against, so the stale value simply stops
     * matching and the stack becomes sweepable again - no separate clearing
     * hook needed.
     *
     * Uses [ItemStack.getOrCreateTagElement] rather than [apply]'s
     * whole-tag replacement so this can be stamped onto a stack that already
     * carries a Target/Sender pair without disturbing either.
     */
    fun markReturned(stack: ItemStack, target: String) {
        stack.getOrCreateTagElement(ROOT).putString(RETURNED_FROM, target)
    }

    fun returnedFrom(stack: ItemStack): String? {
        val root = stack.tag?.getCompound(ROOT) ?: return null
        if (!root.contains(RETURNED_FROM)) return null
        return root.getString(RETURNED_FROM).ifBlank { null }
    }
}
