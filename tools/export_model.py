"""
tools/export_model.py
Export CLIP ViT-B/32 image and text encoders to ONNX (fp16).

Vocab extraction notes
----------------------
open_clip's SimpleTokenizer stores:
  - tokenizer.encoder  : dict[str, int]   (byte-pair vocab, e.g. {"!</s>": 0, ...})
  - tokenizer.bpe_ranks: dict[tuple, int] (merge rules ranked by frequency)

We write:
  vocab.json  -> JSON object mapping token-string -> token-id
                 PLUS the two special tokens sot_token / eot_token added by open_clip.
  merges.txt  -> One merge rule per line "left right", sorted by rank (ascending),
                 preceded by a "#version: 0.2" header (same convention as HuggingFace
                 GPT-2 merges files so the Kotlin BPE implementation can reuse that
                 well-tested format).

The Kotlin tokenizer must use the SAME vocab + merges to produce identical token ids.
"""

import os, sys, json
import numpy as np
import torch
import open_clip
import onnx
from PIL import Image
from onnxconverter_common import float16, auto_mixed_precision

OUT_DIR = os.path.join(
    os.path.dirname(__file__),
    "../app/src/main/assets/models/clip-vit-b32",
)
os.makedirs(OUT_DIR, exist_ok=True)


# ── 1. Load model ──────────────────────────────────────────────────────────────
print("Loading CLIP ViT-B/32 …")
model, _, _ = open_clip.create_model_and_transforms("ViT-B-32", pretrained="openai")
model.eval()


# ── 2. Wrapper modules with in-graph L2 normalisation ─────────────────────────
class ImageEncoder(torch.nn.Module):
    def __init__(self, m):
        super().__init__()
        self.m = m

    def forward(self, pixel_values):
        f = self.m.encode_image(pixel_values)
        return f / f.norm(dim=-1, keepdim=True)


class TextEncoder(torch.nn.Module):
    def __init__(self, m):
        super().__init__()
        self.m = m

    def forward(self, input_ids):
        f = self.m.encode_text(input_ids)
        return f / f.norm(dim=-1, keepdim=True)


# ── 3. Export fp32 ONNX then convert to fp16 ──────────────────────────────────
def export_fp16(module, dummy_input, name, input_names, output_names, dynamic_axes, feed):
    fp32_path = os.path.join(OUT_DIR, f"{name}.onnx")
    fp16_path = os.path.join(OUT_DIR, f"{name}.fp16.onnx")

    print(f"  Tracing {name} …")
    torch.onnx.export(
        module,
        dummy_input,
        fp32_path,
        input_names=input_names,
        output_names=output_names,
        dynamic_axes=dynamic_axes,
        opset_version=17,
    )

    # Plain float16 conversion overflows on real (high-frequency) inputs when the
    # device executes ops in TRUE fp16 (desktop ORT hides this by upcasting to
    # fp32). auto_convert_mixed_precision runs the fp32 model on a representative
    # `feed`, then keeps the overflow/precision-sensitive ops in fp32 while the
    # rest become fp16 — restoring on-device accuracy at near-fp16 size.
    # keep_io_types=True keeps graph I/O float32 so the Android engine stays simple.
    print(f"  Mixed-precision converting {name} (validated on real input) …")
    m32 = onnx.load(fp32_path)
    m_mixed = auto_mixed_precision.auto_convert_mixed_precision(
        m32, feed, rtol=0.01, atol=0.001, keep_io_types=True
    )
    onnx.save(m_mixed, fp16_path)
    os.remove(fp32_path)   # keep only the mixed-precision model
    print(f"  Saved {fp16_path}")


# Representative feeds for mixed-precision validation: a REAL preprocessed image
# (high-frequency content that triggers fp16 overflow) and a real token sequence.
_mean = np.array([0.48145466, 0.4578275, 0.40821073], dtype=np.float32)
_std = np.array([0.26862954, 0.26130258, 0.27577711], dtype=np.float32)
_img_path = os.path.join(os.path.dirname(__file__),
                         "../app/src/androidTest/assets/parity/img_001.png")
_im = np.asarray(Image.open(_img_path).convert("RGB")).astype(np.float32) / 255.0
_img_feed = ((_im - _mean) / _std).transpose(2, 0, 1)[None].astype(np.float32)

_tok = open_clip.get_tokenizer("ViT-B-32")
_txt_feed = _tok(["a photo of a lake"]).to(torch.int64).numpy()

dummy_img = torch.randn(1, 3, 224, 224)
export_fp16(
    ImageEncoder(model),
    dummy_img,
    "image_encoder",
    input_names=["pixel_values"],
    output_names=["image_embeds"],
    dynamic_axes={"pixel_values": {0: "b"}},
    feed={"pixel_values": _img_feed},
)

dummy_txt = torch.randint(0, 49407, (1, 77), dtype=torch.long)
export_fp16(
    TextEncoder(model),
    dummy_txt,
    "text_encoder",
    input_names=["input_ids"],
    output_names=["text_embeds"],
    dynamic_axes={"input_ids": {0: "b"}},
    feed={"input_ids": _txt_feed},
)


# ── 4. Extract vocab.json and merges.txt from open_clip's tokenizer ───────────
print("Extracting BPE vocab and merges …")
tokenizer = open_clip.get_tokenizer("ViT-B-32")
# open_clip wraps the SimpleTokenizer; unwrap it
tok = tokenizer.tokenizer if hasattr(tokenizer, "tokenizer") else tokenizer

# tok.encoder  : {token_str: int}
# tok.bpe_ranks: {(left, right): rank}

vocab = dict(tok.encoder)  # shallow copy
# Confirm special tokens are present (sot=49406, eot=49407)
# open_clip's SimpleTokenizer uses '<start_of_text>'/'<end_of_text>' as internal keys
# (not '<|startoftext|>'/'<|endoftext|>').  The sot/eot_token_id attributes are the
# authoritative source of truth; we verify by those, not by key name.
assert tok.sot_token_id == 49406, f"sot_token_id mismatch: {tok.sot_token_id}"
assert tok.eot_token_id == 49407, f"eot_token_id mismatch: {tok.eot_token_id}"

vocab_path = os.path.join(OUT_DIR, "vocab.json")
with open(vocab_path, "w", encoding="utf-8") as f:
    json.dump(vocab, f, ensure_ascii=False)
print(f"  vocab.json: {len(vocab)} tokens → {vocab_path}")

# merges.txt: sorted by rank so index == rank
merges_sorted = sorted(tok.bpe_ranks.items(), key=lambda x: x[1])
merges_path = os.path.join(OUT_DIR, "merges.txt")
with open(merges_path, "w", encoding="utf-8") as f:
    f.write("#version: 0.2\n")
    for (left, right), _ in merges_sorted:
        f.write(f"{left} {right}\n")
print(f"  merges.txt: {len(merges_sorted)} merge rules → {merges_path}")


print("\nAll done.")
