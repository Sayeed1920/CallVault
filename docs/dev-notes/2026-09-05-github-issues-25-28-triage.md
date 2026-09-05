# GitHub issues #25–#28 — code triage

**Date:** 2026-09-05
**Status:** 🧪 VERIFYING — every finding below was read out of the source and independently
re-checked, but **nothing here has been reproduced on a device and no fix has been written.**
No code was changed in producing this document.
**Reported against:** v2.2.0 (the current public release, 2026-08-30).
**HEAD at time of writing:** `8e3059e` on `fix/mic-diagnostics-appops`, 47 commits ahead of `v2.2.0`.
**Reporter:** `mirror176`, Samsung Galaxy S20 FE 5G (SM-G781V), Android 13. All four filed 2026-09-04.
Experimental features they had on: VoIP recording, Resilient recording, Offline recording.

A note on the reporter: these are unusually good bug reports. #26 contains a correct diagnosis
of its own root cause, and #28's "I tried Shizuku and had clean audio" is the single clue that
localises the crackling defect to one file. Worth saying so when we reply.

---

## Summary

| # | Title | Root cause | Verified? | Severity | State on HEAD |
|---|---|---|---|---|---|
| 28 | Crackling in audio | Default carrier path **silently drops a 21 ms PCM chunk** whenever the encoder is busy; the fix already exists in the sibling encoder and was never back-ported | ✅ in code | **Critical** — corrupts the core artefact | Still present |
| 26 | Transcription estimates wildly off | Believability clamp is defeated by `fallback = measured`; one absurd sample is stored permanently | ✅ in code | High — visible nonsense | Still present |
| 25 | Wrong timestamps on long pauses | whisper.cpp's VAD maps **any** pause onto a hardcoded 100 ms bridge; post-pause starts interpolate into the silence | ✅ in code | Medium | Still present |
| 27 | Rotation closes entry + scrolls to top | Activity is recreated; `playbackFor` is `remember`, not `rememberSaveable` | ✅ in code | Medium — daily annoyance | ✅ **VERIFIED on device 2026-09-05** (`fix/rotation-state-issue-27`); one follow-up fix 🧪 |

**The single most important line in this document:** issue #28 is a real audio-corruption bug in
the default recording path, it is trivially fixable, and the fix is a copy-paste from a file we
already fixed months ago.

---

## Issue #28 — crackling in audio

> "Audio is recorded with a popping sound which happens when audio is present from their side
> and/or my side. Silence doesn't have it keep happening; likely an active distortion based on
> the original waveform." … "I tried pairing through Shizuku and had clean audio."

#28 bundles **five** distinct complaints. They are separated below because only the first is the
headline bug, and three of the others are expected behaviour that we should simply explain.

### 28a — The crackling (CRITICAL, root cause found)

**Which path they were on.** Resilient recording defaults to off (`AppPreferences.kt:202`,
`HANDOFF_PERSIST_ENABLED = false`), and the daemon prefers direct capture
(`RecorderServiceImpl.kt:426`). So a standalone carrier call goes through
`DirectAudioRecorderSession` — daemon-hosted `AudioRecord` → `MediaCodec` → `MediaMuxer`.

**The defect.** `app/src/main/java/com/baba/callvault/server/DirectAudioRecorderSession.kt:162-169`:

```kotlin
val inIdx = enc.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)   // 10_000 µs
if (inIdx >= 0) {
    val inBuf = enc.getInputBuffer(inIdx)!!
    inBuf.clear(); inBuf.put(buf, 0, len)
    val ptsUs = totalFrames * 1_000_000L / SAMPLE_RATE
    enc.queueInputBuffer(inIdx, 0, len, ptsUs, 0)
    totalFrames += len / bytesPerFrame
}
// <-- there is no `else`. When inIdx < 0 the chunk is discarded silently.
muxerStarted = drainEncoder(enc, mux, info, muxerStarted)
```

**Why it pops rather than gaps — and this is the part that matches the report exactly.**
`totalFrames` is *not* advanced when the chunk is dropped, so `ptsUs` stays continuous. The
encoder receives an unbroken timeline with 21 ms of waveform excised. The file is not gappy, it
is **spliced**. A splice during silence is inaudible; a splice mid-vowel is a step discontinuity
whose click amplitude scales with the instantaneous signal level. That is a restatement of
*"popping when audio is present… silence doesn't have it… distortion based on the original
waveform."*

**📐 CALCULATED — why the race is lost on this device and not ours.** From
`DirectAudioRecorderSession.kt:265-273`:

| Constant | Value | Meaning |
|---|---|---|
| `READ_CHUNK_BYTES` | 4096 | 1024 stereo PCM-16 frames |
| `SAMPLE_RATE` | 48_000 | → each read holds **21.3 ms** of audio |
| `DEQUEUE_TIMEOUT_US` | 10_000 | → we wait only **10 ms** for a buffer |

We produce audio roughly twice as fast as we are willing to wait to hand it over. Whether the
dequeue times out is a pure timing race against the device's codec and scheduler, so a fast SoC
never trips it and a slower or differently-scheduled one trips it continuously. This is the same
device-specific shape already recorded in `HandoffGeometry.kt:127-131` — *"a OnePlus 12 sounded
clean while a Galaxy S24 FE crackled on every call."* Our test phones are a OnePlus 12 and a
OnePlus 9 Pro. **We would never have seen this ourselves.**

**We have already fixed this bug — in a different file.**
`app/src/main/java/com/baba/callvault/services/recording/handoff/HandoffEncoder.kt:97-104`:

```kotlin
// Feed the chunk — NEVER drop it. The pipe is bursty, so all input buffers can be briefly
// busy; if dequeue times out we drain output (frees a buffer) and retry, rather than
// discarding audio (which caused periodic dropouts = choppiness).
var inIdx = enc.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
while (inIdx < 0) {
    muxerStarted = drainEncoder(enc, mux, info, muxerStarted)
    inIdx = enc.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
}
```

`VoipCaptureSession.kt:293-298` has the same retry loop. **`DirectAudioRecorderSession` is the
only one of the three encoders that still drops — and it is the default carrier path.** The
retry loop predates v2.2.0 (commit `7e5ccb8`) and was never back-ported.

**Fix:** replace `if (inIdx >= 0)` with the `while (inIdx < 0) { drainEncoder(); retry }` loop.
Low risk, high value. This is the first thing to do.

### ✅ CONFIRMED FROM THE REPORTER'S OWN RECORDING (2026-09-05)

The reporter attached a real affected call in a comment on #25
(`20260904_111322.721-0700_in_+16026093676.ogg`, mono Opus 48 kHz, 101.4 s) and noted it
*"also expresses the bug in my report #28"*. Analysed on the desktop:

- **165 step discontinuities** at a robust-outlier threshold (439 at a looser one) over 101 s.
- They correlate with signal presence: median envelope at events is ~2× the file's median, and
  only 23 of 165 land in the quietest quartile — matching *"silence doesn't have it"*.
- **The decisive test.** Every queued chunk is exactly 1024 frames, so if these are dropped
  chunks the events must sit at a fixed phase modulo 1024 in the output. A Rayleigh uniformity
  test over event positions:

  | period | meaning | R | p |
  |---|---|---|---|
  | **1024** | **our `READ_CHUNK_BYTES` frame count** | **0.27** | **5.4 × 10⁻¹⁵** |
  | 960 | Opus's own frame size at 48 kHz | 0.03 | 0.62 (uniform) |
  | 1100 | control | 0.06 | 0.21 (uniform) |

  The events align to **our chunk boundary** and **not** to the codec's frame boundary. Had these
  been Opus artefacts they would have clustered at 960. This is direct evidence for the splice
  mechanism, independent of the code reading above.

**Consequence: #28a no longer needs a Samsung to reproduce or to verify.** Re-running this
analysis on a post-fix recording is a sufficient regression test, and the pre-fix file is a
permanent fixture. Analysis scripts were ad-hoc; re-create from this description or keep them
alongside the fixture if we want them in-tree.

**Process lesson worth recording separately:** a fix landed in one of three parallel
implementations and no mechanism existed to notice the other two. Compare the memory entry
*"three capture paths: handoff, direct, voip — anything touching captured audio must be added to
all three or it silently never runs."* This is that failure mode, realised, on the default path.

### 28b — `AudioRecord` ring overrun (MEDIUM, same symptom, second source)

`DirectAudioRecorderSession.kt:253` sizes the ring at `minBuf * BUFFER_FACTOR` (`BUFFER_FACTOR = 4`),
≈ **80 ms** at 48 kHz stereo. `captureLoop` runs read → speaker detection → downmix →
`queueInputBuffer` → `drainEncoder` → `mux.writeSampleData` (an actual file write) **all on one
thread**. Any stall in that chain longer than the ring overruns the `AudioRecord`, which discards
frames silently — the same splice-pop from the other end of the pipe. Nothing counts or logs it.

The handoff path deliberately decoupled ring consumption from downstream for exactly this reason
(`audiohandoff.cpp:279-284`, *"DECOUPLE ring consumption from downstream … periodic micro-gaps
(the choppiness)"*). The direct path never got that treatment. Fixing 28a will reduce the stalls
that cause this, but will not eliminate it.

### 28c — AAC selection kills the next recording (MEDIUM; mechanism unproven, silence proven)

> "changing from Opus (my preference) to AAC failed to record the next call"

The **actionable finding is not which AAC setting fails — it is that no failure can be seen at all.**

`RecorderServiceImpl.kt:96` — `startRecording(...)` returns `void` (`IRecorderService.aidl:38`),
and all real work is posted to a worker **after** the binder call returns (`:125-135`). A throw
inside `startWithFallback` is caught and logged *in the daemon's process*, which has no context
to write a log file. The app has already returned `true`, and arms only `livenessWatch`
(`AudioRecordingEngine.kt:201-216`), which calls `pingBinder()` — "is the daemon process alive",
never "did capture actually start". `isRecording()` exists in the AIDL (`:44`) and is never polled.

**So any codec-specific setup failure produces no file, no error, and nothing in the exportable
log.** This is the same class of blindness that cost five rounds of log-gathering on the stuck-mic
investigation, and it is worth fixing on its own merits.

Two candidate triggers, neither confirmed:
- **Bit-rate carry-over.** `AudioRecordingEngine.kt:258`:
  `preferences.getAudioBitRate().takeIf { it > 0 } ?: codecEnum.defaultBitRate`. Once a bit rate
  has ever been set, `ScrcpyAudioCodec.AAC.defaultBitRate = 32000` can never apply. Switching
  Opus→AAC keeps **24 kbps**, and AAC-LC at 48 kHz mono / 24 kbps is where hardware encoders
  start refusing `configure()`. The reporter states they were on 24 kbps.
- **Wrong encoder inspected.** `EncoderLimits.supportsFormat`/`resolveBitRate`
  (`EncoderLimits.kt:62`, `:96`) pick `codecInfos.firstOrNull { it.isEncoder && … }`, but
  `MediaCodec.createEncoderByType(mime)` (`DirectAudioRecorderSession.kt:107`) may instantiate a
  *different* codec. Samsung ships several AAC encoders and one Opus encoder, so this mismatch
  can only bite AAC.

### 28d — Shizuku loses speaker labels (NOT A BUG — explain and close)

Verified as expected behaviour. Shizuku cannot host an `AudioRecord` (`HandoffPolicy.kt:40`), so it
uses scrcpy, which hands us **already-encoded stereo**. `RecorderSession` therefore never sees PCM,
and `SpeakerTurnDetector` runs on the stereo buffer *before* the downmix on the paths we encode
ourselves. No PCM → no speaker turns, structurally.

This also explains their "mic on left and speaker on right": scrcpy's stereo is passed through,
whereas every path we encode is forced to mono (`DirectAudioRecorderSession.kt:268`
`ENCODE_CHANNELS = 1`, rationale at `:90-94` — stereo Opus at 24 kbps starves the far party to
~12 kbps).

**Opportunity, not a defect:** their observation is a reminder that when we *do* have stereo with
mic-L/far-R, speaker attribution is exact rather than inferred. Worth a look for the diarization
backlog — but note the standing "MUST-NOT-UNDO mono-encode rule" before touching it.

### 28e — Shizuku USB-mode nag loop (LOW severity, clean logical gap — found independently)

> "With Shizuku mode I get a constant warning that lock screen recording may not work because USB
> is set to file transfer… if I change it to charge only it stops Shizuku"

The **writer is mode-gated; the warner is not.**

- `UsbDefaultConfig.setViaShell()` refuses in Shizuku mode — logs *"Shizuku mode: not setting the
  Default USB Configuration — no embedded ADB"* (`UsbDefaultConfig.kt:245`)
- `readViaShell()` refuses too (`:299`)
- but `isScreenLockRisk()` (`:196`) reads **only** the cached mode, with no privileged-mode check:
  `cached(context).let { it !in SAFE && it != UNKNOWN }`
- and `HomeViewModel.kt:515` calls it while reading `privilegedMode` **two lines above**, unused

So in Shizuku mode we nag about a setting we refuse to read, refuse to change, and whose cached
value can never be refreshed — while the app's own picker declines to fix it. `PrivilegedMode`'s
own doc comment states the principle being violated (`PrivilegedMode.kt:37-40`): *"in Shizuku mode
none of it applies, and machinery that fires anyway would report failures for setup the user was
never asked to do."*

**Fix:** gate `usbScreenLockRisk` on `privilegedMode.needsAdbSetup`.

⚠️ **Not yet established:** whether the underlying screen-lock risk is genuinely absent under
Shizuku. The rationale for the warning is that a data USB default restarts `adbd`, killing the
shell-uid daemon. Whether a Shizuku-hosted recorder is immune has **not** been verified and should
be before we silence the warning rather than merely re-scoping it.

---

## Issue #26 — transcription time estimates are too far off

> "times of < 10 seconds to hours estimated… for recordings in the range of 10 seconds to 2
> minutes; all calls have transcribed within minutes"

There is exactly one user-facing ETA. It is a pure product computed *before* the run
(`TranscriptionEstimate.kt:61`, `estimateMs = audioMs * rtf`), shown by
`TranscribeConfirmDialog.kt:78`. `rtf` is learned per model from finished runs.

**Two theories are ruled out:** there is no early-progress extrapolation (nothing divides by
`fractionDone`), and no seconds/ms confusion. Published seeds are sane (0.99 / 2.16 / 1.10), so a
fresh install quotes correctly. **"Hours" can only come from a poisoned learned value.**

### 26a — The believability clamp returns the value it just rejected (ROOT CAUSE)

`TranscriptionEstimate.kt:49-58` — note the documented contract: *"@param fallback the model's
published factor"*:

```kotlin
fun blend(stored: Double?, measured: Double?, fallback: Double): Double {
    val believable = measured?.takeIf { it in BELIEVABLE }   // BELIEVABLE = 0.05..50.0
    return when {
        believable == null -> stored ?: fallback              // <-- the hole
        stored == null -> believable
        else -> stored * (1 - SMOOTHING) + believable * SMOOTHING
    }
}
```

`TranscriptionRunner.kt:199-204` violates that contract:

```kotlin
val blended = TranscriptionEstimate.blend(
    stored = prefs.getTranscriptionRtf(modelId),
    measured = measured,
    fallback = measured            // <-- passes the measurement as its own fallback
)
prefs.setTranscriptionRtf(modelId, blended)
```

When `stored == null` and `measured` is outside `0.05..50.0`, the clamp rejects it as a
"measurement bug" and then **returns it anyway** — and line 204 writes it to prefs. A stored rtf
of 90 quotes a 2-minute call at `120_000 × 90` = **3 hours**. That is the reported symptom exactly.

**The tests prove a protection production never gets.** `TranscriptionEstimateTest.kt:53-54`
asserts the clamp only with `stored = 2.0`, never with `stored = null` — so the hole is untested.
But the sharper point is that *every* test passes `fallback = published`, i.e. the contract the
docstring describes, while **the only production caller passes `fallback = measured`**. The suite is
green, the clamp genuinely works as tested, and none of it applies to the single real call site.

### 26b — The calibration wipe re-opens the hole repeatedly

`AppPreferences.kt:707-715` deletes **every** `transcription_rtf_*` key when the thread count
differs from the stored one — and `TranscriptionRunner.kt:198` calls it **before** `stored` is read
on line 200. So after each wipe `stored` is null on that very run, 26a fires, and one unclamped
sample becomes the phone's permanent opinion.

The plausible trigger (⚠️ hypothesis, cheap to falsify by logging): `preferredThreadCount()` derives
from `Runtime.getRuntime().availableProcessors()`, which on Android reports **online** cores.
Samsung kernels vary these with hotplug and thermal state. 8 on one run and 6 on the next wipes
everything each time — matching "wildly varying, no correlation with content."

Distinct and verified: `recordSpeed` reads `preferredThreadCount()` a *second* time at `:198`,
after the run and on another thread, instead of reusing what the run actually used
(`TranscriptionEngine.kt:238`) — so it can record a policy no run ever used.

### 26c — The cost model is structurally wrong (the reporter's own diagnosis, and correct)

`measure` divides elapsed by audio length with no floor (`TranscriptionEstimate.kt:37-40`), but the
elapsed window spans a large fixed cost independent of audio length: an **874 MB** model mmap+init
per run, VAD extraction on first run, decode, and whisper's mandatory **one full 30-second encoder
window** whatever the clip length.

📐 CALCULATED from the project's own measured 0.722 rtf on an OP9 Pro (`TranscriptionModel.kt:99-103`),
with an S20 FE slower still — fixed floor ≈ 30-50 s:

| clip transcribed | measured rtf becomes | then quotes a 2-min call at |
|---|---|---|
| 10 s | ~4 | 8 min |
| 2 s | ~20 | 40 min |
| 0.5 s | ~80 | **2 h 40 min** (and 26a stores it) |

The cost is `load + rtf × audio`; the code models `rtf × audio`. The reporter's suggested fix —
length-aware history — is the right one.

### 26d — Recovery is slow, and other gaps

`SMOOTHING = 0.3` means ~13 completed runs to crawl from a stored 90 back to a true 1.1. The first
measurement also replaces the published seed **outright** (`:55`), so one unaveraged sample becomes
the device's opinion. Queue position is ignored — `TranscriptionScheduler.kt:125-129` chains work,
but the dialog estimates only the tapped file. Storage is **one** `Double` per model
(`AppPreferences.kt:689-694`): no bucketing, no sample count, no variance, no outlier rejection
beyond the defeated range check. With VAD on, whisper processes only kept speech while `measure`
divides by full container duration, adding variance the other way.

### Fix order for #26

1. `TranscriptionRunner.kt:202` — pass the model's published `realTimeFactor` as `fallback`.
   **One word; closes the "hours" magnitude on its own.**
2. Refuse to learn from runs under ~60 s of audio.
3. Move the wipe so it cannot null `stored` on the same run that then writes unclamped.
4. `TranscriptionRunner.kt:147,178` — `SystemClock.elapsedRealtime()` instead of
   `System.currentTimeMillis()` (a clock change currently lands in the learned factor; the rest of
   the codebase already uses monotonic time for durations).
5. Model `loadMs + rtf × audioMs` and learn both terms — the real fix.

---

## Issue #25 — incorrect timestamps on longer pauses

> "a timestamp that got placed in a break of speech between two voices during a transfer…
> there was a noticeable pause between them."

### Root cause: whisper.cpp's VAD compresses every pause into a hardcoded 100 ms bridge

VAD is **on by default** (`DecodeSettings.kt:174`, `useVad = true`), so this affects every
transcription. whisper.cpp's VAD does not merely trim silence — it **rebuilds the audio buffer**
and returns timestamps through a linear-interpolation table that is degenerate at pauses.

`third_party/whisper.cpp/src/whisper.cpp:6731`:

```cpp
int silence_samples = 0.1 * WHISPER_SAMPLE_RATE;   // 1600 samples = 100 ms, hardcoded
```

`:6785-6793` — for the gap between segments *i* and *i+1*:

```cpp
int64_t silence_start_vad = samples_to_cs(offset);
int64_t silence_end_vad   = samples_to_cs(offset + silence_samples);
int64_t orig_silence_start = segment.orig_end;
int64_t orig_silence_end   = vad_segments->data[i+1].start;
state->vad_mapping_table.push_back({silence_start_vad, orig_silence_start});
state->vad_mapping_table.push_back({silence_end_vad,   orig_silence_end});
```

So **100 ms of processed time stands for the entire real gap**, however long it is, and lookups
interpolate linearly across it (`:7995`). Two bridges span every pause:

| processed span | original span | effect |
|---|---|---|
| overlap copy (100 ms) | zero width | slope 0 — everything collapses onto `orig_end_i` |
| injected silence (100 ms) | the whole real pause | slope = gap / 100 ms |

**Why post-pause starts land there by construction:** `whisper.cpp:7699` sets `t0 = t1` — each
segment starts where the previous ended. In the compacted buffer those are ~200 ms apart, so the
post-pause segment's start almost always falls inside one of the two bridges. Worst and most
likely case: it lands in the flat overlap bridge and maps to **the instant the previous speaker
stopped**, while the words themselves begin seconds later. That is issue #25 verbatim.

**Our own VAD params set the threshold** (`app/src/main/cpp/whispercv.cpp:323-338`):
`speech_pad_ms = 400`, `min_silence_duration_ms = 500`, `threshold = 0.4`. With the padding logic
at `whisper.cpp:5410-5442`, a pause **< 800 ms** distorts nothing; a pause **≥ 800 ms** is cut and
its residual compressed to 100 ms.

📐 CALCULATED: a 3 s transfer pause yields up to **2.2 s** of error in ~220 ms steps; a 10 s hold
yields up to **9.2 s**. The error is always **early**, matching the report.

### ✅ CONFIRMED FROM THE REPORTER'S RECORDING (2026-09-05)

Measured against the attached call, the prediction holds to the second:

| Reporter's description | Measured (20 ms RMS envelope) |
|---|---|
| robocall's last sentence ends ~1:16 | speech ends **76.68 s** (1:16.7) |
| "slight ring at 1:18" | isolated blip **78.30–78.44 s** (1:18.3) |
| dialog marked 1:16 really starts 1:28 | speech resumes **88.30 s** (1:28.3) |

The pause is **9.86 s** — far above the 800 ms distortion threshold — and whisper stamps the
post-pause dialog at ~1:16, i.e. **exactly `orig_end_i`, the instant the previous speaker
stopped.** That is the zero-slope overlap bridge, predicted from source before the audio was
available. Error ≈ **12.3 s**.

It also confirms what was previously only a hypothesis: the 1.62 s gap, then the short ring, then
the 9.86 s gap means **the ring passed Silero VAD and became its own segment**, which is why the
reporter saw the mark land near it.

The reporter's own words — *"blank space needs to be appended to end of last time instead of
beginning of this one"* — describe fix direction 1 below.

**This file is ground truth for a regression test**: any fix must move that segment's start from
~76 s to ~88 s.

The source comment at `whispercv.cpp:301-306` — *"it does NOT re-segment, and that distinction is
the whole reason this is safe"* — is correct about text quality and **wrong about timestamps**.
Concatenation is precisely what makes timestamps unrecoverable at pauses.

### Knock-on and secondary findings

- **No sanity check anywhere.** `TranscriptionEngine.kt:296-308` stores segment times verbatim: no
  monotonicity check, no gap clamp, no reconciliation. `SpeakerLabeller.kt:50-81` consumes them, so
  **speaker labels around transfers are wrong too** — the reporter has not noticed this yet.
- **Chunking is NOT at fault.** The classic bug (offsets from trimmed length) is absent:
  `ChunkPlan.stitch` adds `decodeFromMs`, and `TranscriptionEngine.kt:308` passes the *measured*
  decode start, so container seek drift is handled. Chunking only engages above 5 min anyway.
- **Missing "Uh"/"Umm" — suspected causes ruled out.** `suppress_non_speech_tokens` is **false**
  (`whisper.cpp:5985`, never set by us) and `suppress_blank` only affects sampling start. The real
  contributors are the model's training (inherent) and VAD deleting short low-energy turns — a
  failure mode already measured and recorded in `DecodeSettings.kt:161-170`.
- **Trailing hallucinated word — mechanism confirmed.** Rolling conditioning is left at whisper's
  224-token default (`whispercv.cpp:289` with `maxTextCtx = -1`); `no_context = true` does *not*
  disable it. `initial_prompt` is set to the contact name (`TranscriptionPrompt.kt:63-67`), a known
  minor source of prompt echo.
- **Diagnosability gap.** whisper.cpp logs the whole mapping table (`:6774`, `:6818`) but there is
  **no Android log callback** — `whispercv.cpp` never calls `whisper_log_set`, so it goes to stderr
  and is invisible in a debug report. Wiring it to `AppLogger` would make this class of bug readable
  from a user report.

### Fix directions (none implemented; needs a decision)

1. **Cheapest:** clamp a segment start forward to the next VAD segment's original start whenever the
   mapped start falls in a non-speech interval. `whisper_full_get_vad_segment_t0/t1`
   (`whisper.cpp:8131-8155`) is already exposed and **currently unused by the app** — this can be a
   post-pass in `TranscriptionEngine`, with no submodule patch.
2. Make the injected silence proportional to the real gap so interpolation stops lying (submodule patch).
3. Keep the true gap in a side table and snap post-pause starts to it (submodule patch).

⚠️ **Open question not yet checked:** whether upstream whisper.cpp has since fixed this. Our
submodule is pinned at `371b5a7` at both `v2.2.0` and HEAD. Worth checking before we write our own patch.

---

## Issue #27 — rotation closes expanded entry and scrolls to top

> ### ✅ VERIFIED 2026-09-05 — fixed on branch `fix/rotation-state-issue-27`
>
> Confirmed by the maintainer on the OP12 (build `2.3.0-rot27`, versionCode 20316): the open
> recording stays open, the list keeps its place, multi-selection survives, open dialogs survive,
> the app lock does not re-prompt on rotation — **and both negative lock cases still lock**
> (background-and-return, and kill-from-recents). Those two were the security-relevant checks.
>
> | commit | covers | change | state |
> |---|---|---|---|
> | `ab7a055` | 27a + 27c | `playbackFor` → `rememberSaveable` | ✅ VERIFIED |
> | `67aa104` | 27d | the rest of Home's screen state, + `ui/common/StateSavers.kt` and 12 tests | ✅ VERIFIED |
> | `a3341d1` | 27e | App Lock carried across recreation, guarded on `isChangingConfigurations` | ✅ VERIFIED |
> | `cf31f5d` | 27g | the unlock card no longer flashes on open | 🧪 VERIFYING |
>
> 1151 unit tests pass (was 1132). Assembles clean.
>
> **Still VERIFYING:** the flash fix (`cf31f5d`) was written after the device pass and has not been
> installed. **To settle it:** open the app with App Lock on — no "locked / Unlock" card should
> appear before or after the fingerprint prompt. Then dismiss the prompt by tapping outside it: the
> card **must** appear, because it is the only way back in without force-stopping the app.

**Why any state is lost:** `MainActivity` declares no `android:configChanges` and no
`android:screenOrientation` (`AndroidManifest.xml:84-87`), so rotation fully destroys and recreates
the Activity. Only saveable state survives. `HomeScreen.kt` contains **25** `by remember` and
**zero** `rememberSaveable`.

### 27a — The expanded entry closing (still open, one-word fix)

Tapping a row does not expand inline — it sets `playbackFor`, which swaps the whole list out for
`PlaybackScreen`. `HomeScreen.kt:247`:

```kotlin
var playbackFor by remember { mutableStateOf<String?>(null) }
```

On rotation this resets to `null`, the detail view closes, and the user lands back on the list.
It is a `String?`, so `rememberSaveable` needs no `Saver`.

### 27b — The scroll-to-top (ALREADY FIXED on HEAD, unreleased)

In v2.2.0 the `LazyColumn` created its `LazyListState` internally, *inside* the
`if (playbackFor == null)` block. `rememberSaveable` unregisters when it leaves composition, so
while a recording was open there was nothing to save — rotation restored index 0.

Fixed after v2.2.0 by commit `9276d53`, which hoisted it (`HomeScreen.kt:251`) with a comment
describing this exact bug. **So half of #27 is already fixed and merely awaiting a release** —
worth confirming on-device before telling the reporter.

### 27c — Rotation bypasses `closePlayback` (invariant leak) — RESOLVED BY 27a

Closing the detail view deliberately stops audio (`closePlayback` = `stopPlayback(); playbackFor = null`).
Rotation just nulled the field, so **audio kept playing after its screen was gone.** The reporter
charitably read this as a silver lining ("isn't actively interrupting playback"); it was really a
leak of the stop-on-leave invariant.

Making `playbackFor` saveable resolves it without a second decision: the screen is no longer *left*
on rotation, so the invariant it guards is no longer crossed. Audio continues, and the screen that
owns it comes back with it — which is also the behaviour the reporter wanted.

### 27d — Seven more unreported symptoms of the same class — FIXED, with two deliberate exclusions

Converted to `rememberSaveable`: `selection`, `showSupport`, `showSupportAppeal`, `showBulkDelete`,
`transcriptFor`, `deleteTranscriptFor`, `confirmDeleteFor`, `showTranscriptSearch`,
`showTranscribingSheet`, `showModelMissing`, `tooLongMinutes`, `confirmTranscribe`,
`askLanguageFor`, and the bulk-delete dialog's `scope`.

`selection` (`Set<Uri>`), `confirmTranscribe` (`Triple<String, Long?, String?>`) and `scope`
(`DeleteScope`) needed Savers. Those live in `app/src/main/java/com/baba/callvault/ui/common/StateSavers.kt`
as pure encode/decode functions with 12 tests, on the reasoning that `rememberSaveable` restoring a
value is the framework's guarantee but *what we hand it* is ours — and a selection that decodes to
the wrong set is a multi-select whose next action is a delete. Everything encodes to a **list of
strings, never a delimited string**: recording names carry spaces, plus signs, percent-encoding and
at least one real `|`. `RecordingItem` is deliberately never saved — it is a 16-field non-Parcelable
data class, so where an item is needed it is re-resolved from the ViewModel's surviving list.

**Deliberately left resetting**, with the reasons in code comments:

- **The merge dialogs** (`mergeFor`, `unMergeFor`, `unMergeLabels`, `mergeProgress`) — see the new
  finding below. Restoring them would show a dialog for an operation that no longer exists.
- **`deleteTarget`**, the per-row delete confirmation — a rotation that dismisses a destructive
  prompt has destroyed nothing, whereas restoring one bound to a row the refreshed list no longer
  contains leaves a Delete button pointed at something invisible. Dismissing is the safe failure.
- Dropdown menus (`expanded`, `open`) — closing on rotation is conventional.

### 27g — NEW, found on the device: the unlock card flashed on every open

Not in the report and not visible in any screenshot — the maintainer saw it while checking 27e.
Opening the app with App Lock on flashed a card reading "CallVault / locked / Unlock" before the
fingerprint prompt appeared, and again as it tore down.

Cause is lifecycle order, not the lock logic: `onCreate` composes before `onStart` asks for the
prompt, so the locked window drew its full card for a frame with nothing yet on top of it. Fixed in
`cf31f5d` by making the window a function of state — `appLockUi(lockEnabled, isUnlocked,
promptDismissed)` returning `APP` / `WAITING` / `DOOR` — so the card is raised by exactly one thing,
a prompt returning unauthenticated, and the locked window otherwise draws only its background.

The card is deliberately kept for that one case: a prompt dismissed by a tap outside it would
otherwise leave force-stopping the app as the only way back in. The decision is a pure function with
7 tests rather than lifecycle order, because a timing bug like this cannot be seen in a screenshot
and would come back the moment someone reordered a lifecycle call.

### 27f — NEW FINDING: a merge in flight is killed by rotation

Found while fixing 27d, not in the original report and **not fixed here**. `mergeScope` is a
`rememberCoroutineScope()`, so an Activity recreation cancels a running merge or un-merge part-way.
The user sees the progress dialog vanish; what state the recordings are left in depends on where it
was interrupted.

This is arguably more serious than #27 itself, and its fix is different in kind: hoist the merge
into `HomeViewModel` (which survives recreation) rather than making its dialog saveable. Restoring
the dialog without that would be worse than the current behaviour — it would offer to re-run a merge
over recordings that may already be partly merged. Worth its own issue.

### 27e — App Lock re-prompts on rotation — FIXED

`isUnlocked` was an Activity field cleared in `onStop`, so a recreated Activity started locked and
`onStart` re-prompted. Because `AppLockScreen` replaces the nav host, this tore `HomeScreen` out of
composition entirely — which would have discarded even the state 27a and 27d just made saveable.
Not what #27 reports, but it had to be fixed for the rest to hold.

Now carried in the instance state, guarded on `isChangingConfigurations` in **both** directions
(written only during a config change, and `onStop` no longer clears during one).

⚠️ **The security-relevant detail, for review:** the same bundle is also delivered after process
death and restore-from-recents, which *is* leaving the app. Because nothing is written unless
`isChangingConfigurations` is true, `onCreate` reads `false` in that case and the app stays locked.
That distinction is the whole safety argument for this change and is worth confirming on-device —
both negative cases are in the verification list at the top of this section.

### Ruled out (do not spend time here)

`items(key = { it.uri.toString() })` is present and stable (`HomeScreen.kt:693`). The ViewModel and
its list survive recreation, and `refresh()` deliberately does not clear `recordings`, so the
"empty list on first frame" theory does not apply. Playback survives because
`RecordingPlaybackController` is a ViewModel field (`HomeViewModel.kt:84`) — that is the exact
contrast the reporter noticed.

---

## Cross-cutting themes

These matter more than any individual fix.

1. **Parallel implementations drift.** #28a is a fix that landed in one of three encoders. The
   memory entry *"three capture paths… anything touching captured audio must be added to all three
   or it silently never runs"* predicted this precisely. Consider a shared feed helper so the three
   cannot diverge again.
2. **Failures that produce no evidence.** #28c (start failure invisible), #25 (whisper's own
   diagnostics unreachable), #26 (a poisoned rtf is never logged as implausible). This is the same
   blindness that cost five rounds on the stuck-mic investigation.
3. **A guard whose test does not exercise its own failure branch** (#26a) is worse than no guard —
   it reads as protection while providing none.
4. **Our test devices are both OnePlus.** #28a is a timing race we structurally could not observe.
   Samsung reports deserve extra weight.

---

## Suggested order of work

| Order | Item | Cost | Why first |
|---|---|---|---|
| 1 | #28a — no-drop retry loop | ~5 lines | Corrupts the core artefact on the default path; fix already written elsewhere |
| 2 | #26a — `fallback` = published rtf | 1 word | Removes the absurd magnitude immediately |
| 3 | #26b — wipe ordering | small | Stops the hole reopening |
| ~~4~~ | ~~#27a — `rememberSaveable`~~ | done | ✅ **DONE 2026-09-05**, all of #27 — branch `fix/rotation-state-issue-27` |
| 5 | #28e — gate the USB nag on mode | small | Verify the premise first |
| 6 | #28c — make start failure reportable | medium | Unblocks all future diagnosis |
| 7 | #25 — VAD timestamp post-pass | medium | Check upstream first |
| 8 | #26c/#26e — proper cost model | larger | The real fix |
| 9 | #27d — saveable sweep | medium | Prevents seven future reports |

## What needs a device or a decision

- ~~**Reproduce #28a** on a Samsung before and after the fix.~~ **No longer needed** — the
  reporter's attached recording confirms the mechanism statistically (p = 5.4 × 10⁻¹⁵ against the
  1024-frame chunk boundary, uniform against Opus's 960). A post-fix recording re-analysed the
  same way is a sufficient check; a Samsung A/B is now a nice-to-have, not a blocker.
- ~~**Confirm all of #27**~~ — **done on device 2026-09-05**, including both App Lock negative cases.
  Only the follow-up flash fix (`cf31f5d`, 27g) is still unconfirmed; see the top of the #27 section.
- **Release notes for #27 are written** — `CHANGELOG.md` under 2.3.0 → Fixed, and the in-app note
  (`whatsnew_230_body`) in all ten languages. The in-app note stays headline-led on merging, with
  rotation as one closing sentence; the changelog carries the detail.
- **Decide whether #27f** (a merge in flight dies on rotation) gets its own issue. Not fixed here.
- **Falsify or confirm #26b's trigger** by logging `availableProcessors()` per run.
- **Establish whether the screen-lock risk is real under Shizuku** before silencing that warning (#28e).
- **Check upstream whisper.cpp** for a VAD-mapping fix before patching the submodule (#25).
- **Decide the intended rotation behaviour for audio** (#27c).

## Replying to the reporter

Four things we can tell them straight away:
- #28 crackling: root cause found, fix identified, their Shizuku comparison is what localised it.
- #27: **fixed** — both halves, plus the multi-selection, open dialogs and transcript sheet, which
  they had not reported. Worth mentioning that rotating no longer re-asks for the app lock either.
  Note back that a merge interrupted by rotation is a separate bug we found from their report.
- #26: confirmed, and their proposed fix (length-aware history) is the correct one.
- #25: the missing "Uh"/"Umm" is model behaviour plus VAD, not something we suppress — and yes,
  the same audio file transcribes the same way on another device, so sharing one would help.
  The timestamp error itself is ours (inherited from whisper.cpp's VAD) and is actionable.
