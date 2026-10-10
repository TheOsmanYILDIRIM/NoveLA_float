"""Repository-readable summary of all chained FP32 optimizations."""
import json, os
from pathlib import Path
root=Path("reports/ema-combinations")
root.mkdir(parents=True,exist_ok=True)
datafile=Path("dist/ema-fp32-matrix/combinations.json")
lines=["# EMA Lightning FP32 — chained optimization benchmark","",
       f"- Result: **{os.environ.get('COMBINATION_OUTCOME','unknown')}**",
       f"- Run: https://github.com/{os.environ.get('GITHUB_REPOSITORY','TheOsmanYILDIRIM/NoveLA_float')}/actions/runs/{os.environ.get('GITHUB_RUN_ID','unknown')}",
       "- Log: [latest.log](latest.log)",
       "- Platform: Linux x86-64 Actions CPU, not Android ARM64",
       "- No INT8/FP16 or model pruning; numerical waveform parity required",""]
if datafile.exists():
    data=json.loads(datafile.read_text())
    lines+=["## Graph combinations","","| Candidate | Result | Size (MB) | Minimum SNR (dB) | Sound 1×32×64 (ms) |",
            "|---|---|---:|---:|---:|"]
    for name,item in data["combinations"].items():
        size=sum(v.get("bytes",0) for v in item["files"].values())/1e6
        snr=item.get("quality",{}).get("min_waveform_snr_db","—")
        ts=item.get("timings_ms",{}).get("sound_stage",[])
        ms=ts[1]["median_ms"] if len(ts)>1 else "—"
        lines.append(f"| {name} | {item['status']} | {size:.3f} | {snr} | {ms} |")
    lines+=["","## Per-chain CPU threads","","| Candidate | 1 thread | 2 threads | 4 threads | 8 threads |",
            "|---|---:|---:|---:|---:|"]
    for name,threads in data["thread_matrix"].items():
        row=[]
        for n in ("1","2","4","8"):
            stats=threads[n].get("timings_ms",{}).get("sound_stage",[])
            row.append(str(stats[1]["median_ms"]) if len(stats)>1 else "—")
        lines.append(f"| {name} | "+" | ".join(row)+" |")
    lines+=["","## Rejected or failed combinations",""]
    for name,item in data["combinations"].items():
        if item["status"]!="passed":
            lines.append("- "+name+": "+("; ".join(item["errors"]) or "Unknown failure"))
    lines+=["","Detailed results and successful model graphs are available in the Actions artifact.",
            "SNR compares the same output against original PyTorch, not MOS/WER.",""]
else:
    lines+=["No combinations.json produced. Inspect the job log for the error."]
(root/"LATEST.md").write_text("\n".join(lines)+"\n")
