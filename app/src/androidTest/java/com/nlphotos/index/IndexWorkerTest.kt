package com.nlphotos.index

import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nlphotos.data.IndexDatabase
import com.nlphotos.data.IndexStore
import com.nlphotos.ml.EmbeddingEngine
import com.nlphotos.scan.MediaItem
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IndexWorkerTest {

    private lateinit var db: IndexDatabase
    private lateinit var store: IndexStore

    /** Counts encode calls so we can assert resumability/idempotency. */
    private class CountingEngine : EmbeddingEngine {
        var encodeCount = 0
        override fun encodeImage(bitmap: Bitmap): FloatArray {
            encodeCount++
            // Deterministic vector derived from the bitmap's width (we encode the id there).
            return FloatArray(512) { bitmap.width.toFloat() }
        }
        override fun encodeText(query: String): FloatArray = FloatArray(512)
    }

    @Before
    fun setUp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(ctx, IndexDatabase::class.java).build()
        store = IndexStore(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun mediaItems(n: Int): List<MediaItem> =
        (1..n).map { MediaItem(photoId = it.toLong(), uri = "content://photo/$it", dateModified = 1000L) }

    private fun fakeBitmap(item: MediaItem): Bitmap =
        // Encode the id into the width so the engine produces a deterministic, distinct vector.
        Bitmap.createBitmap(item.photoId.toInt(), 1, Bitmap.Config.ARGB_8888)

    @Test
    fun indexes_all_then_resumes_with_zero_new_encodes() = runBlocking {
        val n = 40 // > batch size to exercise multiple batches
        val items = mediaItems(n)
        val engine = CountingEngine()
        val progress = mutableListOf<Pair<Int, Int>>()

        runIndexing(
            scan = { items },
            engine = engine,
            store = store,
            decode = { fakeBitmap(it) },
            setProgress = { done, total -> progress.add(done to total) },
        )

        assertEquals("all items encoded on first run", n, engine.encodeCount)
        assertEquals("all records persisted", n, store.count())
        assertEquals("final progress reports completion", n to n, progress.last())

        // Second run with identical scan input: diff is empty, nothing re-encoded.
        engine.encodeCount = 0
        runIndexing(
            scan = { items },
            engine = engine,
            store = store,
            decode = { fakeBitmap(it) },
            setProgress = { _, _ -> },
        )

        assertEquals("second run encodes nothing (resumable/idempotent)", 0, engine.encodeCount)
        assertEquals("record count unchanged", n, store.count())
    }
}
