package com.example.basicfiredatabase.fragments

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.example.basicfiredatabase.R
import com.example.basicfiredatabase.adapters.GalleryAdapter
import com.example.basicfiredatabase.models.GalleryItem
import com.example.basicfiredatabase.databinding.FragmentGalleryBinding
import com.example.basicfiredatabase.utils.NetworkUtils.isNetworkAvailable
import com.google.firebase.firestore.Query
import com.google.firebase.ktx.Firebase
import com.google.firebase.firestore.ktx.firestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GalleryFragment : Fragment(R.layout.fragment_gallery) {

    companion object {
        private const val TAG = "GalleryFragment"
    }


    private var _binding: FragmentGalleryBinding? = null
    private val binding get() = _binding!!

    private val db = Firebase.firestore
    private lateinit var adapter: GalleryAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentGalleryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecycler()
        setupSwipeRefresh()

        binding.srlGallery.isRefreshing = true
        loadPastEventsImages(onComplete = { if (isAdded) binding.srlGallery.isRefreshing = false })

    }


    private fun setupRecycler() {
        adapter = GalleryAdapter { tappedUrl, eventId ->
            // collect all image URLs belonging to the same eventId in the current list
            val eventImages = adapter.currentList
                .filterIsInstance<GalleryItem.Image>()
                .filter { it.eventId == eventId }
                .map { it.imageUrl }

            // find start index of tapped image
            val startIndex = eventImages.indexOf(tappedUrl).coerceAtLeast(0)

            val frag = ImageViewerFragment.newInstance(eventImages, startIndex)
            requireActivity().supportFragmentManager.beginTransaction()
                .add(R.id.fragment_container, frag)
                .addToBackStack(null)
                .commit()
        }

        val spanCount = 3
        val glm = GridLayoutManager(requireContext(), spanCount)
        glm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                return if (adapter.getItemViewType(position) == 0) spanCount else 1
            }
        }

        binding.rvGallery.setHasFixedSize(true)
        binding.rvGallery.layoutManager = glm
        binding.rvGallery.adapter = adapter
    }


    private fun setupSwipeRefresh() {
        binding.srlGallery.setColorSchemeResources(
            R.color.teal_200,
            android.R.color.holo_blue_light,
            android.R.color.holo_orange_light
        )

        binding.srlGallery.setOnRefreshListener {
            if (!isNetworkAvailable(requireContext())) {
                binding.srlGallery.isRefreshing = false
                Toast.makeText(requireContext(), "No internet connection", Toast.LENGTH_SHORT).show()
            } else {
                // show spinner (user initiated) and load, stopping spinner in onComplete
                binding.srlGallery.isRefreshing = true
                loadPastEventsImages(onComplete = { if (isAdded) binding.srlGallery.isRefreshing = false })
            }
        }
    }


    /**
     * Load past events (is_complete == true), try server-side ordering first.
     * onComplete is invoked when the load finishes (success or failure) so caller can hide spinner.
     */
    private fun loadPastEventsImages(onComplete: (() -> Unit)? = null) {
        val fieldName = "is_complete"
        val colRef = db.collection("users")
            .whereEqualTo(fieldName, true)
            .orderBy("date", Query.Direction.DESCENDING)
            .orderBy("time", Query.Direction.DESCENDING)

        Log.d(TAG, "Loading past events: server-ordered query on $fieldName = false, ordered by date/time desc")

        colRef.get()
            .addOnSuccessListener { snaps ->
                // offload doc->items mapping to background thread to avoid UI jank
                lifecycleScope.launch {
                    val docs = snaps.documents
                    val galleryItems = withContext(Dispatchers.Default) { buildGalleryItemsFromDocs(docs) }
                    Log.d(TAG, "Server-ordered query succeeded. docs returned: ${docs.size}; gallery items: ${galleryItems.size}")

                    if (galleryItems.isEmpty()) {
                        binding.rvGallery.visibility = View.GONE
                        Toast.makeText(requireContext(), "No gallery images found", Toast.LENGTH_SHORT).show()
                    } else {
                        binding.rvGallery.visibility = View.VISIBLE
                        adapter.submitList(galleryItems)
                    }

                    onComplete?.invoke()
                }
            }
            .addOnFailureListener { e ->
                // Log full error + stacktrace so you can inspect index / permission errors in Logcat
                Log.w(TAG, "Server-ordered query failed.", e)
                val msg = e?.message
                Log.w(TAG, "Failure message: $msg")

                // If Firestore requires a composite index it often includes a console URL in the message.
                val url = msg?.let { """https?://\S+""".toRegex().find(it)?.value }
                if (!url.isNullOrBlank()) {
                    Log.w(TAG, "Possible Firestore index URL: $url")

                }

                // Inform user/dev
                Toast.makeText(requireContext(), "Server query failed. Falling back to client-side fetch.", Toast.LENGTH_LONG).show()

                // important: always call onComplete so any refresh spinner is stopped
                onComplete?.invoke()

                // fallback to client-side fetch+sort
                fetchAndSortClientSide(onComplete)
            }
    }

    /** Fallback: fetch whereEqualTo only, then sort client-side (newest first) */
    private fun fetchAndSortClientSide(onComplete: (() -> Unit)? = null) {
        val fieldName = "is_complete"
        Log.d(TAG, "FALLBACK: fetching docs with $fieldName = false and sorting client-side")

        db.collection("users")
            .whereEqualTo(fieldName, true)
            .get()
            .addOnSuccessListener { snaps ->
                lifecycleScope.launch {
                    val docs = snaps.documents
                    val galleryItems = withContext(Dispatchers.Default) { buildGalleryItemsFromDocs(docs) }
                    val ordered = orderGalleryItemsByEventDateDesc(galleryItems)
                    Log.d(TAG, "Fallback gallery items built (after sort): ${ordered.size}")

                    if (ordered.isEmpty()) {
                        binding.rvGallery.visibility = View.GONE
                        Toast.makeText(requireContext(), "No gallery images found", Toast.LENGTH_SHORT).show()
                    } else {
                        binding.rvGallery.visibility = View.VISIBLE
                        adapter.submitList(ordered)
                    }
                    onComplete?.invoke()
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Fallback query failed", e)
                Toast.makeText(requireContext(), "Failed loading gallery: ${e?.localizedMessage}", Toast.LENGTH_LONG).show()
                onComplete?.invoke()
            }
    }

    /** Convert Firestore docs into a flat list of GalleryItem (Header + Image entries) */
    private fun buildGalleryItemsFromDocs(docs: List<com.google.firebase.firestore.DocumentSnapshot>): MutableList<GalleryItem> {
        val galleryItems = mutableListOf<GalleryItem>()

        for (doc in docs) {
            val id = doc.id
            val title = doc.getString("title") ?: "No title"
            val date = doc.getString("date") ?: ""

            val rawImages = doc.get("images")
            val images = mutableListOf<String>()

            when (rawImages) {
                is List<*> -> {
                    for (entry in rawImages) {
                        when (entry) {
                            is Map<*, *> -> {
                                val u = (entry["url"] ?: entry["imageUrl"] ?: entry["uri"])
                                if (u is String && u.isNotBlank()) images.add(u)
                            }
                            is String -> {
                                if (entry.isNotBlank()) images.add(entry)
                            }
                            else -> {
                                Log.d(TAG, "doc $id entry has unexpected image entry type: ${entry?.javaClass}")
                            }
                        }
                    }
                }
                is String -> {
                    if (rawImages.isNotBlank()) images.add(rawImages)
                }
                null -> {
                    Log.d(TAG, "doc $id has no images field")
                }
                else -> {
                    Log.d(TAG, "doc $id images field unexpected type: ${rawImages.javaClass}")
                }
            }

            if (images.isNotEmpty()) {
                galleryItems.add(GalleryItem.Header(id, title, date))
                for (url in images) galleryItems.add(GalleryItem.Image(id, url))
            } else {
                Log.d(TAG, "doc $id has no valid image urls, skipping")
            }
        }

        return galleryItems
    }

    /** Order gallery items so newest events come first (group by event, sort by header date desc) */
    private fun orderGalleryItemsByEventDateDesc(items: List<GalleryItem>): List<GalleryItem> {
        data class Group(val header: GalleryItem.Header, val images: MutableList<GalleryItem.Image> = mutableListOf())

        val groups = linkedMapOf<String, Group>()

        for (it in items) {
            when (it) {
                is GalleryItem.Header -> groups[it.eventId] = Group(it)
                is GalleryItem.Image -> {
                    val gid = it.eventId
                    val g = groups[gid] ?: run {
                        val fakeHeader = GalleryItem.Header(gid, "No title", "")
                        val newG = Group(fakeHeader)
                        groups[gid] = newG
                        newG
                    }
                    g.images.add(it)
                }
            }
        }

        val sortedGroups = groups.values.sortedWith { a, b ->
            val aDate = parseDateTimeForSort(a.header.date)
            val bDate = parseDateTimeForSort(b.header.date)
            when {
                aDate == bDate -> 0
                aDate < bDate -> 1
                else -> -1
            }
        }

        val out = mutableListOf<GalleryItem>()
        for (g in sortedGroups) {
            out.add(g.header)
            out.addAll(g.images)
        }
        return out
    }

    private fun parseDateTimeForSort(dateStr: String, timeStr: String = "00:00"): Long {
        return try {
            val combined = "${if (dateStr.isBlank()) "1970-01-01" else dateStr} $timeStr"
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
            sdf.parse(combined)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }


    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
