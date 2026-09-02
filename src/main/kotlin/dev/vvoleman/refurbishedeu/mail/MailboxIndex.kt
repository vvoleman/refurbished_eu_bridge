package dev.vvoleman.refurbishedeu.mail

import net.minecraft.core.BlockPos
import net.minecraft.core.Registry
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.Level

/**
 * The world's mailboxes, read out of Refurbished's DeliveryService.
 *
 * DeliveryService keeps its mailbox map private, and the only public
 * enumeration - encodeMailboxes/decodeMailboxes - downgrades to IMailbox, which
 * has no position and so cannot be walked to. save() is public and writes every
 * mailbox with its position, and only reads the map to do it, so calling it on a
 * scratch tag is a safe way to enumerate. No reflection, no mixin.
 */
object MailboxIndex {

    fun parse(tag: CompoundTag): List<MailboxRef> =
        tag.getList("Mailboxes", Tag.TAG_COMPOUND.toInt()).mapNotNull { entry ->
            parseOne(entry as CompoundTag)
        }

    /**
     * A mailbox we can't make sense of is skipped, not fatal: this tag comes
     * from another mod and one bad entry must not take out the whole index.
     */
    private fun parseOne(tag: CompoundTag): MailboxRef? {
        if (!tag.hasUUID("UUID")) return null
        if (!tag.contains("BlockPosition")) return null
        val levelId = ResourceLocation.tryParse(tag.getString("Level")) ?: return null
        val name = if (tag.contains("CustomName")) tag.getString("CustomName").ifBlank { null } else null
        return MailboxRef(
            id = tag.getUUID("UUID"),
            pos = BlockPos.of(tag.getLong("BlockPosition")),
            level = ResourceKey.create(Registry.DIMENSION_REGISTRY, levelId),
            name = name,
        )
    }
}
