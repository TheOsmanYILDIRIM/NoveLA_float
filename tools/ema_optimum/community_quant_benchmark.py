"""ONNX Community-style ready quantizers, intentionally isolated to SOUND stage.

FP32 text+decoder prevent quantized duration changes and keep vocoder intact.
INT8 uses ORT dynamic quantize, Q4 MatMulNBits, NF4 MatMulBnb4.
Only constant-weight MatMul nodes (2D initializer) are selected. Source is
rebuilt from the original PyTorch checkpoint, no prior ONNX artifact.
"""
import gc
import json
import shutil
import statistics
import time
from pathlib import Path

import numpy as np
import onnx
import onnxruntime as ort
import torch
from ema_lightning.decoder import load_decoder
from ema_lightning.model import load_acoustic
from ema_lightning.frontend import Frontend
from ema_lightning.engine import Engine
from huggingface_hub import hf_hub_download
from optimize_matrix import SOURCE, REV, STAGES, options
from quality import synthesize_piece

BASE=Path("dist/ema-selected")
OUT=Path("dist/ema-community-quant")
CASES=(("Merhaba! Bugün hava güzel, değil mi?",1.0,42),
       ("2026 yılında saat 14.30'da, bin iki yüz elli lira ödendi.",1.2,1729),
       ("Kapıyı araladı ve yağmurlu sokağa baktı.",1.0,53))


def eligible_nodes(path):
    graph=onnx.load(str(path)).graph
    initializers={i.name:i for i in graph.initializer}
    return [n.name for n in graph.node if n.op_type=="MatMul"
            and len(n.input)==2 and n.input[1] in initializers
            and len(initializers[n.input[1]].dims)==2]


def export_sound(candidate, source, target):
    nodes=eligible_nodes(source)
    if not nodes:
        raise RuntimeError("No static MatMul weights found; refusing false quantization claim")
    if candidate=="int8":
        from onnxruntime.quantization import QuantType, quantize_dynamic
        # No Gemm graph rewriting; only known-safe existing MatMul weights.
        quantize_dynamic(str(source),str(target),nodes_to_quantize=nodes,
                         op_types_to_quantize=["MatMul"],weight_type=QuantType.QInt8,
                         extra_options={"DefaultTensorType":onnx.TensorProto.FLOAT})
    elif candidate=="q4":
        from onnxruntime.quantization.matmul_nbits_quantizer import (
            MatMulNBitsQuantizer,DefaultWeightOnlyQuantConfig)
        cfg=DefaultWeightOnlyQuantConfig(block_size=64,is_symmetric=True,
                                         op_types_to_quantize=("MatMul",),bits=4)
        converter=MatMulNBitsQuantizer(onnx.load(str(source)),
                                      nodes_to_include=nodes,algo_config=cfg)
        converter.process()
        converter.model.save_model_to_file(str(target),use_external_data_format=False)
    elif candidate=="nf4":
        from onnxruntime.quantization.matmul_bnb4_quantizer import MatMulBnb4Quantizer
        # This quantizer handles all 2D constant MatMuls; inputs beyond the
        # vetted node list must be excluded if new model graphs are introduced.
        graph=onnx.load(str(source))
        exclusion=[n.name for n in graph.graph.node if n.op_type=="MatMul"
                   and n.name not in nodes]
        converter=MatMulBnb4Quantizer(graph,MatMulBnb4Quantizer.NF4,64,
                                     nodes_to_exclude=exclusion)
        converter.process()
        converter.model.save_model_to_file(str(target),use_external_data_format=False)
    else:
        raise ValueError(candidate)
    onnx.checker.check_model(str(target))
    result=onnx.load(str(target))
    changed=sum(n.op_type in ("MatMulInteger","MatMulNBits","MatMulBnb4",
                              "DynamicQuantizeLinear") for n in result.graph.node)
    # NF4 operator is MatMulBnb4; make check fail if no quantized op was introduced.
    if changed==0:
        raise AssertionError("No quantized MatMul operator created")
    return {"eligible":len(nodes),"quantized_ops":changed}


def make_sessions(folder):
    return {s:ort.InferenceSession(str(folder/(s+".onnx")),
                                  sess_options=options("disable",4),
                                  providers=["CPUExecutionProvider"])
            for s in STAGES}


def quality_test(sessions,model,decoder):
    engine=Engine(model,decoder,torch.device("cpu"))
    frontend=Frontend(model.vocab)
    checks=[]
    for text,speed,seed in CASES:
        piece=engine.piece(frontend(text),0.,seed)
        try:
            out=synthesize_piece(engine,piece,speed,sessions)
            checks.append({"text":text,"status":"passed",
                           "waveform_snr_db":out["waveform_snr_db"],
                           "latent_snr_db":out["latents_snr_db"]})
        except Exception as error:
            checks.append({"text":text,"status":"rejected",
                           "reason":f"{type(error).__name__}: {error}"})
    return checks


def timing(sessions,model,decoder):
    from ema_config import config_for
    result={}
    for stage in STAGES:
        cfg=config_for(stage,model,decoder)
        inputs={k:v.numpy() for k,v in cfg.generate_dummy_inputs(
            batch_size=1,sequence_length=32,nb_max_frames=64).items()}
        for _ in range(3):
            sessions[stage].run(None,inputs)
        times=[]
        for _ in range(9):
            start=time.perf_counter()
            sessions[stage].run(None,inputs)
            times.append((time.perf_counter()-start)*1000)
        result[stage]=round(statistics.median(times),3)
    return result


def main():
    OUT.mkdir(parents=True,exist_ok=True)
    torch.set_num_threads(4)
    model=load_acoustic(hf_hub_download(SOURCE,"ema.pt",revision=REV),torch.device("cpu"))
    decoder=load_decoder(hf_hub_download(SOURCE,"decoder.pt",revision=REV),torch.device("cpu"))
    report={"model_source":SOURCE,"model_revision":REV,"platform":"x86_64 Linux CPU",
            "scope":"text_stage FP32, sound_stage optionally quantized, decoder FP32",
            "candidates":{}}
    for name in ("fp32","int8","q4","nf4"):
        result={"status":"pending"}
        report["candidates"][name]=result
        folder=OUT/name
        folder.mkdir(exist_ok=True)
        try:
            for stage in STAGES:
                if stage!="sound_stage" or name=="fp32":
                    shutil.copyfile(BASE/(stage+".onnx"),folder/(stage+".onnx"))
            if name!="fp32":
                result["conversion"]=export_sound(name,BASE/"sound_stage.onnx",
                                                  folder/"sound_stage.onnx")
            result["model_bytes"]={s:(folder/(s+".onnx")).stat().st_size for s in STAGES}
            result["total_mb"]=round(sum(result["model_bytes"].values())/1e6,3)
            sessions=make_sessions(folder)
            result["quality"]=quality_test(sessions,model,decoder)
            result["median_ms"]=timing(sessions,model,decoder)
            result["status"]="passed" if all(t["status"]=="passed" for t in result["quality"]) else "rejected_quality"
            del sessions
            gc.collect()
        except Exception as error:
            result["status"]="failed"
            result["error"]=f"{type(error).__name__}: {error}"
        print(f"[{name}] {result['status']} {result.get('total_mb','?')} MB",flush=True)
        (OUT/"comparison.json").write_text(json.dumps(report,indent=2,ensure_ascii=False))
    if report["candidates"]["fp32"]["status"]!="passed":
        raise RuntimeError("FP32 reference failed")
    print("[COMPLETE] Ready quantizer comparisons are saved, failures preserved.",flush=True)


if __name__=="__main__":
    main()
