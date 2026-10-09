# EMA Lightning ONNX: Optimum vs fr0stb1rd

- Job outcome: **success**
- Run: https://github.com/TheOsmanYILDIRIM/NoveLA_float/actions/runs/38006017871
- Executed on an x86_64 GitHub Actions CPU runner, **not Android**.
- WAV samples: workflow artifact `ema-head-to-head-wav` (if generated).

## Model sizes

| Stage | Our Optimum | fr0stb1rd |
|---|---:|---:|
| text_stage | 4,808,378 bytes | 4,926,920 bytes |
| sound_stage | 18,134,771 bytes | 20,001,912 bytes |
| decoder | 12,037,950 bytes | 12,468,757 bytes |

## Numerical parity vs original PyTorch

| Input | Our SNR | fr0stb1rd SNR | Our RMSE | fr0stb1rd RMSE |
|---|---:|---:|---:|---:|
| greeting | 91.276 dB | 91.290 dB | 0.00000141 | 0.00000141 |
| numbers | 90.248 dB | 87.014 dB | 0.00000165 | 0.00000239 |
| novel | 82.386 dB | 83.934 dB | 0.00000533 | 0.00000446 |

The same original EMA Turkish normalizer and seeded noise were used for both exports.
Numerical parity is not MOS, WER, or perceptual quality evidence.
