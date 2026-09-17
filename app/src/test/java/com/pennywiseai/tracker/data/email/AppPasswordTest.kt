package com.pennywiseai.tracker.data.email

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app-password value type. Nothing here needs Android, a device or a network.
 *
 * Every sample is sixteen letters. That is deliberate: a digit-based sample would be a
 * sixteen-digit run, which is exactly the shape `PiiLintTest` fails the build on, and a test
 * that has to be exempted from the PII lint is a test nobody will trust.
 */
class AppPasswordTest {

    /** Sixteen letters — the shape Google issues, with no personal data in it. */
    private val sample = "abcdefghijklmnop"

    @Test
    fun `parse accepts exactly sixteen characters`() {
        assertNotNull(AppPassword.parse(sample))
        assertEquals(16, AppPassword.LENGTH)
    }

    @Test
    fun `parse strips the spaces Google prints the code in`() {
        val grouped = AppPassword.parse("abcd efgh ijkl mnop")
        assertNotNull(grouped)
        assertEquals(AppPassword.parse(sample), grouped)
    }

    @Test
    fun `parse strips every kind of whitespace a paste can carry`() {
        assertEquals(AppPassword.parse(sample), AppPassword.parse("\tabcd\nefgh\r ijkl  mnop \n"))
    }

    @Test
    fun `parse rejects anything that is not sixteen characters`() {
        assertNull(AppPassword.parse(""))
        assertNull(AppPassword.parse("   "))
        assertNull(AppPassword.parse("abcdefghijklmno"))       // fifteen
        assertNull(AppPassword.parse("abcdefghijklmnopq"))     // seventeen
        assertNull(AppPassword.parse("abcd efgh ijkl mno"))    // fifteen once stripped
    }

    @Test
    fun `parse rejects control characters`() {
        // Sixteen characters, but one of them is a NUL that would be written into the
        // credential file and then fail obscurely at login time.
        assertNull(AppPassword.parse("abcdefghijklmno\u0000"))
    }

    @Test
    fun `toString never exposes the value`() {
        val password = requireNotNull(AppPassword.parse(sample))
        val printed = password.toString()

        assertFalse("toString leaked the password", printed.contains(sample))
        assertFalse("toString leaked part of the password", printed.contains("abcd"))
        assertEquals("AppPassword(" + "\u2022".repeat(AppPassword.LENGTH) + ")", printed)
        // Sixteen dots, so the mask does not even reveal the length of what was typed
        // beyond what spec 11 already fixes at sixteen.
        assertEquals(AppPassword.LENGTH, printed.count { it == '\u2022' })
    }

    @Test
    fun `string interpolation goes through toString and is therefore safe`() {
        val password = requireNotNull(AppPassword.parse(sample))
        // This is the accident the type exists to survive: a log line built by hand.
        val logLine = "imap login as user, password=$password"
        assertFalse(logLine.contains(sample))
        assertTrue(logLine.contains("\u2022"))
    }

    @Test
    fun `two parses of the same code are equal and hash alike`() {
        val a = requireNotNull(AppPassword.parse(sample))
        val b = requireNotNull(AppPassword.parse("abcd efgh ijkl mnop"))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertFalse(a == requireNotNull(AppPassword.parse("ponmlkjihgfedcba")))
    }
}
