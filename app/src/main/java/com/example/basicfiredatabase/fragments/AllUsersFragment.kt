package com.example.basicfiredatabase.fragments

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.example.basicfiredatabase.R
import com.example.basicfiredatabase.adapters.UserAdapter
import com.example.basicfiredatabase.models.User
import com.example.basicfiredatabase.models.UserImage
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.Query
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

    private val TAG = "AllUsersFragment"
    private val db = Firebase.firestore
    private lateinit var rv: RecyclerView
    private lateinit var srl: SwipeRefreshLayout
    private lateinit var tvEmpty: TextView


    private val showUpcomingFilter: Boolean by lazy { arguments?.getBoolean(ARG_SHOW_UPCOMING) ?: true }

    // Firebase remote config values (populated later)
    private var deleteImage_url: String? = null
    private var imageCloudApiKey: String? = null

    private val adapter = UserAdapter(
        onEdit = { user -> openEditFragment(user) },
        onDelete = { user -> confirmAndDeleteUser(user) }
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        rv = view.findViewById(R.id.rv_users)
        srl = view.findViewById(R.id.srl_users)
        tvEmpty = view.findViewById(R.id.tv_empty)


        setupRecyclerView()
        applyWindowInsetsToRecyclerView()
        setupRemoteConfig()

        // Add swipe-to-refresh
        setupSwipeRefresh()

        // Start realtime listener (keeps UI up-to-date)
        startUsersListener()

        // initial one-shot load (shows spinner on open); spinner will be stopped in onComplete
        if (!isNetworkAvailable()) {
            // show cached data (if any) but inform the user we're offline and stop spinner
            srl.isRefreshing = false
            Toast.makeText(requireContext(), "No internet connection", Toast.LENGTH_SHORT).show()
            // still attempt a one-shot fetch to surface cached results (optional)
            fetchUsersOnce(onComplete = { if (isAdded) srl.isRefreshing = false })
        } else {
            // online: show spinner and fetch
            srl.isRefreshing = true
            fetchUsersOnce(onComplete = { if (isAdded) srl.isRefreshing = false })
        }

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
        val configSettings = remoteConfigSettings { minimumFetchIntervalInSeconds = 3600 }
        remoteConfig.setConfigSettingsAsync(configSettings)

        remoteConfig.fetchAndActivate()
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    deleteImage_url = remoteConfig.getString("deleteImage_url")
                    imageCloudApiKey = remoteConfig.getString("cloud_api_key")
                }
            }
    }

    private fun showEmptyState(items: List<User>) {
        if (items.isEmpty()) {
            val message = if (showUpcomingFilter) "No upcoming events" else "No past events"
            tvEmpty.text = message
            tvEmpty.visibility = View.VISIBLE
            rv.visibility = View.GONE
        } else {
            tvEmpty.visibility = View.GONE
            rv.visibility = View.VISIBLE
        }
    }


    /**
     * Real-time listener that updates UI whenever Firestore changes.
     * Refactored to reuse buildUsersFromDocs for consistent mapping logic.
     */
    private fun startUsersListener() {
        db.collection("users")
            .addSnapshotListener { snapshots, e ->
                if (e != null) {
                    Log.w(TAG, "Listen failed.", e)
                    return@addSnapshotListener
                }
                if (snapshots == null) return@addSnapshotListener

                lifecycleScope.launch {
                    val docs = snapshots.documents
                    val users = withContext(Dispatchers.Default) { buildUsersFromDocs(docs) }

                    // Filter + sort exactly as before
                    val filtered = if (showUpcomingFilter) users.filter { !it.isComplete } else users.filter { it.isComplete }
                    val sorted = if (showUpcomingFilter) filtered.sortedBy { parseDateTimeOrEpoch(it.date, it.time) } else filtered.sortedByDescending { parseDateTimeOrEpoch(it.date, it.time) }

                    adapter.setItems(sorted)
                    showEmptyState(sorted)

                }
            }
    }

    // ---------- One-shot fetch for initial load and swipe-to-refresh ----------

    /**
     * Fetch documents one-time (used for initial spinner load and manual refresh).
     * onComplete is invoked in all cases (success/failure) so caller can hide spinner.
     */
    private fun fetchUsersOnce(onComplete: (() -> Unit)? = null) {
        Log.d(TAG, "Performing one-shot fetchUsersOnce()")
        db.collection("users")
            .get()
            .addOnSuccessListener { snaps ->
                lifecycleScope.launch {
                    val docs = snaps.documents
                    val users = withContext(Dispatchers.Default) { buildUsersFromDocs(docs) }

                    val filtered = if (showUpcomingFilter) users.filter { !it.isComplete } else users.filter { it.isComplete }
                    val sorted = if (showUpcomingFilter) filtered.sortedBy { parseDateTimeOrEpoch(it.date, it.time) } else filtered.sortedByDescending { parseDateTimeOrEpoch(it.date, it.time) }

                    adapter.setItems(sorted)
                    showEmptyState(sorted)
                    onComplete?.invoke()
                }
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "fetchUsersOnce failed.", e)
                Toast.makeText(requireContext(), "Refresh failed. ${e.message}", Toast.LENGTH_LONG).show()
                onComplete?.invoke()
            }
    }

    private fun setupSwipeRefresh() {
        srl.setColorSchemeResources(
            R.color.teal_200,
            android.R.color.holo_blue_light,
            android.R.color.holo_orange_light
        )

        srl.setOnRefreshListener {
            if (!isNetworkAvailable()) {
                srl.isRefreshing = false
                Toast.makeText(requireContext(), "No internet connection", Toast.LENGTH_SHORT).show()
            } else {
                // show spinner is already true here (SwipeRefreshLayout handles it)
                fetchUsersOnce(onComplete = { if (isAdded) srl.isRefreshing = false })
            }
        }
    }

    // ---------- Shared doc->User mapping (extracted to reuse in both paths) ----------

    private fun buildUsersFromDocs(docs: List<DocumentSnapshot>): List<User> {
        return docs.mapNotNull { doc ->
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
    }

    // ---------- Action handlers (unchanged) ----------

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
                            Log.w(TAG, "Error deleting", e)
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

    // ---------- Utility methods ----------

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
                        .addHeader("x-api-key", imageCloudApiKey!!)
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

    /** Network availability helper (copied from your other fragment) */
    private fun isNetworkAvailable(): Boolean {
        val cm = requireContext().getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val nw = cm.activeNetwork ?: return false
            val actNw = cm.getNetworkCapabilities(nw) ?: return false
            return when {
                actNw.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> true
                actNw.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> true
                actNw.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> true
                actNw.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> true
                else -> false
            }
        } else {
            @Suppress("DEPRECATION")
            val nwInfo = cm.activeNetworkInfo ?: return false
            @Suppress("DEPRECATION")
            return nwInfo.isConnected
        }
    }
}
