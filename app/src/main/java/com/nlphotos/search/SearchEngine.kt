package com.nlphotos.search

import com.nlphotos.ml.EmbeddingEngine

class SearchEngine(
    private val engine: EmbeddingEngine,
    private val buffer: VectorBuffer,
) {
    fun search(query: String, topN: Int = 100): List<SearchHit> =
        buffer.search(engine.encodeText(query), topN)

    /** Photos most similar to [photoId], excluding itself. Empty if not indexed. */
    fun findSimilar(photoId: Long, topN: Int = 100): List<SearchHit> {
        val vector = buffer.vectorFor(photoId) ?: return emptyList()
        return buffer.search(vector, topN + 1).filter { it.photoId != photoId }.take(topN)
    }
}
