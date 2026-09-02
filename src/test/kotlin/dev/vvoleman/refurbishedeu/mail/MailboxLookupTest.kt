package dev.vvoleman.refurbishedeu.mail

import net.minecraft.DetectedVersion
import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.core.Registry
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.Bootstrap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.util.UUID

class MailboxLookupTest {

    companion object {
        // Touching Registry.DIMENSION_REGISTRY throws "Not bootstrapped" without this.
        @JvmStatic
        @BeforeAll
        fun bootstrap() {
            SharedConstants.setVersion(DetectedVersion.BUILT_IN)
            Bootstrap.bootStrap()
        }
    }

    private val overworld =
        ResourceKey.create(Registry.DIMENSION_REGISTRY, ResourceLocation("minecraft:overworld"))

    private fun ref(name: String?, pos: BlockPos) =
        MailboxRef(UUID.randomUUID(), pos, overworld, name)

    @Test
    fun `finds a mailbox by exact name`() {
        val target = ref("Town Hall", BlockPos(100, 64, 0))
        val refs = listOf(ref("Shop", BlockPos.ZERO), target)
        assertEquals(target, MailboxIndex.byName(refs, "Town Hall", BlockPos.ZERO))
    }

    @Test
    fun `name matching ignores case and surrounding space`() {
        val target = ref("Town Hall", BlockPos(100, 64, 0))
        assertEquals(target, MailboxIndex.byName(listOf(target), "  town hall ", BlockPos.ZERO))
    }

    @Test
    fun `duplicate names resolve to the nearest`() {
        val near = ref("Depot", BlockPos(10, 64, 0))
        val far = ref("Depot", BlockPos(900, 64, 0))
        assertEquals(near, MailboxIndex.byName(listOf(far, near), "Depot", BlockPos.ZERO))
    }

    @Test
    fun `an unknown name resolves to nothing`() {
        assertNull(MailboxIndex.byName(listOf(ref("Shop", BlockPos.ZERO)), "Nowhere", BlockPos.ZERO))
    }

    @Test
    fun `unnamed mailboxes are never addressable`() {
        assertNull(MailboxIndex.byName(listOf(ref(null, BlockPos.ZERO)), "", BlockPos.ZERO))
        assertEquals(emptyList<MailboxRef>(), MailboxIndex.named(listOf(ref(null, BlockPos.ZERO))))
    }
}
