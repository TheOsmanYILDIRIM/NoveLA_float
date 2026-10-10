# EMA PCM Diagnostics

Standalone Android application ID `my.novela.ematest`; no book reader or MediaSession. This test branch does not modify the main NoveLA app.

The app runs the same `EmaOnnxEngine.kt` implementation as the Clone, using the same four FP32 assets. It can synthesize one short Turkish sentence, save the **raw generated** 48 kHz mono PCM16 WAV, and play that exact buffer with AudioTrack. Comparing the exported WAV to what the speaker plays separates inference errors from playback errors.

Model files are not committed. Copy verified `text_stage.onnx`, `sound_stage.onnx`, `decoder.onnx`, and `vocab.json` to `app/src/main/assets/ema/` before building. CI must reconstruct these assets using the pinned exporter. Do not use test audio as proof of correct voice quality without listening/reference comparison.
