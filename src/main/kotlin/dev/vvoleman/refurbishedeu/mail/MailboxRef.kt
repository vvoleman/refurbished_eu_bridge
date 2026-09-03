package dev.vvoleman.refurbishedeu.mail

import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level
import java.util.UUID

/** One mailbox as the mail system needs to see it: where it is and what it's called. */
data class MailboxRef(
    val id: UUID,
    val pos: BlockPos,
    val level: ResourceKey<Level>,
    val name: String?,
)
