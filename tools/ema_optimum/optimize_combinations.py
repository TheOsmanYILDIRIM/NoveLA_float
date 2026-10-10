"""FP32 optimizer compositions gated by successful independent candidates.

Run independently of the single-method matrix. Each chain has ordered passes;
CPU threads are runtime configurations, not model conversions.
"""
import gc
import json
import shutil
from pathlib import Path
import onnx
import onnxruntime as ort
import torch
from ema_lightning.decoder import load_decoder
from ema_lightning.model import load_acoustic
from huggingface_hub import hf_hub_download
from optimize_matrix import BASE, OUT, REV, SOURCE, STAGES, options, digest, make_sessions, verify, bench

CHAINED = (
    ("slim_basic", ("slim", "basic")),
    ("slim_extended", ("slim", "extended")),
    ("slim_all", ("slim", "all")),
    ("basic_slim", ("basic", "slim")),
    ("extended_slim", ("extended", "slim")),
    ("all_slim", ("all", "slim")),
    ("slim_extended_ort", ("slim", "extended", "ort")),
    ("extended_slim_ort", ("extended", "slim", "ort")),
    ("slim_all_ort", ("slim", "all", "ort")),
)


def transform(src, dst, method):
    if method == "slim":
        import onnxslim
        # Explicitly serialize slim's ModelProto output; never alter dtype.
        onnx.save(onnxslim.slim(str(src)), str(dst))
    else:
        level = "extended" if method == "ort" else method
        ort.InferenceSession(str(src), providers=["CPUExecutionProvider"],
                             sess_options=options(level, target=dst, ort_format=(method == "ort")))
    if not dst.is_file() or dst.stat().st_size <= 0:
        raise RuntimeError(f"No graph produced by {method}")
    if dst.suffix == ".onnx":
        graph = onnx.load(str(dst))
        onnx.checker.check_model(graph)
        if any(initializer.data_type != onnx.TensorProto.FLOAT for initializer in graph.graph.initializer
               if initializer.data_type == onnx.TensorProto.FLOAT):
            raise AssertionError("Unexpected FP32 change")
    return dst


def run():
    OUT.mkdir(parents=True, exist_ok=True)
    torch.set_num_threads(4)
    acoustic = load_acoustic(hf_hub_download(SOURCE, "ema.pt", revision=REV), torch.device("cpu"))
    decoder = load_decoder(hf_hub_download(SOURCE, "decoder.pt", revision=REV), torch.device("cpu"))
    results = {}
    baseline = {"files": {}}
    for stage in STAGES:
        p = BASE / (stage + ".onnx")
        if not p.is_file():
            raise FileNotFoundError(p)
        baseline["files"][stage] = {"path": str(p)}
    original_sessions = make_sessions("baseline", baseline)
    baseline_quality = verify("baseline", original_sessions, acoustic, decoder)
    results["baseline"] = {"status": "passed", "quality": baseline_quality,
                           "timings_ms": bench(original_sessions, acoustic, decoder),
                           "files": {stage: {"bytes": (BASE / (stage + ".onnx")).stat().st_size}
                                     for stage in STAGES}}
    del original_sessions
    gc.collect()
    for name, chain in CHAINED:
        print(f"[CHAIN] {name} -> {' / '.join(chain)}", flush=True)
        folder = OUT / "combinations" / name
        folder.mkdir(parents=True, exist_ok=True)
        item = {"status": "candidate", "chain": list(chain), "files": {}, "errors": []}
        results[name] = item
        try:
            for stage in STAGES:
                previous = BASE / (stage + ".onnx")
                for n, step in enumerate(chain):
                    extension = ".ort" if step == "ort" else ".onnx"
                    next_file = folder / f"{stage}.pass{n + 1}{extension}"
                    transform(previous, next_file, step)
                    previous = next_file
                item["files"][stage] = {"path": str(previous),
                                        "bytes": previous.stat().st_size,
                                        "sha256": digest(previous)}
            sessions = make_sessions(name, item)
            item["quality"] = verify(name, sessions, acoustic, decoder)
            item["timings_ms"] = bench(sessions, acoustic, decoder)
            item["status"] = "passed"
            del sessions
        except Exception as error:
            item["status"] = "rejected"
            item["errors"].append(f"{type(error).__name__}: {error}")
        print(f"[{item['status'].upper()}] {name}: {item['errors']}", flush=True)
        gc.collect()
    # Compose runtime thread tuning with each model chain. This changes
    # the runtime only; no extra ONNX weights are created.
    thread_results = {}
    for name, item in results.items():
        if item["status"] != "passed":
            continue
        thread_results[name] = {}
        for n in (1, 2, 4, 8):
            try:
                sessions = make_sessions(name, item, threads=n)
                thread_results[name][str(n)] = {"status": "passed", "timings_ms": bench(sessions, acoustic, decoder)}
                del sessions
                gc.collect()
            except Exception as error:
                thread_results[name][str(n)] = {"status": "rejected", "error": str(error)}
    output = {
        "source_revision": REV, "precision": "FP32 (no quantization)",
        "platform": "GitHub Actions x86-64 Linux CPU",
        "baseline_snr_db": baseline_quality["min_waveform_snr_db"],
        "combinations": results, "thread_matrix": thread_results,
        "caveats": ["Graph changes are staged in the listed order",
                    "ORT-format serialized models may be target specific",
                    "Android XNNPACK/NNAPI remain untested",
                    "Each candidate must pass original PyTorch waveform parity"],
    }
    (OUT / "combinations.json").write_text(json.dumps(output, indent=2, ensure_ascii=False))
    if any(item["status"] != "passed" for item in results.values()):
        print("[WARNING] Some combinations rejected; see combinations.json", flush=True)
    print("[COMPLETE] Combination matrix saved", flush=True)


if __name__ == "__main__":
    run()
