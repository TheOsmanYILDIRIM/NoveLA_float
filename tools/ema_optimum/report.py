"""Publish human-readable EMA Optimum workflow results into the repository."""
import datetime
import json
import os
from pathlib import Path

report = Path("reports/ema-optimum")
report.mkdir(parents=True, exist_ok=True)
log = report / "latest.log"
manifest_file = Path("dist/ema-optimum/manifest.json")
manifest = json.loads(manifest_file.read_text()) if manifest_file.exists() else {}
status = os.environ.get("EXPORT_OUTCOME", "unknown")
run_url = os.environ.get("GITHUB_SERVER_URL", "https://github.com") + "/" + os.environ.get(
    "GITHUB_REPOSITORY", "TheOsmanYILDIRIM/NoveLA_float") + "/actions/runs/" + os.environ.get("GITHUB_RUN_ID", "?")
lines = [
    "# EMA Lightning — Optimum / ONNX Community export",
    "",
    f"- **CI result:** `{status}`",
    f"- **Run:** {run_url}",
    f"- **Commit:** `{os.environ.get('GITHUB_SHA', '?')}`",
    f"- **UTC:** {datetime.datetime.now(datetime.timezone.utc).isoformat()}",
    "- **Pipeline:** Genuine `optimum.exporters.onnx.export` with custom `OnnxConfig`.",
    "- **Full job output:** [latest.log](latest.log)",
    "- **Model binaries:** Actions artifacts (not committed to git).",
    "",
]
if manifest:
    lines.extend([
        "## Source & precision", "",
        f"- Model: `{manifest.get('source')}`",
        f"- Source revision: `{manifest.get('source_revision')}`",
        f"- Precision: `{manifest.get('precision')}`",
        "",
        "## Stage validation", "",
    ])
    for name, info in manifest.get("stages", {}).items():
        lines.append(
            f"- `{name}`: {info['bytes']:,} bytes, {len(info['tests'])} dynamic-shape tests, "
            f"SHA256 `{info['sha256']}`"
        )
    quality = manifest.get("quality")
    if quality:
        lines.extend(["", "## End-to-end Turkish speech quality", ""])
        for case in quality.get("cases", []):
            for piece in case.get("pieces", []):
                lines.append(
                    f"- `{case['input_text']}` — {piece['frames']} frames, "
                    f"latent SNR {piece['latents_snr_db']} dB, "
                    f"48 kHz waveform SNR {piece['waveform_snr_db']} dB"
                )
        lines.append("")
        lines.append("These are numerical comparisons against the original EMA engine, not MOS/WER listening scores.")
else:
    lines.extend([
        "## Validation", "",
        "No complete model manifest was generated. **Do not mark the export as verified.**",
        "See [latest.log](latest.log) for the Python error/traceback.",
    ])
(report / "LATEST.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
print("Wrote", report / "LATEST.md")
