# Build status

Single source of truth for where Fin is. The `fin-advance` workflow reads this to decide what
to do next and rewrites it when it finishes, so **keep the structure** — the headings and the
table columns are parsed.

Anything that needs the owner goes in [NEEDS-YOU.md](NEEDS-YOU.md), never here, and never
blocks the build.

- **Baseline:** upstream `sarim2000/pennywiseai-tracker` @ `c512c7c` (15 Sep 2026, schema v62)
- **Branch:** `fin/main` — never pushed by automation
- **Last updated:** 2026-09-18 (M1d's four review defects closed; both gates green, 2,852 tests)

## Toolchain

| Component | State |
|---|---|
| Temurin JDK 21.0.12.1 LTS | installed — `C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot` |
| Android Studio 2026.1.4.7 | installed (bundles JBR 25, **not** used for builds — spec pins 21) |
| Android SDK | platforms 36 + 37.0, build-tools 37.0.0, platform-tools, cmdline-tools |
| SDK licences | accepted (7) |
| `local.properties` | written, git-ignored |

Build commands (`JAVA_HOME` must point at the Temurin 21 above):

| Scope | Command |
|---|---|
| Parsers, fast loop | `./init.sh parser` → `./gradlew :parser-core:test` |
| App compile + unit tests | `./init.sh app` |
| Everything CI gates on | `./init.sh` |

## Milestones

`todo` · `in-progress` · `blocked` (see NEEDS-YOU.md) · `done`

| ID | Milestone | Schema | State | Gate |
|---|---|---|---|---|
| M0 | Toolchain + green baseline | 62 | **done** | `./init.sh parser` green — 2,175 tests, 0 failures |
| M1a | Email parsers (parser-core) | — | **done** | included in the 2,175 above |
| M1b | Room v63 schema | 63 | **done** | `./init.sh app` green |
| M1c | IMAP client + body extractor + credential store | 63 | **done** | green: FakeMailboxClientTest 17, ImapSyncAlgorithmTest 10, ImapMessageReadTest 12, EmailBodyExtractorTest 22 |
| M1d | Ingest pipeline + dedup engine | 63 | in-progress | Wired end to end and green: DedupEngineTest 68, EmailTransactionIngestorTest 23, EmailSyncWorkerTest 21, PlausibleDueSettlerTest 17, DueMergeEngineTest 60, DueRepositoryTest 18, IngestingEmailBatchProcessorTest 9, RoomOwnAccountSourceTest 11. All four code defects the 2026-09-17 review found are fixed and pinned by tests. What is left is **not code**: no screen can connect a mailbox yet, so `EmailSyncRunner.run()` returns `NotConfigured` on every real run — see NEEDS-YOU.md, "How a mailbox gets connected". Not `done` until a real sync is watched on a device. |
| M2 | Pending, commitments, settings backbone | 64 | todo | Commitments entered via UI; dues generated with no false "late" |
| M3 | Money plan | 65 | todo | Cycle shows spendable + run-rate from entered salary |
| M4 | Loans and EMI | 66 | todo | Schedules built; EMI debits settle schedule rows |
| M5 | Receipts | 67 | todo | Order emails link to card transactions; refunds reduce spend |
| M6 | Encrypted backup to Drive | — | blocked | Round-trip restore reproduces the DB |
| M7 | Alerts and run-rate rules | 68 | todo | T-3/T-1/T0/overdue fire at the set time |
| M8 | Cloud bell + snapshots | — | blocked | Owner go/no-go; needs Firebase + Cloudflare |
| M9 | Per-record sync + web dashboard | 69 | todo | Optional; treat as fresh design |

## Done

- Upstream cloned; `fin/main` cut from `c512c7c`; history intact (1238 commits)
- Full spec read and cross-checked; contradictions recorded in [FORK-CHANGES.md](FORK-CHANGES.md)
- `parser-core`: `Money`, `DateParse`, sender registry (~60 senders), ignore rules,
  7 transaction parsers, 8 due parsers, 2 factories, `EmailParserTestUtils` — 333 test methods
- `app`: schema 62 → 63, 5 entities + 5 DAOs, `AutoMigration(62, 63)`
- PII: 24 violations found and redacted; `PiiLintTest` guards fork-owned paths; `spec/` git-ignored
- `app`: `DueMergeEngine` — spec 20 §20.4's merge key, candidate choice, source-precedence
  ladder, escalation fold and R-20-09 — as pure Kotlin with an injected `Clock` (50 tests), and
  `DueRepository`, the production `DueWriter` that carries no rules of its own (16 tests)
- `app`: the email pipeline is **connected**. `IngestingEmailBatchProcessor` builds the run's
  `IngestConfig` from stored settings and runs the real ingestor (9 tests); `EmailModule` binds
  it plus the five §11.4 collaborators and the three missing DAOs; `PennyWiseApplication`
  enqueues `EmailSyncWorker.enqueuePeriodic` on process start
- `PiiLintTest` widened from 88 to 159 scanned files (adds `data/database`, `worker`, `di`,
  `parser/core/test`), with a self-expiring `pending` flag and a per-path staleness assertion
  so a renamed package fails the build instead of going quiet; `data/repository` (main and test)
  added on 2026-09-18, which closes the disagreement with FORK-CHANGES.md
- `app`: **the 2026-09-17 review's four defects are closed**, each with the tests that pin it —
  §20.7 now beats §20.4 step 4's unqualified "fill nulls", so a settled due takes neither an
  `amount` nor a `minimum_due` it never had (DueMergeEngineTest 50 → 60, verified by temporarily
  reverting the fix and watching exactly the four new tests go red); `dues` gains two boolean
  columns, `amount_stated` and `auto_dismissed`, so no machine decision reads the user-editable
  `note` any more (`63.json` re-exported in the same commit, no migration — v63 has never
  shipped); `RoomOwnAccountSource` has its first test suite (11 tests over the
  `account_balances` + `cards` union, including the two ways §14.5 can lose an own account)
- `app`: `EmailMessageDao.trimBodiesBefore` has a caller at last — `EmailSyncRunner` sweeps once
  at the end of every completed, unstopped run, at `retainBodiesDays` before the injected clock.
  The query now truncates to 512 characters instead of emptying the column (spec 04 §4.3), and
  is idempotent (`length(body) > 512`). EmailSyncWorkerTest 15 → 21

## Next action

All four defects that stood here on 2026-09-17 are fixed and pinned by tests — the settled-due
merge bug, the two machine decisions keyed off the user-editable note, the `PiiLintTest` gap on
`data/repository`, and the missing `RoomOwnAccountSource` suite. See "Done" for what each became.

M1d's code is finished. What keeps it out of `done` is not code: **nothing in the app can
connect a mailbox**, so `EmailSyncRunner.run()` returns `NotConfigured` on every periodic run.
That is an owner decision (pull spec 47's Email Settings screen forward into M1d, or accept a
developer-only entry point and leave 47 in M2) and it is written up in NEEDS-YOU.md. Do not
invent a third answer here; build M2 around it.

Both gates are green. The review that followed this gate found three new things. None is live
today; all three are cheap now and expensive later:

1. **Risk — `init.sh` prints "Verification passed" even when Gradle ran no tests.** The first
   invocation this run reported `:parser-core:test UP-TO-DATE` and
   `:app:testStandardDebugUnitTest UP-TO-DATE`, `BUILD SUCCESSFUL in 1s`, and then the green
   checkmark; only after `./gradlew clean` did 2,175 + 677 tests actually execute. An agent that
   runs the gate and quotes that checkmark can honestly believe it verified a change it never
   compiled — which makes rule 7 unenforceable. Make the script assert that the test tasks
   executed (parse the JUnit XML timestamps, or fail when every task is up to date).
2. **Risk — a trimmed email body is byte-indistinguishable from a genuinely short one.** The
   retention sweep now writes `substr(body, 1, 512)` and nothing on the row records that it
   happened: `EmailMessageEntity` has no trimmed flag, and `body_hash` stays the hash of the
   untruncated text. `EmailMessageDao.getUnmatched()` is exactly the backlog R-11-10 replays, so
   once that is bound, a 512-character prefix ending mid-figure would be parsed as a whole mail
   and the evidence would already be gone. It has no caller yet, so this is latent — settle it
   before M5/R-11-10 binds it. v63 has not shipped, so a flag column is still free.
3. **Gap — nothing executes the trim query.** The six new `EmailSyncWorkerTest` cases assert the
   cutoff string handed to a fake DAO; "truncates rather than empties" and "the `length` guard
   makes it idempotent" are verified only by Room's compile-time SQL check. There is no
   Robolectric and no in-memory-Room harness anywhere in `app/src/test`, and `app/src/androidTest`
   holds only the upstream placeholder. One DAO-level harness would cover this and the whole of
   M2's schema work.
4. Nits, three carried over and four new. Carried: `MergeOutcome.escalation` is computed on every
   merge and dropped by its only caller (its notice-kind mapping is duplicated verbatim in
   `EmailTransactionIngestor` with no test pinning the copies together); `newDue` stores
   `currency` un-normalised while `dueId()` and `isCandidate()` normalise it two different ways;
   and `DueRepository.candidatesFor` matches reference and `account_last4` byte-exactly in SQL
   where the engine compares them trimmed/lowercased and digits-only, so the engine's tolerance
   is unreachable through the repository. New: `EmailSyncWorker`'s comment calls the sweep "step
   20's other half" when spec 11 §11.3 step 20 says nothing about trimming (the rule is 04 §4.3,
   which never says *where* it runs); `trimBodies`' `coerceAtLeast(RETAIN_BODIES_MIN_DAYS)` is
   unreachable because both config stores already coerce; four `RoomOwnAccountSourceTest`
   assertions pin a registry order only the fake provides (the real query is `ORDER BY balance
   DESC` and every fixture ties at 1000.00); and `EmailMessageDao` now has two hand-written fakes
   where the shared one lacked a single method body.

Then M2 (schema v64): commitments, the settings backbone, and `sender_overrides`.

## Verification log

Newest first. The workflow appends one row per run; never rewrite history here.

| When | Command | Result |
|---|---|---|
| 2026-09-18 | `./gradlew clean` then `./init.sh parser` + `./init.sh app` @ `fin/main` | **GREEN** — 2,175 parser (161 suites) + 677 app (57 suites) = **2,852 tests, 0 failures, 0 errors, 0 skipped**. Run from a clean state deliberately: the first attempt reported every test task UP-TO-DATE and executed nothing while still printing the checkmark (Next action item 1). Grown this run: DueMergeEngineTest 50 → 60, DueRepositoryTest 16 → 18, EmailSyncWorkerTest 15 → 21, new RoomOwnAccountSourceTest 11. `PiiLintTest` 8, green — forced with `:parser-core:jvmTest --tests "*PiiLintTest*" --rerun-tasks`, because Gradle cannot see that its on-disk inputs changed. 141 compiler warnings, 0 errors, all pre-existing. One trap for whoever reads this next: on the first parser run after `clean`, Gradle wrote only 1 of the 161 JUnit XML files, so `parser-core/build/reports/tests/jvmTest/index.html` claimed 8 tests while the console printed 2,175 PASSED / 0 FAILED. A `--rerun-tasks` on the warm build directory produced all 161 and the same totals — a report-writing quirk, not lost tests. Count from the console or from a warm re-run |
| 2026-09-18 | `./gradlew clean` then `./init.sh parser` + `./init.sh app` @ `fin/main` | **GREEN** — 2,175 parser + 648 app = **2,823 tests, 0 failures, 0 errors, 0 skipped**. Clean run, every task executed. New suites: DueMergeEngineTest 50, DueRepositoryTest 16, IngestingEmailBatchProcessorTest 9. `PiiLintTest` 8, green after its rewrite |
| 2026-09-18 | `./init.sh app` ×4 | failed four times before the green run, **none of it code**: KSP `FileNotFoundException` / `NoSuchFileException` on generated Hilt files, a corrupted Kotlin incremental cache, and `Gradle build daemon has been stopped`. Cause found: a second `./init.sh app` was running against the same repo (two `bash.exe ./init.sh app` PIDs confirmed). `init.sh` has no lock. Serialise runs — and do not read these errors as a regression |
| 2026-09-17 | `./init.sh app` @ `fin/main` | **GREEN** — 573 app unit tests, 0 failures, including every M1 suite |
| 2026-09-17 | `./init.sh app` | failed: three upstream `TestDb` classes needed the five new DAO stubs |
| 2026-09-17 | `./init.sh parser` @ `fin/main` | **GREEN** — 2,175 tests, 0 failures. First fully verified state. |
| 2026-09-17 | `./init.sh parser` | 2,175 tests, 2 failed — both the catalogue doc tests, caused by the CRLF working tree (fixed by `.gitattributes` + renormalisation) |
| 2026-09-17 | `:parser-core:jvmTest --tests *DocTest*` @ pristine `c512c7c` worktree | both **passed** — which proved the two failures were mine, not inherited |
| 2026-09-17 | `./init.sh parser` | 2,175 tests, 4 failed — 2 lint false positives, 2 doc tests |
| 2026-09-17 | `./init.sh parser` | 2,175 tests, 7 failed — **everything compiled on the first real build**; 3 genuine bugs (DateParse long-month pattern, 2 half-scrubbed MoneyTest expectations), 2 lint false positives, 2 doc tests |
| 2026-09-17 | `./init.sh parser` @ `fin/main` | failed before compiling — the Gradle wrapper could not download its own distribution (`timeout (10000ms)` at ~25%). Not a code failure. |
| 2026-09-17 | `:parser-core:test` @ `c512c7c` | abandoned — same download, cancelled to free the link |

## Known environment constraint

This machine's link to Google and Gradle CDNs is running at **~22 KB/s**, while Maven Central
measures 1.7 MB/s. Consequences, measured rather than guessed:

- `gradle/wrapper/gradle-wrapper.properties` sets `networkTimeout=10000`, which a 22 KB/s link
  exceeds on any stall, so the wrapper's own download aborts partway. Worked around by fetching
  `gradle-9.7.1-bin.zip` with a resumable `curl` (retries + resume) and dropping it into
  `~/.gradle/wrapper/dists/gradle-9.7.1-bin/<hash>/`, where the wrapper finds and SHA-verifies
  it. Upstream's wrapper config is deliberately left untouched.
- `dl.google.com` and `maven.google.com` were timing out entirely while that download ran.
  Google Maven is where AGP, Compose and Room come from, so **if it stays unreachable the `app`
  module cannot resolve dependencies at all** — and because `:app` is configured even for
  `:parser-core:test`, that would block the parser gate too. Recheck once the link is idle
  before concluding anything.
