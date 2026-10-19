# FinTrack Cloud Sync — Implementation Contract

Every file path, line number and API below was checked against the working tree at `fin/main` (HEAD `9d1bd147`) during this session. Anything I could not check is marked **VERIFY**.

---

## Decision

**Base architecture: `outbox`**, because it is the only one of the three whose delete story rests on a verified fact rather than an assumption — I extracted `room-runtime-2.8.4-sources.jar` and confirmed `commonMain/androidx/room/InvalidationTracker.kt:215` executes `connection.execSQL("PRAGMA recursive_triggers = 1")` inside `configureConnection`, guarded by `if (!isReadConnection)`, which is what makes `AFTER DELETE` triggers fire for the four `ForeignKey.CASCADE` children (`TransactionSplitEntity`, `TransactionTagCrossRef`, `BudgetCategoryEntity`, `RuleApplicationEntity`). Room stays the source of truth; SQLite `AFTER INSERT/UPDATE/DELETE` triggers write keys (never payloads) into a `cloud_outbox` table, because `TransactionDao` and `AccountBalanceDao` are full of raw `@Query UPDATE/DELETE` statements that no repository wrapper can intercept; a single `users/{uid}/records` collection with one monotone `orderBy(ts, __name__)` cursor replaces twenty per-collection cursors that can disagree. Grafted from `firestore-native`: `sms_body` is excluded so the sign-in screen can make a privacy claim the code actually enforces, and the security rule `ts == request.time` makes the server clock the only clock by rule. Grafted from `pragmatic`: a whole-dataset key-set reconcile that depends on nothing else being correct, a one-way `hasCompletedOnboarding` ratchet so sync can never *cause* the bug it was asked to fix, `receipt_path` never overwritten from remote, and reuse of `backupJson` as the wire codec so money stays a decimal string by construction. Four fatal flaws are fixed rather than inherited: `outbox`'s per-field `fts` merge is deleted outright (it compared per-device `cloud_outbox.id` counters across devices, which decides by lifetime write count, not recency); `outbox`'s `UNIQUE INDEX` on `transactions.sync_uid` is deleted along with the column itself (the soft-delete hash rewrite at `TransactionDao.kt:187-191` exists *so that* a rescan can re-insert the same hash, which would have aborted the INSERT inside the trigger); `pragmatic`'s `''`-sentinel unique index is never created; and the force-resync interaction gets a real mechanism (role gate + generation-token suppression + forced reconcile) instead of a to-do in a risk list. One risk all three designs carried is **false** and is dropped: `fin.firebase.projectId` in `local.properties` matches `project_info.project_id` in `fin-secrets/google-services.json` exactly — `docs/NEEDS-YOU.md:35-36` is the stale artifact, not `local.properties`.

---

## Firestore layout

Root is `users/{uid}`, where `uid` is the Firebase Auth UID. One Google account → one UID → both phones share one tree.

### Document ID strategy (read this before anything else)

**Room `autoGenerate = true` `Long` primary keys are SQLite rowids assigned independently on each phone and WILL collide.** 24 of the 30 entities use them. `BackupImporter` already proves the codebase knows this — every merge path re-inserts with `copy(id = 0)` and rebuilds an old→new map. No rowid is ever a document ID, and no rowid is ever a field value in a document.

```
docId = sha1hex( c + "\u001f" + k )          // 40 lowercase hex chars
```

`c` is the collection tag, `k` is the canonical natural key. Hashing rather than using `k` raw, because Firestore document IDs may not contain `/`, may not be `.` or `..`, may not match `__.*__`, and category/tag/bank names are user-authored. `k` travels in the document, readable, so the console stays browsable and ID derivation stays debuggable.

**Transactions use no new column.** The canonical key is `transaction_hash` **as the trigger sees it**: `NEW.transaction_hash` on INSERT, `OLD.transaction_hash` on UPDATE and DELETE. This is what makes the soft delete correct without a `sync_uid`:

- `softDeleteTransaction` (`TransactionDao.kt:187-191`) is `UPDATE transactions SET is_deleted = 1, transaction_hash = 'DELETED_' || id || '_' || transaction_hash WHERE id = :transactionId`. The `AFTER UPDATE` trigger reads `OLD.transaction_hash` = the original `H`, so the outbox row is keyed on `H` and the tombstone lands on the **same** document the live row occupied.
- At drain time the pusher looks the row up by `H`, finds nothing (the hash was rewritten), and downgrades `UPSERT → DELETE`. A tombstone is written. The device-specific `'DELETED_<rowid>_H'` string never leaves the phone.
- A subsequent force resync re-inserts the same SMS with hash `H` — which the DAO comment at `TransactionDao.kt:150-156` says is the entire purpose of the prefix — and that row upserts the same document back to live. Local and remote agree, and no UNIQUE constraint is violated anywhere, because `transaction_hash`'s existing unique index is the only one involved and the app already depends on it.
- The `AFTER DELETE` trigger carries `WHEN OLD.transaction_hash NOT LIKE 'DELETED\_%' ESCAPE '\'`, so hard-deleting an already-soft-deleted row does not create a junk tombstone at a hash that was never published.

Five tables have neither a natural key nor a content key and get a new `sync_uid TEXT NOT NULL DEFAULT ''` column: `subscriptions`, `budgets`, `transaction_groups`, `loans`, `transaction_splits`. **The index on it is NOT unique** — a UNIQUE index whose backfill sentinel is `''` fails on the second unpushed row, and a constraint failure raised inside an `AFTER INSERT` trigger aborts the enclosing statement, which on this install would kill the SMS scan. Uniqueness comes from `UUID.randomUUID()` assigned at one Kotlin choke point, and duplicates are detectable by the reconciler.

Because random UUIDs do not converge, **the two phones must not both bootstrap**: `cloud_state.role` is `PRIMARY` on the first phone to enable sync (it uploads) and `REPLICA` on the second, whose outbox is not drained until a full reconcile pull has completed and adopted remote identities by natural key. Without that rule, two already-populated phones produce permanent duplicate budgets and loans that nothing can tell apart.

### `users/{uid}` — workspace header

| field | type | notes |
|---|---|---|
| `syncProtocol` | number | `1`. A phone seeing a higher value refuses to sync and says so. |
| `roomSchema` | number | `64`. A phone with a lower schema refuses to pull. |
| `appVersion` | string | last writer's `versionName` |
| `createdAt` | timestamp | server |

### `users/{uid}/meta/profile` — the document that fixes the reported problem

| field | type | DataStore key |
|---|---|---|
| `ts` | timestamp | `FieldValue.serverTimestamp()` |
| `wb` | string | writer device id |
| `wseq` | number | writer's monotonic outbox sequence |
| `hasCompletedOnboarding` | bool | `has_completed_onboarding` |
| `userName` | string | `user_name` |
| `profileBackgroundColor` | number \| null | `profile_background_color` |
| `mainAccountKey` | string \| null | `main_account_key` |
| `baseCurrency` | string \| null | derived base currency |
| `baseCurrencyUserSet` | bool | `base_currency_user_set` |
| `homeSections` | string \| null | `home_sections` (already a serialized string) |
| `useContactsForVpa` | bool | `use_contacts_for_vpa` |
| `profileNames` | map | `{"1": "...", "2": "..."}` from the `profiles` table |

Deliberately absent: `auth_signed_in` and `auth_account_key` (per-device gate state — syncing them would let one phone's sign-out shut the other's gate; both verified at `UserPreferencesRepository.kt:162,171`), `profile_image_uri` (a local `content://` URI, meaningless on the other phone), the scheduled-folder-backup SAF tree URI (a per-device grant), `last_scan_timestamp`, developer flags, app-lock settings, and the whole theme block (the two phones have different screens).

### `users/{uid}/meta/state`

`{ tombstoneFloor: timestamp, lastGcAt: timestamp, primaryDeviceId: string }`. `tombstoneFloor` is the oldest surviving tombstone. **A phone whose cursor is older than this must refuse the incremental path and run a full reconcile** — two lines, and the difference between correct and "works until a phone sits in a drawer for four months".

### `users/{uid}/devices/{deviceId}`

`{ name, model, appVersion, flavor, roomSchema, role, lastSeenAt, lastPushTs, lastPullTs, pendingCount, failedCount, lastError, lastErrorAt }`. `deviceId` is a random UUID minted at first run and kept in DataStore — **not** the Android ID, not any hardware identifier.

### `users/{uid}/records/{docId}` — the replica

| field | type | meaning |
|---|---|---|
| `c` | string | collection tag (see table below) |
| `k` | string | canonical natural key, human-readable |
| `d` | bool | tombstone flag; `true` ⇒ `v` absent |
| `ts` | timestamp | `FieldValue.serverTimestamp()`. **The only clock that orders anything.** |
| `wb` | string | writer device id |
| `wseq` | number | writer's `cloud_outbox.id`. Makes a retry byte-identical ⇒ idempotent. |
| `sv` | number | payload schema version for this `c` |
| `h` | string | first 16 hex of SHA-256 over the canonical serialization of `v`. The reconciler diffs on this. |
| `v` | map | the row. Absent when `d == true`. |

**Value encoding (non-negotiable):**

- **Money is a string**, `BigDecimal.toPlainString()`, never a Firestore number — Firestore numbers are IEEE-754 doubles and would silently round rupee values. Every amount travels with its `currency` string beside it (CLAUDE.md #1). No document anywhere holds a cross-currency total (CLAUDE.md #2); totals are computed on-device with `sumByCurrency`.
- `LocalDateTime` / `LocalDate` are ISO-8601 **strings**, not Firestore `Timestamp` — those columns carry no zone and inventing one is a bug. Only `ts` is a real `Timestamp`.
- `v` is produced by `backupJson.encodeToJsonElement(entity)` plus one `JsonElement → Map<String, Any?>` converter. Every entity is already `@Serializable` with `@Contextual BigDecimal`/`LocalDateTime` (verified on `TransactionEntity.kt:16-23`), so this replaces ~20 hand-written mappers and makes it structurally impossible for the cloud codec to drift from the backup codec.
- **Foreign keys never travel as `Long`.** `transactions.loan_id → loanUid`, `.group_id → groupUid`, `transaction_splits.transaction_id → transactionKey` (the parent's hash), both halves of `transaction_tag_cross_ref → transactionKey` + `tagName`, `budget_categories.budget_id → budgetUid`, `categories.parent_id → parentName`, `dues.settled_transaction_id → settledTransactionKey`. `profile_id` is the one exception: `ProfileEntity` is `@PrimaryKey val id: Long` with no `autoGenerate` (verified `ProfileEntity.kt:11-13`), ids 1 and 2 are seeded identically by `DatabaseCallback`, so it travels as-is.

### The 19 `c` values

| `c` | source table | canonical key `k` |
|---|---|---|
| `transactions` | `transactions` | `transaction_hash` (see ID strategy) |
| `accounts` | *derived projection* | `bank_name \u001f account_last4` |
| `balances` | `account_balances` | `bank_name \u001f account_last4 \u001f timestamp(iso)` |
| `categories` | `categories` | `lower(name)` — `Index(["name"], unique)` verified |
| `cards` | `cards` | `lower(bank_name) \u001f card_last4` — unique index verified |
| `tags` | `tags` | `lower(name)` — unique index verified |
| `tagLinks` | `transaction_tag_cross_ref` | `txnHash \u001f lower(tagName)` |
| `splits` | `transaction_splits` | `sync_uid` |
| `groups` | `transaction_groups` | `sync_uid` |
| `loans` | `loans` | `sync_uid` |
| `budgets` | `budgets` | `sync_uid` |
| `budgetCategories` | `budget_categories` | `budgetUid \u001f lower(category_name)` — `Index(["budget_id","category_name"], unique)` verified |
| `subscriptions` | `subscriptions` | `sync_uid` |
| `recurring` | `recurring_transactions` | `uid` — **already exists**, `RecurringTransactionEntity.kt:41-42`, the in-repo precedent |
| `rules` | `transaction_rules` | `id` (already an app-assigned `String`) |
| `merchantMappings` | `merchant_mappings` | `lower(merchant_name)` — PK *is* the natural key |
| `merchantAliases` | `merchant_aliases` | `lower(merchant_name)` |
| `dues` | `dues` | `due_id` — `Index(["due_id"], unique)` verified, already content-derived |
| `profiles` | `profiles` | `"1"` / `"2"` |

**`accounts` is a projection, not a table.** There is no accounts table; `AccountBalanceDao` materialises an account with `GROUP BY bank_name, account_last4`. But five *authored* settings live smeared across every row of the balance series — verified blanket updates at `AccountBalanceDao.kt:226,229,232,239,278` (`statement_day`, `profile_id`, `alias`, `lowBalanceThreshold`, `currency`), each `WHERE bank_name = ? AND account_last4 = ?`. Renaming one account touches thousands of rows. So `account_balances` gets **two** UPSERT triggers split by column list: `AFTER UPDATE OF alias, profile_id, statement_day, lowBalanceThreshold, currency, account_type` enqueues one `accounts` doc; everything else enqueues a `balances` doc. Because the outbox is keyed `(coll, doc_key)`, all 3,000 firings collapse to **one** Firestore write. On apply, an `accounts` document goes back in through those same five DAO methods — no new write path, no new table. `WALLET_ACCOUNT_MARKER = "WALLET"` is a legal `account_last4` here and needs no special case.

### Does not sync

| table | why not |
|---|---|
| `transactions.sms_body` **(field)** | ~3× the document size and the most sensitive column in the database. Re-derivable from each phone's own inbox. Excluding it is what lets the sign-in screen say something true. One constant, `CloudPayloadPolicy.INCLUDE_SMS_BODY = false`, if you disagree. Consequence stated in the UI: phone B cannot show the original message behind a transaction. |
| `transactions.receipt_path` **(field)** | A relative path into `filesDir/receipts/`. The string would sync, the JPEG would not. Excluded, **and apply never overwrites a local `receipt_path`**, so each phone keeps its own. |
| `chat_messages` | AI conversation transcripts about the owner's finances — the highest sensitivity-to-value ratio in the schema, and chatty. |
| `unrecognized_sms`, `bank_notifications` | Raw SMS and notification text the parser failed on. Pruned at 30 days. Meaningless on a phone that did not receive them. |
| `email_messages` | Raw email bodies, replayable from the mailbox. |
| `exchange_rates` | A TTL cache with its own `expires_at`. Syncing a cache syncs staleness. |
| `rule_applications` | Regenerable audit trail. Also `transactionId` is declared `String` against a `Long` FK — do not build ID mapping on it. |
| `budget_month_snapshots`, `budget_category_month_snapshots` | Pure functions of `budgets` + `transactions`, both of which sync. |
| `sync_runs`, `due_sources`, `transaction_sources`, `sender_overrides` | Per-device run logs and IMAP cursors. Two phones running IMAP would fight over them. `dues` alone is carried out of this group because `due_id` is content-derived and unique. |
| `cloud_outbox`, `cloud_applied`, `cloud_conflicts`, `cloud_state` | Device-local machinery. Also excluded from `DatabaseSnapshot`. |
| IMAP credentials, the vault passphrase | Never, anywhere. |

Estimated size: ~5,500 documents, ~1.5 MB of document data.

---

## Security rules

**Merge this. Do not paste over what is there.** The existing rules in this project serve a previous, unrelated app. Firestore rules are OR-ed, so adding a `match` block can only grant — it cannot narrow anything. **Before deploying, audit the existing rules for a broad `match /{document=**}` with a loose `allow`: such a block already exposes `/users/**` and nothing below can revoke it.** For the same reason no trailing `allow read, write: if false;` appears here — it would be a no-op that reads as protection.

Replace `PASTE_OWNER_FIREBASE_UID` after the first sign-in (Console → Authentication → Users). Both phones share one Google account and therefore one UID.

```
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {

    // ════════════════════════════════════════════════════════════════════
    // EXISTING RULES FOR THE OTHER APP GO HERE, UNCHANGED.
    // Rules are OR-ed: the FinTrack block below cannot narrow them, and
    // deleting one of them cannot be undone except from rules history.
    // ════════════════════════════════════════════════════════════════════

    // ───────────────────────────── FinTrack ─────────────────────────────

    function finOwner(uid) {
      return request.auth != null
          && request.auth.uid == uid
          && uid == 'PASTE_OWNER_FIREBASE_UID';
    }

    // Shape check for replica documents. Not security -- the owner is the only
    // writer -- but `ts == request.time` is what makes the cursor trustworthy:
    // no client can backdate a write and slip in behind another phone's cursor.
    function finRecordOk() {
      let d = request.resource.data;
      return d.keys().hasAll(['c', 'k', 'd', 'ts', 'wb', 'wseq', 'sv'])
          && d.keys().hasOnly(['c', 'k', 'd', 'ts', 'wb', 'wseq', 'sv', 'h', 'v'])
          && d.c is string  && d.c.size() > 0 && d.c.size() <= 40
          && d.k is string  && d.k.size() <= 512
          && d.d is bool
          && d.ts == request.time
          && d.wb is string && d.wb.size() <= 64
          && d.wseq is int  && d.wseq >= 0
          && d.sv is int
          && (d.d == true ? !('v' in d.keys()) : d.v is map);
    }

    match /users/{uid} {
      allow read, write: if finOwner(uid);

      match /meta/{docId} {
        allow read:  if finOwner(uid);
        allow write: if finOwner(uid)
                     && request.resource.data.ts == request.time;
      }

      match /devices/{deviceId} {
        allow read, write: if finOwner(uid);
      }

      match /records/{docId} {
        allow read:   if finOwner(uid);
        allow create: if finOwner(uid) && finRecordOk();
        allow update: if finOwner(uid) && finRecordOk();
        // Only tombstone GC deletes, and only a tombstone. A live row can be
        // retired to d == true, but it must never simply vanish: a deleted
        // document matches no `ts >= cursor` query, so a phone that was offline
        // would never learn about it and would push the row back.
        allow delete: if finOwner(uid) && resource.data.d == true;
      }

      // No {document=**} wildcard on purpose: an unmatched subcollection
      // should fail loudly in development rather than silently accept
      // whatever shape a future bug invents.
    }
  }
}
```

The pinned literal means a **copied APK signed into any other Google account gets nothing — not even its own tree.** That is also the only clause you cannot verify without a second Google account, which is why the emulator tests below matter.

---

## Room changes

**`SCHEMA_VERSION` 63 → 64.** Verified current value at `FinTrackDatabase.kt:80`; the `@Database` entity list currently holds 30 entities (`FinTrackDatabase.kt:93-97`).

This is an **`@AutoMigration(from = 63, to = 64, spec = Migration63To64::class)`**, not a manual `Migration`. Everything added is additive — new tables and new columns with `defaultValue` — so Room generates the DDL, and there is no hand-written `CREATE TABLE` text to reconcile against `64.json`. Nothing goes into `ALL_MIGRATIONS`; that array is for manual `Migration` objects only (`FinTrackDatabase.kt:781-805`). There is deliberately **no UNIQUE index in this migration**, which is what removes the add-column → backfill → index ordering problem entirely.

### New columns — five tables

```kotlin
// SubscriptionEntity, BudgetEntity, TransactionGroupEntity, LoanEntity, TransactionSplitEntity
@ColumnInfo(name = "sync_uid", defaultValue = "")
val syncUid: String = ""
```

Plus a non-unique `@Index(value = ["sync_uid"])` on each, for lookup only.

**CLAUDE.md #3 holds:** every one carries both a Kotlin default *and* a matching `defaultValue`. All five entities are in `DatabaseSnapshot` and therefore in `BackupSchemaGuardTest.entityDescriptors` — a defaulted field is *optional* in the serial descriptor, so the required-field baseline is unchanged and #414 is not re-opened.

**`transactions` gets no new column.** See the ID strategy above.

### New entities (four)

```kotlin
@Entity(tableName = "cloud_outbox",
        indices = [Index(value = ["state", "id"]), Index(value = ["coll", "doc_key"])])
data class CloudOutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,          // the monotonic wseq
    @ColumnInfo(name = "coll")        val coll: String = "",
    @ColumnInfo(name = "doc_key")     val docKey: String = "",
    @ColumnInfo(name = "op")          val op: String = "UPSERT",   // UPSERT | DELETE
    @ColumnInfo(name = "state")       val state: String = "PENDING",
        // PENDING | SENDING | SENT | SUPERSEDED | FAILED
    @ColumnInfo(name = "enqueued_at") val enqueuedAt: Long = 0,
    @ColumnInfo(name = "attempts")    val attempts: Int = 0,
    @ColumnInfo(name = "last_error")  val lastError: String? = null,
    @ColumnInfo(name = "batch_id")    val batchId: String? = null,
    @ColumnInfo(name = "sent_at")     val sentAt: Long? = null
)

@Entity(tableName = "cloud_applied")            // idempotency ledger for the pull side
data class CloudAppliedEntity(
    @PrimaryKey @ColumnInfo(name = "doc_id") val docId: String = "",
    @ColumnInfo(name = "coll")   val coll: String = "",
    @ColumnInfo(name = "wb")     val wb: String = "",
    @ColumnInfo(name = "wseq")   val wseq: Long = 0,
    @ColumnInfo(name = "ts")     val ts: Long = 0,        // server ts, epoch millis
    @ColumnInfo(name = "digest") val digest: String = "", // local payload digest
    @ColumnInfo(name = "applied_at") val appliedAt: Long = 0
)

@Entity(tableName = "cloud_conflicts")          // every lost update, kept and visible
data class CloudConflictEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "doc_id") val docId: String = "",
    @ColumnInfo(name = "coll")   val coll: String = "",
    @ColumnInfo(name = "k")      val k: String = "",
    @ColumnInfo(name = "winner") val winner: String = "",
    @ColumnInfo(name = "loser")  val loser: String = "",
    @ColumnInfo(name = "fields") val fields: String = "",
    @ColumnInfo(name = "at")     val at: Long = 0
)

@Entity(tableName = "cloud_state")              // exactly one row, id = 1
data class CloudStateEntity(
    @PrimaryKey val id: Int = 1,
    /** Master switch. 0 = triggers inert. Default 0, so fdroid and every
     *  signed-out standard install emit nothing and pay nothing. */
    @ColumnInfo(name = "outbox_enabled", defaultValue = "0") val outboxEnabled: Int = 0,
    /** Generation token held by whoever opened the current suppression window;
     *  '' means no window. Closed explicitly in a finally block. */
    @ColumnInfo(name = "suppress_owner", defaultValue = "''") val suppressOwner: String = "",
    /** Safety valve ONLY, epoch millis. Time-boxed rather than a pure boolean so a
     *  process death mid-apply cannot permanently disable capture -- the single worst
     *  failure this design could have. Never used as the primary window bound. */
    @ColumnInfo(name = "suppress_until", defaultValue = "0") val suppressUntil: Long = 0,
    @ColumnInfo(name = "role", defaultValue = "'PRIMARY'")   val role: String = "PRIMARY",
    @ColumnInfo(name = "device_id", defaultValue = "''")     val deviceId: String = "",
    @ColumnInfo(name = "uid", defaultValue = "''")           val uid: String = "",
    @ColumnInfo(name = "cursor_ts", defaultValue = "0")      val cursorTs: Long = 0,
    @ColumnInfo(name = "cursor_doc_id", defaultValue = "''") val cursorDocId: String = "",
    @ColumnInfo(name = "last_reconcile_at", defaultValue = "0") val lastReconcileAt: Long = 0,
    @ColumnInfo(name = "writes_today", defaultValue = "0")   val writesToday: Int = 0,
    @ColumnInfo(name = "writes_day", defaultValue = "0")     val writesDay: Long = 0
)
```

All four carry epoch `Long`s, never `LocalDateTime` — that type has no zone and the sync layer must not inherit the problem. **None of them is `@Serializable`, none is added to `DatabaseSnapshot`, and none is added to `BackupSchemaGuardTest`.** Restoring another device's outbox would replay foreign writes. Say so in a comment or someone will helpfully "fix" the omission.

### The migration spec

```kotlin
class Migration63To64 : AutoMigrationSpec {
    override fun onPostMigrate(db: SupportSQLiteDatabase) {
        super.onPostMigrate(db)
        db.execSQL("INSERT OR IGNORE INTO cloud_state (id) VALUES (1)")
        db.execSQL("UPDATE subscriptions      SET sync_uid = lower(hex(randomblob(16))) WHERE sync_uid = ''")
        db.execSQL("UPDATE budgets            SET sync_uid = lower(hex(randomblob(16))) WHERE sync_uid = ''")
        db.execSQL("UPDATE transaction_groups SET sync_uid = lower(hex(randomblob(16))) WHERE sync_uid = ''")
        db.execSQL("UPDATE loans              SET sync_uid = lower(hex(randomblob(16))) WHERE sync_uid = ''")
        db.execSQL("UPDATE transaction_splits SET sync_uid = lower(hex(randomblob(16))) WHERE sync_uid = ''")
    }
}
```

`onPostMigrate(db: SupportSQLiteDatabase)` is the overload that compiles in this repo — verified against the existing `Migration7To8` and `Migration10To11` at `FinTrackDatabase.kt:843` and `:864`. `randomblob(16)` is evaluated per row. On the largest table these touch (splits, low hundreds) this is milliseconds. `outbox_enabled` stays 0, so **the migration changes nothing observable** until sync is explicitly turned on.

### Triggers — and the latent bug you must fix in the same pass

Triggers are not part of Room's schema identity (Room's `TableInfo` validates tables, columns, indices, FKs and views — never triggers), so they live in a `RoomDatabase.Callback`, not in the migration:

```kotlin
// CloudTriggers.kt, referenced from FinTrackDatabase beside ALL_MIGRATIONS.
class CloudSyncCallback : RoomDatabase.Callback() {
    override fun onCreate(db: SupportSQLiteDatabase) = install(db)
    override fun onOpen(db: SupportSQLiteDatabase) {
        db.execSQL("PRAGMA recursive_triggers = ON")   // defensive; Room already sets it
        db.execSQL("UPDATE cloud_state SET suppress_owner = '' WHERE id = 1 " +
                   "AND suppress_until < CAST(strftime('%s','now') AS INTEGER) * 1000")
        install(db)                                    // CREATE TRIGGER IF NOT EXISTS
    }
    private fun install(db: SupportSQLiteDatabase) = CloudTriggers.STATEMENTS.forEach(db::execSQL)
}
```

`onOpen` is load-bearing, not belt-and-braces: a future `AutoMigration` that changes a column recreates the table (create → copy → drop → rename), which **silently drops every trigger on it**, and Room's validation will never tell you. `CREATE TRIGGER IF NOT EXISTS` in `onOpen` self-heals that on the next launch, forever.

**Register the callback on BOTH builders.** `DatabaseModule.provideFinTrackDatabase` already calls `.addCallback(DatabaseCallback())` (verified, `DatabaseModule.kt:72`). The non-Hilt fallback `FinTrackDatabase.getInstance(context)` calls `.addMigrations(*ALL_MIGRATIONS)` and **no callback at all** (verified, `FinTrackDatabase.kt:254-266`) — which means a database first created through the `BroadcastReceiver` path today also skips default-category seeding. Add both callbacks there and fix that pre-existing bug. This asymmetry is exactly the shape of the v47→v48 boot crash the comment at `FinTrackDatabase.kt:770-780` was written about.

### Backup changes (three small, defaulted additions)

- `PreferencesSnapshot` (`BackupModels.kt:244-256`) gains a fifth block, `@SerialName("profile") val profile: ProfilePreferences = ProfilePreferences()`, carrying `hasCompletedOnboarding`, `userName`, `mainAccountKey`, `profileBackgroundColor`, `baseCurrencyUserSet`. **None of these is in the backup today** — which is why an existing backup would not have rescued the owner either. Every field defaulted; add `ProfilePreferences` to `BackupSchemaGuardTest.backupModelDescriptors`.
- `FinTrackBackup.CURRENT_FORMAT` goes `"FinTrack Backup v1.2"` → `"FinTrack Backup v1.3"`; `COMPATIBLE_PREFIX` stays `"FinTrack Backup v1"` (verified `BackupModels.kt:49,52`). `backupJson` already has `ignoreUnknownKeys` and `coerceInputValues`, so old backups load into the new models and new backups load into old builds.
- The five new `sync_uid` fields ride along inside their existing entities, so a restored backup rejoins the same sync identity instead of minting duplicates.

### Schema file

`ksp { arg("room.schemaLocation", ...) }` emits `app/schemas/com.nrknithin.fintrack.data.database.FinTrackDatabase/64.json`, and it **must be committed** — `63.json` is the newest one present today. **This requires a Gradle run and is therefore blocked in this session.** It is the first action of the implementation session, alone, before anything else.

---

## Sync mechanics

### Change tracking

SQLite `AFTER INSERT / UPDATE / DELETE` triggers into `cloud_outbox`. Everything else was ruled out on evidence in this tree:

- **`updated_at` columns** — absent on several synced tables, `LocalDateTime` with no zone where present, defaulted at *construction*, and most writes go through `@Update` with a whole entity so nothing advances them (only a handful of queries bump them explicitly, e.g. `TransactionDao.kt:160`). A hint, never a change feed. And they cannot record a delete.
- **Room's `InvalidationTracker`** — table-granular. It says `transactions` changed, not which row, and never which row was deleted.
- **Repository-level hooks** — defeated by `updateCategoryForMerchant`, `setProfileForAccountTransactions`, `softDeleteByHash`, `deleteUncuratedTransactions`, `deleteRebuildableBalances`, `setAccountAlias` and dozens of peers, all raw `@Query` statements that never pass through anything you could wrap.

Trigger shape (transactions; every synced table gets the same three, `account_balances` gets four):

```sql
CREATE TRIGGER IF NOT EXISTS cloud_out_transactions_ai
AFTER INSERT ON transactions
WHEN (SELECT outbox_enabled FROM cloud_state WHERE id = 1) = 1
 AND (SELECT suppress_owner  FROM cloud_state WHERE id = 1) = ''
BEGIN
  INSERT INTO cloud_outbox (coll, doc_key, op, state, enqueued_at, attempts)
  VALUES ('transactions', NEW.transaction_hash, 'UPSERT', 'PENDING',
          CAST(strftime('%s','now') AS INTEGER) * 1000, 0);
END;

CREATE TRIGGER IF NOT EXISTS cloud_out_transactions_au
AFTER UPDATE ON transactions
WHEN (SELECT outbox_enabled FROM cloud_state WHERE id = 1) = 1
 AND (SELECT suppress_owner  FROM cloud_state WHERE id = 1) = ''
BEGIN
  INSERT INTO cloud_outbox (coll, doc_key, op, state, enqueued_at, attempts)
  VALUES ('transactions', OLD.transaction_hash, 'UPSERT', 'PENDING',
          CAST(strftime('%s','now') AS INTEGER) * 1000, 0);
END;

CREATE TRIGGER IF NOT EXISTS cloud_out_transactions_ad
AFTER DELETE ON transactions
WHEN (SELECT outbox_enabled FROM cloud_state WHERE id = 1) = 1
 AND (SELECT suppress_owner  FROM cloud_state WHERE id = 1) = ''
 AND OLD.transaction_hash NOT LIKE 'DELETED\_%' ESCAPE '\'
BEGIN
  INSERT INTO cloud_outbox (coll, doc_key, op, state, enqueued_at, attempts)
  VALUES ('transactions', OLD.transaction_hash, 'DELETE', 'PENDING',
          CAST(strftime('%s','now') AS INTEGER) * 1000, 0);
END;
```

The suppression sentinel is read **inside the trigger's `WHEN` clause, in the same transaction as the write** — not as a Kotlin-side flag a concurrent write could race. All trigger SQL lives in one `CloudTriggers.STATEMENTS: List<String>` so the callback and any future migration apply identical text.

**The outbox stores keys, not payloads.** Consequences, all good: triggers stay trivial SQL; a row edited fifty times between drains collapses to one Firestore write; a retry after a crash re-reads Room and ships *current* state rather than a museum of stale payloads; and one "rename this account" that blanket-UPDATEs 3,000 balance rows becomes 3,000 cheap local inserts and **one** network write.

### Draining

1. **Startup sweep:** `UPDATE cloud_outbox SET state = 'PENDING' WHERE state = 'SENDING'`. A process kill during a commit would otherwise strand those rows silently until the next reconcile.
2. `UPDATE ... SET state = 'SUPERSEDED' WHERE state = 'PENDING' AND id < (max id for the same coll + doc_key)`. Kept, not deleted, so a row's change history stays readable.
3. Take up to 450 survivors ordered by `id`, **re-sorted parent-first**: `profiles → categories → tags → groups → loans → budgets → budgetCategories → accounts → transactions → balances → splits → tagLinks → rules → merchantMappings → merchantAliases → subscriptions → recurring → dues`.
4. Read each row from Room by its key. Missing + `UPSERT` ⇒ downgrade to `DELETE`. Present + `DELETE` ⇒ upgrade to `UPSERT`. The queue can never disagree with the database.
5. Build the document; `wseq = outbox.id` so a retry is byte-identical; `ts = FieldValue.serverTimestamp()`.
6. Mark `SENDING` with a `batch_id`, commit one `WriteBatch` (limit 500; use 450), **await the Task**. With `MemoryCacheSettings` the Task does not complete until the server acks — that is the exactly-once seam. Then `SENT`.
7. On failure: back to `PENDING`, `attempts + 1`, `last_error` recorded, `BackoffPolicy.EXPONENTIAL, 30s`. After 8 attempts ⇒ `FAILED`, surfaced in the UI, never silently dropped. WorkManager has no max-retries setting, so the budget is hand-counted exactly as `EmailSyncWorker` already does.

Scheduling follows `ScheduledFolderBackupScheduler` (`app/src/main/java/com/nrknithin/fintrack/backup/folder/`), injected into `FinTrackApplication` beside the existing one at `:40-41` and armed at `:95-99`: a debounced one-shot after a local change (unique work, 30 s), a 15-minute `PeriodicWorkRequest` with `NetworkType.CONNECTED` + `setRequiresBatteryNotLow(true)`, and a foreground listener on `meta/pulse` while the app is open. **Workers cannot use the static-companion convention** — `PeriodicWorkRequestBuilder<CloudSyncWorker>()` is a reified type reference and only compiles where the class exists, so the worker lives in `src/cloud` and is scheduled through a `src/main` `CloudSyncScheduler` interface. Runs log as `sync_runs` rows with `channel = SyncChannel.CLOUD`, which **already exists** at `SourceEnums.kt:109` — no enum addition, no schema concern.

### Pulling

`records.orderBy("ts").orderBy(FieldPath.documentId()).whereGreaterThanOrEqualTo("ts", cursorTs).startAfter(cursorTs, cursorDocId).limit(500)`, paged to exhaustion. Ordering on `(ts, __name__)` is not optional: every document in one `WriteBatch` shares the commit timestamp, so ties are guaranteed rather than rare, and a cursor on `ts` alone would skip documents. Apply each page in one Room transaction with a suppression window open, record each doc in `cloud_applied`, advance the cursor only after the page fully applies. Apply is idempotent on `(docId, wb, wseq)`, so a `>=` re-read after a crash is harmless.

Orphan children (a `budgetCategories` whose budget has not arrived) are deferred and retried on the next page, never dropped.

### Conflict rule

**Whole-document last-writer-wins, ordered by Firestore's server timestamp — and the clock is the last resort, not the first.**

| local `dirty` | remote `ts` newer than applied | action |
|---|---|---|
| no | no | nothing (the overwhelming majority) |
| no | yes | **apply remote**, record in `cloud_applied` |
| yes | no | **push local** |
| yes | yes | **true conflict** → arbitrate |

Rows 2 and 3 consult no clock at all. Arbitration compares `ts`; later wins; ties break on the lexicographically larger `wb`, which is arbitrary but **deterministic and identical on both phones** — they must reach the same verdict independently. The loser's row is overwritten whole and a `cloud_conflicts` row is written with both device ids and the differing field names.

**`outbox`'s per-field `fts` merge is deliberately removed.** It arbitrated same-field conflicts by comparing `fts` values across devices, and `fts` held `cloud_outbox.id` — a per-device autoincrement. Comparing phone A's 8814 against phone B's 4471 decides by lifetime write count, not by recency, forever. It was also, by its own author's admission, the most intricate 150 lines in the system. Whole-document LWW loses one of two simultaneous edits either way; the honest version says so and logs it.

Pushes are batched `set()`, not `runTransaction` per document — a per-document transaction bills a read with every write and serialises a round trip per row, which turns a 5,500-row resync into hours of billed work. A document the applier has flagged as contended (local dirty **and** remote newer) is escalated to a single `runTransaction` compare-and-set on `ts`; nothing else is.

**Clock skew is structurally eliminated.** `ts` is `FieldValue.serverTimestamp()` and the rule `ts == request.time` makes a client-supplied timestamp a *rejected write*. Device time appears only in `cloud_outbox.enqueued_at` and `cloud_conflicts.at`, both debug aids. The zone-less `LocalDateTime` columns are payload data copied verbatim; nothing in the sync path reads one as a clock. Two phones across a DST boundary is therefore not a case to handle.

**Echoes** are suppressed twice: the pull applies inside a suppression window so the triggers queue nothing, and a document whose `wb` is this device and whose `wseq` is `<=` the last pushed `wseq` for that key is skipped entirely.

### Deletes and tombstones

**Nothing issues a Firestore `delete()` except tombstone GC, and the rule forbids deleting a live document.**

The schema hard-deletes almost everywhere — only `transactions.is_deleted` and `unrecognized_sms.is_deleted` are soft. `AFTER DELETE` triggers capture all of it, **including the four `ForeignKey.CASCADE` children** (`TransactionSplitEntity.kt:20`, both FKs in `TransactionTagCrossRef` at `TagEntity.kt:56,62`, `BudgetCategoryEntity.kt:19`, `RuleApplicationEntity`), because Room 2.8.4 runs `PRAGMA recursive_triggers = 1` on the write connection — verified at `InvalidationTracker.kt:215`. SQLite only fires triggers for FK cascade actions when that pragma is on, so this is load-bearing, and the `onOpen` callback re-asserts it in case a Room bump changes it.

A `DELETE` outbox row writes `{ c, k, d: true, ts, wb, wseq, sv }` with **no `v`**. A hard `delete()` would be silently broken: the catch-up query is `ts >= cursor`, and a document that no longer exists matches no query, so a phone offline for a week would never learn about the deletion and would push the row back. A live `addSnapshotListener` does report removals, but only for documents inside its current result set and only while listening — you cannot build correctness on the foreground path.

Apply resolves the local row by key and runs the table's **own** delete path inside the suppression window. A tombstone for a row that does not exist locally is a no-op that still records `cloud_applied`.

GC: the PRIMARY device monthly deletes tombstones older than 90 days and writes `meta/state.tombstoneFloor`. Any phone whose cursor predates the floor refuses the incremental path and runs a full reconcile.

### Force resync — the thing that must not destroy the remote

`OptimizedSmsReaderWorker.kt:325-326` calls `transactionRepository.deleteUncuratedTransactions()` then `accountBalanceRepository.deleteRebuildableBalances()`. The first is `DELETE FROM transactions WHERE loan_id IS NULL AND group_id IS NULL` (`TransactionDao.kt:156-157`). **The app's definition of "curated" is only loan-linked or grouped**, so a hand-recategorised transaction, a manually added cash entry and a split parent are all "uncurated". With sync live and no guard, one long-press on the Sync FAB would emit thousands of tombstones and wipe the other phone.

Four guards, all required, in this order:

1. **Narrow the predicate.** This is the real fix, and it is a pre-existing bug independent of sync:
   ```sql
   DELETE FROM transactions
   WHERE loan_id IS NULL AND group_id IS NULL
     AND excluded_from_analytics = 0
     AND receipt_path IS NULL
     AND budget_category IS NULL
     AND budget_impact_type IS NULL
     AND profile_id IS NULL
     AND id NOT IN (SELECT transaction_id FROM transaction_splits)
     AND id NOT IN (SELECT transaction_id FROM transaction_tag_cross_ref)
   ```
   Every column named is verified on `TransactionEntity.kt`. **Limitation, stated rather than hidden:** a bare `category` or `merchant_name` edit is written in place with no "user touched this" flag anywhere in the entity, so this predicate cannot preserve it. `description` is **VERIFY** — check whether the parser ever populates it before adding it to the clause. Those cases are what guard 4 exists for.
2. **Role gate.** The destructive branch runs only when `cloud_state.role = 'PRIMARY'`. On the REPLICA every transaction arrived by sync and none is loan-linked, so an unguarded rescan there would delete the entire ledger.
3. **Generation-token suppression.** The rescan opens the window by writing a random `suppress_owner` token and a `suppress_until` of `now + 60 min`, and closes it by clearing `suppress_owner` in a `finally`. The wall clock is only a safety valve so a process death cannot disable capture forever — it is never the primary bound, which is what makes this robust against a scan of ~8,900 SMS running longer than anyone guessed.
4. **Forced full reconcile immediately after**, which repairs whatever the re-parse stripped: the rebuilt row's digest mismatches the remote `h`, the remote document has the higher `ts`, so the authored columns come back from the cloud.

Because `transactions` keys on `transaction_hash` and `balances` keys on `(bank, account, timestamp)`, the delete-and-rebuild round trip produces *byte-identical* documents. The reconciler finds no digest mismatch and writes nothing. **A force resync costs approximately zero Firestore writes.**

`BackupImporter` with `REPLACE_ALL` gets the identical treatment — suppression plus forced reconcile — otherwise a restore is a 5,500-write surprise, and a restore of an *old* backup would read on the other phone as "delete everything newer".

### The whole-dataset backstop

`CloudReconciler` runs monthly, on demand from Settings, after every force resync or backup restore, and unconditionally when the cursor predates `tombstoneFloor`. It reads every `records` document, and diffs three ways:

- **local row present, remote absent** → enqueue UPSERT
- **remote present, local absent and the local `cloud_applied` row is clean** → apply (or delete, for a tombstone). A **dirty** local row is never touched — it is an unpushed local creation, not a stale survivor. That one condition is what makes this pass safe to run blind.
- **both present, digests differ** → higher `ts` wins, conflict logged

This is the mechanism that heals a dropped trigger, a table added to one list and not the other, a blown suppression window, or an outright bug in the incremental path. It is also the answer when the owner says "phone B is missing something": one button, deterministic, and it prints what it changed.

**Honest cost note:** the Android Firestore SDK has **no `select()` projection** — there is no ids-only read, so this pass bills a full document read per document and pulls the payload. With `sms_body` excluded that is ~5,500 reads and ~1.5 MB, comfortably inside the daily free read allowance, but it is why the cadence is monthly rather than weekly.

### The onboarding fix

Three changes, and all three are required — the first two alone do not work.

1. `AuthRepository.signIn`'s `Success` branch attaches Firebase and pulls `meta/profile` **before returning**, bounded and never fatal:
   ```kotlin
   val remote = when (val a = cloudSession.attach(result.identity.idToken)) {
       is CloudAttach.Attached -> withTimeoutOrNull(5_000) { cloudProfile.pull(a.uid) }
       else -> null
   }
   userPreferencesRepository.applyGoogleIdentity(
       displayName = result.identity.displayName,
       accountKey  = result.identity.accountKey,
       remoteProfile = remote           // null on timeout, failure, or fdroid
   )
   ```
2. `applyGoogleIdentity` stays **one atomic `dataStore.edit { }`**. Inside it the existing "a different previous accountKey resets onboarding" branch (verified `UserPreferencesRepository.kt:927-931`) runs first, then the remote merge. **`hasCompletedOnboarding` is a one-way ratchet: false→true only.** A remote document claiming `false` can never un-onboard a phone that has already finished — a sync feature that can *cause* the reported bug would be worse than no sync.
3. **`SignInViewModel` must read the value directly on Success.** Today it sets `hasCompletedOnboarding` from a collected flow in `init` (verified `SignInViewModel.kt:57-62`) and the `Success` branch only sets `phase = Completed` (`:70-71`). `SignInScreen` then fires `onSignedIn(uiState.hasCompletedOnboarding)` from a `LaunchedEffect(uiState.phase)` at `SignInScreen.kt:52-54`, and `FinTrackNavHost.kt:84-97` routes on that value. An atomic DataStore write does not guarantee the ViewModel's collector has re-emitted by then. Fix:
   ```kotlin
   is AuthResult.Success -> _uiState.update {
       it.copy(phase = SignInPhase.Completed,
               hasCompletedOnboarding =
                   userPreferencesRepository.userPreferences.first().hasCompletedOnboarding)
   }
   ```
   which requires holding the repository as a constructor property rather than a plain parameter.

`FinTrackNavHost.kt:49` pins the start destination in an **unkeyed** `remember { startDestination }`, deliberately (`FinTrackApp.kt:78-91` explains why), and `FinTrackApp.kt:61-63` returns early only while `isLoading`. Nothing about the nav host changes. **Put that reason in a comment at both ends**: anyone who later moves the cloud read to a `LaunchedEffect`, a worker, or an app-start listener re-opens the exact bug — and it will look like it works on a fast connection.

**One honest note about the reported symptom.** `forgetGoogleIdentity()` clears only `AUTH_SIGNED_IN` and `AUTH_DISPLAY_NAME` (verified `UserPreferencesRepository.kt:941-946`); it deliberately keeps `HAS_COMPLETED_ONBOARDING`. `applyGoogleIdentity` resets onboarding only when a *previous, different* accountKey existed. So on one install, sign out → sign in with the same account should already land on Home. Live causes for what was observed: app data cleared or a reinstall; `accountKey` came back empty (`hashAccountId` returns `""` for a null id, and an empty key is never written); or the whole `auth_account_key` mechanism is **uncommitted working-tree change** — `git status` shows `UserPreferencesRepository.kt` and `AuthRepository.kt` modified — so the build that produced the symptom may not match the code. Reproduce once on an identifiable build. The underlying need — profile state surviving a device change — is real either way, and item 1 above is a one-day fix that does not depend on any of the Room machinery.

---

## Initial upload

### Volume

Reasoned from ~8,900 scanned SMS on an Indian inbox with the email channel not yet run. **Measure before quoting these** — the Database Inspector in Android Studio needs no Gradle run:

```sql
SELECT 'transactions', COUNT(*) FROM transactions WHERE is_deleted = 0
UNION ALL SELECT 'balances', COUNT(*) FROM account_balances
UNION ALL SELECT 'accounts',  COUNT(DISTINCT bank_name || account_last4) FROM account_balances
UNION ALL SELECT 'sms_bytes', SUM(LENGTH(sms_body)) FROM transactions;
```

| collection | docs | bytes/doc |
|---|---|---|
| `transactions` (no `sms_body`) | 1,500–3,500 | ~350 |
| `balances` | 1,000–3,000 | ~250 |
| `categories`, `cards`, `subscriptions`, `accounts` | ~50–100 | ~200 |
| everything else | < 300 | ~200 |
| **total** | **≈ 2,800–6,900; budget 5,500** | **≈ 1.5 MB** |

### Mechanics

**Not a special code path.** The "turn on sync" action seeds the outbox with one `UPSERT` row per syncable row and then flips `outbox_enabled = 1`:

```sql
INSERT INTO cloud_outbox (coll, doc_key, op, state, enqueued_at)
SELECT 'transactions', transaction_hash, 'UPSERT', 'PENDING',
       CAST(strftime('%s','now') AS INTEGER) * 1000
FROM transactions WHERE is_deleted = 0;
-- one peer statement per synced table
```

Everything after that is `CloudSyncWorker` doing what it always does. One code path means one thing to debug, and resume after a kill is free — rows already `SENT` are simply not re-drained. 5,500 documents at 450 per batch is **~13 commits**.

### Cost

`asia-south1` is a regional Firestore location. **VERIFY current prices on the console** — published figures for document writes in regional locations sit around **$0.09–0.11 per 100,000** (the three source designs quoted $0.09, $0.108 and $0.18; the $0.18 figure is the multi-region rate). Reads ~$0.06/100k, storage ~$0.18/GiB/month. The daily free allowance is 20,000 writes / 50,000 reads / 20,000 deletes / 1 GiB stored.

- 5,500 writes finishing inside one UTC day: **₹0**, inside the free allowance.
- If it fell entirely outside the allowance: ~$0.005 ≈ **₹0.45**.
- Storage ~1.5 MB of document data; index storage stays small because of the `v` exemption below.
- Phone B's first pull: ~5,500 reads, once. Steady state afterwards: tens of writes and low hundreds of reads per day.

**The free allowance is per project and is shared with the previous app already running in it.** Quota accounting is not in isolation, so "inside the free tier" depends on what that app consumes.

**What actually costs money is a bug, not the data.** Spec 08 §8.3's entire safety argument was "on Spark there is no billing account, so a bug cannot produce a bill". Blaze removes that. Three replacements, all mandatory before the first write:

1. A **Cloud Billing budget** with alerts at 50/90/100% of a small cap (₹500/month is plenty; any alert means something is wrong).
2. A **client-side write governor**: `cloud_state.writes_today` / `writes_day` count committed document writes per UTC day. Past 20,000 the worker halts, marks its rows `PENDING`, posts a low-importance notification ("Cloud sync paused — unusually high activity"), and does not resume until the next day or a manual override. 20,000 is ~3.6× a full re-upload, so it cannot trip in normal use but catches an echo loop within a cent or two. It is client-side, so a client bug can defeat it — the billing budget is the only guard that cannot.
3. The **same-transaction self-write guard**: apply clears `dirty` and writes `cloud_applied` inside the same Room transaction as the entity write, so an applied remote row cannot re-dirty itself.

### What the owner sees

**Phone 1.** Settings gains a **Cloud sync** row, off. Tapping it shows a one-screen explanation naming what leaves the phone. "Turn on sync" → confirm → a foreground worker with a progress notification, **"FinTrack — Uploading your data · 2,140 of 5,480"**. Unmetered network and battery-not-low by default, with a "Use mobile data" override. Fully resumable: progress *is* the outbox, so a kill, a reboot or a lost network continues where it stopped. Expect **roughly 1–3 minutes** on Wi-Fi. The rest of the app stays usable throughout.

**Phone 2.** Install, sign in with the same account. The profile document is fetched *inside* the sign-in call, so it lands on **Home, not onboarding**, with the right name — that alone is the thing that was reported. A "Restoring from cloud · 3,400 of 5,480" notification runs behind it and the list fills in.

**Both, afterwards.** The Settings row reads "Sync on · Last synced 4 minutes ago · 5,480 items · 2 devices", with pending count, failed count and the last error text, conflicts in the last 7 days, writes used today, and buttons for **Sync now**, **Retry failed**, **Reconcile**, **Copy diagnostics**, **Turn off and delete cloud copy**.

**Surfacing rejected writes is not optional.** A `PERMISSION_DENIED` from a wrong pinned UID, a changed Google account or a rules typo fails silently in a background worker while the app looks perfectly healthy. A one-way sync that looks like a working sync is worse than no sync. Treat a growing pending count as an alert state.

---

## Copy changes

Both false claims are inline literals in `SignInScreen.kt` — no `strings.xml` involved.

### 1. `SignInScreen.kt:99-103`

Currently:
```kotlin
// Honest copy: this is a privacy-first on-device app suddenly asking for a
// Google account, and the screen has to say why in one sentence.
Text(
    text = "Sign in once with Google to name your profile. Everything after " +
        "that stays on this device — and works offline.",
```
Replace with:
```kotlin
// Honest copy: this app reads your bank SMS, and signing in also sets up a
// cloud copy of what it finds. The screen says so before the Google sheet
// appears, not after. "Your own Firebase project" is literal — the data goes
// to a project the owner controls, not to any FinTrack service.
Text(
    text = "Sign in with Google to set up your profile and keep both your phones " +
        "in step. Your transactions, accounts and settings are copied to your own " +
        "private cloud project; the app still works offline.",
```

### 2. `SignInScreen.kt:156-162`

Currently:
```kotlin
// Privacy note. It says "keeps", not "reads", on purpose: ...
Text(
    text = "Google gives FinTrack your name, email and profile picture. " +
        "Only the name is kept, on this device — nothing is sent anywhere.",
```
Replace with:
```kotlin
// Privacy note. Two claims, kept separate on purpose: what Google hands over
// (only the name is kept), and where the money data goes (your own Firestore,
// in readable form). The last sentence is the one claim this design ENFORCES
// rather than intends — sms_body is excluded from every document and
// unrecognized_sms and bank_notifications do not sync at all. Google's own
// sheet appears seconds later; a first screen it disproves costs exactly the
// trust these lines exist to build.
Text(
    text = "Google gives FinTrack your name, email and profile picture — only the " +
        "name is kept. Your transactions, accounts and settings are stored in your " +
        "own Firebase project, in readable form. The text of your bank messages " +
        "never leaves this device. You can turn cloud sync off in Settings.",
```

That third sentence is the strongest remaining privacy statement the app can make, and it is checkable: `CloudPayloadPolicy.INCLUDE_SMS_BODY = false`, `unrecognized_sms` and `bank_notifications` are absent from the collection list. **Do not weaken it by flipping that constant without changing this string in the same commit** — an `E-tests` assertion pins the pair together.

Note what is deliberately **not** claimed: "readable only by this Google account". That is false — Google is a reader, and the previous app's administrators can reach this project's console.

### 3. `SignInViewModel.kt`, `messageFor(AuthFailure.OFFLINE)`

`"You're offline. FinTrack needs one connection to sign you in — after that it works offline."` →
```kotlin
AuthFailure.OFFLINE ->
    "You're offline. FinTrack needs a connection to sign you in and to sync. " +
        "Between syncs the app works offline."
```

### 4. New copy — Settings → Cloud sync

> **Cloud sync** — On. Your transactions, accounts, categories, budgets and settings are stored in your own Firebase project, in the Mumbai region, and shared between the phones signed in with this Google account.
> Stored as ordinary readable rows, not encrypted by FinTrack — anyone with access to that Google account or that Firebase project can read them.
> The text of your bank messages, and your AI chat history, are never uploaded. Receipt photos stay on the phone that took them, so the other phone will show a transaction with no receipt.
> ☐ Sync on mobile data
> Last synced 4 minutes ago · 5,480 items · 2 devices · [Sync now] · [Reconcile] · [Turn off and delete cloud copy]

"Turn off and delete cloud copy" must actually delete `users/{uid}/**`, or the sentence is a lie too.

### 5. Documents that carry the now-false claim

- **`PRIVACY.md:11`** — *"The only data that ever leaves your device goes through the explicit outbound requests listed under Internet Permission below"*, followed by a closed list. Add a **Cloud sync (optional, off by default)** section naming the 19 collections and the profile document, that they are stored unencrypted and queryable in the owner's own Firebase project in `asia-south1`, that Google is a processor with access, and how to turn it off and delete it. **`PRIVACY.md:42`** — *"Only extracted transaction fields are stored, not full messages"* — is **already false today** (`TransactionEntity.kt:41-42` stores `sms_body`) and must be corrected regardless of this feature. `PRIVACY.md:41` ("SMS parsing happens entirely on-device") stays true; separate it from storage. `PRIVACY.md:67` lists Firebase under "❌ Analytics services" — still true as written (we add no analytics SDK), but it will read wrong beside a Firestore section, so qualify it.
- **`README.md:29`** — *"No accounts, no sign-ups, no effort."* is now false twice over in this fork. Add a short "This fork" note; upstream's marketing copy should not be left standing.
- **`docs/START-HERE.md:86-88`** — the "What still leaves the device, deliberately" paragraph gains the Firestore replica. **`docs/START-HERE.md:149`** — *"Debug builds cannot reach Firebase"* is stale: `google-services.json` registers **both** `com.nrknithin.fintrack` and `com.nrknithin.fintrack.debug`, verified.
- **`CLAUDE.md:4`** — *"extracts transactions from bank SMS **entirely on-device**"* is the highest-leverage line, because every future agent reads it as a hard constraint. Make it: "extracts transactions from bank SMS entirely on-device; the extracted rows then sync to the owner's own Firebase project."
- **`docs/NEEDS-YOU.md:35-42`** — records the project as `byt-play`. **That is stale.** `fin.firebase.projectId` in `local.properties` matches `project_info.project_id` in `fin-secrets/google-services.json` exactly (verified programmatically without printing either value). Correct the doc; do not "fix" `local.properties`.
- **`docs/TODO.md`**, **`docs/BUILD-STATUS.md`** (predates the whole sign-in feature), **`docs/database-migrations.md`** (opening line still says "version 58 … 23 entities"; it is 63/30 today and 64/34 after this).

---

## File ownership map

**Tasks:** A-build (Gradle & flavor plumbing) · B-firebase (Firebase SDK surface, DI, rules project) · C-sync (Room, change tracking, sync engine, auth seam) · D-ui (screens, user-facing text, docs) · E-tests (every test, and the lint that guards the new package).

| file | create/modify | task | what it must contain |
|---|---|---|---|
| `gradle/libs.versions.toml` | modify | **A** | `firebaseBom` version + `firebase-bom`, `firebase-auth`, `firebase-firestore` module entries (no `-ktx`; merged since BoM 32.5). |
| `app/build.gradle.kts` | modify | **A** | `"standardImplementation"` + `"personalImplementation"` for the three Firebase artifacts (never plain `implementation` — it would put Play services on fdroid); `FIREBASE_APP_ID/API_KEY/PROJECT_ID/SENDER_ID` `buildConfigField`s in **`defaultConfig`** beside `GOOGLE_WEB_CLIENT_ID:53` (the comment at `:48-52` explains why a flavor field would break `standard`); a `fin.firebase.appId.debug` override inside `buildTypes.debug`; convert the coroutines pin at `:402-403` from a requested version to `constraints { … version { strictly("1.11.0") } }`. **No `com.google.gms.google-services` plugin.** |
| `local.properties` | modify | **A** | Add `fin.firebase.appId.debug` (owner supplies the value; see console steps). Git-ignored. |
| `app/src/cloud/java/com/nrknithin/fintrack/cloud/CloudInitializer.kt` | create | **B** | Manual `FirebaseApp.initializeApp(context, FirebaseOptions.Builder()...)` guarded by `FirebaseApp.getApps(context).none { it.name == DEFAULT_APP_NAME }`; `MemoryCacheSettings` assigned before any other Firestore call; `isConfigured` false when any BuildConfig field is blank. |
| `app/src/cloud/java/com/nrknithin/fintrack/cloud/FirebaseCloudSession.kt` | create | **B** | `GoogleAuthProvider.getCredential(idToken, null)` → `signInWithCredential` → UID. Never throws; `CloudAttach.Failed(e::class.simpleName)` only — never `e.message`, which can echo an email. |
| `app/src/cloud/java/com/nrknithin/fintrack/cloud/FirestoreRemoteStore.kt` | create | **B** | The only file naming `FirebaseFirestore`. Implements `RemoteStore`: batched `set()`, the escalated `runTransaction`, the `(ts, __name__)` cursor query, the full reconcile read, the `meta/*` and `devices/*` writes. |
| `app/src/cloud/java/com/nrknithin/fintrack/cloud/TaskAwait.kt` | create | **B** | ~15-line `suspendCancellableCoroutine` `Task<T>.await()`. **Do not add `kotlinx-coroutines-play-services`.** |
| `app/src/cloud/java/com/nrknithin/fintrack/di/CloudModule.kt` | create | **B** | `@Binds` for `CloudSession`, `RemoteStore`, `CloudSyncScheduler`. Same FQN as its nocloud twin — the idiom `di/AuthModule.kt` already uses. |
| `app/src/nocloud/java/com/nrknithin/fintrack/cloud/NoCloudSession.kt` | create | **B** | `isAvailable = false`, `attach` returns `Unsupported`. Zero Google imports. |
| `app/src/nocloud/java/com/nrknithin/fintrack/cloud/NoRemoteStore.kt` | create | **B** | Every method a no-op. Zero Google imports. |
| `app/src/nocloud/java/com/nrknithin/fintrack/di/CloudModule.kt` | create | **B** | The mirror bindings. Omit it and `fdroidDebug` fails at KSP with `[Dagger/MissingBinding]` in CI's `:app:assembleDebug` (`.github/workflows/test.yml:78`). |
| `firebase/firestore.rules` | create | **B** | The rules text above, with the owner's existing rules pasted in at the marked point. |
| `firebase/firebase.json` | create | **B** | Emulator config for the rules tests. |
| `firebase/package.json` | create | **B** | `@firebase/rules-unit-testing` dev dependency and a `test` script. Node, not Gradle — outside `./init.sh`. |
| `firebase/README.md` | create | **B** | How to run the emulator tests, and the warning that the live rules serve another app. |
| `app/src/main/java/com/nrknithin/fintrack/cloud/CloudSession.kt` | create | **C** | `CloudAttach` sealed interface + `CloudSession`. Zero Google imports — this compiles on fdroid. |
| `app/src/main/java/com/nrknithin/fintrack/cloud/RemoteStore.kt` | create | **C** | The narrow store interface the runner talks to. Zero Google types — this is the testing seam. |
| `app/src/main/java/com/nrknithin/fintrack/cloud/CloudRecord.kt` | create | **C** | The envelope data class (`c, k, d, ts, wb, wseq, sv, h, v`) and `docIdFor(c, k)` = `sha1hex(c + "\u001f" + k)`. |
| `app/src/main/java/com/nrknithin/fintrack/cloud/CloudProfile.kt` | create | **C** | The `meta/profile` model, every field defaulted. |
| `app/src/main/java/com/nrknithin/fintrack/cloud/CloudRecordCodec.kt` | create | **C** | `backupJson.encodeToJsonElement(entity)` → `Map<String, Any?>`; money as `toPlainString()`; FK→key translation both ways; the `h` digest; `CloudPayloadPolicy.INCLUDE_SMS_BODY = false`; `receipt_path` dropped outbound and never applied inbound. |
| `app/src/main/java/com/nrknithin/fintrack/cloud/CloudCollections.kt` | create | **C** | The 19 `c` tags, their canonical-key functions, the parent-first apply order, and the table→collection registry the trigger-coverage test asserts against. |
| `app/src/main/java/com/nrknithin/fintrack/cloud/CloudSyncRunner.kt` | create | **C** | The whole push/pull algorithm as a pure, Android-free class behind `RemoteStore` — factored exactly as `EmailSyncRunner` is out of `EmailSyncWorker`, so it is testable under `./init.sh app`. Includes the startup `SENDING → PENDING` sweep, coalescing, the conflict decision table, the write governor. |
| `app/src/main/java/com/nrknithin/fintrack/cloud/CloudReconciler.kt` | create | **C** | The full key-set + digest pass, the clean-only delete rule, `tombstoneFloor` handling, duplicate-`sync_uid` detection, and REPLICA identity adoption by natural key. |
| `app/src/main/java/com/nrknithin/fintrack/cloud/CloudSyncScheduler.kt` | create | **C** | `src/main` interface (`schedule`, `cancel`, `runOnce`), so `src/main` never names a worker class. |
| `app/src/cloud/java/com/nrknithin/fintrack/cloud/CloudSyncWorker.kt` | create | **C** | `@HiltWorker`. Thin — delegates to `CloudSyncRunner`. Foreground with progress for the bootstrap. Hand-counted `runAttemptCount` budget, as `EmailSyncWorker` does. |
| `app/src/cloud/java/com/nrknithin/fintrack/cloud/FirebaseCloudSyncScheduler.kt` | create | **C** | The only place `PeriodicWorkRequestBuilder<CloudSyncWorker>()` appears (reified — will not compile from `src/main`). |
| `app/src/nocloud/java/com/nrknithin/fintrack/cloud/NoCloudSyncScheduler.kt` | create | **C** | No-op. |
| `app/src/main/java/com/nrknithin/fintrack/data/database/entity/CloudSyncEntities.kt` | create | **C** | The four entities above. Not `@Serializable`. Not in `DatabaseSnapshot` — with a comment saying why. |
| `app/src/main/java/com/nrknithin/fintrack/data/database/dao/CloudSyncDao.kt` | create | **C** | Outbox drain/coalesce queries, `cloud_applied` upsert, conflict insert, `cloud_state` read/write, suppression open/close. |
| `app/src/main/java/com/nrknithin/fintrack/data/database/CloudTriggers.kt` | create | **C** | `STATEMENTS: List<String>` (~57 `CREATE TRIGGER IF NOT EXISTS`) and `CloudSyncCallback`. |
| `app/src/main/java/com/nrknithin/fintrack/data/database/FinTrackDatabase.kt` | modify | **C** | `SCHEMA_VERSION = 64`; four entities added to `@Database`; `AutoMigration(63→64, spec = Migration63To64::class)`; `Migration63To64` class; `cloudSyncDao()`; **add `.addCallback(DatabaseCallback())` and `.addCallback(CloudSyncCallback())` to `getInstance()` at `:254-266`, which today registers no callback at all.** |
| `app/src/main/java/com/nrknithin/fintrack/di/DatabaseModule.kt` | modify | **C** | `.addCallback(CloudSyncCallback())` beside the existing `DatabaseCallback()` at `:72`; `@Provides` for `CloudSyncDao`. |
| `app/schemas/com.nrknithin.fintrack.data.database.FinTrackDatabase/64.json` | create | **C** | Generated by the first Gradle run. **Must be committed.** |
| `.../entity/SubscriptionEntity.kt` | modify | **C** | `sync_uid` column, non-unique index. |
| `.../entity/BudgetEntity.kt` | modify | **C** | same |
| `.../entity/TransactionGroupEntity.kt` | modify | **C** | same |
| `.../entity/LoanEntity.kt` | modify | **C** | same |
| `.../entity/TransactionSplitEntity.kt` | modify | **C** | same |
| `.../dao/TransactionDao.kt` | modify | **C** | Narrow `deleteUncuratedTransactions` per guard 1. Update the KDoc at `:145-156`, which currently defines "curated" as loan-or-group only. |
| `app/src/main/java/com/nrknithin/fintrack/worker/OptimizedSmsReaderWorker.kt` | modify | **C** | Role gate + generation-token suppression around `:320-330` in a `try/finally`, then a forced reconcile. |
| `app/src/main/java/com/nrknithin/fintrack/data/backup/BackupModels.kt` | modify | **C** | `ProfilePreferences` block on `PreferencesSnapshot`; bump `CURRENT_FORMAT` to `v1.3`. |
| `app/src/main/java/com/nrknithin/fintrack/data/backup/BackupExporter.kt` | modify | **C** | Populate the new profile block. |
| `app/src/main/java/com/nrknithin/fintrack/data/backup/BackupImporter.kt` | modify | **C** | Restore the profile block; wrap `REPLACE_ALL` in the suppression window + forced reconcile. |
| `app/src/main/java/com/nrknithin/fintrack/auth/AuthGateway.kt` | modify | **C** | Rewrite the `GoogleIdentity` KDoc at `:6-14`: "if Firestore sync is ever added" is now past tense, and "used in-process and discarded" is false once the Firebase SDK holds a refresh token. Keep the `toString()` redaction and its comment. |
| `app/src/main/java/com/nrknithin/fintrack/auth/AuthRepository.kt` | modify | **C** | The attach + bounded profile pull in the `Success` branch, feeding one atomic `applyGoogleIdentity`. |
| `app/src/main/java/com/nrknithin/fintrack/data/preferences/UserPreferencesRepository.kt` | modify | **C** | `applyGoogleIdentity(..., remoteProfile)` with the false→true ratchet; `cloud_device_id`, `cloud_sync_enabled`, `cloud_sync_on_metered` keys; rewrite the `AUTH_SIGNED_IN` KDoc at `:156-161` ("Nothing ABOUT the account is stored") — the Firebase UID is now persisted. Keep the `AUTH_ACCOUNT_KEY` KDoc; it stays accurate. |
| `app/src/main/java/com/nrknithin/fintrack/FinTrackApplication.kt` | modify | **C** | Inject `CloudSyncScheduler` beside `scheduledFolderBackupScheduler` at `:40-41`; arm it conditionally in the same `applicationScope.launch` shape as `:94-99`. |
| `app/src/main/java/com/nrknithin/fintrack/ui/screens/auth/SignInScreen.kt` | modify | **D** | Copy changes 1 and 2, comments included. |
| `app/src/main/java/com/nrknithin/fintrack/ui/screens/auth/SignInViewModel.kt` | modify | **D** | Copy change 3, **and** the `.first()` read of `hasCompletedOnboarding` in the `Success` branch. |
| `app/src/main/java/com/nrknithin/fintrack/ui/screens/settings/CloudSyncScreen.kt` | create | **D** | The status panel and the kill switch. Must surface rejected writes as an alert state, not as "Last synced: never". |
| `app/src/main/java/com/nrknithin/fintrack/ui/screens/settings/CloudSyncViewModel.kt` | create | **D** | State from `cloud_state`, `cloud_outbox`, `cloud_conflicts`, `devices/*`. |
| `app/src/main/java/com/nrknithin/fintrack/ui/screens/settings/SettingsScreen.kt` | modify | **D** | The Cloud sync entry row. |
| `app/src/main/java/com/nrknithin/fintrack/navigation/FinTrackDestinations.kt` | modify | **D** | The `CloudSync` route object. |
| `app/src/main/java/com/nrknithin/fintrack/navigation/FinTrackNavHost.kt` | modify | **D** | The `composable<CloudSync>` block, and a comment at `:49` explaining why the profile pull must complete inside `signIn`. |
| `PRIVACY.md` | modify | **D** | Cloud sync section; fix the already-false line 42. |
| `README.md` | modify | **D** | "This fork" note against line 29. |
| `CLAUDE.md` | modify | **D** | Qualify line 4. |
| `docs/START-HERE.md` | modify | **D** | Firestore in the leaves-the-device paragraph; drop the stale line 149. |
| `docs/FORK-CHANGES.md` | modify | **D** | The deviations below. |
| `docs/BUILD-STATUS.md` | modify | **D** | Re-date; record this as M9-shaped with per-record encryption not implemented. |
| `docs/database-migrations.md` | modify | **D** | Fix "version 58 … 23 entities"; add the triggers-vanish-on-table-rebuild note. |
| `docs/NEEDS-YOU.md` | modify | **D** | Correct the stale project id; tick the Firebase-values item. |
| `docs/TODO.md` | modify | **D** | Tick the "both become untrue the moment sync ships" item. |
| `parser-core/src/test/kotlin/com/nrknithin/fintrack/parser/core/PiiLintTest.kt` | modify | **E** | **Drop `pending = true` from `ForkPath("app/src/main/java/com/nrknithin/fintrack/cloud")` at `:422`** — the test asserts a pending path holding sources is a failure, so `./init.sh parser` breaks the moment C-sync creates that package. Add `ForkPath("app/src/test/java/com/nrknithin/fintrack/cloud")`. Leave `ForkPath("cloud", pending = true)` at `:431` alone: the rules project lives at `firebase/` and `filesUnder` counts `.kt` files only. |
| `app/src/test/java/com/nrknithin/fintrack/data/backup/BackupSchemaGuardTest.kt` | modify | **E** | Add `ProfilePreferences` to `backupModelDescriptors`. Do **not** add the four cloud entities — they are not backup-serialized. |
| `app/src/test/java/com/nrknithin/fintrack/cloud/FakeRemoteStore.kt` | create | **E** | In-memory `RemoteStore` with injectable failures and a controllable server clock. |
| `app/src/test/java/com/nrknithin/fintrack/cloud/CloudRecordCodecTest.kt` | create | **E** | Round-trip every synced entity; assert money is a string and never a double; assert no `Long` FK appears in any payload; assert `sms_body` and `receipt_path` are absent; **assert the `SignInScreen` string and `INCLUDE_SMS_BODY` stay consistent.** |
| `app/src/test/java/com/nrknithin/fintrack/cloud/CloudSyncRunnerTest.kt` | create | **E** | Coalescing; parent-first ordering; UPSERT↔DELETE downgrade/upgrade; the four-row conflict table; echo suppression; `SENDING` sweep; write governor. |
| `app/src/test/java/com/nrknithin/fintrack/cloud/CloudReconcilerTest.kt` | create | **E** | Clean-and-absent deletes, dirty-and-absent survives, digest mismatch resolution, `tombstoneFloor` forcing a full pass, REPLICA adoption. |
| `app/src/test/java/com/nrknithin/fintrack/cloud/CloudTriggerCoverageTest.kt` | create | **E** | Assert `CloudTriggers.STATEMENTS` covers exactly the tables in `CloudCollections`' registry — so adding a table to one list and not the other fails the build. |
| `app/src/test/java/com/nrknithin/fintrack/auth/AuthRepositoryTest.kt` | modify | **E** | Attach success/failure/timeout all still return `Success`; the onboarding ratchet never goes true→false. |
| `app/src/test/java/com/nrknithin/fintrack/ui/screens/auth/SignInViewModelTest.kt` | modify | **E** | `hasCompletedOnboarding` is correct on the very first `Completed` emission. |
| `app/src/androidTest/java/com/nrknithin/fintrack/data/database/Migration63To64Test.kt` | create | **E** | `MigrationTestHelper` 63→64: seed rows, migrate, assert every `sync_uid` non-empty and distinct, `cloud_state` row seeded, `outbox_enabled = 0`. |
| `app/src/androidTest/java/com/nrknithin/fintrack/data/database/CloudTriggerInstrumentedTest.kt` | create | **E** | With `outbox_enabled = 1`: insert/update/delete one row per synced table; **delete a budget and assert `cloud_outbox` gained a DELETE row per `budget_categories` child** (this is the test that pins the `recursive_triggers` dependency); soft-delete a transaction and assert the outbox key is the *original* hash; then force-resync and assert zero outbox rows were produced. |
| `firebase/test/rules.test.js` | create | **E** | Five cases: owner allowed; another authenticated uid denied on the owner's tree **and on its own**; unauthenticated denied; a client-supplied past `ts` denied; deleting a record with `d == false` denied. |

**Audit:** I walked the table top to bottom and checked every path string against the others. 74 rows, 74 distinct paths, each appearing exactly once and carrying exactly one task letter. Files that do not exist yet are included and owned. Three pairs deliberately share a *filename* but not a path — `di/CloudModule.kt` (cloud vs nocloud, both B) and `cloud/CloudSyncScheduler`-family files (main interface C, cloud impl C, nocloud impl C) — and those are distinct files in distinct source sets, which is the established duplicated-FQN idiom `di/AuthModule.kt` already uses here. Two cross-task dependencies are intentional and must be sequenced: **C-sync creates `app/src/main/.../cloud/`, which breaks `./init.sh parser` until E-tests flips the `pending` flag in `PiiLintTest.kt`** — land them together. And **C-sync's `64.json` cannot be produced until A-build's Gradle changes compile.**

---

## What only the owner can do

1. **Read `fin-secrets/google-services.json` and copy the *debug* app's `mobilesdk_app_id` into `local.properties` as `fin.firebase.appId.debug`.** I verified there are two registered Android apps — `com.nrknithin.fintrack` and `com.nrknithin.fintrack.debug` — with distinct `mobilesdk_app_id` values, and that `fin.firebase.appId` currently holds the *release* one. `FirebaseApp` does not validate `appId` against the running package, so a debug build with the release appId *appears* to work while the API key's `X-Android-Package` restriction is enforced server-side and you get opaque `PERMISSION_DENIED`s. I also verified `fin.firebase.projectId`, `apiKey`, `senderId` and `fin.google.webClientId` all match the JSON exactly — **nothing else in `local.properties` needs changing, and `docs/NEEDS-YOU.md:35-36` is the stale record, not the file.**
2. **Before touching the rules: open Firestore → Rules, and copy the entire current text somewhere safe.** The console's rules history is the only undo. **These rules serve a previous, unrelated app in the same project — replacing them wholesale (which is what spec 08 §8.7 tells you to do) will break it.**
3. **Audit the existing rules for a broad `match /{document=**}` with any loose `allow`.** Firestore rules are OR-ed: such a block already exposes `/users/**`, and nothing you paste in afterwards can narrow it. If one exists, it must be scoped before the FinTrack block means anything.
4. **Deploy the merged rules once with the pin relaxed** — replace the `uid == 'PASTE_OWNER_FIREBASE_UID'` clause with `true` temporarily.
5. **Sign in on one phone**, then read the UID from Console → Authentication → Users, paste it into `finOwner`, and **redeploy with the pin in place. Do this before the bulk upload, not after.**
6. **Set a Cloud Billing budget** on the project: ₹500/month with alerts at 50 / 90 / 100%. Billing is Blaze, so a runaway loop bills rather than throttles, and no budget exists today. This is the only guard a client-side bug cannot defeat.
7. **Add one single-field index exemption**: Firestore → Indexes → Single field → Add exemption, collection ID `records`, field path `v`, **collection scope**, all index modes off. Nothing queries inside `v`; automatic indexing of a ~30-leaf nested map across 5,500 documents is pure storage cost and walks toward the per-document index-entry limit. **Leave automatic indexes on `c`, `ts`, `d`, `wb`.**
8. **Create no composite index.** The only query the app runs is `orderBy("ts").orderBy(FieldPath.documentId())` with a `>=` bound, which a single-field index serves. If Firestore ever demands one it prints the exact console link in the error — use that rather than guessing.
9. **Turn on 2FA for the Google account**, if it is not already. That account is now the single credential protecting the entire financial history, readable from the console with no app and no phone.
10. **Decide which phone is PRIMARY** and enable sync there first. Do not enable it on the second phone until the first reports the upload finished — random `sync_uid`s do not converge, and two simultaneous bootstraps produce permanent duplicate budgets and loans.

---

## Deviations from spec

For `docs/FORK-CHANGES.md`:

- **D1 — Firestore holds plaintext, queryable financial data.** Contradicts spec 00 §0.2 invariant 1 (Firestore should hold ciphertext blobs, never queryable data), spec 08 §8.6 ("never: plaintext of anything"), spec 01 §1.6 C5's closed list of what may leave the device, and spec 04 §4.4. Even M9, the milestone this work actually is, specified per-record AES-GCM; that is **not** implemented. Owner's explicit decision after being shown the trade-off. Blast radius narrowed by excluding `sms_body`, `chat_messages`, `unrecognized_sms`, `bank_notifications` and `email_messages`, but the shape of the exposure is unchanged: whoever controls the Google account can read every transaction, merchant, balance and account alias from the console.
- **D2 — Firestore becomes authoritative for onboarding and profile state.** Contradicts spec 00 §0.2 invariant 1. Bounded by a 5-second timeout that degrades to local state, and by a false→true-only ratchet on `hasCompletedOnboarding`.
- **D3 — cloud code lives in `app/src/cloud/java`, not `app/src/personal/`.** Contradicts spec 08 §8.5.1 and spec 05 §5.2. Reason: `standard` is `isDefault = true` (verified `app/build.gradle.kts:94`), is what Android Studio and `./init.sh app` build, and shares `applicationId` with `personal` so the two cannot coexist on a phone. Sync scoped to `personal` is sync the owner would never run. `HAS_CLOUD` (personal-only, zero Kotlin readers) gates nothing. **`fdroid` gains no Google or Firebase class** — that invariant is untouched, and AC-55-04 stands.
- **D4 — spec 08 §8.3's cost guarantee is void.** It rested on "on Spark there is no billing account, so a bug cannot produce a bill". Billing is Blaze. Replaced by a Cloud Billing budget with alerts and a client-side 20,000-writes/day governor. AC-08-05's "under 5% of daily quotas" was computed for nightly snapshots and no longer describes this system.
- **D5 — AC-08-02 ("no traffic to `*.googleapis.com` when cloud is off") is retained, not withdrawn.** `cloud_state.outbox_enabled` defaults to 0, the triggers are inert, no worker is scheduled, and no Firestore call is made until the owner turns sync on. Spec 01 §1.6 C8's "off by default" therefore still holds. The kill switch must detach listeners and delete `users/{uid}/**`, or it is decorative.
- **D6 — `MemoryCacheSettings` retained, per spec 08 §8.5.1.** Not a concession: the Room outbox is the durable queue and Room is the cache, so a Firestore LevelDB store beside it would be a second, opaque, unmigrated plaintext copy of the same data that `BackupExporter` and `BackupSchemaGuardTest` know nothing about. The cost of keeping it is that a mutation issued while offline lives only in memory — which is exactly why the outbox row stays `PENDING` until the Task acks.
- **D7 — AC-08-04's three Emulator Suite rules tests are implemented as five, in a Node project at `firebase/`, outside the `./init.sh` Gradle gate.** They are the only way to verify the pinned-UID clause without creating a second Google account.
- **D8 — `docs/BUILD-STATUS.md`'s M9 data model is replaced, not implemented.** No `sync_uid` on every table, no `cloud_outbox` of the shape §8.11 sketched, no per-record encryption.
- **D9 — two pre-existing bugs fixed in passing**, both independent of sync: `FinTrackDatabase.getInstance()` registers no `RoomDatabase.Callback` and therefore skips default-category seeding on the `BroadcastReceiver` creation path; and `deleteUncuratedTransactions` treats a hand-recategorised or split transaction as uncurated.

---

## Verification plan

### What `./init.sh` can prove

`./init.sh app` runs `:app:compileStandardDebugKotlin` then `:app:testStandardDebugUnitTest` (verified `init.sh:207-208`). `./init.sh parser` runs `:parser-core:test`, which includes `PiiLintTest`.

- The whole `standard` variant compiles with Firebase on the classpath, and — the real check — that the `strictly("1.11.0")` coroutines constraint held. `android.dependency.useConstraints=true` (`gradle.properties:31`) plus `android.generateSyncIssueWhenLibraryConstraintsAreEnabled=false` (`:37`) means version skew is **not** announced at Gradle sync; it surfaces at assemble time. A Firebase BoM that moves coroutines above 1.11.0 re-opens the litertlm `SendChannel.close$default` break (#599), which would break on-device AI, not sync — maximally confusing to diagnose.
- `CloudSyncRunner`, `CloudReconciler` and `CloudRecordCodec` in full, against `FakeRemoteStore`, because they are pure and Android-free. This is where coalescing, the conflict table, echo suppression, the `SENDING` sweep, ordering, FK translation and the money-as-string rule all get proved.
- `CloudTriggerCoverageTest` proves the trigger list and the collection registry agree.
- `BackupSchemaGuardTest` proves CLAUDE.md #3: no new required field, and the four cloud entities stay out of the backup.
- `PiiLintTest` proves no PII shape entered the new packages, **and** that `app/src/main/.../cloud` is no longer marked pending. Note the new files must contain no ten-digit numbers, no email-shaped strings and no sixteen-digit runs — including in example document IDs and comments.

**The gate has a hole worth naming.** `app/src/test` is shared across flavors, but both `init.sh:208` and `test.yml:124` run only `:app:testStandardDebugUnitTest`. A test that names a `src/cloud` type stays green locally while making `testFdroidDebugUnitTest` uncompilable, and nothing tells you. **Keep every test on the `src/main` interfaces**, exactly as `FakeAuthGateway` already does for `AuthGateway`. CI's `:app:assembleDebug` (`test.yml:78`) builds all three debug variants, so a missing `nocloud` binding fails there — but only there.

### What needs a device (instrumented, `app/src/androidTest`)

The source set exists but holds only `ExampleInstrumentedTest.kt`, so this is new ground. `MigrationTestHelper` needs instrumentation assets, which `docs/START-HERE.md` already records as un-runnable headlessly.

- `Migration63To64Test` — 63→64 with seeded rows.
- `CloudTriggerInstrumentedTest` — **the cascade case is the one that must not be skipped.** Delete a budget with categories, a transaction with splits and tag links, and assert `cloud_outbox` gained a `DELETE` row per child. That test is what pins the `recursive_triggers` dependency; a Room bump that changes `InvalidationTracker.configureConnection` would otherwise silently stop cascade deletions from propagating, and the only symptom would be zombie rows on the other phone.
- The same test must also prove: a soft delete keys the outbox on the *original* hash; a hard delete of an already-soft-deleted row produces nothing; and a full force resync with the guards in place produces **zero** outbox rows.

### What needs two real phones

Nothing below can be proved in a test harness, and all of it must be done before the feature is called done:

1. **Both phones on the same build, same parser version.** Divergent parser versions hash the same SMS differently → two documents for one real transaction, with no automatic reconciliation. This is worth stating on the Cloud sync screen and worth remembering before updating one phone weeks ahead of the other.
2. **Bootstrap ordering** — PRIMARY uploads, REPLICA pulls and adopts before its outbox drains. Verify no duplicate budgets, loans, groups or subscriptions appear afterwards.
3. **Force resync on the PRIMARY with sync live** — the single highest-risk interaction. Watch the write counter: it should stay near zero, and phone B's ledger must be unchanged.
4. **Force resync attempted on the REPLICA** — must be refused by the role gate.
5. **Backup restore with `REPLACE_ALL`** — must produce no tombstone storm.
6. **The echo test** — edit a transaction in a loop on both phones for a few minutes and watch the write counter flatten. Two emulators work for this one.
7. **Offline-then-reconnect** on one phone for a day, with edits on both.
8. **Airplane mode during the bootstrap**, then resume — the counter must continue, not restart.
9. **`PERMISSION_DENIED` handling** — deploy the rules with a deliberately wrong pinned UID for five minutes and confirm the Cloud sync screen says so rather than sitting at "Last synced: never". With writes applied locally first, this is the failure mode that looks exactly like success.
10. **Light and dark themes** on the new Cloud sync screen, on both phones' different screen sizes.