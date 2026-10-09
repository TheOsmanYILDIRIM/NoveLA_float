"""Independent EMA Lightning ONNX exporter using the ONNX Community/Optimum
custom-architecture recipe: explicit per-submodel contracts, dynamic dimensions,
reference comparison and ONNX Runtime validation. No third-party EMA ONNX
conversion code is imported. See https://huggingface.co/docs/optimum-onnx/onnx/usage_guides/contribute
"""
import argparse
import hashlib
import json
from pathlib import Path

import numpy as np
import onnx
import onnxruntime as ort
import torch
from huggingface_hub import hf_hub_download

MODEL_ID = "canberkkkkkk/ema-lightning"
OUT = Path("dist/ema-onnx")


class Text(torch.nn.Module):
    def __init__(self, model):
        super().__init__()
        self.model = model

    def forward(self, ids, mask):
        return self.model.text_stage(ids, mask)


class Sound(torch.nn.Module):
    def __init__(self, model):
        super().__init__()
        self.model = model

    def forward(self, h, dur, mask, cw, wstart, fw, fp, fmask, noise):
        return self.model.sound_stage(h, dur, mask, cw, wstart, fw, fp, fmask, noise)


class Decoder(torch.nn.Module):
    def __init__(self, model):
        super().__init__()
        self.model = model

    def forward(self, z):
        return self.model(z)


def export_stage(name, model, feeds, outputs, dynamic_shapes, atol):
    path = OUT / (name + ".onnx")
    model.eval()
    with torch.no_grad():
        reference = model(**feeds)
        torch.onnx.export(
            model, args=(), kwargs=feeds, f=str(path), dynamo=True,
            input_names=list(feeds), output_names=outputs,
            dynamic_shapes=dynamic_shapes, external_data=False,
        )
    onnx.checker.check_model(str(path))
    session = ort.InferenceSession(str(path), providers=["CPUExecutionProvider"])
    actual = session.run(None, {k: v.detach().numpy() for k, v in feeds.items()})
    expected = reference if isinstance(reference, (tuple, list)) else (reference,)
    assert len(actual) == len(expected), name + ": output count mismatch"
    for i, (got, want) in enumerate(zip(actual, expected)):
        np.testing.assert_allclose(got, want.detach().numpy(), atol=atol, rtol=atol,
                                   err_msg=f"{name} output {i}")
    return {"sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
            "size_bytes": path.stat().st_size,
            "inputs": [x.name for x in session.get_inputs()],
            "outputs": [x.name for x in session.get_outputs()],
            "atol": atol}


def main():
    from ema_lightning.model import load_acoustic
    from ema_lightning.decoder import load_decoder
    OUT.mkdir(parents=True, exist_ok=True)
    torch.manual_seed(42)
    acoustic = load_acoustic(hf_hub_download(MODEL_ID, "ema.pt"), torch.device("cpu")).eval()
    decoder = load_decoder(hf_hub_download(MODEL_ID, "decoder.pt"), torch.device("cpu")).eval()
    d, ld = acoustic.d, acoustic.latent_dim
    b, l, t = 1, 8, 32
    dynamic = lambda **dims: {k: {axis: label for axis, label in axes.items()} for k, axes in dims.items()}
    # ONNX Community-style named dynamic contracts for each independently exported stage.
    contracts = [
        ("text_stage", Text(acoustic),
         {"ids": torch.ones(b, l, dtype=torch.long), "mask": torch.ones(b, l, dtype=torch.bool)},
         ["h", "dur"], dynamic(ids={0:"batch",1:"letters"}, mask={0:"batch",1:"letters"}), 1e-4),
        ("sound_stage", Sound(acoustic),
         {"h": torch.randn(b,l,d), "dur": torch.rand(b,l)+0.5,
          "mask": torch.ones(b,l,dtype=torch.bool), "cw": torch.zeros(b,l,dtype=torch.long),
          "wstart": torch.zeros(b,l,dtype=torch.long), "fw": torch.zeros(b,t,dtype=torch.long),
          "fp": torch.zeros(b,t), "fmask": torch.ones(b,t,dtype=torch.bool),
          "noise": torch.randn(b,len(acoustic.times),t,ld)},
         ["latents"],
         dynamic(h={0:"batch",1:"letters"},dur={0:"batch",1:"letters"},
                 mask={0:"batch",1:"letters"},cw={0:"batch",1:"letters"},
                 wstart={0:"batch",1:"letters"},fw={0:"batch",1:"frames"},
                 fp={0:"batch",1:"frames"},fmask={0:"batch",1:"frames"},
                 noise={0:"batch",2:"frames"}), 1e-3),
        ("decoder", Decoder(decoder), {"z": torch.randn(b,ld,t)},
         ["audio"], dynamic(z={0:"batch",2:"frames"}), 1e-4),
    ]
    manifest = {"source": MODEL_ID, "framework": "optimum-community-style-custom-export",
                "stages": {}}
    for name, module, feeds, outputs, axes, tol in contracts:
        print("Exporting", name, flush=True)
        manifest["stages"][name] = export_stage(name, module, feeds, outputs, axes, tol)
    (OUT/"vocab.json").write_text(json.dumps(
        {"vocab": acoustic.vocab, "stoi": acoustic.stoi, "times": acoustic.times,
         "latent_dim": acoustic.latent_dim}, ensure_ascii=False, default=lambda x: x.tolist()), encoding="utf-8")
    (OUT/"manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    print("Validated all three ONNX stages against PyTorch.")


if __name__ == "__main__":
    main()
