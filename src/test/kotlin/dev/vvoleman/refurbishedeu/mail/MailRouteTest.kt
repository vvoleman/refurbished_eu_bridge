package dev.vvoleman.refurbishedeu.mail

import net.minecraft.DetectedVersion
import net.minecraft.SharedConstants
import net.minecraft.core.Registry
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.Bootstrap
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.util.UUID

class MailRouteTest {
    companion object {
        @JvmStatic
        @BeforeAll
        fun bootstrap() {
            SharedConstants.setVersion(DetectedVersion.BUILT_IN)
            Bootstrap.bootStrap()
        }
    }

    private val overworld =
        ResourceKey.create(Registry.DIMENSION_REGISTRY, ResourceLocation("minecraft:overworld"))

    private fun route() = MailRoute(
        id = UUID.fromString("00000000-0000-0000-0000-000000000001"),
        stack = ItemStack(Items.PAPER, 1),
        originId = UUID.fromString("00000000-0000-0000-0000-00000000000a"),
        targetId = UUID.fromString("00000000-0000-0000-0000-00000000000b"),
        level = overworld,
        pos = Vec3(1.0, 64.0, 2.0),
        state = RouteState.TRAVELLING,
    )

    @Test
    fun `a route round trips through nbt`() {
        val restored = MailRoute.load(route().save())!!
        assertEquals(route().id, restored.id)
        assertEquals(route().originId, restored.originId)
        assertEquals(route().targetId, restored.targetId)
        assertEquals(route().pos, restored.pos)
        assertEquals(RouteState.TRAVELLING, restored.state)
        assertEquals(overworld, restored.level)
        assertEquals(Items.PAPER, restored.stack.item)
    }

    @Test
    fun `state survives the round trip`() {
        val returning = route().also { it.state = RouteState.RETURNING }
        assertEquals(RouteState.RETURNING, MailRoute.load(returning.save())!!.state)
    }

    @Test
    fun `an unparseable route loads as null rather than throwing`() {
        assertNull(MailRoute.load(net.minecraft.nbt.CompoundTag()))
    }
}
