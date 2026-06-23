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
