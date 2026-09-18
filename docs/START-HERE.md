# Start here

Last updated 2026-09-18, after the rebrand. Written so a fresh session — or a fresh person, or
an agent inside Android Studio — can pick this up without the conversation that produced it.
Everything below is on disk; nothing important lives only in a chat transcript.

## What this is

**FinTrack**, a private Android money tracker for one person on one phone. It reads bank SMS and
Gmail and answers four questions without typing: what is pending, was every recurring payment
made on time, how much is in each account, and how much can I spend today.

It began as a fork of PennyWise AI (see `NOTICE.md` for the attribution the AGPL expects), on
branch **`fin/main`**. **Nothing has ever been pushed.** Every commit is local.

Package `com.nrknithin.fintrack`, applicationId `com.nrknithin.fintrack` (the debug build adds a
`.debug` suffix, which matters for Firebase — see below).

## Read these, in this order

| File | What it gives you |
|---|---|
| `docs/BUILD-STATUS.md` | The ledger: milestone states, toolchain, gate commands, and an append-only verification log. **The workflow parses this** — keep its structure. |
| `docs/NEEDS-YOU.md` | Everything the build cannot do on its own. Nothing here blocks work; it routes around and leaves a note. |
| `docs/FORK-CHANGES.md` | Every upstream file the fork touches and every deliberate deviation from the spec, with reasons. |
| `NOTICE.md` | Provenance and licence. The one place upstream is named on purpose. |
| `spec/` | The 50-file specification. **Git-ignored on purpose** — it quotes the owner's real mailbox. It is on disk; read it. |
| `CLAUDE.md` | The agent guide. Its hard constraints still apply. |

## Build and verify

```
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"
export ANDROID_HOME="/c/Users/nrkni/AppData/Local/Android/Sdk"
./init.sh parser     # parser-core tests — the fast loop
./init.sh app        # app compile + unit tests
./init.sh            # both
```

Current green baseline: **2,248 parser tests + 871 app tests, 0 failures**, at commit `1125bac5`.

`init.sh` was rewritten because the original printed its checkmark on Gradle's exit code alone,
and `:parser-core:test` is an actionless lifecycle task that is **always** `UP-TO-DATE` — so it
could report green having run nothing. It now deletes test-results, forces `--rerun`, and fails
unless Gradle reports the task line bare. **Trust its output over any agent's summary, including
your own.**

Three flavors exist, so a compile check is not one command:
`./gradlew :app:compilePersonalDebugKotlin :app:compileFdroidDebugKotlin :app:compileStandardDebugKotlin`

**If you are working inside Android Studio, do not let its Gradle daemon build while a CLI build
is running.** Two Gradle processes on this project corrupted incremental state once already
(a class file missing while the compile task reported UP-TO-DATE). Drive from one or the other.

## The rebrand (2026-09-18) — read before you grep

Every package moved: `com.pennywiseai.tracker` to `com.nrknithin.fintrack`,
`com.pennywiseai.parser` to `com.nrknithin.fintrack.parser`, `com.pennywiseai.shared` to
`com.nrknithin.fintrack.shared`. Every `PennyWise*` type is now `FinTrack*`. 904 file paths
moved with them, including Room's exported schema directory — its name is the `@Database`
class's fully qualified name and Room validates against it.

Two upstream strings remain **on purpose**:

1. `NOTICE.md` — the AGPL attribution.
2. `"pennywise_database"` in `FinTrackDatabase.LEGACY_DATABASE_NAME`. Room opens a database by
   *file name*, so renaming the constant alone would have opened a new empty database and
   stranded the real one on disk. `migrateLegacyDatabaseFile()` moves the file with its `-wal`
   and `-shm` siblings on first launch. Verified on both phones. Delete it once every device has
   launched the renamed build.

A rebase against upstream is no longer realistic — the paths do not line up, so a merge would
conflict on roughly a thousand files. `docs/FORK-CHANGES.md` is now a record, not a rebase aid.

## Upstream connections were removed, not repointed

The app used to call upstream infrastructure. Do not reintroduce any of it:

- **`SmsReportUrlBuilder` is gone.** It put the full bank SMS body, its sender and encrypted
  device data into a URL and opened it on upstream's web parser — the owner's financial data
  leaving the device to a third party. `DeviceEncryption` and the `RSA_PUBLIC_KEY` BuildConfig
  field that fed it went with it, along with all four call sites.
- Discord, Play Store and GitHub-issue entries in the home menu, settings and FAQ are removed.
- The share card no longer stamps a domain the owner does not own.

What still leaves the device, deliberately: the Qwen model download from a Cloudflare R2 bucket
(upstream-controlled — if they delete the object, the AI chat breaks), and `open.er-api.com` for
exchange rates. Firebase, Gmail IMAP and the Cloudflare cron are the intended online surfaces.

## Where the work stands

M0, M1a, M1b, M1c are done. M1d is code-complete. M2 onwards has not started. See
`docs/BUILD-STATUS.md` for the authoritative table.

**M1's engine is finished and heavily tested, and it has never seen real mail.** That gap closes
with the owner's app password and a device, not with more code.

## Findings from real data (2026-09-18) — the highest-value open work

The parser factory was run offline over the owner's real mailbox export (7,929 SMS, Feb 2020 to
Sep 2026). Raw dumps were deleted afterwards; only aggregates were kept.

```
known bank sender        4478
parsed to a transaction  2621
unparsed                 1857  of which:
   OTP / password         173  correctly ignored
   no amount at all       956  correctly ignored
   promotional             73  correctly ignored
   balance alerts         340  correctly ignored
   LIKELY MISSED TXN      315  <- the real gap
parser threw               0
```

Effective coverage is about **89%** (2,621 of 2,936 transaction-bearing messages). Misses
concentrate in HDFC (92), OneCard (91), SBI (48), Axis (37), ICICI (22). OneCard is weakest:
303 messages seen, 72 parsed.

Three **upstream matcher bugs**, all confirmed by reading `canHandle`:

| Parser | Matcher | Wrongly claims |
|---|---|---|
| `TTBBankParser` | `contains("TMB")` | `AD-PAYTMB`, `AX-PYTMBK` — **Paytm** |
| `CIBEgyptParser` | `contains("CIB")` | 12 **CIBIL** sender variants |
| `EverestBankParser` | numeric sender regex | any 7-10 digit sender, including a phone number |

`BankParserFactory.getParsers()` is content-aware and tries every matching parser, so these do
not shadow working parsers. The harm is **masking**: `isKnownBankSender` returns true, so Paytm
looks handled when **there is no Paytm parser at all**, and the gap would never appear in an
"unrecognised sender" report.

Any new fixtures must be **synthetic**, modelled on the formats — never the owner's real
messages (`CLAUDE.md` hard constraint 4).

## Things that will bite you

- **`spec/` is git-ignored** and contains the owner's real financial data. Never copy values out
  of it into code, comments, tests or fixtures. `PiiLintTest` enforces this for `.kt` files —
  but it does **not** scan Markdown, so sweep tracked docs with `git grep` by hand.
- **`.env` holds a live Gmail app password.** Git-ignored. Never print it, never paste it
  anywhere, never let it reach a log or a commit.
- **The SMS scan watermark is a trap.** `SmsScanParamsCalculator` computes an incremental start
  of `max(min(lastScanTimestamp, now - 3 days), periodLimit)`. Restored or transferred SMS keep
  their **original timestamps**, so they are older than that window and are silently skipped —
  no error, just missing data. `last_scan_timestamp` lives in the `user_preferences` DataStore,
  which survives `adb install -r` *and* survived the database rename, because its file name
  never contained the old brand. The only cure is a forced full rescan: **long-press the Sync
  FAB on Home**, which sets `forceResync` and drops the start time to 0. A single tap does not.
- **Debug builds cannot reach Firebase.** `applicationIdSuffix ".debug"` makes the package
  `com.nrknithin.fintrack.debug`, which does not match the registered `com.nrknithin.fintrack`.
- **Line endings are pinned to LF** by `.gitattributes`. The clone was originally made with
  `core.autocrlf=true`, which left 374 files as CRLF while git reported them clean — it silently
  broke two tests and would have corrupted every diff. Do not re-enable autocrlf.
- **Migrations cannot be verified headlessly.** `MigrationTestHelper` needs instrumentation
  assets, so a schema migration needs an `app/src/androidTest` suite and a real device. A green
  `./init.sh` says nothing about a migration.

## Do not re-attempt: the top app bar

The owner asked twice to remove the empty band above the title on every screen. It was tried and
**reverted** — do not redo it without reading this.

`LargeTopAppBar` feeds **one** title lambda into **both** of its rows, so the pinned collapsed
row re-renders the title invisibly, and Material3 clamps `collapsedHeight` up to the small app
bar container height (56dp) — passing `0.dp` still measures 56dp on device. Two attempts failed:

1. Rendering a pinned greeting on home only and returning early **broke scrolling everywhere on
   that screen**. `HomeScreen` wires `Modifier.nestedScroll(scrollBehaviorLarge.nestedScrollConnection)`,
   and `rememberTopAppBarState()` starts at `heightOffsetLimit = -Float.MAX_VALUE`; it is the
   **TopAppBar** that sets the real limit during layout. With no bar composed, the connection
   swallowed every scroll delta.
2. Switching to `TwoRowsTopAppBar` with an `expanded`-gated title and `collapsedHeight = 0.dp`
   did shrink home to 64dp, but laid the **back button out at y=29px, behind the status bar**,
   invisible on every detail screen.

A workable version needs `collapsedHeight = 0.dp` on home only and 56dp elsewhere — but the
owner judged the result worse on the list screens and reverted the lot. The current bar is
upstream's. Treat this as a design decision, not a bug.

## Devices and ADB

Two phones, both on the S25's hotspot (`10.34.165.0/24`):

| Phone | Serial / address | Notes |
|---|---|---|
| Galaxy S25 Ultra | `10.34.165.146:5555` | **It is the hotspot** — `swlan0` is the laptop's gateway. Holds the full 7,929-SMS history. |
| Galaxy S21 FE | `10.34.165.215:5555` | The owner is migrating SMS onto this one. |

`adb tcpip 5555` mode is lost on reboot and needs one USB re-arm. With USB also plugged in there
are two transports for the same phone, so bare `adb` fails — always pass `-s`.

## The automation

`.claude/workflows/fin-advance.js` runs one verified step: assess the real build state, pick the
next unit of work, implement it, run the actual gate, record what happened, commit locally. It is
**not resolvable by name** — invoke it by path:

```
Workflow({scriptPath: "c:/Users/nrkni/Desktop/finspp/.claude/workflows/fin-advance.js"})
```

Its ground rules are the important part: never push, never invent a number, never put personal
data in the repo, never claim a red build is green, never block on the owner.

One hard-won rule inside it: **every shared file must be named in exactly one task, including
files that do not exist yet.** Two agents once collided on a helper that appeared in no brief —
the design phase invented it after the jobs were scoped, so the disjointness check could not see
it and correctly returned true. A file owned by nobody is owned by whoever writes last, and these
files are untracked, so there is nothing to restore.
