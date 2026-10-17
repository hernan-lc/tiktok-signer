#!/usr/bin/env python3
"""Generate reference-fixture tensors from the official Supertonic Python implementation.

This is a **development-only** tool. It lives outside the Android app and is never shipped; it exists
so the Kotlin port can be compared against the reference that the project treats as authoritative
(``supertone-oss-archive/supertonic``, ``py/helper.py``).

The script deliberately injects a pre-generated noise tensor instead of relying on ``np.random.randn``
so the comparison is deterministic: Kotlin ``Random`` and NumPy's global RNG are different generators
and their streams will never match. Both sides consume the same saved noise.

Usage
-----
    git clone https://github.com/supertone-oss-archive/supertonic /tmp/supertonic
    python3 -m pip install onnxruntime numpy

    PYTHONPATH=/tmp/supertonic/py python3 reference.py \
        --onnx-dir  /tmp/supertonic/assets/onnx \
        --voice-style /tmp/supertonic/assets/voice_styles/F1.json \
        --text "Hola, ¿cómo estás?" --lang es --steps 8 \
        --out /tmp/supertonic-parity/es-8step

    # Push the fixtures to a device, then run the gated instrumentation test:
    adb push /tmp/supertonic-parity/es-8step /sdcard/Download/supertonic-parity/es-8step
    ./gradlew :app:connectedDebugAndroidTest \
        -Pandroid.testInstrumentationRunnerArguments.supertonicParity=true
"""

from __future__ import annotations

import argparse
import json
import pathlib

import numpy as np

from helper import UnicodeProcessor, get_latent_mask, load_text_to_speech, load_voice_style


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--onnx-dir", required=True, help="Directory holding the ONNX package")
    parser.add_argument("--voice-style", required=True, help="Path to voice_styles/<name>.json")
    parser.add_argument("--text", required=True)
    parser.add_argument("--lang", default="na", help="ISO code, or 'na' for the fallback")
    parser.add_argument("--steps", type=int, default=8, help="Denoising steps (reference default 8)")
    parser.add_argument("--speed", type=float, default=1.05)
    parser.add_argument("--seed", type=int, default=1234, help="Seed for the injected noise")
    parser.add_argument("--out", required=True, help="Output fixture directory")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    out = pathlib.Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    processor = UnicodeProcessor(str(pathlib.Path(args.onnx_dir) / "unicode_indexer.json"))
    tts = load_text_to_speech(args.onnx_dir)
    style = load_voice_style([args.voice_style], verbose=True)

    processed = processor._preprocess_text(args.text, args.lang)
    text_ids, text_mask = processor([args.text], [args.lang])

    duration = tts.dp_ort.run(
        None, {"text_ids": text_ids, "style_dp": style.dp, "text_mask": text_mask}
    )[0]
    duration = (duration / args.speed).astype(np.float32)

    sample_rate = tts.sample_rate
    chunk_size = tts.base_chunk_size * tts.chunk_compress_factor
    latent_dim = tts.ldim * tts.chunk_compress_factor
    latent_len = int(np.floor((float(duration.max()) * sample_rate + chunk_size - 1) / chunk_size))

    rng = np.random.default_rng(args.seed)
    noise = rng.standard_normal((1, latent_dim, latent_len)).astype(np.float32)

    wav_lengths = (duration * sample_rate).astype(np.int64)
    latent_mask = get_latent_mask(wav_lengths, tts.base_chunk_size, tts.chunk_compress_factor)
    latent = noise * latent_mask

    total_step = np.array([args.steps], dtype=np.float32)
    for step in range(args.steps):
        current_step = np.array([step], dtype=np.float32)
        latent = tts.vector_est_ort.run(
            None,
            {
                "noisy_latent": latent,
                "text_emb": tts.text_enc_ort.run(
                    None,
                    {"text_ids": text_ids, "style_ttl": style.ttl, "text_mask": text_mask},
                )[0],
                "style_ttl": style.ttl,
                "latent_mask": latent_mask,
                "text_mask": text_mask,
                "current_step": current_step,
                "total_step": total_step,
            },
        )[0]

    vocoder_out = tts.vocoder_ort.run(None, {"latent": latent})[0]
    wav = vocoder_out[0, : int(sample_rate * float(duration[0]))].astype(np.float32)

    noise.tofile(out / "noise.f32")
    duration.astype(np.float32).tofile(out / "duration.f32")
    wav.astype(np.float32).tofile(out / "wav.f32")

    (out / "meta.json").write_text(
        json.dumps(
            {
                "input": args.text,
                "processed": processed,
                "lang": args.lang,
                "voice": pathlib.Path(args.voice_style).stem,
                "steps": args.steps,
                "speed": args.speed,
                "sampleRate": sample_rate,
                "seed": args.seed,
                "latentDim": latent_dim,
                "latentLen": latent_len,
                "durationSamples": int(wav.size),
                "textIds": [int(v) for v in text_ids[0]],
                "textMask": [int(v) for v in text_mask[0][0]],
            },
            indent=2,
            ensure_ascii=False,
        ),
        encoding="utf-8",
    )
    print(f"Wrote fixtures for {args.lang!r} ({latent_dim}x{latent_len} latent, {wav.size} samples)")


if __name__ == "__main__":
    main()
