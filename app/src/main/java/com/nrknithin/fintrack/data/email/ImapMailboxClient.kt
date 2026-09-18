package com.nrknithin.fintrack.data.email

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

/**
 * One sighting of a sender, from an ENVELOPE-only peek. Deliberately NOT a
 * [MailboxMessageHeader]: that type normalises the From header down to a bare address and
 * throws the display phrase away, and the phrase is the whole point of discovery.
 * No subject, no body — this record cannot carry the content of the owner's mail.
 */
data class SenderSighting(
    val uid: Long,
    /** Bare lower-cased address, through `EmailSenderRegistry.normaliseAddress`. */
    val address: String,
    /** The From header's display phrase as sent (RFC 2047 already decoded), or null when absent. */
    val displayPhrase: String?,
    /** INTERNALDATE. Feeds firstSeen/lastSeen; never a date read out of a body. */
    val receivedAt: Instant
)

/** A header plus its already-extracted body — see [MailboxMessageHeader] for why the body is not lazy. */
data class FetchedMessage(val header: MailboxMessageHeader, val body: ExtractedBody)

/**
 * One message the session could not turn into a [FetchedMessage].
 *
 * Spec 02 §2.7 says a parser that throws on one message costs that message and nothing more.
 * The same has to hold a layer earlier: a `From` header no address parser accepts, or a
 * message expunged between the search and the fetch, must not end the batch — because the
 * watermark only advances when a batch completes, and a message that kills every batch kills
 * every future sync too (R-11-07).
 *
 * So the message is skipped and its failure recorded here, for the worker to log as
 * `email_messages.skip_reason = "ERROR:<class>"` (R-11-05) and count in `sync_runs.errors`.
 *
 * @param uid the UID, or **null** when the UID itself was the unreadable part. Null means
 *   unknown, never zero: spec 01 constraint C7, and a zero here would be compared against a
 *   watermark as if it were real.
 * @param error the failure's class name — never its message, which on some providers quotes
 *   the login (spec 04 §4.1).
 */
data class UnreadableMessage(val uid: Long?, val error: String)

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
     * So is one whose headers could not be read; that one is recorded in [unreadable].
     *
     * @throws MailboxException only when the *connection* failed, never because one message
     * did. The distinction is what the caller's watermark rests on: a batch that returns has
     * dealt with every UID it was given, so the watermark may advance past all of them.
     */
    suspend fun fetch(uids: List<Long>, maxBodyKb: Int): List<FetchedMessage>

    /**
     * True when the server advertised `X-GM-EXT-1` in its CAPABILITY response.
     *
     * Read once at open from the already-cached capability list — no extra round trip.
     * False on every non-Gmail IMAP server, and false rather than throwing if the
     * capability list cannot be read at all: a discovery feature must never be able to
     * fail a sync.
     */
    val supportsGmailRawSearch: Boolean

    /**
     * UIDs matching a Gmail raw query (`UID SEARCH [SINCE <date>] X-GM-RAW "<query>"`),
     * ascending, capped at [limit].
     *
     * Returns **null** when [supportsGmailRawSearch] is false — not an empty list.
     * The distinction is load-bearing and is spec 01 constraint C7 applied to a
     * capability: `emptyList()` means "this server was asked and matched nothing",
     * `null` means "this server cannot be asked", and the two produce different UI
     * ("no new senders" vs "discovery needs Gmail"). Collapsing them would report a
     * clean scan that never happened.
     *
     * [query] must contain no CR or LF: it is interpolated into an IMAP command line,
     * and a newline there is command injection into the protocol stream. The
     * implementation rejects such a query by returning null rather than sending it.
     * Quoting of `"` and `\` is the implementation's job, not the caller's.
     *
     * READ_ONLY-safe by construction: `UID SEARCH` reads no body and sets no flag, so
     * constraint C2 holds for the same reason it holds for [searchUids].
     */
    suspend fun searchRaw(query: String, receivedSince: Instant?, limit: Int): List<Long>?

    /**
     * ENVELOPE-only fetch: who sent it and when, never what it said.
     *
     * One FETCH round trip with `FetchProfile.Item.ENVELOPE` + UID — the same profile step 8
     * already uses minus CONTENT_INFO — and explicitly **not** `BODY[]`, which is the
     * flag-setting fetch constraint C2 forbids. `BODY.PEEK` is not needed because no body
     * part is requested at all: `ENVELOPE` is a parsed-header item, and RFC 3501 sets
     * `\Seen` only for a `BODY[…]` section fetch.
     *
     * A UID that vanished between search and peek is simply absent, as in [fetch]; a
     * header that will not parse is contained per message and filed under [unreadable].
     */
    suspend fun peekSenders(uids: List<Long>): List<SenderSighting>

    /**
     * Messages this session skipped rather than returned, oldest first, accumulated across
     * every [searchUids], [fetch] and [peekSenders] call it has served.
     *
     * Empty for a clean run. A non-empty list is not an error the worker retries — the
     * messages are gone for this run either way — but each entry is a row the Email Log
     * should be able to show and a `sync_runs.errors` the user can see.
     */
    val unreadable: List<UnreadableMessage>
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

/**
 * The candidate UID set for one sender-discovery pass: Gmail's own classification of the
 * mailbox unioned with the senders the registry already knows — or, on a server that cannot
 * be asked the first question, the registry's answer alone.
 *
 * **Why the union is here and not inside [MailboxSession.searchUids].** That method decides
 * what gets *downloaded*: every UID it returns is handed to [MailboxSession.fetch], which
 * pulls a body. Folding `X-GM-RAW category:purchases` into it would put ~700 bodies a year
 * from senders nobody has approved onto the phone, which is precisely what R-11-03 exists to
 * stop, and it would do so under a watermark that is only safe to advance over a contiguous
 * range the sync fetched itself (R-11-07). Discovery unions the two searches and then peeks
 * *envelopes* ([MailboxSession.peekSenders]), so the widened search stays a widened search
 * and never becomes a widened download.
 *
 * **Why the registry search is in the union at all**, when discovery is looking for senders
 * the registry does not know: so that the pass measures the same corpus on both kinds of
 * server, and so that known senders Gmail files outside the query — the measurement found
 * Swiggy sitting outside `category:purchases` — still appear as sightings and land in the
 * report's known count rather than silently vanishing from the denominator.
 *
 * [sinceUid] is deliberately absent: discovery is not watermarked, reads nothing from
 * `settings.email.lastSyncedUid` and writes nothing to it. It is bounded by [receivedSince]
 * and [limit] instead, and re-scans the same window every time it runs.
 *
 * @return ascending, de-duplicated, capped at [limit] — the same window arithmetic both
 *   searches already applied, re-applied because the union of two capped lists is not itself
 *   capped.
 */
internal suspend fun MailboxSession.discoveryCandidateUids(
    searchTerms: List<String>,
    rawQuery: String,
    receivedSince: Instant?,
    limit: Int
): List<Long> {
    if (limit <= 0) return emptyList()

    val registry = searchUids(
        searchTerms = searchTerms,
        sinceUid = 0L,
        receivedSince = receivedSince,
        limit = limit
    )

    // Null, not empty: the server has no X-GM-EXT-1 and was never asked. Falling back to the
    // registry alone is silent on purpose — a plain IMAP server is a supported configuration,
    // not a failure — and it is the caller, holding the same null from
    // `MailboxSession.searchRaw`, that decides whether the *screen* should say so.
    val raw = searchRaw(rawQuery, receivedSince, limit) ?: return registry

    return MailboxUidWindow.select(registry + raw, sinceUid = 0L, limit = limit)
}
