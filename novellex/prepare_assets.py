#!/usr/bin/env python3
"""Build-time only: bundle the original GLiNER2.5 Small ONNX and tokenizer into the APK.

No model download, network operation, HTTP client or credentials exist in the app.
Do not commit the downloaded checkpoint (roughly 289 MB) into git.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import tempfile
import time
from urllib.request import Request, urlopen

MODEL_REPO = "nicolasembleton/gliner2.5-small-v1-onnx"
MODEL_ID = "fastino/gliner2.5-small-v1"
OUTPUT = Path(__file__).resolve().parents[1] / "features/reader/src/main/assets/novellex"
FILES = {"model.onnx": "onnx/model.onnx", "tokenizer.json": "tokenizer.json"}


def digest(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def validate_tokenizer(path: Path) -> None:
    data = json.loads(path.read_text(encoding="utf-8"))
    if data.get("model", {}).get("type") != "Unigram":
        raise ValueError("Expected the original Unigram tokenizer")
    specials = {t.get("content") for t in data.get("added_tokens", [])}
    if not {"[P]", "[E]", "[SEP_TEXT]"}.issubset(specials):
        raise ValueError("Incompatible GLiNER2.5 tokenizer markers")


def verify(output: Path) -> None:
    manifest = json.loads((output / "manifest.json").read_text(encoding="utf-8"))
    assert manifest["model_id"] == MODEL_ID
    assert manifest["source_repo"] == MODEL_REPO
    assert (output / "model.onnx").stat().st_size == manifest["model_bytes"]
    assert (output / "model.onnx").stat().st_size > 200_000_000
    assert digest(output / "model.onnx") == manifest["model_sha256"]
    assert digest(output / "tokenizer.json") == manifest["tokenizer_sha256"]
    validate_tokenizer(output / "tokenizer.json")


def download(url: str, dest: Path) -> None:
    for attempt in range(3):
        try:
            request = Request(url, headers={"User-Agent": "NoveLA-offline-GLiNER-build/1.0"})
            with urlopen(request, timeout=180) as response, dest.open("wb") as out:
                if response.status != 200:
                    raise RuntimeError("Hugging Face download returned HTTP " + str(response.status))
                shutil.copyfileobj(response, out, 1024 * 1024)
            return
        except Exception:
            dest.unlink(missing_ok=True)
            if attempt == 2:
                raise
            time.sleep(2 * (attempt + 1))


def prepare(output: Path, revision: str) -> None:
    try:
        verify(output)
        print("Bundled model is already present and checksummed")
        return
    except (OSError, ValueError, AssertionError, KeyError, json.JSONDecodeError):
        pass
    output.mkdir(parents=True, exist_ok=True)
    downloaded = {}
    for name, remote in FILES.items():
        # Atomic replacement: never leave a partially downloaded model as a valid asset.
        with tempfile.NamedTemporaryFile(dir=output, prefix=name + ".", suffix=".part", delete=False) as tmp:
            temporary = Path(tmp.name)
        try:
            url = "https://huggingface.co/" + MODEL_REPO + "/resolve/" + revision + "/" + remote
            print("Fetching " + remote + " for offline Android packaging")
            download(url, temporary)
            if name == "model.onnx" and not 200_000_000 < temporary.stat().st_size < 450_000_000:
                raise ValueError("Unexpected GLiNER2.5 Small ONNX size")
            if name == "tokenizer.json":
                validate_tokenizer(temporary)
            downloaded[name] = (temporary.stat().st_size, digest(temporary))
            os.replace(temporary, output / name)
        finally:
            temporary.unlink(missing_ok=True)
    manifest = {
        "model_id": MODEL_ID,
        "source_repo": MODEL_REPO,
        "source_revision": revision,
        "model_bytes": downloaded["model.onnx"][0],
        "model_sha256": downloaded["model.onnx"][1],
        "tokenizer_sha256": downloaded["tokenizer.json"][1],
        "note": "Build-time only. No network or remote model load at Android runtime."
    }
    temporary_manifest = output / "manifest.json.part"
    temporary_manifest.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    os.replace(temporary_manifest, output / "manifest.json")
    verify(output)
    print("Verified original GLiNER2.5 Small offline assets; SHA256 " + manifest["model_sha256"])


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--verify", action="store_true", help="Never download; checksum bundled files")
    parser.add_argument("--revision", default=os.getenv("NOVELLEX_MODEL_REVISION", "main"))
    parser.add_argument("--out", type=Path, default=OUTPUT)
    args = parser.parse_args()
    if args.verify:
        verify(args.out)
        print("Offline GLiNER assets OK")
    else:
        prepare(args.out, args.revision)


if __name__ == "__main__":
    main()
