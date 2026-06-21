package com.nlphotos

import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nlphotos.data.PhotoRecord
import com.nlphotos.ml.OnnxEmbeddingEngine
import com.nlphotos.model.Models
import com.nlphotos.search.SearchEngine
import com.nlphotos.search.VectorBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

private const val TAG = "SearchPipeline"

/**
 * End-to-end smoke test of the full search pipeline on-device:
 * text query -> tokenize/encode -> rank against image embeddings.
 * Proves the wiring (engine + buffer + search) runs and produces
 * well-formed, correctly-ordered results.
 */
@RunWith(AndroidJUnit4::class)
class SearchPipelineTest {

    @Test
    fun text_to_encode_to_rank_pipeline_runs() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ctx = instrumentation.targetContext   // app context — has model assets
        val testCtx = instrumentation.context     // test APK context — has parity fixtures

        val engine = OnnxEmbeddingEngine(ctx, Models.CLIP_VIT_B32)
        engine.use {
            val records = (0..2).map { i ->
                val name = "img_00$i.png"
                val bitmap = testCtx.assets.open("parity/$name").use { stream ->
                    BitmapFactory.decodeStream(stream) ?: error("Failed to decode bitmap: $name")
                }
                val vector = engine.encodeImage(bitmap)
                PhotoRecord(photoId = i.toLong(), uri = name, dateModified = i.toLong(), vector = vector)
            }

            val buffer = VectorBuffer()
            buffer.load(records)
            assertEquals("Buffer should hold all 3 records", 3, buffer.size)

            val search = SearchEngine(engine, buffer)
            val hits = search.search("a photo", topN = 3)

            for (h in hits) Log.i(TAG, "hit ${h.uri} score=${h.score}")

            assertEquals("Expected exactly 3 hits", 3, hits.size)
            assertTrue(
                "Scores must be sorted descending: ${hits.map { it.score }}",
                hits[0].score >= hits[1].score && hits[1].score >= hits[2].score
            )
            for (h in hits) {
                assertTrue("Score must be finite, got ${h.score}", h.score.isFinite())
            }
        }
    }
}
