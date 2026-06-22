package com.nlphotos.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import com.nlphotos.model.ModelDescriptor
import com.nlphotos.search.l2Normalize
import java.io.Closeable
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * ONNX Runtime backed [EmbeddingEngine].
 *
 * Two sessions are created lazily on first use and reused across calls.
 * Close this object when done to release native resources.
 */
class OnnxEmbeddingEngine(
    private val context: Context,
    private val descriptor: ModelDescriptor,
) : EmbeddingEngine, Closeable {

    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }

    private val imageSessionDelegate = lazy {
        val bytes = context.assets.open(descriptor.imageEncoderAsset).readBytes()
        env.createSession(bytes)
    }
    private val imageSession: OrtSession by imageSessionDelegate

    private val textSessionDelegate = lazy {
        val bytes = context.assets.open(descriptor.textEncoderAsset).readBytes()
        env.createSession(bytes)
    }
    private val textSession: OrtSession by textSessionDelegate

    private val preprocessor: ImagePreprocessor by lazy { ImagePreprocessor(descriptor) }

    private val tokenizer: BpeTokenizer by lazy {
        BpeTokenizer.fromAssets(context, descriptor)
    }

    override fun encodeImage(bitmap: Bitmap): FloatArray {
        val res = descriptor.inputResolution
        val floatData = preprocessor.toTensor(bitmap)          // CHW, length 3*res*res

        val shape = longArrayOf(1L, 3L, res.toLong(), res.toLong())
        val buffer = FloatBuffer.wrap(floatData)
        val tensor = OnnxTensor.createTensor(env, buffer, shape)
        tensor.use {
            val inputs = mapOf("pixel_values" to tensor)
            imageSession.run(inputs).use { result ->
                @Suppress("UNCHECKED_CAST")
                val raw = (result[0].value as Array<FloatArray>)[0]
                return l2Normalize(raw)
            }
        }
    }

    override fun encodeText(query: String): FloatArray {
        val tokens = tokenizer.encode(query)                   // IntArray, length contextLength
        val contextLength = descriptor.contextLength

        val longTokens = LongArray(contextLength) { tokens[it].toLong() }
        val shape = longArrayOf(1L, contextLength.toLong())
        val buffer = LongBuffer.wrap(longTokens)
        val tensor = OnnxTensor.createTensor(env, buffer, shape)
        tensor.use {
            val inputs = mapOf("input_ids" to tensor)
            textSession.run(inputs).use { result ->
                @Suppress("UNCHECKED_CAST")
                val raw = (result[0].value as Array<FloatArray>)[0]
                return l2Normalize(raw)
            }
        }
    }

    override fun warmUpText() {
        // Touch the lazy session + tokenizer so they're built ahead of the first
        // user query. A tiny dummy encode forces full initialization.
        encodeText("warm up")
    }

    override fun close() {
        if (imageSessionDelegate.isInitialized()) imageSession.close()
        if (textSessionDelegate.isInitialized()) textSession.close()
        // OrtEnvironment is a global singleton; do not close it.
    }
}
