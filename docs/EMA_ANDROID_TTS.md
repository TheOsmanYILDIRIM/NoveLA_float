# EMA Lightning — NoveLA native offline TTS integration

Status: **Android APK integration candidate, not device-validated**.

This branch preserves the original NoveLA application and its existing
`TextToSpeechManager`/MediaSession/UI. It registers an optional
`EmaTextToSpeechService` in the app manifest; the service implements the
standard Android TTS API, so the existing engine and voice selector can
discover `ema-lightning-tr-fp32` when the model is bundled.

## Production model

The verified, nonquantized **FP32** EMA Lightning model is reconstructed
from the original `canberkkkkkk/ema-lightning` weights at pinned revision
`7a6ba1ad216bb2f1da9863f80ac8770a6a807632`.

The CI workflow uses the code from the project's already validated
`feature/ema-selected-quantization` branch:
Optimum ONNX export → ORT Extended → OnnxSlim → stage and reference-waveform
validation → SHA256 verification → Android APK assets. It does not use INT8,
Q4, NF4, or the earlier unsuccessful quantization experiments.

Files packaged into `tooling/text_to_speech/src/main/assets/ema/`:
`text_stage.onnx`, `sound_stage.onnx`, `decoder.onnx`, `vocab.json`.

The model is absent from git history, but the CI-built APK bundles all the
assets. A local IDE build without model assets will not advertise the voice.

## Runtime

- Native Android/Java ONNX Runtime via `onnxruntime-android`; no WebView or
  remote inference.
- Three graph stages with deterministic-seeded noise, bounded paragraph
  chunks, 25-Hz frame planner, 1-second first window / 4-second later windows,
  8-frame decoder context, 48-kHz PCM16 output.
- The previous Android/system voices remain available. If model assets are
  absent, the EMA voice is not advertised.
- `onStop` interrupts generation and callback delivery.

## Known gaps before shipping

- The Kotlin text frontend preserves Turkish I/İ case and the model alphabet,
  **but is not a port of the original Python normalizer-tr package**; numbers,
  dates, abbreviations and punctuation need mobile voice testing.
- Android generated noise uses `java.util.Random`; unlike the seeded PyTorch
  RNG it need not produce identical sound samples. This does not change
  the downloaded model weights.
- Exact Android ARM64 performance, cold-start delay, Bluetooth playback,
  speed controls, lock-screen controls, word highlighting and interruptions
  must be checked on a physical phone.
- Android service voice discovery and full synthesis require device tests.
- The CI run checks model reproduction and APK compilation, **not acoustic
  quality on Android**.

## Verification

See `reports/ema-android/LATEST.md` and `build.log` for the last triggered
workflow result. APK builds are retained for 7 days as GitHub Actions artifacts.

Do not merge into `default` until the integration compiles and at least
one real-device spoken sentence is heard correctly.
