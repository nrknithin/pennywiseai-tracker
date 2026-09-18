package com.pennywiseai.parser.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.io.File

/**
 * Fails the build when personal data or key material reaches the source tree
 * (spec 04 §4.5; upstream `CLAUDE.md` hard constraint 4).
 *
 * This matters more here than in most repos: the project is AGPL and its source is kept
 * beside every release APK, so anything committed is published. The owner's real corpus
 * is described in spec files 10 and 12 and must never be copied out of them.
 *
 * **Two deliberate design choices.**
 *
 * 1. *It lives in `parser-core`, not in `app`.* Spec 06 §6.1 puts `PiiLintTest` under
 *    `app/src/test/`. Running it there means it only fires in the Android unit-test job,
 *    which needs the SDK and several minutes. Here it runs under `./init.sh parser` — the
 *    two-minute loop an agent or a human actually runs before every commit — while still
 *    scanning `app/` as well. A guard nobody runs is not a guard.
 *
 * 2. *It contains no denylist of the owner's real values.* The obvious implementation —
 *    assert that the string `9513` never appears — would require writing the owner's real
 *    card digits into a file in the repository, which is the exact disclosure this test
 *    exists to prevent. So the rules here are all structural: shapes that are personal data
 *    regardless of whose they are. A four-digit card suffix has no distinguishing shape and
 *    cannot be caught this way; that class is handled by redacting fixtures at the point
 *    they are written (spec 06 §6.2: last-4 → `1234`, VPAs → `someone@upi`, names →
 *    `SOMEONE`, refs → `123456789012`, amounts changed).
 */
class PiiLintTest {

    private data class Rule(
        val name: String,
        val pattern: Regex,
        val why: String,
        /** Files where this shape is legitimate data rather than a leak. */
        val exemptFiles: Set<String> = emptySet()
    )

    /**
     * A UPI handle on one of the families Indian banks issue.
     *
     * The local part decides: a fixture uses an obviously synthetic one, a leak carries somebody's
     * real handle. That test is done in [isPlaceholderHandle] rather than as a negative lookahead
     * in the pattern, because a lookahead anchored at the match start does not stop the regex
     * from simply starting later — `someone-2@okaxis` would be re-matched from `one-2@okaxis`
     * and reported anyway.
     */
    private val upiRule = Rule(
        name = "real-looking UPI handle",
        pattern = Regex("""\b[a-z0-9][a-z0-9._-]{2,}@(ok[a-z]+|ybl|paytm|axl|ibl|upi|apl)\b""", RegexOption.IGNORE_CASE),
        why = "A UPI handle with a real-looking prefix. Use someone@upi, or a synthetic local " +
            "part such as owner-1@okaxis (spec 06 §6.2).",
        // This file spells the handle families out as a pattern; scanning itself would only
        // ever produce a false positive.
        exemptFiles = setOf("PiiLintTest.kt")
    )

    /**
     * Local parts that are self-evidently invented. A fixture needs several *distinct* handles
     * to test "is this the account holder's own VPA or a stranger's", which a single
     * `someone@upi` cannot express — so a synthetic word plus a suffix is allowed.
     */
    private val placeholderLocalParts = listOf(
        "someone", "user", "test", "example", "payee", "payer", "merchant",
        "owner", "stranger", "acme", "demo", "sample", "store", "customer"
    )

    private val rules = listOf(
        Rule(
            name = "Indian mobile number",
            // Ten digits starting 6-9, standing alone. Word boundaries keep this from firing
            // inside a 12-digit UPI reference, which is the shape fixtures are told to use.
            pattern = Regex("""(?<![\d])[6-9]\d{9}(?![\d])"""),
            why = "A 10-digit mobile number. Fixtures must not carry one — and note that an " +
                "invented 10-digit account or loan number starting 6-9 is indistinguishable " +
                "from one by shape, so choose fixture digits that start lower.",
            // This file quotes example numbers while explaining the rule.
            exemptFiles = setOf("PiiLintTest.kt")
        ),
        Rule(
            name = "full card number",
            pattern = Regex("""(?<![\d])\d{16}(?![\d])"""),
            why = "A 16-digit PAN. Card fixtures use a masked form with a fake last-4."
        ),
        Rule(
            name = "spaced card number",
            pattern = Regex("""(?<![\d])\d{4}[ -]\d{4}[ -]\d{4}[ -]\d{4}(?![\d])"""),
            why = "A card number written in groups of four."
        ),
        upiRule,
        Rule(
            name = "service-account private key",
            pattern = Regex("""(-----BEGIN [A-Z ]*PRIVATE KEY-----|"private_key"\s*:|"client_email"\s*:)"""),
            why = "Cloud key material. Credentials live outside the repo (spec 04 §4.5)."
        )
    )

    /**
     * Personal email addresses.
     *
     * An institutional sender — `alerts@hdfcbank.bank.in`, `protect@cred.club` — identifies a
     * bank, not a person, and appears legitimately in roughly thirty files: every email parser
     * matches on one, and every fixture quotes one. So rather than keeping an exemption list
     * that goes stale the moment a parser is added, an address is allowed when
     * [EmailSenderRegistry] recognises it. That is self-maintaining: teaching the app a new
     * sender teaches the lint at the same time, and an address the registry does not know is
     * exactly the thing worth flagging.
     *
     * The pattern is case-SENSITIVE on purpose. Addresses in code and fixtures are lower-case,
     * while Kotlin's qualified-this (`this@FakeMailboxClient.uidValidity`) is not — matching
     * case-insensitively turns every one of those into a false failure.
     */
    private val emailRule = Rule(
        name = "email address",
        pattern = Regex("""\b[a-z0-9][a-z0-9._%+-]*@[a-z0-9.-]+\.[a-z]{2,}\b"""),
        why = "A personal email address. Use an example.com address, or — if this is a bank or " +
            "merchant sender — add it to EmailSenderRegistry, which is where senders belong anyway.",
        // IgnoreRules holds *fragments* of institutional sender addresses — `axis_system@`,
        // `services@custcomm.icici` — matched as substrings against a From header. They are not
        // whole addresses, so the registry cannot resolve them, and they are not people.
        exemptFiles = setOf("PiiLintTest.kt", "IgnoreRules.kt")
    )

    /** Address shapes that are never a person. */
    private val allowedEmailDomains = listOf("example.com", "example.org", "example.in", "example.net", "upi", "okbank")
    private val allowedEmailLocalParts = listOf("someone", "user", "test", "noreply", "no-reply", "payee")

    /**
     * A *call* to the credential's IMAP accessor.
     *
     * The negative lookbehind is what makes it a call rather than an occurrence: the one
     * spelling preceded by `fun` is the declaration in `EmailCredentialStore.kt`, which must
     * not count against its own rule.
     */
    private val credentialExitCall = Regex("""(?<!fun\s)\brevealForImapLogin\s*\(""")

    /** The one file allowed to make that call. */
    private val credentialExitFile =
        "app/src/main/java/com/pennywiseai/tracker/data/email/JavaMailImapClient.kt"

    @TestFactory
    fun `source tree carries no personal data or key material`(): List<DynamicTest> {
        val files = sourceFiles()

        // A lint that silently scans nothing always passes. Make that impossible: if a
        // refactor moves the fork's packages, this fails loudly instead of going quiet.
        //
        // One total cannot say that. It is exactly how a dead path hides behind the living
        // ones: `data/database` alone contributes 60 files, so `files.size > 20` would stay
        // comfortably true long after a rename emptied `domain/engine` and took three
        // engines out of the scan. So every path is asserted on its own, and the total
        // stays as the backstop for the case where the list itself is gutted.
        val sanity = dynamicTest("every fork-owned path still resolves to sources") {
            val empty = forkOwnedPaths.filter { !it.pending && filesUnder(it).isEmpty() }
            assertTrue(
                empty.isEmpty(),
                "These fork-owned paths hold no Kotlin file, so this lint no longer guards what " +
                    "they name — the package was renamed, moved or deleted. Point the path at " +
                    "where the code went, or drop it:\n" + empty.joinToString("\n") { "  " + it.path }
            )

            // A path marked `pending` is exempt from the check above, so the flag has to come
            // off the moment the package exists — otherwise the exemption outlives its reason
            // and that path is the next one able to empty out unnoticed.
            val arrived = forkOwnedPaths.filter { it.pending && filesUnder(it).isNotEmpty() }
            assertTrue(
                arrived.isEmpty(),
                "These paths are marked `pending = true` but now hold sources. Drop the flag, so " +
                    "that emptying them again fails this test:\n" +
                    arrived.joinToString("\n") { "  " + it.path }
            )

            assertTrue(
                files.size > 20,
                "Expected to scan the fork's sources but found only " + files.size +
                    " files under " + repoRoot().absolutePath +
                    " — forkOwnedPaths is stale, so this lint is guarding nothing."
            )
        }

        val ruleTests = (rules + emailRule).map { rule ->
            dynamicTest(rule.name) {
                val hits = mutableListOf<String>()
                for (file in files) {
                    if (file.name in rule.exemptFiles) continue
                    val text = file.readText()
                    for (match in rule.pattern.findAll(text)) {
                        if (rule === emailRule && isAllowedAddress(match.value)) continue
                        if (rule === upiRule && isPlaceholderHandle(match.value)) continue
                        hits += relativePath(file) + ":" + lineOf(text, match.range.first) + "  " + match.value
                    }
                }
                assertTrue(
                    hits.isEmpty(),
                    rule.why + "\nFound " + hits.size + ":\n" + hits.joinToString("\n") { "  " + it }
                )
            }
        }

        return listOf(sanity) + ruleTests
    }

    /**
     * The app password has exactly one way out to the network, and this counts the ways.
     *
     * `AppPassword.revealForImapLogin()` is the single accessor that hands the credential to
     * `javax.mail`. Its KDoc, and `JavaMailImapClient`'s, both say so — this is what makes
     * that true rather than aspirational. A second caller is how an app password ends up in a
     * log line, a crash report or a `toString()`, which is spec 04 §4.1's first threat.
     *
     * **Call sites, not occurrences** (the note `docs/FORK-CHANGES.md` leaves for whoever
     * writes this rule). The *declaration* lives in `EmailCredentialStore.kt` and the name is
     * spelled all over both files' KDoc; counting mentions would fail on the declaring file
     * itself and on every comment that explains the rule. So comments are blanked first, and
     * the declaration — the one occurrence preceded by `fun` — does not count as a call.
     *
     * `revealForStorage()` is deliberately *not* checked here: it is a second accessor for a
     * second purpose (writing to `EncryptedSharedPreferences`), recorded as a deviation in
     * `docs/FORK-CHANGES.md`. Giving each exit its own name is exactly what keeps this rule
     * statable; one shared accessor could not be counted at all.
     */
    @Test
    fun `the app password has exactly one call site into the network`() {
        val callSites = sourceFiles()
            // This file spells the identifier out in the rule itself; scanning it could only
            // ever find that. Same reason the pattern rules exempt it.
            .filter { it.name != "PiiLintTest.kt" }
            .flatMap { file ->
                val code = withoutComments(file.readText())
                credentialExitCall.findAll(code)
                    .map { relativePath(file) + ":" + lineOf(code, it.range.first) }
                    .toList()
            }

        assertEquals(
            1,
            callSites.size,
            "AppPassword.revealForImapLogin() must have exactly one call site — the store.connect(…) " +
                "in JavaMailImapClient. Anywhere else, the credential is a String somebody can log " +
                "(spec 04 §4.1). Found " + callSites.size + ":\n" + callSites.joinToString("\n") { "  " + it }
        )
        assertTrue(
            callSites.single().startsWith(credentialExitFile),
            "The one call site of revealForImapLogin() has moved out of " + credentialExitFile +
                " to " + callSites.single() + ". If the IMAP client really has moved, update this " +
                "rule and the KDoc on AppPassword and JavaMailImapClient with it."
        )
    }

    /**
     * The same text with every comment blanked and the line structure untouched, so a match's
     * line number still points at the source.
     *
     * Deliberately not a Kotlin lexer: string literals are left alone, so a `//` inside a URL
     * in a string swallows the rest of that line. That can only ever *hide* a call site from
     * the rule, never invent one — a lint that fails the build on innocent code would be the
     * worse failure, and the rule is here to catch a careless second caller, not a hostile
     * one who could defeat any textual check.
     */
    private fun withoutComments(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val lineComment = text.startsWith("//", i)
            val blockComment = text.startsWith("/*", i)
            when {
                lineComment -> {
                    while (i < text.length && text[i] != '\n') {
                        out.append(' ')
                        i++
                    }
                }
                blockComment -> {
                    // Kotlin nests block comments, so this counts rather than stopping at the
                    // first `*/`.
                    var depth = 0
                    while (i < text.length) {
                        if (text.startsWith("/*", i)) {
                            depth++
                            out.append("  ")
                            i += 2
                        } else if (text.startsWith("*/", i)) {
                            depth--
                            out.append("  ")
                            i += 2
                            if (depth == 0) break
                        } else {
                            out.append(if (text[i] == '\n') '\n' else ' ')
                            i++
                        }
                    }
                }
                else -> {
                    out.append(text[i])
                    i++
                }
            }
        }
        return out.toString()
    }

    /** True when the local part is one of the synthetic words, with or without a suffix. */
    private fun isPlaceholderHandle(handle: String): Boolean {
        val local = handle.lowercase().substringBefore("@")
        return placeholderLocalParts.any { local == it || local.startsWith("$it-") || local.startsWith("$it.") }
    }

    private fun isAllowedAddress(address: String): Boolean {
        val lower = address.lowercase()
        val local = lower.substringBefore("@")
        val domain = lower.substringAfter("@")

        // Kotlin qualified-this that happens to look like an address (`this@Foo.bar`).
        if (local == "this") return true

        if (allowedEmailDomains.any { domain == it || domain.endsWith(".$it") }) return true
        // The reserved `.example` TLD, used by the registry's own override fixtures.
        if (domain == "example" || domain.endsWith(".example")) return true
        if (allowedEmailLocalParts.any { local == it }) return true

        val registry = com.pennywiseai.parser.core.email.EmailSenderRegistry
        // A sender the app knows is an institution, not a person...
        if (registry.resolve(lower) != null) return true
        // ...and so is the parent company of one. The registry lists
        // `noreply@notify.cloudflare.com`, which makes `billing@cloudflare.com` a Cloudflare
        // address rather than somebody's personal mail.
        return registry.knownSenders().any { known ->
            val knownDomain = known.substringAfter("@", known)
            knownDomain == domain || knownDomain.endsWith(".$domain")
        }
    }

    private fun lineOf(text: String, offset: Int): Int =
        text.substring(0, offset).count { it == '\n' } + 1

    private fun relativePath(file: File): String =
        file.absolutePath.removePrefix(repoRoot().absolutePath).trimStart('/', '\\').replace('\\', '/')

    /**
     * Walks up from the module directory to the directory holding `settings.gradle.kts`, so
     * the lint works whether Gradle runs it from the module or the root.
     */
    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").exists()) return dir
            dir = dir.parentFile
        }
        error("Could not locate the repository root from " + System.getProperty("user.dir"))
    }

    /**
     * One path this lint scans, and whether the package behind it exists yet.
     *
     * `pending` marks a package the spec plans but no commit has created — the cloud mirror
     * (M3). Every other path must resolve to at least one Kotlin file, which is what turns a
     * renamed or moved package into a red build instead of a quietly smaller scan.
     */
    private data class ForkPath(val path: String, val pending: Boolean = false)

    /**
     * Paths the fork owns. Everything else in the tree is upstream PennyWise code, which
     * this lint deliberately does not police.
     *
     * That is a scoping decision, not an oversight. Measured against upstream at `c512c7c`,
     * its 142 parser test files contain 79 ten-digit mobile-shaped numbers, 26 UPI handles
     * and 13 sixteen-digit runs — overwhelmingly invented (`9876543210`, `merchant@paytm`,
     * `john.doe@oksbi`), but enough of them real-looking that scanning upstream would fail
     * the build permanently on code the fork did not write and will not rewrite. Upstream
     * has its own review for that. The rule the fork can actually keep is "we add none",
     * and that is what this enforces.
     *
     * Four of these paths are directories the fork *shares* with upstream — `data/database`,
     * `worker`, `di` and `parser/core/test` each hold upstream files beside the fork's
     * fifteen (the v63 entities and DAOs, `EmailSyncWorker`, `EmailModule`,
     * `EmailParserTestUtils`). They are listed whole rather than file by file: a list of
     * individual files goes stale the day the next entity lands, which is the failure this
     * lint is supposed to be immune to. The upstream files caught in those four directories
     * carry none of the shapes these rules look for — they are Room declarations and Hilt
     * bindings, not fixtures — so including them costs nothing and the build is the ongoing
     * check. Upstream's *parser test* tree, the one that genuinely cannot pass, stays out.
     *
     * Add a path here whenever the fork starts a new package.
     */
    private val forkOwnedPaths = listOf(
        ForkPath("parser-core/src/main/kotlin/com/pennywiseai/parser/core/email"),
        ForkPath("parser-core/src/main/kotlin/com/pennywiseai/parser/core/text"),
        ForkPath("parser-core/src/test/kotlin/com/pennywiseai/parser/core/email"),
        ForkPath("parser-core/src/test/kotlin/com/pennywiseai/parser/core/text"),
        ForkPath("parser-core/src/test/kotlin/com/pennywiseai/parser/core/test"),
        ForkPath("parser-core/src/test/kotlin/com/pennywiseai/parser/core/PiiLintTest.kt"),
        ForkPath("app/src/main/java/com/pennywiseai/tracker/data/email"),
        ForkPath("app/src/main/java/com/pennywiseai/tracker/data/database"),
        ForkPath("app/src/main/java/com/pennywiseai/tracker/data/backup"),
        ForkPath("app/src/main/java/com/pennywiseai/tracker/domain/engine"),
        // Shared with upstream, listed whole for the same reason as data/database: the fork's
        // DiscoverSendersUseCase lives here and must not fall out of the scan.
        ForkPath("app/src/main/java/com/pennywiseai/tracker/domain/usecase"),
        ForkPath("app/src/test/java/com/pennywiseai/tracker/domain/usecase"),
        ForkPath("app/src/main/java/com/pennywiseai/tracker/worker"),
        ForkPath("app/src/main/java/com/pennywiseai/tracker/di"),
        ForkPath("app/src/main/java/com/pennywiseai/tracker/cloud", pending = true),
        ForkPath("app/src/test/java/com/pennywiseai/tracker/data/email"),
        // Shared with upstream, listed whole for the same reason as data/database above: a
        // fork file landing here must not fall out of the scan. DueRepository lives here.
        ForkPath("app/src/main/java/com/pennywiseai/tracker/data/repository"),
        ForkPath("app/src/test/java/com/pennywiseai/tracker/data/repository"),
        ForkPath("app/src/test/java/com/pennywiseai/tracker/data/database"),
        ForkPath("app/src/test/java/com/pennywiseai/tracker/worker"),
        ForkPath("app/src/test/java/com/pennywiseai/tracker/domain/engine"),
        ForkPath("cloud", pending = true)
    )

    /** The Kotlin files one path contributes — none when the package is not there. */
    private fun filesUnder(fork: ForkPath): List<File> {
        val target = File(repoRoot(), fork.path)
        return when {
            !target.exists() -> emptyList()
            target.isFile -> listOf(target)
            else -> target.walkTopDown().filter { f -> f.isFile && f.extension == "kt" }.toList()
        }
    }

    /** Kotlin sources the fork wrote. */
    private fun sourceFiles(): List<File> = forkOwnedPaths.flatMap { filesUnder(it) }
}
