"""EMA Lightning ONNX Community-style interface specifications.

Optimum ONNX custom architectures normally register an OnnxConfig for a
Transformers PreTrainedModel. EMA Lightning is *not* a Transformers model:
its three independent stages must be wrapped and exported separately.
These explicit specs are the source of truth for the custom exporter,
and can later be adapted to Optimum's model registry without pretending
that optimum-cli export supports this model out of the box.
"""
from dataclasses import dataclass

@dataclass(frozen=True)
class StageSpec:
    name: str
    inputs: tuple[str, ...]
    outputs: tuple[str, ...]
    tolerance: float

STAGES = (
    StageSpec("text_stage", ("ids", "mask"), ("h", "dur"), 1e-4),
    StageSpec("sound_stage", ("h", "dur", "mask", "cw", "wstart", "fw", "fp", "fmask", "noise"), ("latents",), 1e-3),
    StageSpec("decoder", ("z",), ("audio",), 1e-4),
)

def validate_specs():
    assert len({s.name for s in STAGES}) == len(STAGES)
    for s in STAGES:
        assert s.inputs and s.outputs and s.tolerance > 0
