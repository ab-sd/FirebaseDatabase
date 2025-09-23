package com.example.basicfiredatabase.utils

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import android.util.Log
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.textfield.TextInputEditText

object TranslationHelper {
    private val translateClient = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private fun sanitizeText(s: String): String {
        var r = s.replace(Regex("<[^>]*>"), " ")
        r = r.replace("&nbsp;", " ")
        r = r.replace(Regex("\\s+"), " ").trim()
        r = r.replace(Regex("[\\u0000-\\u001F\\u007F]+"), "")
        return r
    }

    suspend fun translateText(originalText: String, url: String, apiKey: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                val jsonBody = JSONObject().put("text", originalText).toString()
                val requestBody =
                    jsonBody.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
                val request = Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .addHeader("x-api-key", apiKey)
                    .build()

                val response = translateClient.newCall(request).execute()
                val body = response.body?.string() ?: return@withContext null
                if (!response.isSuccessful) {
                    Log.w(
                        "TranslationHelper",
                        "failed code=${response.code} body=${body.take(500)}"
                    )
                    return@withContext null
                }

                // try parse JSON similarly to your existing logic:
                try {
                    val j = JSONObject(body)
                    val top = j.optString("translatedText", "").trim()
                    if (top.isNotBlank()) return@withContext sanitizeText(top)

                    val rawObj = j.optJSONObject("raw")
                    val respData = rawObj?.optJSONObject("responseData")
                    val rawResp = respData?.optString("translatedText", "")?.trim()
                    if (!rawResp.isNullOrBlank()) return@withContext sanitizeText(rawResp)

                    val matchesArr = rawObj?.optJSONArray("matches") ?: j.optJSONArray("matches")
                    if (matchesArr != null) {
                        for (i in 0 until matchesArr.length()) {
                            val m = matchesArr.optJSONObject(i) ?: continue
                            val tr = m.optString("translation", "").trim()
                            if (tr.isNotBlank()) return@withContext sanitizeText(tr)
                        }
                    }

                    val keys = listOf("translation", "translated", "text", "result")
                    for (k in keys) {
                        val v = j.optString(k, "").trim()
                        if (v.isNotBlank()) return@withContext sanitizeText(v)
                    }
                } catch (e: Exception) {
                    Log.d("TranslationHelper", "json parse: ${e.message}")
                }

                val sanitized = sanitizeText(body)
                if (sanitized.isNotBlank()) return@withContext sanitized
                null
            } catch (e: Exception) {
                Log.w("TranslationHelper", "translate failed", e)
                null
            }
        }
    }


    fun pasteFromClipboard(context: Context, target: TextInputEditText): Boolean {
        return try {
            val clipboard =
                context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = clipboard.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).coerceToText(context).toString()
                if (text.isNotBlank()) {
                    // set text and place cursor at end
                    target.setText(text)
                    target.requestFocus()
                    target.setSelection(target.text?.length ?: 0)
                    // brief feedback
                    Toast.makeText(context, "Pasted from clipboard", Toast.LENGTH_SHORT).show()
                    true
                } else {
                    Toast.makeText(context, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                    false
                }
            } else {
                Toast.makeText(context, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                false
            }
        } catch (e: Exception) {
            Log.w("TranslationHelper", "pasteFromClipboard failed", e)
            Toast.makeText(context, "Paste failed", Toast.LENGTH_SHORT).show()
            false
        }
    }



    fun openGoogleTranslateFromPrimary(fragment: Fragment, primaryText: String, targetLang: String) {
        val originalText = primaryText.trim()
        val ctx = fragment.requireContext()

        if (originalText.isBlank()) {
            Toast.makeText(ctx, "Enter text in primary box to verify", Toast.LENGTH_SHORT).show()
            return
        }

        val encodedText = Uri.encode(originalText)

        // 1) Try to open Google Translate app via ACTION_SEND
        val translatePackage = "com.google.android.apps.translate"
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, originalText)
            `package` = translatePackage
        }

        try {
            if (sendIntent.resolveActivity(fragment.requireActivity().packageManager) != null) {
                fragment.startActivity(sendIntent)
                return
            } else {
                Log.d("TranslationHelper", "Translate app not installed or not visible to package queries")
            }
        } catch (e: Exception) {
            Log.e("TranslationHelper", "Error while trying to open Translate app", e)
            // continue to fallback
        }

        // 2) Fallback: open Translate web with prefilled text
        val webUrl = "https://translate.google.com/?sl=auto&tl=$targetLang&text=$encodedText&op=translate"
        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(webUrl))
        val chooser = Intent.createChooser(browserIntent, "Open translation with")

        try {
            fragment.startActivity(chooser)
        } catch (e: ActivityNotFoundException) {
            Log.e("TranslationHelper", "No app found to open translation URL", e)
            Toast.makeText(ctx, "No app available to open translation", Toast.LENGTH_SHORT).show()
        }
    }




}



