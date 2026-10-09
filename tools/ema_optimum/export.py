"""EMA Lightning -> ONNX with the actual Hugging Face Optimum export API.

This intentionally DOES NOT import the fr0stb1rd converter or its checkpoints.
The Optimum CLI cannot auto-detect EMA Lightning because it is not a
Transformers architecture. We supply explicit OnnxConfig instances and
invoke optimum.exporters.onnx.export(...) on the original trained modules.
"""
import hashlib
import importlib.metadata
import json
import os
import time
from pathlib import Path

import numpy as np
import onnx
import onnxruntime as ort
import torch
from huggingface_hub import HfApi, hf_hub_download
from optimum.exporters.onnx import export

from ema_config import config_for, module_for
from quality import verify_real_turkish_speech

MODEL_ID = "canberkkkkkk/ema-lightning"
OUT = Path("dist/ema-optimum")
STAGES = ("text_stage", "sound_stage", "decoder")


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as file:
        for block in iter(lambda: file.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def compare(reference, candidate, atol, label):
    a = reference.detach().cpu().numpy().astype(np.float64)
    b = np.asarray(candidate, dtype=np.float64)
    if a.shape != b.shape:
        raise AssertionError(f"{label}: shape mismatch {a.shape} != {b.shape}")
    if not np.all(np.isfinite(a)) or not np.all(np.isfinite(b)):
        raise AssertionError(f"{label}: nonfinite values")
    error = np.abs(a - b)
    max_abs = float(error.max(initial=0))
    rms_error = float(np.sqrt(np.mean(error ** 2)))
    np.testing.assert_allclose(a, b, atol=atol, rtol=atol, err_msg=label)
    return {"shape": list(a.shape), "max_abs": max_abs, "rmse": rms_error}


def validate_stage(stage, wrapper, cfg, path):
    onnx.checker.check_model(str(path), full_check=True)
    session = ort.InferenceSession(str(path), providers=["CPUExecutionProvider"])
    inputs = [x.name for x in session.get_inputs()]
    outputs = [x.name for x in session.get_outputs()]
    if inputs != list(cfg.inputs):
        raise AssertionError(f"{stage}: inputs {inputs} != {list(cfg.inputs)}")
    if outputs != list(cfg.outputs):
        raise AssertionError(f"{stage}: outputs {outputs} != {list(cfg.outputs)}")
    # Vary letters, frame counts AND batch size after export. This catches
    # silently hard-coded axes which a single test shape cannot reveal.
    shapes = [(1, 9, 20), (2, 16, 32), (3, 25, 44)]
    tests = []
    atol = 1e-3 if stage == "sound_stage" else 1e-4
    for batch, letters, frames in shapes:
        feeds = cfg.generate_dummy_inputs(
            framework="pt", batch_size=batch, sequence_length=letters,
            nb_max_frames=frames)
        with torch.inference_mode():
            result = wrapper(**feeds)
        actual = session.run(None, {k: v.cpu().numpy() for k, v in feeds.items()})
        matches = {
            name: compare(result[name], actual[i], atol, f"{stage}.{name}:{batch},{letters},{frames}")
            for i, name in enumerate(outputs)
        }
        if stage == "decoder" and actual[0].shape[1] != frames * cfg.hop:
            raise AssertionError(f"{stage}: unexpected audio sample length")
        tests.append({"batch": batch, "letters": letters, "frames": frames, "outputs": matches})
    return session, {
        "sha256": sha256(path),
        "bytes": path.stat().st_size,
        "inputs": inputs, "outputs": outputs,
        "input_shapes": {x.name: x.shape for x in session.get_inputs()},
        "output_shapes": {x.name: x.shape for x in session.get_outputs()},
        "tests": tests,
        "atol": atol, "rtol": atol,
    }


def main():
    from ema_lightning.decoder import load_decoder
    from ema_lightning.model import load_acoustic

    OUT.mkdir(parents=True, exist_ok=True)
    torch.manual_seed(1729)
    torch.set_num_threads(min(4, torch.get_num_threads()))
    revision = os.environ.get("EMA_HF_REVISION") or HfApi().model_info(MODEL_ID).sha
    checkpoints = {
        name: Path(hf_hub_download(MODEL_ID, name, revision=revision))
        for name in ("ema.pt", "decoder.pt")
    }
    acoustic = load_acoustic(str(checkpoints["ema.pt"]), torch.device("cpu"))
    decoder = load_decoder(str(checkpoints["decoder.pt"]), torch.device("cpu"))
    manifest = {
        "engine": "Hugging Face Optimum ONNX exporter (real API, custom OnnxConfig)",
        "source": MODEL_ID, "source_revision": revision,
        "checkpoints": {k: sha256(v) for k, v in checkpoints.items()},
        "versions": {
            k: importlib.metadata.version(k)
            for k in ("torch", "ema-lightning", "optimum-onnx", "optimum",
                      "onnx", "onnxruntime", "transformers")
        },
        "precision": "FP32 (no quantization)", "stages": {},
        "quality": None,
    }
    sessions = {}
    for stage in STAGES:
        cfg = config_for(stage, acoustic, decoder)
        wrapper = module_for(stage, acoustic, decoder)
        path = OUT / f"{stage}.onnx"
        print(f"[OPTIMUM EXPORT] {stage}", flush=True)
        start = time.perf_counter()
        export(
            model=wrapper, config=cfg, output=path,
            opset=18, device="cpu",
            input_shapes={"batch_size": 2, "sequence_length": 16, "nb_max_frames": 32},
            disable_dynamic_axes_fix=True,
        )
        session, result = validate_stage(stage, wrapper, cfg, path)
        result["export_and_validation_seconds"] = round(time.perf_counter() - start, 2)
        sessions[stage] = session
        manifest["stages"][stage] = result
        print(f"[PASS] {stage}: {result['bytes']} bytes, 3 shape tests", flush=True)

    # The original EMA frontend (including number/date normalization) is used.
    # Compare complete waveform synthesis across the original and ONNX stages.
    print("[QUALITY] Starting real Turkish speech parity checks", flush=True)
    manifest["quality"] = verify_real_turkish_speech(acoustic, decoder, sessions)
    vocab = {"vocab": acoustic.vocab, "stoi": acoustic.stoi,
             "times": acoustic.times, "latent_dim": acoustic.latent_dim}
    (OUT / "vocab.json").write_text(
        json.dumps(vocab, ensure_ascii=False, default=lambda x: x.tolist()), encoding="utf-8")
    (OUT / "manifest.json").write_text(json.dumps(manifest, indent=2, ensure_ascii=False), encoding="utf-8")
    print("[PASS] Genuine Optimum export + dynamic tests + full Turkish waveform parity", flush=True)


if __name__ == "__main__":
    main()
