/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.baba.callvault.R
import kotlinx.coroutines.delay

/** How long the "done" state stays up before closing itself. */
private const val AUTO_CLOSE_SECONDS = 5

/**
 * The height the progress body always occupies.
 *
 * Fixed so that finishing does not resize the card. The two states hold different things — a
 * spinner and a count, then a tick and a result — and letting the dialog shrink under the finger
 * as it completes is most of what made the first version feel abrupt.
 */
private val PROGRESS_BODY_HEIGHT = 96.dp

/** What the merge/un-merge card is doing, or null while the user is still choosing. */
data class MergeProgressState(
    val isUnMerge: Boolean,
    val current: Int,
    val total: Int,
    val finished: Boolean = false,
)

/**
 * The working half of the merge and un-merge cards.
 *
 * Deliberately a body rather than a dialog of its own: opening a second dialog meant one card
 * closing and another opening over it, which flashed. The card stays; only what is inside it changes.
 */
@Composable
fun MergeProgressBody(state: MergeProgressState) {
    // Animated so the bar slides between calls instead of stepping, and glides to full on the last
    // one rather than snapping there.
    val target = when {
        state.finished -> 1f
        state.total <= 0 -> 0f
        else -> (state.current - 1).coerceAtLeast(0) / state.total.toFloat()
    }
    val progress by animateFloatAsState(targetValue = target, label = "mergeProgress")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(PROGRESS_BODY_HEIGHT),
        verticalArrangement = Arrangement.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Spinner and tick share one box, so the text beside them never shifts sideways.
            Box(modifier = Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                if (state.finished) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                } else {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            }
            Spacer(Modifier.size(12.dp))
            Text(
                text = if (state.finished) {
                    stringResource(
                        if (state.isUnMerge) R.string.unmerge_progress_done
                        else R.string.merge_progress_done
                    )
                } else {
                    stringResource(R.string.merge_progress_step, state.current, state.total)
                },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (state.finished) FontWeight.Medium else FontWeight.Normal
            )
        }
        Spacer(Modifier.size(16.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * The card's footer while working: the countdown and a way out, or nothing at all.
 *
 * The row keeps its height in both states, so the card does not grow a button row when it finishes.
 */
@Composable
fun MergeProgressFooter(state: MergeProgressState, onClose: () -> Unit) {
    var remaining by remember { mutableIntStateOf(AUTO_CLOSE_SECONDS) }

    // Closes itself, but never silently: the countdown is visible, and Close is there for anyone who
    // would rather not wait it out.
    LaunchedEffect(state.finished) {
        if (!state.finished) return@LaunchedEffect
        remaining = AUTO_CLOSE_SECONDS
        while (remaining > 0) {
            delay(1000)
            remaining -= 1
        }
        onClose()
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        if (state.finished) {
            Text(
                text = stringResource(R.string.progress_closing_in, remaining),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.size(8.dp))
            TextButton(onClick = onClose) { Text(stringResource(R.string.general_close)) }
        } else {
            // Same height as a TextButton, so the footer does not appear from nowhere at the end.
            Spacer(Modifier.height(40.dp))
        }
    }
}
