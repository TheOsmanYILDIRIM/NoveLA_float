# NovelLex for NoveLA — original GLiNER2.5 Small companion (prototype)

This implementation uses **the original** `fastino/gliner2.5-small-v1` model (Apache 2.0). No NovelLex LoRA adapter or copyrighted training excerpts are shipped. The official model is downloaded by `gliner2` when the user launches the Python companion and first requests extraction.

## What works

- Optional in-reader **More → NovelLex** entry. Nothing is analyzed until the user explicitly presses **Scan loaded chapter**.
- Scans currently loaded **original-language** chapter body paragraphs, requesting extraction in bounded local batches.
- Per-book/per-chapter glossary in Android app preferences; re-scan **replaces** the same chapter instead of inflating counts; clear glossary control.
- Label, text, and count shown in the dialog. Malformed model spans are discarded. Source chapter text and access token are **not** saved in the glossary.
- A standalone Python companion exposing `GET /v1/health` and `POST /v1/extract`, requiring a bearer token, bounded payloads, with original-model inference and no request body logging.

## Run on a computer (Python 3.11 recommended)

```bash
python -m pip install 'gliner2[local]==2.1.0'
export NOVELLEX_TOKEN='replace-with-a-random-string-at-least-24-chars'
python novellex/server.py --host 127.0.0.1 --port 8765
python -m unittest discover -s novellex -p 'test_*.py'
```

For an Android **emulator**, enter `http://10.0.2.2:8765` in NovelLex settings. For a **physical phone**, start the companion using `--host 0.0.0.0`, connect the PC and phone to a *trusted* LAN, open the computer firewall for port 8765 on the LAN only, and enter `http://192.168.x.x:8765` or its private subnet address. The token must match `NOVELLEX_TOKEN`. **HTTP exposes both the token and the book text to someone capable of intercepting the LAN traffic**; use the loopback/emulator mode, a trusted isolated network, or terminate HTTPS on your own secure reverse proxy (the phone currently limits configured hosts to private IP/localhost). Do not expose port 8765 to the public Internet.

## Critical limitations

- **Not an on-device Android ML inference implementation**. The original GLiNER2.5 Small checkpoint is approximately 296 MB FP32 and currently runs via an explicitly configured companion process. No Android-native model export, APK embedding, quantization, real-device test, or production accuracy validation is claimed.
- The model card identifies the small checkpoint as **English**. Turkish chapters are not verified for language quality.
- The reader only scans currently loaded chapter paragraphs; it does not automatically crawl/download whole books, modify translated text, alter TTS, or highlight spans inline.
- Generated glossary entries are AI predictions, not reliable canonical terms. No human-reviewed gold validation is implied.
- The companion's initial model load needs model download access and sufficient memory. Its HTTP API is intentionally incompatible with public inference endpoints.
- This integration is experimental; no NoveLA user data or previous reading history is migrated.

## Verification

`python -m unittest discover -s novellex -p 'test_*.py'` tests request authentication, health, offsets, and invalid payload handling without downloading the 296 MB model. Android compilation needs Android SDK/Gradle (CI workflow validates source). The Python unit tests do **not** certify real model loading or actual phone behavior.
