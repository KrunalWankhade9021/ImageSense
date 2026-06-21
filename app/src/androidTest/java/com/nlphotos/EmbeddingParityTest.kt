package com.nlphotos

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nlphotos.ml.ImagePreprocessor
import com.nlphotos.ml.OnnxEmbeddingEngine
import com.nlphotos.model.Models
import com.nlphotos.search.dot
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

private const val TAG = "ParityDiag"

// Accuracy bar: int8-quantized model vs reference CLIP. The user accepts >0.83
// (small/stable on-device int8 over reference-exact fp32). See accuracy memo.
private const val MIN_COSINE = 0.83f

@RunWith(AndroidJUnit4::class)
class EmbeddingParityTest {

    @Test
    fun on_device_embeddings_match_reference() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ctx = instrumentation.targetContext      // app context — has model assets
        val testCtx = instrumentation.context       // test APK context — has parity fixtures
        val engine = OnnxEmbeddingEngine(ctx, Models.CLIP_VIT_B32)
        val failures = mutableListOf<String>()
        engine.use {
            val fx = JSONObject(testCtx.assets.open("parity/fixtures.json").bufferedReader().readText())

            // Images
            val images = fx.getJSONObject("images")
            for (name in images.keys()) {
                val refArr = images.getJSONArray(name)
                val ref = FloatArray(refArr.length()) { refArr.getDouble(it).toFloat() }

                val bitmap = testCtx.assets.open("parity/$name").use { stream ->
                    BitmapFactory.decodeStream(stream)
                        ?: error("Failed to decode bitmap: $name")
                }
                if (name == "img_001.png") diagnose(bitmap)
                val onDevice = engine.encodeImage(bitmap)
                val cosine = dot(onDevice, ref)
                Log.i(TAG, "IMAGE $name cosine=$cosine")
                if (cosine < MIN_COSINE) failures += "Image '$name': cosine=$cosine"
            }

            // Text queries
            val queries = fx.getJSONObject("queries")
            for (query in queries.keys()) {
                val refArr = queries.getJSONArray(query)
                val ref = FloatArray(refArr.length()) { refArr.getDouble(it).toFloat() }

                val onDevice = engine.encodeText(query)
                val cosine = dot(onDevice, ref)
                Log.i(TAG, "TEXT '$query' cosine=$cosine")
                if (cosine < MIN_COSINE) failures += "Query '$query': cosine=$cosine"
            }
        }
        assertTrue("Parity failures: $failures", failures.isEmpty())
    }

    /** Logs on-device tensor stats for comparison against the Python reference. */
    private fun diagnose(bitmap: Bitmap) {
        Log.i(TAG, "bitmap ${bitmap.width}x${bitmap.height} config=${bitmap.config}")
        val p0 = bitmap.getPixel(0, 0)
        Log.i(TAG, "pixel(0,0) R=${Color.red(p0)} G=${Color.green(p0)} B=${Color.blue(p0)}")
        val t = ImagePreprocessor(Models.CLIP_VIT_B32).toTensor(bitmap)
        val res = Models.CLIP_VIT_B32.inputResolution
        val n = res * res
        for (c in 0 until 3) {
            var s = 0f
            for (i in 0 until n) s += t[c * n + i]
            Log.i(TAG, "channel $c mean=${s / n}  firstPx=${t[c * n]}")
        }
    }
}
