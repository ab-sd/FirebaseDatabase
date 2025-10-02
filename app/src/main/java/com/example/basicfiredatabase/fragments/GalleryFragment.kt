package com.example.basicfiredatabase.fragments

import android.content.ContentValues.TAG
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import com.example.basicfiredatabase.R
import com.example.basicfiredatabase.adapters.GalleryAdapter
import com.example.basicfiredatabase.models.GalleryItem
import com.example.basicfiredatabase.databinding.FragmentGalleryBinding
import com.google.firebase.firestore.Query
import com.google.firebase.ktx.Firebase
import com.google.firebase.firestore.ktx.firestore

class GalleryFragment : Fragment(R.layout.fragment_gallery) {

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

        adapter = GalleryAdapter { imageUrl, _ ->
            val frag = ImageViewerFragment.newInstance(listOf(imageUrl), 0)
            requireActivity().supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, frag)
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

        loadPastEventsImages()
    }

    private fun loadPastEventsImages() {
        val fieldName = "is_upcoming"
        // Try server-side ordering by date then time (newest first)
        // Note: if Firestore requires a composite index you'll get an error and we fallback.
        val colRef = db.collection("users")
            .whereEqualTo(fieldName, false)
            .orderBy("date", Query.Direction.DESCENDING)
            .orderBy("time", Query.Direction.DESCENDING)

        android.util.Log.d(TAG, "Loading past events: server-ordered query on $fieldName = false, ordered by date/time desc")

        colRef.get()
            .addOnSuccessListener { snaps ->
                android.util.Log.d(TAG, "Server-ordered query succeeded. docs returned: ${snaps.size()}")
                val galleryItems = buildGalleryItemsFromDocs(snaps.documents)
                android.util.Log.d(TAG, "gallery items built: ${galleryItems.size}")

                if (galleryItems.isEmpty()) {
                    // No items — show empty state
                    binding.rvGallery.visibility = View.GONE
                    Toast.makeText(requireContext(), "No gallery images found", Toast.LENGTH_SHORT).show()
                } else {
                    binding.rvGallery.visibility = View.VISIBLE
                    adapter.submitList(galleryItems)
                }
            }
            .addOnFailureListener { e ->
                // If Firestore asks for an index, the message usually contains 'index' or a link.
                android.util.Log.w(TAG, "Server-ordered query failed, falling back to client-side sorting. Error: ${e.message}", e)
                // fallback: fetch filtered docs (no order) then sort client-side
                fetchAndSortClientSide()
            }
    }

    /** Fallback path: fetch whereEqualTo only, then do client-side sort (newest first) */
    private fun fetchAndSortClientSide() {
        val fieldName = "is_upcoming"
        android.util.Log.d(TAG, "FALLBACK: fetching docs with $fieldName = false and sorting client-side")

        db.collection("users")
            .whereEqualTo(fieldName, false)
            .get()
            .addOnSuccessListener { snaps ->
                android.util.Log.d(TAG, "Fallback query returned ${snaps.size()} docs")
                val galleryItems = buildGalleryItemsFromDocs(snaps.documents)
                // client-side sort: we already place headers/images in the same order as source docs,
                // so we must re-order by their event datetime. We'll group by event and sort groups.
                val ordered = orderGalleryItemsByEventDateDesc(galleryItems)
                android.util.Log.d(TAG, "Fallback gallery items built (after sort): ${ordered.size}")

                if (ordered.isEmpty()) {
                    binding.rvGallery.visibility = View.GONE
                    Toast.makeText(requireContext(), "No gallery images found", Toast.LENGTH_SHORT).show()
                } else {
                    binding.rvGallery.visibility = View.VISIBLE
                    adapter.submitList(ordered)
                }
            }
            .addOnFailureListener { e ->
                android.util.Log.e(TAG, "Fallback query failed", e)
                Toast.makeText(requireContext(), "Failed loading gallery: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
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
                                android.util.Log.d(TAG, "doc $id entry has unexpected image entry type: ${entry?.javaClass}")
                            }
                        }
                    }
                }
                is String -> {
                    if (rawImages.isNotBlank()) images.add(rawImages)
                }
                null -> {
                    android.util.Log.d(TAG, "doc $id has no images field")
                }
                else -> {
                    android.util.Log.d(TAG, "doc $id images field unexpected type: ${rawImages.javaClass}")
                }
            }

            if (images.isNotEmpty()) {
                galleryItems.add(GalleryItem.Header(id, title, date))
                for (url in images) galleryItems.add(GalleryItem.Image(id, url))
            } else {
                android.util.Log.d(TAG, "doc $id has no valid image urls, skipping")
            }
        }

        return galleryItems
    }

    /** Order gallery items so events with newest datetime come first.
     *  We detect header items, group images under the header's eventId, order groups by header date/time,
     *  then flatten to a list of header+its images (desc by date/time).
     */
    private fun orderGalleryItemsByEventDateDesc(items: List<GalleryItem>): List<GalleryItem> {
        // Map eventId -> pair(header, images)
        data class Group(val header: GalleryItem.Header, val images: MutableList<GalleryItem.Image> = mutableListOf())

        val groups = linkedMapOf<String, Group>()
        var currentHeaderId: String? = null

        // First pass: group items in the original sequence
        for (it in items) {
            when (it) {
                is GalleryItem.Header -> {
                    groups[it.eventId] = Group(it)
                    currentHeaderId = it.eventId
                }
                is GalleryItem.Image -> {
                    val gid = it.eventId
                    val g = groups[gid] ?: run {
                        // If there's an image without header (shouldn't happen), create a minimal header
                        val fakeHeader = GalleryItem.Header(gid, "No title", "")
                        val newG = Group(fakeHeader)
                        groups[gid] = newG
                        newG
                    }
                    g.images.add(it)
                }
            }
        }

        // Sort groups by header date/time descending
        val sortedGroups = groups.values.sortedWith { a, b ->
            val aDate = parseDateTimeForSort(a.header.date)
            val bDate = parseDateTimeForSort(b.header.date)
            // descending
            when {
                aDate == bDate -> 0
                aDate < bDate -> 1
                else -> -1
            }
        }

        // Flatten
        val out = mutableListOf<GalleryItem>()
        for (g in sortedGroups) {
            out.add(g.header)
            out.addAll(g.images)
        }
        return out
    }

    /** Parse date string "yyyy-MM-dd" and optionally time "HH:mm" — return epoch millis.
     *  If parsing fails, return 0 so it sorts to oldest.
     */
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
