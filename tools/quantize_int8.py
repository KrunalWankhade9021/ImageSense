"""
tools/quantize_int8.py
Produce int8 DYNAMIC-quantized CLIP encoders for on-device use.

Why dynamic int8 (not fp16): on real images the phone executes fp16 ops in TRUE
fp16 and ViT attention activations overflow (desktop ORT hides this by upcasting).
Dynamic int8 quantizes only the weights to int8 while activations are computed in
fp32 at runtime — no overflow, stable on-device, and smaller than fp16.

Outputs (overwriting the asset paths the app loads):
  image_encoder.fp16.onnx   <- int8-dynamic (filename kept for the descriptor)
  text_encoder.fp16.onnx    <- int8-dynamic
fp32 base models are written alongside as *.fp32.onnx for parity comparison.
"""
import os
import numpy as np
import torch
import open_clip
from onnxruntime.quantization import quantize_dynamic, QuantType

OUT_DIR = os.path.join(os.path.dirname(__file__),
                       "../app/src/main/assets/models/clip-vit-b32")

print("Loading CLIP ViT-B/32 …")
model, _, _ = open_clip.create_model_and_transforms("ViT-B-32", pretrained="openai")
model.eval()


class ImageEncoder(torch.nn.Module):
    def __init__(self, m): super().__init__(); self.m = m
    def forward(self, x):
        f = self.m.encode_image(x); return f / f.norm(dim=-1, keepdim=True)


class TextEncoder(torch.nn.Module):
    def __init__(self, m): super().__init__(); self.m = m
    def forward(self, x):
        f = self.m.encode_text(x); return f / f.norm(dim=-1, keepdim=True)


def patch_argmax_to_int32(path):
    """ORT's CPU ArgMax kernel has no int64 implementation, but the EOT-position
    ArgMax in encode_text runs on int64 token ids. Insert a Cast(int64->int32)
    before each ArgMax so it uses a supported type. Output is unchanged."""
    import onnx
    from onnx import helper, TensorProto
    m = onnx.load(path)
    g = m.graph
    new_nodes = []
    patched = 0
    for node in g.node:
        if node.op_type == "ArgMax":
            src = node.input[0]
            cast_out = src + "_i32"
            new_nodes.append(helper.make_node(
                "Cast", [src], [cast_out], to=TensorProto.INT32,
                name=f"cast_argmax_{patched}"))
            node.input[0] = cast_out
            patched += 1
        new_nodes.append(node)
    del g.node[:]
    g.node.extend(new_nodes)
    onnx.save(m, path)
    print(f"  patched {patched} ArgMax node(s) with int32 Cast")


def trace_fp32(module, dummy, name, inames, onames, axes):
    p = os.path.join(OUT_DIR, f"{name}.fp32.onnx")
    torch.onnx.export(module, dummy, p, input_names=inames, output_names=onames,
                      dynamic_axes=axes, opset_version=17)
    return p


def quant(fp32_path, out_name):
    out = os.path.join(OUT_DIR, out_name)
    # Quantize ONLY MatMul (per-channel). Quantizing Conv would emit ConvInteger,
    # which ORT's Android build does not implement; MatMulInteger is supported.
    quantize_dynamic(fp32_path, out, weight_type=QuantType.QInt8,
                     per_channel=True, op_types_to_quantize=["MatMul"])
    print(f"  {out_name}: {os.path.getsize(out)/1e6:.1f} MB")


img_fp32 = trace_fp32(ImageEncoder(model), torch.randn(1, 3, 224, 224),
                      "image_encoder", ["pixel_values"], ["image_embeds"],
                      {"pixel_values": {0: "b"}})
txt_fp32 = trace_fp32(TextEncoder(model), torch.randint(0, 49407, (1, 77), dtype=torch.long),
                      "text_encoder", ["input_ids"], ["text_embeds"],
                      {"input_ids": {0: "b"}})
patch_argmax_to_int32(txt_fp32)   # make ArgMax ORT-Android compatible

print("Quantizing (int8 dynamic) …")
quant(img_fp32, "image_encoder.fp16.onnx")   # filename kept for descriptor
quant(txt_fp32, "text_encoder.fp16.onnx")
print("Done.")
