package com.example.basicfiredatabase.utils

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream

object SharedUtils {

    // --- Display / layout helpers ---
    fun dpToPx(context: Context, dp: Int): Int =
        (dp * context.resources.displayMetrics.density).toInt()

    /**
     * Scroll helper: computes rect for `view` inside `scrollView` and scrolls so the view bottom
     * is visible after accounting for ime/nav heights.
     */
    fun scrollToView(scrollView: android.widget.ScrollView, view: android.view.View, lastImeHeight: Int, lastNavHeight: Int, extra: Int = 0) {
        val rect = android.graphics.Rect()
        view.getDrawingRect(rect)
        scrollView.offsetDescendantRectToMyCoords(view, rect)
        val visibleHeight = scrollView.height - lastImeHeight - lastNavHeight
        val targetBottom = rect.bottom
        val scrollY = targetBottom - (visibleHeight - extra)
        if (scrollY > 0) {
            scrollView.post { scrollView.smoothScrollTo(0, scrollY) }
        } else {
            scrollView.post { scrollView.smoothScrollTo(0, maxOf(0, scrollView.scrollY - dpToPx(scrollView.context, 4))) }
        }
    }

    // --- Image helpers (used by both fragments) ---
    fun loadThumbnail(context: Context, uri: Uri, maxSize: Int = 160): Bitmap {
        val input = context.contentResolver.openInputStream(uri) ?: return Bitmap.createBitmap(1,1,Bitmap.Config.ARGB_8888)
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeStream(input, null, options)
        input.close()

        val width = options.outWidth
        val height = options.outHeight
        var scale = 1
        while (width / scale > maxSize || height / scale > maxSize) scale *= 2

        val input2 = context.contentResolver.openInputStream(uri) ?: return Bitmap.createBitmap(1,1,Bitmap.Config.ARGB_8888)
        val opts2 = BitmapFactory.Options().apply { inSampleSize = scale }
        val bitmap = BitmapFactory.decodeStream(input2, null, opts2)
        input2.close()
        return bitmap ?: Bitmap.createBitmap(1,1,Bitmap.Config.ARGB_8888)
    }

    /**
     * If bytes > 2MB or largest dimension > 1080, scale down to max 1080 and compress JPEG 85%.
     * Otherwise return original bytes.
     */
    fun maybeCompressIfNeeded(bytes: ByteArray): ByteArray {
        return try {
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
            baos.toByteArray()
        } catch (e: Exception) {
            bytes
        }
    }

    // --- Network helpers: upload images (suspend) ---
    /**
     * Uploads a list of URIs to `uploadUrl` with optional `apiKey`.
     * - contentResolver: use fragment.requireContext().contentResolver
     * - progressCallback runs on Main and is called with (index, visible:Boolean) to show/hide spinner
     *
     * Returns list of maps { "url": "...", "public_id": "..." } on success or throws on failure.
     */
    suspend fun uploadImages(
        contentResolver: ContentResolver,
        client: OkHttpClient,
        uploadUrl: String,
        apiKey: String?,
        uris: List<Uri>,
        progressCallback: (index: Int, visible: Boolean) -> Unit
    ): List<Map<String, String>> = withContext(Dispatchers.IO) {
        val uploaded = mutableListOf<Map<String, String>>()
        try {
            for ((index, uri) in uris.withIndex()) {
                // show spinner
                withContext(Dispatchers.Main) { progressCallback(index, true) }

                val input: InputStream = contentResolver.openInputStream(uri)
                    ?: throw Exception("Cannot open image #$index")
                val rawBytes = input.readBytes()
                input.close()

                val bytesToUpload = maybeCompressIfNeeded(rawBytes)
                val mediaType = "image/*".toMediaTypeOrNull()
                val fileBody = bytesToUpload.toRequestBody(mediaType)

                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", "upload_$index.jpg", fileBody)
                    .build()

                val builder = Request.Builder()
                    .url(uploadUrl)
                    .post(requestBody)

                if (!apiKey.isNullOrEmpty()) builder.addHeader("x-api-key", apiKey)

                val response = client.newCall(builder.build()).execute()
                val responseBody = response.body?.string()
                if (!response.isSuccessful || responseBody.isNullOrEmpty()) {
                    withContext(Dispatchers.Main) { progressCallback(index, false) }
                    throw Exception("HTTP ${response.code}: ${responseBody ?: "empty response"}")
                }

                val json = JSONObject(responseBody)
                val url = json.optString("url", "")
                val publicId = json.optString("public_id", "")
                if (url.isBlank() || publicId.isBlank()) {
                    withContext(Dispatchers.Main) { progressCallback(index, false) }
                    throw Exception("Missing url or public_id")
                }

                uploaded.add(mapOf("url" to url, "public_id" to publicId))
                withContext(Dispatchers.Main) { progressCallback(index, false) }
            }
            uploaded
        } catch (e: Exception) {
            // hide all progress indicators on failure
            withContext(Dispatchers.Main) {
                for (i in uris.indices) progressCallback(i, false)
            }
            throw e
        }
    }

    // --- Network helpers: delete images (suspend) ---
    /**
     * Delete images by public_id at deleteUrl. Calls progressCallback(publicId, visible).
     * Returns true if all deletions succeeded.
     */
    suspend fun deleteImages(
        client: OkHttpClient,
        deleteUrl: String,
        apiKey: String?,
        publicIds: List<String>,
        progressCallback: (publicId: String, visible: Boolean) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            for (id in publicIds) {
                withContext(Dispatchers.Main) { progressCallback(id, true) }

                val body = JSONObject().put("public_id", id).toString()
                    .toRequestBody("application/json".toMediaTypeOrNull())

                val builder = Request.Builder()
                    .url(deleteUrl)
                    .post(body)

                if (!apiKey.isNullOrEmpty()) builder.addHeader("x-api-key", apiKey)

                val response = client.newCall(builder.build()).execute()
                withContext(Dispatchers.Main) { progressCallback(id, false) }
                if (!response.isSuccessful) return@withContext false
            }
            true
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { for (id in publicIds) progressCallback(id, false) }
            false
        }
    }

    // --- Translation helpers ---
    /**
     * Synchronous network call to translation endpoint. Returns translated text or null.
     * If url is null/empty returns null.
     */
    fun translateText(client: OkHttpClient, originalText: String, url: String?, apiKey: String?): String? {
        if (url.isNullOrEmpty()) return null
        return try {
            val jsonBody = JSONObject().put("text", originalText).toString()
            val requestBody = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())

            val builder = Request.Builder().url(url).post(requestBody)
            if (!apiKey.isNullOrEmpty()) builder.addHeader("x-api-key", apiKey)
            val response = client.newCall(builder.build()).execute()
            val body = response.body?.string() ?: return null
            if (!response.isSuccessful) return null

            try {
                val j = JSONObject(body)
                val candidates = listOf("translation", "translatedText", "translated", "result", "text", "data")
                for (k in candidates) {
                    val v = j.optString(k, "")
                    if (v.isNotBlank()) return v
                }
                for (key in j.keys()) {
                    val valObj = j.opt(key)
                    if (valObj is JSONObject) {
                        for (k in candidates) {
                            val v2 = valObj.optString(k, "")
                            if (v2.isNotBlank()) return v2
                        }
                    }
                }
            } catch (_: Exception) { /* ignore */ }
            if (body.isNotBlank()) body else null
        } catch (e: Exception) {
            Log.e("SharedUtils", "translateText error", e)
            null
        }
    }

    /**
     * Opens Google Translate app (if installed) or the Translate web URL fallback.
     * Reads plain originalText (caller supplies text).
     */
    fun openGoogleTranslate(context: Context, originalText: String, targetLang: String) {
        val encodedText = Uri.encode(originalText)
        val translatePackage = "com.google.android.apps.translate"
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, originalText)
            `package` = translatePackage
        }

        try {
            if (sendIntent.resolveActivity(context.packageManager) != null) {
                context.startActivity(sendIntent)
                return
            }
        } catch (e: Exception) {
            Log.d("SharedUtils", "Translate app not available: ${e.message}")
        }

        val webUrl = "https://translate.google.com/?sl=auto&tl=$targetLang&text=$encodedText&op=translate"
        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(webUrl))
        val chooser = Intent.createChooser(browserIntent, "Open translation with")
        try {
            context.startActivity(chooser)
        } catch (e: Exception) {
            Log.e("SharedUtils", "openGoogleTranslate failed", e)
            android.widget.Toast.makeText(context, "No app available to open translation", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}
