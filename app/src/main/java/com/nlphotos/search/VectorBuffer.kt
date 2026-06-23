package com.nlphotos.search

import com.nlphotos.data.PhotoRecord

data class SearchHit(val photoId: Long, val uri: String, val score: Float)

class VectorBuffer {
    private var dim: Int = 0
    private var data: FloatArray = FloatArray(0)
    private var ids: LongArray = LongArray(0)
    private var uris: Array<String> = emptyArray()

    var size: Int = 0
        private set

    fun load(records: List<PhotoRecord>) {
        if (records.isEmpty()) {
            dim = 0; size = 0
            data = FloatArray(0); ids = LongArray(0); uris = emptyArray()
            return
        }
        dim = records[0].vector.size
        size = records.size
        data = FloatArray(size * dim)
        ids = LongArray(size)
        uris = Array(size) { "" }
        for (i in records.indices) {
            val r = records[i]
            ids[i] = r.photoId
            uris[i] = r.uri
            System.arraycopy(r.vector, 0, data, i * dim, dim)
        }
    }

    fun search(query: FloatArray, topN: Int): List<SearchHit> {
        if (size == 0 || dim == 0 || topN <= 0) return emptyList()
        val scores = FloatArray(size)
        for (i in 0 until size) {
            val c = i * dim
            var s = 0f
            for (j in 0 until dim) s += data[c + j] * query[j]
            scores[i] = s
        }
        val n = minOf(topN, size)
        val indices = (0 until size).sortedByDescending { scores[it] }.take(n)
        return indices.map { SearchHit(ids[it], uris[it], scores[it]) }
    }

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
}
