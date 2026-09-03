# Shell mic open: the bug is real, our evidence was not

**Status: 🧪 OPEN — the bug is REAL and CONFIRMED. What was disproven is only our *evidence* for it.**

**🚨 Read this before anything else. The microphone has been CONFIRMED ON by the maintainer.** This
document does not say the problem is imaginary; it says the diagnostic we built to find it reports
something unrelated, and that we therefore currently have **no working evidence at all**. Those are
different claims, and an earlier revision of this file wrongly conflated them into "false positive".

Something is holding `OP_RECORD_AUDIO`. We cannot presently see what.

Date: 2026-09-03. Supersedes the first version of this document, which concluded the opposite.

Reports: `~/Downloads/callvault_system_report (4).txt`, `callvault_debug_report (27).txt`.
Device: OnePlus **CPH2653** = **OnePlus 13 (EEA)**, `OP5D55L1` / `CPH2653EEA`, Android 16 (API 36),
i.e. **OxygenOS 16**. CallVault **2.2.1-rc3 (20205)**, STANDALONE, VoIP recording on.

> ⚠️ **Diagnostic gap, still worth fixing.** The report captures manufacturer / model / device /
> product and the API level but **not the ROM build fingerprint** — no `ro.build.display.id`, no
> OxygenOS version. "OxygenOS 16" is inferred from model + API level. Add `ro.build.display.id` and
> `ro.build.version.incremental` to the report header.

---

## The conclusion

Two separate things are true, and both matter:

1. **The bug is real.** The green dot has been confirmed on. A microphone app-op is being started and
   never finished.
2. **`RecordActivityReport` cannot see it.** It manufactures an unrelated finding for every
   `REMOTE_SUBMIX` capture that has ever run — ours and other apps' — so the three "still open" shell
   captures in the tester's report were never the leak. They were noise that looked like signal.

**The net position is worse than before, not better: we had a lead, it was bogus, and we now have
none.** Worse still, the noise is *actively destructive* — the unpairable entries accumulate in a
50-entry ring, so they evict the very `rec start` that would reveal the real capture. Our diagnostic
was not merely unhelpful; it was crowding out the evidence.

The chain, verified from AOSP source on `android16-release` by two independent passes:

1. **`REMOTE_SUBMIX` is a "system-only" audio source.** `MediaRecorder.isSystemOnlyAudioSource()` has
   the REMOTE_SUBMIX case *deliberately commented out* of the `return false` list, so it falls to
   `default: return true`.
2. **Its config therefore never enters the state table.**
   `RecordingActivityMonitor.onRecordingConfigurationChanged()` logs the event and **returns early**,
   never calling `updateSnapshot()`. The `RecordingState` for that riid keeps `mConfig == null` for
   life. The comment in AOSP is explicit: *"still want to log event, it just won't appear in
   recording configurations"*.
3. **So the client-side start/stop can never be logged.** `updateSnapshot()` gates its log line on
   `configChanged`, and `RecordingState.setActive()` returns `mConfig != null` → always `false`.
4. **And the server-side start/stop is suppressed too**, for any client with a valid riid —
   `AudioInputDescriptor::updateClientRecordingConfiguration()` returns early when
   `!client->isLowLevel() && (event == START || event == STOP)`.
5. **What survives is `rec update`** — updates are not suppressed, so they reach step 2's `enqueue`
   and appear in the ring log.

**A `REMOTE_SUBMIX` capture can therefore only ever appear as an `update` with no `stop`. Not
sometimes. Structurally, always.**

And our parser is documented to treat exactly that as a leak:

> `update` counts as an open the same way `start` does: the events log is a ring, so a recording that
> began before the window shows only its later `update`, and treating that as "not open" would hide
> exactly the long-lived capture this exists to find.

That reasoning is sound for ordinary sources and guaranteed wrong for system-only ones.

### The Android Auto control was right all along

Six unmatched `REMOTE_SUBMIX` entries from `com.google.android.projection.gearhead` in the same dump.
Android Auto is not leaking six microphones. It is the same artefact. **This was flagged as the
control that cut against the hypothesis, and it should have been weighted far more heavily than it
was.**

### 🔬 Proof on the maintainer's own OP12 — same ROM generation, no symptom

`CPH2581`, **OxygenOS `CPH2581_16.0.9.400(EX01)`, Android 16** — the same OxygenOS 16 generation as
the tester's OP13. This phone has never shown a stuck-microphone problem.

Running `RecordActivityReport`'s exact parsing logic over its `dumpsys audio`:

```
total rec events parsed: 50            <-- the ring is EXACTLY saturated
STILL OPEN: 5 capture(s), 5 of them uid 2000 (shell)
  [update] riid=3151 src=REMOTE_SUBMIX pack=com.android.shell   <-- "CallVault's privileged capture"
  [update] riid=4679 src=REMOTE_SUBMIX pack=com.android.shell   <-- "CallVault's privileged capture"
  [update] riid=4743 src=REMOTE_SUBMIX pack=com.android.shell   <-- "CallVault's privileged capture"
  [update] riid=6351 src=REMOTE_SUBMIX pack=com.android.shell   <-- "CallVault's privileged capture"
  [update] riid=6415 src=REMOTE_SUBMIX pack=com.android.shell   <-- "CallVault's privileged capture"
```

**Five phantom "still open" captures on a healthy phone — worse than the tester's three.** Every one
is an `update`, every one is `REMOTE_SUBMIX`. Meanwhile every `MIC` and `VOICE_CALL` capture in the
same dump pairs cleanly:

```
rec update riid:6359 src:MIC        → rec stop riid:6359    ✅
rec update riid:6375 src:MIC        → rec stop riid:6375    ✅
rec start  riid:6383 src:VOICE_CALL → rec stop riid:6383    ✅
rec update riid:6423 src:MIC        → rec stop riid:6423    ✅
rec update riid:4743 src:REMOTE_SUBMIX → (never)            ❌ structurally impossible
```

**And the actual indicator evidence says the mic is clean.** `dumpsys appops`, uid 2000,
`com.android.shell`:

```
RECORD_AUDIO (allow):
  Access: [cch-s] 2026-09-03 13:55:50.695 (-34m58s592ms) duration=+19s261ms
```

A **completed** access with a closed duration, not a running op. No stranded `OP_RECORD_AUDIO`, no
green dot. And it reconciles exactly: that op opened at 13:55:50.695, the matching
`rec update riid:6423 src:MIC` is at 13:55:50.721 and its `rec stop` at 13:56:09.960 — **19.2 s,
matching `duration=+19s261ms` to the millisecond.**

The ring being exactly saturated at 50 also confirms the eviction concern below is live rather than
theoretical.

**This settles what the submix entries are worth — nothing — on a device with no symptom. It does not
settle the tester's problem, which remains real and unexplained.**

### On-device corroboration (OP9 Pro, `daabf34f`, OxygenOS 14)

```
rec update riid:183 … src:VOICE_COMMUNICATION … pack:com.whatsapp
rec stop   riid:183 … src:VOICE_COMMUNICATION … pack:com.whatsapp
→ 8 events: 4 rec update, 4 rec stop. 0 unmatched. No REMOTE_SUBMIX present.
```

Two things this pins down: ordinary (non-system-only) sources **do** pair correctly on
OnePlus/OxygenOS — so the ROM is not generally broken — and the normal event kind on this OEM is
`update`, not `start`, which is consistent with the tester's lines being updates.

---

## `REMOTE_SUBMIX` cannot light the green dot either

Independent of the bookkeeping, from `frameworks/av`:

- `ServiceUtilities.cpp::getOpForSource()` maps `AUDIO_SOURCE_REMOTE_SUBMIX` to
  **`OP_RECORD_AUDIO_OUTPUT` (106)**, and `isRecordOpRequired()` returns **false** for it — so
  `OP_RECORD_AUDIO` (27) is never started at all.
- SystemUI's `AppOpsControllerImpl.OPS_MIC` watches `OP_RECORD_AUDIO`, `OP_PHONE_CALL_MICROPHONE` and
  the ambient/sandbox trigger ops. **`OP_RECORD_AUDIO_OUTPUT` is not in the list.**
- `AudioPolicyService::isVirtualSource()` lists REMOTE_SUBMIX, exempting it from the silencing/UID
  machinery, and `AudioRecordClient::isAppOpSource()` returns false for it, so no op monitor is even
  created.

**Verified on a live device:** `RECORD_AUDIO`, `RECORD_AUDIO_HOTWORD` and `RECORD_AUDIO_OUTPUT` are
three distinct appops on the OP9 Pro. The op split is real, not a source-reading artefact.

So even had the submix captures genuinely been open, they could not have produced the symptom.

---

## Unregistering the policy would not have helped

`AudioManager.unregisterAudioPolicyAsyncStatic()` — the variant we call — never calls
`invalidateCaptorsAndInjectors()`. Even the synchronous variant only calls `record.stop()` on its
captors, never `release()`, and explicitly tolerates them already being released. Nothing in policy
unregistration reaches `RecordingActivityMonitor`; only `releaseRecorder(riid)` (driven by our
`AudioRecord.release()`) or binder death removes a record state.

**`stop()` + `release()` is the correct and sufficient teardown, and we already do it.** Leaving the
policy armed leaks the `AudioMix` routing resource — worth tidying for hygiene, invisible here, and
not the dot. Note `scrcpy`, whose recipe ours derives from, never unregisters either; it gets away
with it because its `app_process` exits per session, which a long-lived daemon does not.

**The "arming is free" comment in `VoipCaptureController` stands.** It was doubted in the first
version of this doc; that doubt is withdrawn.

---

## 🚨 The leading hypothesis now — UNTESTED

`AudioPolicyService::releaseInput()` (`AudioPolicyInterfaceImpl.cpp`) clears `client->active`
**without** calling `finishRecording()`:

```cpp
if (client->active) {
    ALOGW("%s releasing active client portId %d", __FUNCTION__, portId);
    client->active = false;
    client->startTimeNs = 0;
    updateUidStates_l();
}
mAudioRecordClients.removeItem(portId);
```

`stopInput()` is the only place the op is finished. **An input released while still active leaves its
started app-op un-finished.** For a `MIC` / `VOICE_*` client that stranded op is `OP_RECORD_AUDIO` —
i.e. a green dot that never goes out.

Normally `RecordTrack::destroy()` covers this by calling `AudioSystem::stopInput()` before
`releaseInput()` when the track was active. **A process that is SIGKILLed mid-capture does not run
that path** — which connects this directly to [[post-update-daemon-race]]: daemons killing each other
mid-recording is exactly the shape that strands the op.

Note the tester's log does show sibling-clearing at 10:26:57 (`Clearing 3 other recorder process(es):
[29687, 29724, 29729] (I am 29689)`), so the precondition occurs in the field.

**This is now the leading hypothesis, and it fits everything we know:** the dot is confirmed on, the
op survives the capture, and a killed daemon is exactly what skips the clean teardown. It has not
been tested — but it is where to look next, and `MicOpReport` (added 2026-09-03) is what will show it,
because a stranded op prints `Running start at:` with no closed duration.

---

## Three defects in `RecordActivityReport` — all shipped in rc3

1. **It counts system-only sources as leaks.** `REMOTE_SUBMIX` (and `ECHO_REFERENCE`,
   `VOICE_DOWNLINK`, `FM_TUNER`) can never produce a matching `stop`. **Fix: exclude them, or report
   them in a clearly-labelled separate bucket that is not called "still open".**
2. **It asserts something false.** *"that is CallVault's privileged capture, and it is what puts the
   green microphone dot on screen"* is printed for any shell-uid entry, including sources that
   provably cannot drive the indicator. **Fix: only make the indicator claim for sources that note
   `OP_RECORD_AUDIO`.** This sentence is what sent this investigation the wrong way for a day.
3. **It discards the event kind.** The regex captures `(start|update|stop)` and `OpenCapture` then
   drops it, so the report cannot distinguish a genuine unmatched `start` from an expected orphan
   `update`. That single field was the discriminator, and we threw it away. **Fix: keep and print
   it**, along with the total event count and whether the 50-entry ring saturated.

Ring saturation matters on its own: un-stoppable submix entries **accumulate**, so ours plus Android
Auto's can evict the very `rec start` that would reveal a real MIC capture. The report currently
cannot tell "no leak" from "the evidence scrolled away".

---

## The right diagnostic from here

`dumpsys audio`'s RecordActivityMonitor is **not** a usable signal for "is a capture still open". Use
instead, while the dot is lit:

- **`dumpsys appops`** filtered to uid 2000 — a *running* `RECORD_AUDIO` op is what lights the
  indicator, and it is the direct evidence. **How to read it:** a completed access prints
  `duration=+…`; an op that is still running prints no closed duration. That single distinction is
  the whole test, and it is what this report should be parsing instead of the rec-event ring.
- **`dumpsys media.audio_policy`** — authoritative for open inputs and record clients.

---

## What is still genuinely unknown

1. **Whether the green dot is actually lit at all.** Never independently confirmed. Everything in
   these two reports is consistent with it never having been on.
2. Whether the stranded-`OP_RECORD_AUDIO` path above ever fires in practice.

## Prior-art sweep: nobody else has reported this

BCR (900+ issues), ShizuCallRecorder, Shizuku, scrcpy: **zero** reports of a stuck indicator or a
submix capture leak. BCR cannot hit it — it uses `AudioRecord(VOICE_CALL)`/`InCallService` and never
registers a dynamic policy. The one directly relevant thread is
[scrcpy #4380](https://github.com/Genymobile/scrcpy/issues/4380), the origin of our
`ROUTE_FLAG_LOOP_BACK_RENDER` + `createAudioRecordSink()` recipe; nobody there raises the indicator or
policy teardown either.
