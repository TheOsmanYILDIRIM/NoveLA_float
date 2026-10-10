# EMA Lightning: FP32 / INT8 / INT4

- Run: https://github.com/TheOsmanYILDIRIM/NoveLA_float/actions/runs/38036333671
- Linux x86_64 ONNX Runtime CPU results (not phone benchmarks).
- INT8/INT4: eligible MatMul weights only; convolution weights remain FP32.
- Full output: [latest.log](latest.log)

| Candidate | Result | Model MB | Text ms | Sound ms | Decoder ms | Minimum waveform SNR |
|---|---|---:|---:|---:|---:|---:|
| FP32 | passed | 34.767 | 0.974 | 16.039 | 77.262 | 83.594 |
| INT8 | failed | — | — | — | — | not passed |
| INT4 | failed | — | — | — | — | not passed |

## Failures

- **int8:** Fail: [ONNXRuntimeError] : 1 : FAIL : Load model from dist/ema-simple-quant/int8/sound_stage.onnx failed:Node (/t_embed/mlp/mlp.0/Gemm_MatMul) Op (MatMul) [ShapeInferenceError] Incompatible dimensions for matrix multiplication
- **int4:** ImportError: cannot import name 'MatMul4BitsQuantizer' from 'onnxruntime.quantization.matmul_nbits_quantizer' (/opt/hostedtoolcache/Python/3.12.15/x64/lib/python3.12/site-packages/onnxruntime/quantization/matmul_nbits_quantizer.py)

Raw numeric results: [comparison.json](comparison.json).
No quantized model should replace FP32 without validated quality and Android CPU testing.
