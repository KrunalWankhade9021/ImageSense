package com.nlphotos.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.nlphotos.model.ModelDescriptor

class ImagePreprocessor(private val descriptor: ModelDescriptor) {

    /**
     * Converts a Bitmap to a normalized CHW float tensor expected by the CLIP image encoder.
     *
     * Steps:
     * 1. Resize bitmap to inputResolution x inputResolution using bilinear filtering.
     * 2. Extract RGB pixels.
     * 3. Scale to [0, 1] then normalize per channel: (pixel/255 - mean) / std.
     * 4. Output in CHW order: index = c * res * res + y * res + x.
     *
     * @param bitmap Input bitmap (any size).
     * @return FloatArray of length 3 * inputResolution * inputResolution.
     */
    fun toTensor(bitmap: android.graphics.Bitmap): FloatArray {
        val res = descriptor.inputResolution
        val scaled = if (bitmap.width == res && bitmap.height == res) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, res, res, true)
        }

        val pixels = IntArray(res * res)
        scaled.getPixels(pixels, 0, res, 0, 0, res, res)

        val tensor = FloatArray(3 * res * res)
        val mean = descriptor.pixelMean
        val std = descriptor.pixelStd

        for (i in pixels.indices) {
            val px = pixels[i]
            val r = ((px shr 16) and 0xFF) / 255f
            val g = ((px shr 8) and 0xFF) / 255f
            val b = (px and 0xFF) / 255f

            tensor[0 * res * res + i] = (r - mean[0]) / std[0]
            tensor[1 * res * res + i] = (g - mean[1]) / std[1]
            tensor[2 * res * res + i] = (b - mean[2]) / std[2]
        }

        if (scaled !== bitmap) scaled.recycle()

        return tensor
    }

    /**
     * Memory-safe decode of a content URI. Uses BitmapFactory.Options.inSampleSize to
     * downsample the image so the decoded bitmap is at least targetRes on the short side,
     * avoiding loading the full-resolution image into memory.
     *
     * @param context Android context for ContentResolver.
     * @param uri Content URI of the image.
     * @param targetRes Target resolution (default: descriptor.inputResolution).
     * @return Decoded and downsampled Bitmap.
     */
    fun decodeDownsampled(
        context: Context,
        uri: Uri,
        targetRes: Int = descriptor.inputResolution
    ): Bitmap {
        // First pass: decode bounds only
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        }

        // Compute inSampleSize so short side >= targetRes
        options.inSampleSize = computeSampleSize(options.outWidth, options.outHeight, targetRes)
        options.inJustDecodeBounds = false

        return context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        } ?: error("Failed to decode image from URI: $uri")
    }

    private fun computeSampleSize(width: Int, height: Int, targetRes: Int): Int {
        val shortSide = minOf(width, height)
        var sampleSize = 1
        // Keep halving until the next halving would make the short side < targetRes
        while (shortSide / (sampleSize * 2) >= targetRes) {
            sampleSize *= 2
        }
        return sampleSize
    }
}
