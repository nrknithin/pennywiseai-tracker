package com.nrknithin.fintrack.worker

import com.nrknithin.fintrack.data.database.entity.AccountBalanceEntity
import com.nrknithin.fintrack.data.repository.AccountBalanceRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * The two halves of the "phantom `XXXX` account" fix in [OptimizedSmsReaderWorker].
 *
 * A bank SMS that reports a balance but no account number used to be written as a real
 * balance row under a literal placeholder last-4. Accounts are materialised by
 * `GROUP BY bank_name, account_last4`, so every such write invented an account in the
 * owner's list that could never be shown against anything real, updated by the next SMS,
 * or reconciled.
 *
 * The worker itself is a `CoroutineWorker` and cannot be constructed without an Android
 * `Context` (`EmailSyncWorkerTest` says the same of its own worker), so neither half is
 * reached through `doWork`:
 *
 *  - The *stop writing it* half is a guard read off the worker's own source text, in the
 *    style of `ProfileNameFieldSourceGuardTest`. It is the only way to pin a two-character
 *    deletion that no type checker will ever miss.
 *  - The *clean up what is already there* half is the pure qualification behind an
 *    irreversible delete, which is why it was written as a function of data
 *    ([OptimizedSmsReaderWorker.qualifiesAsPlaceholderResidue]) rather than inline in the
 *    sweep. Everything below is invented; no figure or name here is anyone's.
 */
class PlaceholderAccountTest {

    // -- the write path: no account number, no balance row ------------------------------

    @Test
    fun `no balance write invents an account identifier`() {
        val source = normalise(workerFile().readText())

        val calls = callSites(source, "insertBalanceUpdate(")
        assertEquals(
            "Expected the two balance-notification writes in $WORKER_PATH (HDFC and " +
                "IndusInd). If a call was added or removed, re-read this guard rather " +
                "than adjusting the count blindly — each one has to obey the same rule.",
            2,
            calls.size
        )

        for (call in calls) {
            val argument = ACCOUNT_ARGUMENT.find(call)?.groupValues?.get(1)?.trim()
            assertTrue(
                "An insertBalanceUpdate( call in $WORKER_PATH passes no named " +
                    "accountLast4 argument, so this guard can no longer read what it " +
                    "writes:\n$call",
                argument != null
            )
            assertFalse(
                "insertBalanceUpdate( in $WORKER_PATH substitutes a fallback for a " +
                    "missing account number (`accountLast4 = $argument`). A balance that " +
                    "cannot be attributed to an account must be dropped, not written " +
                    "under an invented identifier — that is what put phantom accounts in " +
                    "the owner's list. Guard the null with `?.let` and skip the write.\n" +
                    call,
                argument!!.contains("?:") || argument.contains(PLACEHOLDER) ||
                    argument == "PLACEHOLDER_ACCOUNT_LAST4"
            )
        }
    }

    // -- the cleanup path: what may be deleted, and what may not ------------------------

    @Test
    fun `an unattributable balance row left by the old write is residue`() {
        assertTrue(
            OptimizedSmsReaderWorker.qualifiesAsPlaceholderResidue(
                rows = listOf(placeholderRow(), placeholderRow(balance = "250.00")),
                transactionCount = 0
            )
        )
    }

    @Test
    fun `an account with no rows at all is not residue`() {
        // Nothing to delete, and an empty list must never read as "everything qualifies".
        assertFalse(
            OptimizedSmsReaderWorker.qualifiesAsPlaceholderResidue(
                rows = emptyList(),
                transactionCount = 0
            )
        )
    }

    @Test
    fun `a transaction attributed to the account keeps it`() {
        assertFalse(
            OptimizedSmsReaderWorker.qualifiesAsPlaceholderResidue(
                rows = listOf(placeholderRow()),
                transactionCount = 1
            )
        )
    }

    @Test
    fun `a row produced by a transaction keeps the account`() {
        assertFalse(
            OptimizedSmsReaderWorker.qualifiesAsPlaceholderResidue(
                rows = listOf(placeholderRow(), placeholderRow(transactionId = 7L)),
                transactionCount = 0
            )
        )
    }

    @Test
    fun `a row with an origin of its own keeps the account`() {
        // Only the balance-notification write leaves source_type null; MANUAL, OPENING and
        // TRANSACTION all mean somebody or something else put this row here.
        for (sourceType in listOf(
            AccountBalanceRepository.SOURCE_MANUAL,
            AccountBalanceRepository.SOURCE_OPENING,
            "TRANSACTION",
            "CARD_LINK"
        )) {
            assertFalse(
                "A $sourceType row must not be swept up as placeholder residue.",
                OptimizedSmsReaderWorker.qualifiesAsPlaceholderResidue(
                    rows = listOf(placeholderRow(), placeholderRow(sourceType = sourceType)),
                    transactionCount = 0
                )
            )
        }
    }

    @Test
    fun `an account the owner has touched keeps its rows`() {
        // An alias or a low-balance threshold is the owner saying this account is real to
        // them. Deleting balance rows cannot be undone, so their say-so outranks the last-4.
        assertFalse(
            "An owner-set alias must stop the sweep.",
            OptimizedSmsReaderWorker.qualifiesAsPlaceholderResidue(
                rows = listOf(placeholderRow(alias = "Savings")),
                transactionCount = 0
            )
        )
        assertFalse(
            "An owner-set low-balance threshold must stop the sweep.",
            OptimizedSmsReaderWorker.qualifiesAsPlaceholderResidue(
                rows = listOf(placeholderRow(threshold = BigDecimal("100.00"))),
                transactionCount = 0
            )
        )
    }

    @Test
    fun `a real account is never residue`() {
        // The sweep only ever looks at placeholder accounts, but the qualification says so
        // itself: hand it a digit last-4, or a mobile-money wallet whose last-4 is a
        // sentinel for a genuine reason, and it refuses.
        for (last4 in listOf("4321", AccountBalanceEntity.WALLET_ACCOUNT_MARKER)) {
            assertFalse(
                "An account keyed on \"$last4\" is not placeholder residue.",
                OptimizedSmsReaderWorker.qualifiesAsPlaceholderResidue(
                    rows = listOf(placeholderRow(accountLast4 = last4)),
                    transactionCount = 0
                )
            )
        }
    }

    // -- fixtures -----------------------------------------------------------------------

    /** A balance row exactly as the old placeholder write left it, unless overridden. */
    private fun placeholderRow(
        accountLast4: String = PLACEHOLDER,
        balance: String = "1000.00",
        transactionId: Long? = null,
        sourceType: String? = null,
        alias: String? = null,
        threshold: BigDecimal? = null
    ) = AccountBalanceEntity(
        bankName = BANK,
        accountLast4 = accountLast4,
        balance = BigDecimal(balance),
        timestamp = LocalDateTime.of(2026, 1, 2, 3, 4),
        transactionId = transactionId,
        sourceType = sourceType,
        alias = alias,
        lowBalanceThreshold = threshold
    )

    // -- source reading (borrowed from ProfileNameFieldSourceGuardTest) -----------------

    private fun workerFile(): File = File(repoRoot(), WORKER_PATH).also {
        assertTrue("Expected to find $WORKER_PATH under ${repoRoot().absolutePath}", it.isFile)
    }

    /** Comments out, whitespace collapsed — so re-wrapping the call cannot break the guard. */
    private fun normalise(text: String): String = text
        .replace(BLOCK_COMMENT, " ")
        .replace(LINE_COMMENT, " ")
        .replace(WHITESPACE, " ")
        .trim()

    /** Every `needle …)` call in [text], each from the needle to its matching `)`. */
    private fun callSites(text: String, needle: String): List<String> {
        val sites = mutableListOf<String>()
        var from = text.indexOf(needle)
        while (from >= 0) {
            sites += balancedCall(text, from)
            from = text.indexOf(needle, from + needle.length)
        }
        return sites
    }

    /** From the call's `(` to its matching `)`, ignoring parentheses inside string literals. */
    private fun balancedCall(text: String, openIndex: Int): String {
        var depth = 0
        var inString = false
        var i = text.indexOf('(', openIndex)
        while (i in text.indices) {
            val c = text[i]
            when {
                inString && c == '\\' -> i++ // the escaped character is not a delimiter
                c == '"' -> inString = !inString
                inString -> Unit
                c == '(' -> depth++
                c == ')' -> {
                    depth--
                    if (depth == 0) return text.substring(openIndex, i + 1)
                }
            }
            i++
        }
        return text.substring(openIndex)
    }

    /** Up to the directory holding `settings.gradle.kts`, wherever Gradle ran the test from. */
    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").exists()) return dir
            dir = dir.parentFile
        }
        error("Could not locate the repository root from " + System.getProperty("user.dir"))
    }

    private companion object {
        const val WORKER_PATH =
            "app/src/main/java/com/nrknithin/fintrack/worker/OptimizedSmsReaderWorker.kt"

        /** Mirrors the worker's own private constant. */
        const val PLACEHOLDER = "XXXX"

        /** Invented; this repo's tests carry no real institution of the owner's. */
        const val BANK = "Example Bank"

        val ACCOUNT_ARGUMENT = Regex("""accountLast4\s*=\s*([^,)]+)""")
        val BLOCK_COMMENT = Regex("""/\*[\s\S]*?\*/""")
        val LINE_COMMENT = Regex("""(?<!:)//[^\n]*""")
        val WHITESPACE = Regex("""\s+""")
    }
}
