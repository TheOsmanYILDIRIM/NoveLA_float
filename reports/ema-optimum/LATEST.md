# EMA Lightning — Optimum / ONNX Community export

- **CI result:** `success`
- **Run:** https://github.com/TheOsmanYILDIRIM/NoveLA_float/actions/runs/38004682817
- **Commit:** `10671ad720c94bac962251136b68f2f978d5aae0`
- **UTC:** 2026-10-09T23:30:37.522042+00:00
- **Pipeline:** Genuine `optimum.exporters.onnx.export` with custom `OnnxConfig`.
- **Full job output:** [latest.log](latest.log)
- **Model binaries:** Actions artifacts (not committed to git).

## Source & precision

- Model: `canberkkkkkk/ema-lightning`
- Source revision: `7a6ba1ad216bb2f1da9863f80ac8770a6a807632`
- Precision: `FP32 (no quantization)`

## Stage validation

- `text_stage`: 4,808,378 bytes, 3 dynamic-shape tests, SHA256 `e94e099572f8e3c20cc67a8915b3982f30e4283d428a08dbf7fd579d734dc419`
- `sound_stage`: 18,134,771 bytes, 3 dynamic-shape tests, SHA256 `3c889f542c418d69c20392a3a3b3faebf19bc444601d5c490f6e3fdf3f17c4b2`
- `decoder`: 12,037,950 bytes, 3 dynamic-shape tests, SHA256 `dc270a590bd78cea14b49a3fb309bbbf5fb89d622720c9c35c17bc59ec161131`

## End-to-end Turkish speech quality

- `Merhaba! Bugün hava güzel, değil mi?` — 52 frames, latent SNR 99.056 dB, 48 kHz waveform SNR 88.256 dB
- `2026 yılında saat 14.30'da, bin iki yüz elli lira ödendi.` — 100 frames, latent SNR 85.344 dB, 48 kHz waveform SNR 74.239 dB

These are numerical comparisons against the original EMA engine, not MOS/WER listening scores.
