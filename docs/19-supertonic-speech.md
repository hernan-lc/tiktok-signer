# Supertonic 3 speech (Android)

Status: implemented. Scope: `android/app/src/main/java/dev/nglmercer/tiktools/tts/`.

Supertonic 3 is the offline text-to-speech engine for TikTok LIVE events. It runs on CPU through ONNX
Runtime, downloads its own model files, and never contacts the network once installed.

The port follows the official reference — `supertone-oss-archive/supertonic`, `py/helper.py` and
`py/example_onnx.py` — which is treated as authoritative for preprocessing, language tags, model
inputs, inference defaults, and output handling. The earlier `mewmix/nabu`-derived code is **not** the
authority and its behaviour is deliberately not preserved where it disagreed.

## Defects fixed

| # | Defect | Fix |
|---|---|---|
| 1 | No `<lang>…</lang>` wrapper, so the model never received language conditioning | Tagged text is built in `UnicodeProcessor.preprocess` before tokenization |
| 2 | NFKD combining marks were deleted, corrupting `niño`, `estás`, `canción` | Marks are preserved and indexed, matching the reference |
| 3 | Mask/id lengths used UTF-16 units, desyncing on surrogate pairs | Lengths are Unicode code-point counts |
| 4 | Emoji were tokenized and out-of-range code points were clamped to the last indexer entry | Emoji ranges are stripped; numpy `uint16` truncation is reproduced instead of clamping |
| 5 | Only part of the normalization pipeline existed | Full pipeline: symbol/dash table, decorative-symbol removal, `@`/`e.g.,`/`i.e.,` expansion, punctuation spacing, duplicate-quote collapse, the reference terminal-punctuation set |
| 6 | Defaulted to 5 denoising steps | Reference default 8, with Fast/Balanced/High presets |
| 7 | The vocoder’s latent-padding tail was played | Every chunk is trimmed to its predicted duration |
| 8 | `text.take(300)` silently truncated messages | Sentence-aware chunking with 0.3 s inter-chunk silence and an explicit, configurable cap |
| 9 | Requests were dropped while busy; repeat raced with in-flight synthesis | Single-worker scheduler with a conflated request slot and request generations; superseded results are discarded |
| 10 | `AudioTrack` ignored `write()` results, used `MODE_STATIC` with the whole utterance as its buffer, and busy-polled | Streaming writes with partial-write handling, bounded buffers, error detection, and bounded completion polling |
| 11 | “Ready” meant “file exists and is non-empty” on a mutable `main` revision | Pinned revision plus real digests, atomic `.part` downloads, and a local verified manifest |
| 12 | Voice fixed to F1 | All ten published styles, downloaded on demand and sharing the model sessions |
| 13 | No diagnostics | Optional structured logging and Float32/PCM16 WAV export |

## Model installation

`files/supertonic3/` holds six runtime files plus one style file per voice. All downloads are pinned to
revision `3cadd1ee6394adea1bd021217a0e650ede09a323` of `Supertone/supertonic-3`; files from different
revisions can never be mixed.

Verification uses only published metadata:

- the four ONNX weights by **SHA-256** (Hugging Face LFS object ids) and exact byte length;
- `tts.json`, `unicode_indexer.json` and each voice style by exact byte length and **git blob SHA-1**
  (Hugging Face stores them as plain git blobs, so the git object hash is the published identity).

Downloads stream to `<name>.part`, are retried up to three times with backoff, and are renamed only
after the digest matches, so a corrupt or interrupted download never becomes an active file. After a
successful pass a local `manifest.json` records the verified revision and digests. `status()` then does
a fast length check, and re-hashing happens only after a download and immediately before the engine
loads. A model installed by an older build (or a different revision) reports as unverified and is
re-downloaded.

Total download is roughly 400 MB. `vector_estimator.onnx` alone is 256 MB.

## Languages

31 ISO codes plus `na`:

`en ko ja ar bg cs da de el es et fi fr hi hr hu id it lt lv nl pl pt ro ru sk sl sv tr uk vi` + `na`

- **Auto** (default) sends `na`. `na` is the model’s **unknown/fallback** tag: a language-agnostic
  input. It is **not** a language detector, and the UI says so. No detector is implemented.
- **Manual** sends one specific code. Invalid codes are rejected before inference — both at the
  settings boundary and again in the engine.
- **Device language** maps `Locale.language` to a supported code, falling back to `na` when there is no
  match rather than guessing.

## Voices

`F1 F2 F3 F4 F5 M1 M2 M3 M4 M5`. `F1` is the default so an upgrade changes nothing.

Voice styles are ~290 KB JSON files. They share the four ONNX sessions: selecting a voice loads a style
tensor, never a second copy of the model. Each style is validated against the model’s declared shapes
(`style_ttl[1]` must equal `latent_dim * chunk_compress_factor`) before use, incompatible styles are
refused, and the cache keeps at most three styles, closing evicted tensors.

## Quality, speed, chunking

| Preset | Steps |
|---|---:|
| Fast | 5 |
| **Balanced (default)** | 8 |
| High | 12 |

Speed is independent of quality and defaults to `1.05` (range `0.7`–`2.0`). Long text is split on
paragraph and sentence boundaries with the reference’s abbreviation guards, at 120 characters for
Korean and Japanese and 300 otherwise, then joined with 0.3 s of silence. The LIVE speech-length cap is
off by default (`0` = speak everything); when set, it drops whole trailing chunks and cuts a single
over-long chunk at a word boundary.

**More steps cost proportionally more CPU and do not necessarily sound better.** High is an experiment,
not an upgrade — compare by ear with the same text and voice before keeping it.

## Audio pipeline

`ONNX waveform (Float32) → validation → PCM16 → AudioTrack (streaming) → device`

- Before playback: reject empty waveforms, count NaN/Inf, measure peak and RMS, count samples outside
  `[-1, 1]`, and sanity-check the sample rate. No gain or normalization is ever applied.
- Float32 → PCM16 clamps **only** at the final integer step; NaN/Inf become silence rather than
  full-scale noise.
- Playback uses `MODE_STREAM` with a `getMinBufferSize`-derived buffer, handles partial writes, detects
  negative `write()` results, and waits for the playback head to drain with a bounded deadline. The
  track is always released in a `finally`.

## Concurrency

Speech requests and playback are serialized by a single worker. New requests go into a capacity-1
conflated channel, so a request arriving while the engine is busy **replaces** the pending one instead
of being dropped and the queue can never grow. A monotonic request id means a superseded synthesis is
discarded rather than played — a single ONNX `run` cannot be interrupted, so discarding is the correct
cooperative approach. `stop()` invalidates the current request and stops the track; repeat works
because the speaker queues rather than refuses. Shutdown cancels the queue, waits for the worker to
leave inference, then closes styles and sessions, and a closed engine throws instead of touching a
released session.

## Diagnostics

Enable **Advanced → Diagnostic logging** for one structured line per utterance: preprocessing,
duration, text-encoding, latent, estimator, vocoder and total time; playback start latency; real-time
factor; sample rate and sample count; predicted duration; WAV duration; peak; RMS; clipped-sample count;
chosen language, voice, preset and chunk count. Speech content is never logged.

Enable **Export WAV for debugging** to write both the raw Float32 waveform and the played PCM16 audio to
`cacheDir/supertonic-diag/`, shareable with **Share diagnostic WAVs**. Both use the model sample rate and
neither is normalized.

## Troubleshooting distorted speech

1. Export both WAVs, listen off-device.
2. Distorted export → the defect is upstream: re-verify the model files against the pinned revision,
   confirm preprocessing with the unit tests, then compare against the reference harness.
3. Clean export but wrong on the device → playback or device output: AudioTrack configuration,
   Bluetooth path, volume, or system audio effects.
4. Then compare 5-step and 8-step output for the same text and voice before assuming the step count is
   the problem.

## Running the tests

```sh
cd android
./gradlew :app:testDebugUnitTest                    # 194 JVM tests; no model needed
./gradlew :app:assembleDebug :app:lintDebug

# Real model, on a device. Requires the model installed from the Speech screen.
./gradlew :app:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.supertonicIntegration=true

# Reference parity (see scripts/supertonic/parity/README.md).
./gradlew :app:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.supertonicParity=true
```

JVM tests pin preprocessing against the reference (`UnicodeProcessorTest`), plus chunking, quality
validation, manifest/integrity, PCM16/WAV, preference migration, and repeat/cancellation behaviour.
The instrumentation tests require the real model and are skipped unless explicitly enabled.

## Known limitations

- Numerical parity for preprocessing, ids, mask and duration is exercised by the harness; the final
  waveform comparison requires identical injected noise and is only run on demand.
- Perceived quality is a **listening** check. No test in this repository can claim an audio defect is
  fixed on perceptual grounds.
- Non-BMP code points follow the reference’s `uint16` truncation: deterministic and faithful, but not
  “correct” Unicode mapping. After emoji removal such inputs are rare.
- Voice styles are validated against the model’s shapes, and the pinned revision is the only supported
  one.
- CPU only. Adding GPU/NNAPI providers is out of scope until CPU correctness and parity hold.
