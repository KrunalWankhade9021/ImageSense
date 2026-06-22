package com.nlphotos.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import com.nlphotos.model.ModelDescriptor
import com.nlphotos.search.l2Normalize
import java.io.Closeable
import java.io.File
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

    private val imageSessionDelegate = lazy { buildSession(descriptor.imageEncoderAsset) }
    private val imageSession: OrtSession by imageSessionDelegate

    private val textSessionDelegate = lazy { buildSession(descriptor.textEncoderAsset) }
    private val textSession: OrtSession by textSessionDelegate

    /**
     * Builds a session with an on-disk optimized-model cache.
     *
     * An ORT inference session lives in RAM and is freed when the process dies,
     * so every cold start must rebuild it — that's the first-search delay.
     *
     * To shrink later cold starts we cache the *optimized* graph on disk:
     *  - First launch (no cache): create the session from the bundled asset bytes
     *    with [setOptimizedModelFilePath]; ORT writes the fully-optimized model to
     *    the cache file as a side effect.
     *  - Later launches: create the session straight from that cache file (ORT
     *    mmaps it — no heap copy) with optimizations disabled (already applied),
     *    which initializes noticeably faster.
     *
     * The cache dir is keyed by app version so a model/app update invalidates it.
     */
    private fun buildSession(assetName: String): OrtSession {
        val cacheDir = optimizedCacheDir()
        val cacheFile = File(cacheDir, assetName.substringAfterLast('/') + ".opt.onnx")

        if (cacheFile.exists()) {
            val opts = OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT)
            }
            return env.createSession(cacheFile.absolutePath, opts)
        }

        val opts = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setOptimizedModelFilePath(cacheFile.absolutePath)
        }
        val bytes = context.assets.open(assetName).readBytes()
        return env.createSession(bytes, opts)
    }

    /** filesDir/ort-cache-v<versionCode>, created on demand; stale versions pruned. */
    private fun optimizedCacheDir(): File {
        val version = try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            @Suppress("DEPRECATION")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                info.versionCode.toLong()
            }
        } catch (e: Exception) {
            0L
        }
        val dir = File(context.filesDir, "ort-cache-v$version")
        if (!dir.exists()) {
            // Drop optimized caches from previous app versions before creating ours.
            context.filesDir.listFiles { f -> f.isDirectory && f.name.startsWith("ort-cache-v") }
                ?.forEach { it.deleteRecursively() }
            dir.mkdirs()
        }
        return dir
    }

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
