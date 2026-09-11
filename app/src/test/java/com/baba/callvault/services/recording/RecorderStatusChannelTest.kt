/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import android.app.NotificationManager
import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The "Ready to record calls" notification's channel, which Settings now lets the user switch off
 * without stopping the recorder (mirror176, issue #31).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35]) // Robolectric 4.14 max; project targets SDK 36
class RecorderStatusChannelTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /**
     * The row may be opened before the recorder has ever run. Android's page for a channel that does
     * not exist yet is useless, so asking for the page must create the channel first.
     */
    @Test
    fun ensuring_the_channel_creates_it_quiet() {
        RecorderStatusChannel.ensure(context)

        val channel = context.getSystemService(NotificationManager::class.java)
            .getNotificationChannel(RecorderStatusChannel.ID)
        assertNotNull("the channel was not created", channel)
        assertEquals(NotificationManager.IMPORTANCE_MIN, channel.importance)
    }

    @Test
    fun the_settings_intent_opens_this_channel_and_nothing_wider() {
        val intent = RecorderStatusChannel.settingsIntent(context)

        assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, intent.action)
        assertEquals(context.packageName, intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        assertEquals(RecorderStatusChannel.ID, intent.getStringExtra(Settings.EXTRA_CHANNEL_ID))
    }

    /** The id is what existing installs already have; changing it would orphan their choice. */
    @Test
    fun the_channel_id_is_the_one_already_on_users_phones() {
        assertEquals("recorder_keepalive", RecorderStatusChannel.ID)
    }
}
