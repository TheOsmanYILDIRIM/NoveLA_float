"""Exploratory quantization candidates; fail closed, do not relabel INT4 as NVFP4.

INT8: ONNX Runtime dynamic MatMul weight quantization.
INT4: ONNX Runtime MatMulNBits (blockwise weight-only) if supported.
NVFP4 requires target compatible NVIDIA hardware and a calibrated ModelOpt/TensorRT
pipeline; it is not an ARM Android runtime path. We do not fake NVFP4.
"""
import json
from pathlib import Path

import onnxruntime as ort
from onnxruntime.quantization import QuantType, quantize_dynamic
from ema_lightning.decoder import load_decoder
from ema_lightning.model import load_acoustic
from huggingface_hub import hf_hub_download
import torch

from optimize_matrix import SOURCE, REV, STAGES, options, verify

SOURCE_DIR=Path("dist/ema-selected")
DEST=Path("dist/ema-quant-research")

def convert_int8(folder):
    for stage in STAGES:
        src=SOURCE_DIR/f"{stage}.onnx"
        dst=folder/f"{stage}.onnx"
        quantize_dynamic(src,dst,weight_type=QuantType.QInt8,op_types_to_quantize=["MatMul"])
    return {stage:str(folder/f"{stage}.onnx") for stage in STAGES}

def convert_int4(folder):
    from onnxruntime.quantization.matmul_4bits_quantizer import MatMul4BitsQuantizer, DefaultWeightOnlyQuantConfig
    import onnx
    for stage in STAGES:
        model=onnx.load(str(SOURCE_DIR/f"{stage}.onnx"))
        config=DefaultWeightOnlyQuantConfig(block_size=64, is_symmetric=True,
                                            accuracy_level=4)
        quantizer=MatMul4BitsQuantizer(model,algo_config=config)
        quantizer.process()
        quantizer.model.save_model_to_file(str(folder/f"{stage}.onnx"),use_external_data_format=False)
    return {stage:str(folder/f"{stage}.onnx") for stage in STAGES}

def main():
    DEST.mkdir(parents=True,exist_ok=True)
    acoustic=load_acoustic(hf_hub_download(SOURCE,"ema.pt",revision=REV),torch.device("cpu"))
    decoder=load_decoder(hf_hub_download(SOURCE,"decoder.pt",revision=REV),torch.device("cpu"))
    output={"nvfp4":{"status":"not_attempted",
            "reason":"NVIDIA NVFP4 needs target Blackwell hardware + TensorRT/ModelOpt; generic Android ARM64 CPU lacks a validated NVFP4 execution path."}}
    for method, fn in (("int8_matmul",convert_int8),("int4_matmul_nbits",convert_int4)):
        folder=DEST/method;folder.mkdir(exist_ok=True)
        try:
            paths=fn(folder)
            sessions={stage:ort.InferenceSession(path,sess_options=options("disable",4),
                             providers=["CPUExecutionProvider"]) for stage,path in paths.items()}
            checks=verify(method,sessions,acoustic,decoder)
            if checks["min_waveform_snr_db"] < 35:
                raise AssertionError("Full waveform quality gate <35 dB")
            output[method]={"status":"passed","quality":checks,
                             "bytes":{stage:Path(path).stat().st_size for stage,path in paths.items()}}
        except Exception as error:
            output[method]={"status":"rejected","error":f"{type(error).__name__}: {error}"}
        print(method,output[method]["status"],flush=True)
    (DEST/"research.json").write_text(json.dumps(output,indent=2,ensure_ascii=False))
    print("NVFP4 is documented but not misrepresented as a supported Android quantization.",flush=True)

if __name__=="__main__":
    main()
