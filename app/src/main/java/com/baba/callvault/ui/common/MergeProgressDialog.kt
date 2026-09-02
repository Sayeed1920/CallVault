/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.baba.callvault.R
import kotlinx.coroutines.delay

/** What [MergeProgressDialog] is showing. */
data class MergeProgressState(
    val isUnMerge: Boolean,
    val current: Int,
    val total: Int,
    val finished: Boolean = false,
)

/** How long the "done" state stays up before closing itself. */
private const val AUTO_CLOSE_SECONDS = 5

/**
 * What a merge or un-merge is doing right now.
 *
 * Merging four long calls is not instant, and the first version showed only a spinner on the
 * confirm button of a dialog still listing the calls — so it read as though nothing had happened
 * and the list was still waiting to be edited. This replaces that list once the work starts:
 * one short dialog that says which call it is on, then that it finished.
 */
@Composable
fun MergeProgressDialog(
    /** True while un-merging, so the same dialog serves both directions. */
    isUnMerge: Boolean,
    /** 1-based index of the call being handled. */
    current: Int,
    total: Int,
    /** True once the work has finished successfully. */
    finished: Boolean,
    onClose: () -> Unit,
) {
    var remaining by remember { mutableIntStateOf(AUTO_CLOSE_SECONDS) }

    // Closes itself, but never silently: the countdown is on screen, and Close is there for anyone
    // who does not want to wait out five seconds of a dialog they have already read.
    LaunchedEffect(finished) {
        if (!finished) return@LaunchedEffect
        remaining = AUTO_CLOSE_SECONDS
        while (remaining > 0) {
            delay(1000)
            remaining -= 1
        }
        onClose()
    }

    AlertDialog(
        // Not dismissible mid-flight: tapping away from a merge in progress would hide work that is
        // still deleting the user's original calls.
        onDismissRequest = { if (finished) onClose() },
        title = {
            Text(
                stringResource(
                    if (isUnMerge) R.string.unmerge_progress_title else R.string.merge_progress_title
                )
            )
        },
        text = {
            Column {
                if (finished) {
                    Text(
                        text = stringResource(
                            if (isUnMerge) R.string.unmerge_progress_done else R.string.merge_progress_done
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(
                        text = stringResource(R.string.progress_closing_in, remaining),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = stringResource(R.string.merge_progress_step, current, total),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(Modifier.size(12.dp))
                    LinearProgressIndicator(
                        // Determinate: a call-by-call bar says how much is left, where an
                        // indeterminate one says only that something is happening.
                        progress = { if (total <= 0) 0f else (current - 1).coerceAtLeast(0) / total.toFloat() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            if (finished) {
                TextButton(onClick = onClose) { Text(stringResource(R.string.general_close)) }
            }
        }
    )
}
