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
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.example.basicfiredatabase.R
import com.example.basicfiredatabase.adapters.UserAdapter
import com.example.basicfiredatabase.databinding.FragmentAllUsersBinding
import com.example.basicfiredatabase.models.User
import com.example.basicfiredatabase.models.UserImage
import com.example.basicfiredatabase.utils.NetworkUtils.isNetworkAvailable
import com.example.basicfiredatabase.viewmodels.EventsViewModel
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.google.firebase.remoteconfig.ktx.remoteConfig
import com.google.firebase.remoteconfig.ktx.remoteConfigSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resumeWithException

class AllUsersFragment : Fragment(R.layout.fragment_all_users) {

    companion object {
        private const val ARG_SHOW_UPCOMING = "show_upcoming"
        fun newInstance(showUpcoming: Boolean) = AllUsersFragment().apply {
            arguments = Bundle().apply { putBoolean(ARG_SHOW_UPCOMING, showUpcoming) }
        }
    }

    private var fetchJob: kotlinx.coroutines.Job? = null

    private var _binding: FragmentAllUsersBinding? = null
    private val binding get() = _binding!!


    private val TAG = "AllUsersFragment"
    private val db = Firebase.firestore


    // Shared ViewModel (scoped to activity so both pages share it)
    private val eventsViewModel: EventsViewModel by activityViewModels()

    // fragmentId used to identify which fragment initiated the reload ("upcoming" or "completed")
    private val fragmentId: String by lazy { if (showUpcomingFilter) "upcoming" else "completed" }

    // throttle offline toast so it doesn't spam
    private var lastOfflineToastAt: Long = 0L
    private val OFFLINE_TOAST_THROTTLE_MS = 5_000L // 5 seconds


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


        _binding = FragmentAllUsersBinding.bind(view)



        setupRecyclerView()
        applyWindowInsetsToRecyclerView()
        setupRemoteConfig()

        // Add swipe-to-refresh
        setupSwipeRefresh()


        // observe shared reload trigger — reload only when origin != this fragment
        eventsViewModel.reloadTrigger.observe(viewLifecycleOwner) { pair ->
            val (originId, _) = pair
            if (originId != fragmentId) {
                val b = _binding ?: return@observe
                // Show swipe refresh spinner while loading
                b.srlUsers.isRefreshing = true

                // onComplete uses a safe call, not isAdded
                fetchUsersOnce(onComplete = { _binding?.srlUsers?.isRefreshing = false })
            }
        }


        // Start realtime listener (keeps UI up-to-date)
//        startUsersListener()

        // initial one-shot load (shows spinner on open); spinner will be stopped in onComplete
        val hasNetwork = isNetworkAvailable(requireContext())
        // disable swipe refresh if offline to prevent user from attempting to refresh while offline
        binding.srlUsers.isEnabled = true

        if (!hasNetwork) {
            // show cached data (if any) but inform the user we're offline
            binding.srlUsers.isRefreshing = false
            showOfflineToastIfNeeded()
            // still attempt a one-shot fetch to surface cached results (optional)
            fetchUsersOnce(onComplete = { if (isAdded) binding.srlUsers.isRefreshing = false })
        } else {
            // online: show spinner and fetch
            binding.srlUsers.isRefreshing = true
            fetchUsersOnce(onComplete = { if (isAdded) binding.srlUsers.isRefreshing = false })
        }

        setupHeader()


    }

    private fun setupHeader() {
        // Controls: card_header, tv_header_title, tv_header_desc, btn_header_cta
        val card = binding.cardHeader
        val title = binding.tvHeaderTitle
        val desc = binding.tvHeaderDesc
        val cta = binding.btnHeaderCta

        if (showUpcomingFilter) {
            // Example upcoming header text
            title.text = "Upcoming events"
            desc.text = "Here are events scheduled soon. Tap a card to see details."
            cta.visibility = View.GONE  // no CTA for upcoming
            card.visibility = View.VISIBLE
        } else {
            // Past events header with CTA to open full gallery
            title.text = "Past events"
            desc.text = "Tap 'View images' on an event to see images for that event, or open the full gallery below."
            cta.visibility = View.VISIBLE
            card.visibility = View.VISIBLE

            cta.setOnClickListener {
                // Open full gallery (unfiltered)
                val frag = com.example.basicfiredatabase.fragments.GalleryFragment.newInstance(null)
                requireActivity().supportFragmentManager.beginTransaction()
                    .replace(R.id.fragment_container, frag)
                    .addToBackStack("gallery_all_past")
                    .commit()
            }
        }
    }

    // ---------- Setup helpers ----------

    private fun setupRecyclerView() {
        binding.rvUsers.layoutManager = LinearLayoutManager(requireContext())
        binding.rvUsers.adapter = adapter
    }

    override fun onDestroyView() {
        super.onDestroyView()
        fetchJob?.cancel()            // cancel pending fetch so it doesn't try to touch UI later
        fetchJob = null

        _binding = null
    }

    private fun applyWindowInsetsToRecyclerView() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.rvUsers) { v, insets ->
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

                // bail if fragment has been detached
                if (!isAdded) return@addOnCompleteListener

                if (task.isSuccessful) {
                    deleteImage_url = remoteConfig.getString("deleteImage_url")
                    imageCloudApiKey = remoteConfig.getString("cloud_api_key")
                }
            }
    }

    private fun showEmptyState(items: List<User>) {
        val b = _binding ?: return         // return early if view destroyed
        if (items.isEmpty()) {
            val message = if (showUpcomingFilter) "No upcoming events" else "No past events"
            b.tvEmpty.text = message
            b.tvEmpty.visibility = View.VISIBLE
            b.rvUsers.visibility = View.GONE
        } else {
            b.tvEmpty.visibility = View.GONE
            b.rvUsers.visibility = View.VISIBLE
        }
    }



//    /**
//     * Real-time listener that updates UI whenever Firestore changes.
//     * Refactored to reuse buildUsersFromDocs for consistent mapping logic.
//     */
//    private fun startUsersListener() {
//        db.collection("users")
//            .addSnapshotListener { snapshots, e ->
//                if (e != null) {
//                    Log.w(TAG, "Listen failed.", e)
//                    return@addSnapshotListener
//                }
//                if (snapshots == null) return@addSnapshotListener
//
//                lifecycleScope.launch {
//                    val docs = snapshots.documents
//                    val users = withContext(Dispatchers.Default) { buildUsersFromDocs(docs) }
//
//                    // Filter + sort exactly as before
//                    val filtered = if (showUpcomingFilter) users.filter { !it.isComplete } else users.filter { it.isComplete }
//                    val sorted = if (showUpcomingFilter) filtered.sortedBy { parseDateTimeOrEpoch(it.date, it.time) } else filtered.sortedByDescending { parseDateTimeOrEpoch(it.date, it.time) }
//
//                    adapter.setItems(sorted)
//                    showEmptyState(sorted)
//
//                }
//            }
//    }

    // ---------- One-shot fetch for initial load and swipe-to-refresh ----------

    /**
     * Fetch documents one-time (used for initial spinner load and manual refresh).
     * onComplete is invoked in all cases (success/failure) so caller can hide spinner.
     */

    // call this from onViewCreated or from swipe refresh etc.
    private fun fetchUsersOnce(onComplete: (() -> Unit)? = null) {
        // cancel previous if running
        fetchJob?.cancel()

        // capture view scope early while view exists
        val viewScope = viewLifecycleOwner.lifecycleScope

        // start Task
        val task = db.collection("users").get()
        task.addOnSuccessListener { snaps ->
            // launch work inside captured viewScope -> it will be cancelled when view is destroyed
            fetchJob = viewScope.launch {
                // heavy work off main thread
                val docs = snaps.documents
                val users = withContext(Dispatchers.Default) { buildUsersFromDocs(docs) }

                val filtered = if (showUpcomingFilter) users.filter { !it.isComplete } else users.filter { it.isComplete }
                val sorted = if (showUpcomingFilter) filtered.sortedBy { parseDateTimeOrEpoch(it.date, it.time) } else filtered.sortedByDescending { parseDateTimeOrEpoch(it.date, it.time) }

                // final UI update on Main with binding guard
                withContext(Dispatchers.Main) {
                    val b = _binding ?: run {
                        onComplete?.invoke()
                        return@withContext
                    }
                    adapter.setItems(sorted)
                    showEmptyState(sorted)   // make sure showEmptyState itself uses _binding guard
                    onComplete?.invoke()
                }
            }
        }
        task.addOnFailureListener { e ->
            // use captured scope to report failure on main safely
            fetchJob = viewScope.launch(Dispatchers.Main) {
                if (_binding != null && isAdded) {
                    Toast.makeText(requireContext(), "Refresh failed. ${e.message}", Toast.LENGTH_LONG).show()
                }
                onComplete?.invoke()
            }
        }
    }




    private fun showOfflineToastIfNeeded() {
        if (!isAdded) return
        val now = System.currentTimeMillis()
        if (now - lastOfflineToastAt >= OFFLINE_TOAST_THROTTLE_MS) {
            lastOfflineToastAt = now
            // short toast to reduce time on screen and reduce annoyance
            Toast.makeText(requireContext(), "No internet connection", Toast.LENGTH_SHORT).show()
        }
    }


    private fun setupSwipeRefresh() {
        val b = _binding ?: return
        b.srlUsers.setColorSchemeResources(
            R.color.teal_200,
            android.R.color.holo_blue_light,
            android.R.color.holo_orange_light
        )

        b.srlUsers.setOnRefreshListener {
            if (!isNetworkAvailable(requireContext())) {
                b.srlUsers.isRefreshing = false
                showOfflineToastIfNeeded()
            } else {
                fetchUsersOnce(onComplete = { _binding?.srlUsers?.isRefreshing = false })
                eventsViewModel.triggerReload(fragmentId)
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
                val progressDialog = createProgressDialog() ?: return@setPositiveButton
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

    private fun createProgressDialog(): AlertDialog? {
        val ctx = context ?: return null
        return AlertDialog.Builder(ctx)
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
        val url = deleteImage_url ?: return false
        val key = imageCloudApiKey ?: return false

        return withContext(Dispatchers.IO) {
            try {
                val client = OkHttpClient()
                for (image in images) {
                    val body = JSONObject().put("public_id", image.public_id).toString()
                        .toRequestBody("application/json".toMediaTypeOrNull())

                    val request = Request.Builder()
                        .url(url)
                        .post(body)
                        .addHeader("x-api-key", key)
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

