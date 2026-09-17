package com.pennywiseai.tracker.data.email

import java.time.Instant

/**
 * One message as it leaves the client.
 *
 * No `javax.mail` type escapes this boundary. That is not tidiness: a JavaMail `Message` is
 * a live handle into an open folder and dies the moment the folder closes, so anything that
 * held one outside [ImapMailboxClient.withSession] would throw at a distance. Handing back
 * plain data instead is also what lets the whole sync algorithm be tested against
 * `FakeMailboxClient` with no network and no credential.
 */
data class MailboxMessageHeader(
    /** IMAP UID, unique inside the mailbox for the current UIDVALIDITY. */
    val uid: Long,
    /** RFC 5322 Message-ID, null when the sender omitted one. Second identity key after a UIDVALIDITY reset (spec 11 R-11-08). */
    val messageId: String?,
    /** Bare lower-cased address, already through `EmailSenderRegistry.normaliseAddress`. */
    val from: String,
    val subject: String,
    /** INTERNALDATE. The server's receive time, never a date inside the body (spec 11 §11.3 step 15). */
    val receivedAt: Instant
)

/** A header plus its already-extracted body — see [MailboxMessageHeader] for why the body is not lazy. */
data class FetchedMessage(val header: MailboxMessageHeader, val body: ExtractedBody)

/** Result of screen 47's "Test connection" (E25/E26). Never carries the credential. */
sealed interface ConnectionTest {
    /** [folders] feeds the folder picker so a wrong `[Gmail]/All Mail` can be corrected in place. */
    data class Ok(val folder: String, val messageCount: Int, val folders: List<String>) : ConnectionTest

    data object AuthFailed : ConnectionTest

    data class FolderMissing(val folder: String, val folders: List<String>) : ConnectionTest

    /** [detail] is a class name or a short cause — never a message that could quote the login (spec 04 §4.1). */
    data class NetworkError(val detail: String) : ConnectionTest
}

/**
 * Thrown out of [ImapMailboxClient.withSession].
 *
 * [Auth] deliberately drops the provider's exception message, which can echo the login
 * (spec 04 §4.1 threat "app password leaked through logs"). [Network] keeps its cause so a
 * bug report can say *what* broke — callers log `cause::class.simpleName`, never
 * `cause.message`.
 */
sealed class MailboxException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** Password revoked or wrong. The worker must not retry (spec 11 AC-11-03). */
    class Auth : MailboxException("auth failed")

    /** The configured folder is not on the server. [folder] is a mailbox name, not user data. */
    class FolderMissing(val folder: String) : MailboxException("folder missing")

    /** Anything transient: DNS, TLS, socket, server hiccup. The worker retries. */
    class Network(cause: Throwable) : MailboxException("network error", cause)
}

/**
 * An open, READ_ONLY folder (spec 11 R-11-02: never flag, move, label or expunge — this
 * interface has no method that could).
 *
 * Valid only inside [ImapMailboxClient.withSession].
 */
interface MailboxSession {
    /** The folder's full name, e.g. `[Gmail]/All Mail`. Stored on every `email_messages` row. */
    val mailbox: String

    /** Compare against `EmailAccountConfig.uidValidity`; a change means reset the watermark (spec 11 R-11-08). */
    val uidValidity: Long

    /** Messages in the folder — the figure screen 47 shows next to "Connected". */
    val messageCount: Int

    /** Folder names for the picker of screen 47 E30. Never fails a sync: an empty list is a valid answer. */
    suspend fun listFolders(): List<String>

    /**
     * UIDs matching `OR(FROM …)` over [searchTerms], ascending (spec 11 §11.3 steps 6, 7, 9).
     *
     * [sinceUid] > 0 → UID window `sinceUid+1..*`; [sinceUid] == 0 → whole folder bounded by
     * [receivedSince] (null means no bound, i.e. lookback "all").
     *
     * At most [limit] ids are returned; the caller re-runs when it gets exactly [limit]
     * (spec 11 §11.3 step 21). An empty [searchTerms] returns nothing rather than everything:
     * a `FROM`-less search would download the entire mailbox, which R-11-03 exists to prevent.
     */
    suspend fun searchUids(
        searchTerms: List<String>,
        sinceUid: Long,
        receivedSince: Instant?,
        limit: Int
    ): List<Long>

    /**
     * Fetches and extracts one batch, in the order given.
     *
     * A UID that vanished server-side (the user deleted the mail between search and fetch) is
     * simply absent from the result — the caller must key off `header.uid`, not the index.
     */
    suspend fun fetch(uids: List<Long>, maxBodyKb: Int): List<FetchedMessage>
}

/**
 * The network boundary of the email channel.
 *
 * IMAPS only (spec 04 §4.1: TLS 1.2/1.3, `checkserveridentity=true`, no STARTTLS fallback),
 * folder opened READ_ONLY (spec 11 R-11-02).
 *
 * An interface with one real implementation ([JavaMailImapClient]) and one fake, because the
 * worker's whole algorithm — windowing, batching, resume, UIDVALIDITY reset — has to be
 * testable with no network and no credential.
 */
interface ImapMailboxClient {
    /** Screen 47's "Test connection". Returns a verdict rather than throwing, because every outcome is a UI state. */
    suspend fun testConnection(config: EmailAccountConfig, password: AppPassword): ConnectionTest

    /**
     * Connects, opens [EmailAccountConfig.folder] READ_ONLY, runs [block], always closes both.
     *
     * @throws MailboxException and nothing else — an implementation that lets a provider
     * exception escape would hand the worker an error it cannot classify into a state.
     */
    suspend fun <T> withSession(
        config: EmailAccountConfig,
        password: AppPassword,
        block: suspend (MailboxSession) -> T
    ): T
}

/**
 * The part of spec 11 §11.3 steps 7 and 9 that is pure arithmetic: which UIDs a search may
 * return, in which order, and how many.
 *
 * It is shared by the real client and the fake on purpose. The two would otherwise drift —
 * the server sorts and the fake sorts, and a worker test that passes against a fake which
 * happens to return insertion order proves nothing about the watermark, which is only safe
 * to advance when the batch really was the *lowest* outstanding UIDs.
 */
object MailboxUidWindow {

    /**
     * Ascending, de-duplicated, strictly greater than [sinceUid], capped at [limit].
     *
     * Non-positive ids are dropped: JavaMail's `UIDFolder.getUID` answers -1 for a message
     * the server no longer knows, and -1 must never be written to `email_messages.uid` or
     * compared against a watermark.
     *
     * Ascending *before* the cap is the load-bearing part. Taking the oldest [limit] ids
     * leaves the remainder contiguous above the new watermark, so the follow-up run of step
     * 21 picks up exactly what was left and nothing is skipped.
     */
    fun select(uids: Collection<Long>, sinceUid: Long, limit: Int): List<Long> {
        if (limit <= 0) return emptyList()
        return uids.asSequence()
            .filter { it > 0 && it > sinceUid }
            .distinct()
            .sorted()
            .take(limit)
            .toList()
    }
}
