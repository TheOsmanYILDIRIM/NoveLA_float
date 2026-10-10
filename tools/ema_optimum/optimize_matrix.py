"""Separate, falsifiable FP32 ONNX optimization candidates and benchmarks.

No quantization, casting, or trainable-weight changes. Every candidate is
evaluated independently against original PyTorch on the same runner.
"""
import gc
import hashlib
import json
import os
import shutil
import statistics
import time
from pathlib import Path

import numpy as np
import onnx
import onnxruntime as ort
import psutil
import torch
from ema_lightning.decoder import load_decoder
from ema_lightning.engine import Engine
from ema_lightning.frontend import Frontend
from ema_lightning.model import load_acoustic
from huggingface_hub import hf_hub_download

from ema_config import config_for
from quality import synthesize_piece

BASE = Path("dist/ema-optimum")
OUT = Path("dist/ema-fp32-matrix")
SOURCE = "canberkkkkkk/ema-lightning"
REV = "7a6ba1ad216bb2f1da9863f80ac8770a6a807632"
STAGES = ("text_stage", "sound_stage", "decoder")
TEST_TEXTS = (
    ("Merhaba! Bugün hava güzel, değil mi?", 1.0, 42),
    ("2026 yılında saat 14.30'da, bin iki yüz elli lira ödendi.", 1.2, 1729),
    ("Kapıyı araladı ve yağmurlu sokağa baktı.", 1.0, 53),
)


def digest(path):
    h = hashlib.sha256()
    with path.open("rb") as f:
        for block in iter(lambda: f.read(1048576), b""):
            h.update(block)
    return h.hexdigest()


def options(level=None, threads=4, target=None, ort_format=False):
    o = ort.SessionOptions()
    o.intra_op_num_threads = threads
    o.inter_op_num_threads = 1
    o.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
    o.graph_optimization_level = {
        None: ort.GraphOptimizationLevel.ORT_DISABLE_ALL,
        "disable": ort.GraphOptimizationLevel.ORT_DISABLE_ALL,
        "basic": ort.GraphOptimizationLevel.ORT_ENABLE_BASIC,
        "extended": ort.GraphOptimizationLevel.ORT_ENABLE_EXTENDED,
        "all": ort.GraphOptimizationLevel.ORT_ENABLE_ALL,
    }[level]
    if target:
        o.optimized_model_filepath = str(target)
        if ort_format:
            o.add_session_config_entry("session.save_model_format", "ORT")
    return o


def make_graphs():
    import onnxslim
    OUT.mkdir(parents=True, exist_ok=True)
    results = {}
    for variant in ("baseline", "slim", "basic", "extended", "all", "slim_extended", "ort_extended"):
        folder = OUT / variant
        folder.mkdir(exist_ok=True)
        result = {"status": "candidate", "files": {}, "errors": []}
        for stage in STAGES:
            src = BASE / f"{stage}.onnx"
            extension = ".ort" if variant == "ort_extended" else ".onnx"
            dst = folder / f"{stage}{extension}"
            try:
                if variant == "baseline":
                    shutil.copyfile(src, dst)
                elif variant == "slim":
                    onnx.save(onnxslim.slim(str(src)), str(dst))
                elif variant == "slim_extended":
                    # First simplify, then optimize: two independent passes.
                    mid = folder / f"{stage}.preslim.onnx"
                    onnx.save(onnxslim.slim(str(src)), str(mid))
                    ort.InferenceSession(str(mid), sess_options=options("extended", target=dst),
                                         providers=["CPUExecutionProvider"])
                    mid.unlink()
                else:
                    level = "extended" if variant == "ort_extended" else variant
                    ort.InferenceSession(str(src), sess_options=options(level, target=dst, ort_format=variant=="ort_extended"),
                                         providers=["CPUExecutionProvider"])
                if not dst.is_file() or dst.stat().st_size == 0:
                    raise RuntimeError("Optimizer did not create output")
                if dst.suffix == ".onnx":
                    onnx.checker.check_model(str(dst))
                result["files"][stage] = {
                    "path": str(dst), "bytes": dst.stat().st_size,
                    "sha256": digest(dst),
                }
                print(f"[GRAPH] {variant}/{stage}: {dst.stat().st_size} bytes", flush=True)
            except Exception as e:
                result["errors"].append(f"{stage}: {type(e).__name__}: {e}")
                print(f"[GRAPH FAIL] {variant}/{stage}: {e}", flush=True)
                break
        if len(result["files"]) != len(STAGES):
            result["status"] = "failed_build"
        results[variant] = result
    return results


def make_sessions(variant, data, threads=4):
    sessions = {}
    for stage in STAGES:
        path = data["files"][stage]["path"]
        sessions[stage] = ort.InferenceSession(path, sess_options=options("disable", threads),
                                                providers=["CPUExecutionProvider"])
    return sessions


def verify(variant, sessions, acoustic, decoder):
    engine = Engine(acoustic, decoder, torch.device("cpu"))
    frontend = Frontend(acoustic.vocab)
    values = []
    for text, speed, seed in TEST_TEXTS:
        normalized = frontend(text)
        if not normalized:
            raise AssertionError("Normalizer produced empty input")
        piece = engine.piece(normalized, 0.0, seed)
        metrics = synthesize_piece(engine, piece, speed, sessions)
        metrics["text"] = text
        values.append(metrics)
    return {
        "tests": values,
        "min_waveform_snr_db": min(v["waveform_snr_db"] for v in values),
        "min_latents_snr_db": min(v["latents_snr_db"] for v in values),
    }


def bench(sessions, acoustic, decoder):
    metrics = {}
    for stage in STAGES:
        conf = config_for(stage, acoustic, decoder)
        stage_res = []
        for batch, letters, frames in ((1, 12, 32), (1, 32, 64), (2, 24, 64)):
            values = conf.generate_dummy_inputs(
                batch_size=batch, sequence_length=letters, nb_max_frames=frames)
            feeds = {key: tensor.numpy() for key, tensor in values.items()}
            sess = sessions[stage]
            # Warmup ensures timing does not include first-run graph initialization.
            for _ in range(3):
                sess.run(None, feeds)
            trials = []
            for _ in range(7):
                start = time.perf_counter()
                sess.run(None, feeds)
                trials.append((time.perf_counter() - start) * 1000)
            stage_res.append({
                "batch": batch, "letters": letters, "frames": frames,
                "median_ms": round(statistics.median(trials), 3),
                "min_ms": round(min(trials), 3),
                "max_ms": round(max(trials), 3),
            })
        metrics[stage] = stage_res
    return metrics


def main():
    torch.set_num_threads(4)
    checkpoints = {
        filename: hf_hub_download(SOURCE, filename, revision=REV)
        for filename in ("ema.pt", "decoder.pt")
    }
    acoustic = load_acoustic(checkpoints["ema.pt"], torch.device("cpu"))
    decoder = load_decoder(checkpoints["decoder.pt"], torch.device("cpu"))
    results = make_graphs()
    process = psutil.Process()
    for variant, item in results.items():
        if item["status"] != "candidate":
            continue
        try:
            gc.collect()
            before = process.memory_info().rss
            start = time.perf_counter()
            sessions = make_sessions(variant, item)
            load_ms = (time.perf_counter() - start)*1000
            loaded = process.memory_info().rss
            item["load_ms"] = round(load_ms, 2)
            item["session_rss_delta_bytes_approx"] = max(0, loaded - before)
            item["quality"] = verify(variant, sessions, acoustic, decoder)
            item["timings_ms"] = bench(sessions, acoustic, decoder)
            item["status"] = "passed"
            print(f"[PASS] {variant}: min SNR {item['quality']['min_waveform_snr_db']} dB", flush=True)
            del sessions
            gc.collect()
        except Exception as exc:
            item["status"] = "rejected"
            item["errors"].append(f"{type(exc).__name__}: {exc}")
            print(f"[REJECT] {variant}: {exc}", flush=True)
    # Session-thread comparisons are independent runtime settings, not separate exports.
    threads = {}
    base = results["baseline"]
    for n in (1, 2, 4, 8):
        try:
            sessions = make_sessions("baseline", base, threads=n)
            threads[str(n)] = {"status": "passed", "timings_ms": bench(sessions, acoustic, decoder)}
            del sessions
            gc.collect()
        except Exception as exc:
            threads[str(n)] = {"status": "rejected", "error": str(exc)}
    output = {
        "reference": "Optimum FP32 model validated by Actions 38004682817",
        "precision": "FP32; no quantization/casting/pruning",
        "platform": "GitHub Actions Linux x86_64 CPU (NOT Android)",
        "source_revision": REV,
        "variants": results,
        "cpu_threads": threads,
        "limitations": [
            "Session memory deltas share a process; they are approximate, not isolated peak RSS.",
            "Only measured CPU provider here; Android XNNPACK and NNAPI require device tests.",
            "ORT format can alter portability and startup time; benchmarked with ONNX Runtime.",
            "SNR is numeric waveform parity, not human MOS or WER.",
        ],
    }
    (OUT / "matrix.json").write_text(json.dumps(output, indent=2, ensure_ascii=False))
    if base["status"] != "passed":
        raise RuntimeError("Baseline failed; optimization comparison invalid")
    print("[COMPLETE] FP32 matrix written to dist/ema-fp32-matrix/matrix.json", flush=True)


if __name__ == "__main__":
    main()
