package com.nrknithin.fintrack.data.database

import androidx.room.Room
import org.robolectric.RuntimeEnvironment

/**
 * A real [FinTrackDatabase], in memory, for JVM unit tests (spec 06 §6.1, row
 * "Repository / DAO tests": *Robolectric + in-memory Room*).
 *
 * **Why this exists.** Until it did, no SQL in this repository had ever executed. Room's
 * annotation processor checks that a `@Query` parses and that its columns and parameters line
 * up with the Kotlin signature — which is a real check, and is exactly why every DAO here
 * compiles — but it says nothing about what a query *means*. The whole v63 email schema was
 * covered only by hand-written fakes that re-state each query's filter in Kotlin, and a fake
 * cannot disagree with itself: `EmailMessageDao.trimBodiesBefore` could truncate, empty or do
 * nothing at all and every test in the tree would still pass. A fake is a statement of what
 * the author believed the SQL did. This is the database saying what it does.
 *
 * **Why in memory, and per test.** `Room.inMemoryDatabaseBuilder` creates the schema with the
 * same generated `createAllTables` statements the app ships, so tables, column types, defaults
 * and unique indices are the production ones, and each suite gets a database with no rows it
 * did not write. Nothing is shared between tests, so nothing can order them.
 *
 * **What it deliberately does not test.** Opening a database this way runs `createAllTables`,
 * never a migration — so a green suite here is not evidence that `AutoMigration(62, 63)` or any
 * later migration upgrades an existing device. That gap is recorded in docs/FORK-CHANGES.md and
 * belongs to a `MigrationTestHelper` suite, not to this helper.
 *
 * Usage — a suite annotates itself `@RunWith(RobolectricTestRunner::class)`, opens in `@Before`
 * and closes in `@After`:
 *
 * ```
 * private lateinit var db: FinTrackDatabase
 *
 * @Before fun open()  { db = RoomTestDb.open() }
 * @After  fun close() { db.close() }
 * ```
 */
object RoomTestDb {

    /**
     * Opens a fresh, empty database. The caller owns it and must [FinTrackDatabase.close] it;
     * an in-memory Room database is destroyed when its last connection closes, so a leaked one
     * is a leaked thread rather than a leaked file.
     *
     * `allowMainThreadQueries` is on because these tests drive the DAOs from `runBlocking` on
     * the test thread, which Robolectric reports as the main thread. It relaxes a guard that
     * exists to protect a UI frame; there is no UI here.
     */
    fun open(): FinTrackDatabase =
        Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            FinTrackDatabase::class.java
        )
            .allowMainThreadQueries()
            .build()
}
