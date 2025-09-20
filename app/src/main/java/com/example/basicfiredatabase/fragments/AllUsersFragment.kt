package com.example.basicfiredatabase.fragments

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.basicfiredatabase.R
import com.example.basicfiredatabase.adapters.UserAdapter
import com.example.basicfiredatabase.models.User
import com.example.basicfiredatabase.models.UserImage
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

class AllUsersFragment : Fragment(R.layout.fragment_all_users) {

    private val db = Firebase.firestore
    private lateinit var rv: RecyclerView

    private var deleteImage_url: String? = null


    private val adapter = UserAdapter(
        onEdit = { user ->
            val fragment = EditUserFragment().apply {
                arguments = Bundle().apply {
                    putString("id", user.id)
                    putString("username", user.username)
                    putInt("age", user.age ?: 0)
                    // ✅ also pass images
                    putSerializable(
                        "images",
                        ArrayList(user.images.map { mapOf("url" to it.url, "public_id" to it.public_id) })
                    )
                }
            }
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, fragment) // your container id
                .addToBackStack(null)
                .commit()
        },
        onDelete = { user ->
            // ✅ launch coroutine for Cloudinary deletion
            lifecycleScope.launch {
                val success = deleteImagesFromCloudinary(user.images)
                if (!success) {
                    Toast.makeText(requireContext(), "Some images could not be deleted", Toast.LENGTH_LONG).show()
                }

                db.collection("users").document(user.id)
                    .delete()
                    .addOnSuccessListener {
                        Toast.makeText(requireContext(), "Deleted ${user.username}", Toast.LENGTH_SHORT).show()
                    }
                    .addOnFailureListener { e ->
                        Log.w("AllUsersFragment", "Error deleting", e)
                        Toast.makeText(requireContext(), "Delete failed", Toast.LENGTH_SHORT).show()
                    }
            }
        }
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        rv = view.findViewById(R.id.rv_users)
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter


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


        // realtime listener - keeps UI updated
        db.collection("users")
            .addSnapshotListener { snapshots, e ->
                if (e != null) {
                    Log.w("AllUsersFragment", "Listen failed.", e)
                    return@addSnapshotListener
                }
                if (snapshots == null) return@addSnapshotListener

                val list = snapshots.documents.map { doc ->
                    val id = doc.id
                    val username = doc.getString("username") ?: "No name"
                    val age = doc.getLong("age")?.toInt()

                    val images = (doc.get("images") as? List<*>)?.mapNotNull { item ->
                        val map = item as? Map<*, *>
                        val url = map?.get("url") as? String
                        val publicId = map?.get("public_id") as? String
                        if (url != null && publicId != null) {
                            UserImage(url, publicId)
                        } else null
                    } ?: emptyList()

                    User(id = id, username = username, age = age, images = images)
                }
                adapter.setItems(list)
            }
    }

    // ✅ helper function to delete from Cloudinary
    private suspend fun deleteImagesFromCloudinary(images: List<UserImage>): Boolean {
        if (images.isEmpty()) return true
        return withContext(Dispatchers.IO) {
            try {
                val client = OkHttpClient()
                for (image in images) {
                    val body = JSONObject().put("public_id", image.public_id).toString()
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
