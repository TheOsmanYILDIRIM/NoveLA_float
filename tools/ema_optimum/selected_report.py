"""Record source-level rebuild, artifact deletion and quant experiments."""
import json,os
from pathlib import Path
p=Path("reports/ema-selected");p.mkdir(parents=True,exist_ok=True)
lines=["# EMA selected model and artifact cleanup","",
       "- Run: https://github.com/"+os.getenv("GITHUB_REPOSITORY","TheOsmanYILDIRIM/NoveLA_float")+"/actions/runs/"+os.getenv("GITHUB_RUN_ID","?"),
       "- Old Actions artifacts: see [artifact-cleanup.log](artifact-cleanup.log)",
       "- Source Optimum conversion: **"+os.getenv("BASE_STATUS","unknown")+"**",
       "- Extended -> Slim rebuild: **"+os.getenv("SELECTED_STATUS","unknown")+"**",
       "- Experimental quantization: **"+os.getenv("QUANT_STATUS","unknown")+"**",""]
selected=Path("dist/ema-selected/manifest.json")
if selected.exists():
    x=json.loads(selected.read_text())
    lines+=["## Verified FP32 production candidate","","| Stage | Bytes | SHA256 |","|---|---:|---|"]
    for stage,z in x["models"].items():
        lines.append(f"| {stage} | {z['bytes']:,} | `{z['sha256']}` |")
    lines+=["",f"- Minimum original voice SNR: {x['selected_quality']['min_waveform_snr_db']} dB",
             "- No quantization in production candidate."]
else:
    lines+=["**No verified selected manifest. Do not publish model.**"]
research=Path("dist/ema-quant-research/research.json")
if research.exists():
    data=json.loads(research.read_text())
    lines+=["","## Experimental quantization","","| Format | Status | Note |","|---|---|---|"]
    for name,z in data.items():
        lines.append(f"| {name} | {z['status']} | {z.get('error',z.get('reason','Audio parity passed'))} |")
lines+=["","## Runtime constraints","","NVFP4 is for supported NVIDIA Blackwell/TensorRT paths, **not an established Android ARM64 CPU inference format**.",
        "INT8 / INT4 are not selected without passing waveform parity and device performance validation.",""]
(p/"LATEST.md").write_text("\n".join(lines)+"\n")
