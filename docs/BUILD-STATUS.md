# Build status

Single source of truth for where Fin is. The `fin-advance` workflow reads this to decide what
to do next and rewrites it when it finishes, so **keep the structure** — the headings and the
table columns are parsed.

Anything that needs the owner goes in [NEEDS-YOU.md](NEEDS-YOU.md), never here, and never
blocks the build.

- **Baseline:** upstream `sarim2000/pennywiseai-tracker` @ `c512c7c` (15 Sep 2026, schema v62)
- **Branch:** `fin/main` — never pushed by automation
- **Last updated:** 2026-09-18 (email pipeline wired end to end; both gates green, 2,823 tests)

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
| M1d | Ingest pipeline + dedup engine | 63 | in-progress | Wired end to end and green: DedupEngineTest 68, EmailTransactionIngestorTest 23, EmailSyncWorkerTest 15, PlausibleDueSettlerTest 17, DueMergeEngineTest 50, DueRepositoryTest 16, IngestingEmailBatchProcessorTest 9. **One open merge bug** found by review after the gate — see Next action item 1. Not `done` until that is fixed and a real sync is watched on a device. |
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
  so a renamed package fails the build instead of going quiet

## Next action

The four items that stood here on 2026-09-17 are all done: `DueWriter` has a production
implementation, `EmailBatchProcessor` binds to the real ingestor, the three missing DAOs are
provided, and the worker is enqueued at process start. A real sync now parses mail.

Both gates are green, but a code review run **after** the gate found defects the tests do not
cover. Fix these before starting M2, hardest first:

1. **Bug — `DueMergeEngine.merge()` rewrites a settled due's amount.** In the `amount` /
   `minimumDue` ladder the `stated == null` branch is evaluated before `mayOverwrite`, so the
   `frozen` guard never reaches a due whose amount was never stated. A row settled against
   "amount not stated", then re-described by a low-rank aggregator copy, silently acquires that
   aggregator's figure while keeping the `Amount not stated` note — so screen 33 still shows
   "—" for a row that now holds a number. Reorder the branches and add the missing test case
   (settled **and** unknown-amount; the two existing tests each cover only one half).
2. **Risk — machine decisions keyed off the user-editable `dues.note` string.** `statedAmount`
   tests `note != AMOUNT_NOT_STATED_NOTE` and `frozen` tests `note != NOTHING_DUE_NOTE`, both by
   exact equality. A user typing in the note turns an unknown amount into a stated zero (a C7
   violation) or freezes an auto-dismissed due forever. Needs a flag that is not free text.
3. **Gap — `PiiLintTest` does not scan `data/repository`.** `DueRepository.kt` and
   `DueRepositoryTest.kt` were added there this run, the test file carries last-4s and sender
   addresses, and FORK-CHANGES.md already lists the package as fork-owned. The two documents
   disagree; add the main and test paths to `forkOwnedPaths`.
4. **Gap — `RoomOwnAccountSource` has no test.** Every `IngestingEmailBatchProcessorTest` fakes
   the `OwnAccountSource` seam, so the production set-union over `account_balances` + `cards`
   is unexercised. A hole in spec 14 §14.5's own-account list makes `DedupEngine` file a
   transfer between the owner's own banks as INCOME, and nothing would go red.
5. Three nits, cheap and worth doing while the code is warm: `MergeOutcome.escalation` is
   computed on every merge and dropped by its only caller (and its notice-kind mapping is
   duplicated verbatim in `EmailTransactionIngestor` with no test pinning the copies together);
   `newDue` stores `currency` un-normalised while `dueId()` and `isCandidate()` normalise it two
   different ways; and `DueRepository.candidatesFor` matches reference and `account_last4`
   byte-exactly in SQL where the engine compares them trimmed/lowercased and digits-only, so
   the engine's tolerance is unreachable through the repository.

Then M2 (schema v64): commitments, the settings backbone, and `sender_overrides`.

## Verification log

Newest first. The workflow appends one row per run; never rewrite history here.

| When | Command | Result |
|---|---|---|
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
