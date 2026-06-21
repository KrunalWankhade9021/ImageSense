# Offline Semantic Photo Search Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A fully offline Android app that finds photos by natural-language description of their content, with search accuracy as the top priority.

**Architecture:** Native Kotlin app. A CLIP ViT-B/32 (fp16) model, split into image and text ONNX encoders, runs on-device via ONNX Runtime. Photos are indexed in the background (WorkManager) into a Room DB as L2-normalized 512-D vectors; search loads those vectors into a contiguous in-memory buffer and ranks by dot product. No network access exists in the app at all.

**Tech Stack:** Kotlin, Jetpack Compose, ONNX Runtime Mobile, Room (SQLite), WorkManager, JUnit/Robolectric (JVM tests), AndroidX Test (instrumented). Python + PyTorch/open_clip + onnx for the model-export and parity fixtures.

## Global Constraints

- **Accuracy is the highest priority.** Every preprocessing/tokenization detail must match the reference model exactly; correctness is verified by a parity harness (cosine > 0.99 vs reference Python embeddings) before any UI work is trusted.
- **No `INTERNET` permission.** It must NOT appear in `AndroidManifest.xml`. No dependency may pull in networking (no analytics, no crash reporting, no remote config). Verified by an automated manifest/merge check.
- **No hardcoded model constants** in preprocessing or tokenizer code. All model-specific values come from `ModelDescriptor`.
- **All embedding vectors are L2-normalized at creation.** Cosine similarity is therefore a plain dot product everywhere.
- **Model (v1):** CLIP ViT-B/32, **fp16** ONNX, embedding dim **512**, input resolution **224**, context length **77**.
- **Min SDK 26, target/compile SDK 35** (SDK 35 is the installed platform). Kotlin 1.9+, AGP 8.x.

---

## File Structure

```
app/
  src/main/
    AndroidManifest.xml                         # NO INTERNET permission
    assets/models/clip-vit-b32/
      image_encoder.fp16.onnx
      text_encoder.fp16.onnx
      vocab.json                                # CLIP BPE vocab
      merges.txt                                # CLIP BPE merges
    java/com/nlphotos/
      model/ModelDescriptor.kt                  # config contract
      model/Models.kt                           # the ViT-B/32 descriptor instance
      ml/BpeTokenizer.kt                         # text -> token ids
      ml/ImagePreprocessor.kt                    # Bitmap -> float CHW tensor
      ml/EmbeddingEngine.kt                       # interface
      ml/OnnxEmbeddingEngine.kt                   # ONNX Runtime impl
      data/PhotoEntity.kt                         # Room entity
      data/PhotoDao.kt                            # Room DAO
      data/IndexDatabase.kt                       # Room DB
      data/IndexStore.kt                          # repository over DAO
      scan/PhotoScanner.kt                        # MediaStore enumeration + diff
      scan/ScanDiff.kt                            # pure diff logic
      index/IndexWorker.kt                        # WorkManager coordinator
      search/VectorBuffer.kt                      # in-memory normalized vectors
      search/SearchEngine.kt                      # query -> ranked photo ids
      ui/SearchScreen.kt                          # Compose UI
      ui/SearchViewModel.kt
      App.kt                                      # Application + DI wiring
  src/test/java/com/nlphotos/                     # JVM unit tests (Robolectric where needed)
  src/androidTest/java/com/nlphotos/              # instrumented + parity tests
tools/
  export_model.py                                 # exports fp16 ONNX from open_clip
  make_parity_fixtures.py                          # reference embeddings for parity test
docs/superpowers/...
```

---

### Task 1: Project scaffold + privacy invariant

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `app/build.gradle.kts`, `gradle.properties`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/com/nlphotos/App.kt`
- Test: `app/src/test/java/com/nlphotos/ManifestPrivacyTest.kt`

**Interfaces:**
- Produces: a buildable empty app; `App : Application`.

- [ ] **Step 1: Write the failing test** (privacy invariant — the manifest must not declare INTERNET)

```kotlin
// ManifestPrivacyTest.kt
package com.nlphotos
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class ManifestPrivacyTest {
    @Test fun manifest_does_not_request_internet() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertFalse(
            "INTERNET permission must never be declared",
            manifest.contains("android.permission.INTERNET")
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nlphotos.ManifestPrivacyTest"`
Expected: FAIL (no Gradle/manifest yet).

- [ ] **Step 3: Create the Gradle build files and manifest**

`settings.gradle.kts`:
```kotlin
pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
rootProject.name = "NLPhotos"
include(":app")
```

`app/build.gradle.kts` (key parts):
```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}
android {
    namespace = "com.nlphotos"
    compileSdk = 35
    defaultConfig { applicationId = "com.nlphotos"; minSdk = 26; targetSdk = 35 }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    // Keep .onnx assets uncompressed so ONNX Runtime can mmap them
    androidResources { noCompress += listOf("onnx") }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.18.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.12.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
}
```

`app/src/main/AndroidManifest.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <!-- NO android.permission.INTERNET. Privacy by construction. -->
    <uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />
    <application android:name=".App" android:label="NLPhotos" android:theme="@style/Theme.Material3.DayNight">
        <activity android:name=".ui.MainActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

`App.kt`:
```kotlin
package com.nlphotos
import android.app.Application
class App : Application()
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nlphotos.ManifestPrivacyTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add settings.gradle.kts build.gradle.kts gradle.properties app/
git commit -m "feat: project scaffold with no-INTERNET privacy invariant test"
```

---

### Task 2: Model export + parity fixtures (Python)

This task produces the model assets and the ground-truth fixtures the whole accuracy strategy depends on. No app code yet.

**Files:**
- Create: `tools/export_model.py`
- Create: `tools/make_parity_fixtures.py`
- Create (generated): `app/src/main/assets/models/clip-vit-b32/{image_encoder.fp16.onnx,text_encoder.fp16.onnx,vocab.json,merges.txt}`
- Create (generated): `app/src/androidTest/assets/parity/{fixtures.json, img_000.png ...}`

**Interfaces:**
- Produces: ONNX encoders with input/output names `pixel_values`/`image_embeds` (image) and `input_ids`/`text_embeds` (text); a `fixtures.json` mapping each test image and each test query to its reference 512-D L2-normalized embedding.

- [ ] **Step 1: Write `export_model.py`**

```python
# tools/export_model.py — exports CLIP ViT-B/32 (fp16) image & text encoders to ONNX
import torch, open_clip, onnx
from onnxconverter_common import float16

model, _, _ = open_clip.create_model_and_transforms("ViT-B-32", pretrained="openai")
model.eval()

class ImageEncoder(torch.nn.Module):
    def __init__(self, m): super().__init__(); self.m = m
    def forward(self, pixel_values):
        f = self.m.encode_image(pixel_values)
        return f / f.norm(dim=-1, keepdim=True)   # L2-normalize in-graph

class TextEncoder(torch.nn.Module):
    def __init__(self, m): super().__init__(); self.m = m
    def forward(self, input_ids):
        f = self.m.encode_text(input_ids)
        return f / f.norm(dim=-1, keepdim=True)

dummy_img = torch.randn(1, 3, 224, 224)
torch.onnx.export(ImageEncoder(model), dummy_img, "image_encoder.onnx",
    input_names=["pixel_values"], output_names=["image_embeds"],
    dynamic_axes={"pixel_values": {0: "b"}}, opset_version=17)

dummy_txt = torch.randint(0, 49407, (1, 77))
torch.onnx.export(TextEncoder(model), dummy_txt, "text_encoder.onnx",
    input_names=["input_ids"], output_names=["text_embeds"],
    dynamic_axes={"input_ids": {0: "b"}}, opset_version=17)

for name in ["image_encoder", "text_encoder"]:
    m = onnx.load(f"{name}.onnx")
    onnx.save(float16.convert_float_to_float16(m), f"{name}.fp16.onnx")
print("exported fp16 encoders")
```

Also copy the open_clip BPE assets (`bpe_simple_vocab_16e6.txt.gz` → expand to `vocab.json`/`merges.txt` format the Kotlin tokenizer expects; document the exact conversion in the script comments).

- [ ] **Step 2: Write `make_parity_fixtures.py`**

```python
# tools/make_parity_fixtures.py — reference embeddings for the on-device parity test
import json, torch, open_clip
from PIL import Image
model, _, preprocess = open_clip.create_model_and_transforms("ViT-B-32", pretrained="openai")
tokenizer = open_clip.get_tokenizer("ViT-B-32")
model.eval()

images = ["img_000.png", "img_001.png", "img_002.png"]   # checked-in test images
queries = ["a photo of a lake", "a car", "a bank document", "a dog on the beach"]

out = {"images": {}, "queries": {}}
with torch.no_grad():
    for p in images:
        t = preprocess(Image.open(p).convert("RGB")).unsqueeze(0)
        e = model.encode_image(t); e = e / e.norm(dim=-1, keepdim=True)
        out["images"][p] = e[0].tolist()
    for q in queries:
        e = model.encode_text(tokenizer([q])); e = e / e.norm(dim=-1, keepdim=True)
        out["queries"][q] = e[0].tolist()
json.dump(out, open("fixtures.json", "w"))
print("wrote fixtures.json")
```

- [ ] **Step 3: Run both scripts; place outputs**

Run: `python tools/export_model.py && python tools/make_parity_fixtures.py`
Then move the `.onnx`/vocab assets into `app/src/main/assets/models/clip-vit-b32/` and the test images + `fixtures.json` into `app/src/androidTest/assets/parity/`.
Expected: four model asset files and `fixtures.json` exist (verify with `ls -la`).

- [ ] **Step 4: Commit**

```bash
git add tools/ app/src/main/assets app/src/androidTest/assets
git commit -m "feat: export fp16 CLIP ONNX encoders and parity fixtures"
```

---

### Task 3: ModelDescriptor

**Files:**
- Create: `app/src/main/java/com/nlphotos/model/ModelDescriptor.kt`
- Create: `app/src/main/java/com/nlphotos/model/Models.kt`
- Test: `app/src/test/java/com/nlphotos/model/ModelsTest.kt`

**Interfaces:**
- Produces: `ModelDescriptor` and `Models.CLIP_VIT_B32`. Consumed by tokenizer, preprocessor, engine.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.nlphotos.model
import org.junit.Assert.assertEquals
import org.junit.Test
class ModelsTest {
    @Test fun clip_descriptor_has_expected_constants() {
        val d = Models.CLIP_VIT_B32
        assertEquals(224, d.inputResolution)
        assertEquals(512, d.embeddingDim)
        assertEquals(77, d.contextLength)
        assertEquals(3, d.pixelMean.size)
        assertEquals(3, d.pixelStd.size)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nlphotos.model.ModelsTest"`
Expected: FAIL ("unresolved reference Models").

- [ ] **Step 3: Implement**

```kotlin
// ModelDescriptor.kt
package com.nlphotos.model
data class ModelDescriptor(
    val name: String,
    val imageEncoderAsset: String,
    val textEncoderAsset: String,
    val vocabAsset: String,
    val mergesAsset: String,
    val inputResolution: Int,
    val pixelMean: FloatArray,
    val pixelStd: FloatArray,
    val embeddingDim: Int,
    val contextLength: Int,
)
```

```kotlin
// Models.kt
package com.nlphotos.model
object Models {
    val CLIP_VIT_B32 = ModelDescriptor(
        name = "clip-vit-b32-fp16",
        imageEncoderAsset = "models/clip-vit-b32/image_encoder.fp16.onnx",
        textEncoderAsset = "models/clip-vit-b32/text_encoder.fp16.onnx",
        vocabAsset = "models/clip-vit-b32/vocab.json",
        mergesAsset = "models/clip-vit-b32/merges.txt",
        inputResolution = 224,
        pixelMean = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f),
        pixelStd = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f),
        embeddingDim = 512,
        contextLength = 77,
    )
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nlphotos.model.ModelsTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nlphotos/model app/src/test/java/com/nlphotos/model
git commit -m "feat: ModelDescriptor and CLIP ViT-B/32 descriptor"
```

---

### Task 4: BPE tokenizer (pure JVM, TDD — accuracy-critical)

**Files:**
- Create: `app/src/main/java/com/nlphotos/ml/BpeTokenizer.kt`
- Test: `app/src/test/java/com/nlphotos/ml/BpeTokenizerTest.kt`
- Test asset: `app/src/test/resources/clip_token_fixtures.json` (a few `query -> token id list` pairs produced by `open_clip.get_tokenizer("ViT-B-32")`)

**Interfaces:**
- Consumes: vocab/merges streams.
- Produces: `class BpeTokenizer(vocab, merges, contextLength)` with `fun encode(text: String): IntArray` returning padded ids of length `contextLength`, starting with SOT (49406) and ending with EOT (49407) before padding with 0.

- [ ] **Step 1: Write the failing test** (assert exact token ids vs CLIP reference)

```kotlin
package com.nlphotos.ml
import org.junit.Assert.assertArrayEquals
import org.junit.Test
class BpeTokenizerTest {
    private fun load() = BpeTokenizer.fromResources(
        vocab = javaClass.getResourceAsStream("/clip/vocab.json")!!,
        merges = javaClass.getResourceAsStream("/clip/merges.txt")!!,
        contextLength = 77,
    )
    @Test fun encodes_known_phrase_to_reference_ids() {
        val ids = load().encode("a diagram")
        // reference from open_clip: [49406, 320, 22697, 49407, 0, 0, ...]
        assertArrayEquals(intArrayOf(49406, 320, 22697, 49407), ids.copyOfRange(0, 4))
        assert(ids.size == 77)
        assert(ids.drop(4).all { it == 0 })
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nlphotos.ml.BpeTokenizerTest"`
Expected: FAIL ("unresolved reference BpeTokenizer").

- [ ] **Step 3: Implement the CLIP BPE tokenizer**

Implement the standard CLIP byte-pair encoding: byte-to-unicode mapping, lowercase + whitespace cleanup, greedy merge using ranks from `merges.txt`, map tokens to ids via `vocab.json`, wrap with SOT/EOT, truncate to `contextLength-1` then place EOT, pad with 0. (Port the well-known `open_clip` `SimpleTokenizer` algorithm exactly — it is the canonical reference; deviations break accuracy.)

```kotlin
package com.nlphotos.ml
import java.io.InputStream
class BpeTokenizer private constructor(
    private val encoder: Map<String, Int>,
    private val bpeRanks: Map<Pair<String, String>, Int>,
    private val contextLength: Int,
) {
    private val sot = encoder["<|startoftext|>"]!!   // 49406
    private val eot = encoder["<|endoftext|>"]!!     // 49407
    fun encode(text: String): IntArray { /* byte-unicode + bpe merges + ids, then SOT/.../EOT, pad 0 */ TODO() }
    companion object {
        fun fromResources(vocab: InputStream, merges: InputStream, contextLength: Int): BpeTokenizer { /* parse */ TODO() }
        fun fromAssets(ctx: android.content.Context, d: com.nlphotos.model.ModelDescriptor): BpeTokenizer =
            fromResources(ctx.assets.open(d.vocabAsset), ctx.assets.open(d.mergesAsset), d.contextLength)
    }
}
```

(Replace the `TODO()`s with the full ported algorithm during implementation; the test pins correctness.)

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nlphotos.ml.BpeTokenizerTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nlphotos/ml/BpeTokenizer.kt app/src/test/java/com/nlphotos/ml/BpeTokenizerTest.kt app/src/test/resources/clip
git commit -m "feat: CLIP BPE tokenizer with reference-id parity test"
```

---

### Task 5: Vector math (pure JVM, TDD)

**Files:**
- Create: `app/src/main/java/com/nlphotos/search/VectorMath.kt`
- Test: `app/src/test/java/com/nlphotos/search/VectorMathTest.kt`

**Interfaces:**
- Produces: `fun l2Normalize(v: FloatArray): FloatArray`; `fun dot(a: FloatArray, b: FloatArray): Float`. Used by engine, buffer, search.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.nlphotos.search
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.sqrt
class VectorMathTest {
    @Test fun normalize_gives_unit_length() {
        val n = l2Normalize(floatArrayOf(3f, 4f))
        assertEquals(1.0f, sqrt(n[0]*n[0] + n[1]*n[1]), 1e-6f)
    }
    @Test fun dot_of_identical_unit_vectors_is_one() {
        val a = l2Normalize(floatArrayOf(1f, 2f, 2f))
        assertEquals(1.0f, dot(a, a), 1e-6f)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nlphotos.search.VectorMathTest"`
Expected: FAIL.

- [ ] **Step 3: Implement**

```kotlin
package com.nlphotos.search
import kotlin.math.sqrt
fun l2Normalize(v: FloatArray): FloatArray {
    var s = 0f; for (x in v) s += x * x
    val n = sqrt(s); if (n == 0f) return v
    return FloatArray(v.size) { v[it] / n }
}
fun dot(a: FloatArray, b: FloatArray): Float {
    var s = 0f; for (i in a.indices) s += a[i] * b[i]; return s
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nlphotos.search.VectorMathTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nlphotos/search/VectorMath.kt app/src/test/java/com/nlphotos/search/VectorMathTest.kt
git commit -m "feat: vector math (l2 normalize, dot product)"
```

---

### Task 6: Image preprocessor (Robolectric JVM, TDD)

**Files:**
- Create: `app/src/main/java/com/nlphotos/ml/ImagePreprocessor.kt`
- Test: `app/src/test/java/com/nlphotos/ml/ImagePreprocessorTest.kt`

**Interfaces:**
- Consumes: `ModelDescriptor`.
- Produces: `class ImagePreprocessor(d)` with `fun toTensor(bitmap: Bitmap): FloatArray` of length `3*res*res` in CHW order, normalized with mean/std.

- [ ] **Step 1: Write the failing test** (shape + normalization correctness)

```kotlin
@RunWith(RobolectricTestRunner::class)
class ImagePreprocessorTest {
    @Test fun output_is_chw_normalized() {
        val d = Models.CLIP_VIT_B32
        val bmp = Bitmap.createBitmap(224, 224, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
        val t = ImagePreprocessor(d).toTensor(bmp)
        assertEquals(3 * 224 * 224, t.size)
        // black pixel (0) -> (0 - mean)/std on R channel (index 0)
        assertEquals((0f - d.pixelMean[0]) / d.pixelStd[0], t[0], 1e-4f)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nlphotos.ml.ImagePreprocessorTest"`
Expected: FAIL.

- [ ] **Step 3: Implement** (bilinear resize to res², RGB, scale /255, CHW, normalize). Provide a `decodeDownsampled(context, uri, targetRes)` helper using `BitmapFactory.Options.inSampleSize` for memory safety.

- [ ] **Step 4: Run test to verify it passes** — Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git commit -am "feat: image preprocessor (CHW normalized tensor + safe decode)"
```

---

### Task 7: EmbeddingEngine over ONNX Runtime

**Files:**
- Create: `app/src/main/java/com/nlphotos/ml/EmbeddingEngine.kt`
- Create: `app/src/main/java/com/nlphotos/ml/OnnxEmbeddingEngine.kt`

**Interfaces:**
- Consumes: `ModelDescriptor`, `ImagePreprocessor`, `BpeTokenizer`, vector math.
- Produces:
```kotlin
interface EmbeddingEngine {
    fun encodeImage(bitmap: Bitmap): FloatArray   // normalized, len = embeddingDim
    fun encodeText(query: String): FloatArray      // normalized, len = embeddingDim
}
```

- [ ] **Step 1: Implement `OnnxEmbeddingEngine`** — create `OrtEnvironment` + two `OrtSession`s lazily from asset bytes (read via `AssetManager`; assets are `noCompress`). `encodeImage`: preprocess → `OnnxTensor` shape `[1,3,res,res]` → run → read `image_embeds` → `l2Normalize`. `encodeText`: tokenize → `[1,contextLength]` long tensor → run → `text_embeds` → `l2Normalize`. Reuse sessions; `Closeable`.

- [ ] **Step 2: Build to verify it compiles**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git commit -am "feat: ONNX Runtime embedding engine (image + text encoders)"
```

(Correctness of this task is verified by the parity test in Task 8.)

---

### Task 8: Parity test (instrumented — the accuracy gate)

**Files:**
- Test: `app/src/androidTest/java/com/nlphotos/EmbeddingParityTest.kt`
- Uses: `app/src/androidTest/assets/parity/{fixtures.json, img_000.png...}` from Task 2.

**Interfaces:**
- Consumes: `OnnxEmbeddingEngine`, `fixtures.json`.

- [ ] **Step 1: Write the parity test**

```kotlin
@RunWith(AndroidJUnit4::class)
class EmbeddingParityTest {
    @Test fun on_device_embeddings_match_reference() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = OnnxEmbeddingEngine(ctx, Models.CLIP_VIT_B32)
        val fx = JSONObject(ctx.assets.open("parity/fixtures.json").bufferedReader().readText())
        // each image: cosine(onDevice, reference) must exceed 0.99
        // each query: cosine(onDevice, reference) must exceed 0.99
        // (iterate fx.images / fx.queries, assert dot(...) > 0.99f)
    }
}
```

- [ ] **Step 2: Run on a connected device/emulator**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "com.nlphotos.EmbeddingParityTest"`
Expected: PASS (cosine > 0.99 for every fixture). If it fails, the bug is in preprocessing or tokenizer — fix there before proceeding. **Do not continue past this gate until it passes.**

- [ ] **Step 3: Commit**

```bash
git commit -am "test: embedding parity gate vs reference (accuracy verification)"
```

---

### Task 9: Index Store (Room, Robolectric TDD)

**Files:**
- Create: `app/src/main/java/com/nlphotos/data/{PhotoEntity.kt,PhotoDao.kt,IndexDatabase.kt,IndexStore.kt}`
- Test: `app/src/test/java/com/nlphotos/data/IndexStoreTest.kt`

**Interfaces:**
- Produces: `IndexStore` with `suspend fun upsert(p: PhotoRecord)`, `suspend fun delete(ids: List<Long>)`, `suspend fun allIds(): Map<Long, Long>` (photoId→dateModified for diffing), `suspend fun allVectors(): List<PhotoRecord>`. `PhotoRecord(photoId: Long, uri: String, dateModified: Long, vector: FloatArray)`. Vector stored as `ByteArray` blob (512 floats).

- [ ] **Step 1: Write failing test** — insert two records, `allIds()` returns both with correct dateModified; `delete` removes one; vectors round-trip exactly (float blob encode/decode).

- [ ] **Step 2: Run → FAIL.** `./gradlew :app:testDebugUnitTest --tests "com.nlphotos.data.IndexStoreTest"`

- [ ] **Step 3: Implement** Room entity (`@PrimaryKey photoId`, vector `ByteArray`), DAO, DB, and `IndexStore` with float↔ByteArray converters.

- [ ] **Step 4: Run → PASS.**

- [ ] **Step 5: Commit** `git commit -am "feat: Room-backed IndexStore with vector blob round-trip"`

---

### Task 10: Scanner diff (pure JVM, TDD)

**Files:**
- Create: `app/src/main/java/com/nlphotos/scan/ScanDiff.kt`
- Create: `app/src/main/java/com/nlphotos/scan/PhotoScanner.kt`
- Test: `app/src/test/java/com/nlphotos/scan/ScanDiffTest.kt`

**Interfaces:**
- Produces: `data class MediaItem(photoId: Long, uri: String, dateModified: Long)`; `data class Diff(toEncode: List<MediaItem>, toDelete: List<Long>)`; `fun diff(current: List<MediaItem>, indexed: Map<Long, Long>): Diff`. `PhotoScanner.scan(): List<MediaItem>` queries MediaStore (not unit-tested; thin wrapper).

- [ ] **Step 1: Write failing test** — new item → toEncode; changed dateModified → toEncode; missing-from-current but indexed → toDelete; unchanged → neither.

- [ ] **Step 2: Run → FAIL.**

- [ ] **Step 3: Implement** pure `diff` + the MediaStore query in `PhotoScanner`.

- [ ] **Step 4: Run → PASS.**

- [ ] **Step 5: Commit** `git commit -am "feat: media scan diff logic + MediaStore scanner"`

---

### Task 11: VectorBuffer + SearchEngine (pure JVM, TDD)

**Files:**
- Create: `app/src/main/java/com/nlphotos/search/{VectorBuffer.kt,SearchEngine.kt}`
- Test: `app/src/test/java/com/nlphotos/search/SearchEngineTest.kt`

**Interfaces:**
- Consumes: `PhotoRecord` list, `EmbeddingEngine`, `dot`.
- Produces: `VectorBuffer` holding a contiguous `FloatArray` of all (already-normalized) vectors + parallel id/uri arrays, loaded once via `load(records)`. `SearchEngine(engine, buffer).search(query: String, topN: Int): List<SearchHit>` where `SearchHit(photoId, uri, score)`, sorted descending.

- [ ] **Step 1: Write failing test** — load 3 known unit vectors; query equal to vector #2 ranks #2 first; results sorted by score desc; respects topN.

- [ ] **Step 2: Run → FAIL.**

- [ ] **Step 3: Implement** contiguous buffer + dot-product scan with a top-N selection.

- [ ] **Step 4: Run → PASS.**

- [ ] **Step 5: Commit** `git commit -am "feat: in-memory vector buffer + dot-product search"`

---

### Task 12: Indexer (WorkManager coordinator)

**Files:**
- Create: `app/src/main/java/com/nlphotos/index/IndexWorker.kt`
- Test: `app/src/androidTest/java/com/nlphotos/index/IndexWorkerTest.kt`

**Interfaces:**
- Consumes: `PhotoScanner`, `ScanDiff`, `EmbeddingEngine`, `IndexStore`.
- Produces: `IndexWorker : CoroutineWorker` that scans → diffs → encodes new/changed in batches → upserts → deletes removed → publishes progress via `setProgress(workDataOf("done" to x, "total" to n))`. Enqueued as unique work; resumable (re-running processes only the remaining diff because completed items are already in the store).

- [ ] **Step 1: Write instrumented test** using `TestListenableWorkerBuilder` with a fake scanner returning N items and a fake engine; assert all N land in `IndexStore` and a second run encodes 0 (idempotent/resumable).

- [ ] **Step 2: Run → FAIL.** `./gradlew :app:connectedDebugAndroidTest --tests "com.nlphotos.index.IndexWorkerTest"`

- [ ] **Step 3: Implement** the worker (batch size ~16; yield between batches for thermal headroom; `setForeground` with a progress notification — local only, no network).

- [ ] **Step 4: Run → PASS.**

- [ ] **Step 5: Commit** `git commit -am "feat: WorkManager indexer (batched, resumable, progress)"`

---

### Task 13: UI (Compose) + wiring

**Files:**
- Create: `app/src/main/java/com/nlphotos/ui/{MainActivity.kt,SearchScreen.kt,SearchViewModel.kt}`
- Modify: `app/src/main/java/com/nlphotos/App.kt` (build singletons: engine, store, buffer, search)

**Interfaces:**
- Consumes: `SearchEngine`, `IndexStore`→`VectorBuffer`, WorkManager, READ_MEDIA_IMAGES permission flow.

- [ ] **Step 1: Implement** `MainActivity` requesting `READ_MEDIA_IMAGES`; on grant, enqueue `IndexWorker` and observe progress. `SearchViewModel` loads `VectorBuffer` from `IndexStore` at startup and exposes `search(query)`. `SearchScreen`: top search field, indexing progress bar, results grid via Coil (`AsyncImage` from content URIs), tap → full-screen viewer.

- [ ] **Step 2: Build + install + manual smoke**

Run: `./gradlew :app:installDebug`
Then on device: grant permission, wait for indexing, search "a lake" → relevant photos appear ranked.
Expected: results are relevant and ordered by score.

- [ ] **Step 3: Commit** `git commit -am "feat: Compose search UI wired to indexer and search engine"`

---

### Task 14: Accuracy eval harness + privacy verification (final gates)

**Files:**
- Test: `app/src/androidTest/java/com/nlphotos/AccuracyEvalTest.kt`
- Test asset: `app/src/androidTest/assets/eval/{labeled images, queries.json with expected top result}`
- Test: extend `ManifestPrivacyTest` to also assert the **merged** manifest has no INTERNET.

**Interfaces:**
- Consumes: full pipeline (engine + buffer + search).

- [ ] **Step 1: Write accuracy eval test** — index ~20 labeled images; for each query in `queries.json`, assert the expected image is in top-3 (recall@3). Record recall@1/@3 to logcat for tracking.

- [ ] **Step 2: Write merged-manifest privacy check**

```kotlin
@Test fun merged_manifest_has_no_internet() {
    val pm = ctx.packageManager
    val info = pm.getPackageInfo(ctx.packageName, PackageManager.GET_PERMISSIONS)
    assertFalse((info.requestedPermissions ?: arrayOf()).contains("android.permission.INTERNET"))
}
```

- [ ] **Step 3: Run both**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "com.nlphotos.AccuracyEvalTest" --tests "com.nlphotos.ManifestPrivacyTest"`
Expected: recall@3 = 1.0 on the eval set (tune `topN`/preprocessing if not); no INTERNET permission in merged manifest.

- [ ] **Step 4: Commit** `git commit -am "test: accuracy eval (recall@3) and merged-manifest privacy gate"`

---

## Self-Review

**Spec coverage:** Scanner (T10), Embedding Engine + descriptor + tokenizer (T3,T4,T6,T7), Index Store (T9), Indexer/WorkManager (T12), in-memory vector buffer + search (T5,T11), UI (T13), privacy-by-construction (T1,T14), model-agnostic descriptor (T3), L2-normalize invariant (T5,T7), accuracy priority (T2 fixtures, T8 parity gate, T14 recall eval), fp16 ViT-B/32 (T2,T3). All spec sections map to tasks.

**Placeholder scan:** The `TODO()`s in Task 4/6/7 are explicitly flagged as "replace during implementation, pinned by the listed test" rather than vague instructions — each has its verifying test and algorithm reference. Acceptable as plan guidance; implementer must fill with the named algorithm.

**Type consistency:** `ModelDescriptor` fields, `EmbeddingEngine` signatures, `PhotoRecord`, `MediaItem`, `Diff`, `SearchHit`, `l2Normalize`/`dot` names are used consistently across tasks.

**Accuracy emphasis:** Verified by three gates — parity (T8, cosine>0.99), recall eval (T14), and exact-preprocessing/tokenizer TDD (T4,T6).
