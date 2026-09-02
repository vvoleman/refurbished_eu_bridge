package dev.vvoleman.refurbishedeu.mail

import net.minecraft.DetectedVersion
import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.server.Bootstrap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.UUID

class MailboxIndexTest {

    companion object {
        private var bootstrapped = false
    }

    init {
        if (!bootstrapped) {
            SharedConstants.setVersion(DetectedVersion.BUILT_IN)
            Bootstrap.bootStrap()
            bootstrapped = true
        }
    }

    private val idA = UUID.fromString("00000000-0000-0000-0000-00000000000a")
    private val idB = UUID.fromString("00000000-0000-0000-0000-00000000000b")

    /** Mirrors what DeliveryService.save() writes, per the spec. */
    private fun mailboxTag(id: UUID, level: String, pos: BlockPos, name: String?): CompoundTag {
        val tag = CompoundTag()
        tag.putUUID("UUID", id)
        tag.putString("Level", level)
        tag.putLong("BlockPosition", pos.asLong())
        if (name != null) tag.putString("CustomName", name)
        return tag
    }

    private fun serviceTag(vararg boxes: CompoundTag): CompoundTag {
        val list = ListTag()
        boxes.forEach { list.add(it) }
        val root = CompoundTag()
        root.put("Mailboxes", list)
        return root
    }

    @Test
    fun `parses a named mailbox`() {
        val tag = serviceTag(
            mailboxTag(idA, "minecraft:overworld", BlockPos(10, 64, -20), "Town Hall")
        )
        val refs = MailboxIndex.parse(tag)
        assertEquals(1, refs.size)
        assertEquals(idA, refs[0].id)
        assertEquals(BlockPos(10, 64, -20), refs[0].pos)
        assertEquals("Town Hall", refs[0].name)
        assertEquals("minecraft:overworld", refs[0].level.location().toString())
    }

    @Test
    fun `an unnamed mailbox parses with a null name`() {
        val tag = serviceTag(mailboxTag(idB, "minecraft:overworld", BlockPos.ZERO, null))
        assertNull(MailboxIndex.parse(tag)[0].name)
    }

    @Test
    fun `an empty service yields nothing`() {
        assertEquals(emptyList<MailboxRef>(), MailboxIndex.parse(CompoundTag()))
    }

    @Test
    fun `a malformed entry is skipped rather than throwing`() {
        val broken = CompoundTag().also { it.putString("Level", "minecraft:overworld") }
        val tag = serviceTag(broken, mailboxTag(idA, "minecraft:overworld", BlockPos.ZERO, "Ok"))
        val refs = MailboxIndex.parse(tag)
        assertEquals(1, refs.size)
        assertEquals("Ok", refs[0].name)
    }
}
