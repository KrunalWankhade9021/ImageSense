package com.nlphotos.ml

import android.graphics.Bitmap

interface EmbeddingEngine {
    /** Returns a normalized embedding vector of length embeddingDim. */
    fun encodeImage(bitmap: Bitmap): FloatArray

    /** Returns a normalized embedding vector of length embeddingDim. */
    fun encodeText(query: String): FloatArray

    /**
     * Eagerly builds the text encoder (model session + tokenizer) so the first
     * real search isn't a cold start. Safe to call repeatedly. Default no-op.
     */
    fun warmUpText() {}
}
