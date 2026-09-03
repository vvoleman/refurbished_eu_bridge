package dev.vvoleman.refurbishedeu.mail

import net.minecraft.core.BlockPos
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class MailmanLabelTest {

    /** The label only reads this when the mailbox has no name. */
    private val anywhere = BlockPos(0, 64, 0)

    @Test
    fun `marks an outbound delivery with the mailbox it is heading to`() {
        assertEquals("→ Bakery", MailmanLabel.text("Bakery", anywhere, returning = false))
    }

    @Test
    fun `marks a returning delivery with the mailbox it is heading back to`() {
        assertEquals("↩ Home", MailmanLabel.text("Home", anywhere, returning = true))
    }

    /**
     * The whole point of carrying the direction: a route flipped to RETURNING
     * walks to the ORIGIN, so a mailman heading to "Home" outbound and one
     * carrying undelivered mail back to "Home" must not read identically.
     */
    @Test
    fun `distinguishes the two directions for the same mailbox`() {
        assertNotEquals(
            MailmanLabel.text("Home", anywhere, returning = false),
            MailmanLabel.text("Home", anywhere, returning = true),
        )
    }

    /**
     * Refurbished lets a player name a mailbox anything, and MailboxIndex only
     * requires the name to be non-blank. Whatever they chose is reproduced
     * verbatim - the label decorates the name, it never edits it.
     */
    @Test
    fun `reproduces the mailbox name verbatim`() {
        assertEquals("→ ✉ bob's box (2)", MailmanLabel.text("✉ bob's box (2)", anywhere, returning = false))
    }

    /**
     * Only the TARGET has to be named - MailboxIndex.byName can only resolve a
     * named mailbox. The ORIGIN is just wherever the addressed stack was
     * sitting (MailRouteService:194 reads origin.name as nullable), so a route
     * that flips to RETURNING can be walking back to an unnamed mailbox. Fall
     * back to its coordinates rather than showing a bare arrow.
     */
    @Test
    fun `falls back to coordinates for an unnamed mailbox`() {
        assertEquals("↩ 100, 64, -20", MailmanLabel.text(null, BlockPos(100, 64, -20), returning = true))
    }
}
