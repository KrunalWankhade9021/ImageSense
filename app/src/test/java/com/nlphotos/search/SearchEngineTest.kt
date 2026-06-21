package com.nlphotos.search

import android.graphics.Bitmap
import com.nlphotos.data.PhotoRecord
import com.nlphotos.ml.EmbeddingEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchEngineTest {

    private fun unit(dim: Int, hot: Int): FloatArray =
        FloatArray(dim) { if (it == hot) 1f else 0f }

    private fun records(): List<PhotoRecord> = listOf(
        PhotoRecord(1L, "uri1", 100L, unit(512, 0)),
        PhotoRecord(2L, "uri2", 200L, unit(512, 1)),
        PhotoRecord(3L, "uri3", 300L, unit(512, 2)),
    )

    @Test
    fun queryMatchingRecordTwoRanksItFirst() {
        val buffer = VectorBuffer()
        buffer.load(records())
        assertEquals(3, buffer.size)

        val hits = buffer.search(unit(512, 1), 3)
        assertEquals(3, hits.size)
        assertEquals(2L, hits[0].photoId)
        assertEquals("uri2", hits[0].uri)
        assertEquals(1f, hits[0].score, 1e-5f)
    }

    @Test
    fun resultsSortedByScoreDescending() {
        val buffer = VectorBuffer()
        buffer.load(records())
        val hits = buffer.search(unit(512, 0), 3)
        for (i in 1 until hits.size) {
            assertTrue(hits[i - 1].score >= hits[i].score)
        }
        assertEquals(1L, hits[0].photoId)
    }

    @Test
    fun topNRespectedAndClamped() {
        val buffer = VectorBuffer()
        buffer.load(records())
        assertEquals(1, buffer.search(unit(512, 0), 1).size)
        assertEquals(3, buffer.search(unit(512, 0), 100).size)
    }

    @Test
    fun emptyBufferReturnsEmpty() {
        val buffer = VectorBuffer()
        assertEquals(0, buffer.size)
        assertTrue(buffer.search(unit(512, 0), 5).isEmpty())
    }

    @Test
    fun searchEngineDelegatesToBuffer() {
        val buffer = VectorBuffer()
        buffer.load(records())
        val fake = object : EmbeddingEngine {
            override fun encodeImage(bitmap: Bitmap): FloatArray = unit(512, 2)
            override fun encodeText(query: String): FloatArray = unit(512, 2)
        }
        val engine = SearchEngine(fake, buffer)
        val hits = engine.search("anything", 3)
        assertEquals(3L, hits[0].photoId)
        assertEquals(1f, hits[0].score, 1e-5f)
    }
}
