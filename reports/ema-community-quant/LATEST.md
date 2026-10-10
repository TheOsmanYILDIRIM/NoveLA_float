# EMA Lightning — ONNX Community quantizer comparison

- Actions: https://github.com/TheOsmanYILDIRIM/NoveLA_float/actions/runs/38038449145
- Text Stage and Decoder stay FP32; Sound Stage weight-only quantization
- Platform: Linux x86-64 ONNX Runtime CPU; **not Android**
- See [latest.log](latest.log) for diagnostics

| Candidate | Status | Total MB | Sound ms | Minimum waveform SNR |
|---|---|---:|---:|---:|
| FP32 | passed | 34.767 | 21.72 | 83.978 |
| INT8 | failed | 23.36 | — | NOT VERIFIED |
| Q4 | rejected_quality | 21.855 | 24.029 | NOT VERIFIED |
| NF4 | rejected_quality | 21.581 | 27.186 | NOT VERIFIED |

## Problems and rejection reasons

- **INT8:** Fail: [ONNXRuntimeError] : 1 : FAIL : Load model from dist/ema-community-quant/int8/sound_stage.onnx failed:Node (/t_embed/mlp/mlp.0/Gemm_MatMul) Op (MatMul) [ShapeInferenceError] Incompatible dimensions for matrix multiplication
- **Q4:** AssertionError: Latents differ excessively: SNR=3.41 dB
- **Q4:** AssertionError: Latents differ excessively: SNR=4.95 dB
- **Q4:** AssertionError: Latents differ excessively: SNR=3.53 dB
- **NF4:** AssertionError: Latents differ excessively: SNR=4.57 dB
- **NF4:** AssertionError: Latents differ excessively: SNR=4.36 dB
- **NF4:** AssertionError: Latents differ excessively: SNR=2.17 dB

[Raw measured data](comparison.json)

FP32 unchanged for Text and Decoder; graph size savings relate only to quantized eligible Sound Stage MatMuls.
No model promoted to Android until native ARM64 testing.
