"""TorchScript ONNX symbolics missing from Optimum 0.1.0's PyTorch pathway.

ONNX Community currently pins optimum-onnx==0.1.0 which internally selects
TorchScript export. EMA Lightning uses aten::expm1, not available in TorchScript
ONNX opset 18. Provide a faithful ONNX implementation Exp(x) - 1.
The numerical difference near zero is measured by reference parity tests;
we reject the conversion if these discrepancies exceed the quality gate.
"""
import torch
from torch.onnx import register_custom_op_symbolic


def _expm1(g, x):
    """onnx::Exp(x) minus 1, preserving dtype (EMA uses float32)."""
    one = g.op("Constant", value_t=torch.tensor(1.0, dtype=torch.float32))
    return g.op("Sub", g.op("Exp", x), one)


def register_ema_symbolics(opset=18):
    register_custom_op_symbolic("aten::expm1", _expm1, opset)
    print(f"[OPTIMUM] Installed EMA-specific aten::expm1 symbolic for opset {opset}", flush=True)
