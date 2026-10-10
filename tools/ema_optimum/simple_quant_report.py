"""Write a compact FP32/INT8/INT4 comparison into the repository."""
import json
import os
from pathlib import Path

root=Path("reports/ema-simple-quant")
root.mkdir(parents=True,exist_ok=True)
source=Path("dist/ema-simple-quant/comparison.json")
lines=["# EMA Lightning: FP32 / INT8 / INT4","","- Run: https://github.com/"+os.getenv("GITHUB_REPOSITORY","TheOsmanYILDIRIM/NoveLA_float")+"/actions/runs/"+os.getenv("GITHUB_RUN_ID","unknown"),
       "- Linux x86_64 ONNX Runtime CPU results (not phone benchmarks).",
       "- INT8/INT4: eligible MatMul weights only; convolution weights remain FP32.",
       "- Full output: [latest.log](latest.log)",""]
if source.exists():
    x=json.loads(source.read_text())
    lines+=["| Candidate | Result | Model MB | Text ms | Sound ms | Decoder ms | Minimum waveform SNR |",
            "|---|---|---:|---:|---:|---:|---:|"]
    for name,d in x.items():
        ts=d.get("stage_median_ms",{})
        valid=[t["waveform_snr_db"] for t in d.get("quality",[]) if t["status"]=="passed"]
        snr=min(valid) if len(valid)==3 else "not passed"
        lines.append(f"| {name.upper()} | {d['status']} | {d.get('size_mb','—')} | {ts.get('text_stage','—')} | {ts.get('sound_stage','—')} | {ts.get('decoder','—')} | {snr} |")
    lines+=["","## Failures",""]
    for name,d in x.items():
        errors=[d.get("error","")]+[t.get("reason","") for t in d.get("quality",[]) if t["status"]!="passed"]
        for err in filter(None, errors):
            lines.append(f"- **{name}:** {err}")
    lines+=["","Raw numeric results: [comparison.json](comparison.json).",
            "No quantized model should replace FP32 without validated quality and Android CPU testing."]
    (root/"comparison.json").write_text(json.dumps(x,ensure_ascii=False,indent=2))
else:
    lines+=["No result JSON yet; inspect full Actions job output."]
(root/"LATEST.md").write_text("\n".join(lines)+"\n")
