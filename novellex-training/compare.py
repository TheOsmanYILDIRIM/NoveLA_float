"""Compare base GLiNER2.5 against the exact 8-step NovelLex LoRA adapter.
No copyrighted novel text is loaded by this public Actions evaluation.
"""
from pathlib import Path
from collections import Counter
import argparse
import gzip
import hashlib
import json
import random
import time

import torch
from gliner2 import AutoExtractor
from peft import PeftModel
from pilot import LABELS, MODEL, prepare, SEED

WEIGHTS_SHA = "22f1a31a82544d050b5b3b87e58cfa37c9852b97e5df9ad15daa82640189da00"
CONFIG_SHA = "36dd073423b7b72ed7dce02587e60dd0b5142114e0c9a8af90b54439048cce6d"
LABEL_NAMES = list(LABELS.values())
THRESHOLD = 0.5


def checksum(file):
    return hashlib.sha256(file.read_bytes()).hexdigest()


def validation_rows(output):
    meta = prepare(output)
    records = [json.loads(s) for s in gzip.decompress(SEED.read_bytes()).decode().splitlines()]
    pairs = [records[i:i + 2] for i in range(0, len(records), 2)]
    random.Random(20261110).shuffle(pairs)
    validation = [r for pair in pairs[:13] for r in pair]
    frozen = [json.loads(s) for s in (output / "validation.jsonl").read_text().splitlines()]
    assert len(validation) == len(frozen) == meta["validation"] == 26
    assert [r["text"] for r in validation] == [r["input"] for r in frozen]
    assert len(set(r["id"] for r in validation)) == len(validation)
    return validation, meta


def predict(model, row):
    result = model.extract_entities(
        row["text"], LABEL_NAMES, threshold=THRESHOLD,
        include_spans=True, include_confidence=True)
    if not isinstance(result, dict):
        raise TypeError("Invalid GLiNER result")
    entities = result.get("entities", {})
    if not isinstance(entities, dict):
        raise TypeError("Invalid GLiNER entities")
    predicted = set()
    for label, values in entities.items():
        if label not in LABEL_NAMES:
            raise ValueError("Unknown predicted label: " + label)
        if values is None:
            continue
        if not isinstance(values, list):
            raise TypeError("Non-list entities")
        for e in values:
            if not isinstance(e, dict) or not {"start", "end", "text"} <= e.keys():
                raise ValueError("Missing character offsets")
            a, b = e["start"], e["end"]
            if not isinstance(a, int) or not isinstance(b, int) or not 0 <= a < b <= len(row["text"]):
                raise ValueError("Invalid character offsets")
            if row["text"][a:b] != e["text"]:
                raise ValueError("Prediction character offsets do not match text")
            predicted.add((label, a, b))
    return predicted


def rates(c):
    t, f, n = [c[k] for k in ("tp", "fp", "fn")]
    p, r = (t / (t + f) if t + f else 0), (t / (t + n) if t + n else 0)
    return {"tp": t, "fp": f, "fn": n, "precision": round(p, 6),
            "recall": round(r, 6), "f1": round(2*p*r/(p+r), 6) if p+r else 0.0}


def measure(model, rows):
    counts = Counter()
    per_label = {k: Counter() for k in LABEL_NAMES}
    errors = []
    t = time.monotonic()
    with torch.inference_mode():
        for row in rows:
            gold = {(LABELS[e["label"]], e["start"], e["end"]) for e in row["entities"]}
            guessed = predict(model, row)
            tp, fp, fn = guessed & gold, guessed - gold, gold - guessed
            counts.update(tp=len(tp), fp=len(fp), fn=len(fn), exact=int(guessed == gold),
                          negatives=int(not gold), negative_fp=int(not gold and bool(guessed)))
            for label in LABEL_NAMES:
                per_label[label].update(tp=sum(x[0] == label for x in tp),
                                        fp=sum(x[0] == label for x in fp),
                                        fn=sum(x[0] == label for x in fn))
            if fp or fn:
                errors.append({"id": row["id"], "fp": sorted(fp), "fn": sorted(fn)})
    return {"micro_exact_span_label": rates(counts),
            "per_label": {label: rates(c) for label, c in per_label.items()},
            "exact_sentences": counts["exact"], "total_sentences": len(rows),
            "negative_sentences": counts["negatives"],
            "negatives_with_false_positive": counts["negative_fp"],
            "elapsed_seconds": round(time.monotonic()-t, 2), "errors": errors}


def main(out, checkpoint):
    rows, meta = validation_rows(out)
    weights = checkpoint / "adapter_model.safetensors"
    cfg = checkpoint / "adapter_config.json"
    assert weights.is_file() and cfg.is_file(), "Original checkpoint missing"
    assert checksum(weights) == WEIGHTS_SHA, "Different adapter weights"
    assert checksum(cfg) == CONFIG_SHA, "Different adapter config"
    config = json.loads(cfg.read_text())
    assert config["peft_type"] == "LORA"
    assert config["base_model_name_or_path"] == MODEL
    torch.set_num_threads(4)
    torch.manual_seed(20261110)
    model = AutoExtractor.from_pretrained(MODEL)
    model.eval()
    baseline = measure(model, rows)
    print("BASE_METRICS", baseline["micro_exact_span_label"], flush=True)

    peft_model = PeftModel.from_pretrained(model, str(checkpoint), is_trainable=False)
    peft_model.eval()
    adapted = peft_model.get_base_model()
    adapted.eval()
    attached = sum(1 for m in adapted.modules() if hasattr(m, "lora_A"))
    assert attached > 0, "No LoRA layers attached"
    trained = measure(adapted, rows)
    print("ADAPTED_METRICS", trained["micro_exact_span_label"], flush=True)

    report = {"status": "synthetic_holdout_evaluated",
              "baseline": baseline, "trained": trained,
              "delta_f1": round(trained["micro_exact_span_label"]["f1"]
                                -baseline["micro_exact_span_label"]["f1"], 6),
              "source_run": 42707389516, "model": MODEL,
              "validation_examples": 26, "validation_gold_spans": sum(len(r["entities"]) for r in rows),
              "adapter_weights_sha256": WEIGHTS_SHA, "lora_layers": attached,
              "threshold": THRESHOLD, "split_seed": 20261110,
              "source_seed_sha256": meta["source_sha256"],
              "limit": "AI-generated synthetic holdout; real novel accuracy NOT tested"}
    path = out / "comparison_report.json"
    path.write_text(json.dumps(report, ensure_ascii=False, indent=2))
    assert path.exists() and path.stat().st_size > 100
    print("SYNTHETIC_COMPARISON_COMPLETE", report["delta_f1"], flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, default=Path("novellex_output"))
    parser.add_argument("--adapter", type=Path, default=Path("novellex_output/checkpoints/final"))
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    main(args.output, args.adapter)
