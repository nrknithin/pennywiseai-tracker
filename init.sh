#!/usr/bin/env bash
#
# init.sh — the verification gate for coding agents (and humans).
#
# Runs exactly what CI (.github/workflows/test.yml) gates on, so "green here"
# means "green in CI". Prefer this over `./gradlew build` / `lint`, which are
# heavier and are NOT part of the gate.
#
# The checkmark at the bottom is a claim that tests *ran and passed*, so it has
# to be earned rather than assumed. A Gradle build whose test task was skipped
# as UP-TO-DATE still exits 0 and still prints a happy build summary — which is
# how several "verified" claims in this repo came to be made over zero executed
# tests. Every test task below is therefore forced to re-execute, its Gradle
# task outcome is checked, and its JUnit XML is counted afterwards. If any of
# that is missing the script fails loudly instead of printing a checkmark.
#
# Usage:
#   ./init.sh            # verify everything (default)
#   ./init.sh parser     # parser-core JVM tests only  (fast; use for parser work)
#   ./init.sh app        # app compile + unit tests only
#   ./init.sh all        # same as no argument
#
# Exit code is non-zero if any step fails (fail-fast).

set -euo pipefail

cd "$(dirname "$0")"

# --- JDK 21 -----------------------------------------------------------------
# Gradle here needs a JDK 21 (matches CI). Find one and verify its major
# version rather than trusting whatever JAVA_HOME happens to point at — a stray
# JDK 17/11 would otherwise fail the build with a cryptic error, and older
# Android Studio JBRs are 17, not 21.

jdk_major() {
  # Print the major version of the JDK at $1 (e.g. "21"), or nothing if unusable.
  local home="$1"
  [[ -n "$home" && -x "$home/bin/java" ]] || return 1
  "$home/bin/java" -version 2>&1 | awk -F'"' '/version/ {print $2; exit}' | cut -d. -f1
}

find_jdk21() {
  local c major
  local -a candidates=()
  # 1. An already-correct JAVA_HOME wins (no surprise switching).
  [[ -n "${JAVA_HOME:-}" ]] && candidates+=("$JAVA_HOME")
  # 2. macOS: ask the OS for a registered JDK 21.
  if [[ -x /usr/libexec/java_home ]]; then
    c="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
    [[ -n "$c" ]] && candidates+=("$c")
  fi
  # 3. Android Studio's bundled JBR (used only if it is actually 21).
  candidates+=("/Applications/Android Studio.app/Contents/jbr/Contents/Home")
  # 4. Common SDKMAN install.
  candidates+=("${SDKMAN_DIR:-$HOME/.sdkman}/candidates/java/current")

  for c in "${candidates[@]}"; do
    major="$(jdk_major "$c" || true)"
    if [[ "$major" == "21" ]]; then
      printf '%s\n' "$c"
      return 0
    fi
  done
  return 1
}

if JDK21="$(find_jdk21)"; then
  if [[ "${JAVA_HOME:-}" != "$JDK21" ]]; then
    echo "→ Using JDK 21 at: $JDK21"
    export JAVA_HOME="$JDK21"
  fi
else
  echo "⚠️  No JDK 21 found. CI builds on JDK 21 and Gradle here needs it too." >&2
  if [[ -n "${JAVA_HOME:-}" ]]; then
    echo "    JAVA_HOME points at a JDK $(jdk_major "$JAVA_HOME" 2>/dev/null || echo '?'), not 21." >&2
  fi
  echo "    Install one (e.g. Temurin 21, or Android Studio's JBR) and either set" >&2
  echo "    JAVA_HOME to it or make it discoverable via /usr/libexec/java_home, then re-run." >&2
  exit 1
fi

TARGET="${1:-all}"

# --- the gate ---------------------------------------------------------------

# Full Gradle output per task, kept for whatever the one-line summary is too
# small to say. Lives under /build, which .gitignore already covers.
GATE_LOG_DIR="build/init-gate"

fail() {
  echo "" >&2
  echo "❌ GATE FAILED — $*" >&2
  echo "   Nothing here counts as verified. Do not report this run as green." >&2
  exit 1
}

run() {
  echo ""
  echo "▶ $*"
  "$@"
}

junit_totals() {
  # Print "<tests> <failures> <errors> <skipped>", summed over every JUnit XML
  # report under $1.
  #
  # Only the opening <testsuite …> tags are read. These reports also embed
  # everything the tests printed to stdout (testLogging.showStandardStreams is
  # on), so a looser match would cheerfully add up numbers out of a log line.
  local dir="$1"
  find "$dir" -type f -name 'TEST-*.xml' -exec cat {} + 2>/dev/null | awk '
    function attr(tag, name,   m) {
      if (!match(tag, name "=\"[0-9]+\"")) return 0
      m = substr(tag, RSTART, RLENGTH)
      sub(/^[^"]*"/, "", m)
      sub(/"$/, "", m)
      return m + 0
    }
    {
      rest = $0
      while (match(rest, /<testsuite [^>]*>/)) {
        tag  = substr(rest, RSTART, RLENGTH)
        rest = substr(rest, RSTART + RLENGTH)
        t += attr(tag, "tests")
        f += attr(tag, "failures")
        e += attr(tag, "errors")
        s += attr(tag, "skipped")
      }
    }
    END { printf "%d %d %d %d\n", t, f, e, s }
  '
}

verify_tests() {
  # Run one Gradle test task, then prove it actually tested something.
  # $1 is the task path, $2 the directory it writes its JUnit XML to.
  local task="$1" results="$2"
  local log="${GATE_LOG_DIR}/${task//:/_}.log"

  # rm -rf below: refuse anything that is not a Gradle test-results directory.
  [[ -n "$results" && "$results" == */build/test-results/* ]] \
    || fail "internal: refusing to clear '$results'"

  mkdir -p "$GATE_LOG_DIR"

  # Clear last run's reports first, for two reasons: anything counted below can
  # then only have come from this run, and a test task whose outputs are gone
  # cannot be UP-TO-DATE. `--rerun` closes the remaining hole — a build-cache
  # hit, which CI enables and a developer may too.
  rm -rf "$results"

  echo ""
  echo "▶ ./gradlew $task --rerun --console=plain"
  local status=0
  ./gradlew "$task" --rerun --console=plain 2>&1 | tee "$log" || status=$?
  [[ "$status" -eq 0 ]] || fail "$task exited $status (full output: $log)"

  # Gradle appends an outcome to every task it did not really execute
  # (UP-TO-DATE, FROM-CACHE, SKIPPED, NO-SOURCE). A bare task line is the only
  # evidence the tests ran; the build succeeding is not, because skipping them
  # succeeds just as quietly.
  local line
  line="$(grep -E "^> Task ${task}( |\$)" "$log" | tail -n 1 || true)"
  line="${line%$'\r'}"
  [[ -n "$line" ]] \
    || fail "Gradle never reported $task, so no test ran (full output: $log)"
  [[ "$line" == "> Task ${task}" ]] \
    || fail "$task did not execute — Gradle reported '$line'. A skipped test task verifies nothing."

  # Counts come from the XML rather than the console, because the console
  # format depends on each module's testLogging config (`:app` prints no
  # per-test lines at all) and because test stdout is echoed into it verbatim.
  # Known caveat, recorded in BUILD-STATUS's gate log: on the first run after
  # `./gradlew clean`, Gradle has been seen to write only one of the 161
  # reports, which would understate this number. It cannot manufacture a green
  # one — a failing test fails the build, which the exit check above catches —
  # and clearing only the results directory keeps the build warm, which is the
  # state where all 161 reports appear.
  local totals tests failures errors skipped passed
  totals="$(junit_totals "$results" || true)"
  read -r tests failures errors skipped <<<"$totals"
  passed=$(( tests - failures - errors - skipped ))

  (( tests > 0 )) \
    || fail "$task ran but left no JUnit results in $results — zero tests is not a pass."
  (( failures + errors == 0 )) \
    || fail "$task: $failures failed, $errors errored out of $tests (reports: $results)"
  (( passed > 0 )) \
    || fail "$task: all $tests of its tests were skipped."

  echo ""
  echo "✔ $task executed — ${tests} tests: ${passed} PASSED, $(( failures + errors )) FAILED, ${skipped} SKIPPED"
}

verify_parser() {
  # `:parser-core:test` is a lifecycle alias with no actions of its own — it
  # only dependsOn `jvmTest` (see parser-core/build.gradle.kts), so Gradle
  # prints it UP-TO-DATE even on a cold run and its outcome says nothing about
  # whether tests ran. Name the task that actually runs them.
  verify_tests ":parser-core:jvmTest" "parser-core/build/test-results/jvmTest"
}

verify_app() {
  # An UP-TO-DATE compile is genuine verification — Gradle re-hashes every
  # input, so "up to date" means this exact source already compiled — and it is
  # not forced to re-run. Only test tasks lie when they are skipped.
  run ./gradlew :app:compileStandardDebugKotlin
  verify_tests ":app:testStandardDebugUnitTest" "app/build/test-results/testStandardDebugUnitTest"
}

case "$TARGET" in
  parser) verify_parser ;;
  app)    verify_app ;;
  all)    verify_parser; verify_app ;;
  *)
    echo "Unknown target: '$TARGET' (expected: parser | app | all)" >&2
    exit 2
    ;;
esac

echo ""
echo "✅ Verification passed ($TARGET) — every test task above re-ran and its results were counted."
echo "   Note: runtime/UI changes still need an on-device check (emulator-verify skill)."
