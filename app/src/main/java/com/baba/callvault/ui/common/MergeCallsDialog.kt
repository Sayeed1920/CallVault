/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.baba.callvault.R
import com.baba.callvault.data.recordings.RecordingsRepository.RecordingItem

/**
 * Chooses which calls to merge into one, and in what order.
 *
 * The recording the menu was opened from is ① and cannot be moved or unticked — a merge is always
 * "this call, then these". Everything else is ticked in the order it should play, and takes the next
 * number as it goes. **The numbers are the whole safety mechanism:** the order is deliberately not
 * chronological, so the only thing stopping someone assembling a conversation backwards is being
 * able to see the order they have built before they commit to it. The summary line at the bottom
 * spells the same thing out a second way.
 *
 * @param candidates every other call with this number that is on the phone, newest first.
 * @param onConfirm  receives the ticked names in tick order — the primary is not included.
 */
@Composable
fun MergeCallsDialog(
    primary: RecordingItem,
    candidates: List<RecordingItem>,
    /** Whether the calls being merged in will be kept — [AppPreferences.isKeepOriginalsAfterMerge]. */
    keepOriginals: Boolean,
    working: Boolean,
    onConfirm: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    // A list, not a set: ticking order is the merge order, so it has to be remembered.
    var picked by remember { mutableStateOf(listOf<String>()) }
    val who = primary.contactName ?: primary.number ?: primary.displayName
    val totalSeconds = (primary.durationSeconds ?: 0L) +
        picked.sumOf { name -> candidates.firstOrNull { it.displayName == name }?.durationSeconds ?: 0L }

    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text(stringResource(R.string.merge_title, who)) },
        text = {
            Column {
                MergeRow(item = primary, badge = 1, enabled = false, checked = true, onToggle = {})
                Text(
                    text = stringResource(R.string.merge_primary_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 40.dp, bottom = 8.dp)
                )
                HorizontalDivider()
                Spacer(Modifier.size(8.dp))
                if (candidates.isEmpty()) {
                    Text(
                        text = stringResource(R.string.merge_no_candidates),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = stringResource(R.string.merge_pick_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.size(4.dp))
                    LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
                        items(candidates, key = { it.displayName }) { item ->
                            val at = picked.indexOf(item.displayName)
                            MergeRow(
                                item = item,
                                // +2 because the primary is 1 and this list starts after it.
                                badge = if (at >= 0) at + 2 else null,
                                enabled = !working,
                                checked = at >= 0,
                                onToggle = {
                                    picked = if (at >= 0) picked - item.displayName
                                    else picked + item.displayName
                                }
                            )
                        }
                    }
                }
                if (picked.isNotEmpty()) {
                    Spacer(Modifier.size(8.dp))
                    HorizontalDivider()
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = pluralStringResource(
                            R.plurals.merge_summary,
                            picked.size + 1,
                            picked.size + 1,
                            formatDuration(totalSeconds)
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    // Say what merging does to the other calls, here, at the moment of deciding.
                    // The setting that controls it lives in Storage, and someone who has never
                    // opened that screen would otherwise find out by noticing three calls missing.
                    Text(
                        text = stringResource(
                            if (keepOriginals) R.string.merge_note_will_keep
                            else R.string.merge_note_will_delete
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = picked.isNotEmpty() && !working,
                onClick = { onConfirm(picked) }
            ) {
                if (working) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.merge_working))
                } else {
                    Text(stringResource(R.string.merge_confirm))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !working) {
                Text(stringResource(R.string.general_cancel))
            }
        }
    )
}

/** One call in the merge list: its number badge when ticked, a checkbox when not. */
@Composable
private fun MergeRow(
    item: RecordingItem,
    badge: Int?,
    enabled: Boolean,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .then(if (enabled) Modifier.clickable(onClick = onToggle) else Modifier)
            .padding(vertical = 4.dp)
    ) {
        if (badge != null) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            ) {
                Text(
                    text = badge.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontWeight = FontWeight.Bold
                )
            }
        } else {
            Checkbox(checked = checked, onCheckedChange = { onToggle() }, enabled = enabled)
        }
        Spacer(Modifier.size(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.displayDate ?: item.displayName,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        Text(
            text = formatDuration(item.durationSeconds ?: 0L),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** m:ss, or h:mm:ss once a conversation runs past an hour — which merged ones often will. */
private fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/**
 * Confirms taking a merged call apart, listing exactly what comes back.
 *
 * The list is not decoration. Merging an already-merged call flattens it, so taking apart a call
 * assembled from a merge plus one more returns **three** calls rather than the two that were ticked.
 * Naming them is what stops that being a surprise.
 */
@Composable
fun UnMergeDialog(
    partLabels: List<String>,
    working: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text(stringResource(R.string.unmerge_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.unmerge_message))
                Spacer(Modifier.size(4.dp))
                partLabels.forEachIndexed { i, label ->
                    Text(
                        text = "${i + 1}.  $label",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !working) {
                Text(stringResource(if (working) R.string.unmerge_working else R.string.unmerge_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !working) {
                Text(stringResource(R.string.general_cancel))
            }
        }
    )
}
