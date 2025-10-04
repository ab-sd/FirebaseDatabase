package com.example.basicfiredatabase.fragments

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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
        private const val ARG_SHOW_UPCOMING = "show_upcoming"
        fun newInstance(showUpcoming: Boolean) = AllUsersFragment().apply {
            arguments = Bundle().apply { putBoolean(ARG_SHOW_UPCOMING, showUpcoming) }
        }
    }

    private val db = Firebase.firestore
    private lateinit var rv: RecyclerView

    private val showUpcomingFilter: Boolean by lazy { arguments?.getBoolean(ARG_SHOW_UPCOMING) ?: true }

    // Firebase remote config values (populated later)
    private var deleteImage_url: String? = null
    private var imageCloudApiKey: String? = null

    private val adapter = UserAdapter(
        onEdit = { user ->
            openEditFragment(user)
        },
        onDelete = { user ->
            confirmAndDeleteUser(user)
        }
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        rv = view.findViewById(R.id.rv_users)

        setupRecyclerView()
        applyWindowInsetsToRecyclerView()
        setupRemoteConfig()
        startUsersListener()
    }

    // ---------- Setup helpers ----------

    private fun setupRecyclerView() {
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter
    }

    private fun applyWindowInsetsToRecyclerView() {
        ViewCompat.setOnApplyWindowInsetsListener(rv) { v, insets ->
            val navBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, navBottom + dpToPx(8))
            insets
        }
    }

    private fun setupRemoteConfig() {
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
    }

    private fun startUsersListener() {
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

                        val includeMap = doc.getBoolean("include_map_link") ?: false
                        val mapLink = doc.getString("map_link")?.takeIf { it.isNotBlank() }

                        val isComplete = doc.getBoolean("is_complete") ?: false

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
                            includeMapLink = includeMap,
                            mapLink = mapLink,
                            isComplete = isComplete
                        )
                    } catch (ex: Exception) {
                        null
                    }
                }

                // Filter by upcoming or completed
                val filtered = if (showUpcomingFilter) {
                    list.filter { !it.isComplete }   // upcoming = not complete
                } else {
                    list.filter { it.isComplete }    // show completed events
                }

                // Sort (upcoming earliest->latest, completed latest->oldest)
                val sorted = if (showUpcomingFilter) {
                    filtered.sortedBy { parseDateTimeOrEpoch(it.date, it.time) }
                } else {
                    filtered.sortedByDescending { parseDateTimeOrEpoch(it.date, it.time) }
                }

                adapter.setItems(sorted)
            }
    }

    // ---------- Action handlers ----------

    private fun openEditFragment(user: User) {
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
                putBoolean("is_complete", user.isComplete)

                putBoolean("include_map_link", user.includeMapLink)
                putString("map_link", user.mapLink)
            }
        }

        requireActivity().supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .addToBackStack(null)
            .commit()
    }

    private fun confirmAndDeleteUser(user: User) {
        AlertDialog.Builder(requireContext())
            .setTitle("Delete Event")
            .setMessage("Are you sure you want to delete \"${user.title}\"?")
            .setPositiveButton("Yes") { _, _ ->
                val progressDialog = createProgressDialog()
                progressDialog.show()

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

    private fun createProgressDialog(): AlertDialog {
        return AlertDialog.Builder(requireContext())
            .setView(R.layout.dialog_progress)
            .setCancelable(false)
            .create()
    }

    // ---------- Utility methods (kept unchanged) ----------

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

    // delete helper (unchanged)
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
