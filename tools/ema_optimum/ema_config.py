"""Real Hugging Face Optimum ONNX export configurations for EMA Lightning.

EMA is not a Transformers checkpoint. The ONNX Community's generic CLI cannot
discover it automatically, so we implement three explicit Optimum OnnxConfig
subclasses with real input/output axis maps and dummy-input generation. The
export driver invokes optimum.exporters.onnx.export for each original module.
No third-party EMA ONNX converter or ONNX checkpoint is imported.
"""
from collections import OrderedDict

import numpy as np
import torch
from optimum.exporters.onnx import OnnxConfig
from optimum.utils import NormalizedConfig
from transformers import PretrainedConfig


class EMAStageOnnxConfig(OnnxConfig):
    DEFAULT_ONNX_OPSET = 18
    NORMALIZED_CONFIG_CLASS = NormalizedConfig

    def __init__(self, stage: str, channels: int, latent_dim: int, steps: int, hop: int):
        if stage not in {"text_stage", "sound_stage", "decoder"}:
            raise ValueError(f"Unknown EMA stage: {stage}")
        self.stage = stage
        self.channels = channels
        self.latent_dim = latent_dim
        self.steps = steps
        self.hop = hop
        super().__init__(PretrainedConfig(return_dict=True), task="text-to-audio")

    @property
    def inputs(self):
        batch = {0: "batch_size"}
        letters = {0: "batch_size", 1: "letters"}
        frames = {0: "batch_size", 1: "frames"}
        if self.stage == "text_stage":
            return OrderedDict(ids=letters, mask=letters)
        if self.stage == "sound_stage":
            return OrderedDict(
                h=letters, dur=letters, mask=letters,
                cw=letters, wstart=letters, fw=frames, fp=frames,
                fmask=frames, noise={0: "batch_size", 2: "frames"},
            )
        return OrderedDict(z={0: "batch_size", 2: "frames"})

    @property
    def outputs(self):
        if self.stage == "text_stage":
            return OrderedDict(h={0: "batch_size", 1: "letters"},
                               dur={0: "batch_size", 1: "letters"})
        if self.stage == "sound_stage":
            return OrderedDict(latents={0: "batch_size", 1: "frames"})
        return OrderedDict(audio={0: "batch_size", 1: "samples"})

    def generate_dummy_inputs(
        self, framework="pt", batch_size=2, sequence_length=16,
        nb_max_frames=32, **kwargs,
    ):
        """Explicit seeded test tensors, valid for Optimum's PyTorch/NumPy tracing."""
        if framework not in ("pt", "np"):
            raise ValueError(f"Unsupported framework: {framework}")
        b, l, t = int(batch_size), int(sequence_length), int(nb_max_frames)
        if b < 1 or l < 2 or t < 2:
            raise ValueError("Export dimensions require batch >=1, letters >=2, frames >=2")
        g = torch.Generator(device="cpu").manual_seed(1729)
        if self.stage == "text_stage":
            # 1 is a known valid character ID; 0 is padding.
            feeds = OrderedDict(
                ids=torch.ones(b, l, dtype=torch.long),
                mask=torch.ones(b, l, dtype=torch.bool),
            )
        elif self.stage == "sound_stage":
            feeds = OrderedDict(
                h=torch.randn(b, l, self.channels, generator=g),
                dur=0.5 + torch.rand(b, l, generator=g),
                mask=torch.ones(b, l, dtype=torch.bool),
                cw=torch.zeros(b, l, dtype=torch.long),
                wstart=torch.zeros(b, l, dtype=torch.long),
                fw=torch.zeros(b, t, dtype=torch.long),
                fp=torch.zeros(b, t),
                fmask=torch.ones(b, t, dtype=torch.bool),
                noise=torch.randn(b, self.steps, t, self.latent_dim, generator=g),
            )
        else:
            feeds = OrderedDict(z=torch.randn(b, self.latent_dim, t, generator=g))
        if framework == "np":
            return OrderedDict((key, value.numpy()) for key, value in feeds.items())
        return feeds


class StageWrapper(torch.nn.Module):
    """Minimal Optimum-compatible wrapper around the original EMA modules."""

    def __init__(self, model):
        super().__init__()
        self.model = model
        # Optimum accesses model.config.return_dict even for custom modules.
        self.config = PretrainedConfig(return_dict=True)


class TextWrapper(StageWrapper):
    def forward(self, ids, mask):
        h, dur = self.model.text_stage(ids, mask)
        return {"h": h, "dur": dur}


class SoundWrapper(StageWrapper):
    def forward(self, h, dur, mask, cw, wstart, fw, fp, fmask, noise):
        latents = self.model.sound_stage(h, dur, mask, cw, wstart, fw, fp, fmask, noise)
        return {"latents": latents}


class DecoderWrapper(StageWrapper):
    def forward(self, z):
        return {"audio": self.model(z)}


def module_for(stage, acoustic, decoder):
    return {
        "text_stage": lambda: TextWrapper(acoustic),
        "sound_stage": lambda: SoundWrapper(acoustic),
        "decoder": lambda: DecoderWrapper(decoder),
    }[stage]().eval()


def config_for(stage, acoustic, decoder):
    return EMAStageOnnxConfig(
        stage=stage,
        channels=acoustic.d,
        latent_dim=acoustic.latent_dim,
        steps=len(acoustic.times),
        hop=decoder.hop,
    )
