# EMA Lightning — NoveLA native offline TTS integration

Status: **Android APK integration candidate, not device-validated**.

This branch keeps the existing NoveLA TextToSpeechManager, MediaSession and reader UI.
EMA runs only through the app-internal `EmaLocalPlayer` PCM AudioTrack route.
The separate Android `TextToSpeechService` and system engine manifest registration
were removed on 2026-11-06 at the user's request. Installed Android system
TTS engines remain independently available in NoveLA's voice picker.
The EMA model is advertised only if the four bundled assets are present. Symbol/control characters are removed from the spoken copy without modifying displayed text; system TTS preserves UTF-16 character offsets for range callbacks.

## Production model

The verified, nonquantized **FP32** EMA Lightning model is reconstructed
from the original `canberkkkkkk/ema-lightning` weights at pinned revision
`7a6ba1ad216bb2f1da9863f80ac8770a6a807632`.

The first cache-miss build uses the validated pinned converter:
Optimum ONNX export → ORT Extended → OnnxSlim → stage and reference-waveform
validation → SHA256 verification → Android APK assets. Subsequent Clone and debug
builds restore the same selected FP32 bundle through GitHub Actions cache, verify
the pinned model manifest, file sizes and SHA256, and package the APK without
re-running the converter. The cache is **not permanent**: if GitHub evicts it,
the verified model must be regenerated once. It does not use INT8,
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
- The direct PCM player prebuffers the first phrase and generates subsequent phrases in a producer coroutine. Stop invalidates pending generation and releases AudioTrack.

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
- Android system voice discovery, direct EMA PCM synthesis, buffering, pause/resume and music ducking require physical-device tests.
- The CI run checks model reproduction and APK compilation, **not acoustic
  quality on Android**.

## Verification

See `reports/ema-android/LATEST.md` and `build.log` for the last triggered
workflow result. APK builds are retained for 7 days as GitHub Actions artifacts.

Do not merge into `default` until the integration compiles and at least
one real-device spoken sentence is heard correctly.

## Built-in EMA diagnostic export (2026-11-06)
The reader's TTS control panel contains **Copy EMA diagnostic log**. After reproducing a stall, tap it and paste the text in the chat; no adb is required. Logs survive reader restarts and are capped to 96 KiB in app-private storage. They contain no text of the book. Key events: READER_QUEUE, WAIT_PREVIOUS, PREVIOUS_DONE, MODEL_TEXT_BEGIN/DONE, MODEL_SOUND_BEGIN/DONE, MODEL_DECODE_BEGIN/DONE, AUDIO_START/DONE, PCM_FIRST_TIMEOUT/PCM_NEXT_TIMEOUT, READER_FINISHED and REACHED_CHAPTER_END.
The live player no longer awaits speculative lookahead. PCM waits and engine lock acquisition are bounded. On a long native inference, the reader may skip the failed phrase; native ONNX itself cannot be forcibly interrupted safely. Inspect stage timing before changing model windows, text cleaning or audio quality.
