package com.nlphotos.ml

import android.graphics.Bitmap
import android.graphics.Color
import com.nlphotos.model.Models
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImagePreprocessorTest {

    @Test
    fun output_is_chw_normalized() {
        val d = Models.CLIP_VIT_B32
        val bmp = Bitmap.createBitmap(224, 224, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLACK)
        }
        val t = ImagePreprocessor(d).toTensor(bmp)

        // Shape: 3 channels * 224 * 224
        assertEquals(3 * 224 * 224, t.size)

        // Black pixel: R=0, G=0, B=0 → (0/255 - mean[c]) / std[c]
        val expectedR = (0f - d.pixelMean[0]) / d.pixelStd[0]
        val expectedG = (0f - d.pixelMean[1]) / d.pixelStd[1]
        val expectedB = (0f - d.pixelMean[2]) / d.pixelStd[2]

        val res = d.inputResolution
        // CHW: index = c*res*res + y*res + x; first pixel of each channel
        assertEquals(expectedR, t[0], 1e-4f)
        assertEquals(expectedG, t[res * res], 1e-4f)
        assertEquals(expectedB, t[2 * res * res], 1e-4f)
    }

    @Test
    fun output_is_chw_normalized_white_pixel() {
        val d = Models.CLIP_VIT_B32
        val bmp = Bitmap.createBitmap(224, 224, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }
        val t = ImagePreprocessor(d).toTensor(bmp)

        assertEquals(3 * 224 * 224, t.size)

        val res = d.inputResolution
        val expectedR = (1f - d.pixelMean[0]) / d.pixelStd[0]
        val expectedG = (1f - d.pixelMean[1]) / d.pixelStd[1]
        val expectedB = (1f - d.pixelMean[2]) / d.pixelStd[2]

        assertEquals(expectedR, t[0], 1e-4f)
        assertEquals(expectedG, t[res * res], 1e-4f)
        assertEquals(expectedB, t[2 * res * res], 1e-4f)
    }

    @Test
    fun resize_input_bitmap_to_descriptor_resolution() {
        val d = Models.CLIP_VIT_B32
        // Input bitmap is smaller than 224x224
        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLACK)
        }
        val t = ImagePreprocessor(d).toTensor(bmp)
        assertEquals(3 * 224 * 224, t.size)
    }
}
