# Offline Semantic Photo Search (Android) — Design Spec

**Date:** 2026-06-21
**Status:** Approved design, ready for implementation planning

## Problem

Finding a specific photo on a phone is slow and frustrating. The user often does
not remember the filename or capture date, only the *context* of the image
("standing in front of a lake", "a car", "a bank document"). Manual scrolling is
time-consuming and frequently fails.

## Goal

A fully **offline, privacy-preserving Android app** that lets the user search
their photo library using **natural-language descriptions of image content**.

## Core Approach

On-device **CLIP-style semantic search**: an AI model converts each photo into a
512-dimensional "meaning vector". A text query is converted the same way. The
best matches are the photos whose vectors are closest to the query vector. All
computation happens on the device — nothing leaves the phone.

Proven by open-source references:
- `slavabarkov/tidy` — full offline Kotlin + OpenCLIP + ONNX Runtime Android app
- `greyovo/CLIP-android-demo` — quantized CLIP ViT-B/32 on Android

## Platform & Stack

- **Platform:** Android, native **Kotlin**
- **UI:** Jetpack Compose
- **ML runtime:** ONNX Runtime Mobile
- **Persistence:** SQLite (Room)
- **Background work:** WorkManager
- **Model (v1):** quantized **CLIP ViT-B/32** (int8) — image encoder ~91MB +
  text encoder ~61MB, embedding dim 512. Chosen because both Android reference
  projects use it (smoothest path). Architecture allows a later swap to
  **MobileCLIP-S0** with no code changes (strategy "c").

## Hard Constraints (Invariants)

1. **Privacy by construction.** The `INTERNET` permission is **omitted from the
   manifest entirely** — not merely unused. No dependency may pull in
   networking: no analytics, no crash-reporting SDK, no map tiles. Verifiable by
   inspecting the APK.
2. **No hardcoded model constants** in preprocessing or tokenization code. All
   model-specific values live in a `ModelDescriptor`. This is what makes the
   MobileCLIP swap a config change rather than a refactor.
3. **All embedding vectors are L2-normalized at creation.** Therefore cosine
   similarity is a plain dot product everywhere. Every component relies on this.

## Architecture — Six Units

```
┌────────────────────────────────────────────────────────────┐
│                        Android App                          │
│                   (no INTERNET permission)                  │
│                                                             │
│   ┌─────────── Indexer (WorkManager) ───────────┐           │
│   │  drives, batches, resumes, throttles,        │           │
│   │  reports progress                            │           │
│   │   [Photo Scanner] → [Embedding Engine] → [Index Store]   │
│   └──────────────────────────────────────────────┘          │
│                            │ image vectors                   │
│                            ▼                                  │
│   startup load ──► [In-memory normalized vector buffer]      │
│                            ▲                                  │
│   [Search UI] → text → [Embedding Engine] → query vec → dot ─┘
│   (Compose)            (uses ModelDescriptor)               │
└────────────────────────────────────────────────────────────┘
```

1. **Photo Scanner** — enumerates device photos via MediaStore; reports
   new/changed/deleted images by diffing against the Index Store. Knows nothing
   about ML.
2. **Embedding Engine** — wraps ONNX Runtime; `encodeImage(Bitmap)` and
   `encodeText(String)`, both returning normalized 512-D vectors. Driven by a
   `ModelDescriptor`. Owns the BPE tokenizer.
3. **Index Store** — SQLite/Room; persists `(photoId, uri, vector, dateModified)`.
   Insert/update/delete and bulk read.
4. **Indexer / coordinator** — WorkManager-backed. Owns batching, surviving
   process death, resuming from last-indexed offset, thermal throttling, and
   progress reporting. Most bugs will live here.
5. **In-memory vector buffer** — at startup, all vectors load once into a single
   contiguous L2-normalized float buffer. Search runs here (dot product), not
   live SQLite reads. SQLite touched only on index changes.
6. **Search / UI** — Jetpack Compose: search bar, results grid (best match
   first), tap-to-open, and first-run indexing progress.

## Embedding Engine Internals

### ModelDescriptor (the contract)

```kotlin
data class ModelDescriptor(
    val name: String,              // "clip-vit-b32-int8"
    val imageEncoderAsset: String, // image .onnx path
    val textEncoderAsset: String,  // text .onnx path
    val inputResolution: Int,      // 224
    val pixelMean: FloatArray,     // CLIP: [0.4815, 0.4578, 0.4082]
    val pixelStd: FloatArray,      // CLIP: [0.2686, 0.2613, 0.2758]
    val embeddingDim: Int,         // 512
    val tokenizer: TokenizerAsset, // vocab/merges + context length (77)
)
```

All preprocessing/tokenization reads from the descriptor; **no literals** in that
code.

### Image path: `Bitmap → FloatArray[1,3,R,R]`
1. Downsample-decode the bitmap toward `inputResolution` (never decode full-size).
2. Center-crop/resize to exactly `inputResolution²`.
3. CHW float tensor, scale to [0,1], apply `(x − pixelMean)/pixelStd` per channel.
4. Image encoder → raw vector → **L2-normalize**.

### Text path: `String → FloatArray[embeddingDim]`
1. Lowercase + clean → **BPE tokenize** using descriptor vocab/merges.
2. Wrap with start/end tokens, pad/truncate to context length (77), build mask.
3. Text encoder → raw vector → **L2-normalize**.

### Engine interface
```kotlin
interface EmbeddingEngine {
    fun encodeImage(bitmap: Bitmap): FloatArray
    fun encodeText(query: String): FloatArray
}
```

Guarantees: outputs already normalized; ONNX sessions created once and reused;
tokenizer unit-testable in pure JVM against known CLIP token outputs (the #1
failure mode).

## Data Flow

### Indexing (first launch + background)
1. Scanner queries MediaStore → `(photoId, uri, dateModified)` list.
2. Diff against Index Store → new/changed (encode) and deleted (remove).
3. For each: downsample-decode → resize 224² → image encoder → normalize → store.
4. Indexer runs this in batches via WorkManager (survives app close, resumes,
   throttles). Progress shown in UI.
5. Later launches process only the diff (fast).

### Search
1. User types e.g. "standing in front of a lake".
2. Text encoder → normalized query vector.
3. Dot product vs in-memory buffer → sort → top N (e.g. 100).
4. Grid shows results best-first; tap opens full image.

## Scope

### v1 (build now)
- On-device indexing of the gallery
- Natural-language text → image search
- Results grid + open photo
- Incremental re-indexing
- Zero network permission

### Not in v1 (later)
- OCR / reading text inside documents (the "bank account number" case)
- Image-to-image search (architecture already supports it via the buffer)
- MobileCLIP-S0 swap (descriptor makes it trivial)
- Albums, favorites, sharing, multi-language

## Known Limitation

CLIP matches images by **visual concept** ("lake", "car", "a document") but does
**not read text inside images**. Searching by literal text content (e.g. an
account number on a statement) requires OCR — deferred to a later version.

## Testing & Risks

- **Pure JVM unit tests:** Index Store CRUD + dot-product math, Scanner diff
  logic, vector normalization, BPE tokenizer vs known token outputs.
- **Instrumented tests:** Embedding Engine produces stable vectors for a known
  image; end-to-end "index N test images → query → expected top result".
- **Top risks & mitigations:**
  1. Model export correctness → reuse reference projects' ONNX models.
  2. Indexing performance/battery on large libraries → batched WorkManager +
     progress + throttling.
  3. Memory during bitmap decode → downsample before encode.
