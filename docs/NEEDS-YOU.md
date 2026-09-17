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

- [ ] **Firebase project** (only if you want M8 — the wake-up push and encrypted cloud snapshots).
  Four values — `projectId`, `appId`, `apiKey`, `senderId` — into git-ignored `local.properties`,
  plus `google-services.json` kept beside the keystore.
  *Meanwhile:* M8 is skipped entirely. Spec constraint C8 says the app is complete with every
  cloud feature off, and the `fdroid` flavor is the control build that proves it.

- [ ] **Cloudflare account + `wrangler` login** (only for M8, the scheduled bell).
  *Meanwhile:* same as above — skipped.

---

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
  WorkManager scheduling cannot be exercised by a JVM unit test — nothing in the 2,823 green
  tests proves the job exists on a real device.
  *Meanwhile:* the runner underneath it is tested headlessly (`EmailSyncWorkerTest`, 15 tests),
  so a scheduling problem would be the only thing this check could find.
  *Where it goes:* nowhere in the repo — it is a one-line confirmation after a cold start.

---

## Log

Appended by the automation when it hits something new.

| When | Item | Why it came up |
|---|---|---|
| 2026-09-18 | Confirm the periodic email sync is scheduled | The pipeline was wired end to end this run, so the worker is now enqueued at process start. JVM tests cannot see a WorkManager job; only a device can |
| 2026-09-18 | (no new blockers) | The **Gmail app password** above is now the single thing standing between the wired pipeline and a real sync — the code path behind it is complete and green. Not duplicated as a new entry |
| 2026-09-17 | (seeded by hand) | Initial list from the spec review |
