package com.nlphotos

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nlphotos.ml.OnnxEmbeddingEngine
import com.nlphotos.model.Models
import com.nlphotos.search.dot
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EmbeddingParityTest {

    @Test
    fun on_device_embeddings_match_reference() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ctx = instrumentation.targetContext      // app context — has model assets
        val testCtx = instrumentation.context       // test APK context — has parity fixtures
        val engine = OnnxEmbeddingEngine(ctx, Models.CLIP_VIT_B32)
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
                val onDevice = engine.encodeImage(bitmap)
                val cosine = dot(onDevice, ref)
                assertTrue(
                    "Image '$name': cosine=$cosine (expected > 0.99)",
                    cosine > 0.99f
                )
            }

            // Text queries
            val queries = fx.getJSONObject("queries")
            for (query in queries.keys()) {
                val refArr = queries.getJSONArray(query)
                val ref = FloatArray(refArr.length()) { refArr.getDouble(it).toFloat() }

                val onDevice = engine.encodeText(query)
                val cosine = dot(onDevice, ref)
                assertTrue(
                    "Query '$query': cosine=$cosine (expected > 0.99)",
                    cosine > 0.99f
                )
            }
        }
    }
}
