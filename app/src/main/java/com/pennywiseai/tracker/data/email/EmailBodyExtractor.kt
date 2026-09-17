package com.pennywiseai.tracker.data.email

import com.pennywiseai.parser.core.text.BodyNormaliser
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton
import javax.mail.Multipart
import javax.mail.Part

/**
 * Table cells. File-level rather than in a companion so the nested rendering visitor can
 * see them without qualification.
 */
private val CELL_TAGS = setOf("td", "th")

/** Tags that end a line of reading matter. */
private val BLOCK_TAGS = setOf(
    "p", "div", "table", "thead", "tbody", "tfoot", "caption",
    "ul", "ol", "li", "dl", "dt", "dd",
    "h1", "h2", "h3", "h4", "h5", "h6",
    "blockquote", "pre", "hr", "address", "center",
    "section", "article", "header", "footer", "main", "nav", "aside",
    "form", "fieldset", "figure", "figcaption", "details", "summary"
)

/**
 * What one message's MIME tree reduced to (spec 11 §11.5).
 *
 * [text] is already truncated to the configured cap; [bodyHash] is the hash of the text
 * **before** truncation, so a body that grows past the cap still recognises its own
 * re-delivery (spec 11 R-11-08).
 */
data class ExtractedBody(
    val text: String,
    /**
     * `BodyNormaliser.bodyHash` of the untruncated text, or `""` when the body normalises
     * to nothing.
     *
     * The empty case is deliberate. Spec 11 §11.5 step 4 says "body_hash = md5" without
     * qualification, but md5 of an empty normal form is one fixed string that *every*
     * bodyless mail would carry — and dedup layer L0 (spec 14 §14.3) merges on equal body
     * hashes. Two unrelated image-only mails would then collapse into one transaction.
     * An unknown hash is recorded as unknown (spec 01 constraint C7); callers must treat
     * `""` as "no evidence", never as a match.
     */
    val bodyHash: String,
    val hasAttachments: Boolean,
    val attachmentNames: List<String>,
    val truncated: Boolean,
    /** `"text/plain"`, `"text/html"`, or `"none"` — shown in the Email Log when a body looks wrong. */
    val sourceMimeType: String
) {
    companion object {
        /** `text/plain` — both the MIME type asked for, and a [sourceMimeType] value. */
        const val MIME_PLAIN = "text/plain"

        /** `text/html` — likewise. */
        const val MIME_HTML = "text/html"

        /** No usable body: an image-only mail, or one whose every part failed to decode. */
        const val MIME_NONE = "none"

        val EMPTY = ExtractedBody(
            text = "",
            bodyHash = "",
            hasAttachments = false,
            attachmentNames = emptyList(),
            truncated = false,
            sourceMimeType = MIME_NONE
        )
    }
}

/**
 * Turns a MIME message into the single text string every parser, hash and log entry in the
 * email channel is defined over (spec 11 §11.5).
 *
 * Three properties are load-bearing elsewhere and must not be changed casually:
 *
 * 1. **Table cells are joined with `" | "` between them** — not around them. Every
 *    HTML-only sender in spec 12 is parsed against that shape: `SBIEmailParser` reads
 *    `label | value` pairs and treats a line edge as a cell boundary,
 *    `GoDaddyRenewalDueParser` reads one domain per line as
 *    `domain | Cancels dd-mm-yyyy | price | 1 year`, and `OneCardEmailParser` tolerates a
 *    pipe wherever it tolerates a space.
 * 2. **One `<tr>` per line.** A GoDaddy notice is several dues in one mail, and the row is
 *    the only thing separating them.
 * 3. **Whitespace inside a line collapses to single spaces, and blank lines are dropped.**
 *    Wrapping is chosen by the transport, so keeping it would make `body_hash` differ
 *    between two deliveries of one mail — the exact failure the hash exists to prevent.
 *
 * The class holds no state and touches no Android API: [fromParts] and [htmlToText] are
 * pure functions, which is what makes the HTML shapes of spec 10 testable with a string
 * and no mail session.
 */
@Singleton
class EmailBodyExtractor @Inject constructor() {

    /**
     * Walks [part]'s MIME tree depth-first and returns the best text (spec 11 §11.5
     * steps 1-3), plus the attachment names of step 3.
     *
     * Takes `javax.mail.Part` rather than `Message` so a nested `message/rfc822` attachment
     * walks with the same code.
     *
     * Never throws: a part whose content cannot be decoded is skipped, because one broken
     * MIME part must not cost the user the rest of the message (spec 02 §2.7).
     */
    fun extract(part: Part, maxBodyKb: Int = DEFAULT_MAX_BODY_KB): ExtractedBody {
        val walk = Walk()
        visit(part, walk, depth = 0)
        val body = fromParts(walk.plain, walk.html, walk.attachmentNames.toList(), maxBodyKb)
        // An attachment with neither a filename nor a Content-Type name still happened;
        // the flag says so even though there is no name to list.
        return if (walk.hasAttachments && !body.hasAttachments) {
            body.copy(hasAttachments = true)
        } else {
            body
        }
    }

    /**
     * The same decision without any MIME at all — this is what the unit tests drive, and
     * what a future re-processing path can call over a stored body.
     *
     * Prefers [plain] when its collapsed length is at least [MIN_PLAIN_LENGTH] and it is
     * not merely a "view this email in a browser" stub; otherwise converts [html].
     *
     * When the HTML yields nothing, a short plain part is still used rather than discarded:
     * spec 11 §11.5 says "else text/html" and is silent about a mail whose only body is a
     * one-line text part, and dropping it would lose the only body there is.
     */
    fun fromParts(
        plain: String?,
        html: String?,
        attachmentNames: List<String> = emptyList(),
        maxBodyKb: Int = DEFAULT_MAX_BODY_KB
    ): ExtractedBody {
        val plainText = if (plain.isNullOrEmpty()) "" else collapseLines(plain)
        val plainUsable = plainText.length >= MIN_PLAIN_LENGTH && !isBrowserStub(plainText)

        val chosen: String
        val mimeType: String
        if (plainUsable) {
            chosen = plainText
            mimeType = ExtractedBody.MIME_PLAIN
        } else {
            val htmlText = if (html.isNullOrBlank()) "" else htmlToText(html)
            when {
                htmlText.isNotEmpty() -> {
                    chosen = htmlText
                    mimeType = ExtractedBody.MIME_HTML
                }
                plainText.isNotEmpty() -> {
                    chosen = plainText
                    mimeType = ExtractedBody.MIME_PLAIN
                }
                else -> {
                    chosen = ""
                    mimeType = ExtractedBody.MIME_NONE
                }
            }
        }

        if (chosen.isEmpty()) {
            return ExtractedBody.EMPTY.copy(
                hasAttachments = attachmentNames.isNotEmpty(),
                attachmentNames = attachmentNames
            )
        }

        // Hash first: the stored hash describes the message, not the slice of it that fit.
        val hash = hashOf(chosen)
        val limitBytes = maxBodyKb.coerceAtLeast(1) * BYTES_PER_KB
        val bytes = chosen.toByteArray(StandardCharsets.UTF_8)
        val truncated = bytes.size > limitBytes
        val text = if (truncated) {
            String(bytes, 0, cutPoint(bytes, limitBytes), StandardCharsets.UTF_8)
        } else {
            chosen
        }

        return ExtractedBody(
            text = text,
            bodyHash = hash,
            hasAttachments = attachmentNames.isNotEmpty(),
            attachmentNames = attachmentNames,
            truncated = truncated,
            sourceMimeType = mimeType
        )
    }

    /**
     * jsoup to text: drops `<head>`, `<style>`, `<script>` and the other tags that carry no
     * reading matter; `<td>`/`<th>` joined with `" | "`; `<br>`, `<p>`, `<tr>` become
     * newlines; entities decoded; zero-width characters stripped; whitespace runs collapsed.
     *
     * Only the text of the document survives, which is `Safelist.none()` semantics as spec
     * 04 §4.1 requires: no markup, no scripts, no attributes and no remote references reach
     * anything downstream.
     */
    fun htmlToText(html: String): String {
        if (html.isBlank()) return ""
        // Guard against a mail bomb before jsoup ever sees it (spec 04 §4.1). A character
        // cap rather than a byte cap: it is the cheap measure and it bounds the parse to
        // the same order of magnitude.
        val bounded = if (html.length > MAX_HTML_BYTES) html.substring(0, MAX_HTML_BYTES) else html
        val document = runCatching { Jsoup.parse(bounded) }.getOrNull() ?: return ""
        document.outputSettings().prettyPrint(false)
        document.select(DROPPED_TAGS).remove()

        val builder = StringBuilder(minOf(bounded.length, INITIAL_CAPACITY))
        val root: Element = document.body() ?: document
        runCatching { NodeTraversor.traverse(TextVisitor(builder), root) }

        return collapseLines(builder.toString())
    }

    // ---------------------------------------------------------------- MIME walk

    /** What the depth-first walk has found so far. */
    private class Walk {
        var plain: String? = null
        var html: String? = null
        val attachmentNames = mutableListOf<String>()
        var hasAttachments = false
        var partsSeen = 0
    }

    /**
     * One MIME node. Containers recurse; text parts become candidates; anything with an
     * attachment disposition or a filename of its own is recorded as an attachment and
     * never used as the body.
     *
     * The order of the tests matters: an attachment is recognised from its **headers**, so
     * its bytes are never decoded. Spec 11 R-11-12 is explicit that M1 records attachment
     * names and nothing else, and a card statement PDF is megabytes the phone has no
     * reason to hold in memory.
     */
    private fun visit(part: Part, walk: Walk, depth: Int) {
        if (depth > MAX_DEPTH || walk.partsSeen >= MAX_PARTS) return
        walk.partsSeen++

        val isPlain = mimeTypeIs(part, ExtractedBody.MIME_PLAIN)
        val isHtml = mimeTypeIs(part, ExtractedBody.MIME_HTML)
        val isContainer = mimeTypeIs(part, MULTIPART_ANY)
        val isNestedMessage = mimeTypeIs(part, NESTED_MESSAGE)
        val fileName = runCatching { part.fileName }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        val disposition = runCatching { part.disposition }.getOrNull()
        val declaredAttachment = Part.ATTACHMENT.equals(disposition, ignoreCase = true)
        // A text part that merely carries a filename is still the body of many senders'
        // mail; only an explicit attachment disposition takes it out of the running.
        val isAttachment = declaredAttachment ||
            (fileName != null && !isPlain && !isHtml && !isContainer)

        if (isAttachment) {
            walk.hasAttachments = true
            val name = fileName ?: nameParameter(runCatching { part.contentType }.getOrNull())
            if (name != null) walk.attachmentNames += name
            // A forwarded message is the one attachment worth opening: the alert inside it
            // is the only body such a mail has.
            if (isNestedMessage) {
                val forwarded = runCatching { part.content }.getOrNull()
                if (forwarded is Part) visit(forwarded, walk, depth + 1)
            }
            return
        }

        if (isPlain || isHtml) {
            val text = readTextPart(part)
            if (isPlain) {
                walk.plain = longerOf(walk.plain, text)
            } else {
                walk.html = longerOf(walk.html, text)
            }
            return
        }

        when (val content = runCatching { part.content }.getOrNull()) {
            is Multipart -> {
                val count = runCatching { content.count }.getOrDefault(0)
                for (index in 0 until count) {
                    val child = runCatching { content.getBodyPart(index) }.getOrNull() ?: continue
                    visit(child, walk, depth + 1)
                }
            }
            is Part -> visit(content, walk, depth + 1)
            else -> Unit
        }
    }

    /** `Part.isMimeType` throws on a malformed Content-Type; a part that cannot say is not it. */
    private fun mimeTypeIs(part: Part, mimeType: String): Boolean =
        runCatching { part.isMimeType(mimeType) }.getOrDefault(false)

    /**
     * The longer candidate wins rather than the first.
     *
     * Senders split text across parts, and two in spec 10 (Amazon Pay, Cashfree) ship a
     * one-line `text/plain` fragment ahead of the real content. First-wins would hand the
     * parsers that fragment.
     */
    private fun longerOf(current: String?, candidate: String?): String? {
        if (candidate.isNullOrBlank()) return current
        if (current == null) return candidate
        return if (candidate.trim().length > current.trim().length) candidate else current
    }

    /**
     * A text part as a String.
     *
     * A part that *declares* itself larger than the jsoup cap is read through a bounded
     * stream instead of decoded whole (spec 04 §4.1); everything else goes through
     * `getContent()`, which is the path JavaMail keeps the charset handling in.
     */
    private fun readTextPart(part: Part): String? {
        val declaredSize = runCatching { part.size }.getOrDefault(UNKNOWN_SIZE)
        if (declaredSize > MAX_HTML_BYTES) return boundedText(part)

        return when (val content = runCatching { part.content }.getOrNull()) {
            is String -> content
            is InputStream -> runCatching {
                content.use { stream -> String(readBounded(stream), charsetOf(part)) }
            }.getOrNull()
            else -> boundedText(part)
        }
    }

    private fun boundedText(part: Part): String? = runCatching {
        part.inputStream.use { stream -> String(readBounded(stream), charsetOf(part)) }
    }.getOrNull()

    private fun readBounded(stream: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(READ_BUFFER)
        var total = 0
        while (total < MAX_HTML_BYTES) {
            val read = stream.read(buffer, 0, minOf(buffer.size, MAX_HTML_BYTES - total))
            if (read <= 0) break
            out.write(buffer, 0, read)
            total += read
        }
        return out.toByteArray()
    }

    /** `charset` from the part's Content-Type; UTF-8 when absent or unknown to the JVM. */
    private fun charsetOf(part: Part): Charset {
        val declared = parameter(runCatching { part.contentType }.getOrNull(), "charset")
            ?: return StandardCharsets.UTF_8
        return runCatching { Charset.forName(declared) }.getOrDefault(StandardCharsets.UTF_8)
    }

    /** `name` from a Content-Type such as `application/pdf; name="statement.pdf"`. */
    private fun nameParameter(contentType: String?): String? = parameter(contentType, "name")

    /**
     * One Content-Type parameter, without a regex: spec 02 §2.1 keeps pattern matching in
     * `:parser-core`, and this is header plumbing rather than text understanding.
     */
    private fun parameter(contentType: String?, key: String): String? {
        if (contentType.isNullOrEmpty()) return null
        for (segment in contentType.split(';')) {
            val trimmed = segment.trim()
            val equals = trimmed.indexOf('=')
            if (equals <= 0) continue
            if (!trimmed.substring(0, equals).trim().equals(key, ignoreCase = true)) continue
            val value = trimmed.substring(equals + 1).trim().trim('"').trim()
            return value.takeIf { it.isNotEmpty() }
        }
        return null
    }

    // ---------------------------------------------------------------- text shaping

    /**
     * The visitor that does the actual HTML-to-text rendering.
     *
     * `rowDepth` is why cells survive: inside a `<tr>`, a `<p>` or `<div>` is a layout
     * wrapper — email HTML is built from nested tables and wrapper divs — so it becomes a
     * space instead of a newline and the row stays on the one line the parsers expect. A
     * nested `<tr>` or a `<br>` still breaks the line, because those are what a sender
     * uses when it means "new row".
     *
     * A newline *inside* a text node is not a line break either: HTML says so, and every
     * pretty-printed mail puts one between `</td>` and `<td>`. Honouring it would split
     * `Amount: | INR 1.00` across two lines and cost the parsers the cell join. Inside
     * `<pre>` the newline is content, so there it is kept.
     */
    private class TextVisitor(private val out: StringBuilder) : NodeVisitor {

        private var rowDepth = 0
        private var preDepth = 0

        override fun head(node: Node, depth: Int) {
            when (node) {
                is TextNode -> out.append(
                    if (preDepth > 0) node.wholeText else node.wholeText.replace('\n', ' ').replace('\r', ' ')
                )
                is Element -> {
                    val tag = node.normalName()
                    if (tag == "pre") preDepth++
                    when {
                        tag == "br" -> out.append('\n')
                        tag == "tr" -> {
                            rowDepth++
                            out.append('\n')
                        }
                        tag in CELL_TAGS -> {
                            val previous = node.previousElementSibling()
                            if (previous != null && previous.normalName() in CELL_TAGS) {
                                out.append(EmailBodyExtractor.CELL_SEPARATOR)
                            }
                        }
                        tag in BLOCK_TAGS -> out.append(if (rowDepth > 0) ' ' else '\n')
                    }
                }
                else -> Unit
            }
        }

        override fun tail(node: Node, depth: Int) {
            if (node !is Element) return
            val tag = node.normalName()
            if (tag == "pre") preDepth = (preDepth - 1).coerceAtLeast(0)
            when {
                tag == "tr" -> {
                    rowDepth = (rowDepth - 1).coerceAtLeast(0)
                    out.append('\n')
                }
                tag in BLOCK_TAGS -> out.append(if (rowDepth > 0) ' ' else '\n')
            }
        }
    }

    /**
     * Strips invisible characters, collapses every run of whitespace inside a line to one
     * space, trims each line and drops the blank ones.
     *
     * Shared by the plain and the HTML path on purpose: the same mail sent both ways should
     * differ in wording, not in shape, and `body_hash` should not depend on which part a
     * sender happened to fill in.
     */
    private fun collapseLines(raw: String): String {
        if (raw.isEmpty()) return ""
        val out = StringBuilder(raw.length)
        val line = StringBuilder()
        var pendingSpace = false
        for (char in raw) {
            when {
                isInvisible(char) -> Unit
                isLineBreak(char) -> {
                    flush(out, line)
                    pendingSpace = false
                }
                isSpace(char) -> if (line.isNotEmpty()) pendingSpace = true
                else -> {
                    if (pendingSpace) {
                        line.append(' ')
                        pendingSpace = false
                    }
                    line.append(char)
                }
            }
        }
        flush(out, line)
        return out.toString()
    }

    private fun flush(out: StringBuilder, line: StringBuilder) {
        if (line.isEmpty()) return
        if (out.isNotEmpty()) out.append('\n')
        out.append(line)
        line.setLength(0)
    }

    /**
     * CR, LF, and the Unicode LINE/PARAGRAPH SEPARATORs that Word-authored HTML pastes in
     * (U+2028, U+2029). Everything else that looks like a break is handled as a space.
     */
    private fun isLineBreak(char: Char): Boolean =
        char == '\n' || char == '\r' ||
            char.code == LINE_SEPARATOR || char.code == PARAGRAPH_SEPARATOR

    /**
     * `Character.isWhitespace` deliberately excludes the non-breaking spaces (U+00A0 from
     * `&nbsp;`, U+2007, U+202F), and `&nbsp;` is the single most common character in a
     * formatted bill, so they are listed here.
     *
     * Code points rather than escaped char literals: an invisible character written
     * literally in source is a character nobody can review.
     */
    private fun isSpace(char: Char): Boolean = char.isWhitespace() || char.code in NB_SPACES

    /** Soft hyphen, zero-width family, bidi controls, BOM — all invisible, all noise. */
    private fun isInvisible(char: Char): Boolean {
        val code = char.code
        return code == SOFT_HYPHEN || code == WORD_JOINER || code == BOM ||
            code in ZERO_WIDTH_RANGE || code in BIDI_RANGE
    }

    /**
     * True for a `text/plain` part that is only a link to the web version.
     *
     * Length alone cannot decide it: several senders put "view this email in a browser" at
     * the top of a perfectly good text body. So the phrase is removed and what remains is
     * measured — a stub has nothing left.
     */
    private fun isBrowserStub(text: String): Boolean {
        val lower = text.lowercase()
        if (STUB_PHRASES.none { it in lower }) return false
        var residue = lower
        for (phrase in STUB_PHRASES) residue = residue.replace(phrase, " ")
        return collapseLines(residue).length < MIN_PLAIN_LENGTH
    }

    /** Empty normal form means no hash; see [ExtractedBody.bodyHash]. */
    private fun hashOf(text: String): String =
        if (BodyNormaliser.normalise(text).isEmpty()) "" else BodyNormaliser.bodyHash(text)

    /**
     * The largest index at or below [limit] that is not in the middle of a UTF-8 character,
     * so a truncated body never ends in a replacement character.
     */
    private fun cutPoint(bytes: ByteArray, limit: Int): Int {
        var end = limit
        // 10xxxxxx is a continuation byte: back up to the start of the character that
        // straddles the cap, and drop that character.
        while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
        return end
    }

    companion object {
        const val MIN_PLAIN_LENGTH = 40

        /** Guard against a mail bomb before jsoup ever sees it (spec 04 §4.1). */
        const val MAX_HTML_BYTES = 2 * 1024 * 1024

        /** `settings.email.maxBodyKb` (spec 11 §11.2). A parameter so a test can shrink it. */
        const val DEFAULT_MAX_BODY_KB = 64

        /** Between cells, never around them — see the class KDoc. */
        const val CELL_SEPARATOR = " | "

        private const val BYTES_PER_KB = 1024
        private const val READ_BUFFER = 8 * 1024
        private const val INITIAL_CAPACITY = 64 * 1024

        /** MIME patterns `Part.isMimeType` understands. */
        private const val MULTIPART_ANY = "multipart/*"
        private const val NESTED_MESSAGE = "message/rfc822"

        /** `Part.getSize()` returns -1 when the server did not say. */
        private const val UNKNOWN_SIZE = -1

        /** A MIME tree deeper or wider than this is not mail anyone meant to send. */
        private const val MAX_DEPTH = 12
        private const val MAX_PARTS = 200

        // Invisible and non-breaking code points, named so the source stays readable.
        private const val SOFT_HYPHEN = 0x00AD
        private const val WORD_JOINER = 0x2060
        private const val BOM = 0xFEFF
        private const val LINE_SEPARATOR = 0x2028
        private const val PARAGRAPH_SEPARATOR = 0x2029

        /** ZWSP, ZWNJ, ZWJ, LRM, RLM. */
        private val ZERO_WIDTH_RANGE = 0x200B..0x200F

        /** LRE, RLE, PDF, LRO, RLO. */
        private val BIDI_RANGE = 0x202A..0x202E

        /** NO-BREAK SPACE, FIGURE SPACE, NARROW NO-BREAK SPACE. */
        private val NB_SPACES = setOf(0x00A0, 0x2007, 0x202F)

        /** Markup that carries no reading matter, or that must never be interpreted. */
        private const val DROPPED_TAGS =
            "head, title, style, script, noscript, link, meta, base, iframe, frame, " +
                "object, embed, applet, svg, canvas, template, map, area, param, source, track"

        private val STUB_PHRASES = listOf(
            "view this email in a browser",
            "view this email in your browser",
            "view this message in your browser",
            "view this email online",
            "view in browser",
            "view online",
            "having trouble viewing this email",
            "trouble viewing this email",
            "cannot view this email",
            "can not view this email",
            "if you cannot see this email",
            "click here to view"
        )
    }
}
