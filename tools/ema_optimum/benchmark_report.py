"""Produce canonical benchmark results in the GitHub repo."""
import json,os
from pathlib import Path
p=Path("reports/ema-comparison");p.mkdir(parents=True,exist_ok=True)
datafile=Path("dist/ema-comparison/comparison.json")
status=os.getenv("TEST_OUTCOME","unknown")
lines=["# EMA Lightning ONNX: Optimum vs fr0stb1rd","","- Job outcome: **"+status+"**",
       "- Run: https://github.com/"+os.getenv("GITHUB_REPOSITORY","TheOsmanYILDIRIM/NoveLA_float")+"/actions/runs/"+os.getenv("GITHUB_RUN_ID","unknown"),
       "- Executed on an x86_64 GitHub Actions CPU runner, **not Android**.",
       "- WAV samples: workflow artifact `ema-head-to-head-wav` (if generated).",""]
if datafile.exists():
    d=json.loads(datafile.read_text())
    lines.extend(["## Model sizes","","| Stage | Our Optimum | fr0stb1rd |","|---|---:|---:|"])
    for n in ("text_stage","sound_stage","decoder"):
        a,b=d["model_bytes"]["ours"][n],d["model_bytes"]["web"][n]
        lines.append(f"| {n} | {a:,} bytes | {b:,} bytes |")
    lines.extend(["","## Numerical parity vs original PyTorch","","| Input | Our SNR | fr0stb1rd SNR | Our RMSE | fr0stb1rd RMSE |","|---|---:|---:|---:|---:|"])
    for x in d["tests"]:
        a,b=x["scores"]["ours"],x["scores"]["web"]
        lines.append(f"| {x['id']} | {a['snr_db']:.3f} dB | {b['snr_db']:.3f} dB | {a['rmse']:.8f} | {b['rmse']:.8f} |")
    lines.extend(["", "## CPU inference (single measured run)", "",
                  "| Example | Original PyTorch | Our Optimum | fr0stb1rd | Our RTF | fr0stb1rd RTF |",
                  "|---|---:|---:|---:|---:|---:|"])
    for x in d["tests"]:
        tt=x["wall_seconds"]
        dur=x["audio_seconds"]
        lines.append(f"| {x['id']} | {tt['original']:.3f}s | {tt['ours']:.3f}s | {tt['web']:.3f}s | {tt['ours']/dur:.3f} | {tt['web']/dur:.3f} |")
    lines+=["","The same original EMA Turkish normalizer and seeded noise were used for both exports.","Numerical parity is not MOS, WER, or perceptual quality evidence.",""]
else:
    lines+=["**No completed JSON report**. Inspect the GitHub Actions job log.",""]
p.joinpath("LATEST.md").write_text("\n".join(lines))
