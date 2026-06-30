# NLPhotos — Setup & Run Guide

Fully offline, on-device semantic photo search for Android. Find photos by
describing their content ("a lake", "a car", "a bank document") — no internet,
nothing leaves the device.

---

## 1. Prerequisites

| Tool | Version used | Notes |
|------|--------------|-------|
| JDK | 17 | Required by the Android Gradle Plugin 8.5 |
| Android SDK | platform **35**, build-tools 35 | Set `ANDROID_HOME` (e.g. `~/Android/Sdk`) |
| Android device | API 26+ (Android 8+) | A physical phone over USB with **USB debugging** on, or an emulator |
| Python | **3.11** | Only needed once, to generate the model files (the default 3.12+/3.14 may lack PyTorch wheels) |
| Gradle | wrapper included | Use `./gradlew` — no system Gradle needed |

Confirm your device is connected:

```bash
adb devices        # should list your device as "device"
```

---

## 2. Generate the model assets (one-time)

The CLIP model files are **not** committed to git (they are large and
regenerable). Create a Python env and produce them before the first build.

```bash
# From the repo root
python3.11 -m venv .venv-export
.venv-export/bin/pip install --upgrade pip
.venv-export/bin/pip install torch torchvision --index-url https://download.pytorch.org/whl/cpu
.venv-export/bin/pip install open_clip_torch onnx onnxconverter-common onnxruntime numpy pillow

# 1) Export the CLIP ViT-B/32 encoders + tokenizer assets + parity fixtures
.venv-export/bin/python tools/export_model.py
.venv-export/bin/python tools/make_parity_fixtures.py

# 2) Quantize to int8 (this is what the app ships — small + stable on-device)
.venv-export/bin/python tools/quantize_int8.py
```

After this you should have (≈230 MB total):

```
app/src/main/assets/models/clip-vit-b32/
  image_encoder.fp16.onnx   # int8-quantized image encoder (filename kept for the descriptor)
  text_encoder.fp16.onnx    # int8-quantized text encoder
  vocab.json                # CLIP BPE vocab
  merges.txt                # CLIP BPE merges
```

> **Why int8?** fp16 overflows in true fp16 on-device (the desktop runtime hides
> this by upcasting). int8 dynamic quantization keeps activations in fp32 — no
> overflow, stable, and smaller. On-device accuracy: cosine ≥ 0.89 vs the
> reference model.

---

## 3. Configure the SDK location

Create `local.properties` in the repo root (once):

```
sdk.dir=/home/<you>/Android/Sdk
```

(Or `export ANDROID_HOME=/home/<you>/Android/Sdk` in your shell.)

---

## 4. Build & install on the phone

```bash
export ANDROID_HOME=$HOME/Android/Sdk

# Build the debug APK
./gradlew :app:assembleDebug

# Install onto the connected device
./gradlew :app:installDebug
```

The APK is ~230 MB (it bundles the model), so the first install takes a couple
of minutes. Then open **NLPhotos** from the app drawer.

### Quick reinstall script

`tools/reinstall.sh` wraps build + (re)install + launch (and optional
screenshot) so you don't have to remember the commands. Run it from the repo
root:

```bash
./tools/reinstall.sh           # build + install, keeps app data/permissions
./tools/reinstall.sh --clean   # uninstall first, then a fresh install
./tools/reinstall.sh --shot    # also save a screenshot to /tmp/nlphotos.png
```

Flags combine, e.g. `./tools/reinstall.sh --clean --shot`. It defaults
`ANDROID_HOME` to `~/Android/Sdk` (respects yours if already set), `cd`s to the
repo root automatically, and exits early with a clear message if no device is
connected.

---

## 5. Using the app

1. Tap **Grant photo access** and choose **Allow all** (or "Select photos" to
   limit it to a few).
2. The app **indexes** your gallery on-device — a progress bar shows done/total.
   First run over a large library takes a while; it is incremental afterward.
3. Type a description in the search bar (e.g. `lake`, `car`, `document`) and
   press search. Results are ranked best-match first; tap one for full screen.

Everything runs locally. The app declares **no INTERNET permission**, so it
cannot send any data off the device (verifiable by inspecting the APK).

---

## 6. Running the tests

```bash
export ANDROID_HOME=$HOME/Android/Sdk

# JVM unit tests (tokenizer, vector math, store, scanner diff, search, privacy)
./gradlew :app:testDebugUnitTest

# On-device instrumented tests (needs a connected device):
#   - accuracy parity gate (embeddings match the reference within the 0.83 bar)
#   - indexer, search pipeline, merged-manifest privacy
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.nlphotos.EmbeddingParityTest
```

> Note: `connectedDebugAndroidTest` uninstalls the app when it finishes. Re-run
> `./gradlew :app:installDebug` (or `./tools/reinstall.sh`) afterward to keep
> NLPhotos on the phone.

---

## 7. CI and releases

**CI (GitHub Actions)** — `.github/workflows/ci.yml` runs on every merge to
`main` (and via manual dispatch). It compiles the app and runs the JVM unit
tests. It does **not** need the ONNX models: the build only packages whatever
assets exist, and the unit tests use the committed vocab/merges fixtures.
Feature-branch pushes are intentionally not built.

**Releases** — cut from your machine with `tools/release.sh` (it needs the
~230 MB models, which only live locally). It verifies, builds the APK, and
creates the GitHub release via the `gh` CLI:

```bash
./tools/release.sh v0.2.0                 # debug APK + auto-generated notes
./tools/release.sh v0.2.0 --release       # signed release APK instead
./tools/release.sh v0.2.0 --draft         # create as a draft to review first
./tools/release.sh v0.2.0 --notes "…"     # custom release notes
```

Requires `gh auth login` and the models present under
`app/src/main/assets/models/`.

---

## 8. Troubleshooting

| Symptom | Fix |
|---|---|
| `Could not find an implementation for ConvInteger/ArgMax` | Regenerate models with `tools/quantize_int8.py` (it quantizes MatMul-only and casts ArgMax to int32). |
| Indexing finds very few photos | You granted "Select photos" (partial access). Settings → Apps → NLPhotos → Permissions → Photos → **Allow all**. |
| `INSTALL_FAILED_INSUFFICIENT_STORAGE` | Free up space; the APK bundles ~230 MB of model. |
| Gradle wants to download dependencies offline | Drop `--offline`, or pre-warm the cache with one online build. |

---

## Project layout

```
app/src/main/java/com/nlphotos/
  model/   ModelDescriptor + CLIP descriptor (all model constants live here)
  ml/      BpeTokenizer, ImagePreprocessor, OnnxEmbeddingEngine
  data/    Room-backed IndexStore (photo vectors)
  scan/    MediaStore scanner + diff
  index/   WorkManager indexer (batched, resumable, progress)
  search/  VectorBuffer + SearchEngine (dot-product ranking)
  ui/      Compose search screen
tools/     Python: model export, quantization, parity fixtures
docs/design/      design specs
docs/plans/       implementation plans
```
