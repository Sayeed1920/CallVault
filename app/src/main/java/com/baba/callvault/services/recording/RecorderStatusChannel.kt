/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.baba.callvault.R

/**
 * The channel behind the "Ready to record calls" notification.
 *
 * That notification exists because keeping the recorder warm is a foreground service, and Android
 * requires one to show a notification — so it cannot simply be removed without the recorder going cold
 * and calls starting late. What the user *can* do is switch the channel off in Android's own settings:
 * the service keeps running, and from Android 13 the app is listed under the active apps in quick
 * settings instead. Settings links straight to that one switch (mirror176, issue #31).
 *
 * One place for the channel, because two services post to it ([DaemonKeepAliveService] and the
 * post-boot [com.baba.callvault.services.call.CallMonitorService]) and the settings row must be able to
 * create it before either has ever run.
 */
object RecorderStatusChannel {

    /** Unchanged from before this object existed: a user's choice is stored against this id. */
    const val ID = "recorder_keepalive"

    /**
     * Creates the channel if it does not exist.
     *
     * Safe to call again: Android ignores importance and sound for a channel that already exists, so a
     * user who switched it off stays switched off.
     */
    fun ensure(context: Context) {
        val channel = NotificationChannel(
            ID,
            context.getString(R.string.notif_readiness_channel),
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** Android's settings page for this channel alone, not the app's whole notification list. */
    fun settingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, ID)
}
