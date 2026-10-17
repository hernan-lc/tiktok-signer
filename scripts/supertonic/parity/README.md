# Supertonic 3 reference-parity harness

Development-only tooling that compares the Android (Kotlin/ONNX Runtime) port against the official
Python reference — `supertone-oss-archive/supertonic`, `py/helper.py`. Nothing here ships in the APK
and no Python/Node runtime is introduced into the Android app.

## Why injected noise

The reference calls `np.random.randn` and the Kotlin port uses `kotlin.random.Random`. Those are
different generators, so their streams never agree and a direct comparison of the wave end-to-end
would always fail. The harness therefore **generates the noise once in Python, saves it, and feeds the
same tensor to both implementations**. With identical noise, duration, text embeddings, every
vector-estimator step and the vocoder output become deterministic, so a diverging tensor shows up as a
diverging waveform.

## Generate fixtures

```bash
git clone https://github.com/supertone-oss-archive/supertonic /tmp/supertonic
python3 -m pip install onnxruntime numpy

cd /path/to/tiktok-signer/scripts/supertonic/parity
PYTHONPATH=/tmp/supertonic/py python3 reference.py \
  --onnx-dir /tmp/supertonic/assets/onnx \
  --voice-style /tmp/supertonic/assets/voice_styles/F1.json \
  --text "Hola, ¿cómo estás?" --lang es --steps 8 \
  --out /tmp/supertonic-parity/es-8step
```

Repeat for each case you care about (`en`, `ja`, `na`, 5 vs 8 steps). Keep the pinned model revision:
use the same `onnx/` files the app downloads (revision
`3cadd1ee6394adea1bd021217a0e650ede09a323` of `Supertone/supertonic-3`).

## Run the comparison

```bash
adb push /tmp/supertonic-parity /sdcard/Download/supertonic-parity
cd android
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.supertonicParity=true
```

The test reads `/sdcard/Download/supertonic-parity/<case>/` and skips (JUnit `assumeTrue`) when the
fixtures or the model are absent, so it never breaks an ordinary CI run.

## What is compared, and tolerances

| Stage | Comparison | Tolerance |
|---|---|---|
| Normalized text | exact string equality | exact |
| `text_ids` | element-wise | exact |
| `text_mask` | element-wise | exact |
| Predicted duration | absolute, per element | `1e-3` s |
| Injected latent mask | element-wise | exact |
| Final waveform | max absolute difference | `2e-3` (float32 accumulation across 8 estimator steps + vocoder) |

Text embeddings, the latent mask and each vector-estimator step are not dumped individually: with
identical injected noise the waveform is a deterministic function of all of them, so comparing the
waveform at `2e-3` covers those stages. If a test fails on the waveform, dump `text_emb` and the
per-step latents in `reference.py` to bisect.

## Listening checks

Numerical parity is necessary but not sufficient — the model can match exactly and still be perceived
as wrong for a given voice/step count. Listening checks are manual; see the "Audio diagnostics"
section of `android/README.md`. Export both diagnostic WAVs (Float32 and PCM16) from the Speech screen
with **Export WAV for debugging** enabled and compare against the reference `output.wav`.
