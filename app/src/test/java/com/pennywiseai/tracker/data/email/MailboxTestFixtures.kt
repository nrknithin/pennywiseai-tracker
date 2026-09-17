package com.pennywiseai.tracker.data.email

import java.time.Instant

/**
 * Shared fixtures for the mailbox tests.
 *
 * Every address here is an `example.com` placeholder and every body is a redacted template
 * (spec 06 §6.2) — `PiiLintTest` scans this directory, and a real sender address or a real
 * amount would fail the build, correctly.
 */

/** A 16-character stand-in for a Google app password; letters only, so it is obviously fake. */
internal val TEST_PASSWORD: AppPassword = requireNotNull(AppPassword.parse("abcdefghijklmnop")) {
    "AppPassword.parse rejected the 16-character test value"
}

/** "Now" for every test, so the lookback window is deterministic. */
internal val TEST_NOW: Instant = Instant.parse("2026-09-17T09:00:00Z")

/** The sender the tests treat as a known bank; one IMAP FROM term covers it. */
internal const val BANK_SENDER = "alerts@bank.example.com"

/** A sender no registry term matches — it must never be downloaded (spec 11 R-11-03). */
internal const val UNRELATED_SENDER = "noreply@newsletter.example.org"

/** What the worker would pass as `EmailSenderRegistry.searchTerms()`. */
internal val TEST_SEARCH_TERMS = listOf("bank.example.com")

/**
 * One message in the fake folder.
 *
 * [receivedAt] defaults to a minute per UID before [TEST_NOW], so UID order and time order
 * agree the way they do on a real server.
 */
internal fun mailboxMessage(
    uid: Long,
    from: String = BANK_SENDER,
    subject: String = "Transaction alert",
    receivedAt: Instant = TEST_NOW.minusSeconds(60L * uid),
    messageId: String? = "<msg-$uid@example.com>",
    body: String = "Rs.1,234.00 spent on card ending 1234 on 17-09-26",
    bodyHash: String = "hash-$uid"
): FetchedMessage = FetchedMessage(
    header = MailboxMessageHeader(
        uid = uid,
        messageId = messageId,
        from = from,
        subject = subject,
        receivedAt = receivedAt
    ),
    body = ExtractedBody(
        text = body,
        bodyHash = bodyHash,
        hasAttachments = false,
        attachmentNames = emptyList(),
        truncated = false,
        sourceMimeType = "text/plain"
    )
)

/**
 * A config with the settings the sync algorithm reads.
 *
 * Built directly rather than through `coerced()` on purpose: [batchSize] exists so a test can
 * shrink the commit unit below the production 50 and still exercise several batches.
 */
internal fun testConfig(
    maxMessagesPerSync: Int = 500,
    batchSize: Int = 50,
    initialLookbackDays: Int = 180,
    folder: String = EmailAccountConfig.DEFAULT_FOLDER
): EmailAccountConfig = EmailAccountConfig(
    address = "someone@example.com",
    folder = folder,
    initialLookbackDays = initialLookbackDays,
    maxMessagesPerSync = maxMessagesPerSync,
    batchSize = batchSize
)
