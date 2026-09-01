/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.recordings.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The v1 → v2 migration that adds the remembered duration.
 *
 * Worth a test rather than trusting one ALTER TABLE: Room validates the on-disk schema against the
 * entity every time the database is opened, so a migration that produces the wrong shape does not
 * fail quietly — it throws on open, for every user, on launch. Building the real v1 file and opening
 * it through Room is what proves the migration and the entity agree.
 *
 * The catalog is also the only record of which recordings have a Drive copy, so the test pins that
 * the migration keeps the existing rows rather than letting the destructive fallback wipe them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35]) // Robolectric 4.14 max; project targets SDK 36
class RecordingDatabaseMigrationTest {

    private val name = "migration-test.db"
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(name)
    }

    /** Creates the database exactly as schema version 1 had it, with one fully populated row. */
    private fun seedVersion1() {
        val db = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null)
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `recordings` (" +
                "`displayName` TEXT NOT NULL, " +
                "`localUri` TEXT, " +
                "`driveUri` TEXT, " +
                "`localSizeBytes` INTEGER, " +
                "`driveSizeBytes` INTEGER, " +
                "`lastModified` INTEGER NOT NULL, " +
                "PRIMARY KEY(`displayName`))"
        )
        db.execSQL(
            "INSERT INTO recordings VALUES " +
                "('20260827_154104.611+0300_voip-WhatsApp_Feroza.ogg', " +
                "'content://local/a', 'content://drive/a', 4096, 4096, 1700000000000)"
        )
        db.version = 1
        db.close()
    }

    @Test
    fun migrating_keeps_the_row_and_leaves_the_duration_unread() = runBlocking {
        // Arrange
        seedVersion1()

        // Act — Room validates the migrated schema against the entity as it opens.
        val db = Room.databaseBuilder(context, RecordingDatabase::class.java, name)
            .addMigrations(RecordingDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()
        val rows = db.recordingDao().getAll()

        // Assert
        assertEquals(1, rows.size)
        val entry = rows.single()
        assertEquals("20260827_154104.611+0300_voip-WhatsApp_Feroza.ogg", entry.displayName)
        assertEquals("content://drive/a", entry.driveUri)
        assertEquals(4096L, entry.localSizeBytes)
        assertEquals(1_700_000_000_000L, entry.lastModified)
        // Never read yet, so it gets read exactly once — not never, and not every load.
        assertNull(entry.durationSeconds)
        db.close()
    }

    @Test
    fun the_migrated_database_can_remember_a_duration() = runBlocking {
        // Arrange
        seedVersion1()
        val db = Room.databaseBuilder(context, RecordingDatabase::class.java, name)
            .addMigrations(RecordingDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()

        // Act
        db.recordingDao().setDuration("20260827_154104.611+0300_voip-WhatsApp_Feroza.ogg", 312L)

        // Assert
        assertEquals(312L, db.recordingDao().getAll().single().durationSeconds)
        db.close()
    }
}
