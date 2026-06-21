"""
tools/make_parity_fixtures.py
Generate reference embeddings (parity fixtures) for the on-device accuracy test.

Test images: 3 deterministic synthetic PNGs generated with a fixed random seed.
             They contain no real photos so can be freely committed to the repo.
             The key property is that the SAME byte content is run through both
             the reference Python pipeline and the on-device ONNX pipeline, so
             any difference in embedding reveals a porting error, not a data
             mismatch.

Token-id consistency check
---------------------------
After generating fixtures, the script re-encodes every query phrase using a
from-scratch BPE implementation driven only by the emitted vocab.json +
merges.txt, then asserts that the resulting token ids match those produced by
open_clip's own tokenizer.  If they differ the script exits non-zero so CI
catches the inconsistency before it becomes a silent accuracy bug.
"""

import os, sys, json, gzip
import hashlib
from functools import lru_cache
try:
    import regex as re   # open_clip uses the 'regex' module for \p{L} / \p{N} support
except ImportError:
    import re            # fallback (ASCII-only text will still work)

import numpy as np
import torch
import open_clip
from PIL import Image, ImageDraw

# ── Paths ──────────────────────────────────────────────────────────────────────
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PARITY_DIR = os.path.join(ROOT, "app/src/androidTest/assets/parity")
MODEL_DIR = os.path.join(ROOT, "app/src/main/assets/models/clip-vit-b32")
CLIP_RES_DIR = os.path.join(ROOT, "app/src/test/resources/clip")
os.makedirs(PARITY_DIR, exist_ok=True)
os.makedirs(CLIP_RES_DIR, exist_ok=True)

# ── 1. Create deterministic synthetic test images ─────────────────────────────
def make_test_images():
    """Generate 3 synthetic PNGs with fixed content (no randomness needed)."""
    images = {}

    # img_000: horizontal gradient (red channel ramp)
    arr = np.zeros((224, 224, 3), dtype=np.uint8)
    arr[:, :, 0] = np.tile(np.arange(224, dtype=np.uint8), (224, 1))
    arr[:, :, 1] = 100
    arr[:, :, 2] = 50
    img0 = Image.fromarray(arr, "RGB")
    p0 = os.path.join(PARITY_DIR, "img_000.png")
    img0.save(p0)
    images["img_000.png"] = img0

    # img_001: blue/green diagonal gradient
    arr = np.zeros((224, 224, 3), dtype=np.uint8)
    for i in range(224):
        for j in range(224):
            arr[i, j, 1] = (i + j) % 256
            arr[i, j, 2] = (i * 2) % 256
    img1 = Image.fromarray(arr, "RGB")
    p1 = os.path.join(PARITY_DIR, "img_001.png")
    img1.save(p1)
    images["img_001.png"] = img1

    # img_002: simple geometric shapes on white background
    img2 = Image.new("RGB", (224, 224), color=(255, 255, 255))
    draw = ImageDraw.Draw(img2)
    draw.rectangle([30, 30, 100, 100], fill=(200, 50, 50))
    draw.ellipse([120, 80, 200, 160], fill=(50, 50, 200))
    draw.polygon([(112, 20), (180, 100), (44, 100)], fill=(50, 180, 50))
    p2 = os.path.join(PARITY_DIR, "img_002.png")
    img2.save(p2)
    images["img_002.png"] = img2

    print(f"  Saved {len(images)} test images to {PARITY_DIR}")
    return images


# ── 2. Load model ──────────────────────────────────────────────────────────────
print("Loading CLIP ViT-B/32 …")
model, _, preprocess = open_clip.create_model_and_transforms("ViT-B-32", pretrained="openai")
tokenizer = open_clip.get_tokenizer("ViT-B-32")
model.eval()

# ── 3. Generate embeddings ─────────────────────────────────────────────────────
QUERIES = [
    "a photo of a lake",
    "a car",
    "a bank document",
    "a dog on the beach",
    "a diagram",
]

print("Generating test images …")
pil_images = make_test_images()

out = {"images": {}, "queries": {}}

with torch.no_grad():
    for name, pil_img in pil_images.items():
        t = preprocess(pil_img.convert("RGB")).unsqueeze(0)
        e = model.encode_image(t)
        e = e / e.norm(dim=-1, keepdim=True)
        out["images"][name] = e[0].tolist()
        print(f"  image embed: {name}  norm={float(e.norm()):.6f}")

    for q in QUERIES:
        ids = tokenizer([q])
        e = model.encode_text(ids)
        e = e / e.norm(dim=-1, keepdim=True)
        out["queries"][q] = e[0].tolist()
        print(f"  text  embed: {q!r}")

fixtures_path = os.path.join(PARITY_DIR, "fixtures.json")
with open(fixtures_path, "w") as f:
    json.dump(out, f)
print(f"Wrote {fixtures_path}")


# ── 4. Write token-id fixtures for Kotlin tokenizer test ──────────────────────
tok = tokenizer.tokenizer if hasattr(tokenizer, "tokenizer") else tokenizer
SOT = 49406
EOT = 49407
CTX = 77

def clip_tokenize_ref(text: str):
    """Tokenize a single string using open_clip's tokenizer; return list of ints len=77."""
    ids_tensor = tokenizer([text])  # shape [1, 77]
    return ids_tensor[0].tolist()

token_fixtures = {}
for phrase in QUERIES + ["a diagram"]:  # "a diagram" already in QUERIES but be explicit
    token_fixtures[phrase] = clip_tokenize_ref(phrase)

token_fixtures_path = os.path.join(CLIP_RES_DIR, "clip_token_fixtures.json")
with open(token_fixtures_path, "w") as f:
    json.dump(token_fixtures, f, indent=2)
print(f"Wrote {token_fixtures_path}")


# ── 5. Copy vocab/merges to test resources ────────────────────────────────────
import shutil
for fname in ("vocab.json", "merges.txt"):
    src = os.path.join(MODEL_DIR, fname)
    dst = os.path.join(CLIP_RES_DIR, fname)
    if os.path.exists(src):
        shutil.copy2(src, dst)
        print(f"Copied {fname} to {CLIP_RES_DIR}")
    else:
        print(f"WARNING: {src} not found — run export_model.py first")


# ── 6. Token-id consistency check ─────────────────────────────────────────────
"""
We re-implement the CLIP BPE tokenization from scratch using only our emitted
vocab.json + merges.txt, then assert the token ids match open_clip's output.
This guarantees the Kotlin implementation can reproduce the same ids.
"""

print("\nRunning token-id consistency check …")

vocab_path = os.path.join(MODEL_DIR, "vocab.json")
merges_path = os.path.join(MODEL_DIR, "merges.txt")

if not os.path.exists(vocab_path) or not os.path.exists(merges_path):
    print("SKIP consistency check: vocab/merges not present (run export_model.py first)")
    sys.exit(0)

with open(vocab_path) as f:
    vocab_check = json.load(f)

with open(merges_path) as f:
    lines = [l.rstrip("\n") for l in f if not l.startswith("#")]
bpe_ranks_check = {tuple(line.split(" ", 1)): i for i, line in enumerate(lines) if line}

# ── Minimal BPE implementation mirroring open_clip's SimpleTokenizer ──────────
# byte encoder (same as GPT-2)
def bytes_to_unicode():
    bs = (
        list(range(ord("!"), ord("~") + 1))
        + list(range(ord("¡"), ord("¬") + 1))
        + list(range(ord("®"), ord("ÿ") + 1))
    )
    cs = list(bs)
    n = 0
    for b in range(256):
        if b not in bs:
            bs.append(b)
            cs.append(256 + n)
            n += 1
    return dict(zip(bs, [chr(c) for c in cs]))

BYTE_ENCODER = bytes_to_unicode()

def get_pairs(word):
    pairs = set()
    prev = word[0]
    for char in word[1:]:
        pairs.add((prev, char))
        prev = char
    return pairs

@lru_cache(maxsize=16384)
def bpe_encode(token: str):
    # Mirrors open_clip's SimpleTokenizer.bpe():
    #   last character gets '</w>' appended (not as a separate element)
    chars = [BYTE_ENCODER[b] for b in token.encode("utf-8")]
    word = tuple(chars[:-1]) + (chars[-1] + "</w>",)
    pairs = get_pairs(word)
    if not pairs:
        return word
    while True:
        bigram = min(pairs, key=lambda p: bpe_ranks_check.get(p, float("inf")))
        if bigram not in bpe_ranks_check:
            break
        first, second = bigram
        new_word = []
        i = 0
        while i < len(word):
            try:
                j = word.index(first, i)
            except ValueError:
                new_word.extend(word[i:])
                break
            new_word.extend(word[i:j])
            i = j
            if word[i] == first and i < len(word) - 1 and word[i + 1] == second:
                new_word.append(first + second)
                i += 2
            else:
                new_word.append(word[i])
                i += 1
        word = tuple(new_word)
        if len(word) == 1:
            break
        pairs = get_pairs(word)
    return word

# Pattern from open_clip's SimpleTokenizer — uses the 'regex' module for
# Unicode property classes \p{L} (letter) and \p{N} (number).
# Special tokens use '<start_of_text>'/'<end_of_text>' (open_clip's internal names).
PAT = re.compile(
    r"""<start_of_text>|<end_of_text>|'s|'t|'re|'ve|'m|'ll|'d|[\p{L}]+|[\p{N}]|[^\s\p{L}\p{N}]+""",
    re.IGNORECASE,
)

def tokenize_scratch(text: str):
    text = re.sub(r"\s+", " ", text.strip().lower())
    tokens = [SOT]
    for token in re.findall(PAT, text):
        bpe_tokens = bpe_encode(token.strip())
        for bt in bpe_tokens:
            if bt in vocab_check:
                tokens.append(vocab_check[bt])
    tokens.append(EOT)
    # pad / truncate to CTX
    tokens = tokens[:CTX]
    tokens += [0] * (CTX - len(tokens))
    return tokens

fail = False
for phrase, ref_ids in token_fixtures.items():
    scratch_ids = tokenize_scratch(phrase)
    if scratch_ids != ref_ids:
        print(f"  MISMATCH for {phrase!r}")
        print(f"    open_clip : {ref_ids[:20]}")
        print(f"    from-file : {scratch_ids[:20]}")
        fail = True
    else:
        print(f"  OK: {phrase!r}")

if fail:
    print("\nConsistency check FAILED — vocab/merges do not reproduce open_clip token ids.")
    sys.exit(1)
else:
    print("\nConsistency check PASSED — vocab/merges reproduce open_clip token ids exactly.")
