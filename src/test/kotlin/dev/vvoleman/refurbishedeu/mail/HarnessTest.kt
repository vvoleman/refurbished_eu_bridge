package dev.vvoleman.refurbishedeu.mail

import net.minecraft.DetectedVersion
import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.Bootstrap
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class HarnessTest {
    companion object {
        @JvmStatic
        @BeforeAll
        fun bootstrap() {
            SharedConstants.setVersion(DetectedVersion.BUILT_IN)
            Bootstrap.bootStrap()
        }
    }

    @Test
    fun `plain nbt works without registries`() {
        val tag = CompoundTag()
        tag.putLong("BlockPosition", BlockPos(1, 2, 3).asLong())
        assertEquals(BlockPos(1, 2, 3), BlockPos.of(tag.getLong("BlockPosition")))
    }

    @Test
    fun `itemstack round trips after bootstrap`() {
        val stack = ItemStack(Items.PAPER, 1)
        val restored = ItemStack.of(stack.save(CompoundTag()))
        assertEquals(Items.PAPER, restored.item)
    }
}
