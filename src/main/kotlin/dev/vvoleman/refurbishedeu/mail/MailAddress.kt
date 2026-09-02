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
}
