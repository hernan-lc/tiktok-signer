# TikTools Studio for Android

A Jetpack Compose / Material 3 studio for TikTok LIVE. The Android app owns
its session, event history, HTTP automations, rewards, and speech controls.
Signing and the live socket still use the existing Rust JNI core. The Java
SDK and Rust implementations are unchanged by this refactor.

## Navigation

- **Home:** username or room connection, live discovery, recent creators,
  connection progress, session counters, recent activity, and quick speech controls.
- **Events:** searchable event history, category filters, display density,
  pause/clear, and event details with raw JSON copying.
- **Actions:** HTTP GET/POST automations, trigger categories, optional gift
  matching, template fields, cooldowns, test execution, and run history.
- **Rewards:** leaderboard, event rates, viewer earnings, manual adjustments,
  and confirmed resets.
- **Settings:** secondary destination for connection policies and appearance;
  separate Speech and Diagnostics screens hold engine/model controls and logs.

Phones use bottom navigation; windows at least 600 dp wide use a navigation
rail. Colors support system/light/dark appearance and Android 12+ dynamic
colors. Shared Compose components and spacing live in `ui/`.

## Architecture and compatibility

The application-scoped `AppContainer` owns `LiveSessionManager`, repositories,
and the single event pipeline. Screen ViewModels expose state to stateless
Compose screens; navigation and Activity recreation do not own the socket.
The session distinguishes resolution, preparation, connection, live, retry,
and failure. Only the native open callback marks a connection live. Cancelled
connections release their native handle and stale callbacks are ignored.

Room opens the existing `tiktools-studio.db`. Migration 1 → 2 retains viewer
balances, actions, run history, and auto-increment sequences, and adds earning
attribution for future awards. Existing balances appear as previous balance
because historical per-category earnings cannot be reconstructed. Awards and
manual adjustments use transactions. The exported Room schema is in `app/schemas/`.

DataStore migrates the existing points, speech, and event-display preferences.
It also persists filters, recent creators, theme, speech categories, and
connection policies, including chat speaker naming and a custom chat prefix.
Supertonic settings persist too: language mode and code, voice, quality preset,
custom inference steps, speed, chunk length, inter-chunk silence, the LIVE speech
length cap, diagnostic logging, and WAV export. Upgrades default to Auto/`na`, voice
F1, Balanced, speed 1.05 and 300 ms silence, so an existing installation keeps working
without being reconfigured. The UI and spoken event phrases are available in English
and Spanish. Android 13+ users can change the app language from Settings → Appearance
→ App language; older Android versions follow the device language. Event history is
memory-only and limited to 300 rows;
pausing the reader does not pause rewards, actions, or speech.

The source namespace is `dev.nglmercer.tiktools`. The installed application ID
remains `com.example.ttlsigner` to preserve upgrade data. `TtlNative` and
`TtlEvents` retain their original package and signatures for JNI compatibility.

## Speech and background operation

Speech supports Off, Android device TTS, and offline Supertonic 3. Models download
separately into `files/supertonic3/`; Speech shows install state and provides
cancel/delete, reload, preview, voice/language/quality controls, repeat, skip, and
category toggles. Chat speech can announce each name, only announce names when the
speaker changes (with a repeat after 15 seconds of silence), or omit names. The chat
prefix is editable; leaving it blank uses the selected app language’s default.
The ONNX dependency includes native libraries and increases APK size.

### Supertonic 3 (offline)

The engine is a port of the official reference
(`supertone-oss-archive/supertonic`, `py/helper.py`), pinned to revision
`3cadd1ee6394adea1bd021217a0e650ede09a323` of `Supertone/supertonic-3`.

- **Install.** Download all six runtime files (~400 MB) from the Speech screen. Each
  file is streamed to `.part` and promoted only after its digest matches the published
  metadata — SHA-256 for the four ONNX weights (Hugging Face LFS object ids) and byte
  length plus git-blob SHA-1 for `tts.json`, `unicode_indexer.json` and each voice
  style. A local `manifest.json` records the verification, so later launches do a fast
  size check and the full hashing happens once, after download and before the engine
  loads. Nothing is invented: every digest comes from the repository’s own metadata.
- **Offline.** Once installed, synthesis never touches the network. No GPU or NNAPI is
  used; inference runs on CPU with ONNX Runtime.
- **Languages.** 31 codes plus `na`. The default is **Auto**, which sends the model’s
  `na` **fallback** tag — Supertonic has no language detector, so nobody should read
  `na` as automatic detection. Choose a specific code, or “Device language”, which maps
  the phone locale to a supported code and falls back to `na` when there is no match.
  Text is wrapped as `<es>…</es>` before tokenization, so language conditioning reaches
  the model.
- **Voices.** F1–F5 and M1–M5. F1 is the default so existing users hear no change.
  Voices are small JSON style files that share the same four ONNX sessions, so
  switching voice never reloads the model. Styles are downloaded on demand, validated
  against the model’s shapes, and cached (at most three at a time).
- **Quality presets.** Fast (5 steps), **Balanced (8, default)**, High (12). The
  reference default is 8. More steps cost proportionally more CPU and may not sound
  better — treat High as an experiment and compare by ear. Speed is independent and
  defaults to 1.05 (range 0.7–2.0).
- **Long messages.** Text is split on paragraph and sentence boundaries (120 characters
  for Korean and Japanese, 300 otherwise) and chunks are joined with 0.3 s of silence.
  Nothing is silently truncated; set the advanced “LIVE speech length cap” to bound how
  much of a long message is spoken, and whole words are preserved when it applies.
- **Memory.** `vector_estimator.onnx` alone is 256 MB, and the four sessions need roughly
  1 GB of RAM at runtime. Low-memory devices may fail to load; that surfaces as an error
  in the Speech screen rather than a silent fallback to another engine.

### Audio diagnostics

Enable **Advanced → Export WAV for debugging** to write, for every utterance, the raw
Float32 waveform and the PCM16 audio that was actually played into
`cacheDir/supertonic-diag/`, then use **Share diagnostic WAVs** to send them off-device.
Both files use the model’s own sample rate and neither is normalised, so they show
whether a defect is in inference or in playback.

**Troubleshooting distorted speech:**

1. Export both WAVs and listen to them off-device.
2. If the exported Float32 file is already distorted, the problem is upstream —
   preprocessing, model compatibility, or inference parameters. Re-verify the model,
   confirm the pinned revision, and compare against the reference harness.
3. If the exported file is clean but the phone sounds wrong, the problem is playback or
   device output (AudioTrack configuration, Bluetooth path, volume/effects).
4. Turn on **Diagnostic logging** for timings, real-time factor, peak/RMS, clipped-sample
   counts, sample rate, sample count, chunk count, language and voice. Speech content is
   never logged.

When background connection is enabled, `LiveService` maintains the foreground
notification with disconnect and speech controls. Disabling this policy ends
an active connection when the Activity goes into the background. Reconnection
can be disabled independently. Notification permission is requested on Android
13+. The service does not reconstruct a session after process death.

## Build and verification

Requirements: JDK 21, Android SDK platform 35, and Gradle (the checked-in wrapper
uses 9.7.1). The Android Gradle build rejects other JDK versions because the
Robolectric test stack is verified with JDK 21. CI runs Android build, tests,
lint, and instrumentation APK assembly on JDK 21. Native builds additionally
require NDK 27, Rust Android targets,
`cargo-ndk`, and libclang. Set `sdk.dir` in ignored `android/local.properties`.

```sh
# From the repository root: build the live/signing JNI library for all ABIs.
./scripts/android/build-native.sh

cd android
./gradlew assembleDebug
./gradlew testDebugUnitTest lintDebug assembleDebugAndroidTest
# APK: app/build/outputs/apk/debug/app-debug.apk
```

JVM tests cover event/reward/template logic, session cancellation and stale
callbacks, real SQLite migration, preference migration, and Compose navigation
and touch targets using Robolectric. Screenshots can be emitted by supplying
`screenshotDir` as a test JVM system property. The APK can compile without the
JNI artifact, but signing and LIVE require `libttl_sign_mobile.so` on the device.

```sh
./gradlew installDebug
adb shell am start -n com.example.ttlsigner/dev.nglmercer.tiktools.app.MainActivity
# Explicitly opt into native + network device integration tests:
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.liveIntegration=true

# Supertonic inference tests need the model installed on the device (install it from the
# Speech screen first) and their own opt-in flag. They synthesize Spanish, English,
# Japanese and the na fallback, check sample rate/length/finiteness/non-silence, exercise
# repeated inference, voice changes, chunking, presets, and engine reload/shutdown.
./gradlew connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.supertonicIntegration=true
```

Reference-parity tooling against the official Python implementation lives in
`scripts/supertonic/parity/`. It injects a fixed noise tensor into both implementations,
because Kotlin `Random` and NumPy’s generator never produce the same stream, and then
compares normalized text, text ids, mask, predicted duration and the final waveform.
Usage and tolerances are documented in that directory’s `README.md`.

Host checks do not establish real-device LIVE connectivity or ONNX audio
playback, and they say nothing about perceived speech quality. Those require the
native artifact, a device, and listening checks — the instrumentation tests above
verify structure, not naturalness.
