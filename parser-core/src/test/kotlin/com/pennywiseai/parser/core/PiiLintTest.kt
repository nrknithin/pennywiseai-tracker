package com.pennywiseai.parser.core

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
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

    private val rules = listOf(
        Rule(
            name = "Indian mobile number",
            // Ten digits starting 6-9, standing alone. Word boundaries keep this from firing
            // inside a 12-digit UPI reference, which is the shape fixtures are told to use.
            pattern = Regex("""(?<![\d])[6-9]\d{9}(?![\d])"""),
            why = "A 10-digit mobile number. Fixtures must not carry one."
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
        Rule(
            name = "real-looking UPI handle",
            // The handle families Indian banks issue. A fixture VPA must use a placeholder
            // local part; anything else is somebody's actual handle.
            pattern = Regex("""\b(?!someone\b|user\b|test\b|example\b|payee\b)[a-z0-9][a-z0-9._-]{2,}@(ok[a-z]+|ybl|paytm|axl|ibl|upi|apl)\b""", RegexOption.IGNORE_CASE),
            why = "A UPI handle with a real-looking prefix. Use someone@upi (spec 06 §6.2).",
            // This file spells the handle families out as a pattern; scanning itself would
            // only ever produce a false positive.
            exemptFiles = setOf("PiiLintTest.kt")
        ),
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
        exemptFiles = setOf("PiiLintTest.kt")
    )

    /** Address shapes that are never a person. */
    private val allowedEmailDomains = listOf("example.com", "example.org", "example.in", "example.net", "upi", "okbank")
    private val allowedEmailLocalParts = listOf("someone", "user", "test", "noreply", "no-reply", "payee")

    @TestFactory
    fun `source tree carries no personal data or key material`(): List<DynamicTest> {
        val files = sourceFiles()

        // A lint that silently scans nothing always passes. Make that impossible: if a
        // refactor moves the fork's packages, this fails loudly instead of going quiet.
        val sanity = dynamicTest("the scan actually found source files") {
            assertTrue(
                files.size > 20,
                "Expected to scan the fork's sources but found only " + files.size +
                    " files under " + repoRoot().absolutePath +
                    " — forkOwnedPrefixes is stale, so this lint is guarding nothing."
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

    private fun isAllowedAddress(address: String): Boolean {
        val lower = address.lowercase()
        val local = lower.substringBefore("@")
        val domain = lower.substringAfter("@")

        // Kotlin qualified-this that happens to look like an address (`this@Foo.bar`).
        if (local == "this") return true

        if (allowedEmailDomains.any { domain == it || domain.endsWith(".$it") }) return true
        if (allowedEmailLocalParts.any { local == it }) return true

        // A sender the app knows about is an institution, not a person.
        return com.pennywiseai.parser.core.email.EmailSenderRegistry.resolve(lower) != null
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
     * Add a prefix here whenever the fork starts a new package.
     */
    private val forkOwnedPrefixes = listOf(
        "parser-core/src/main/kotlin/com/pennywiseai/parser/core/email",
        "parser-core/src/main/kotlin/com/pennywiseai/parser/core/text",
        "parser-core/src/test/kotlin/com/pennywiseai/parser/core/email",
        "parser-core/src/test/kotlin/com/pennywiseai/parser/core/text",
        "parser-core/src/test/kotlin/com/pennywiseai/parser/core/PiiLintTest.kt",
        "app/src/main/java/com/pennywiseai/tracker/data/email",
        "app/src/main/java/com/pennywiseai/tracker/data/backup",
        "app/src/main/java/com/pennywiseai/tracker/domain/engine",
        "app/src/main/java/com/pennywiseai/tracker/cloud",
        "app/src/test/java/com/pennywiseai/tracker/data/email",
        "app/src/test/java/com/pennywiseai/tracker/domain/engine",
        "cloud"
    )

    /** Kotlin sources the fork wrote. */
    private fun sourceFiles(): List<File> {
        val root = repoRoot()
        return forkOwnedPrefixes
            .map { File(root, it) }
            .filter { it.exists() }
            .flatMap { target ->
                if (target.isFile) listOf(target)
                else target.walkTopDown().filter { f -> f.isFile && f.extension == "kt" }.toList()
            }
    }
}
