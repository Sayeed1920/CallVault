# Issue #29 — playing a voicemail is reported as a call that failed to record

**Reporter:** mirror176 (Samsung Galaxy S20 FE 5G, One UI 5.1, Android 13), against 2.2.0, standalone
mode, VoIP + resilient + offline recording all on. *"Playing back voicemail… causes callvault to
indicate like a call was initiated and then marks that the call was not recorded. Issue disappears once
I activate Shizuku for recording."*

## Root cause (from our source; the Shizuku clue is what proves it)

`VoipCallDetector` has exactly one signal: `AudioManager.getMode() == MODE_IN_COMMUNICATION`. That is
deliberate and documented — there is no broadcast for WhatsApp-style calls, and playback *usages* are
anonymised for ordinary apps, so the mode was the only thing left. But the mode is a **routing state,
not a call state**: any app that wants its audio in the earpiece takes it.

Samsung's voicemail app plays through the earpiece by default (there is a speaker toggle beside it,
which is why the reporter noticed it made no difference). So each play — and each resume after a pause,
which he also noticed — looks exactly like an app call starting.

`VoipRecordingCoordinator.onCallStarted` then finds it cannot record (no daemon connected, or the
policy was not armed before the "call") and calls `reportMissed`, which shows *"An app call was not
recorded — CallVault was not ready in time"* **and writes `recordGap(now, "App call")` into
`SetupHealthStore`.**

**Why Shizuku makes it vanish, and why that is the proof:** `VOIP_RECORDING` is unavailable in Shizuku
mode (`ModeCapability`: `!mode.needsShizuku`), so the mode switch turns the feature off and
`VoipCallDetector.start()` returns immediately. **Carrier recording stays on in Shizuku mode** — so if
this were the carrier path, Shizuku would have changed nothing. It is not a fix; the feature is absent,
and the symptom returns the moment he goes back to standalone.

## Severity: higher than "an annoying notification"

Nothing is lost — there was no call. But every false trigger writes an **unexplained gap** into the
health record, the strongest signal the app has, and it surfaces on the status card the next day. So
playing voicemails fabricates a history of missed calls, and it teaches the user to ignore the one
warning that must never be ignored. Same shape as the Drive-health false positive: **a claim about the
present, made without evidence about the present.**

## Two defects found while reading, both real on their own

1. **The per-app exclusion is checked too late.** `VoipAppPolicy.shouldRecord` sits *after* the
   daemon-missing and folder-invalid branches, and both report a miss first. A user who excludes the
   voicemail app still gets the notification whenever the daemon is down.
2. **He may have no workaround at all.** `VoipAppList` offers only apps that have a launcher entry
   **and** request `RECORD_AUDIO`. A visual-voicemail app may fail either test.

## What the research found, and what it changed

### ✅ It is not necessary to ask the daemon whether something is recording

The first plan was "have the daemon check for an active capture, since it holds `MODIFY_AUDIO_ROUTING`".
The platform docs say that is unnecessary: *"You can get a general view of all active recordings on the
device by calling `AudioManager.getActiveRecordingConfigurations()`"* — device-wide, no permission. Only
the **identity** is redacted without `MODIFY_AUDIO_ROUTING` (the client uid reads `-1`), and identity is
not what we need. **Existence is enough**, and existence is exactly the discriminator:

- a real two-way call means **some app is capturing the microphone**;
- voicemail playback captures nothing.

App-side, no daemon, no new permission — which matters precisely because in this reporter's case **the
daemon is the thing that is missing**. We use none of this API today.

### 🆕 `TelecomManager.isInCall()` is a positive-only signal, and must not be used to suppress

It *does* include self-managed (VoIP) calls, and needs only `READ_PHONE_STATE`, **which we already
hold**. So `true` is solid corroboration that a call exists. But it can only be used one way: not every
VoIP app registers a self-managed `ConnectionService`, and this research could not confirm that WhatsApp
does. Using `isInCall() == false` to stay quiet would silence a genuine missed WhatsApp call — trading
this bug for the worst failure this app has.

### 🆕 A non-UI `InCallService` cannot see app calls at all

Worth knowing before anyone proposes "detect calls through Telecom like upstream does":
`METADATA_INCLUDE_SELF_MANAGED_CALLS` *"can only be set for an InCallService which also sets
METADATA_IN_CALL_SERVICE_UI, and only the default phone/dialer app or a car-mode InCallService can see
self-managed calls."* So Telecom binding solves **carrier** detection, never VoIP — unless the parked
dialer-mode work lands and we become the default dialer.

### 🆕 Upstream and BCR both drive *carrier* detection from an `InCallService`

`kitsumed/ShizuCallRecorder` binds one as NON_UI through the `MANAGE_ONGOING_CALLS` AppOp (Android 12+),
and reads `Call.Details.PROPERTY_VOIP_AUDIO_MODE` and `isSelfManaged`; its source carries the AOSP
links and an adb check (`adb shell telecom is-non-ui-in-call-service-bound <pkg>`). BCR calls this
*"much more reliable than using the READ_PHONE_STATE permission and relying on PHONE_STATE
broadcasts."* Not a fix for #29, but it is the industry answer for the carrier path, and it is sitting
in our own upstream.

### 🆕 Two corroborations of things we already believed

- *"Two ordinary apps can never capture audio at the same time"* — the documented rule behind
  [[no-second-voice-capture-during-call]].
- `AudioRecordingConfiguration.isClientSilenced()` exists: the platform will **tell** a recorder that it
  has been silenced by capture policy. We currently infer this. Relevant to the one-sided-VoIP warning.

## Recommended fix, in order

1. **Corroborate before believing the mode.** Require at least one active recording configuration
   (excluding our own) before treating `MODE_IN_COMMUNICATION` as a call — and certainly before
   `reportMissed` ever fires. Kills the whole class, needs nothing but the app.
2. **Check the user's exclusion first**, above every report path.
3. **Say less when we know least:** a "not ready" miss for a call we could not even identify is where we
   shout loudest on the thinnest evidence.

## Must be verified on a device before implementing

📐 The mechanism is **reasoned, not measured**. Before writing the fix:

- During a real WhatsApp call, does an ordinary app see ≥1 active recording configuration? During
  voicemail playback, zero? That is the whole fix in one probe.
- Does `TelecomManager.isInCall()` read true during a WhatsApp call on the OP12? Settles whether
  WhatsApp registers with Telecom at all.
- Ask the reporter: the voicemail app's package name, whether the notification says "not ready in time",
  and a 2.3.0 debug report taken right after playing a voicemail.

**rc2 does not change this** — the capture-start check added for 28c is on the carrier path only.
Expect the same report against 2.3.0-rc2.
