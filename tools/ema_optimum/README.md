# EMA Lightning → Hugging Face Optimum ONNX (NoveLA experiment)

This is the **actual Optimum exporter**, not a renamed torch.onnx exporter
script and not a copy of the third-party web conversion.

- `ema_config.py`: custom `optimum.exporters.onnx.OnnxConfig` with
  stage-specific inputs, outputs, symbolic dimensions and example tensors.
- `export.py`: loads **original** EMA Lightning 1.0.4 checkpoints from
  `canberkkkkkk/ema-lightning`, calls
  `optimum.exporters.onnx.export(..., dynamo=True)` for each of the three
  original model stages, and checks additional ONNX Runtime shapes.
- `quality.py`: uses EMA's **own Turkish Frontend, chunker, Engine, seeded
  noise and 48 kHz contextual-window decoder**. Compares the entire speech
  path (word timings, latents, waveforms) to the original PyTorch engine.
- `report.py`: creates the repo's persistent status and log output.
- `.github/ema-optimum-trigger.txt`: changing this file triggers CI.

## Why not simply run optimum-cli?

The ONNX Community Space uses Hugging Face Optimum for supported
Transformers/Diffusers model families. EMA Lightning 1.0.4 is neither: it
provides a custom PyTorch acoustic model and vocoder. An unmodified
`optimum-cli export onnx --model ...` cannot discover EMA's architecture.

Instead, this directory registers **explicit custom OnnxConfig instances**
and invokes the same supported Optimum programmatic exporter. The
preprocessing and temporal planner remain the original EMA Python
implementation for validation; they are not yet Android/Kotlin ports.

## Quality gate

All three FP32 models must pass multiple batch/sequence/frame shapes, matching
PyTorch within a stage-specific tolerance. Two Turkish input samples pass
through the **original** number/date normalizer; their complete 48 kHz output
and seeded latents must exceed 35 dB signal-to-error ratio versus the
PyTorch reference, with matching frame lengths. This is a conservative
numerical equivalence gate, **not proof of perceptual equivalence (MOS/WER)**.
No FP16 or INT8 optimization runs before the FP32 reference is verified.

## Artifacts and logs

GitHub Actions run `EMA Optimum Community — full quality parity` uploads
`ema-optimum-onnx-fp32-quality-verified` if and only if all tests pass.
Human-readable status and complete log are committed to:

- `reports/ema-optimum/LATEST.md`
- `reports/ema-optimum/latest.log`

Model files and manifest go to Actions artifacts; do not commit heavy files
to this repository. The pipeline logs the source Hub commit and SHA256 values
for each original checkpoint and converted ONNX file.

**Scope**: Conversion and test automation only. This does not yet change
NoveLA's Android TTS engine.
