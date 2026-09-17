# Fork changes — every upstream file Fin touches

Fin is a private fork of [`sarim2000/pennywiseai-tracker`](https://github.com/sarim2000/pennywiseai-tracker),
branched from `c512c7c` (15 Sep 2026, schema v62). Spec `05-build-release.md` §5.6 requires
that fork changes live in **new files** wherever possible, and that every edit to an existing
upstream file is listed here with a reason. This file is the audit of that rule.

Keeping this list short is the point: the smaller the diff against upstream, the cheaper the
quarterly rebase.

## Upstream files edited

| File | Why |
|---|---|
| `app/src/main/java/com/pennywiseai/tracker/data/database/PennyWiseDatabase.kt` | Unavoidable. Room requires every entity and DAO to be declared on the `@Database` class. Bumps `SCHEMA_VERSION` 62 → 63, registers the five M1 entities and their DAOs, and adds `AutoMigration(62, 63)`. No existing entity, DAO or migration was altered. |

## New files added by the fork

Nothing below exists upstream, so none of it contributes to the rebase diff.

### `parser-core` — the email channel (M1)

| Path | Contents |
|---|---|
| `…/parser/core/text/` | `Money`, `DateParse` — amount and date parsing shared by every email parser (spec 12 §12.11) |
| `…/parser/core/email/` | `EmailMessage`, `SenderMapping`, `EmailSenderRegistry`, `IgnoreRules`, `EmailTransactionParser`, `EmailParserFactory`, and seven per-sender parsers (HDFC, Axis, ICICI, OneCard, SBI, CRED payment, PGSharp payment) |
| `…/parser/core/email/due/` | `DueKind`, `ParsedDue`, `DueParser`, `DueParserFactory`, and eight due parsers (HDFC mandate + preclosure, CRED bill, ICICI + Axis statements, GoDaddy, PGSharp, Cloudflare) |
| `…/parser/core/test/EmailParserTestUtils.kt` | The email counterpart to upstream's `ParserTestUtils`, with a fixed clock so relative wording ("due today") is deterministic |
| `…/parser/core/PiiLintTest.kt` | Enforces spec 04 §4.5. Scoped to fork-owned paths — see the note in the file for why upstream's tree is excluded |

### `app` — the v63 schema (M1)

| Path | Contents |
|---|---|
| `…/data/database/entity/` | `EmailMessageEntity`, `DueEntity`, `SyncRunEntity`, `TransactionSourceEntity`, `DueSourceEntity`, plus the `DueEnums` and `SourceEnums` vocabularies |
| `…/data/database/dao/` | `EmailMessageDao`, `DueDao`, `SyncRunDao`, `TransactionSourceDao`, `DueSourceDao` |

### Documentation

| Path | Contents |
|---|---|
| `spec/` | The 50-file Fin specification. Not upstream's; not shipped in the APK |
| `docs/FORK-CHANGES.md` | This file |

## Deviations from the spec, and why

These are places where following the spec literally would have produced code that does not
compile, does not run, or loses data. Each is also commented at the site.

| Spec says | Fin does | Why |
|---|---|---|
| `02-architecture.md` §2.2 puts new parser code in `parser-core/src/commonMain` | It goes in `src/main/kotlin` (wired as `jvmMain`) | `commonMain` holds one file upstream and cannot see `java.math.BigDecimal`, which `ParsedTransaction` and every amount depend on. All 168 existing bank parsers live in `src/main/kotlin` for the same reason |
| `03-data-model.md` §3.5 lists a `DueKind` without `UTILITY_BILL` or `LOAN_PRECLOSURE`; `12-email-parsers.md` emits both, plus `SUBSCRIPTION_CHARGE` | `SUBSCRIPTION_CHARGE` → `SUBSCRIPTION`; `UTILITY_BILL` and `LOAN_PRECLOSURE` added to the enum | The three names in file 12 have no home in file 03's list, so those parsers could not be persisted at all. Adding an enum value needs no migration (§3.5) |
| `03-data-model.md` §3.3 files `sync_runs`, `transaction_sources` and `due_sources` under M2 (v64) | They are created at v63 | M1's own pipeline writes all three from its first run (spec 11 §11.3 step 2, §11.4 steps b and f). `ignore_rules`, `sender_overrides` and `never_merge_pairs` genuinely do wait for M2 |
| `12-email-parsers.md` §12.10 seeds `order-update@amazon.in` and `shipment-tracking@amazon.in` as dropped marketing | They are not dropped | `24-receipts-shopping.md` §24.2 names `order-update@` as a receipt source and R-24-01 needs its status progression. Ignore rules run first and short-circuit, so seeding them would make M5 unimplementable |
| `12-email-parsers.md` §12.10 drops any subject containing `offer`, `deal`, `sale`, `IPO`, `survey` or `%` | Those match on word boundaries; `%` only after a digit | As plain substrings, `sale` drops "wholesale" and `%` drops any subject quoting a rate — including real bank mail. Dropping a genuine bill is silent data loss |
| `12-email-parsers.md` §12.11 lists `MM/dd/yyyy` last in one ordered list of date patterns | When a sender is flagged `preferDMY = false`, US ordering is tried **first** | Listed last, `03/09/2026` parses as 3 September before the US pattern is reached — so every Xsolla date with a day ≤ 12 would be silently wrong |
| `06-test-plan.md` §6.1 puts `PiiLintTest` in `app/src/test/` | It lives in `parser-core/src/test/` | There it runs in the two-minute `./init.sh parser` loop rather than only in the Android unit-test job. It still scans `app/` |
| `14-dedup-and-linking.md` §14.3 guards that "email never twins another email" | The guard compares channel **and** parser template id | HDFC sends H7 and H8 for the same card charge and spec 10 §10.8 requires them to collapse. As written the guard and that requirement cannot both hold |

## Still open — decisions for the owner

Recorded here so they are not lost; none blocks M1.

- **`05-build-release.md` §5.5 vs upstream `CLAUDE.md`.** The spec wants `versionName = "fin-0.2.0+pwc512c7c"`; upstream makes it a hard constraint that `scripts/release.sh` owns the version and it is never hand-edited. The fork must either break that rule or replace the script.
- **The `personal` flavor cannot compile as specified.** `05-build-release.md` §5.2 defines it as "`fdroid` behaviour plus cloud", but Gradle flavors in one dimension do not share source sets, and the four classes it needs live in `app/src/fdroid/`. Needs a shared source set (e.g. `app/src/unlocked/`) added to both flavors.
- **Backup and cloud snapshot specify two incompatible encryption schemes for the same bytes.** `27-backup-drive.md` R-27-03 derives the key straight from the passphrase; `08-cloud-services.md` §8.5.3 calls it the same payload but wraps a random DEK. The `PWFINENC1` container has no slot for a wrapped key, so one of the two has to change before backups exist.
