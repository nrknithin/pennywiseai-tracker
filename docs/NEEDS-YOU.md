# Needs you

Things the build cannot do on its own. **Nothing here blocks anything** — the automation skips
past these, builds everything around them, and adds a note. Pick them up whenever you like.

The `fin-advance` workflow appends to this file and never deletes from it. When you have done
one, tick it — that is the signal the automation reads.

Format: `- [ ]` open, `- [x]` done, each with why it matters and what happens meanwhile.

---

## Credentials and accounts

- [ ] **Gmail app password** (16 characters, from Google Account → Security → App passwords).
  Needed before the email channel can fetch a single real message.
  *Meanwhile:* the IMAP client is built and tested against a fake mailbox, so everything except
  a live sync gets written and verified without it.
  *Where it goes:* typed into the app's Email Settings screen at runtime. It is stored in
  `EncryptedSharedPreferences` and never written to a file, a log, or this repo (spec C1).

- [ ] **Release keystore** — `fin-release.jks`, generated once and backed up somewhere safe.
  ```
  keytool -genkeypair -alias fin -keyalg RSA -keysize 4096 -validity 10000 -keystore fin-release.jks
  ```
  Needed for the first installable APK. Losing it later means uninstall + restore from backup,
  because Android refuses an update signed by a different key.
  *Meanwhile:* debug builds are unaffected; everything can be developed and tested without it.
  *Where it goes:* outside the repo, with a git-ignored `keystore.properties` pointing at it.
  As of 2026-09-18 that is enforced rather than merely promised: `keystore.properties`,
  `google-services.json` and `secrets.properties` are in `.gitignore` (unanchored, so a root copy
  and an `app/` copy are both covered). Upstream ignored `*.jks` but not the properties file that
  points at it — until this run, only the absence of the files kept the promise true.

- [ ] **Firebase project** (only if you want M8 — the wake-up push and encrypted cloud snapshots).
  Four values — `projectId`, `appId`, `apiKey`, `senderId` — into git-ignored `local.properties`,
  plus `google-services.json` kept beside the keystore.
  *Meanwhile:* M8 is skipped entirely. Spec constraint C8 says the app is complete with every
  cloud feature off, and the `fdroid` flavor is the control build that proves it.

- [ ] **Cloudflare account + `wrangler` login** (only for M8, the scheduled bell).
  *Meanwhile:* same as above — skipped.

---


- [ ] **Google Drive backup — owner said on 2026-09-18 it is not wanted.** Recorded rather than
  acted on, because the premise needs checking first: M8 (Firebase) is `todo`, not built, and was
  explicitly excluded from the autonomous work, so there is no cloud copy today. Spec 00 §0.2 also
  fixes that Firestore would hold *ciphertext blobs, never queryable data* — a restore source, not
  a sync. And spec 02 §2.4 makes the snapshot **the backup file**: `ScheduledFolderBackupWorker ->
  backup bytes -> CloudSnapshotWorker -> Firestore`, so deleting M6 removes what M8 uploads.
  *Split that survives the confusion:* keep `BackupExporter`/`BackupCrypto` (M8 needs the payload),
  drop the SAF folder-picker screen (genuinely optional). That is the default if nothing else is
  said — it costs one screen and keeps a recovery path that needs no account and no network, which
  constraint C8 requires.
  *Still open:* whether this means M8 should now be built (puts Firebase back in scope and needs
  the four config values), and the encryption-model question above stops being academic if
  Firestore ever becomes the only copy — losing the vault passphrase would then lose everything.

## Decisions only you can make

- [ ] **Backup encryption model.** The spec describes two incompatible schemes for the same
  bytes: `27-backup-drive.md` R-27-03 derives the key straight from your passphrase, while
  `08-cloud-services.md` §8.5.3 calls it the same payload but wraps a random key. The
  `PWFINENC1` container has no slot for a wrapped key, so one has to change.
  *Recommendation:* keep the wrapped-key design and extend the container, so you can change
  your passphrase later without re-encrypting every old backup.
  *Meanwhile:* M6 is marked blocked. Nothing else depends on it.

- [ ] **Is M8 (cloud) in or out?** The entire benefit is punctuality — a worker firing at 08:00
  instead of whenever Android feels like it.
  *Recommendation:* decide after M7, once you can see whether WorkManager is actually late on
  your own phone. If it is not, M8 buys nothing.

- [ ] **Version numbering.** `05-build-release.md` §5.5 wants `versionName = "fin-0.2.0+pwc512c7c"`,
  but upstream's `CLAUDE.md` makes it a hard rule that `scripts/release.sh` owns the version and
  it is never hand-edited. The fork must either break that rule or replace the script.
  *Meanwhile:* the version stays untouched at upstream's value.

- [ ] **May an ignore rule name a correspondent who is neither a bank nor a merchant?**
  `MARKETING_SENDERS` in `parser-core/.../email/IgnoreRules.kt` seeds two addresses — `talent@ibm.com`
  and `ibm@email.ibm.com` — that were taken from your mailbox like every other entry in that list.
  They are institutional addresses, so `PiiLintTest` passes them and spec 06 §6.2's redaction
  conventions (last-4, VPAs, names, refs, amounts) do not reach them. But unlike every other seed
  they say nothing about money: they identify a **recruiting relationship of yours**, which ground
  rule 3's list of personal data does not mention either way. Nothing here is actionable by a
  reader — no number, no account, no name — so this is a taste call about what the repository
  should be seen to know, not a leak.
  *Recommendation:* replace them with a generic rule if you want the repo silent about who writes to
  you; keep them if the noise they suppress is worth it. A generic recruiting-domain rule is weaker,
  because it would have to guess at domains.
  *Meanwhile:* the file is left exactly as it is and the ignore rule keeps working. If the answer is
  "drop them", it is a two-line change plus one test.
  *Where it goes:* nowhere in the repo — tell the next run which, and it does it.

- [ ] **How a mailbox gets connected — and therefore when M1d can close.** There is no way to
  enter an email address, a folder or an app password in the app today. `presentation/` has no
  email package, and outside `EmailSyncWorker` and `PennyWiseApplication` nothing reads
  `EmailAccountConfigStore` or `EmailCredentialStore`. The periodic worker runs and
  `EmailSyncRunner.run()` returns `NotConfigured` every single time, forever. Two ways out:
  pull spec 47's Email Settings connect form forward into M1d, or accept a temporary
  developer-only entry point (a debug menu item, deleted when screen 47 lands) and leave 47 in
  M2 where spec 07's sequencing puts it. Both are deliberate deviations from the milestone
  order, which is why the automation will not choose for you.
  *This also corrects the Gmail app-password entry above:* its "typed into the app's Email
  Settings screen at runtime" describes a screen that does not exist yet. The password itself is
  still needed, still goes in `EncryptedSharedPreferences`, and still never touches the repo.
  *Meanwhile:* M1d's engine work is finished and green — ingest, dedup, due merge, settlement,
  commit and the retention sweep are all exercised end to end against a fake mailbox, so the
  pipeline is defect-free behind the gap. M2's settings backbone brings screen 47 on its own
  schedule if the answer is "leave it in M2", and nothing else waits on this.
  *Where it goes:* nowhere in the repo — tell the next run which of the two, and it builds it.

---

## Your data (entered in the app, never in code)

- [ ] **Salary amount and payday.** Until entered, the Plan tab shows "Income: not set" and
  computes no run-rate — by design (spec C7: never invent a number).
- [ ] **Your commitments** — the EMIs, subscriptions and promises from `01-product-overview.md`
  §1.4. These are entered through onboarding. Nothing about them is hard-coded, and the worked
  example in the spec is treated as illustration, not seed data.
- [ ] **Your own accounts and UPI handles**, so a transfer between your own accounts reads as a
  transfer rather than income. The parsers already take these as settings that default to empty.

---

## Device

- [ ] **USB debugging on the phone**, for installing and for on-device checks.
  *Meanwhile:* everything is verified headlessly. UI work will queue up a list of things worth
  looking at on a real screen, which is faster to do in one batch anyway.

- [ ] **Confirm the periodic email sync is actually scheduled**, once you next install a build:
  ```
  adb shell dumpsys jobscheduler | grep email_sync_periodic
  ```
  As of 2026-09-18 `PennyWiseApplication` enqueues `EmailSyncWorker.enqueuePeriodic` on process
  start. This is the first change in the fork whose whole effect is a *background run*, and
  WorkManager scheduling cannot be exercised by a JVM unit test — nothing in the 2,974 green tests
  measured on 2026-09-18 proves the job exists on a real device.
  *Meanwhile:* the runner underneath it is tested headlessly (`EmailSyncWorkerTest`, 21 tests),
  so a scheduling problem would be the only thing this check could find.
  *Where it goes:* nowhere in the repo — it is a one-line confirmation after a cold start.

- [ ] **A device or emulator session for the schema-migration suite**, when M2 gets there.
  `AutoMigration(62, 63)` has never executed: the Robolectric harness added on 2026-09-18 runs
  real SQL, but it proves the schema Room builds *from scratch*, which is a different statement
  from "an existing v62 database survives the upgrade". The suite that would prove it needs
  `MigrationTestHelper`, which reads each `NN.json` through the instrumentation context's assets —
  so it has to live in `app/src/androidTest` and cannot run under `./init.sh` at all. Putting the
  schemas where a unit test could reach them would mean shipping 2.3 MB of JSON inside the APK,
  which is why it was not done.
  *Meanwhile:* nothing waits on this, and nothing needs doing yet — the suite is not written.
  M2's `Migration(63, 64)` is the fork's first hand-written migration and should land together
  with it, at which point one device run covers both migrations at once. Batch it with the two
  device checks above rather than making a separate trip.
  *Where it goes:* nowhere in the repo — it is `./gradlew :app:connectedStandardDebugAndroidTest`
  with a phone plugged in or an emulator up.

---

## Resolved by following the spec — no decision needed

- **How a mailbox gets connected.** M1d left this open: pull spec 47 (Email Settings) forward,
  or leave it in M2. Spec 07 already schedules 47 in **M2**, so the default is simply to build
  it there — no decision is required. M2 itself is still `todo` in BUILD-STATUS.md and no line
  of it has been written yet; screen 47 is scheduled into it, not under way in it. (An earlier
  version of this entry called M2 "the milestone now in progress", which disagreed with the
  milestone table and with the code; the table is the one to trust.) M1d stays `in-progress`
  until a real sync is watched on a device, which needs your Gmail app password above rather
  than any code.
  *Override this only if you want a developer-only entry point sooner.*

## Log

Appended by the automation when it hits something new.

| When | Item | Why it came up |
|---|---|---|
| 2026-09-18 | May an ignore rule name a correspondent who is neither a bank nor a merchant? | Found while auditing the repo for anything traceable to you. Two IBM recruiting addresses sit in `MARKETING_SENDERS` alongside the banks and merchants, seeded from your mailbox like the rest of the list. Nothing is exposed that anyone could act on, and no rule in the project forbids it — which is exactly why the automation will not decide it. **Also worth knowing, and not a blocker:** the last three code defects in the email pipeline were closed this run, so the **Gmail app password** above is now the *only* thing between a green pipeline and a real sync. The rules behind it are no longer merely green — a payment confirmation now reaches the bill whose figure it states, over the window §20.5 actually specifies, and lends that bill no authority of its own. The first real sync will be testing your network and Gmail, not the logic |
| 2026-09-18 | (no new blockers) | Two engine behaviours landed — an own-account transfer now becomes a single `TRANSFER` (spec 14 §14.5), and a "payment received" mail now records a source without settling anything (spec 20 §20.4 step 7) — and neither needed anything from you. Worth knowing rather than doing: **the transfer fix is correct but inert until Fin knows your accounts.** The own-account list is seeded from `account_balances` and `cards`, so it only recognises accounts the app has already seen mail about, and `OwnAccount.vpa` is always null at schema v63 — a transfer identified only by a UPI handle still lands as two rows. That is the existing "Your own accounts and UPI handles" item above, which stays open and unticked. Also unchanged: the **Gmail app password** is still the single thing between the green pipeline and a real sync |
| 2026-09-18 | A device or emulator session for the schema-migration suite | The first Room-backed tests landed this run and run real SQL, which made the remaining hole precise rather than vague: they exercise the schema Room creates from scratch, never the upgrade path. `MigrationTestHelper` can only read the schema JSON through instrumentation assets, so the suite cannot run headlessly at any price worth paying. Nothing to do yet — filed so that M2's first hand-written migration is not written on the assumption that `./init.sh` can check it |
| 2026-09-18 | (no new blockers) | The **Gmail app password** and the **`dumpsys jobscheduler` confirmation** are both still open and still the only two things between the green pipeline and a real sync. Neither was ticked here — the automation never ticks; that is the owner's signal. The keystore entry above gained one factual note: its git-ignore promise is now actually enforced by `.gitignore` rather than by the files not existing |
| 2026-09-18 | How a mailbox gets connected — and therefore when M1d can close | M1d's last four code defects were fixed this run, which made the real blocker visible: the pipeline is complete and green but unreachable, because no screen can supply an address or an app password. Choosing between pulling screen 47 forward and a temporary developer entry point is a scope call, not a credential, so nothing in the repo can make it |
| 2026-09-18 | Confirm the periodic email sync is scheduled | The pipeline was wired end to end this run, so the worker is now enqueued at process start. JVM tests cannot see a WorkManager job; only a device can |
| 2026-09-18 | (no new blockers) | The **Gmail app password** above is now the single thing standing between the wired pipeline and a real sync — the code path behind it is complete and green. Not duplicated as a new entry |
| 2026-09-17 | (seeded by hand) | Initial list from the spec review |
