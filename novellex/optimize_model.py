#!/usr/bin/env python3
"""Audited GLiNER2.5 Small compression for bundled NoveLA Android ARM64 APK.

Only ORIGINAL checkpoint derived weights are used. Compare predictions against
FP32 on original synthetic fantasy passages; this is PARITY, not gold accuracy.
Fail closed when no smaller CPU-supported graph preserves reference extractions.
No copyrighted novels, raw passages from users, or credentials are uploaded.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import tempfile
import traceback
import time
import numpy as np

MODEL_ID = "fastino/gliner2.5-small-v1"
LABELS = [
    "fictional creature species", "fictional person", "named fantasy technique",
    "named fictional organization", "named fictional place",
    "named fictional world concept", "named magical artifact", "named rank",
    "personal title",
]
# Newly authored synthetic text; no passages from books or private training data.
SENTENCES = [
    "Arven entered the city of Moonhaven, carrying the Silver Flame amulet.",
    "Queen Merida summoned the Ashen Council to defend Eldoria from the ember wyrms.",
    "The apprentice Soren learned the Stormglass Rite under Master Velan.",
    "In Frostspire, Captain Elira discovered the Black Sun relic beneath the tower.",
    "Jorek and the Silent Wardens crossed Nareth to recover the Crystal Fang.",
    "The wind drakes fled from the Iron Basilisk when the Moonfall Eclipse began.",
    "Priestess Lyana invoked the Starweave Blessing at the ruins of Veyr.",
    "Tarin became a Shadowbound Adept after defeating the Hollow Serpents.",
    "The Emberborne clans guarded Sunreach, while Rowan sought the Dawn Key.",
    "High Sage Orien warned the Ember Guild that Voidtide would engulf Mirath.",
    "Elandra opened the Gate of Echoes with the ancient rune blade Karsil.",
    "The Night Talons served Lord Cassian inside the Obsidian Citadel.",
]
THRESHOLDS = (0.30, 0.50)
WORD_RE = re.compile(r"https?://\S+|www\.\S+|[\w]+(?:[-_][\w]+)*|[^\s]", re.UNICODE)


def digest(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def feeds_for(text, tokenizer, model_names):
    words = [x.group().lower() for x in WORD_RE.finditer(text)]
    schema = ["(", "[P]", "entities", "("]
    for label in LABELS:
        schema += ["[E]", label]
    schema += [")", ")"]
    words = words if text.endswith((".", "!", "?")) else words + ["."]
    pieces = schema + ["[SEP_TEXT]"] + words
    ids, starts, markers = [], [], []
    for i, value in enumerate(pieces):
        if i < len(schema) and value == "[E]":
            markers.append(len(ids))
        if i > len(schema):
            starts.append(len(ids))
        segment = tokenizer.encode(value, add_special_tokens=False).ids
        if not segment:
            raise ValueError("Tokenizer generated an empty segment: " + value)
        ids.extend(segment)
    if len(markers) != len(LABELS) or len(starts) != len(words):
        raise ValueError("GLiNER schema routing mismatch")
    result = {
        "input_ids": np.array([ids], dtype=np.int64),
        "attention_mask": np.ones((1, len(ids)), dtype=np.int64),
        "text_word_indices": np.array([starts], dtype=np.int64),
        "text_word_mask": np.ones((1, len(words)), dtype=np.float32),
        "query_marker_indices": np.array([markers], dtype=np.int64),
        "query_marker_mask": np.ones((1, len(markers)), dtype=np.float32),
    }
    for optional in ("cls", "rel"):
        if optional + "_marker_indices" in model_names:
            result[optional + "_marker_indices"] = np.array([[0]], dtype=np.int64)
            result[optional + "_marker_mask"] = np.zeros((1, 1), dtype=np.float32)
    return result, words


def entities(outputs, words, threshold):
    logits = outputs["pair_logits"][0]
    indices = outputs["pair_indices"][0]
    valids = outputs["pair_valid"][0]
    found = set()
    for q, label in enumerate(LABELS):
        candidates = {}
        for k in range(len(logits[q])):
            if not valids[q][k]:
                continue
            start, end = (int(x) for x in indices[q][k])
            if start < 0 or end <= start or end > len(words):
                continue
            p = float(1.0 / (1.0 + np.exp(-np.clip(logits[q][k], -40, 40))))
            if p < threshold:
                continue
            key = (start, end)
            candidates[key] = max(p, candidates.get(key, 0))
        selected = []
        for (s, e), p in sorted(candidates.items(), key=lambda a: -a[1]):
            if not any(s < f and e > b for b, f in selected):
                selected.append((s, e))
                found.add((label, " ".join(words[s:e])))
    return found


def run_set(session, texts, tok):
    names = {x.name for x in session.get_inputs()}
    output_names = [x.name for x in session.get_outputs()]
    required = {"pair_indices", "pair_logits", "pair_valid"}
    if not required.issubset(output_names):
        raise ValueError("This graph lacks GLiNER2.5 reranker outputs")
    found = []
    for sentence in texts:
        feeds, words = feeds_for(sentence, tok, names)
        raw = session.run(None, feeds)
        outputs = dict(zip(output_names, raw))
        found.append([entities(outputs, words, t) for t in THRESHOLDS])
    return found


def compare(ref, other):
    metrics = []
    for ix, threshold in enumerate(THRESHOLDS):
        a = set()
        b = set()
        for i in range(len(ref)):
            a.update((i, *row) for row in ref[i][ix])
            b.update((i, *row) for row in other[i][ix])
        matched = len(a & b)
        recall = matched / len(a) if a else 0
        precision = matched / len(b) if b else 0
        f1 = (2 * precision * recall / (precision + recall)) if precision + recall else 0
        metrics.append({"threshold": threshold, "fp32_mentions": len(a), "candidate_mentions": len(b),
                        "exact_matches": matched, "reference_parity_f1": round(f1, 4)})
    # Avoid a false "pass" on an empty baseline.
    accepted = all(x["fp32_mentions"] >= 5 and x["reference_parity_f1"] >= .92 for x in metrics)
    return accepted, metrics


def make_fp16(source: Path, target: Path):
    import onnx
    from onnxconverter_common import float16
    print("Converting FP16 model...", flush=True)
    fp32 = onnx.load(str(source))
    converted = float16.convert_float_to_float16(
        fp32, keep_io_types=True, disable_shape_infer=True
    )
    onnx.save_model(converted, str(target))
    del converted, fp32


def make_embedded_int8(source: Path, target: Path):
    """Quantize large float Gather embeddings per dimension, preserving FP32 outputs.

    The model's huge vocab embedding is a Gather of constant float32 matrix.
    INT8 storage + ONNX DequantizeLinear follows the Gather, keeping all
    downstream attention and reranking operators unchanged and Android-compatible.
    """
    import onnx
    from onnx import helper, numpy_helper, TensorProto
    print("Converting large Gather embeddings to per-channel INT8...", flush=True)
    model = onnx.load(str(source))
    initializers = {x.name: x for x in model.graph.initializer}
    changed = []
    additions = []
    replacements = {}
    for index, node in enumerate(model.graph.node):
        if node.op_type != "Gather" or not node.input:
            continue
        weight = initializers.get(node.input[0])
        if weight is None or weight.data_type != TensorProto.FLOAT:
            continue
        matrix = numpy_helper.to_array(weight)
        if matrix.ndim != 2 or matrix.nbytes < 16_000_000:
            continue
        # Output is [...indices shape..., hidden_size]. Quantize each hidden
        # dimension independently, then dequantize on the last output axis.
        scale = np.max(np.abs(matrix), axis=0).astype(np.float32) / 127.0
        scale[scale < 1e-8] = 1e-8
        quant = np.clip(np.rint(matrix / scale), -127, 127).astype(np.int8)
        weight_new = numpy_helper.from_array(quant, name=weight.name)
        scale_name = node.name + "_nlex_scale"
        if not node.name:
            scale_name = node.output[0].replace("/", "_") + "_nlex_scale"
        zero_name = scale_name + "_zero"
        prev_output = node.output[0]
        quant_out = prev_output + "_nlex_int8"
        node.output[0] = quant_out
        model.graph.initializer.append(numpy_helper.from_array(scale, name=scale_name))
        model.graph.initializer.append(numpy_helper.from_array(np.zeros(scale.shape, dtype=np.int8), name=zero_name))
        dq = helper.make_node("DequantizeLinear", [quant_out, scale_name, zero_name],
                              [prev_output], axis=-1, name="nlex_restore_" + str(index))
        additions.append((index, dq))
        replacements[weight.name] = weight_new
        changed.append({"embedding": weight.name, "original_bytes": matrix.nbytes,
                        "int8_bytes": quant.nbytes})
        print("INT8 embedding:", weight.name, matrix.shape, flush=True)
    if not changed:
        raise ValueError("No large FP32 Gather embedding found")
    for i, dq in reversed(additions):
        model.graph.node.insert(i + 1, dq)
    for i, item in enumerate(model.graph.initializer):
        if item.name in replacements:
            model.graph.initializer[i].CopyFrom(replacements[item.name])
    onnx.checker.check_model(model)
    onnx.save_model(model, str(target))
    return changed


def make_selective_int8(source: Path, target: Path):
    import onnx
    from onnxruntime.quantization import quantize_dynamic, QuantType
    model = onnx.load(str(source))
    init = {x.name for x in model.graph.initializer}
    nodes = [n.name for n in model.graph.node if n.op_type == "MatMul"
             and n.name and len(n.input) > 1 and n.input[1] in init
             and not re.search(r"pair|head|rerank|candidate|proposer|classifier", n.name, re.I)]
    print("Selective INT8 MatMul candidates:", len(nodes), flush=True)
    if not nodes:
        raise ValueError("No compatible constant-weight MatMul nodes to quantize")
    del model
    quantize_dynamic(str(source), str(target), op_types_to_quantize=["MatMul"],
                     nodes_to_quantize=nodes, per_channel=False,
                     weight_type=QuantType.QInt8)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--assets", type=Path, required=True)
    parser.add_argument("--work", type=Path, required=True)
    args = parser.parse_args()
    base = args.assets / "model.onnx"
    tokenizer_path = args.assets / "tokenizer.json"
    manifest_path = args.assets / "manifest.json"
    original_manifest = json.loads(manifest_path.read_text())
    if original_manifest.get("model_id") != MODEL_ID or digest(base) != original_manifest["model_sha256"]:
        raise ValueError("FP32 checkpoint is not the audited original")
    original_bytes = base.stat().st_size

    from tokenizers import Tokenizer
    import onnxruntime as ort
    tok = Tokenizer.from_file(str(tokenizer_path))
    settings = ort.SessionOptions()
    settings.intra_op_num_threads = 2
    settings.inter_op_num_threads = 1
    settings.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    def load(path):
        return ort.InferenceSession(str(path), sess_options=settings, providers=["CPUExecutionProvider"])
    args.work.mkdir(parents=True, exist_ok=True)
    print("Loading original reference FP32 model...", flush=True)
    ref_session = load(base)
    refs = run_set(ref_session, SENTENCES, tok)
    total = sum(len(refs[i][0]) for i in range(len(refs)))
    print("Reference detections at 0.3:", total, flush=True)
    del ref_session
    if total < 5:
        raise ValueError("Insufficient reference predictions to audit compression")

    attempts = []
    candidates = [
        ("fp16", lambda p: make_fp16(base, p)),
        ("int8-embeddings", lambda p: make_embedded_int8(base, p)),
        ("int8-encoder", lambda p: make_selective_int8(base, p)),
    ]
    for name, build in candidates:
        path = args.work / (name + ".onnx")
        attempt = {"variant": name, "pass": False}
        try:
            started = time.monotonic()
            build(path)
            attempt["model_bytes"] = path.stat().st_size
            if path.stat().st_size >= original_bytes * .85:
                raise ValueError("Candidate saves less than 15%")
            session = load(path)
            predictions = run_set(session, SENTENCES, tok)
            del session
            accepted, metrics = compare(refs, predictions)
            attempt["parity"] = metrics
            attempt["pass"] = accepted
            attempt["seconds"] = round(time.monotonic() - started, 2)
        except Exception as exc:
            attempt["error"] = repr(exc)
            print("Candidate", name, "rejected:", exc, flush=True)
            traceback.print_exc(limit=2)
        attempts.append(attempt)
        print("Candidate:", json.dumps(attempt, ensure_ascii=False), flush=True)

    winners = sorted((x for x in attempts if x["pass"]), key=lambda x: x["model_bytes"])
    if not winners:
        raise RuntimeError("NO VERIFIED COMPACT MODEL: no release will be published")
    chosen = winners[0]
    selected = args.work / (chosen["variant"] + ".onnx")
    temporary = args.assets / "model.onnx.new"
    shutil.copyfile(selected, temporary)
    os.replace(temporary, base)
    final = dict(original_manifest)
    final["precision"] = chosen["variant"]
    final["fp32_reference_sha256"] = original_manifest["model_sha256"]
    final["model_sha256"] = digest(base)
    final["model_bytes"] = base.stat().st_size
    final["parity"] = chosen["parity"]
    final["validation"] = "ONNX Runtime CPU original-model prediction agreement on 12 synthetic fantasy passages, not gold accuracy or Android device validation"
    tmp_manifest = manifest_path.with_suffix(".new")
    tmp_manifest.write_text(json.dumps(final, indent=2, ensure_ascii=False) + "\n")
    os.replace(tmp_manifest, manifest_path)
    report = {"original_model_bytes": original_bytes, "chosen": chosen,
              "attempts": attempts, "final_sha256": final["model_sha256"]}
    (args.work / "report.json").write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n")
    print("OPTIMIZATION_REPORT_JSON=" + json.dumps(report, ensure_ascii=False), flush=True)
    print("CHOSEN_MODEL_BYTES=" + str(base.stat().st_size), flush=True)
    print("MODEL_VARIANT=" + chosen["variant"], flush=True)


if __name__ == "__main__":
    main()
