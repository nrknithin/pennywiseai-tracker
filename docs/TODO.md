# TODO

Working checklist for the FinTrack build. **Claude updates this every time something is
asked for or finished** — if it is not here, it was not agreed; if it is not ticked, it is
not done.

Status keys: `[x]` done and verified · `[~]` code written, **not compiled or run** ·
`[ ]` not started · `[!]` needs you, not me.

Last updated 2026-09-19, after the Google sign-in work.

> **App gate GREEN at 928 tests, 0 failures** (2026-09-19), every test task re-run and
> counted. Source is verified.

---

## Blocked on you

- [!] **Set a Cloud Billing budget** — Rs 500/month, alerts at 50/90/100%. Blaze bills rather
  than throttling, no budget exists today, and this is the only guard a client-side bug
  cannot defeat.
- [!] **Firestore rules — five steps, in order.** (a) Copy the current rules text somewhere
  safe; console history is the only undo. (b) Audit them for a broad `match /{document=**}`
  with a loose `allow` — rules are OR-ed, so such a block already exposes `/users/**` and
  nothing pasted after it can narrow that. (c) Deploy merged rules with the owner pin
  relaxed to `true`. (d) Sign in on one phone, read the UID from Authentication -> Users.
  (e) Paste the UID in and redeploy **before** the bulk upload. Note these rules currently
  serve a previous unrelated app.
- [!] **Add `fin.firebase.appId.debug` to `local.properties`** — the debug app`s
  `mobilesdk_app_id` from `fin-secrets/google-services.json`. `fin.firebase.appId` holds the
  **release** one. `FirebaseApp` does not validate appId against the running package, so a
  debug build appears to work while the API key`s package restriction is enforced
  server-side and you get opaque PERMISSION_DENIED.
- [!] **One single-field index exemption**: collection `records`, field `v`, collection
  scope, all modes off. Nothing queries inside `v`, and auto-indexing a ~30-leaf nested map
  across ~5,500 docs is pure cost. Leave `c`, `ts`, `d`, `wb` indexed. Create no composite
  index.
- [!] **Turn on 2FA for the Google account.** It becomes the single credential protecting
  your entire financial history, readable from the console with no app and no phone.
- [!] **Pick which phone is PRIMARY** and enable sync there first. Do not enable the second
  until the first reports its upload finished — random sync ids do not converge, and two
  simultaneous bootstraps produce permanent duplicate budgets and loans.
- [!] **Link each unlinked debit card to its account** — Manage Accounts → "Unlinked Cards".
  Zero code, and the only durable fix for the duplicated-account problem. See the accounts
  section below for why merging is the wrong tool.
- [!] **Decide the backup encryption model** (`docs/NEEDS-YOU.md`) — passphrase-derived key
  vs wrapped key. Blocks M6 and any encrypted cloud payload.
- [!] **S21 FE SMS transfer is stalled** at 220 of ~7,900. The second phone cannot be tested
  against real data until it finishes.

---

## In progress

- [ ] **Strip Pro / billing / paywall entirely.** Every feature stays and works; every Pro
  surface goes — upgrade sheet, Home sparkle chip, Settings upgrade row, "Unlock all", the
  membership ring, tip-jar nudges, and Play in-app update/review. ~2,000 lines across
  `billing/`, `presentation/paywall/`, both flavor gateways and 29 gate call sites.
  Read-only planning workflow running; cutting once the defect-fix workflow releases the
  tree. **Care needed:** "subscription" is overloaded here — `SubscriptionEntity`,
  `SubscriptionsScreen` and friends are YOUR recurring bills, a core feature, and must not
  be touched. Only Play *purchase* subscriptions are billing.
- [x] **Sync architecture designed and contracted** -> `docs/CLOUD-SYNC-CONTRACT.md` (87KB,
  every path and line number checked against the tree). Chosen: **outbox**. Room stays the
  source of truth; SQLite AFTER INSERT/UPDATE/DELETE triggers write *keys, never payloads*
  into a `cloud_outbox` table, because the DAOs are full of raw @Query UPDATE/DELETE that no
  repository wrapper could intercept. Document ids are `sha1(collection + natural key)` —
  Room rowids are per-device and would collide across your two phones. `sms_body` is
  excluded so the privacy claim is enforced by code, not promised. Initial upload ~5,500
  docs / ~1.5 MB, inside the free daily allowance.
- [ ] **Implement it** — 75 files across five tasks (A-build, B-firebase, C-sync, D-ui,
  E-tests). Not started.
- [ ] **Rewrite the false privacy copy.** `SignInScreen.kt` says "Everything after that stays
  on this device" and "nothing is sent anywhere". Both become untrue the moment sync ships.
  Part of the sync contract.
- [ ] **Record the spec deviation** in `docs/FORK-CHANGES.md`: live sync contradicts spec 00
  §0.2 (Firestore holds ciphertext blobs, never queryable data) and spec 08.

---

## Next up

_Empty — everything here is done. See **In progress** and **Blocked on you**._

- [x] **Ran `./init.sh app` — GREEN, 906 tests, 0 failures.** It caught a real break first:
  `OnBoardingNameLockTest` still passed four constructor arguments after
  `AccountBalanceRepository` left `OnBoardingViewModel`. Production compiled fine, so the
  app would have installed and looked healthy — the break was tests-only, the quiet kind.
- [x] **Rebuilt and reinstalled** at 10:50 from the green tree. This is the first APK on the
  S25 containing the four-step onboarding, the non-blocking scan, the profile sheet and the
  unlocked Pro features.

---

## Done

### Google sign-in
- [x] Native sign-in gate — Credential Manager + `GetSignInWithGoogleOption`, Android's own
  account sheet. No browser, no WebView. Verified on device: `auth_signed_in = TRUE`.
- [x] Gate is the app's first screen; onboarding only after it clears.
- [x] Google display name adopted as the profile name.
- [x] Onboarding name field **locked and populated**, with a lock glyph so it reads as
  deliberate. Avatar and colour pickers untouched.
- [x] App logo on the sign-in screen (was a generic `AccountCircle` person glyph).
- [x] `fin.google.webClientId` written into git-ignored `local.properties`; real
  `google-services.json` installed to `fin-secrets/` (old one backed up).
- [x] Both packages registered with SHA-1s, Google provider enabled — verified
  `client_type: 3` present.
- [x] No Firebase dependency in the build: the gateway compiles into `standard` + `personal`
  via `src/cloud`, `fdroid` gets a no-op from `src/nocloud`.
- [x] Account identity stored as a **SHA-256 hash** — the raw Google id is the email address
  and is never stored, logged or passed to a caller.
- [x] A different Google account resets onboarding; the same account does not.
- [x] "Continue without signing in" removed — the gate is absolute, at your request.

### Defects found in review and fixed
- [x] **First run would have silently done nothing.** `DEVELOPER_ERROR` arrives as a
  *cancellation*, and the typed catch swallowed it before classification. One catch now,
  classify first.
- [x] **Four ways to walk past the gate** — launcher shortcut, quick-add tile, widget, and
  edit-transaction notifications all navigated on the first frame. Now gated; the intent
  re-fires after sign-in rather than being lost.
- [x] **App lock died permanently after sign-out** — the lock effect was gated on the auth
  gate, so signing out swallowed every lock until a cold start. Replaced with a route guard.
- [x] **Sign-out never navigated** — it cleared the flag and left the whole ledger on screen.
  Both paths now `popUpTo(0)`.
- [x] **ID token could reach logcat** — `GoogleIdentity` is a data class, so its generated
  `toString()` interpolated a live credential. Redacted.
- [x] Privacy line corrected: the app receives name, email and picture; only the name is kept.
- [x] Unanchored `"10:"` match would have sent you to the Google Cloud console over an
  unrelated error. Anchored.

### Onboarding
- [x] **Primary-account step removed entirely.** You use accounts for different purposes, so
  there is no primary one. Settings still offers the same choice with the same currency side
  effect, for anyone who wants it.
- [x] **SMS scan no longer blocks.** It was already a WorkManager job; only the UI waited.
  Button is now **Finish** during the scan, back is re-enabled, and the screen says the scan
  keeps running.
- [x] Removed the upstream effect that auto-completed onboarding whenever SMS permission
  existed — it was skipping the whole flow on your phone.

### Pro / entitlement
- [x] **Everything unlocked.** `EntitlementGate.isProEntitled` is a constant `true` for every
  flavor. It was blocking eight features on the `standard` build you run, including a **CSV
  export silently truncated to 100 rows with no marker in the file** — a wrong total you
  would never have caught. Also: 3 rules max, 1 recurring template, 1 statement import per
  month, account merge unreachable, Top Tags analytics hidden, daily backup un-enableable.

### Environment
- [x] ADB over TCP to the S25 (`10.34.165.146:5555`) — it is the hotspot, so it is the gateway.
- [x] Clean rebuild and install at 00:51 (`BUILD SUCCESSFUL in 2m 38s`).

---


### Fixed this pass (all verified by the 928-test green run)
- [x] **Phantom `"XXXX"` account** — both balance-write paths now skip rather than invent an
  identifier, plus a narrow sweep for existing residue. Second-order catch: an unattributable
  HDFC balance SMS now `Discard`s instead of falling through to the transaction parsers,
  which would have traded a phantom account for a phantom transaction in the ledger.
- [x] **Account merge** — same-bank/relatedness guard, 13 tests where there were none, and
  the destructiveness spelled out in the sheet. Identity comparison deliberately left
  untrimmed: trimming made the commonest duplicate (`"HDFC Bank "` vs `"HDFC Bank"`)
  unmergeable AND hid it from the picker. Refusals now name the remedy.
- [x] **Settings Pro section removed** — no tier, no upgrade row, no paywall sheet.
- [x] **`fin.firebase.appId.debug`** written to `local.properties`; the existing key held the
  *release* id, which fails server-side with an opaque PERMISSION_DENIED on a debug build.
- [x] **Docs corrected** — `PlausibleDueSettlerTest` 17→21 (stale figure presented as
  measured), the false claim that `:shared` is gone, a mislabelled commit order, and two
  `NEEDS-YOU.md` boxes the automation ticked. Only the owner ticks those; reverted.

## Backlog — small, real, not urgent

- [x] **Phantom `"XXXX"` account — fixed.** `OptimizedSmsReaderWorker.kt:624` and `:665` invent a
  placeholder account when an SMS carries no account number. Should skip the balance write.
- [x] **Deleted `UnifiedAccountsCard.kt`** (264 lines, zero call sites). Line 138 formats every balance with the display
  currency regardless of the account's own — exactly what CLAUDE.md #2 forbids. It has no
  call sites; delete it before an accounts rework wires it up.
- [x] **Account merge guarded and tested.** No tests, no undo, does **not** require the two accounts to
  be the same bank, and it is not durable — the next SMS recreates the merged-away row, and a
  force resync reverts it entirely. Either fix or clearly mark as duplicate-repair only.
- [ ] Sort and section accounts by bank. The only account query is `ORDER BY balance DESC`,
  which is why the list reads as random.
- [ ] Put a transaction count and last-activity date on each account row and in the merge
  picker — currently there is no way to tell a real account from a stray one.
- [x] `docs/BUILD-STATUS.md` corrected: it claims the app gate is RED at 783 tests with
  one failure, which `238d41a3` fixed.
- [x] `docs/NEEDS-YOU.md` corrected: it lists the release keystore as outstanding, but
  `fin-secrets/fin-release.jks` exists and is wired into `local.properties`.

---

## Known traps (do not rediscover)

- **Two Gradle processes corrupt this repo.** Never run `./init.sh` while Android Studio is
  building. Drive from one or the other.
- **The top app bar gap was tried twice and reverted.** See `docs/START-HERE.md`.
- **`spec/` is git-ignored and contains real financial data.** Never copy values out of it.
- **`.env` holds a live Gmail app password.** Never print or commit it.
- **The SMS watermark trap** — transferred messages keep their original dates and are skipped
  by an incremental scan. Only a long-press on the Sync FAB forces a full rescan.
