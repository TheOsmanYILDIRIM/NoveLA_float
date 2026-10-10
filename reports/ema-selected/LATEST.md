# EMA selected model and artifact cleanup

- Run: https://github.com/TheOsmanYILDIRIM/NoveLA_float/actions/runs/38035552077
- Old Actions artifacts: see [artifact-cleanup.log](artifact-cleanup.log)
- Source Optimum conversion: **success**
- Extended -> Slim rebuild: **success**
- Experimental quantization: **success**

## Verified FP32 production candidate

| Stage | Bytes | SHA256 |
|---|---:|---|
| text_stage | 4,808,079 | `85c7a5a5946dd1257ef98237040e1cc8ded61049d9878fc1f3a89b0dac2d430a` |
| sound_stage | 17,921,918 | `65c3746cc64a17089a534ad6df5f492ace04a14c958da93f3c99c581daadbf94` |
| decoder | 12,036,684 | `8a907ccbe35945030efe09d3ae886861e546882332ee8a3786167ccaa18ea42d` |

- Minimum original voice SNR: 83.978 dB
- No quantization in production candidate.

## Experimental quantization

| Format | Status | Note |
|---|---|---|
| nvfp4 | not_attempted | NVIDIA NVFP4 needs target Blackwell hardware + TensorRT/ModelOpt; generic Android ARM64 CPU lacks a validated NVFP4 execution path. |
| int8_matmul | rejected | RuntimeError: Unable to find data type for weight_name='/text/conv.0/pw2/MatMul_output_0'. shape_inference failed to return a type probably this node is from a different domain or using an input produced by such an operator. This may happen if you quantize a model already quantized. You may use extra_options `DefaultTensorType` to indicate the default weight type, usually `onnx.TensorProto.FLOAT`. |
| int4_matmul_nbits | rejected | ModuleNotFoundError: No module named 'onnxruntime.quantization.matmul_4bits_quantizer' |

## Runtime constraints

NVFP4 is for supported NVIDIA Blackwell/TensorRT paths, **not an established Android ARM64 CPU inference format**.
INT8 / INT4 are not selected without passing waveform parity and device performance validation.

