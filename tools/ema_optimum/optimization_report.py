"""Create compact, versioned Github-readable optimization matrix report."""
import json
import os
from pathlib import Path

directory=Path("reports/ema-optimization")
directory.mkdir(parents=True,exist_ok=True)
source=Path("dist/ema-fp32-matrix/matrix.json")
lines=[
    "# EMA Lightning FP32 optimization matrix",
    "",
    f"- Job result: **{os.environ.get('MATRIX_OUTCOME','unknown')}**",
    f"- Run: https://github.com/{os.environ.get('GITHUB_REPOSITORY','TheOsmanYILDIRIM/NoveLA_float')}/actions/runs/{os.environ.get('GITHUB_RUN_ID','unknown')}",
    "- Platform: GitHub Actions Linux x86_64 CPU; **not Android**",
    "- Precision: FP32 only. No quantization or pruning.",
    "- Log: [latest.log](latest.log)",
    "- Full data (and successful graphs): Actions artifact",
    "",
]
if source.exists():
    data=json.loads(source.read_text())
    lines+=["## Graph candidates","","| Variant | Result | 3-graph size (MB) | Minimum waveform SNR (dB) | Representative 1×32×64 sound stage (ms) |",
            "|---|---|---:|---:|---:|"]
    for name,item in data["variants"].items():
        total=sum(f["bytes"] for f in item["files"].values())/1e6
        snr=item.get("quality",{}).get("min_waveform_snr_db","—")
        timings=item.get("timings_ms",{}).get("sound_stage",[])
        ms=timings[1]["median_ms"] if len(timings)>1 else "—"
        lines.append(f"| {name} | {item['status']} | {total:.3f} | {snr} | {ms} |")
    lines+=["","## ONNX Runtime CPU thread settings (same FP32 baseline)","",
            "| Intra-op threads | Sound stage 1×32×64 median (ms) | Status |","|---|---:|---|"]
    for name,item in data["cpu_threads"].items():
        timings=item.get("timings_ms",{}).get("sound_stage",[])
        ms=timings[1]["median_ms"] if len(timings)>1 else "—"
        lines.append(f"| {name} | {ms} | {item['status']} |")
    lines+=["","## Failed or rejected candidates",""]
    for name,item in data["variants"].items():
        if item["status"]!="passed":
            lines.append(f"- **{name}:** "+("; ".join(item["errors"]) or "No detail"))
    lines+=["","## Scope","","These are local CPU graph tests; Android ARM64 and XNNPACK/NNAPI require separate device tests.",
            "SNR is waveform parity versus original PyTorch, not subjective sound quality.",""]
else:
    lines+=["**No matrix.json generated.** Read latest.log for the setup/export failure."]
directory.joinpath("LATEST.md").write_text("\n".join(lines)+"\n")
