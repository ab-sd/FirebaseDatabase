package com.example.basicfiredatabase.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream

/**
 * Utility for thumbnail loading and image compression used across fragments.
 */
object ImageCompressionUtil {


    /**
     * Load a memory-friendly thumbnail for the given Uri.
     *
     * - Decodes bounds first, computes a power-of-two inSampleSize so the decoded
     *   bitmap fits within maxSize x maxSize.
     * - Returns a 1x1 placeholder bitmap on any failure (keeps callers simple).
     */
    fun loadThumbnail(context: Context, uri: Uri, maxSize: Int = 160): Bitmap {
        try {
            val resolver = context.contentResolver

            // 1) read bounds
            resolver.openInputStream(uri).use { input ->
                if (input == null) return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
                val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(input, null, boundsOpts)
                // if bounds invalid, bail early
                if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) {
                    return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
                }
            }

            // 2) compute power-of-two sample size
            val sampleSize = run {
                // re-read bounds to compute (safe and avoids sharing state)
                resolver.openInputStream(uri).use { input2 ->
                    if (input2 == null) return@run 1
                    val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeStream(input2, null, b)
                    val width = b.outWidth
                    val height = b.outHeight
                    var scale = 1
                    while (width / scale > maxSize || height / scale > maxSize) {
                        scale *= 2
                    }
                    scale
                } ?: 1
            }

            // 3) decode with sample size
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            resolver.openInputStream(uri).use { input3 ->
                if (input3 == null) return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
                val bmp = BitmapFactory.decodeStream(input3, null, decodeOpts)
                return bmp ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            }
        } catch (e: Exception) {
            // On error, return tiny placeholder
            return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        }
    }




    /**
     * If image bytes are large (>2MB) or large dimension (>1080), scale the bitmap so the largest
     * dimension is 1080 and compress to JPEG @85 quality. Otherwise returns the original bytes.
     *
     * This mirrors your fragments' behaviour exactly.
     */
    fun maybeCompressIfNeeded(bytes: ByteArray): ByteArray {
        try {
            val sizeInBytes = bytes.size
            val twoMB = 2 * 1024 * 1024
            if (sizeInBytes <= twoMB) {
                val opts = BitmapFactory.Options()
                opts.inJustDecodeBounds = true
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                val maxDim = maxOf(opts.outWidth, opts.outHeight)
                if (maxDim <= 1080) return bytes
            }

            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return bytes
            val maxDim = maxOf(bmp.width, bmp.height)
            if (maxDim <= 1080 && sizeInBytes <= twoMB) return bytes

            val scale = 1080f / maxDim
            val newW = (bmp.width * scale).toInt()
            val newH = (bmp.height * scale).toInt()
            val scaled = Bitmap.createScaledBitmap(bmp, newW, newH, true)

            val baos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, baos)
            return baos.toByteArray()
        } catch (e: Exception) {
            return bytes
        }
    }
}
