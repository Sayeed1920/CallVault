/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading Android's own record of who holds a microphone.
 *
 * The healthy fixture is a real `dumpsys audio` taken on 2026-09-01 from a device that does NOT show
 * the stuck-microphone symptom — a control, so "reports nothing" is known to mean nothing is wrong
 * rather than the parser having failed to match.
 */
class RecordActivityReportTest {

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(name)) { "missing fixture $name" }
            .bufferedReader().readText()

    @Test
    fun `a device with no symptom has nothing left open`() {
        // Act
        val open = RecordActivityReport.openCaptures(fixture("dumpsys_audio_healthy.txt"))

        // Assert — every rec start on that phone had a matching rec stop.
        assertEquals(emptyList<RecordActivityReport.OpenCapture>(), open)
    }

    @Test
    fun `the parser really does match that fixture, so the empty result means something`() {
        // A parser that matched nothing would also return "nothing open". Prove it matched.
        val text = fixture("dumpsys_audio_healthy.txt")
        assertTrue("fixture should contain rec events", text.contains("rec start riid:"))
        // Stops are what emptied it, so removing them must produce open captures.
        val withoutStops = text.lineSequence().filterNot { " rec stop " in it }.joinToString("\n")
        assertTrue(RecordActivityReport.openCaptures(withoutStops).isNotEmpty())
    }

    @Test
    fun `a capture with no stop is reported, and named as ours when it is shell`() {
        // Arrange — one shell capture never stopped, one app capture opened and closed.
        val dump = """
            08-31 20:13:49:297 rec start riid:1 uid:10394 session:1 src:MIC not silenced pack:com.whatsapp
            08-31 20:13:55:296 rec stop riid:1 uid:10394 session:1 src:MIC not silenced pack:com.whatsapp
            09-01 10:18:56:322 rec start riid:2 uid:2000 session:2 src:MIC not silenced pack:com.android.shell
        """.trimIndent()

        // Act
        val open = RecordActivityReport.openCaptures(dump)

        // Assert
        assertEquals(1, open.size)
        assertEquals("2", open.single().riid)
        assertEquals("MIC", open.single().source)
        assertTrue(open.single().isShell)
        assertTrue(RecordActivityReport.render(dump).contains("green microphone dot"))
    }

    @Test
    fun `a recording that began before the log window still counts as open`() {
        // Arrange — the events log is a ring, so a long call shows only its later 'update'.
        val dump = "09-01 09:42:17:035 rec update riid:9 uid:2000 session:9 src:VOICE_CALL not silenced pack:com.android.shell"

        // Act + Assert — treating an unpaired update as closed would hide the longest-lived capture.
        assertEquals(1, RecordActivityReport.openCaptures(dump).size)
    }

    @Test
    fun `a stuck capture belonging to another app is not blamed on CallVault`() {
        // Arrange
        val dump = "09-01 10:18:56:322 rec start riid:3 uid:10394 session:3 src:MIC not silenced pack:com.whatsapp"

        // Act
        val rendered = RecordActivityReport.render(dump)

        // Assert
        assertTrue(rendered, "Another app is holding the microphone" in rendered)
    }

    @Test
    fun `an unreadable dump says so instead of claiming everything is fine`() {
        assertTrue(RecordActivityReport.render(null).contains("could not read"))
        assertTrue(RecordActivityReport.render("").contains("could not read"))
    }
}
