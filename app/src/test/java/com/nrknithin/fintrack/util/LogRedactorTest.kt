package com.nrknithin.fintrack.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Spec 04 §4.6 requires the log redactor to be unit-tested; spec 11's AC-11-04 is what it
 * is tested *for* — `adb logcat` during a sync must carry no amount and no account number.
 *
 * Every string here is synthetic. Amounts are invented, last-4s are the `1234` spec 06
 * §6.2 mandates, and the 16-digit PAN is assembled from a repeated `1234` rather than
 * written out, so no 16-digit run exists in this source file.
 */
class LogRedactorTest {

    // --- the five cases the gate names -----------------------------------------------

    @Test
    fun `amount with thousands separators is replaced`() {
        assertEquals(
            "Debited # from account",
            LogRedactor.redact("Debited 1,23,456.78 from account")
        )
    }

    @Test
    fun `amount without thousands separators is replaced`() {
        assertEquals(
            "Debited # from account",
            LogRedactor.redact("Debited 250.00 from account")
        )
    }

    @Test
    fun `bare four-digit group is replaced with four bullets`() {
        assertEquals("A/c •••• debited", LogRedactor.redact("A/c 1234 debited"))
    }

    @Test
    fun `masked last four keeps its mask and loses its digits`() {
        assertEquals("card **••••", LogRedactor.redact("card **1234"))
    }

    @Test
    fun `sixteen digit card number is fully replaced`() {
        // Built from a repeated synthetic group so the source tree holds no 16-digit run
        // for PiiLintTest's "full card number" rule to find, should app/…/util ever be
        // added to its fork-owned paths.
        val pan = "1234".repeat(4)

        assertEquals("•".repeat(16), LogRedactor.redact(pan))
    }

    @Test
    fun `line with neither shape is returned unchanged`() {
        val line = "EmailSyncWorker: connected, folder open, resuming from watermark"

        assertEquals(line, LogRedactor.redact(line))
    }

    // --- the ordering decision commented in LogRedactor.redact ------------------------

    @Test
    fun `amount whose integer part is four digits is not split by the digit rule`() {
        // Applying the spec's listed order literally (\d{4} first) yields "••••5.67",
        // which leaks the tail of the amount. Amounts must be redacted first.
        assertEquals("paid #", LogRedactor.redact("paid 12345.67"))
    }

    @Test
    fun `both shapes on one line are both replaced`() {
        assertEquals(
            "A/c •••• debited by # on 12 Oct",
            LogRedactor.redact("A/c 1234 debited by 4,500.00 on 12 Oct")
        )
    }

    // --- shape and stability ----------------------------------------------------------

    @Test
    fun `redaction is idempotent`() {
        val once = LogRedactor.redact("A/c 1234 debited by 4,500.00")

        assertEquals(once, LogRedactor.redact(once))
    }

    @Test
    fun `empty line is returned unchanged`() {
        assertEquals("", LogRedactor.redact(""))
    }

    @Test
    fun `digit runs shorter than four survive`() {
        // Deliberate: a counter or a batch size is not personal data, and the spec's rule
        // starts at four digits.
        assertEquals("batch 12 of 345", LogRedactor.redact("batch 12 of 345"))
    }

    @Test
    fun `amount carrying a currency symbol loses only the number`() {
        assertEquals("₹#", LogRedactor.redact("₹1,299.00"))
    }

    @Test
    fun `merchant name is left alone`() {
        // The redactor is a net for numbers only; merchant text is handled by not logging
        // it (spec 04 §4.6), not by this function. Pinned so a future "improvement" that
        // starts mangling words has to argue with a test.
        assertEquals("merchant=EXAMPLE STORE", LogRedactor.redact("merchant=EXAMPLE STORE"))
    }

    @Test
    fun `long digit run is replaced in four-digit chunks and leaves a remainder`() {
        // A 12-digit reference goes entirely; a 14-digit one keeps its last two digits,
        // which is what the spec's unanchored \d{4} means. Recorded rather than fixed:
        // changing it would be a change to the spec's regex.
        assertEquals("ref ••••••••••••", LogRedactor.redact("ref 123456789012"))
        assertEquals("ref ••••••••••••34", LogRedactor.redact("ref 12345678901234"))
    }
}
