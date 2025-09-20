package com.example.basicfiredatabase.fragments

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.basicfiredatabase.R
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.google.firebase.remoteconfig.ktx.remoteConfig
import com.google.firebase.remoteconfig.ktx.remoteConfigSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class EditUserFragment : Fragment(R.layout.fragment_edit_user) {

    private val db = Firebase.firestore
    private var userId: String? = null
    private var userImages: List<Map<String, String>> = emptyList()

    private var deleteImage_url: String? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val etName = view.findViewById<EditText>(R.id.et_edit_name)
        val etAge = view.findViewById<EditText>(R.id.et_edit_age)
        val btnUpdate = view.findViewById<Button>(R.id.btn_update)
        val btnDelete = view.findViewById<Button>(R.id.btn_delete)

        // --- arguments ---
        userId = arguments?.getString("id")
        val name = arguments?.getString("username") ?: ""
        val age = arguments?.getInt("age") ?: 0
        @Suppress("UNCHECKED_CAST")
        userImages = arguments?.getSerializable("images") as? List<Map<String, String>> ?: emptyList()

        etName.setText(name)
        etAge.setText(age.toString())


        // --- Remote Config setup for deleteImage_url ---
        val remoteConfig = Firebase.remoteConfig
        val configSettings = remoteConfigSettings {
            minimumFetchIntervalInSeconds = 3600 // always fetch fresh for testing
        }
        remoteConfig.setConfigSettingsAsync(configSettings)

        // as a fallback if Remote Config fails
        remoteConfig.setDefaultsAsync(
            mapOf("deleteImage_url" to "https://cloudinaryserver.onrender.com/delete")
        )

        remoteConfig.fetchAndActivate()
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    deleteImage_url = remoteConfig.getString("deleteImage_url")
                    Toast.makeText(
                        requireContext(),
                        "Fetched delete URL: $deleteImage_url",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(
                        requireContext(),
                        "Failed to fetch delete URL from Remote Config",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }



        btnUpdate.setOnClickListener {
            val newName = etName.text.toString().trim()
            val newAge = etAge.text.toString().toIntOrNull()

            if (userId == null || newName.isEmpty() || newAge == null) {
                Toast.makeText(requireContext(), "Fill in details", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            db.collection("users").document(userId!!)
                .update(
                    mapOf(
                        "username" to newName,
                        "age" to newAge
                    )
                )
                .addOnSuccessListener {
                    Toast.makeText(requireContext(), "Updated!", Toast.LENGTH_SHORT).show()
                    parentFragmentManager.popBackStack()
                }
                .addOnFailureListener { e ->
                    Toast.makeText(requireContext(), "Update failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
        }

        btnDelete.setOnClickListener {
            if (userId == null) return@setOnClickListener

            lifecycleScope.launch {
                val success = deleteImagesFromCloudinary(userImages)
                if (!success) {
                    Toast.makeText(requireContext(), "Some images could not be deleted", Toast.LENGTH_LONG).show()
                }

                db.collection("users").document(userId!!)
                    .delete()
                    .addOnSuccessListener {
                        Toast.makeText(requireContext(), "Deleted!", Toast.LENGTH_SHORT).show()
                        parentFragmentManager.popBackStack()
                    }
                    .addOnFailureListener { e ->
                        Toast.makeText(requireContext(), "Delete failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
            }
        }
    }

    private suspend fun deleteImagesFromCloudinary(images: List<Map<String, String>>): Boolean {
        if (images.isEmpty()) return true
        return withContext(Dispatchers.IO) {
            try {
                val client = OkHttpClient()
                for (image in images) {
                    val publicId = image["public_id"] ?: continue
                    val body = JSONObject().put("public_id", publicId).toString()
                        .toRequestBody("application/json".toMediaTypeOrNull())

                    val request = Request.Builder()
                        .url(deleteImage_url!!)
                        .post(body)
                        .build()

                    val response = client.newCall(request).execute()
                    if (!response.isSuccessful) {
                        return@withContext false
                    }
                }
                true
            } catch (e: Exception) {
                false
            }
        }
    }
}
