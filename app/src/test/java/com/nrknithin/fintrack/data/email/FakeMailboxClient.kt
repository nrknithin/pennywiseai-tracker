package com.nrknithin.fintrack.data.email

import java.io.IOException
import java.time.Instant

/**
 * A mailbox in a list. Test-only; `EmailSyncWorker` cannot tell it from the real client.
 *
 * It exists so the whole of spec 11 §11.3 — the UID window, the batch commit, the resume
 * after an interruption, the UIDVALIDITY reset — can be driven with no network, no
 * credential and no `javax.mail` on the test classpath. It deliberately shares
 * [MailboxUidWindow] with [JavaMailImapClient] so that "ascending, de-duplicated, capped"
 * cannot mean one thing in the test and another on the phone.
 *
 * **It has no mutating operation at all**, which is how it models R-11-02: there is no way to
 * write a test that flags, moves or expunges a message, because there is nothing to call.
 *
 * @param messages the folder contents, in any order — [searchUids] sorts.
 * @param uidValidity read by each new session, so a test changes it between runs to make the
 *   worker see the folder Gmail rebuilt (R-11-08).
 * @param connectionTest what [testConnection] answers, and also what [withSession] does: a
 *   non-[ConnectionTest.Ok] value makes the session throw the matching [MailboxException],
 *   exactly as the real client would, which is how the AUTH_FAILED / FOLDER_MISSING /
 *   NETWORK_ERROR branches of the worker are reached.
 * @param failAfter how many messages the fake will hand out before every further [fetch]
 *   throws [failWith]. This is how AC-11-02 is tested without killing a process. Null means
 *   never fail.
 * @param unreadableUids UIDs the fake answers the way the real client answers a message whose
 *   headers will not parse: absent from the [MailboxSession.fetch] result, present in
 *   [MailboxSession.unreadable], and — the point of the whole arrangement — costing that one
 *   message rather than the batch.
 *
 * It also models the two servers the discovery pass has to survive: [supportsGmailRawSearch]
 * off is a plain IMAP server, where [MailboxSession.searchRaw] answers null and the caller
 * falls back to the registry search alone; on, with [rawSearchHits], is a Gmail-like server
 * that can be asked an `X-GM-RAW` question. Neither shape can download a body it was not
 * asked for — see [peekedUids].
 */
class FakeMailboxClient(
    var messages: List<FetchedMessage> = emptyList(),
    var uidValidity: Long = 1,
    var connectionTest: ConnectionTest = ConnectionTest.Ok("[Gmail]/All Mail", 0, emptyList()),
    var failAfter: Int? = null,
    var failWith: () -> MailboxException = { MailboxException.Network(IOException()) },
    var unreadableUids: Set<Long> = emptySet(),
    /**
     * Whether this server advertises `X-GM-EXT-1`, i.e. whether it is Gmail.
     *
     * Default **false** — a plain IMAP server. The Gmail path has to be opted into, so a test
     * that forgets to say which server it is running against gets the degraded one, and the
     * silent fallback is what is exercised by accident rather than the extension.
     */
    var supportsGmailRawSearch: Boolean = false,
    /**
     * What `X-GM-RAW <query>` matches, keyed by the exact query string.
     *
     * A map rather than a predicate because Gmail's search language is the server's, not
     * ours: the fake cannot evaluate `category:purchases` and must not pretend to. A test
     * states the answer it wants for the query it will send, and an unknown query matches
     * nothing — which is also the honest model of a query Gmail does not understand.
     */
    var rawSearchHits: Map<String, Set<Long>> = emptyMap(),
    /**
     * The From display phrase per UID, as the header carried it.
     *
     * It lives here and not on [MailboxMessageHeader] for the reason [SenderSighting] exists
     * at all: that type has already thrown the phrase away. A UID with no entry models a
     * sender that named itself nothing.
     */
    var displayPhrases: Map<Long, String> = emptyMap()
) : ImapMailboxClient {

    private val delivered = mutableListOf<Long>()

    private val peeked = mutableListOf<Long>()

    /**
     * UIDs handed out by [MailboxSession.fetch], in order, across every session this client
     * has served.
     *
     * A test asserts on this that committed messages are never re-downloaded. Note what it
     * cannot claim: when a batch throws part-way, the messages the fake had already handed
     * out *are* recorded here and *will* appear again on the next run, because the worker
     * never committed that batch. That is the real cost of a resume and the assertion to
     * write is "every UID below the watermark appears exactly once", not "no UID repeats".
     */
    val fetchedUids: List<Long> get() = delivered.toList()

    /**
     * UIDs whose envelope [MailboxSession.peekSenders] read, in order, across every session.
     *
     * Kept apart from [fetchedUids] because the whole bound on the discovery pass is that the
     * two lists are different: a sender the registry does not know may appear here and must
     * never appear there (R-11-03).
     */
    val peekedUids: List<Long> get() = peeked.toList()

    /** How many sessions have been opened — a re-run that reconnects is visible to a test. */
    var sessionsOpened: Int = 0
        private set

    override suspend fun testConnection(
        config: EmailAccountConfig,
        password: AppPassword
    ): ConnectionTest = connectionTest

    override suspend fun <T> withSession(
        config: EmailAccountConfig,
        password: AppPassword,
        block: suspend (MailboxSession) -> T
    ): T {
        // Same mapping the real client applies, so a test that sets AuthFailed exercises the
        // worker's real AUTH_FAILED path rather than a fake-only shortcut.
        when (val outcome = connectionTest) {
            is ConnectionTest.AuthFailed -> throw MailboxException.Auth()
            is ConnectionTest.FolderMissing -> throw MailboxException.FolderMissing(outcome.folder)
            is ConnectionTest.NetworkError -> throw MailboxException.Network(IOException(outcome.detail))
            is ConnectionTest.Ok -> Unit
        }
        sessionsOpened++
        return block(FakeSession(config.folder))
    }

    private inner class FakeSession(override val mailbox: String) : MailboxSession {

        /** Snapshotted at open, like the real `UIDVALIDITY` response, so a mid-run change is not seen. */
        override val uidValidity: Long = this@FakeMailboxClient.uidValidity

        override val messageCount: Int = messages.size

        private val skipped = mutableListOf<UnreadableMessage>()

        override val unreadable: List<UnreadableMessage> get() = skipped.toList()

        override suspend fun listFolders(): List<String> {
            val configured = when (val outcome = connectionTest) {
                is ConnectionTest.Ok -> outcome.folders
                is ConnectionTest.FolderMissing -> outcome.folders
                else -> emptyList()
            }
            // A server always lists at least the folder that is open.
            return if (configured.isEmpty()) listOf(mailbox) else configured
        }

        override suspend fun searchUids(
            searchTerms: List<String>,
            sinceUid: Long,
            receivedSince: Instant?,
            limit: Int
        ): List<Long> {
            // Matches the real client: no FROM terms means fetch nothing, never everything.
            if (searchTerms.isEmpty()) return emptyList()

            // IMAP's FROM search is a substring match, which is why the registry can get away
            // with one term per domain (EmailSenderRegistry.searchTerms).
            val matching = messages.filter { message ->
                searchTerms.any { term -> message.header.from.contains(term, ignoreCase = true) }
            }

            // The date bound applies to the first run only; once there is a watermark the UID
            // window is the bound (spec 11 §11.3 step 7).
            val bounded = if (sinceUid > 0 || receivedSince == null) {
                matching
            } else {
                matching.filter { !it.header.receivedAt.isBefore(receivedSince) }
            }

            return MailboxUidWindow.select(bounded.map { it.header.uid }, sinceUid, limit)
        }

        override suspend fun fetch(uids: List<Long>, maxBodyKb: Int): List<FetchedMessage> {
            // maxBodyKb is ignored on purpose: these bodies are already extracted, and the
            // truncation rule belongs to EmailBodyExtractor's own tests, not to the mailbox.
            val byUid = messages.associateBy { it.header.uid }
            val fetched = mutableListOf<FetchedMessage>()
            for (uid in uids) {
                val budget = failAfter
                if (budget != null && delivered.size >= budget) throw failWith()
                if (uid in unreadableUids) {
                    // The real client's contained failure: recorded, skipped, batch continues.
                    // "MessagingException" is what JavaMail raises on a header it cannot parse,
                    // and the real client records the class name and nothing else (spec 04 §4.1).
                    skipped += UnreadableMessage(uid, "MessagingException")
                    continue
                }
                val message = byUid[uid] ?: continue // vanished server-side between search and fetch
                delivered += uid
                fetched += message
            }
            return fetched
        }

        /**
         * Snapshotted at open, like the real client's read of the cached CAPABILITY list, so a
         * test flipping the flag mid-session does not change the answer a session already gave.
         */
        override val supportsGmailRawSearch: Boolean =
            this@FakeMailboxClient.supportsGmailRawSearch

        override suspend fun searchRaw(
            query: String,
            receivedSince: Instant?,
            limit: Int
        ): List<Long>? {
            // Every guard the real client applies, in the same order and with the same answer,
            // so a test of the injection guard proves something about the phone and not only
            // about this file.
            if (!supportsGmailRawSearch || limit <= 0) return null
            if (query.isBlank() || query.any { it == '\r' || it == '\n' }) return null

            val hits = rawSearchHits[query].orEmpty()
            val byUid = messages.associateBy { it.header.uid }
            // Discovery is never watermarked, so the only bound besides `limit` is the date —
            // and unlike searchUids there is no sinceUid branch that could switch it off.
            val bounded = if (receivedSince == null) {
                hits
            } else {
                hits.filter { uid ->
                    val receivedAt = byUid[uid]?.header?.receivedAt
                    receivedAt != null && !receivedAt.isBefore(receivedSince)
                }
            }

            return MailboxUidWindow.select(bounded, sinceUid = 0L, limit = limit)
        }

        override suspend fun peekSenders(uids: List<Long>): List<SenderSighting> {
            val byUid = messages.associateBy { it.header.uid }
            val sightings = mutableListOf<SenderSighting>()
            for (uid in uids) {
                if (uid in unreadableUids) {
                    // A From header the real client cannot parse: contained, recorded, and the
                    // peek carries on — the same containment `fetch` gives a bad message.
                    skipped += UnreadableMessage(uid, "MessagingException")
                    continue
                }
                val message = byUid[uid] ?: continue // vanished between search and peek
                // Deliberately NOT recorded in `delivered`. A peek is an ENVELOPE read, and
                // `fetchedUids` has to keep meaning "bodies that came down onto the phone" —
                // that is the assertion R-11-03 is held to when discovery widens the search.
                peeked += uid
                sightings += SenderSighting(
                    uid = uid,
                    address = message.header.from,
                    displayPhrase = displayPhrases[uid],
                    receivedAt = message.header.receivedAt
                )
            }
            return sightings
        }
    }
}
