package com.example.basicfiredatabase.fragments

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
                    putString("title", user.title)
                    putString("event_type", user.eventType)
                    putSerializable("descriptions", HashMap(user.descriptions))
                    putString("date", user.date)
                    putString("time", user.time)
                    putSerializable(
                        "images",
                        ArrayList(user.images.map { mapOf("url" to it.url, "public_id" to it.public_id) })
                    )
                    putInt("duration_minutes", user.durationMinutes ?: 0)
                    putString("location", user.location)
                    putBoolean("is_upcoming", user.isUpcoming)
                }
            }
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .addToBackStack(null)
                .commit()
        },
        onDelete = { user ->
            lifecycleScope.launch {
                val success = deleteImagesFromCloudinary(user.images)
                if (!success) {
                    Toast.makeText(requireContext(), "Some images could not be deleted", Toast.LENGTH_LONG).show()
                }

                db.collection("users").document(user.id)
                    .delete()
                    .addOnSuccessListener {
                        Toast.makeText(requireContext(), "Deleted ${user.title}", Toast.LENGTH_SHORT).show()
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


        // Adjust RecyclerView bottom padding for nav bar dynamically
        ViewCompat.setOnApplyWindowInsetsListener(rv) { v, insets ->
            val navBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, navBottom + dpToPx(8))
            insets
        }


        // Remote Config setup for deleteImage_url
        val remoteConfig = Firebase.remoteConfig
        val configSettings = remoteConfigSettings {
            minimumFetchIntervalInSeconds = 3600
        }
        remoteConfig.setConfigSettingsAsync(configSettings)

        remoteConfig.setDefaultsAsync(
            mapOf("deleteImage_url" to "https://cloudinaryserver.onrender.com/delete")
        )

        remoteConfig.fetchAndActivate()
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    deleteImage_url = remoteConfig.getString("deleteImage_url")
                }
            }

        db.collection("users")
            .addSnapshotListener { snapshots, e ->
                if (e != null) {
                    Log.w("AllUsersFragment", "Listen failed.", e)
                    return@addSnapshotListener
                }
                if (snapshots == null) return@addSnapshotListener

                val list = snapshots.documents.map { doc ->
                    val id = doc.id
                    // NEW fields (events)
                    val title = doc.getString("title") ?: "No title"
                    val eventType = doc.getString("event_type") ?: "Other"
                    val descriptions = (doc.get("descriptions") as? Map<*, *>)?.mapNotNull { entry ->
                        val k = entry.key as? String
                        val v = entry.value as? String
                        if (k != null && v != null) k to v else null
                    }?.toMap() ?: emptyMap()

                    val date = doc.getString("date") ?: ""
                    val time = doc.getString("time") ?: ""
                    val duration = doc.getLong("duration_minutes")?.toInt()
                    val location = doc.getString("location")
                    val isUpcoming = doc.getBoolean("is_upcoming") ?: true

                    val images = (doc.get("images") as? List<*>)?.mapNotNull { item ->
                        val map = item as? Map<*, *>
                        val url = map?.get("url") as? String
                        val publicId = map?.get("public_id") as? String
                        if (url != null && publicId != null) {
                            UserImage(url, publicId)
                        } else null
                    } ?: emptyList()

                    User(
                        id = id,
                        title = title,
                        eventType = eventType,
                        descriptions = descriptions,
                        date = date,
                        time = time,
                        durationMinutes = duration,
                        images = images,
                        location = location,
                        isUpcoming = isUpcoming
                    )
                }
                adapter.setItems(list)
            }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    // delete helper
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
