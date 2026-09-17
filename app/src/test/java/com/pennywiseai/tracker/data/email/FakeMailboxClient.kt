package com.pennywiseai.tracker.data.email

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
 */
class FakeMailboxClient(
    var messages: List<FetchedMessage> = emptyList(),
    var uidValidity: Long = 1,
    var connectionTest: ConnectionTest = ConnectionTest.Ok("[Gmail]/All Mail", 0, emptyList()),
    var failAfter: Int? = null,
    var failWith: () -> MailboxException = { MailboxException.Network(IOException()) }
) : ImapMailboxClient {

    private val delivered = mutableListOf<Long>()

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
                val message = byUid[uid] ?: continue // vanished server-side between search and fetch
                delivered += uid
                fetched += message
            }
            return fetched
        }
    }
}
