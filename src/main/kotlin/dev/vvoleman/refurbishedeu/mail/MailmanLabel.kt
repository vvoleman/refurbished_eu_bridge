package dev.vvoleman.refurbishedeu.mail

import net.minecraft.core.BlockPos

/**
 * The text shown above a materialised mailman's head.
 *
 * Kept as a pure string builder rather than living in MailRouteService so the
 * wording can be tested without a Minecraft bootstrap - the same reason
 * WaterCrossing takes an injected sampler instead of a Level.
 *
 * The direction carries real information: a route flipped to RETURNING walks
 * back to the ORIGIN mailbox, so without it a mailman on its way to "Home" and
 * one carrying undelivered mail back to "Home" would read identically, and the
 * second one looks like a bug.
 */
object MailmanLabel {

    /** Heading out to [mailbox] with mail to deliver. */
    private const val OUTBOUND = "→"

    /** Carrying undelivered mail back to [mailbox], the origin. */
    private const val RETURNING = "↩"

    /**
     * @param mailbox the destination's name, or null when it has none. Only the
     *  TARGET is required to be named - MailboxIndex.byName cannot resolve an
     *  unnamed one - but the ORIGIN is simply wherever the addressed stack was
     *  found, so a RETURNING route can be walking back to an unnamed mailbox.
     * @param pos where that mailbox is, shown only when it has no name.
     */
    fun text(mailbox: String?, pos: BlockPos, returning: Boolean): String {
        val arrow = if (returning) RETURNING else OUTBOUND
        return "$arrow ${mailbox ?: "${pos.x}, ${pos.y}, ${pos.z}"}"
    }
}
