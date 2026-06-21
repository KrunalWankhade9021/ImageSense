package com.nlphotos.ml

import android.graphics.Bitmap

interface EmbeddingEngine {
    /** Returns a normalized embedding vector of length embeddingDim. */
    fun encodeImage(bitmap: Bitmap): FloatArray

    /** Returns a normalized embedding vector of length embeddingDim. */
    fun encodeText(query: String): FloatArray
}
