package com.example.basicfiredatabase.fragments

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.basicfiredatabase.R
import com.google.firebase.ktx.Firebase
import com.google.firebase.firestore.ktx.firestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.InputStream

class AddUserFragment : Fragment(R.layout.fragment_add_user) {

    private val db by lazy { Firebase.firestore }

    // selections and uploaded URLs
    private val selectedImageUris = mutableListOf<Uri>()
    private val uploadedImageUrls = mutableListOf<String>()

    private val pickImagesLauncher = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        selectedImageUris.clear()
        if (uris != null && uris.isNotEmpty()) {
            selectedImageUris.addAll(uris.take(5)) // limit to 5
        }
        updateSelectedUI()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val etUsername = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.et_username)
        val etAge = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.et_age)
        val btnPick = view.findViewById<Button>(R.id.btn_pick_images)
        val btnUpload = view.findViewById<Button>(R.id.btn_upload_images)
        val btnSave = view.findViewById<Button>(R.id.btn_save)
        val tvImagesCount = view.findViewById<TextView>(R.id.tv_images_count)
        val llImages = view.findViewById<LinearLayout>(R.id.ll_selected_images)

        // store references for update function
        _tvImagesCount = tvImagesCount
        _llImages = llImages

        btnPick.setOnClickListener { pickImagesLauncher.launch("image/*") }

        btnUpload.setOnClickListener {
            if (selectedImageUris.isEmpty()) {
                Toast.makeText(requireContext(), "No images selected to upload", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // disable buttons while uploading
            btnUpload.isEnabled = false
            btnPick.isEnabled = false
            lifecycleScope.launchWhenStarted {
                uploadSelectedImages(
                    onComplete = { urls ->
                        uploadedImageUrls.clear()
                        uploadedImageUrls.addAll(urls)
                        Toast.makeText(requireContext(), "Uploaded ${urls.size} image(s)", Toast.LENGTH_LONG).show()
                        btnUpload.isEnabled = true
                        btnPick.isEnabled = true
                    },
                    onError = { err ->
                        Toast.makeText(requireContext(), "Upload error: $err", Toast.LENGTH_LONG).show()
                        btnUpload.isEnabled = true
                        btnPick.isEnabled = true
                    }
                )
            }
        }

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

            // prepare map (include images if uploaded)
            val user = hashMapOf(
                "username" to username,
                "age" to age,
                "images" to uploadedImageUrls.toList() // may be empty
            )

            db.collection("users")
                .add(user)
                .addOnSuccessListener { docRef ->
                    Toast.makeText(requireContext(), "Saved (id=${docRef.id})", Toast.LENGTH_SHORT).show()
                    etUsername.text?.clear()
                    etAge.text?.clear()
                    selectedImageUris.clear()
                    uploadedImageUrls.clear()
                    updateSelectedUI()
                }
                .addOnFailureListener { e ->
                    Toast.makeText(requireContext(), "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
        }
    }

    // UI helpers (store these to avoid passing as params)
    private var _tvImagesCount: TextView? = null
    private var _llImages: LinearLayout? = null

    private fun updateSelectedUI() {
        val tv = _tvImagesCount ?: return
        val container = _llImages ?: return
        tv.text = "${selectedImageUris.size} image(s) selected"
        container.removeAllViews()
        val sizePx = (resources.displayMetrics.density * 80).toInt()
        val marginPx = (resources.displayMetrics.density * 6).toInt()
        for (uri in selectedImageUris) {
            val iv = ImageView(requireContext())
            val lp = LinearLayout.LayoutParams(sizePx, sizePx)
            lp.setMargins(marginPx, marginPx, marginPx, marginPx)
            iv.layoutParams = lp
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            iv.setImageURI(uri)
            container.addView(iv)
        }
    }

    // Upload images sequentially on IO dispatcher, return list of secure URLs on success
    private suspend fun uploadSelectedImages(
        onComplete: (List<String>) -> Unit,
        onError: (String) -> Unit
    ) {
        withContext(Dispatchers.IO) {
            val cloudName = getString(R.string.cloudinary_cloud_name)
            val uploadPreset = getString(R.string.cloudinary_upload_preset)
            val client = OkHttpClient()
            val uploaded = mutableListOf<String>()

            try {
                for ((index, uri) in selectedImageUris.withIndex()) {
                    // open the image bytes
                    val input: InputStream = requireContext().contentResolver.openInputStream(uri)
                        ?: throw Exception("Cannot open image #$index")
                    val bytes = input.readBytes()
                    input.close()

                    val mediaType = "image/*".toMediaTypeOrNull()
                    val fileBody = bytes.toRequestBody(mediaType)

                    val requestBody = MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("file", "upload_$index.jpg", fileBody)
                        .addFormDataPart("upload_preset", uploadPreset)
                        .addFormDataPart("folder", "events") // 👈 all uploads go into "users" folder
                        .build()

                    val url = "https://api.cloudinary.com/v1_1/$cloudName/image/upload"
                    val request = Request.Builder().url(url).post(requestBody).build()
                    val response = client.newCall(request).execute()

                    val responseBody = response.body?.string()
                    if (!response.isSuccessful || responseBody.isNullOrEmpty()) {
                        throw Exception("HTTP ${response.code}: ${responseBody ?: "empty response"}")
                    }

                    val json = JSONObject(responseBody)
                    val secureUrl = json.optString("secure_url", "")
                    if (secureUrl.isBlank()) throw Exception("No secure_url in response")
                    uploaded.add(secureUrl)
                }

                // back to main thread to call completion
                withContext(Dispatchers.Main) {
                    onComplete(uploaded)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onError(e.message ?: "Upload failed")
                }
            }
        }
    }
}
