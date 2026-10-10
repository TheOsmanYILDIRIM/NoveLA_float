"""FP32 vs INT8 vs INT4: three-candidate CPU benchmark with original EMA audio parity.

Only MatMul weights are quantized. Unsupported Conv/ConvTranspose weights stay FP32.
Quantized graphs are never passed off as fully INT8/INT4. All methods run
with the same inputs and CPU provider and report actual graph size and speed.
"""
import gc
import json
import statistics
import time
from pathlib import Path

import numpy as np
import onnx
import onnxruntime as ort
import torch
from ema_lightning.decoder import load_decoder
from ema_lightning.frontend import Frontend
from ema_lightning.engine import Engine
from ema_lightning.model import load_acoustic
from huggingface_hub import hf_hub_download

from optimize_matrix import SOURCE, REV, STAGES, options, digest
from quality import synthesize_piece
from ema_config import config_for

BASE = Path("dist/ema-selected")
OUT = Path("dist/ema-simple-quant")
TESTS = (
    ("Merhaba! Bugün hava güzel, değil mi?", 1.0, 42),
    ("2026 yılında saat 14.30'da, bin iki yüz elli lira ödendi.", 1.2, 1729),
    ("Kapıyı araladı ve yağmurlu sokağa baktı.", 1.0, 53),
)


def constant_matmuls(path):
    model = onnx.load(str(path))
    constants = {x.name for x in model.graph.initializer}
    # Only quantize static MatMul weights; dynamic intermediate tensors stay float.
    return [node.name for node in model.graph.node
            if node.op_type == "MatMul" and len(node.input) > 1 and node.input[1] in constants]


def convert(method):
    folder = OUT / method
    folder.mkdir(parents=True, exist_ok=True)
    results = {}
    for stage in STAGES:
        src = BASE / (stage + ".onnx")
        dst = folder / (stage + ".onnx")
        nodes = constant_matmuls(src)
        if not nodes:
            import shutil
            shutil.copy2(src, dst)
        elif method == "int8":
            from onnxruntime.quantization import quantize_dynamic, QuantType
            quantize_dynamic(str(src), str(dst), weight_type=QuantType.QInt8,
                             op_types_to_quantize=["MatMul"], nodes_to_quantize=nodes,
                             extra_options={"DefaultTensorType": onnx.TensorProto.FLOAT})
        elif method == "int4":
            from onnxruntime.quantization.matmul_nbits_quantizer import (
                MatMul4BitsQuantizer, DefaultWeightOnlyQuantConfig)
            from onnxruntime.quantization.quant_utils import load_model_with_shape_infer
            config = DefaultWeightOnlyQuantConfig(block_size=64, is_symmetric=True,
                                                  accuracy_level=4, op_types_to_quantize=("MatMul",))
            quantizer = MatMul4BitsQuantizer(load_model_with_shape_infer(src),
                                            nodes_to_include=nodes, algo_config=config)
            quantizer.process()
            quantizer.model.save_model_to_file(str(dst), use_external_data_format=False)
        onnx.checker.check_model(str(dst))
        model = onnx.load(str(dst))
        qops = sum(node.op_type in ("MatMulInteger", "MatMulNBits", "DynamicQuantizeLinear")
                   for node in model.graph.node)
        results[stage] = {"path": str(dst), "bytes": dst.stat().st_size,
                          "sha256": digest(dst), "eligible_matmuls": len(nodes),
                          "quantized_operator_count": qops}
    return results


def load_sessions(files):
    return {stage: ort.InferenceSession(details["path"], sess_options=options("disable", 4),
                                      providers=["CPUExecutionProvider"])
            for stage, details in files.items()}


def test_quality(sessions, model, decoder):
    engine = Engine(model, decoder, torch.device("cpu"))
    front = Frontend(model.vocab)
    results = []
    for text, speed, seed in TESTS:
        piece = engine.piece(front(text), 0.0, seed)
        try:
            got = synthesize_piece(engine, piece, speed, sessions)
            results.append({"text": text, "status": "passed",
                            "waveform_snr_db": got["waveform_snr_db"],
                            "latent_snr_db": got["latents_snr_db"]})
        except Exception as e:
            results.append({"text": text, "status": "rejected",
                            "reason": f"{type(e).__name__}: {e}"})
    return results


def timings(sessions, model, decoder):
    result = {}
    for stage in STAGES:
        config = config_for(stage, model, decoder)
        feeds = {k: v.numpy() for k, v in config.generate_dummy_inputs(
            batch_size=1, sequence_length=32, nb_max_frames=64).items()}
        sess = sessions[stage]
        for _ in range(3):
            sess.run(None, feeds)
        measurements = []
        for _ in range(9):
            begin = time.perf_counter()
            sess.run(None, feeds)
            measurements.append((time.perf_counter() - begin) * 1000)
        result[stage] = round(statistics.median(measurements), 3)
    return result


def main():
    torch.set_num_threads(4)
    OUT.mkdir(parents=True, exist_ok=True)
    model = load_acoustic(hf_hub_download(SOURCE, "ema.pt", revision=REV), torch.device("cpu"))
    decoder = load_decoder(hf_hub_download(SOURCE, "decoder.pt", revision=REV), torch.device("cpu"))
    matrix = {}
    for method in ("fp32", "int8", "int4"):
        print("[TEST]", method, flush=True)
        entry = {"status": "pending"}
        matrix[method] = entry
        try:
            if method == "fp32":
                files = {stage: {"path": str(BASE/(stage+".onnx")),
                                 "bytes": (BASE/(stage+".onnx")).stat().st_size}
                         for stage in STAGES}
            else:
                files = convert(method)
            sessions = load_sessions(files)
            entry["files"] = files
            entry["size_mb"] = round(sum(f["bytes"] for f in files.values())/1e6, 3)
            entry["quality"] = test_quality(sessions, model, decoder)
            entry["stage_median_ms"] = timings(sessions, model, decoder)
            entry["status"] = ("passed" if all(x["status"] == "passed" for x in entry["quality"])
                               else "rejected_quality")
            del sessions
            gc.collect()
        except Exception as exc:
            entry["status"] = "failed"
            entry["error"] = f"{type(exc).__name__}: {exc}"
        print("[RESULT]", method, entry["status"], entry.get("size_mb"), flush=True)
        # Save after every model, even when a later conversion fails.
        (OUT/"comparison.json").write_text(json.dumps(matrix, indent=2, ensure_ascii=False))
    if matrix["fp32"]["status"] != "passed":
        raise RuntimeError("FP32 reference failed")
    print("[COMPLETE] Three-way comparison available in comparison.json", flush=True)


if __name__ == "__main__":
    main()
