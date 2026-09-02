/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.data.recordings.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Data-access for the merge manifest ([MergePartEntry]).
 *
 * Every read is ordered by position, never by insertion: the order a merged call plays in is the
 * order the user ticked the parts, which is deliberately neither chronological nor the order the
 * rows happened to be written.
 */
@Dao
interface MergePartDao {

    /** The parts of [mergedName], in playback order. Empty for a recording that was never merged. */
    @Query("SELECT * FROM merge_parts WHERE mergedName = :mergedName ORDER BY position ASC")
    suspend fun partsOf(mergedName: String): List<MergePartEntry>

    /** The merged recording [partName] lives inside, or null if it is not part of one. */
    @Query("SELECT mergedName FROM merge_parts WHERE partName = :partName LIMIT 1")
    suspend fun mergedNameContaining(partName: String): String?

    /** Every merged recording that currently has a manifest. */
    @Query("SELECT DISTINCT mergedName FROM merge_parts")
    suspend fun allMergedNames(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(parts: List<MergePartEntry>)

    /** Drops the manifest — the second half of an un-merge, once the parts are back on disk. */
    @Query("DELETE FROM merge_parts WHERE mergedName = :mergedName")
    suspend fun deleteParts(mergedName: String)
}
