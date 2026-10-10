"""Rebuild selected EMA FP32 release from original PyTorch using genuine Optimum.

Never consumes old Actions artifacts. Applies ORT Extended then OnnxSlim.
Validates full Turkish voice and compares stage/timing against exported FP32.
"""
import json
import shutil
import sys
from pathlib import Path

import onnx
import onnxruntime as ort
import onnxslim
import torch
from ema_lightning.decoder import load_decoder
from ema_lightning.model import load_acoustic
from huggingface_hub import hf_hub_download

from optimize_matrix import BASE, STAGES, SOURCE, REV, make_sessions, verify, bench, digest, options

OUT=Path("dist/ema-selected")
OUT.mkdir(parents=True,exist_ok=True)

def main():
    torch.set_num_threads(4)
    acoustic=load_acoustic(hf_hub_download(SOURCE,"ema.pt",revision=REV),torch.device("cpu"))
    decoder=load_decoder(hf_hub_download(SOURCE,"decoder.pt",revision=REV),torch.device("cpu"))
    assert all((BASE/(s+".onnx")).is_file() for s in STAGES)
    models={}
    for stage in STAGES:
        source=BASE/(stage+".onnx")
        intermediate=OUT/(stage+".extended.onnx")
        target=OUT/(stage+".onnx")
        ort.InferenceSession(str(source),sess_options=options("extended",target=intermediate),
                             providers=["CPUExecutionProvider"])
        optimized=onnxslim.slim(str(intermediate))
        onnx.save(optimized,str(target))
        onnx.checker.check_model(str(target),full_check=True)
        models[stage]={"path":str(target),"sha256":digest(target),"bytes":target.stat().st_size}
        intermediate.unlink()
    original={"files":{s:{"path":str(BASE/(s+".onnx"))} for s in STAGES}}
    selected={"files":models}
    baseline_sess=make_sessions("baseline",original)
    selected_sess=make_sessions("selected",selected)
    baseline_quality=verify("baseline",baseline_sess,acoustic,decoder)
    selected_quality=verify("selected",selected_sess,acoustic,decoder)
    if selected_quality["min_waveform_snr_db"] < 35:
        raise AssertionError("Reference waveform fidelity gate failed")
    baseline_times=bench(baseline_sess,acoustic,decoder)
    selected_times=bench(selected_sess,acoustic,decoder)
    summary={"recipe":"Optimum FP32 -> ORT Extended -> OnnxSlim","source_commit":REV,
             "precision":"FP32","models":models,
             "baseline_quality":baseline_quality,"selected_quality":selected_quality,
             "baseline_timings_ms":baseline_times,"selected_timings_ms":selected_times}
    (OUT/"manifest.json").write_text(json.dumps(summary,indent=2,ensure_ascii=False))
    shutil.copyfile(BASE/"vocab.json",OUT/"vocab.json")
    print("[PASS] Selected FP32 rebuilt from original source and independently validated.",flush=True)
    for stage in STAGES:
        print(stage,models[stage]["bytes"],flush=True)

if __name__=="__main__":
    main()
