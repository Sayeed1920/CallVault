# Merging a dropped-and-redialled call into one recording — plan

🧪 **PLAN ONLY — nothing built.** Status of every item below is "not started".

## The problem

A call drops and you ring each other back. To you that is one conversation; to CallVault it is two
recordings, which is correct and also unhelpful. The user should be able to say "these were one
call" and get one recording — and be able to take it back.

## Decisions already taken

Settled with the maintainer on 2026-09-02, recorded so they are not relitigated:

- **Entry point is the recording card's ⋮ menu**, not a proactive suggestion on the list. The
  alternative (the app spots a likely continuation and offers) was considered and **declined**: it is
  inherently pairwise, so a call that dropped twice would prompt twice.
- **Order is the order you tick them in, not chronological.** The recording you opened the menu from
  is always ① and cannot be moved. Each one you tick takes the next number. Ticking the 3rd row in
  the list makes it ②, and it plays immediately after ①.
- **Un-merge is required**, which forces the merge to be non-destructive.

⚠️ Because order is selection order, a merge *can* be assembled out of chronological order. That is
allowed on purpose. The consequence is that the merged file's timeline is **playback order, not
wall-clock order** — so each part's header keeps its real date and time, and it is the running
position inside the merged audio that is re-based. The numbered badges plus the result strip are
what make the chosen order visible before it is committed.

## Why non-destructive is the only sane shape

Merge writes a **new** recording and keeps every original, hidden from the list and recorded as a
part. Un-merge deletes the merged file and its derived rows; the parts reappear untouched, because
they were never altered.

The alternative — merge destructively and re-split to undo — means cutting encoded audio back apart
at a frame boundary and hoping the pieces are what they were. It is lossy and it gets edge cases
wrong. Not doing that.

Cost is roughly 2× storage for a merged call. Mono AAC runs about 0.5 MB/minute, so an hour of
merged conversation costs ~30 MB extra. Acceptable.

🚨 **Retention and the storage cap must never reap a part**, exactly as they already never reap a
starred recording (`StorageCapPolicy`, `RetentionSweepWorker`). A part that vanishes turns un-merge
into a lie. This is the single highest-risk interaction in the feature.

## The audio

Cheaper than it looks. Every capture path encodes **mono** (`ENCODE_CHANNELS = 1`), and the direct
and VoIP paths both run at **48 kHz**. Two calls recorded on the same phone with unchanged settings
are therefore format-identical, and merging is `MediaExtractor` → `MediaMuxer` **stream copy** with
each part's presentation timestamps offset by the running total. No decode, no re-encode: lossless
and effectively instant.

**v1 declines mismatches** rather than re-encoding them. A mismatch only happens if the codec setting
(AAC/`.m4a` vs Opus/`.ogg`) changed between the two calls, which is rare and which the user did
deliberately. The message says so plainly. Re-encoding is a later addition if anyone asks.

**At the seam: butt-join and drop a mark.** Padding the real gap with silence bloats the file and
adds nothing. A mark preserves the information — "there was a 7m54s gap here" — and makes it
navigable, reusing a feature that already exists.

## Schema

`recordings.db` → **version 3** (version 2 is the duration cache on `fix/recording-duration-cache`).

```kotlin
@Entity(tableName = "merge_parts", primaryKeys = ["mergedName", "position"])
data class MergePartEntry(
    val mergedName: String,  // the merged recording's displayName
    val position: Int,       // 1 = the primary, then selection order
    val partName: String,    // the original recording's displayName
    val offsetMs: Long,      // where this part begins inside the merged audio
)
```

That is everything un-merge needs. **A recording is hidden from the list iff it appears as a
`partName`** — derived, not a second flag, so the two can never disagree.

## What happens to the metadata

Everything keys off `displayName` across ten tables. Each is **copied, never moved**, so un-merge is
a delete:

| Table | Rule on merge |
|---|---|
| `transcripts`, `transcript_segments` | concatenated; part *n*'s times shifted by its `offsetMs` |
| `speaker_turns` | concatenated and shifted |
| `recording_flags` (marks) | concatenated and shifted, **plus a seam mark per boundary** |
| `recording_tags` | union |
| `recording_favourites` | starred if **any** part was starred |
| `recording_notes` | concatenated with a separator naming each part |
| `recording_waveforms` | peaks concatenated |
| `call_summaries` | **not inherited.** A summary of half a conversation is worse than none; the merged call offers re-summarise |

## Build order

1. `MergePartEntry` + DAO + `MIGRATION_2_3`, with tests. No UI.
2. `AudioConcat` — the stream-copy join, plus format-compatibility check. Unit-tested against real
   fixtures from both capture paths.
3. `MergeService` — orchestration: write the file, copy the metadata, write `merge_parts`, and the
   inverse for un-merge. Transactional; a failed merge must leave nothing behind.
4. Teach `StorageCapPolicy` and `RetentionSweepWorker` that parts are exempt. **Tests first** — this
   is where silent data loss would come from.
5. Hide parts from the Home list.
6. The modal, the ⋮ entry, un-merge + its confirm dialog.
7. Strings across all ten locales via `scripts/merge-translations.py`.

Steps 1–4 are invisible to the user and carry all the risk; the UI is the cheap half.

## Open, needs the maintainer

- **How far back does the list go?** Every call with that number, or the last N / last 90 days?
- **A merged recording as a part of another merge** — allow chaining, or refuse? Refusing in v1 is
  simpler and no worse.
