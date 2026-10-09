"""End-to-end speech-quality gate against the ORIGINAL EMA Lightning engine.

Checks the production Turkish frontend, text->frame plan, acoustic latents,
48 kHz windowed decoder with 8-frame context, exact samples, and waveform SNR.
No pre-generated ONNX files or third-party pipeline code is involved.
"""
from math import log10

import numpy as np
import torch
from ema_lightning.chunker import chunk
from ema_lightning.engine import CONTEXT, FIRST_WINDOW, MAX_FRAMES, MAX_WORD_FRAMES, Engine, windows
from ema_lightning.frontend import Frontend


SENTENCES = (
    ("Merhaba! Bugün hava güzel, değil mi?", 1.0, 42),
    ("2026 yılında saat 14.30'da, bin iki yüz elli lira ödendi.", 1.2, 1729),
)


def snr_db(reference, candidate):
    a = np.asarray(reference, dtype=np.float64)
    b = np.asarray(candidate, dtype=np.float64)
    if a.shape != b.shape:
        raise AssertionError(f"Waveform sample mismatch: {a.shape} != {b.shape}")
    if not np.isfinite(a).all() or not np.isfinite(b).all():
        raise AssertionError("NaN or Infinity found in waveform")
    power = np.mean(a * a)
    if power < 1e-10:
        raise AssertionError("Reference audio silent (quality test invalid)")
    noise = np.mean((a - b) ** 2)
    return 10.0 * log10(power / max(noise, 1e-16))


def ort_forward(session, **feeds):
    return session.run(None, {k: (v.detach().cpu().numpy() if isinstance(v, torch.Tensor) else v)
                              for k, v in feeds.items()})


def from_onnx_durations(piece, durations):
    """Plan frames using EMA's original rounding, word mapping and constraints."""
    dur = torch.from_numpy(durations[None, :])
    word = piece.cw.unsqueeze(0)
    counts = torch.zeros_like(dur).scatter_add_(1, word, dur).round().clamp(1, MAX_WORD_FRAMES).long().cpu()
    n = counts[0, :int(piece.cw[-1]) + 1]
    frames = min(int(n.sum()), MAX_FRAMES)
    fw = torch.repeat_interleave(torch.arange(n.numel()), n)[:frames]
    fp = ((torch.arange(frames) - (n.cumsum(0) - n)[fw]).double() / n[fw].double()).float()
    return fw, fp


def synthesize_piece(engine, piece, speed, sessions):
    # Reference: uses original PyTorch Engine and the original seed/noise.
    engine.plan([piece], speed)
    engine.think([piece])
    p = piece
    ids, mask = p.ids.unsqueeze(0), torch.ones(1, p.letters, dtype=torch.bool)
    h_onnx, dur_raw = ort_forward(sessions["text_stage"], ids=ids, mask=mask)
    h_onnx, dur = h_onnx[0], (dur_raw[0] / speed).astype(np.float32)
    fw, fp = from_onnx_durations(p, dur)
    if not torch.equal(fw, p.fw):
        raise AssertionError("Text-stage duration rounding changed the word/frame timeline")
    if not torch.allclose(fp, p.fp, atol=1e-6, rtol=1e-6):
        raise AssertionError("Text-stage intra-word timing mismatch")

    # Identical seed and time layout in both pipelines; this is production
    # Engine.noise, not a random dummy tensor.
    noise = engine.noise(p).unsqueeze(0)
    latents, = ort_forward(
        sessions["sound_stage"],
        h=h_onnx[None].astype(np.float32),
        dur=dur[None].astype(np.float32),
        mask=np.ones((1, p.letters), dtype=np.bool_),
        cw=p.cw.unsqueeze(0),
        wstart=p.wstart.unsqueeze(0),
        fw=fw.unsqueeze(0),
        fp=fp.unsqueeze(0),
        fmask=torch.ones(1, p.frames, dtype=torch.bool),
        noise=noise,
    )
    latents = latents[0]
    expected_latents = p.latents.cpu().numpy()
    latent_snr = snr_db(expected_latents, latents)
    if latent_snr < 35.0:
        raise AssertionError(f"Latents differ excessively: SNR={latent_snr:.2f} dB")

    # Original streaming windows: first 1 sec, then 4 sec, with 8-frame
    # context on both sides. This avoids false parity claims from decoding
    # only isolated random latents.
    original_audio, onnx_audio = [], []
    for start, end in windows(p.frames, FIRST_WINDOW):
        a, b = max(0, start - CONTEXT), min(p.frames, end + CONTEXT)
        with torch.inference_mode():
            ref = engine.decoder(p.latents[a:b].T[None]).cpu().numpy()[0]
        candidate, = ort_forward(
            sessions["decoder"], z=latents[a:b].T[None].astype(np.float32))
        low, high = (start - a) * engine.hop, (end - a) * engine.hop
        original_audio.append(ref[low:high])
        onnx_audio.append(candidate[0, low:high])
    original_audio = np.concatenate(original_audio)
    onnx_audio = np.concatenate(onnx_audio)
    expected_samples = p.frames * engine.hop
    if len(onnx_audio) != expected_samples:
        raise AssertionError(f"Incorrect samples: {len(onnx_audio)} != {expected_samples}")
    waveform_snr = snr_db(original_audio, onnx_audio)
    if waveform_snr < 35.0:
        raise AssertionError(f"Waveform differs excessively: SNR={waveform_snr:.2f} dB")

    return {
        "normalized_piece": p.text,
        "speed": speed, "seed": p.seed,
        "frames": p.frames, "samples": expected_samples, "sample_rate": 48000,
        "latents_snr_db": round(latent_snr, 3),
        "waveform_snr_db": round(waveform_snr, 3),
        "waveform_max_abs_difference": round(float(np.max(np.abs(original_audio - onnx_audio))), 7),
        "reference_peak": round(float(np.max(np.abs(original_audio))), 5),
    }


def verify_real_turkish_speech(acoustic, decoder, sessions):
    torch.set_num_threads(min(4, torch.get_num_threads()))
    frontend = Frontend(acoustic.vocab)
    engine = Engine(acoustic, decoder, torch.device("cpu"))
    cases = []
    for original, speed, seed in SENTENCES:
        normalized = frontend(original)
        if not normalized:
            raise AssertionError("Turkish frontend returned empty text")
        parts = list(chunk(normalized, speed))
        if not parts:
            raise AssertionError("Original sentence chunker returned no pieces")
        results = []
        for index, (text, pause) in enumerate(parts):
            p = engine.piece(text, pause, (seed * 1_000_003 + index) % 2**63)
            results.append(synthesize_piece(engine, p, speed, sessions))
        cases.append({
            "input_text": original, "normalized_text": normalized,
            "pieces": results,
        })
        print(f"[QUALITY PASS] {original[:35]}... min waveform SNR="
              f"{min(p['waveform_snr_db'] for p in results):.2f} dB", flush=True)
    return {
        "target": "original EMA Lightning 1.0.4 reference engine",
        "parity_gate": ">=35 dB SNR for both latents and 48 kHz waveform",
        "full_production_frontend": True,
        "windowing": "original 25-frame first, 100-frame later, 8-frame context",
        "cases": cases,
        "caveat": "Numerical fidelity is not a listening test, MOS or WER assessment.",
    }
