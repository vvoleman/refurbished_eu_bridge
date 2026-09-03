package dev.vvoleman.refurbishedeu.mail

import net.minecraft.DetectedVersion
import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.util.UUID

class MailAddressTest {
    companion object {
        @JvmStatic
        @BeforeAll
        fun bootstrap() {
            SharedConstants.setVersion(DetectedVersion.BUILT_IN)
            Bootstrap.bootStrap()
        }
    }

    private val sender = UUID.fromString("00000000-0000-0000-0000-0000000000aa")

    @Test
    fun `a bare stack is not addressed`() {
        val stack = ItemStack(Items.PAPER)
        assertFalse(MailAddress.isAddressed(stack))
        assertNull(MailAddress.target(stack))
        assertNull(MailAddress.sender(stack))
    }

    @Test
    fun `apply then read round trips`() {
        val stack = ItemStack(Items.PAPER)
        MailAddress.apply(stack, "Town Hall", sender)
        assertTrue(MailAddress.isAddressed(stack))
        assertEquals("Town Hall", MailAddress.target(stack))
        assertEquals(sender, MailAddress.sender(stack))
    }

    @Test
    fun `a blank target is not an address`() {
        val stack = ItemStack(Items.PAPER)
        MailAddress.apply(stack, "   ", sender)
        assertFalse(MailAddress.isAddressed(stack))
    }

    @Test
    fun `a fresh stack has no returned-from marker`() {
        val stack = ItemStack(Items.PAPER)
        assertNull(MailAddress.returnedFrom(stack))
    }

    @Test
    fun `markReturned then read round trips`() {
        val stack = ItemStack(Items.PAPER)
        MailAddress.markReturned(stack, "Bob")
        assertEquals("Bob", MailAddress.returnedFrom(stack))
    }

    @Test
    fun `markReturned does not disturb an existing address`() {
        val stack = ItemStack(Items.PAPER)
        MailAddress.apply(stack, "Bob", sender)
        MailAddress.markReturned(stack, "Bob")
        assertEquals("Bob", MailAddress.target(stack))
        assertEquals(sender, MailAddress.sender(stack))
        assertEquals("Bob", MailAddress.returnedFrom(stack))
    }
}
