# QA checklist — M1 (email + SMS channel)

Spec 06 §6.1 makes this file the gate for the "manual device checklist" layer, and §6.4 makes
it the definition of done: *a milestone is done only when every AC for its screens passes on
the owner's phone with the owner's real mailbox.* This is the one place real data is used, and
nothing observed here gets written back into the repository — record outcomes as pass/fail and
a note, never as a quoted amount, account number or merchant.

Scope is **M1 only**: spec 11 AC-11-01…05, spec 13 AC-13-01…03, and the logcat grep that
AC-11-04 turns on. Later milestones get their own sections in this file as they land; do not
tick an M2+ criterion here.

`./init.sh` covers what a JVM can check. Everything below is what it cannot: a real IMAP
socket, a real 180-day mailbox, a process kill, and an R8-minified build.

---

## Before you start

| # | Prerequisite | Note |
|---|---|---|
| P1 | A **release** build installed (`./gradlew :app:assembleStandardRelease`) | AC-11-04 is meaningless on a debug build — see the warning in §AC-11-04 |
| P2 | A mailbox connected in the app | **Not possible yet.** Screen 47 (Email Settings) is not built; `EmailSyncRunner.run()` returns `NotConfigured` on every run. See docs/NEEDS-YOU.md, "How a mailbox gets connected". Everything in this file is blocked on P2 and that is expected — the checklist exists so the run is watched properly when it can happen |
| P3 | `adb` on PATH, phone in developer mode, USB debugging on | `adb devices` lists the phone |
| P4 | A way to read the database | Android Studio's App Inspection → Database Inspector, on the release build's `com.nrknithin.fintrack` process |
| P5 | The phone's SMS history covers the same window as the mailbox | AC-13-01 and AC-13-03 need an SMS and its email twin to both exist |

The SQL below is written against schema v63 and can be pasted into the Database Inspector.

---

## AC-11-01 · First sync creates nothing twice

> First sync on the owner's mailbox (180 days) logs every message from registry senders and
> creates no transaction twice (checked by the sync counters: `linked_twin` > 0, no duplicate
> `transaction_hash`).

1. With a freshly installed app, connect the mailbox and let the first sync run to completion.
2. Read the run's counters:
   ```sql
   SELECT fetched, parsed_txn, linked_twin, dues_upserted, dues_settled,
          skipped, errors, last_uid, duration_ms
   FROM sync_runs WHERE channel = 'EMAIL' ORDER BY id DESC LIMIT 1;
   ```
3. Look for the same payment recorded twice. `transactions.transaction_hash` carries a UNIQUE
   index, so the obvious `GROUP BY transaction_hash HAVING COUNT(*) > 1` can never return a
   row — a duplicate here shows up as **two rows with different hashes for one payment**, which
   is the thing dedup is supposed to prevent. Find them by shape, then judge each pair:
   ```sql
   SELECT date(t.date_time) d, t.amount, t.transaction_type, COUNT(*) c,
          GROUP_CONCAT(t.id) ids
   FROM transactions t
   GROUP BY d, t.amount, t.transaction_type, t.bank_name
   HAVING c > 1
   ORDER BY d DESC;
   ```

| Passes when | |
|---|---|
| `linked_twin > 0` — at least one email found its SMS twin, which proves dedup ran rather than that nothing collided | ☐ |
| Every pair the query returns is a genuine pair of separate payments (spec 06 §6.3 names this case explicitly: two real charges of the same amount on the same day must **not** merge) and none is one payment counted twice | ☐ |
| `errors` is 0, or every one of them is explained by a row in `email_messages` with a `skip_reason` you agree with | ☐ |
| `fetched` is in the right order of magnitude for 180 days of the mailbox (spec 11 §11.7 quotes ~1,900 messages as the realistic scale) | ☐ |

Note the counters here as a shape, not as figures to copy anywhere: ______________________

---

## AC-11-02 · Killing the app mid-sync resumes with no gaps

> Killing the app mid-sync and reopening resumes from the last committed batch;
> `email_messages` has no gaps (UIDs between watermark and LASTUID are all present).

1. Start a first sync on a fresh install (the 180-day one is long enough to interrupt).
2. While the foreground notification still shows progress, force-stop the app:
   `adb shell am force-stop com.nrknithin.fintrack`.
3. Reopen the app and let the sync finish.
4. Look for holes in the UID sequence:
   ```sql
   SELECT MIN(uid), MAX(uid), COUNT(*), (MAX(uid) - MIN(uid) + 1) AS span
   FROM email_messages;
   ```

| Passes when | |
|---|---|
| The second run starts from the watermark, not from zero (`sync_runs` has two rows; the second one's `fetched` is smaller than the first's) | ☐ |
| No UID between the watermark and `last_uid` is missing. `COUNT(*)` may legitimately be **less** than `span` — IMAP UIDs skip over deleted messages — so treat a shortfall as a prompt to check that the missing UIDs are absent from the *server*, not as an automatic fail | ☐ |
| No message is logged twice. `email_messages` has a UNIQUE index on `(mailbox, uid)`, so this cannot happen silently — if the resume path tried, the run would have recorded an error rather than a duplicate. Confirm `errors` on the second `sync_runs` row is 0 | ☐ |

---

## AC-11-03 · Revoked app password fails closed

> Revoking the app password in Google → next run sets AUTH_FAILED, one notification, no
> retries until the password is replaced.

1. With a connected, working mailbox, revoke the app password at
   `myaccount.google.com/apppasswords`.
2. Trigger a sync ("Sync now", or wait for the periodic run).
3. Leave the phone alone for at least two sync intervals.

| Passes when | |
|---|---|
| The account state is `AUTH_FAILED` and Email Settings shows the red "Replace app password" card | ☐ |
| Exactly **one** notification appeared — not one per attempt | ☐ |
| No further sync attempt is made while the state is `AUTH_FAILED` (`sync_runs` gains no new rows) | ☐ |
| The failure message carries no credential — see the AC-11-04 grep, which covers this line too | ☐ |
| Pasting a fresh app password clears the state and the next sync succeeds | ☐ |

---

## AC-11-04 · logcat carries no password, no account number, no amount

> `adb logcat` during a sync contains no password, no full account number, no amounts
> (redactor).

**This must be run on a release build.** Two of the three defences only exist there:
`proguard-rules.pro` strips `android.util.Log`'s level methods with `-assumenosideeffects`
(which R8 applies only when optimisation is on, i.e. the release build type), and
`RedactingReleaseTree` is planted only when `BuildConfig.DEBUG` is false. A debug build logs
merchants, hashes, row ids and account last-4s by design, and failing this on one proves
nothing. **Neither of those two can be checked without building and installing release — the
JVM gate cannot see them.**

### Capture

```sh
adb logcat -c
# trigger the sync, wait for the foreground notification to finish
adb logcat -d --pid=$(adb shell pidof -s com.nrknithin.fintrack) > /tmp/fin-sync.log
```

`--pid` matters: an unfiltered logcat is full of other apps' lines and will produce hits that
have nothing to do with Fin.

### Grep

| # | Command | Passes when |  |
|---|---|---|---|
| G1 | `grep -nE '[0-9,]+\.[0-9]{2}' /tmp/fin-sync.log` | No hit is a money amount. Timestamps and durations are not amounts; judge each hit | ☐ |
| G2 | `grep -nE '[0-9]{4}' /tmp/fin-sync.log` | No hit is an account or card suffix. `••••` is the redactor working, not a failure | ☐ |
| G3 | `grep -F '<paste the app password here, in the shell only>' /tmp/fin-sync.log` | Zero hits. **Do not write the password into this file, into a script, or into any file under the repo** — type it into the shell and let it die with the shell's history | ☐ |
| G4 | `grep -niE 'password|passwd|credential|app-?password' /tmp/fin-sync.log` | Every hit is a message *about* a credential, never one containing it (e.g. `auth failed`) | ☐ |
| G5 | `grep -cE '^[VD]/' /tmp/fin-sync.log` | `0` — the release tree drops VERBOSE and DEBUG (spec 04 §4.6) | ☐ |

### If G1 or G2 hits something real

The redactor is `app/src/main/java/com/nrknithin/fintrack/util/LogRedactor.kt` and its unit
tests are `LogRedactorTest`. A hit means one of three things, in rough order of likelihood:

1. **A call site that does not use Timber.** The inherited SMS path logs through
   `android.util.Log` directly; in release those calls should be gone. If they are not, check
   that the build really is minified (`isMinifyEnabled = true` for the release build type) and
   that the `-assumenosideeffects` block in `proguard-rules.pro` survived the last rebase.
2. **A shape the spec's two regexes do not cover** — an amount written without decimals
   (`₹4500`), or a 5-to-7-digit account fragment. Spec 04 §4.6 fixes the two patterns, so
   widening them is a spec change: record it in docs/FORK-CHANGES.md rather than quietly
   editing the regex.
3. **A native or library log line**, which no Kotlin-side tree can reach. Note the tag and
   raise it as its own item.

---

## AC-11-05 · Turning a sender off deletes nothing

> Turning off "extra senders" for `pgsharp.com` and re-syncing does not delete existing
> PGSharp dues (data is never removed by a setting change; only future fetches change).

1. After a sync that produced at least one due from that sender, note how many there are.
   `dues` records the issuer, not the address, so count through `due_sources`:
   ```sql
   SELECT COUNT(DISTINCT ds.due_id) FROM due_sources ds
   WHERE ds.sender LIKE '%pgsharp%';
   ```
2. Turn the extra sender off in Email Settings.
3. Re-sync.

| Passes when | |
|---|---|
| The count is **unchanged** | ☐ |
| The dues are still visible on Pending Dues, not merely present in the table | ☐ |
| A new mail from that sender arriving after the toggle produces no new due | ☐ |
| Turning the sender back on and re-syncing does not double anything already there | ☐ |

---

## AC-13-01 · SMS and its email twin become one transaction

> An HDFC UPI debit SMS followed 2 min later by the HDFC email for the same UPI ref yields one
> transaction with two `transaction_sources` rows (SMS primary).

Pick a UPI debit you can identify in both channels, then:

```sql
SELECT t.id, COUNT(s.id) AS sources,
       SUM(CASE WHEN s.is_primary THEN 1 ELSE 0 END) AS primaries
FROM transactions t JOIN transaction_sources s ON s.transaction_id = t.id
WHERE t.id = <the id>;
```

| Passes when | |
|---|---|
| Exactly **one** transaction row for that payment | ☐ |
| `sources = 2` — one SMS, one EMAIL | ☐ |
| `primaries = 1`, and the primary is the SMS row (it arrived first) | ☐ |
| The surviving row kept the better data from both legs — a reference or merchant name the SMS did not have is filled in, and nothing that was populated got blanked (spec 14 §14.4) | ☐ |

---

## AC-13-02 · A lone SMS still produces a balance row

> An SBI ATM SMS (no email twin) yields one transaction and a balance row with
> `source_type=TRANSACTION`.

| Passes when | |
|---|---|
| One transaction, one `transaction_sources` row, channel SMS | ☐ |
| An `account_balances` row exists for it with `source_type = 'TRANSACTION'` | ☐ |
| The balance shown on Accounts matches the balance the SMS stated | ☐ |
| Nothing was invented: if the SMS quoted no balance, no balance row appears and the screen shows "—" (constraint C7) | ☐ |

---

## AC-13-03 · A statement SMS and its email become one due

> A card-statement SMS creates an OPEN due that later merges with the emailed statement (one
> due, two `due_sources`).

1. Find a card statement that arrived by both SMS and email.
2. ```sql
   -- note: dues.due_id is the merge key (spec 20 §20.4), not the row id.
   -- due_sources.due_id refers to dues.id.
   SELECT d.id, d.status, d.amount, COUNT(ds.id) AS sources
   FROM dues d JOIN due_sources ds ON ds.due_id = d.id
   WHERE d.id = <the id> GROUP BY d.id;
   ```

| Passes when | |
|---|---|
| Exactly **one** due for that statement — not one per channel | ☐ |
| `sources = 2` | ☐ |
| Status is `OPEN` (or `SETTLED` if you have already paid it, with the payment identified) | ☐ |
| The amount is the emailed statement's, not the SMS's, if the two disagree — the email is the higher-precedence source (spec 20 §20.4) | ☐ |
| No false "late": a due whose date has not passed is not shown overdue | ☐ |

---

## Recording a run

Fill this in per device run. Keep it to outcomes; no amounts, no account numbers, no merchant
names (ground rule 3 — this file is tracked).

| Date | Build (flavor + versionName) | Device / Android | ACs passed | ACs failed | Notes |
|---|---|---|---|---|---|
| | | | | | |

A failure here is a finding, not a blocker on the next AC — run the rest of the list, then
open the failures as work items. An AC that could not be attempted (P2, most likely) is
recorded as **not run**, never as passed.
