# A whole utterance goes missing from a transcript — greedy + VAD, not capture

**2026-09-05, on 2.3.0-rc2.** Reported as a suspected regression: *"speaker labels in cell call work,
but it didn't transcribe properly, it's missing words entirely… the VoIP call recognised all the words
and I used the same sentences for both."*

**Verdict: not a regression, and not a capture fault.** The audio is complete. The decoder drops the
words, it has done so since before this build, and which call it hits is close to a coin flip.

## What the evidence says

Four recordings from the maintainer's OP12, all Hebrew, all saying a near-identical test phrase twice
("בדיקה, בדיקה, בדיקה, זה OnePlus 12 / OnePlus 9"):

| file | capture path | app-config transcript |
|---|---|---|
| `20:40 out_Feroza` (cell) | handoff | ❌ **first utterance gone** |
| `20:41 voip-WhatsApp` | VoIP | ✅ both |
| `21:03 out_Feroza` (cell) | handoff | ✅ both |
| `21:04 voip-WhatsApp` | VoIP | ❌ **first utterance gone** |

**The failure flipped sides between the two rounds.** That alone rules out "the carrier path is broken":
the same configuration loses an utterance on a VoIP recording too.

**The audio is intact, proven per utterance.** Cutting each speech window out of the 20:40 cell file and
transcribing it alone returns both sentences cleanly — "זה 1.12" and "זה וואן פלוס תשע". Nothing was
lost in capture, and the capture's own accounting agrees: the handoff drain reported
`dropped=0 frames in 0 overruns` over 14 612 ms, and the VoIP capture `0 silence-filled chunks,
farPartyHeard=true`.

**It predates the build.** A cell recording made at 19:46, *before* rc2 was installed at 20:14, loses
words the same way: the app's config yields "חכה" where beam-5 and `-mc 0` both recover
"רגע גבריא, חכה".

**Nothing about the audio changed.** Every file — before and after rc2 — is mono Opus 48 kHz from the
same encoder (`libopus`, preskip 312) at 27–34 kbps, and peak levels are within 1.5 dB. The 28c encoder
change did not alter what encodes calls on this phone.

## What actually causes it

One variable at a time, on the 20:40 cell file, with the app's exact decode settings:

| config | result |
|---|---|
| app config: greedy (`beamSize=1`), VAD on, `entropy_thold` 2.8, prompt | ❌ one utterance |
| …but beam 5 | ✅ both |
| …but VAD off | ✅ both |
| …but rolling conditioning off (`-mc 0`) | ❌ still one (but ✅ on the 19:46 file) |
| …but `entropy_thold` 2.4, or no prompt | ❌ no change |

So it is the **greedy + VAD pair**, exactly the interaction `DecodeSettings.beamSize` already documents
from the other direction. VAD concatenates the kept speech, which packs two near-identical sentences
against each other, and greedy then collapses the near-repeat into one.

**Beam-5 is not the fix.** It repairs both files here, and the existing measurement on a real 8:46 call
has beam-5 + VAD deleting the first 94 seconds, emitting `*ערבית*` language tags and looping `כאילו`
49 times. One pair of 15-second test calls does not overturn that.

## The trap in the test material

**Saying the same sentence twice is the worst possible probe for this pipeline**, because near-repeats
are precisely what a greedy decoder collapses. A regression test that repeats a phrase will keep
producing this "missing words" symptom on healthy builds. Use two different sentences.

## Reproducing the app's decoder on the Mac

This is the part worth keeping — the whole diagnosis was done off-device in minutes.

```sh
brew install cmake                       # was missing
cd third_party/whisper.cpp && cmake -B build -DCMAKE_BUILD_TYPE=Release
cmake --build build --config Release -j 8 --target whisper-cli
ffmpeg -i call.ogg -ar 16000 -ac 1 call.wav

# the app's exact configuration (whispercv.cpp + DecodeSettings defaults)
third_party/whisper.cpp/build/bin/whisper-cli \
  -m ~/.cache/whisper-models/ggml-ivrit-large-v3-turbo.bin -f call.wav \
  -l he -bs 1 -et 2.8 --prompt "<contact name>." \
  --vad --vad-model app/src/main/assets/ggml-silero-v5.1.2.bin \
  --vad-threshold 0.4 --vad-min-speech-duration-ms 100 \
  --vad-min-silence-duration-ms 500 --vad-speech-pad-ms 400
```

Ground truth is the same command with `-bs 5` and no `--vad`.

🚨 **This shell is zsh, which does not word-split unquoted variables.** Putting the VAD flags in a
`$VAD` variable passes them as ONE argument; whisper-cli then prints its usage and exits, and the run
looks like "the model returned nothing". Three runs were misread that way before it was spotted. Write
the flags inline, or use `${=VAD}`.

## Left open, deliberately

A fix needs a corpus, not this pair of calls — the same standard `DecodeSettings` already sets for
beam. Candidates, in the order they look promising:

1. **Decode each VAD segment separately** instead of letting whisper concatenate them, so two sentences
   can never be packed against each other. Closest to the actual mechanism.
2. **`maxTextCtx = 0`** (no rolling conditioning). Recovered the words on the 19:46 file and split the
   VoIP one correctly, but did not save the 20:40 file — so it is a partial mitigation at best.
3. Beam-5 **only when VAD is off**, which is the one quadrant the earlier 2×2 found safe. Costs 17% more
   wall-clock and gives up VAD's larger gain.
