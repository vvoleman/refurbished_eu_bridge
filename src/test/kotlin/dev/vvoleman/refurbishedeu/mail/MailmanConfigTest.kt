package dev.vvoleman.refurbishedeu.mail

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The accessors fall back to their defaults when the spec is not loaded, which
 * is always the case in a unit test. That fallback is what the rest of the
 * suite leans on, so it is worth pinning.
 */
class MailmanConfigTest {

    @Test
    fun `boat settings fall back to their documented defaults`() {
        assertTrue(MailmanConfig.useBoats())
        assertEquals(6, MailmanConfig.minWaterCrossingWidth())
        assertEquals(600, MailmanConfig.boatCrossingTimeoutTicks())
    }

    @Test
    fun `the crossing timeout is shorter than the stall timeout`() {
        // A stuck boat must be abandoned while the route still has budget to
        // swim, rather than the route dying with a boat under it.
        assertTrue(MailmanConfig.boatCrossingTimeoutTicks() < MailmanConfig.stallTimeoutTicks())
    }
}
