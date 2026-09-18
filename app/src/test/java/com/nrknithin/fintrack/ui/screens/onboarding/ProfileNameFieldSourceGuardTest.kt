package com.nrknithin.fintrack.ui.screens.onboarding

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **The test that goes red if someone re-enables the onboarding name field.**
 *
 * Why a source-text assertion rather than a Compose test: this repo has no Compose UI test
 * infrastructure in use — `androidx.ui.test.junit4` is declared but `app/src/androidTest`
 * holds only `ExampleInstrumentedTest.kt`, and `./init.sh` runs no instrumentation at all.
 * A rule nothing runs is not a rule. `PiiLintTest` in `parser-core` reaches for the same
 * technique for the same reason, and this file borrows its `repoRoot()` walk.
 *
 * **What is pinned, and why each one.** All four are read out of the single `TextField`
 * whose label is "Your name", never from the file at large, so an unrelated composable
 * cannot accidentally satisfy them:
 *
 *  - `enabled = !uiState.isNameLocked` — greys the field and blocks focus.
 *  - `readOnly = uiState.isNameLocked` — closes the programmatic edit path that `enabled`
 *    alone leaves open (IME, accessibility actions, autofill).
 *  - `if (!uiState.isNameLocked)` inside the block — the `onValueChange` guard. The
 *    ViewModel guards this too (`OnBoardingNameLockTest`), and both are deliberate:
 *    deleting either one is a silent behaviour change with no compile error to explain it.
 *  - `disabledTextColor` — not cosmetic. Material 3 renders disabled text at 38% alpha,
 *    which makes the user's own name read as broken rather than deliberately locked.
 *
 * **What it tolerates.** The slice has its comments stripped and its whitespace collapsed
 * before matching, so re-indenting, re-wrapping or moving arguments onto one line is
 * invisible to it, and a comment quoting one of these fragments cannot satisfy it. What it
 * does not tolerate is rewriting an argument — `enabled = !(uiState.isNameLocked)` fails
 * here. That is the accepted cost, and it is the cheaper of the two failures: a false red
 * costs a minute, a silently re-enabled field costs the guarantee the whole gate exists
 * for.
 */
class ProfileNameFieldSourceGuardTest {

    @Test
    fun `the onboarding name field is still locked to the google name`() {
        val slice = profileNameFieldSource()

        for ((fragment, requirement) in REQUIRED) {
            assertTrue(
                "The onboarding name field no longer carries `$fragment`.\n" +
                    requirement + "\n" +
                    "The name shown on the PROFILE step comes from the signed-in Google " +
                    "account and must not be editable; the ViewModel half of the same rule " +
                    "is pinned by OnBoardingNameLockTest. If the rule itself has changed, " +
                    "change both tests deliberately — do not delete this assertion.\n" +
                    "Field source as this test read it:\n" + slice,
                slice.contains(fragment)
            )
        }
    }

    @Test
    fun `the avatar and background pickers were not caught by the lock`() {
        // The lock was meant to reach one TextField. These two call sites are the rest of
        // the PROFILE step; if they disappear, something took more of the screen with it
        // than it should have.
        val source = normalise(screenFile().readText())

        assertTrue(
            "OnBoardingScreen no longer calls viewModel.selectAvatar(index) — the avatar " +
                "picker on the PROFILE step is gone or was renamed.",
            source.contains("viewModel.selectAvatar(index)")
        )
        assertTrue(
            "OnBoardingScreen no longer calls viewModel.selectBackgroundColor(index) — the " +
                "background-colour picker on the PROFILE step is gone or was renamed.",
            source.contains("viewModel.selectBackgroundColor(index)")
        )
    }

    /**
     * The `TextField(...)` call whose label is "Your name", comment-free and with runs of
     * whitespace collapsed to one space.
     */
    private fun profileNameFieldSource(): String {
        val source = normalise(screenFile().readText())

        val anchor = source.indexOf(NAME_FIELD_ANCHOR)
        assertTrue(
            "Could not find `$NAME_FIELD_ANCHOR` in ${SCREEN_PATH}, so this guard is no " +
                "longer guarding anything. If the label was reworded, re-point this test at " +
                "the new one rather than dropping it.",
            anchor >= 0
        )

        // `TextFieldDefaults.colors(` does not match this needle (the next character is a
        // `D`, not a `(`), and it sits after the anchor anyway.
        val open = source.lastIndexOf("TextField(", anchor)
        assertTrue(
            "Found the \"Your name\" label but no enclosing TextField( in ${SCREEN_PATH}.",
            open >= 0
        )

        return balancedCall(source, open)
    }

    private fun screenFile(): File = File(repoRoot(), SCREEN_PATH).also {
        assertTrue("Expected to find ${SCREEN_PATH} under ${repoRoot().absolutePath}", it.isFile)
    }

    /**
     * Comments out, whitespace collapsed.
     *
     * Line comments are dropped only when the `//` is not preceded by a colon, so a URL in
     * a string literal survives. This file carries none today; the guard is there so that
     * adding one later does not quietly swallow half of a line this test reads.
     */
    private fun normalise(text: String): String = text
        .replace(BLOCK_COMMENT, " ")
        .replace(LINE_COMMENT, " ")
        .replace(WHITESPACE, " ")
        .trim()

    /** From `TextField(` to its matching `)`, ignoring parentheses inside string literals. */
    private fun balancedCall(text: String, openIndex: Int): String {
        var depth = 0
        var inString = false
        var i = text.indexOf('(', openIndex)
        while (i in text.indices) {
            val c = text[i]
            when {
                inString && c == '\\' -> i++ // the escaped character is not a delimiter
                c == '"' -> inString = !inString
                inString -> Unit
                c == '(' -> depth++
                c == ')' -> {
                    depth--
                    if (depth == 0) return text.substring(openIndex, i + 1)
                }
            }
            i++
        }
        return text.substring(openIndex)
    }

    /**
     * Walks up from the module directory to the directory holding `settings.gradle.kts`,
     * so this works whether Gradle runs the test from `app/` or from the root — the same
     * walk `PiiLintTest` uses.
     */
    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").exists()) return dir
            dir = dir.parentFile
        }
        error("Could not locate the repository root from " + System.getProperty("user.dir"))
    }

    private companion object {
        const val SCREEN_PATH =
            "app/src/main/java/com/nrknithin/fintrack/ui/screens/onboarding/OnBoardingScreen.kt"

        const val NAME_FIELD_ANCHOR = """label = { Text("Your name") }"""

        val BLOCK_COMMENT = Regex("""/\*[\s\S]*?\*/""")
        val LINE_COMMENT = Regex("""(?<!:)//[^\n]*""")
        val WHITESPACE = Regex("""\s+""")

        /** fragment → why it is there, quoted back in the failure message. */
        val REQUIRED = listOf(
            "enabled = !uiState.isNameLocked" to
                "Without it the field accepts focus and typing while the name is supposed " +
                "to be the one Google supplied.",
            "readOnly = uiState.isNameLocked" to
                "`enabled = false` alone still leaves programmatic edit paths open (IME, " +
                "accessibility actions, autofill); readOnly closes them.",
            "if (!uiState.isNameLocked)" to
                "This is the onValueChange guard. It is the last line of defence if the " +
                "field is ever made interactive again by a styling change.",
            "disabledTextColor" to
                "Material 3 renders disabled text at 38% alpha. Without an explicit " +
                "disabledTextColor the user's own name reads as broken rather than locked, " +
                "in both light and dark themes."
        )
    }
}
