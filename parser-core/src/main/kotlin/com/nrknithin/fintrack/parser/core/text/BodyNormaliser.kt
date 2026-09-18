package com.nrknithin.fintrack.parser.core.text

import com.nrknithin.fintrack.parser.core.md5Hex

/**
 * Canonicalises an extracted email body so that two deliveries of the same mail hash equal
 * (spec 11 §11.5 step 4).
 *
 * It lives in `:parser-core` because it is regex text handling (spec 02 §2.1) and because
 * `email_messages.body_hash` is **persisted**: the normaliser must never be re-implemented
 * on the app side, or every stored hash silently changes meaning and the L0 dedup layer
 * (spec 14 §14.3) stops recognising mail it has already seen.
 *
 * What is removed is exactly what varies between two deliveries of one mail:
 *  - **case** — senders re-template headings between months;
 *  - **URLs** — every tracking link carries a per-delivery id, so leaving them in would make
 *    two copies of the same bill hash differently, which is the failure this exists to stop;
 *  - **zero-width and bidi controls** — invisible, and some senders sprinkle them to defeat
 *    spam clustering;
 *  - **whitespace runs** — line wrapping is decided by the transport, not by the sender.
 *
 * Digits are **kept**: the amount, the last-4 and the reference are what make one body
 * distinguishable from the next month's copy of the same template.
 */
object BodyNormaliser {

    /**
     * Lower-cased, URL-free, whitespace-collapsed form of [raw].
     *
     * Returns an empty string for a body that was nothing but links and blanks — the caller
     * decides what an empty normal form means (`EmailBodyExtractor` stores no hash for it,
     * rather than the md5 of the empty string, which every such body would share).
     */
    fun normalise(raw: String): String {
        if (raw.isEmpty()) return ""
        var text = raw.lowercase()
        // Invisible characters go first: one sitting inside a URL would otherwise break the
        // URL match and leave half a tracking link in the hash.
        text = ZERO_WIDTH.replace(text, "")
        text = URL.replace(text, " ")
        text = WHITESPACE.replace(text, " ")
        return text.trim()
    }

    /**
     * `md5Hex(normalise(raw))` — the value stored in `email_messages.body_hash` and
     * `transaction_sources.body_hash`.
     *
     * md5 rather than a stronger digest on purpose: this is a same-message key, not a
     * security boundary (spec 03 §3.2), and `md5Hex` is the one hash `:parser-core` already
     * carries on every platform.
     */
    fun bodyHash(raw: String): String = md5Hex(normalise(raw))

    /**
     * A URL, and only a URL: the scheme (or a bare `www.`) plus everything up to the next
     * space or angle bracket. Deliberately not a general "looks like a domain" pattern —
     * `godaddy.com` as a bare word inside a renewal notice is content, not a link.
     */
    private val URL = Regex(
        """(?:https?://|ftp://|mailto:|www\.)[^\s<>"'\]\)]+""",
        RegexOption.IGNORE_CASE
    )

    /** Soft hyphen, zero-width family, bidi embedding controls, BOM. */
    private val ZERO_WIDTH = Regex("""[\u00AD\u200B-\u200F\u202A-\u202E\u2060\uFEFF]""")

    /**
     * Whitespace including the non-breaking kinds. Java's `\s` covers neither `\u00A0` nor
     * the typographic spaces HTML entities decode to, and email bodies are full of both.
     */
    private val WHITESPACE = Regex("""[\s\u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000]+""")
}
