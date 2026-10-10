"""Readable persistent FP32, INT8, Q4, NF4 results."""
import json,os
from pathlib import Path
p=Path("reports/ema-community-quant");p.mkdir(parents=True,exist_ok=True)
src=Path("dist/ema-community-quant/comparison.json")
lines=["# EMA Lightning — ONNX Community quantizer comparison","",
       "- Actions: https://github.com/"+os.getenv("GITHUB_REPOSITORY","TheOsmanYILDIRIM/NoveLA_float")+"/actions/runs/"+os.getenv("GITHUB_RUN_ID","?"),
       "- Text Stage and Decoder stay FP32; Sound Stage weight-only quantization",
       "- Platform: Linux x86-64 ONNX Runtime CPU; **not Android**",
       "- See [latest.log](latest.log) for diagnostics",""]
if src.exists():
    data=json.loads(src.read_text())
    lines+=["| Candidate | Status | Total MB | Sound ms | Minimum waveform SNR |",
            "|---|---|---:|---:|---:|"]
    for key,v in data["candidates"].items():
        passing=[t["waveform_snr_db"] for t in v.get("quality",[]) if t["status"]=="passed"]
        snr=min(passing) if len(passing)==3 else "NOT VERIFIED"
        lines.append(f"| {key.upper()} | {v['status']} | {v.get('total_mb','—')} | {v.get('median_ms',{}).get('sound_stage','—')} | {snr} |")
    lines+=["","## Problems and rejection reasons",""]
    for key,v in data["candidates"].items():
        errs=[v.get("error","")]+[t.get("reason","") for t in v.get("quality",[]) if t["status"]!="passed"]
        for e in filter(None,errs):lines.append(f"- **{key.upper()}:** {e}")
    lines+=["","[Raw measured data](comparison.json)","",
            "FP32 unchanged for Text and Decoder; graph size savings relate only to quantized eligible Sound Stage MatMuls.",
            "No model promoted to Android until native ARM64 testing."]
    (p/"comparison.json").write_text(json.dumps(data,indent=2,ensure_ascii=False))
else:lines+=["No comparison output; consult latest.log and GitHub job."]
(p/"LATEST.md").write_text("\n".join(lines)+"\n")
