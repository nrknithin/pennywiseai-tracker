package com.nrknithin.fintrack.parser.core.text

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * `body_hash` is persisted, so this is a compatibility suite as much as a correctness one:
 * a change that makes any case below produce a different string silently invalidates every
 * hash already in `email_messages` and `transaction_sources` (spec 11 §11.5 step 4).
 *
 * Fixtures are invented — amounts, digits and links are all placeholders (spec 06 §6.2).
 */
class BodyNormaliserTest {

    @TestFactory
    fun `normalise keeps what identifies a body and drops what varies`(): List<DynamicTest> {
        val zeroWidthSpace = Char(0x200B).toString()
        val nonBreakingSpace = Char(0x00A0).toString()
        val byteOrderMark = Char(0xFEFF).toString()

        val cases = listOf(
            Triple(
                "case is folded",
                "Bill of INR 250.00 Is Due",
                "bill of inr 250.00 is due"
            ),
            Triple(
                "whitespace runs collapse and the ends are trimmed",
                "  bill of   inr 250.00\n\n  is due  ",
                "bill of inr 250.00 is due"
            ),
            Triple(
                "a non-breaking space is a space",
                "bill of${nonBreakingSpace}inr 250.00",
                "bill of inr 250.00"
            ),
            Triple(
                "zero-width characters and the BOM disappear",
                byteOrderMark + "bill" + zeroWidthSpace + " of inr 250.00",
                "bill of inr 250.00"
            ),
            Triple(
                "a tracking link is dropped, because it differs per delivery",
                "bill of inr 250.00 https://example.com/track/aaa111 pay now",
                "bill of inr 250.00 pay now"
            ),
            Triple(
                "digits are kept — they are what make one body different from the next",
                "bill of inr 250.00 ref 123456789012",
                "bill of inr 250.00 ref 123456789012"
            ),
            Triple(
                "a body that is nothing but a link normalises to nothing",
                "   https://example.com/view/12345678   ",
                ""
            ),
            Triple(
                "a bare domain inside the text is content, not a link",
                "example-domain.com cancels 28-09-2026",
                "example-domain.com cancels 28-09-2026"
            )
        )

        return cases.map { (name, raw, expected) ->
            dynamicTest(name) { assertEquals(expected, BodyNormaliser.normalise(raw)) }
        }
    }

    @TestFactory
    fun `bodyHash follows the normal form`(): List<DynamicTest> = listOf(
        dynamicTest("two deliveries of one mail hash the same") {
            val first = "Bill of INR 250.00 is due.\nhttps://example.com/track/aaa111"
            val second = "bill of inr   250.00 is due.\nhttps://example.com/track/bbb222"

            assertEquals(BodyNormaliser.bodyHash(first), BodyNormaliser.bodyHash(second))
        },
        dynamicTest("a different amount is a different hash") {
            assertNotEquals(
                BodyNormaliser.bodyHash("Bill of INR 250.00 is due."),
                BodyNormaliser.bodyHash("Bill of INR 251.00 is due.")
            )
        },
        dynamicTest("the hash is 32 lower-case hex characters") {
            val hash = BodyNormaliser.bodyHash("Bill of INR 250.00 is due.")

            assertEquals(32, hash.length)
            assertTrue(hash.all { it.isDigit() || it in 'a'..'f' }, "not hex: $hash")
        }
    )
}
