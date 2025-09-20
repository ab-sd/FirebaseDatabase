package com.example.basicfiredatabase.fragments

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.basicfiredatabase.R
import com.google.firebase.ktx.Firebase
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.remoteconfig.ktx.remoteConfig
import com.google.firebase.remoteconfig.ktx.remoteConfigSettings
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
import java.util.concurrent.TimeUnit

class AddUserFragment : Fragment(R.layout.fragment_add_user) {

    private val db by lazy { Firebase.firestore }

    // selections and uploaded results
    private val selectedImageUris = mutableListOf<Uri>()
    private val uploadedImages = mutableListOf<Map<String, String>>() // url + public_id

    private var backendUrl: String? = null

    // maps image index -> progress spinner view (to show/hide while uploading)
    private val progressMap = mutableMapOf<Int, ProgressBar>()

    private val pickImagesLauncher =
        registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            selectedImageUris.clear()
            if (uris != null && uris.isNotEmpty()) {
                // limit to 10
                selectedImageUris.addAll(uris.take(10))
            }
            updateSelectedUI()
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val etUsername =
            view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.et_username)
        val etAge =
            view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.et_age)
        val btnPick = view.findViewById<Button>(R.id.btn_pick_images)
//        val btnUpload = view.findViewById<Button>(R.id.btn_upload_images)
        val btnSave = view.findViewById<Button>(R.id.btn_save)
        val tvImagesCount = view.findViewById<TextView>(R.id.tv_images_count)
        val llImages = view.findViewById<LinearLayout>(R.id.ll_selected_images)

        _tvImagesCount = tvImagesCount
        _llImages = llImages

        // Remote Config setup
        val remoteConfig = Firebase.remoteConfig
        val configSettings = remoteConfigSettings {
            minimumFetchIntervalInSeconds = 3600 // adjust for production
        }
        remoteConfig.setConfigSettingsAsync(configSettings)

        // fallback defaults (keep them here so app works offline)
        remoteConfig.setDefaultsAsync(
            mapOf("uploadImage_url" to "https://cloudinaryserver.onrender.com/upload")
        )

        // fetch and activate
        remoteConfig.fetchAndActivate()
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    backendUrl = remoteConfig.getString("uploadImage_url")
                    // debug toast (commented out normally)
                    Toast.makeText(requireContext(), "Fetched upload URL: $backendUrl", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "Failed to fetch Remote Config", Toast.LENGTH_SHORT)
                        .show()
                }
            }

        btnPick.setOnClickListener { pickImagesLauncher.launch("image/*") }

//        btnUpload.setOnClickListener {
//            if (selectedImageUris.isEmpty()) {
//                Toast.makeText(requireContext(), "No images selected to upload", Toast.LENGTH_SHORT).show()
//                return@setOnClickListener
//            }
//            if (backendUrl.isNullOrEmpty()) {
//                Toast.makeText(requireContext(), "Backend URL not loaded", Toast.LENGTH_SHORT).show()
//                return@setOnClickListener
//            }
//
//            // disable UI while uploading
//            btnUpload.isEnabled = false
//            btnPick.isEnabled = false
//            btnSave.isEnabled = false
//
//            lifecycleScope.launchWhenStarted {
//                uploadSelectedImages(
//                    onComplete = { images ->
//                        uploadedImages.clear()
//                        uploadedImages.addAll(images)
//                        Toast.makeText(requireContext(), "Uploaded ${images.size} image(s)", Toast.LENGTH_LONG).show()
//                        btnUpload.isEnabled = true
//                        btnPick.isEnabled = true
//                        btnSave.isEnabled = true
//                    },
//                    onError = { err ->
//                        Toast.makeText(requireContext(), "Upload error: $err", Toast.LENGTH_LONG).show()
//                        btnUpload.isEnabled = true
//                        btnPick.isEnabled = true
//                        btnSave.isEnabled = true
//                    }
//                )
//            }
//        }

        // SAVE button: first upload (if needed), then save the Firestore doc only if uploads succeed
        btnSave.setOnClickListener {
            val username = etUsername.text.toString().trim()
            val ageTxt = etAge.text.toString().trim()

            if (username.isEmpty() || ageTxt.isEmpty()) {
                Toast.makeText(requireContext(), "Fill both fields", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val age = ageTxt.toIntOrNull()
            if (age == null) {
                Toast.makeText(requireContext(), "Enter a valid age", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // If not all images uploaded, upload them first
            if (selectedImageUris.size != uploadedImages.size) {
                if (selectedImageUris.isEmpty()) {
                    // no images selected: just save
                    saveUser(username, age)
                    return@setOnClickListener
                }

                if (backendUrl.isNullOrEmpty()) {
                    Toast.makeText(requireContext(), "Backend URL not loaded", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                // disable UI while uploading
//                btnUpload.isEnabled = false
                btnPick.isEnabled = false
                btnSave.isEnabled = false

                lifecycleScope.launchWhenStarted {
                    uploadSelectedImages(
                        onComplete = { images ->
                            uploadedImages.clear()
                            uploadedImages.addAll(images)
                            // now save
                            saveUser(username, age)
//                            btnUpload.isEnabled = true
                            btnPick.isEnabled = true
                            btnSave.isEnabled = true
                        },
                        onError = { err ->
                            Toast.makeText(requireContext(), "Upload error: $err", Toast.LENGTH_LONG).show()
//                            btnUpload.isEnabled = true
                            btnPick.isEnabled = true
                            btnSave.isEnabled = true
                        }
                    )
                }
            } else {
                // images already uploaded -> save directly
                saveUser(username, age)
            }
        }
    }

    private fun saveUser(username: String, age: Int) {
        val user = hashMapOf(
            "username" to username,
            "age" to age,
            "images" to uploadedImages // contains url + public_id
        )

        db.collection("users")
            .add(user)
            .addOnSuccessListener { docRef ->
                Toast.makeText(requireContext(), "Saved (id=${docRef.id})", Toast.LENGTH_SHORT).show()
                // reset UI
                val etUsername = requireView().findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.et_username)
                val etAge = requireView().findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.et_age)
                etUsername.text?.clear()
                etAge.text?.clear()
                selectedImageUris.clear()
                uploadedImages.clear()
                progressMap.clear()
                updateSelectedUI()
            }
            .addOnFailureListener { e ->
                Toast.makeText(requireContext(), "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    private var _tvImagesCount: TextView? = null
    private var _llImages: LinearLayout? = null

    private fun updateSelectedUI() {
        val tv = _tvImagesCount ?: return
        val container = _llImages ?: return
        tv.text = "${selectedImageUris.size} image(s) selected"
        container.removeAllViews()
        progressMap.clear()

        val sizePx = (resources.displayMetrics.density * 80).toInt()
        val marginPx = (resources.displayMetrics.density * 6).toInt()

        // for each selected image, create a FrameLayout with ImageView + ProgressBar (spinner)
        for ((index, uri) in selectedImageUris.withIndex()) {
            val frame = FrameLayout(requireContext())
            val frameLp = LinearLayout.LayoutParams(sizePx, sizePx)
            frameLp.setMargins(marginPx, marginPx, marginPx, marginPx)
            frame.layoutParams = frameLp

            val iv = ImageView(requireContext())
            val ivLp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            iv.layoutParams = ivLp
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            iv.setImageURI(uri)

            val progress = ProgressBar(requireContext())
            val pSize = (resources.displayMetrics.density * 32).toInt()
            val pLp = FrameLayout.LayoutParams(pSize, pSize)
            pLp.gravity = Gravity.CENTER
            progress.layoutParams = pLp
            progress.isIndeterminate = true
            // yellow tint for spinner
            try {
                progress.indeterminateTintList = resources.getColorStateList(android.R.color.holo_orange_light, null)
            } catch (_: Exception) { /* fallback if older API */ }

            progress.visibility = View.GONE

            // store progress bar so upload can show/hide it
            progressMap[index] = progress

            frame.addView(iv)
            frame.addView(progress)
            container.addView(frame)
        }
    }

    private suspend fun uploadSelectedImages(
        onComplete: (List<Map<String, String>>) -> Unit,
        onError: (String) -> Unit
    ) {
        withContext(Dispatchers.IO) {
            // Increase timeouts for uploads (30s)
            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()

            val uploaded = mutableListOf<Map<String, String>>()

            try {
                for ((index, uri) in selectedImageUris.withIndex()) {
                    // show spinner for this image on main thread
                    withContext(Dispatchers.Main) {
                        progressMap[index]?.visibility = View.VISIBLE
                    }

                    val input: InputStream = requireContext().contentResolver.openInputStream(uri)
                        ?: throw Exception("Cannot open image #$index")
                    val rawBytes = input.readBytes()
                    input.close()

                    // if large (bytes > 2MB) or max dimension > 1080, compress
                    val bytesToUpload = maybeCompressIfNeeded(rawBytes)

                    val mediaType = "image/*".toMediaTypeOrNull()
                    val fileBody = bytesToUpload.toRequestBody(mediaType)

                    val requestBody = MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("file", "upload_$index.jpg", fileBody)
                        .build()

                    val request = Request.Builder()
                        .url(backendUrl!!) // use Remote Config value (checked earlier)
                        .post(requestBody)
                        .build()

                    val response = client.newCall(request).execute()
                    val responseBody = response.body?.string()
                    if (!response.isSuccessful || responseBody.isNullOrEmpty()) {
                        withContext(Dispatchers.Main) { progressMap[index]?.visibility = View.GONE }
                        throw Exception("HTTP ${response.code}: ${responseBody ?: "empty response"}")
                    }

                    val json = JSONObject(responseBody)
                    val url = json.optString("url", "")
                    val publicId = json.optString("public_id", "")
                    if (url.isBlank() || publicId.isBlank()) {
                        withContext(Dispatchers.Main) { progressMap[index]?.visibility = View.GONE }
                        throw Exception("Missing url or public_id")
                    }

                    uploaded.add(mapOf("url" to url, "public_id" to publicId))

                    // hide spinner after this upload
                    withContext(Dispatchers.Main) { progressMap[index]?.visibility = View.GONE }
                }

                withContext(Dispatchers.Main) { onComplete(uploaded) }
            } catch (e: Exception) {
                // hide any visible spinners
                withContext(Dispatchers.Main) {
                    progressMap.values.forEach { it.visibility = View.GONE }
                    onError(e.message ?: "Upload failed")
                }
            }
        }
    }

    /**
     * Compress only if needed:
     * - If bytes > 2MB OR max dimension > 1080, scale down to max 1080 and compress to JPEG 85%.
     * - Otherwise return original bytes.
     */
    private fun maybeCompressIfNeeded(bytes: ByteArray): ByteArray {
        try {
            // quick size check
            val sizeInBytes = bytes.size
            val twoMB = 2 * 1024 * 1024
            if (sizeInBytes <= twoMB) {
                // still check dimensions
                val opts = BitmapFactory.Options()
                opts.inJustDecodeBounds = true
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                val maxDim = maxOf(opts.outWidth, opts.outHeight)
                if (maxDim <= 1080) return bytes
            }

            // decode full bitmap (careful with very large images)
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
            // if anything goes wrong, return original bytes
            return bytes
        }
    }
}
