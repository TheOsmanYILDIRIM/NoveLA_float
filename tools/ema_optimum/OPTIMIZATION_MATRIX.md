# FP32 optimization benchmark

Source: original `EMA Lightning` weights and the previously verified
`feature/ema-optimum-community` Optimum export (Actions run 38004682817).

The GitHub Actions workflow `ema-optimization.yml` fetches the known-good
model and independently creates these non-quantized candidates:

| Candidate | What changes |
|---|---|
| baseline | Original verified Optimum FP32 ONNX |
| slim | OnnxSlim shape/graph simplification |
| basic | ONNX Runtime Basic graph optimizations |
| extended | ONNX Runtime Extended graph optimizations (operator fusions) |
| all | ORT All graph optimizations (may be machine-specific) |
| slim_extended | OnnxSlim followed by ORT Extended |
| ort_extended | ORT-format serialized Extended graph |
| CPU threads 1/2/4/8 | Independent runtime scheduling setting; same baseline weights |

For every candidate that opens correctly, the workflow checks Turkish
normalization, original EMA seeded acoustic noise and streaming-window
48-kHz audio against PyTorch. The tests run short speech, numbers/dates,
and a novel passage. It measures graph bytes, SHA256, waveform SNR,
sample length, approximate session RSS delta and per-stage timing at
three tensor shapes. Seven post-warmup iterations are summarized by
median. Candidates failing a stage or quality gate are marked rejected.

**Limitations:** Real Android XNNPACK, NNAPI, ARM64 timings, phone power and
thermal tests cannot be inferred from x86_64 Linux runners and remain
unmeasured. These optimizations do not change FP32 precision, but floating
point operation reorderings may change results; the waveform gate catches
large differences. CPU-thread benchmarks are settings, not separate ONNX
models. Memory deltas are observational, not isolated peak RSS.

Model artifacts are stored by Actions and the latest status/error log is
committed automatically to:
`reports/ema-optimization/LATEST.md` and `latest.log`.
Do not merge or select a winning model solely because graph optimization
completed; require numerical parity and measured speed/size improvement.
