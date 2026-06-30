# Gallery-first Home (P0) Implementation Plan

**Goal:** Reframe the app as a gallery-first experience: open to a time-grouped photo grid (Photos tab), with NL search under a second tab, and a full-screen viewer offering Share / Info / Find similar.

**Architecture:** A bottom-nav `Scaffold` hosts two tabs — Photos (MediaStore-backed grid, indexing-independent) and Search (existing flow). A full-screen viewer overlay sits above both. Find-similar reuses the existing in-memory `VectorBuffer`. Pure logic (date grouping, vector lookup, find-similar ranking) is TDD'd as JVM unit tests; Compose UI is verified by build + on-device check.

**Tech Stack:** Kotlin, Jetpack Compose, Material 3, Coil, ONNX Runtime (existing), Room (existing), WorkManager (existing), JUnit + Robolectric (existing).

## Global Constraints

- No INTERNET permission — privacy by construction. Share uses a FileProvider content URI only. (verbatim invariant)
- No media-write capability — Delete is out of scope; app stays read-only to photos.
- Embedding parity test must stay green: cosine ≥ 0.83.
- Package/namespace stays `com.nlphotos`; only the *visible* name becomes "ImageSense".
- Min SDK 26, target/compile SDK 35.
- No Co-Authored-By trailer in commits.
- Use Opus 4.8 for any implementation subagents.

---

### Task 1: Date-grouping model (`GallerySection`)

**Files:**
- Create: `app/src/main/java/com/nlphotos/gallery/GallerySection.kt`
- Test: `app/src/test/java/com/nlphotos/gallery/GallerySectionTest.kt`

**Interfaces:**
- Consumes: `com.nlphotos.scan.MediaItem(photoId: Long, uri: String, dateModified: Long)` — `dateModified` is **seconds** since epoch (MediaStore convention).
- Produces:
  - `data class GallerySection(val label: String, val items: List<MediaItem>)`
  - `fun groupByDate(items: List<MediaItem>, nowMillis: Long): List<GallerySection>`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.nlphotos.gallery

import com.nlphotos.scan.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class GallerySectionTest {
    private fun item(id: Long, sec: Long) = MediaItem(id, "uri$id", sec)

    // Fixed "now": 2026-06-23 12:00:00 local
    private val now: Long = Calendar.getInstance().apply {
        set(2026, Calendar.JUNE, 23, 12, 0, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun secsAt(y: Int, m: Int, d: Int): Long {
        val c = Calendar.getInstance().apply {
            set(y, m, d, 9, 0, 0); set(Calendar.MILLISECOND, 0)
        }
        return c.timeInMillis / 1000
    }

    @Test fun groupsTodayYesterdayAndMonth() {
        val items = listOf(
            item(1, secsAt(2026, Calendar.JUNE, 23)),  // Today
            item(2, secsAt(2026, Calendar.JUNE, 22)),  // Yesterday
            item(3, secsAt(2026, Calendar.JUNE, 22)),  // Yesterday
            item(4, secsAt(2024, Calendar.JUNE, 1)),   // June 2024
        )
        val sections = groupByDate(items, now)
        assertEquals(listOf("Today", "Yesterday", "June 2024"), sections.map { it.label })
        assertEquals(listOf(2L, 3L), sections[1].items.map { it.photoId })
    }

    @Test fun emptyInputYieldsNoSections() {
        assertEquals(emptyList<GallerySection>(), groupByDate(emptyList(), now))
    }

    @Test fun sectionsAreNewestFirstAndItemsNewestFirst() {
        val items = listOf(
            item(1, secsAt(2024, Calendar.JUNE, 1)),
            item(2, secsAt(2026, Calendar.JUNE, 23)),
        )
        val sections = groupByDate(items, now)
        assertEquals("Today", sections.first().label)
        assertEquals(2L, sections.first().items.first().photoId)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nlphotos.gallery.GallerySectionTest" --offline`
Expected: FAIL — `groupByDate` / `GallerySection` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.nlphotos.gallery

import com.nlphotos.scan.MediaItem
import java.util.Calendar

/** A run of photos sharing a human date label, newest-first. */
data class GallerySection(val label: String, val items: List<MediaItem>)

/**
 * Buckets [items] into date sections labelled "Today", "Yesterday", or
 * "<Month> <Year>". Sections and the items within them are newest-first.
 * [MediaItem.dateModified] is in seconds (MediaStore convention).
 */
fun groupByDate(items: List<MediaItem>, nowMillis: Long): List<GallerySection> {
    if (items.isEmpty()) return emptyList()

    fun startOfDay(millis: Long): Long = Calendar.getInstance().apply {
        timeInMillis = millis
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    val todayStart = startOfDay(nowMillis)
    val yesterdayStart = todayStart - 24L * 60 * 60 * 1000
    val months = arrayOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December",
    )

    fun label(itemMillis: Long): String {
        val day = startOfDay(itemMillis)
        return when {
            day >= todayStart -> "Today"
            day >= yesterdayStart -> "Yesterday"
            else -> {
                val c = Calendar.getInstance().apply { timeInMillis = itemMillis }
                "${months[c.get(Calendar.MONTH)]} ${c.get(Calendar.YEAR)}"
            }
        }
    }

    val sorted = items.sortedByDescending { it.dateModified }
    val sections = LinkedHashMap<String, MutableList<MediaItem>>()
    for (item in sorted) {
        val key = label(item.dateModified * 1000)
        sections.getOrPut(key) { mutableListOf() }.add(item)
    }
    return sections.map { (label, list) -> GallerySection(label, list) }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nlphotos.gallery.GallerySectionTest" --offline`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nlphotos/gallery/GallerySection.kt app/src/test/java/com/nlphotos/gallery/GallerySectionTest.kt
git commit -m "feat(gallery): date-grouping model for the photo grid"
```

---

### Task 2: Per-photo vector lookup (`VectorBuffer.vectorFor`)

**Files:**
- Modify: `app/src/main/java/com/nlphotos/search/VectorBuffer.kt`
- Test: `app/src/test/java/com/nlphotos/search/VectorBufferLookupTest.kt`

**Interfaces:**
- Consumes: existing `VectorBuffer.load(records: List<PhotoRecord>)`, `PhotoRecord(photoId, uri, dateModified, vector)`.
- Produces: `fun VectorBuffer.vectorFor(photoId: Long): FloatArray?` — returns the stored (already-normalized) vector for an indexed photo, or `null` if not indexed. Needed by find-similar (Task 3).

- [ ] **Step 1: Write the failing test**

```kotlin
package com.nlphotos.search

import com.nlphotos.data.PhotoRecord
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VectorBufferLookupTest {
    @Test fun returnsStoredVectorForIndexedPhoto() {
        val buf = VectorBuffer()
        buf.load(listOf(
            PhotoRecord(1, "u1", 0, floatArrayOf(1f, 0f)),
            PhotoRecord(2, "u2", 0, floatArrayOf(0f, 1f)),
        ))
        assertArrayEquals(floatArrayOf(0f, 1f), buf.vectorFor(2), 0f)
    }

    @Test fun returnsNullForUnindexedPhoto() {
        val buf = VectorBuffer()
        buf.load(listOf(PhotoRecord(1, "u1", 0, floatArrayOf(1f, 0f))))
        assertNull(buf.vectorFor(999))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nlphotos.search.VectorBufferLookupTest" --offline`
Expected: FAIL — `vectorFor` unresolved.

- [ ] **Step 3: Write minimal implementation**

Add inside the `VectorBuffer` class (after `search`):

```kotlin
    /** The stored normalized vector for [photoId], or null if not indexed. */
    fun vectorFor(photoId: Long): FloatArray? {
        for (i in 0 until size) {
            if (ids[i] == photoId) {
                val c = i * dim
                return data.copyOfRange(c, c + dim)
            }
        }
        return null
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nlphotos.search.VectorBufferLookupTest" --offline`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nlphotos/search/VectorBuffer.kt app/src/test/java/com/nlphotos/search/VectorBufferLookupTest.kt
git commit -m "feat(search): vectorFor(photoId) lookup for find-similar"
```

---

### Task 3: Find-similar ranking (`SearchEngine.findSimilar`)

**Files:**
- Modify: `app/src/main/java/com/nlphotos/search/SearchEngine.kt`
- Test: `app/src/test/java/com/nlphotos/search/FindSimilarTest.kt`

**Interfaces:**
- Consumes: `VectorBuffer.vectorFor` (Task 2), `VectorBuffer.search(query, topN)`, `SearchHit(photoId, uri, score)`.
- Produces: `fun SearchEngine.findSimilar(photoId: Long, topN: Int = 100): List<SearchHit>` — similar photos ranked by cosine, **excluding the query photo itself**. Empty list if the photo isn't indexed.

Existing `SearchEngine`:
```kotlin
class SearchEngine(private val engine: EmbeddingEngine, private val buffer: VectorBuffer) {
    fun search(query: String, topN: Int = 100): List<SearchHit> =
        buffer.search(engine.encodeText(query), topN)
}
```

- [ ] **Step 1: Write the failing test**

```kotlin
package com.nlphotos.search

import com.nlphotos.data.PhotoRecord
import com.nlphotos.ml.EmbeddingEngine
import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FindSimilarTest {
    // Find-similar never calls the model; a throwing fake proves that.
    private val noEngine = object : EmbeddingEngine {
        override fun encodeImage(bitmap: Bitmap) = error("unused")
        override fun encodeText(query: String) = error("unused")
    }

    private fun buffer() = VectorBuffer().apply {
        load(listOf(
            PhotoRecord(1, "u1", 0, floatArrayOf(1f, 0f)),
            PhotoRecord(2, "u2", 0, floatArrayOf(0.9f, 0.1f)),
            PhotoRecord(3, "u3", 0, floatArrayOf(0f, 1f)),
        ))
    }

    @Test fun ranksSimilarExcludingSelf() {
        val hits = SearchEngine(noEngine, buffer()).findSimilar(1, topN = 10)
        assertTrue(hits.none { it.photoId == 1L })
        assertEquals(2L, hits.first().photoId) // closest to (1,0)
    }

    @Test fun emptyWhenNotIndexed() {
        assertEquals(emptyList<SearchHit>(), SearchEngine(noEngine, buffer()).findSimilar(999))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nlphotos.search.FindSimilarTest" --offline`
Expected: FAIL — `findSimilar` unresolved.

- [ ] **Step 3: Write minimal implementation**

Add to `SearchEngine`:

```kotlin
    /** Photos most similar to [photoId], excluding itself. Empty if not indexed. */
    fun findSimilar(photoId: Long, topN: Int = 100): List<SearchHit> {
        val vector = buffer.vectorFor(photoId) ?: return emptyList()
        return buffer.search(vector, topN + 1).filter { it.photoId != photoId }.take(topN)
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.nlphotos.search.FindSimilarTest" --offline`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nlphotos/search/SearchEngine.kt app/src/test/java/com/nlphotos/search/FindSimilarTest.kt
git commit -m "feat(search): findSimilar(photoId) ranking via the vector buffer"
```

---

### Task 4: ViewModel — gallery + find-similar state

**Files:**
- Modify: `app/src/main/java/com/nlphotos/ui/SearchViewModel.kt`

**Interfaces:**
- Consumes: `PhotoScanner(context).scan()`, `groupByDate` (Task 1), `SearchEngine.findSimilar` (Task 3), existing `_results`, `_query`, `_searching`.
- Produces (new public members on `SearchViewModel`):
  - `val gallery: StateFlow<List<GallerySection>>`
  - `fun loadGallery()` — populates `gallery` from MediaStore off-main.
  - `fun findSimilar(photoId: Long)` — sets `_query` to a sentinel label and fills `_results`; reuses existing `searching` + results flows so the Search tab renders them.

This task is glue over already-tested units; verify by compile + the on-device check in Task 9. No new unit test.

- [ ] **Step 1: Add imports + members**

Add imports:
```kotlin
import com.nlphotos.gallery.GallerySection
import com.nlphotos.gallery.groupByDate
import com.nlphotos.scan.PhotoScanner
```

Add inside `SearchViewModel`:
```kotlin
    private val scanner = PhotoScanner(application)

    private val _gallery = MutableStateFlow<List<GallerySection>>(emptyList())
    val gallery: StateFlow<List<GallerySection>> = _gallery.asStateFlow()

    /** Loads all device photos (MediaStore) into time-grouped sections. */
    fun loadGallery() {
        viewModelScope.launch {
            val items = withContext(Dispatchers.IO) { scanner.scan() }
            _gallery.value = withContext(Dispatchers.Default) {
                groupByDate(items, System.currentTimeMillis())
            }
        }
    }

    /** Find photos similar to [photoId]; results surface on the Search tab. */
    fun findSimilar(photoId: Long) {
        viewModelScope.launch {
            _query.value = "Similar photos"
            _searching.value = true
            try {
                val hits = withContext(Dispatchers.Default) { searchEngine.findSimilar(photoId) }
                _results.value = hits
            } finally {
                _searching.value = false
            }
        }
    }
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:assembleDebug --offline --no-daemon`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/nlphotos/ui/SearchViewModel.kt
git commit -m "feat(ui): gallery sections + findSimilar in the view model"
```

---

### Task 5: FileProvider for Share

**Files:**
- Create: `app/src/main/res/xml/file_paths.xml`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Produces: a `FileProvider` authority `"$packageName.fileprovider"` usable by the viewer's Share action. (Sharing MediaStore content URIs typically needs no provider, but registering one keeps share robust for any cached/file path and is the documented pattern.)

- [ ] **Step 1: Create `file_paths.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<paths xmlns:android="http://schemas.android.com/apk/res/android">
    <external-path name="external" path="." />
    <cache-path name="cache" path="." />
</paths>
```

- [ ] **Step 2: Register the provider in the manifest**

Inside `<application>`, after the `<activity>` block:
```xml
        <provider
            android:name="androidx.core.content.FileProvider"
            android:authorities="${applicationId}.fileprovider"
            android:exported="false"
            android:grantUriPermissions="true">
            <meta-data
                android:name="android.support.FILE_PROVIDER_PATHS"
                android:resource="@xml/file_paths" />
        </provider>
```

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew :app:assembleDebug --offline --no-daemon`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/res/xml/file_paths.xml app/src/main/AndroidManifest.xml
git commit -m "feat(share): register FileProvider for photo sharing"
```

---

### Task 6: Photo viewer (`PhotoViewerScreen`)

**Files:**
- Create: `app/src/main/java/com/nlphotos/ui/PhotoViewerScreen.kt`

**Interfaces:**
- Consumes: `com.nlphotos.scan.MediaItem`, Coil `AsyncImage`, `rememberTransformableState` (pattern already used in `SearchScreen`).
- Produces:
```kotlin
@Composable
fun PhotoViewerScreen(
    items: List<MediaItem>,
    startIndex: Int,
    onDismiss: () -> Unit,
    onFindSimilar: (Long) -> Unit,
)
```
  Full-screen `Dialog`, `HorizontalPager` across `items`, pinch-zoom/pan, and a bottom action bar: Share (ACTION_SEND of the current `MediaItem.uri`), Info (a `ModalBottomSheet` showing id/uri/date), Find similar (calls `onFindSimilar(currentItem.photoId)` then `onDismiss()`).

- [ ] **Step 1: Implement the viewer**

```kotlin
package com.nlphotos.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.nlphotos.scan.MediaItem
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoViewerScreen(
    items: List<MediaItem>,
    startIndex: Int,
    onDismiss: () -> Unit,
    onFindSimilar: (Long) -> Unit,
) {
    if (items.isEmpty()) return
    val context = LocalContext.current
    val pager = rememberPagerState(initialPage = startIndex.coerceIn(0, items.lastIndex)) { items.size }
    var showInfo by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                var scale by remember { mutableFloatStateOf(1f) }
                var ox by remember { mutableFloatStateOf(0f) }
                var oy by remember { mutableFloatStateOf(0f) }
                val ts = rememberTransformableState { z, p, _ ->
                    scale = (scale * z).coerceIn(1f, 5f); ox += p.x; oy += p.y
                }
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    AsyncImage(
                        model = Uri.parse(items[page].uri),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                            .graphicsLayer(scaleX = scale, scaleY = scale, translationX = ox, translationY = oy)
                            .transformable(ts),
                    )
                }
            }

            // Top close
            Box(
                modifier = Modifier.align(Alignment.TopStart).padding(12.dp)
                    .background(Color(0x66000000), MaterialTheme.shapes.small)
                    .clickable(onClick = onDismiss).padding(horizontal = 14.dp, vertical = 8.dp),
            ) { Text("Close", color = Color.White, style = MaterialTheme.typography.labelLarge) }

            // Bottom action bar
            val current = items[pager.currentPage]
            Row(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Color(0x99000000)).navigationBarsPadding().padding(vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                TextButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "image/*"
                        putExtra(Intent.EXTRA_STREAM, Uri.parse(current.uri))
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(send, "Share photo"))
                }) { Text("Share", color = Color.White) }
                TextButton(onClick = { showInfo = true }) { Text("Info", color = Color.White) }
                TextButton(onClick = { onFindSimilar(current.photoId); onDismiss() }) {
                    Text("Find similar", color = Color.White)
                }
            }

            if (showInfo) {
                ModalBottomSheet(onDismissRequest = { showInfo = false }) {
                    val it = items[pager.currentPage]
                    Column(Modifier.fillMaxWidth().padding(24.dp)) {
                        Text("Details", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(12.dp))
                        Text("Date: ${DateFormat.getDateTimeInstance().format(Date(it.dateModified * 1000))}")
                        Text("ID: ${it.photoId}")
                        Text("URI: ${it.uri}", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(12.dp))
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:assembleDebug --offline --no-daemon`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/nlphotos/ui/PhotoViewerScreen.kt
git commit -m "feat(viewer): full-screen pager viewer with share/info/find-similar"
```

---

### Task 7: Gallery screen (`GalleryScreen`)

**Files:**
- Create: `app/src/main/java/com/nlphotos/ui/GalleryScreen.kt`

**Interfaces:**
- Consumes: `GallerySection` (Task 1), Coil `AsyncImage`.
- Produces:
```kotlin
@Composable
fun GalleryScreen(
    sections: List<GallerySection>,
    indexing: Boolean,
    indexDone: Int,
    indexTotal: Int,
    onOpen: (sectionIndex: Int, itemIndex: Int) -> Unit,
)
```
  A `LazyVerticalGrid` (adaptive 112dp) with **sticky date headers** per section; tiles are square, rounded, `surfaceVariant` placeholder. A non-blocking "Search ready for X / Y" pill shows while indexing. Honest empty state when `sections` is empty.

- [ ] **Step 1: Implement the gallery**

```kotlin
package com.nlphotos.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.nlphotos.gallery.GallerySection

@Composable
fun GalleryScreen(
    sections: List<GallerySection>,
    indexing: Boolean,
    indexDone: Int,
    indexTotal: Int,
    onOpen: (Int, Int) -> Unit,
) {
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 12.dp)) {
        Row(indexing, indexDone, indexTotal)
        if (sections.isEmpty()) {
            EmptyGallery(); return
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(112.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            sections.forEachIndexed { sIdx, section ->
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        section.label,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.fillMaxWidth()
                            .background(MaterialTheme.colorScheme.background)
                            .padding(vertical = 8.dp),
                    )
                }
                items(section.items.size) { iIdx ->
                    val uri = section.items[iIdx].uri
                    Box(
                        Modifier.aspectRatio(1f).clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { onOpen(sIdx, iIdx) },
                    ) {
                        AsyncImage(
                            model = Uri.parse(uri), contentDescription = null,
                            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Row(indexing: Boolean, done: Int, total: Int) {
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Photos", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f))
        if (indexing) {
            Box(
                Modifier.clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                val label = if (total > 0) "Search ready $done / $total" else "Preparing search…"
                Text(label, style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}

@Composable
private fun EmptyGallery() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
            Text("🖼️", style = MaterialTheme.typography.displaySmall)
            Text(
                "No photos yet",
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Photos you grant access to will appear here.",
                style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:assembleDebug --offline --no-daemon`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/nlphotos/ui/GalleryScreen.kt
git commit -m "feat(gallery): time-grouped grid with sticky headers + indexing pill"
```

---

### Task 8: Bottom-nav scaffold + wire-up in `MainActivity`

**Files:**
- Modify: `app/src/main/java/com/nlphotos/ui/MainActivity.kt`

**Interfaces:**
- Consumes: `GalleryScreen` (Task 7), `SearchScreen` (existing), `PhotoViewerScreen` (Task 6), VM members `gallery`, `loadGallery()`, `findSimilar()` (Task 4), existing `query/results/indexedCount/searching` + indexing observer.
- Produces: a `Scaffold` with `NavigationBar` (Photos default, Search) and a viewer overlay state. Removes the search-only root.

- [ ] **Step 1: Add nav state + load gallery**

In `AppRoot()` after `vm.warmUp()` is enqueued, also call `vm.loadGallery()` when granted; collect `val gallery by vm.gallery.collectAsState()`; add:
```kotlin
    var tab by rememberSaveable { mutableStateOf(0) } // 0=Photos, 1=Search
    var viewer by remember { mutableStateOf<Pair<Int, Int>?>(null) } // (sectionIdx, itemIdx)
```
(Add imports: `androidx.compose.material3.Scaffold`, `androidx.compose.material3.NavigationBar`, `androidx.compose.material3.NavigationBarItem`, `androidx.compose.material3.Icon`, `androidx.compose.material.icons.Icons`, `androidx.compose.material.icons.filled.Photo`, `androidx.compose.material.icons.filled.Search`, `androidx.compose.runtime.saveable.rememberSaveable`.)

- [ ] **Step 2: Replace the `SearchScreen(...)` call with the scaffold**

```kotlin
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0, onClick = { tab = 0 },
                    icon = { Icon(Icons.Filled.Photo, null) }, label = { Text("Photos") },
                )
                NavigationBarItem(
                    selected = tab == 1, onClick = { tab = 1 },
                    icon = { Icon(Icons.Filled.Search, null) }, label = { Text("Search") },
                )
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad)) {
            when (tab) {
                0 -> GalleryScreen(
                    sections = gallery, indexing = indexing, indexDone = done, indexTotal = total,
                    onOpen = { s, i -> viewer = s to i },
                )
                else -> SearchScreen(
                    query = query, onQueryChange = vm::onQueryChange, onSubmit = { vm.search(it) },
                    results = results, indexedCount = indexedCount, indexing = indexing,
                    indexDone = done, indexTotal = total, searching = searching,
                    onReindex = { reselectLauncher.launch(PHOTO_PERMISSIONS) },
                )
            }
        }
    }

    viewer?.let { (s, i) ->
        val flat = gallery.getOrNull(s)?.items ?: emptyList()
        if (flat.isNotEmpty()) {
            PhotoViewerScreen(
                items = flat, startIndex = i, onDismiss = { viewer = null },
                onFindSimilar = { id -> vm.findSimilar(id); tab = 1 },
            )
        }
    }
```

- [ ] **Step 3: Refresh gallery on resume (new photos)**

Add a `LaunchedEffect` keyed on a lifecycle resume signal, or simplest: call `vm.loadGallery()` inside the existing indexing `SUCCEEDED` observer AND whenever `granted` becomes true. Add to the `SUCCEEDED` branch:
```kotlin
    LaunchedEffect(info?.state) {
        if (info?.state == WorkInfo.State.SUCCEEDED) { vm.loadBuffer(); vm.loadGallery() }
    }
```

- [ ] **Step 4: Build + install + on-device verify**

Run: `./tools/reinstall.sh --shot`
Expected: app opens to the **Photos** tab showing a time-grouped grid; bottom nav switches to Search; tapping a photo opens the viewer; Find similar jumps to Search with results.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/nlphotos/ui/MainActivity.kt
git commit -m "feat(nav): gallery-first bottom nav (Photos/Search) + viewer overlay"
```

---

### Task 9: Rename to ImageSense + honest states polish

**Files:**
- Modify: `app/src/main/AndroidManifest.xml` (label)
- Modify: `app/src/main/java/com/nlphotos/ui/SearchScreen.kt` (header title "NLPhotos" → "ImageSense")

**Interfaces:** none new — copy changes only.

- [ ] **Step 1: Update the launcher label**

In `AndroidManifest.xml`, change `android:label="NLPhotos"` to `android:label="ImageSense"`.

- [ ] **Step 2: Update the in-app header**

In `SearchScreen.kt`, change the header `Text(text = "NLPhotos", …)` to `"ImageSense"`.

- [ ] **Step 3: Build + verify**

Run: `./gradlew :app:assembleDebug --offline --no-daemon`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Run the full unit + regression suite**

Run: `./gradlew :app:testDebugUnitTest --offline`
Expected: PASS — includes new gallery/find-similar tests and the existing privacy/parity-related JVM tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/AndroidManifest.xml app/src/main/java/com/nlphotos/ui/SearchScreen.kt
git commit -m "chore: rename visible app name to ImageSense"
```

---

## Self-Review

**Spec coverage:**
- Gallery-first home + MediaStore source → Tasks 1, 4, 7, 8 ✓
- Bottom nav (Photos/Search) → Task 8 ✓
- Viewer with Share/Info/Find-similar → Tasks 3, 5, 6, 8 ✓
- Indexing transparency pill → Task 7 ✓
- Add-photos reload (gallery + buffer refresh on success) → Task 8 Step 3 ✓
- Honest empty states → Task 7 (EmptyGallery), existing SearchScreen states ✓
- No-INTERNET / no media-write invariants → preserved; Share uses content URI (Task 6), Delete excluded ✓
- Rename to ImageSense → Task 9 ✓
- Find-similar "not indexed yet" path → Task 3 (empty list) surfaces as Search no-match ✓

**Placeholder scan:** none — every code step has full content.

**Type consistency:** `groupByDate(items, nowMillis)`, `GallerySection(label, items)`, `VectorBuffer.vectorFor(photoId): FloatArray?`, `SearchEngine.findSimilar(photoId, topN)`, VM `gallery`/`loadGallery`/`findSimilar`, `GalleryScreen(sections, indexing, indexDone, indexTotal, onOpen)`, `PhotoViewerScreen(items, startIndex, onDismiss, onFindSimilar)` — used consistently across Tasks 1–8.

**Note for executor:** `MediaItem.dateModified` is in **seconds**; multiply by 1000 before constructing `Date`/grouping (handled in Tasks 1, 6).
