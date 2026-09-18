package com.pennywiseai.tracker.data.email

import com.pennywiseai.parser.core.email.EmailSenderRegistry
import com.sun.mail.iap.Response
import com.sun.mail.imap.IMAPFolder
import com.sun.mail.imap.IMAPStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import java.util.Properties
import javax.inject.Inject
import javax.inject.Singleton
import javax.mail.AuthenticationFailedException
import javax.mail.FetchProfile
import javax.mail.Folder
import javax.mail.FolderClosedException
import javax.mail.FolderNotFoundException
import javax.mail.Message
import javax.mail.MessageRemovedException
import javax.mail.MessagingException
import javax.mail.Session
import javax.mail.Store
import javax.mail.StoreClosedException
import javax.mail.UIDFolder
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage
import javax.mail.search.AndTerm
import javax.mail.search.ComparisonTerm
import javax.mail.search.FromStringTerm
import javax.mail.search.OrTerm
import javax.mail.search.ReceivedDateTerm
import javax.mail.search.SearchTerm

/**
 * The real IMAP client: the only file in the app that opens a connection to a mail server,
 * and — together with [EmailBodyExtractor], which walks the MIME tree it hands back — one of
 * the two that import `javax.mail` at all.
 *
 * Tuning is exactly spec 11 §11.3: `connectiontimeout` / `timeout` / `writetimeout` = 25 s,
 * `partialfetch=false`, `fetchsize=65536`; plus `ssl.checkserveridentity=true` and
 * `ssl.protocols=TLSv1.2 TLSv1.3` from spec 04 §4.1. There is no STARTTLS fallback and no
 * plaintext IMAP: the store protocol is `imaps` and nothing else.
 *
 * This is also the only file permitted to call [AppPassword.revealForImapLogin] — and that
 * is enforced rather than asserted: `PiiLintTest` counts the call sites of that name across
 * the fork's sources and fails the build on a second one.
 *
 * **Why `javax.mail` and not `jakarta.mail`.** The dependency is
 * `com.sun.mail:android-mail:1.6.7` (with `android-activation`), the Android port of
 * JavaMail. Jakarta Mail 2.x needs `java.beans` and `javax.activation`, neither of which
 * Android ships, so the modern coordinate cannot be used here.
 */
@Singleton
class JavaMailImapClient @Inject constructor(
    private val bodyExtractor: EmailBodyExtractor
) : ImapMailboxClient {

    override suspend fun testConnection(
        config: EmailAccountConfig,
        password: AppPassword
    ): ConnectionTest = withContext(Dispatchers.IO) {
        var store: Store? = null
        try {
            val connected = connect(config, password)
            store = connected
            val folders = folderNames(connected)
            val folder = connected.getFolder(config.folder)
            if (folder == null || !folder.exists()) {
                return@withContext ConnectionTest.FolderMissing(config.folder, folders)
            }
            folder.open(Folder.READ_ONLY)
            val count = folder.messageCount
            closeQuietly(folder)
            ConnectionTest.Ok(config.folder, count, folders)
        } catch (e: MailboxException.Auth) {
            ConnectionTest.AuthFailed
        } catch (e: MailboxException.FolderMissing) {
            // Thrown before the folder list could be read; the picker falls back to typing.
            ConnectionTest.FolderMissing(e.folder, emptyList())
        } catch (e: MailboxException.Network) {
            ConnectionTest.NetworkError(detailOf(e))
        } catch (e: FolderNotFoundException) {
            ConnectionTest.FolderMissing(config.folder, emptyList())
        } catch (e: MessagingException) {
            ConnectionTest.NetworkError(detailOf(e))
        } catch (e: IOException) {
            ConnectionTest.NetworkError(detailOf(e))
        } finally {
            store?.let { closeQuietly(it) }
        }
    }

    override suspend fun <T> withSession(
        config: EmailAccountConfig,
        password: AppPassword,
        block: suspend (MailboxSession) -> T
    ): T = withContext(Dispatchers.IO) {
        val store = connect(config, password)
        try {
            val folder = openFolder(store, config.folder)
            try {
                block(JavaMailSession(folder, config.folder))
            } finally {
                closeQuietly(folder)
            }
        } finally {
            closeQuietly(store)
        }
    }

    // -- connection ---------------------------------------------------------------------

    private fun connect(config: EmailAccountConfig, password: AppPassword): Store {
        val session = Session.getInstance(properties(config))
        val store = session.getStore(PROTOCOL)
        try {
            // The one call site of revealForImapLogin(), and the one PiiLintTest counts. The
            // value goes straight into JavaMail and is never assigned to a local, so it
            // cannot reach a log line here.
            store.connect(config.host, config.port, config.address, password.revealForImapLogin())
        } catch (e: AuthenticationFailedException) {
            // `e` is dropped on purpose: its message quotes the server reply, which on some
            // providers echoes the login (spec 04 section 4.1).
            closeQuietly(store)
            throw MailboxException.Auth()
        } catch (e: MessagingException) {
            closeQuietly(store)
            throw MailboxException.Network(e)
        } catch (e: IOException) {
            closeQuietly(store)
            throw MailboxException.Network(e)
        }
        return store
    }

    private fun openFolder(store: Store, name: String): Folder {
        val folder = mapErrors(name) { store.getFolder(name) }
            ?: throw MailboxException.FolderMissing(name)
        if (!mapErrors(name) { folder.exists() }) throw MailboxException.FolderMissing(name)
        // READ_ONLY is a hard requirement (spec 11 R-11-02): opening READ_WRITE would let
        // Gmail mark the bank mail read behind the user's back.
        mapErrors(name) { folder.open(Folder.READ_ONLY) }
        return folder
    }

    private fun properties(config: EmailAccountConfig): Properties = Properties().apply {
        put("mail.store.protocol", PROTOCOL)
        put("mail.imaps.host", config.host)
        put("mail.imaps.port", config.port.toString())
        put("mail.imaps.connectiontimeout", TIMEOUT_MS)
        put("mail.imaps.timeout", TIMEOUT_MS)
        put("mail.imaps.writetimeout", TIMEOUT_MS)
        // A partial fetch streams the body in chunks and, on a flaky mobile link, can leave a
        // truncated body that still hashes as if it were complete (spec 11 section 11.3).
        put("mail.imaps.partialfetch", "false")
        put("mail.imaps.fetchsize", FETCH_SIZE)
        put("mail.imaps.ssl.enable", "true")
        // Without checkserveridentity JavaMail accepts any chain that validates, including one
        // issued for a different host. This is spec 04 section 4.1's MITM mitigation.
        put("mail.imaps.ssl.checkserveridentity", "true")
        put("mail.imaps.ssl.protocols", TLS_PROTOCOLS)
        // No STARTTLS, ever: this store is imaps, and an upgrade path is an attack path.
        put("mail.imaps.starttls.enable", "false")
        put("mail.imaps.starttls.required", "false")
        // Marketing senders emit From headers RFC 5322 does not allow. Strict parsing throws
        // and costs the whole batch; lenient parsing loses nothing the registry needs.
        put("mail.mime.address.strict", "false")
    }

    private fun folderNames(store: Store): List<String> = try {
        store.defaultFolder.list("*")
            .filterNotNull()
            .filter { (it.type and Folder.HOLDS_MESSAGES) != 0 }
            .map { it.fullName }
            .sorted()
    } catch (e: MessagingException) {
        // An empty folder picker is a far smaller failure than a sync that stops because the
        // server would not enumerate its folders.
        emptyList()
    }

    private fun closeQuietly(folder: Folder) {
        // close(false) = do not expunge. R-11-02 in one argument.
        runCatching { if (folder.isOpen) folder.close(false) }
    }

    private fun closeQuietly(store: Store) {
        runCatching { if (store.isConnected) store.close() }
    }

    // -- the open folder ----------------------------------------------------------------

    /**
     * Wraps one open folder. Everything it hands back is a plain data class, so nothing
     * survives past [withSession] to throw later against a closed folder.
     */
    private inner class JavaMailSession(
        private val folder: Folder,
        override val mailbox: String
    ) : MailboxSession {

        // Every IMAP folder is a UIDFolder; this branch is reachable only if the store
        // protocol were changed away from imaps. Reported as Network so the worker retries
        // and shows an amber banner instead of dying on a cast.
        private val uidFolder: UIDFolder = folder as? UIDFolder
            ?: throw MailboxException.Network(IllegalStateException("folder is not a UIDFolder"))

        /** Read once at open: step 5 compares it with the stored value before anything is fetched. */
        override val uidValidity: Long = mapErrors(mailbox) { uidFolder.uidValidity }

        override val messageCount: Int = mapErrors(mailbox) { folder.messageCount }

        /** What [perMessage] contained, in the order it happened. Guarded; see [record]. */
        private val skipped = mutableListOf<UnreadableMessage>()

        override val unreadable: List<UnreadableMessage>
            get() = synchronized(skipped) { skipped.toList() }

        override suspend fun listFolders(): List<String> = withContext(Dispatchers.IO) {
            folderNames(folder.store)
        }

        override suspend fun searchUids(
            searchTerms: List<String>,
            sinceUid: Long,
            receivedSince: Instant?,
            limit: Int
        ): List<Long> = withContext(Dispatchers.IO) {
            // A search with no FROM terms matches the whole mailbox. R-11-03 exists so the
            // phone never downloads unrelated mail, so an empty term list fetches nothing.
            if (searchTerms.isEmpty() || limit <= 0) return@withContext emptyList<Long>()

            val fromTerm = orFrom(searchTerms)
            val matches: List<Message> = mapErrors(mailbox) {
                if (sinceUid > 0) {
                    // Incremental run: ask only about UIDs above the watermark, then let the
                    // server apply the FROM filter to that window (R-11-04).
                    val window = uidFolder.getMessagesByUID(sinceUid + 1, UIDFolder.LASTUID)
                        ?.filterNotNull()
                        .orEmpty()
                    if (window.isEmpty()) {
                        emptyList<Message>()
                    } else {
                        folder.search(fromTerm, window.toTypedArray())?.filterNotNull().orEmpty()
                    }
                } else {
                    // First run, or a run after a UIDVALIDITY reset: the whole folder bounded
                    // by the lookback. A null bound is the user's "all mail" choice.
                    val term = receivedSince?.let {
                        AndTerm(fromTerm, ReceivedDateTerm(ComparisonTerm.GE, Date.from(it)))
                    } ?: fromTerm
                    folder.search(term)?.filterNotNull().orEmpty()
                }
            }

            // Contained for the same reason the fetch loop contains its reads: one message
            // the server will not answer for must not end the run. A dropped UID is simply
            // not a candidate — it is below no watermark and will be searched again.
            val uids = matches.mapNotNull { message ->
                perMessage(uid = null) { uidFolder.getUID(message) }
            }
            MailboxUidWindow.select(uids, sinceUid, limit)
        }

        override suspend fun fetch(uids: List<Long>, maxBodyKb: Int): List<FetchedMessage> =
            withContext(Dispatchers.IO) {
                if (uids.isEmpty()) return@withContext emptyList<FetchedMessage>()

                val messages = mapErrors(mailbox) { uidFolder.getMessagesByUID(uids.toLongArray()) }
                    ?.filterNotNull()
                    .orEmpty()
                if (messages.isEmpty()) return@withContext emptyList<FetchedMessage>()

                // One round trip for envelope, flags and bodystructure (spec 11 step 8).
                mapErrors(mailbox) { folder.fetch(messages.toTypedArray(), fetchProfile()) }

                val byUid = HashMap<Long, Message>(messages.size)
                for (message in messages) {
                    // The UID is the only handle there is on a message. When reading *it* is
                    // what failed there is nothing to key a log row on, so the failure is
                    // recorded with a null uid (unknown stays unknown — spec 01 C7) and the
                    // message leaves the batch.
                    val uid = perMessage(uid = null) { uidFolder.getUID(message) } ?: continue
                    if (uid > 0) byUid[uid] = message
                }

                // Ordered by the caller's list; a UID the server no longer has, and one whose
                // headers would not parse, are simply absent rather than a hole, a null, or an
                // exception.
                uids.mapNotNull { uid -> byUid[uid]?.let { toFetched(uid, it, maxBodyKb) } }
            }

        /**
         * Read from the capability list JavaMail cached at connect — no extra round trip, and
         * no failure path. `hasCapability` can only throw if the store is already gone, and a
         * *discovery* feature that made an ordinary sync throw would be a far worse bug than
         * one that quietly decides this server is not Gmail.
         */
        override val supportsGmailRawSearch: Boolean =
            runCatching { (folder.store as? IMAPStore)?.hasCapability(GMAIL_EXT) == true }
                .getOrDefault(false)

        override suspend fun searchRaw(
            query: String,
            receivedSince: Instant?,
            limit: Int
        ): List<Long>? = withContext(Dispatchers.IO) {
            // Null, not empty: this server was never asked (see the interface KDoc).
            if (!supportsGmailRawSearch || limit <= 0) return@withContext null
            // A blank query is an X-GM-RAW that matches the whole mailbox, and CR or LF in it
            // is command injection into the protocol stream — a second IMAP command smuggled
            // onto the wire under this session's authentication. Rejected rather than escaped:
            // there is no legitimate newline in a Gmail search query, so refusing is free.
            if (query.isBlank() || query.any { it == '\r' || it == '\n' }) return@withContext null

            // JavaMail has no SearchTerm for X-GM-RAW, so the command is written by hand. This
            // is the only place in the app that speaks IMAP directly.
            val imap = folder as? IMAPFolder ?: return@withContext null
            val raw = mapErrors(mailbox) {
                imap.doCommand { protocol ->
                    val since = receivedSince?.let { "SINCE " + IMAP_DATE.format(it) + " " }.orEmpty()
                    // UID SEARCH, not SEARCH: the numbers that come back have to be UIDs, or
                    // they would be sequence numbers silently compared against a UID window.
                    val command = "UID SEARCH " + since + "X-GM-RAW " + quoteImap(query)
                    val responses: Array<Response>? = protocol.command(command, null)
                    val uids = parseSearchResponse(responses)
                    // Untagged EXISTS / EXPUNGE / RECENT can ride along on any command. Handing
                    // them back to JavaMail keeps the folder's own view of the mailbox honest;
                    // it reads no body and sets no flag, so R-11-02 is untouched.
                    if (responses != null) protocol.notifyResponseHandlers(responses)
                    uids
                } as? List<*>
            } ?: return@withContext null

            // Same window arithmetic as searchUids: ascending, de-duplicated, non-positive
            // dropped. sinceUid is 0 because discovery is not watermarked — it neither reads
            // settings.email.lastSyncedUid nor may advance it (R-11-07).
            MailboxUidWindow.select(raw.filterIsInstance<Long>(), sinceUid = 0L, limit = limit)
        }

        override suspend fun peekSenders(uids: List<Long>): List<SenderSighting> =
            withContext(Dispatchers.IO) {
                if (uids.isEmpty()) return@withContext emptyList<SenderSighting>()

                val messages = mapErrors(mailbox) { uidFolder.getMessagesByUID(uids.toLongArray()) }
                    ?.filterNotNull()
                    .orEmpty()
                if (messages.isEmpty()) return@withContext emptyList<SenderSighting>()

                // ENVELOPE + UID and nothing else. Not CONTENT_INFO, which fetch() needs for
                // attachment names, and emphatically not a body part: RFC 3501 sets \Seen on a
                // `BODY[…]` fetch, and this session is the owner's real mailbox (R-11-02 / C2).
                mapErrors(mailbox) { folder.fetch(messages.toTypedArray(), envelopeProfile()) }

                val byUid = HashMap<Long, Message>(messages.size)
                for (message in messages) {
                    val uid = perMessage(uid = null) { uidFolder.getUID(message) } ?: continue
                    if (uid > 0) byUid[uid] = message
                }

                // Ordered by the caller's list; a UID the server no longer has, and one whose
                // From header will not parse, are absent rather than a hole or an exception.
                uids.mapNotNull { uid ->
                    byUid[uid]?.let { message -> perMessage(uid) { sightingOf(uid, message) } }
                }
            }

        /** One message, or null when it was skipped — see [perMessage] for which is which. */
        private fun toFetched(uid: Long, message: Message, maxBodyKb: Int): FetchedMessage? {
            // Contained per message. Spec 02 §2.7 grants that to a parser that throws on one
            // message; a header that throws has to have it too, and for a harder reason. The
            // watermark advances per *committed batch* (R-11-07), so a header failure thrown
            // out of here would abort the batch, leave the watermark below this UID, and make
            // every later run fetch this same message and die on it again — one malformed
            // From header would stop the email channel permanently.
            val header = perMessage(uid) { headerOf(uid, message) } ?: return null
            // Spec 11 step 8 says bodies are fetched lazily per message. They cannot be: a
            // JavaMail Message is only readable while its folder is open, so a lazy body would
            // throw once the session closed. Extracting inside the session costs one pass over
            // the batch and keeps every javax.mail type behind this class.
            // EmailBodyExtractor documents that it never throws; this is the belt to that
            // brace. One unreadable body must not cost the batch (spec 02 section 2.7), and
            // the message is still returned so R-11-05 logs it and the Email Log can show why
            // it is bare — `sourceMimeType = "none"` on a mail that plainly had a body is that
            // record, which is why this one is not also filed under `unreadable`.
            // ExtractedBody.EMPTY carries an empty body_hash, which dedup L0 is required to
            // treat as "no evidence" rather than as a match.
            val body = try {
                bodyExtractor.extract(message, maxBodyKb)
            } catch (e: FolderClosedException) {
                // Not this message's fault: the folder is gone, so every remaining read in
                // this batch would "fail" too. Report it as what it is and let the worker
                // retry (spec 11 §11.3, last line).
                throw MailboxException.Network(e)
            } catch (e: StoreClosedException) {
                throw MailboxException.Network(e)
            } catch (e: MessagingException) {
                ExtractedBody.EMPTY
            } catch (e: IOException) {
                ExtractedBody.EMPTY
            }
            return FetchedMessage(header, body)
        }

        /** [readOneMessage], with a contained failure filed against [uid]. */
        private fun <T> perMessage(uid: Long?, body: () -> T): T? =
            readOneMessage(onContained = { failure -> record(uid, failure) }, body = body)

        private fun record(uid: Long?, failure: Throwable) {
            // `fetch` runs on Dispatchers.IO and a session outlives one call, so the list is
            // guarded rather than assumed single-threaded. detailOf keeps it a class name:
            // a provider message can quote the login, and the subject is the user's mail.
            synchronized(skipped) { skipped += UnreadableMessage(uid, detailOf(failure)) }
        }

        private fun fetchProfile(): FetchProfile = FetchProfile().apply {
            add(FetchProfile.Item.ENVELOPE)
            add(FetchProfile.Item.FLAGS)
            // BODYSTRUCTURE: attachment names without a second round trip (R-11-12).
            add(FetchProfile.Item.CONTENT_INFO)
            add(UIDFolder.FetchProfileItem.UID)
        }

        /**
         * [peekSenders]'s profile: who sent it and when, and nothing else.
         *
         * Deliberately [fetchProfile] minus FLAGS and minus CONTENT_INFO. It is not a smaller
         * version of the same request — it is the boundary that keeps a widened *search* from
         * becoming a widened *download*: discovery peeks senders the registry has never heard
         * of, and this profile is why doing so cannot pull their mail onto the phone.
         */
        private fun envelopeProfile(): FetchProfile = FetchProfile().apply {
            add(FetchProfile.Item.ENVELOPE)
            add(UIDFolder.FetchProfileItem.UID)
        }

        private fun orFrom(terms: List<String>): SearchTerm {
            val from = terms.map { FromStringTerm(it) as SearchTerm }
            // An OrTerm of one is legal but some servers answer it oddly; send the bare term.
            return if (from.size == 1) from.first() else OrTerm(from.toTypedArray())
        }
    }

    companion object {
        private const val PROTOCOL = "imaps"
        private const val TIMEOUT_MS = "25000"
        private const val FETCH_SIZE = "65536"
        private const val TLS_PROTOCOLS = "TLSv1.2 TLSv1.3"

        /** Gmail's CAPABILITY advertisement for X-GM-RAW, X-GM-MSGID, X-GM-THRID and X-GM-LABELS. */
        private const val GMAIL_EXT = "X-GM-EXT-1"

        /**
         * RFC 3501 `date-text` for a SEARCH key: `1-Sep-2026`, one or two digits for the day.
         *
         * Locale.US because the month is a fixed protocol token, not a display date — a phone
         * in a Hindi or Turkish locale must still send `Sep`. UTC because IMAP compares the
         * server's own INTERNALDATE against it and the phone's zone is not a factor.
         */
        private val IMAP_DATE: DateTimeFormatter =
            DateTimeFormatter.ofPattern("d-MMM-uuuu", Locale.US).withZone(ZoneOffset.UTC)
    }
}

/**
 * Runs one message's read, containing what is that message's problem and rethrowing what is
 * the connection's.
 *
 * The line between the two is the whole point. Contained, so that the batch continues:
 * a `From` header no address parser accepts, a `Content-Type` naming a charset the JVM does
 * not know, a message expunged between the search and the fetch. Not contained:
 * [FolderClosedException] and [StoreClosedException], which mean the socket is gone — every
 * remaining message in the batch would then fail one by one, the batch would look complete,
 * and the watermark would advance over mail that was never read. That is silent data loss,
 * so it is reported as [MailboxException.Network] and the worker retries (spec 11 §11.3,
 * last line).
 *
 * File-level and `internal` rather than a method on the session: the rule is about
 * exceptions, not about folders, and this way it can be tested without standing up a
 * `javax.mail` `Folder`.
 *
 * @param onContained called with the failure when one is contained, so the caller can file
 *   it under [MailboxSession.unreadable]. Not called for a message the server expunged:
 *   that is an absence, not a fault, and logging it as `ERROR:` would invent a failure.
 * @return the value, or null when the read was contained.
 */
internal fun <T> readOneMessage(onContained: (Throwable) -> Unit, body: () -> T): T? = try {
    body()
} catch (e: MailboxException) {
    throw e
} catch (e: FolderClosedException) {
    throw MailboxException.Network(e)
} catch (e: StoreClosedException) {
    throw MailboxException.Network(e)
} catch (e: AuthenticationFailedException) {
    // Mid-session re-authentication failed; `e` is dropped for the reason `connect` drops it
    // — its message can quote the login (spec 04 §4.1).
    throw MailboxException.Auth()
} catch (e: MessageRemovedException) {
    // The user deleted the mail between the search and the fetch.
    null
} catch (e: MessagingException) {
    onContained(e)
    null
} catch (e: IOException) {
    // A per-part decoding failure, not a dead socket: JavaMail signals a lost connection with
    // FolderClosedException / StoreClosedException, both caught above.
    onContained(e)
    null
}

/**
 * The IMAP envelope as [MailboxMessageHeader].
 *
 * Every read in here can throw on a malformed header, which is why callers wrap it in
 * [readOneMessage] rather than trusting it.
 */
internal fun headerOf(uid: Long, message: Message): MailboxMessageHeader {
    val rawFrom = message.from?.firstOrNull()
    val address = (rawFrom as? InternetAddress)?.address ?: rawFrom?.toString()
    // Normalised here rather than downstream so `email_messages.from_address` and the
    // registry lookup can never disagree about what this sender is called.
    val from = EmailSenderRegistry.normaliseAddress(address)
        ?: address?.trim()?.lowercase()
        ?: ""
    val messageId = (message as? MimeMessage)?.messageID?.trim()?.takeIf { it.isNotEmpty() }
    // INTERNALDATE first. sentDate is a fallback only because a header-less message
    // still has to be logged; RFC 3501 makes the null branch unreachable on a
    // compliant server, and dropping the message would be the larger data loss.
    val receivedAt = (message.receivedDate ?: message.sentDate)?.toInstant() ?: Instant.now()
    return MailboxMessageHeader(
        uid = uid,
        messageId = messageId,
        from = from,
        subject = message.subject.orEmpty(),
        receivedAt = receivedAt
    )
}

/**
 * The same envelope as [SenderSighting]: the sender, the phrase they gave themselves, and
 * when the server received it. No subject and no body — a sighting is evidence about a
 * sender, and must not be able to carry the content of the owner's mail.
 *
 * Not a variant of [headerOf], and that is the point. `headerOf` normalises `From` down to a
 * bare address and drops the display phrase; there is no way back from `bigbasket.com` to
 * `BigBasket`, so a discovery screen built on `MailboxMessageHeader` could only ever label
 * rows by domain. This reads `InternetAddress.getPersonal()` — already RFC 2047 decoded by
 * JavaMail — beside the address, and hands both on untouched. Deciding whether the phrase is
 * a *name* is `SenderDisplayName`'s job in `:parser-core`, because that is regex text
 * handling and CLAUDE.md constraint 5 keeps it out of `:app`.
 *
 * Every read here can throw on a malformed header, so callers wrap it in [readOneMessage].
 */
internal fun sightingOf(uid: Long, message: Message): SenderSighting {
    val rawFrom = message.from?.firstOrNull()
    val internet = rawFrom as? InternetAddress
    val address = internet?.address ?: rawFrom?.toString()
    // Normalised exactly as headerOf normalises, so a domain discovery proposes is the same
    // string EmailSenderRegistry.resolve will later be asked about.
    val normalised = EmailSenderRegistry.normaliseAddress(address)
        ?: address?.trim()?.lowercase()
        ?: ""
    // Blank is not a phrase. A group address or a header JavaMail could not parse into an
    // InternetAddress has no personal part at all, and null there means "the sender named
    // nothing" — never a name reconstructed from the address (spec 01 constraint C7).
    val phrase = internet?.personal?.trim()?.takeIf { it.isNotEmpty() }
    // INTERNALDATE first, sentDate second, and — for the reason headerOf documents — `now`
    // only for a message carrying no date at all, which RFC 3501 makes unreachable against a
    // real server. firstSeen/lastSeen are built from this and from nothing else.
    val receivedAt = (message.receivedDate ?: message.sentDate)?.toInstant() ?: Instant.now()
    return SenderSighting(
        uid = uid,
        address = normalised,
        displayPhrase = phrase,
        receivedAt = receivedAt
    )
}

/**
 * The untagged `* SEARCH 12 34 56` lines of a `UID SEARCH` response, as UIDs.
 *
 * File-level `internal` for the reason [readOneMessage] is: the rule is about parsing a
 * protocol response, not about folders, so it is testable without standing up a `Folder`.
 *
 * A non-OK **tagged** response yields an empty list rather than throwing — a server that
 * declines the query has told us there are no candidates, and discovery is optional. That is
 * also why a `NO` is not an error: Gmail answers `NO` to an `X-GM-RAW` it dislikes, and a
 * malformed saved query must cost one skipped scan, never a failed sync.
 *
 * Numbers stop at the first token that is not a digit run, so the `(MODSEQ 1234)` that a
 * CONDSTORE server appends to `* SEARCH` is ignored rather than read as a UID. `* ESEARCH`
 * and every other untagged line is ignored outright: only `SEARCH` is claimed.
 */
internal fun parseSearchResponse(responses: Array<Response>?): List<Long> {
    if (responses == null) return emptyList()

    val uids = mutableListOf<Long>()
    // filterNotNull rather than a null check in the loop: the parameter is declared with
    // non-null elements, so the check would read as dead code, and JavaMail hands this array
    // back from Java where the element type is a platform type and a null is still possible.
    for (response in responses.filterNotNull()) {
        if (response.isTagged) {
            // The tagged line terminates the command. NO or BAD means the server refused, and
            // anything already collected came from a command that did not complete.
            if (!response.isOK) return emptyList()
            continue
        }
        if (!response.isUnTagged) continue

        // parse() left `pindex` just past the `*`, so reset() puts the cursor on the key.
        response.reset()
        if (!"SEARCH".equals(response.readAtom(), ignoreCase = true)) continue

        // readLong answers -1 without advancing once the next token is not a digit run, so
        // this terminates on `(MODSEQ …)`, on trailing spaces and at end of line alike.
        while (true) {
            val uid = response.readLong()
            if (uid < 0) break
            uids += uid
        }
    }
    return uids
}

/**
 * [value] as an RFC 3501 quoted string: wrapped in double quotes, with `\` and `"`
 * backslash-escaped.
 *
 * The escaping is not cosmetic. An unescaped `"` inside the query would close the string
 * early and let the rest of it be read as further SEARCH keys — the same class of mistake as
 * an unescaped quote in SQL, against a session already authenticated as the owner. CR and LF
 * cannot be escaped into safety at all, which is why `searchRaw` refuses them upstream rather
 * than passing them here.
 */
internal fun quoteImap(value: String): String {
    val escaped = StringBuilder(value.length + 2)
    escaped.append('"')
    for (character in value) {
        if (character == '\\' || character == '"') escaped.append('\\')
        escaped.append(character)
    }
    escaped.append('"')
    return escaped.toString()
}

/**
 * Maps provider failures onto [MailboxException], the only error vocabulary the worker
 * understands. An [AuthenticationFailedException] loses its message here for the same reason
 * it does in `connect`.
 */
private inline fun <T> mapErrors(mailbox: String, body: () -> T): T = try {
    body()
} catch (e: MailboxException) {
    throw e
} catch (e: AuthenticationFailedException) {
    throw MailboxException.Auth()
} catch (e: FolderNotFoundException) {
    throw MailboxException.FolderMissing(mailbox)
} catch (e: MessagingException) {
    throw MailboxException.Network(e)
} catch (e: IOException) {
    throw MailboxException.Network(e)
}

/**
 * A loggable description of a failure: the class name of the root cause and nothing else.
 * Never the message — spec 04 section 4.1 forbids anything that could quote the login.
 */
private fun detailOf(error: Throwable): String = (error.cause ?: error).javaClass.simpleName
