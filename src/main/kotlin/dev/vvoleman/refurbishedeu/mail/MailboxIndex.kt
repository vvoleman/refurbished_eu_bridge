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

    /** Only named mailboxes can be addressed; the rest are invisible to the mail system. */
    fun named(refs: List<MailboxRef>): List<MailboxRef> = refs.filter { it.name != null }

    /**
     * Refurbished allows duplicate mailbox names, so this can be genuinely
     * ambiguous. Nearest to the sender wins, which is both predictable and
     * usually what was meant.
     */
    fun byName(refs: List<MailboxRef>, name: String, from: BlockPos): MailboxRef? {
        val wanted = name.trim().lowercase()
        if (wanted.isEmpty()) return null
        return named(refs)
            .filter { it.name!!.trim().lowercase() == wanted }
            .minByOrNull { it.pos.distSqr(from) }
    }

    /**
     * Resolves the mailbox a sweep should route [target] to from [origin]: restricted
     * to origin's dimension before matching by name, and never origin itself.
     *
     * The dimension restriction has to happen before [byName] runs, not after -
     * byName picks the nearest match by raw block position with no idea that two
     * different dimensions even exist, so a same-named mailbox in another
     * dimension can otherwise win on a meaningless coordinate distance even
     * though a valid same-dimension match exists, and the mail is then routed
     * nowhere reachable.
     */
    fun resolveDestination(refs: List<MailboxRef>, origin: MailboxRef, target: String): MailboxRef? {
        val local = refs.filter { it.level == origin.level }
        val destination = byName(local, target, origin.pos) ?: return null
        return destination.takeIf { it.id != origin.id }
    }
}
