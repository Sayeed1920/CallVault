/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.diagnostics

/**
 * Who is holding a microphone, according to Android rather than according to us.
 *
 * Parses the `RecordActivityMonitor` section of `dumpsys audio`, which logs a `rec start` / `rec stop`
 * pair per recording, each carrying a `riid`, the uid, the audio source and the package. A `start` or
 * `update` with no matching `stop` is a capture that was still open when the dump was taken.
 *
 * **Why this and not [com.baba.callvault.server.CaptureAudit].** That ledger lives inside whichever
 * process opened the capture, is only printed when something releases, and cannot see a capture held
 * by a process we no longer talk to — a leftover scrcpy server, a daemon from a previous app version,
 * or a Shizuku-hosted service. All of those present as uid 2000 / `com.android.shell`, which is what
 * the user sees behind the green microphone dot. This reads the platform's own record instead, so it
 * reports a stuck microphone even when the leak is in code that is no longer running.
 *
 * Validated against a device with no symptom on 2026-09-01: 50 events, 0 unmatched.
 */
object RecordActivityReport {

    /** Android's own uid for the shell user; every privileged capture of ours is attributed to it. */
    const val SHELL_UID = "2000"

    private val EVENT = Regex(
        """rec (start|update|stop) riid:(\d+) uid:(\d+) session:(\d+) src:(\w+)\s*(\w[\w ]*?)?\s*pack:(\S+)"""
    )

    /** One capture that was still open when the dump was taken. */
    data class OpenCapture(
        val riid: String,
        val uid: String,
        val session: String,
        val source: String,
        val pack: String,
    ) {
        /** True for the uid CallVault's privileged captures run as — the ones a user sees as "Shell". */
        val isShell: Boolean get() = uid == SHELL_UID

        override fun toString(): String =
            "riid=$riid uid=$uid session=$session src=$source pack=$pack" +
                if (isShell) "   <-- CallVault's privileged capture" else ""
    }

    /**
     * Every capture with no matching stop, oldest first.
     *
     * `update` counts as an open the same way `start` does: the events log is a ring, so a recording
     * that began before the window shows only its later `update`, and treating that as "not open"
     * would hide exactly the long-lived capture this exists to find.
     */
    fun openCaptures(dumpsysAudio: String): List<OpenCapture> {
        val open = LinkedHashMap<String, OpenCapture>()
        for (m in EVENT.findAll(dumpsysAudio)) {
            val (kind, riid, uid, session, src) = m.destructured
            val pack = m.groupValues[7]
            when (kind) {
                "stop" -> open.remove(riid)
                else -> open[riid] = OpenCapture(riid, uid, session, src, pack)
            }
        }
        return open.values.toList()
    }

    /** The section as it appears in the report: a verdict first, then the evidence. */
    fun render(dumpsysAudio: String?): String {
        if (dumpsysAudio.isNullOrBlank()) {
            return "Microphone activity: could not read `dumpsys audio` — section unavailable."
        }
        val open = openCaptures(dumpsysAudio)
        val shell = open.filter { it.isShell }
        return buildString {
            appendLine("--- Microphone activity (from Android, not from CallVault) ---")
            when {
                open.isEmpty() ->
                    appendLine("No capture was left open. Every recording that started also stopped.")
                shell.isNotEmpty() ->
                    appendLine(
                        "STILL OPEN: ${open.size} capture(s), ${shell.size} of them running as uid " +
                            "$SHELL_UID (shell) — that is CallVault's privileged capture, and it is " +
                            "what puts the green microphone dot on screen."
                    )
                else ->
                    appendLine(
                        "STILL OPEN: ${open.size} capture(s), none belonging to CallVault " +
                            "(uid $SHELL_UID). Another app is holding the microphone."
                    )
            }
            open.forEach { appendLine("    $it") }
        }
    }
}
