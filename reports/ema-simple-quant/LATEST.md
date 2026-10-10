# EMA Lightning: FP32 / INT8 / INT4

- Run: https://github.com/TheOsmanYILDIRIM/NoveLA_float/actions/runs/38036902233
- Linux x86_64 ONNX Runtime CPU results (not phone benchmarks).
- INT8/INT4: eligible MatMul weights only; convolution weights remain FP32.
- Full output: [latest.log](latest.log)

| Candidate | Result | Model MB | Text ms | Sound ms | Decoder ms | Minimum waveform SNR |
|---|---|---:|---:|---:|---:|---:|
| FP32 | passed | 34.767 | 1.236 | 21.326 | 93.128 | 83.978 |
| INT8 | failed | — | — | — | — | not passed |
| INT4 | rejected_quality | 19.132 | 1.103 | 26.323 | 92.457 | not passed |

## Failures

- **int8:** Fail: [ONNXRuntimeError] : 1 : FAIL : Load model from dist/ema-simple-quant/int8/sound_stage.preprocessed.onnx failed:Node (/t_embed/mlp/mlp.0/Gemm_MatMul) Op (MatMul) [ShapeInferenceError] Incompatible dimensions for matrix multiplication
- **int4:** AssertionError: Text-stage duration rounding changed the word/frame timeline
- **int4:** AssertionError: Text-stage duration rounding changed the word/frame timeline
- **int4:** AssertionError: Text-stage duration rounding changed the word/frame timeline

Raw numeric results: [comparison.json](comparison.json).
No quantized model should replace FP32 without validated quality and Android CPU testing.
