package com.pennywiseai.parser.core.email

/**
 * Turning a From header's display phrase into a name a person would recognise, and
 * grouping addresses into the domain that owns them.
 *
 * In `:parser-core`, not in `:app`, because every rule here is regex text handling and
 * `CLAUDE.md` constraint 5 puts that in this module. It sits next to
 * [EmailSenderRegistry.normaliseAddress] on purpose: the two must agree about what an
 * address is, or a discovered domain will not match the domain the registry later
 * resolves against.
 */
object SenderDisplayName {

    /**
     * Some senders push a subject line into the display phrase. A name that long is not a
     * name, and it would be the widest column on a screen that exists to be skimmed.
     */
    const val MAX_NAME_LENGTH = 64

    /**
     * Two-label public suffixes this build recognises.
     *
     * A heuristic, and named as one — see [registrableDomain] for why there is no
     * public-suffix list here.
     */
    val MULTI_LABEL_SUFFIXES: Set<String> = setOf(
        "co.in", "net.in", "org.in", "gov.in", "ac.in", "co.uk", "com.au", "co.jp"
    )

    /**
     * A local part, an at-sign and a dotted domain, with nothing else around it — the shape
     * of `someone@example.com`. Used only to recognise a phrase that is
     * itself an address; address *extraction* stays [EmailSenderRegistry.normaliseAddress]'s
     * job so the two can never disagree about what an address is.
     */
    private val ADDRESS_SHAPE = Regex("""^[^\s@]+@[^\s@]+\.[^\s@]+$""")

    /** `.`, `-` and `_` carry no meaning when comparing a phrase to a mailbox name. */
    private val SEPARATORS = Regex("""[.\-_]""")

    /**
     * The phrase as a name, or **null** when the header offered nothing usable.
     *
     * Null, never a name derived from the address. `bigbasket` cannot be title-cased back
     * into `BigBasket`, `1mg` becomes `1Mg` and `irctc` becomes `Irctc`; a proper noun
     * that was never sent cannot be recovered, and inventing one is exactly what spec 01
     * constraint C7 forbids. A null name means the UI labels the row by its domain.
     *
     * Rejected as "not a name":
     *  - blank after trimming, unwrapping quotes and undoing backslash escapes;
     *  - contains `@` and parses as an address — `"noreply@bigbasket.com" <noreply@…>`
     *    is the sender repeating itself, not naming itself;
     *  - equals the address's local part or its domain once `.`, `-` and `_` are
     *    stripped and case is ignored — `"no-reply" <no-reply@example.com>` is a mailbox name;
     *  - holds no letter at all;
     *  - longer than [MAX_NAME_LENGTH] (64).
     */
    fun fromPhrase(phrase: String?, address: String): String? {
        val cleaned = unwrap(phrase) ?: return null
        if (cleaned.length > MAX_NAME_LENGTH) return null
        // A phrase of digits and punctuation is a reference number or a date, not a name.
        if (cleaned.none { it.isLetter() }) return null
        if (cleaned.contains('@') && ADDRESS_SHAPE.matches(cleaned)) return null

        // The same normalisation the registry applies, so "is this phrase just the mailbox
        // name again?" is asked about the address the registry would resolve.
        val bare = EmailSenderRegistry.normaliseAddress(address) ?: address.trim().lowercase()
        val squashed = squash(cleaned)
        if (squashed.isEmpty()) return null
        if (squashed == squash(bare.substringBefore('@'))) return null
        val domain = bare.substringAfter('@', "")
        if (domain.isNotEmpty() && squashed == squash(domain)) return null

        return cleaned
    }

    /**
     * The domain a group of addresses belongs to: last two labels, or three when the last
     * two are one of [MULTI_LABEL_SUFFIXES] (`co.in`, `net.in`, `org.in`, `gov.in`, `ac.in`,
     * `co.uk`, `com.au`, `co.jp`).
     *
     * A heuristic, and named as one: there is no public-suffix list in this build and
     * shipping one for a discovery screen is not worth 200 KB. `co.in` is not optional —
     * `irctc.co.in` is already in the registry, so the three-label case is proven, not
     * hypothetical. The failure mode of an unlisted suffix is two unrelated merchants
     * merged into one proposed row; because discovery only ever *proposes*, the cost is
     * one row the owner declines, never a wrong write.
     *
     * Returns null for an address with no `@` or no dot in its domain.
     */
    fun registrableDomain(address: String): String? {
        val bare = EmailSenderRegistry.normaliseAddress(address) ?: return null
        val domain = bare.substringAfter('@', "")
        val labels = domain.split('.').filter { it.isNotEmpty() }
        if (labels.size < 2) return null
        val lastTwo = labels.takeLast(2).joinToString(".")
        if (labels.size >= 3 && lastTwo in MULTI_LABEL_SUFFIXES) {
            return labels.takeLast(3).joinToString(".")
        }
        return lastTwo
    }

    /**
     * Trims, strips one layer of RFC 5322 quoting and undoes its backslash escapes.
     *
     * JavaMail's `InternetAddress.getPersonal()` already does this for a well-formed header,
     * so on most mail this changes nothing. It exists for the headers that are not
     * well-formed — a quoted-string inside a quoted-string, or a phrase a sender quoted by
     * hand — where the alternative is a name that ships with its own punctuation.
     */
    private fun unwrap(phrase: String?): String? {
        var value = phrase?.trim().orEmpty()
        if (value.length >= 2 && value.first() == '"' && value.last() == '"') {
            value = value.substring(1, value.length - 1)
        }
        if (value.contains('\\')) {
            val out = StringBuilder(value.length)
            var escaped = false
            for (ch in value) {
                when {
                    escaped -> { out.append(ch); escaped = false }
                    ch == '\\' -> escaped = true
                    else -> out.append(ch)
                }
            }
            // A trailing lone backslash is dropped rather than kept: it is the header being
            // malformed, and keeping it would put a stray mark in the middle of a name.
            value = out.toString()
        }
        value = value.trim()
        return value.ifEmpty { null }
    }

    /** Lower-case with `.`, `-` and `_` removed — the form phrase/mailbox comparisons use. */
    private fun squash(value: String): String = SEPARATORS.replace(value.lowercase(), "")
}
