package com.nrknithin.fintrack.util

import android.util.Log
import timber.log.Timber

/**
 * Strips money and account digits out of a log line.
 *
 * Spec 04 §4.6 defines the release log policy: a Timber tree that drops `VERBOSE`/`DEBUG`
 * and "redacts amounts and last-4s from `INFO`+ (regex `\d{4}` → `••••`, `[\d,]+\.\d{2}`
 * → `#`)". Spec 11's AC-11-04 is the acceptance test for it — `adb logcat` during a sync
 * must contain no password, no full account number and no amounts.
 *
 * This object is the whole rule, as a pure function over a `String`, so it can be tested
 * without an Android runtime ([com.nrknithin.fintrack.util.LogRedactorTest]).
 *
 * ### What it deliberately does not do
 *
 * It is a net, not a guarantee. A password is kept out of the logs by never being passed
 * to a logger in the first place (spec 04 §4.1: `ImapMailboxClient` wraps
 * `AuthenticationFailedException` into `EmailAuthException("auth failed")`), because a
 * 16-character app password contains no digit group and no decimal amount and would sail
 * straight through these two patterns. The redactor's job is the residue: the merchant
 * lines, hashes, row ids and account last-4s that the inherited SMS path and the email
 * ingestor log as a matter of course.
 *
 * It is also deliberately over-eager. `\d{4}` has no way to tell an account suffix from a
 * year, an OTP or a row id, so it redacts all of them, and `[\d,]+\.\d{2}` will take the
 * "1.11" out of a version string. In a log line that is the right trade: a redaction that
 * removes too much costs a little debuggability, and one that removes too little puts the
 * owner's money on a wire anyone with `adb` can read.
 */
object LogRedactor {

    /** Replacement for a four-digit group — four U+2022 BULLETs, per spec 04 §4.6. */
    const val DIGITS_PLACEHOLDER = "••••"

    /** Replacement for a decimal amount, per spec 04 §4.6. */
    const val AMOUNT_PLACEHOLDER = "#"

    /**
     * Spec 04 §4.6's amount pattern, verbatim. `[\d,]+` swallows thousands separators, so
     * `1,23,456.78` (the Indian grouping every bank in the corpus uses) goes in one match
     * rather than leaving `,23,456` behind.
     */
    private val AMOUNT = Regex("""[\d,]+\.\d{2}""")

    /** Spec 04 §4.6's digit-group pattern, verbatim. Unanchored, so it also eats a PAN. */
    private val DIGIT_GROUP = Regex("""\d{4}""")

    /**
     * Returns [line] with every amount and every four-digit group replaced.
     *
     * Order matters and the spec does not state one. §4.6 lists `\d{4}` first, but applying
     * it first destroys the amount pattern before the amount rule ever sees it: in
     * `12345.67` the leading `1234` matches, giving `••••5.67` — which has leaked the last
     * three characters of the amount and can no longer match `[\d,]+\.\d{2}`. Amounts are
     * therefore redacted first, which is the reading that leaks nothing; the listed order is
     * prose, not an algorithm. See docs/FORK-CHANGES.md.
     *
     * Both passes are applied unconditionally, so a line carrying both shapes
     * (`A/c 1234 debited by 500.00`) loses both.
     */
    fun redact(line: String): String =
        DIGIT_GROUP.replace(AMOUNT.replace(line, AMOUNT_PLACEHOLDER), DIGITS_PLACEHOLDER)
}

/**
 * The release log tree of spec 04 §4.6: `INFO` and above only, every line through
 * [LogRedactor].
 *
 * Planted by [com.nrknithin.fintrack.FinTrackApplication] when `BuildConfig.DEBUG` is
 * false; debug builds get Timber's own `DebugTree` unredacted, since that build is only
 * ever on a developer's machine.
 *
 * Timber checks `isLoggable` inside `Tree.prepareLog` *before* it formats the message, so a
 * dropped `Timber.d("… %s", expensiveThing)` costs nothing and never builds the string it
 * would have leaked.
 */
class RedactingReleaseTree : Timber.Tree() {

    /** Spec 04 §4.6: the release tree drops `VERBOSE` and `DEBUG` outright. */
    override fun isLoggable(tag: String?, priority: Int): Boolean = priority >= Log.INFO

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        val body = if (t == null) {
            LogRedactor.redact(message)
        } else {
            // The throwable goes through the redactor too, and as text rather than as
            // Log's own `Throwable` parameter: `Log.e(tag, msg, t)` formats the trace
            // internally, which would put an un-redacted exception message — "insufficient
            // balance: 4,213.55" — into logcat below a message we had just cleaned.
            LogRedactor.redact(message + '\n' + t.stackTraceToString())
        }
        // Log.println, not Log.i/w/e: proguard-rules.pro strips `android.util.Log`'s
        // level methods from the release build with -assumenosideeffects, which would
        // silently delete this tree's only output and leave the release build logging
        // nothing at all. `println` is not in that list, on purpose.
        Log.println(priority, tag ?: DEFAULT_TAG, body)
    }

    private companion object {
        /**
         * Timber's base `Tree` has no call-site tag of its own (that is `DebugTree`'s
         * stack-walk, which is too expensive for release), so an untagged call needs one.
         */
        const val DEFAULT_TAG = "Fin"
    }
}
