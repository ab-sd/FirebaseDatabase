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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AllUsersFragment : Fragment(R.layout.fragment_all_users) {

    companion object {
        private const val ARG_IS_UPCOMING = "is_upcoming"
        fun newInstance(isUpcoming: Boolean) = AllUsersFragment().apply {
            arguments = Bundle().apply { putBoolean(ARG_IS_UPCOMING, isUpcoming) }
        }
    }

    private val db = Firebase.firestore
    private lateinit var rv: RecyclerView

    private val isUpcomingFilter: Boolean by lazy { arguments?.getBoolean(ARG_IS_UPCOMING) ?: true }


    //Firebase remote config
    private var deleteImage_url: String? = null
    private var imageCloudApiKey: String? = null


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

                requireActivity().supportFragmentManager.beginTransaction()
                    .replace(R.id.fragment_container, fragment)
                    .addToBackStack(null)
                    .commit()


        },
        onDelete = { user ->

            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("Delete Event")
                .setMessage("Are you sure you want to delete \"${user.title}\"?")
                .setPositiveButton("Yes") { _, _ ->
                    // Step 2: Show progress bar
                    val progressDialog = androidx.appcompat.app.AlertDialog.Builder(requireContext())
                        .setView(R.layout.dialog_progress) // custom layout with ProgressBar
                        .setCancelable(false)
                        .create()
                    progressDialog.show()

                    // Step 3: Run deletion
                    lifecycleScope.launch {
                        val success = deleteImagesFromCloudinary(user.images)
                        if (!success) {
                            Toast.makeText(requireContext(), "Some images could not be deleted", Toast.LENGTH_LONG).show()
                        }

                        db.collection("users").document(user.id)
                            .delete()
                            .addOnSuccessListener {
                                progressDialog.dismiss()
                                Toast.makeText(requireContext(), "Deleted ${user.title}", Toast.LENGTH_SHORT).show()
                            }
                            .addOnFailureListener { e ->
                                progressDialog.dismiss()
                                Log.w("AllUsersFragment", "Error deleting", e)
                                Toast.makeText(requireContext(), "Delete failed", Toast.LENGTH_SHORT).show()
                            }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
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


        remoteConfig.fetchAndActivate()
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    deleteImage_url = remoteConfig.getString("deleteImage_url")
                    imageCloudApiKey = remoteConfig.getString("cloud_api_key")
                }
            }

        db.collection("users")
            .addSnapshotListener { snapshots, e ->
                if (e != null) {
                    Log.w("AllUsersFragment", "Listen failed.", e)
                    return@addSnapshotListener
                }
                if (snapshots == null) return@addSnapshotListener

                val list = snapshots.documents.mapNotNull { doc ->
                    try {
                        val id = doc.id
                        // NEW fields (events)
                        val title = doc.getString("title") ?: "No title"
                        val eventType = doc.getString("event_type") ?: "Other"
                        val descriptions =
                            (doc.get("descriptions") as? Map<*, *>)?.mapNotNull { entry ->
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
                    } catch (ex: Exception) {
                        null
                    }
                }

                // filter by the fragment's isUpcoming flag
                val filtered = list.filter { it.isUpcoming == isUpcomingFilter }

                // sort: upcoming -> earliest to latest, past -> latest to oldest
                val sorted = if (isUpcomingFilter) {
                    filtered.sortedBy { parseDateTimeOrEpoch(it.date, it.time) }
                } else {
                    filtered.sortedByDescending { parseDateTimeOrEpoch(it.date, it.time) }
                }

                adapter.setItems(sorted)
            }
    }


        private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }


    // parse yyyy-MM-dd + HH:mm into Date
    private fun parseDateTimeOrEpoch(dateStr: String, timeStr: String): Date {
        val date = if (dateStr.isBlank()) "1970-01-01" else dateStr
        val time = if (timeStr.isBlank()) "00:00" else timeStr
        val combined = "$date $time"
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            sdf.parse(combined) ?: Date(0)
        } catch (e: Exception) {
            Date(0)
        }
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
                        .addHeader("x-api-key", imageCloudApiKey!!) // match your Render env var
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
