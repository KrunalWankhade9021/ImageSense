package com.nlphotos.search

import com.nlphotos.ml.EmbeddingEngine

class SearchEngine(
    private val engine: EmbeddingEngine,
    private val buffer: VectorBuffer,
) {
    fun search(query: String, topN: Int = 100): List<SearchHit> =
        buffer.search(engine.encodeText(query), topN)
}
