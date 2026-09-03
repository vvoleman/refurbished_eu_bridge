package dev.vvoleman.refurbishedeu.mail

import net.minecraft.DetectedVersion
import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.core.Registry
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.Level
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
    private val theNether =
        ResourceKey.create(Registry.DIMENSION_REGISTRY, ResourceLocation("minecraft:the_nether"))

    private fun ref(name: String?, pos: BlockPos, level: ResourceKey<Level> = overworld) =
        MailboxRef(UUID.randomUUID(), pos, level, name)

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

    @Test
    fun `resolveDestination prefers the origin's dimension over a nearer match elsewhere`() {
        val origin = ref("Alice", BlockPos.ZERO, overworld)
        // Same name, much closer in raw coordinates, but in a different dimension -
        // must lose to the farther same-dimension match, not win on distance alone.
        val nearerButWrongDimension = ref("Depot", BlockPos(5, 64, 0), theNether)
        val fartherButRightDimension = ref("Depot", BlockPos(500, 64, 0), overworld)
        val refs = listOf(origin, nearerButWrongDimension, fartherButRightDimension)

        assertEquals(
            fartherButRightDimension,
            MailboxIndex.resolveDestination(refs, origin, "Depot"),
        )
    }

    @Test
    fun `resolveDestination never routes a mailbox to itself`() {
        val origin = ref("Alice", BlockPos.ZERO, overworld)
        assertNull(MailboxIndex.resolveDestination(listOf(origin), origin, "Alice"))
    }

    @Test
    fun `resolveDestination resolves nothing when no match exists in the origin's dimension`() {
        val origin = ref("Alice", BlockPos.ZERO, overworld)
        val wrongDimension = ref("Depot", BlockPos(5, 64, 0), theNether)
        assertNull(MailboxIndex.resolveDestination(listOf(origin, wrongDimension), origin, "Depot"))
    }
}
