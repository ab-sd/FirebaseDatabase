package com.example.basicfiredatabase.utils

import android.content.Context
import android.view.View
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Small helper to upload multiple images.
 *
 * @param context for contentResolver to read Uris
 * @param uploadUrl backend endpoint (must be non-null)
 * @param apiKey header "x-api-key"
 *
 * The caller provides urisWithKeys: List of Pair(key, Uri). Key is an arbitrary String
 * used for progress callbacks (e.g. "0" or "new:3").
 *
 * onProgress(key, visible) is invoked on the MAIN thread (true before upload, false after).
 *
 * Returns a list of maps: each map contains "url" and "public_id".
 *
 * Throws Exception on error (HTTP errors / parse errors / IO).
 */
object ImageUploadService {

    private fun defaultClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    suspend fun uploadUris(
        context: Context,
        urisWithKeys: List<Pair<String, android.net.Uri>>,
        uploadUrl: String,
        apiKey: String,
        onProgress: (key: String, visible: Boolean) -> Unit
    ): List<Map<String, String>> {
        return withContext(Dispatchers.IO) {
            val client = defaultClient()
            val uploaded = mutableListOf<Map<String, String>>()

            for ((key, uri) in urisWithKeys) {
                // notify UI: show spinner (onProgress will be called on caller's thread, but we switch to Main below)
                // read file bytes
                val input: InputStream =
                    context.contentResolver.openInputStream(uri) ?: throw Exception("Cannot open image for key=$key")
                val rawBytes = input.readBytes()
                input.close()

                // compression (uses your util)
                val bytesToUpload = ImageCompressionUtil.maybeCompressIfNeeded(rawBytes)

                val mediaType = "image/*".toMediaTypeOrNull()
                val fileBody = bytesToUpload.toRequestBody(mediaType)

                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", "upload_$key.jpg", fileBody)
                    .build()

                val request = Request.Builder()
                    .url(uploadUrl)
                    .post(requestBody)
                    .addHeader("x-api-key", apiKey)
                    .build()

                // show spinner on main
                withContext(Dispatchers.Main) { onProgress(key, true) }

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string()

                // hide spinner on main in both success and failure branches once we have response
                try {
                    if (!response.isSuccessful || responseBody.isNullOrEmpty()) {
                        throw Exception("HTTP ${response.code}: ${responseBody ?: "empty response"}")
                    }

                    val json = JSONObject(responseBody)
                    val url = json.optString("url", "")
                    val publicId = json.optString("public_id", "")
                    if (url.isBlank() || publicId.isBlank()) {
                        throw Exception("Missing url or public_id in upload response for key=$key")
                    }

                    uploaded.add(mapOf("url" to url, "public_id" to publicId))
                } finally {
                    withContext(Dispatchers.Main) { onProgress(key, false) }
                }
            }

            uploaded.toList()
        }
    }
}
