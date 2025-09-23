package com.example.basicfiredatabase.fragments

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.example.basicfiredatabase.R
import com.example.basicfiredatabase.databinding.FragmentEditUserBinding
import com.example.basicfiredatabase.utils.ImageUploadService
import com.example.basicfiredatabase.utils.TranslationHelper
import com.example.basicfiredatabase.utils.TranslationHelper.pasteFromClipboard
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.google.firebase.remoteconfig.ktx.remoteConfig
import com.google.firebase.remoteconfig.ktx.remoteConfigSettings
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

class EditUserFragment : Fragment(R.layout.fragment_edit_user) {

    private var _binding: FragmentEditUserBinding? = null
    private val binding get() = _binding!!

    private val db by lazy { Firebase.firestore }

    // Image state
    // existingImages: mutable list of maps with keys "url" and "public_id"
    private val existingImages = mutableListOf<MutableMap<String, String>>()
    private val newImageUris = mutableListOf<Uri>()
    private val imagesToDelete = mutableListOf<String>()

    // progress UI map: keys like "existing:<public_id>" or "new:<index>"
    private val progressMap = mutableMapOf<String, ProgressBar>()

    // Remote Config values (upload/delete + translation endpoints)
    private var uploadUrl: String? = null
    private var deleteUrl: String? = null
    private var imageCloudApiKey: String? = null

    private var zuluUrl: String? = null
    private var afrikaansUrl: String? = null
    private var apiKeyForTranslate: String? = null

    // translation debounce job
    private var translationJob: Job? = null
    private var originalPrimaryText: String = ""

    // Accept doc id
    private var docIdArg: String? = null

    private var isInitializing = false


    private val pickImagesLauncher =
        registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            if (uris == null || uris.isEmpty()) return@registerForActivityResult

            val currentTotal = existingImages.size + newImageUris.size
            val spaceLeft = maxOf(0, 10 - currentTotal)
            if (spaceLeft <= 0) {
                Toast.makeText(requireContext(), "Maximum 10 images allowed", Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }

            val toAdd = uris.take(spaceLeft)
            newImageUris.addAll(toAdd)
            renderImages()
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentEditUserBinding.bind(view)

        // Window inset handling similar to AddUser
        ViewCompat.setOnApplyWindowInsetsListener(binding.scrollViewEdit) { v, insets ->
            val navBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val extraGap = dpToPx(4)
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, navBottom + imeBottom + extraGap)

            val lp = binding.btnUpdate.layoutParams
            if (lp is ViewGroup.MarginLayoutParams) {
                val desiredBottomMargin = navBottom + dpToPx(6)
                if (lp.bottomMargin != desiredBottomMargin) {
                    lp.bottomMargin = desiredBottomMargin
                    binding.btnUpdate.layoutParams = lp
                }
            }
            insets
        }

        // Load remote config (upload/delete + translate endpoints)
        setupRemoteConfig()

        // Paste buttons
        binding.btnPasteSecondaryEdit.setOnClickListener {
            pasteFromClipboard(requireContext(), binding.etEditDescriptionSecondary)
        }
        binding.btnPasteTertiaryEdit.setOnClickListener {
            pasteFromClipboard(requireContext(), binding.etEditDescriptionTertiary)
        }

        // Verify links (open Google Translate)
        binding.tvVerifyEditSecondary.setOnClickListener {
            TranslationHelper.openGoogleTranslateFromPrimary(
                this@EditUserFragment,
                binding.etEditDescriptionPrimary.text?.toString().orEmpty(),
                "zu"
            )
        }
        binding.tvVerifyEditTertiary.setOnClickListener {
            TranslationHelper.openGoogleTranslateFromPrimary(
                this@EditUserFragment,
                binding.etEditDescriptionPrimary.text?.toString().orEmpty(),
                "af"
            )
        }

        // Date/time pickers
        binding.tvEditDate.setOnClickListener {
            val c = Calendar.getInstance()
            val dp = DatePickerDialog(
                requireContext(),
                { _, year, month, dayOfMonth ->
                    val cal = Calendar.getInstance()
                    cal.set(year, month, dayOfMonth)
                    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                    binding.tvEditDate.text = sdf.format(cal.time)
                },
                c.get(Calendar.YEAR),
                c.get(Calendar.MONTH),
                c.get(Calendar.DAY_OF_MONTH)
            )
            dp.show()
        }

        binding.tvEditTime.setOnClickListener {
            val c = Calendar.getInstance()
            val tp = TimePickerDialog(
                requireContext(),
                { _, hourOfDay, minute ->
                    val formatted = String.format(Locale.US, "%02d:%02d", hourOfDay, minute)
                    binding.tvEditTime.text = formatted
                },
                c.get(Calendar.HOUR_OF_DAY),
                c.get(Calendar.MINUTE),
                true
            )
            tp.show()
        }

        // Spinner choices (keep consistent with AddUser)
        val types = listOf(
            "Community Feeding Program",
            "Back-to-School Drive",
            "Children’s Health and Wellness Fair",
            "Sports and Recreation Day",
            "Community Clean-Up and Beautification",
            "Food and Hygiene Pack Distribution",
            "Emergency Relief Fundraising Event",
            "Other"
        )
        binding.spinnerEditEventType.adapter =
            ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, types)

        // Setup primary description watcher (debounced translation). We'll set originalPrimaryText when doc is loaded.
        setupPrimaryTranslationWatcher()

        // pick more images
        binding.btnPickMoreImages.setOnClickListener { pickImagesLauncher.launch("image/*") }

        // Update button logic
        binding.btnUpdate.setOnClickListener {
            performUpdate()
        }

        // Determine docId (support both keys)
        val argDocId = arguments?.getString("docId")
        val argId = arguments?.getString("id")
        docIdArg = argDocId ?: argId

        // If docId provided -> load from Firestore; otherwise attempt to populate from arguments bundle (old behavior)
        if (!docIdArg.isNullOrBlank()) {
            loadDocumentAndPopulate(docIdArg!!)
        } else {
            populateFromArgumentsIfPresent()
            renderImages()
        }
    }

    // -----------------------
    // Remote Config
    // -----------------------
    private fun setupRemoteConfig() {
        val remoteConfig = Firebase.remoteConfig
        val configSettings = remoteConfigSettings { minimumFetchIntervalInSeconds = 3600 }
        remoteConfig.setConfigSettingsAsync(configSettings)

        remoteConfig.setDefaultsAsync(
            mapOf(
                "uploadImage_url" to "https://cloudinaryserver.onrender.com/upload",
                "deleteImage_url" to "https://cloudinaryserver.onrender.com/delete",
                "translate_zulu_url" to "https://mymemoryserver.onrender.com/translate/zu",
                "translate_afrikaans_url" to "https://mymemoryserver.onrender.com/translate/af"
            )
        )

        remoteConfig.fetchAndActivate()
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    uploadUrl = remoteConfig.getString("uploadImage_url")
                    deleteUrl = remoteConfig.getString("deleteImage_url")
                    imageCloudApiKey = remoteConfig.getString("cloud_api_key")

                    zuluUrl = remoteConfig.getString("translate_zulu_url")
                    afrikaansUrl = remoteConfig.getString("translate_afrikaans_url")
                    apiKeyForTranslate = remoteConfig.getString("translate_api_key")

                    Toast.makeText(requireContext(), "Remote config loaded", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "Failed to fetch Remote Config", Toast.LENGTH_SHORT).show()
                }
            }
    }

    // -----------------------
    // Load existing document & populate UI
    // -----------------------
    private fun loadDocumentAndPopulate(docId: String) {
        db.collection("users").document(docId).get()
            .addOnSuccessListener { snapshot ->
                if (!snapshot.exists()) {
                    Toast.makeText(requireContext(), "Document not found", Toast.LENGTH_LONG).show()
                    return@addOnSuccessListener
                }

                val title = snapshot.getString("title") ?: ""
                val eventType = snapshot.getString("event_type") ?: "Other"
                val descriptions = snapshot.get("descriptions") as? Map<*, *>
                val date = snapshot.getString("date") ?: ""
                val time = snapshot.getString("time") ?: ""
                val duration = snapshot.getLong("duration_minutes")?.toString() ?: ""
                val location = snapshot.getString("location") ?: ""
                val isUpcoming = snapshot.getBoolean("is_upcoming") ?: true

                // images
                val imagesList = snapshot.get("images") as? List<Map<String, Any>>
                existingImages.clear()
                imagesList?.forEach { map ->
                    val url = map["url"]?.toString()
                    val publicId = map["public_id"]?.toString()
                    if (!url.isNullOrBlank() && !publicId.isNullOrBlank()) {
                        existingImages.add(mutableMapOf("url" to url, "public_id" to publicId))
                    }
                }

                isInitializing = true

                // populate UI
                binding.etEditTitle.setText(title)
                // set spinner selection (match by exact string)
                val adapter = binding.spinnerEditEventType.adapter
                if (adapter != null) {
                    for (i in 0 until adapter.count) {
                        if (adapter.getItem(i)?.toString() == eventType) {
                            binding.spinnerEditEventType.setSelection(i)
                            break
                        }
                    }
                }
                val primary = descriptions?.get("primary")?.toString() ?: ""
                val secondary = descriptions?.get("secondary")?.toString() ?: ""
                val tertiary = descriptions?.get("tertiary")?.toString() ?: ""

                binding.etEditDescriptionPrimary.setText(primary)
                binding.etEditDescriptionSecondary.setText(secondary)
                binding.etEditDescriptionTertiary.setText(tertiary)

                // record original primary to avoid unnecessary re-translation
                originalPrimaryText = primary
                isInitializing = false


                binding.tvEditDate.text = if (date.isNotBlank()) date else "Select date"
                binding.tvEditTime.text = if (time.isNotBlank()) time else "Select time"
                binding.etEditDuration.setText(duration)
                binding.etEditLocation.setText(location)
                binding.switchEditUpcoming.isChecked = isUpcoming

                renderImages()
            }
            .addOnFailureListener { ex ->
                Toast.makeText(requireContext(), "Failed to load: ${ex.message}", Toast.LENGTH_LONG).show()
            }
    }

    // Fallback: populate from arguments (old behavior you had)
    private fun populateFromArgumentsIfPresent() {
        val args = arguments ?: return
        val title = args.getString("title") ?: ""
        val eventTypeArg = args.getString("event_type") ?: "Other"
        @Suppress("UNCHECKED_CAST")
        val descriptionsArg = (args.getSerializable("descriptions") as? HashMap<*, *>)?.mapNotNull { e ->
            val k = e.key as? String; val v = e.value as? String
            if (k != null && v != null) k to v else null
        }?.toMap() ?: emptyMap()

        val dateArg = args.getString("date") ?: ""
        val timeArg = args.getString("time") ?: ""
        val durationArg = args.getInt("duration_minutes", 0)
        val locationArg = args.getString("location") ?: ""
        val isUpcomingArg = args.getBoolean("is_upcoming", true)

        @Suppress("UNCHECKED_CAST")
        val imgs = args.getSerializable("images") as? ArrayList<*>
        existingImages.clear()
        if (imgs != null) {
            for (item in imgs) {
                val map = item as? Map<*, *> ?: continue
                val url = map["url"] as? String ?: continue
                val publicId = map["public_id"] as? String ?: continue
                existingImages.add(mutableMapOf("url" to url, "public_id" to publicId))
            }
        }

        isInitializing = true

        binding.etEditTitle.setText(title)
        val adapter = binding.spinnerEditEventType.adapter
        if (adapter != null) {
            for (i in 0 until adapter.count) {
                if (adapter.getItem(i)?.toString() == eventTypeArg) {
                    binding.spinnerEditEventType.setSelection(i)
                    break
                }
            }
        }
        val p = descriptionsArg["primary"] ?: ""
        binding.etEditDescriptionPrimary.setText(p)
        binding.etEditDescriptionSecondary.setText(descriptionsArg["secondary"] ?: "")
        binding.etEditDescriptionTertiary.setText(descriptionsArg["tertiary"] ?: "")
        originalPrimaryText = p

        binding.tvEditDate.text = if (dateArg.isNotBlank()) dateArg else "Select date"
        binding.tvEditTime.text = if (timeArg.isNotBlank()) timeArg else "Select time"
        binding.etEditDuration.setText(if (durationArg > 0) durationArg.toString() else "")
        binding.etEditLocation.setText(locationArg)
        binding.switchEditUpcoming.isChecked = isUpcomingArg
    }

    // -----------------------
    // UI: render images strip (existing + new)
    // -----------------------
    private fun renderImages() {
        val ll = binding.llEditImages
        ll.removeAllViews()
        progressMap.clear()

        val sizePx = dpToPx(140)
        val marginPx = dpToPx(6)

        // existing images first
        for ((idx, img) in existingImages.withIndex()) {
            val frame = FrameLayout(requireContext())
            val frameLp = LinearLayout.LayoutParams(sizePx, ViewGroup.LayoutParams.MATCH_PARENT)
            frameLp.setMargins(marginPx, marginPx, marginPx, marginPx)
            frame.layoutParams = frameLp

            val iv = ImageView(requireContext())
            iv.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            Glide.with(this).load(img["url"]).into(iv)
            frame.addView(iv)

            // remove button (marks for deletion)
            val removeBtn = ImageButton(requireContext())
            val dbSize = dpToPx(36)
            val remLp = FrameLayout.LayoutParams(dbSize, dbSize)
            remLp.gravity = Gravity.END or Gravity.TOP
            remLp.topMargin = dpToPx(6)
            remLp.marginEnd = dpToPx(6)
            removeBtn.layoutParams = remLp
            removeBtn.setImageResource(R.drawable.ic_close)
            removeBtn.setBackgroundResource(android.R.color.transparent)
            removeBtn.setOnClickListener {
                val publicId = img["public_id"] ?: return@setOnClickListener
                imagesToDelete.add(publicId)
                existingImages.removeAt(idx)
                renderImages()
            }
            frame.addView(removeBtn)

            // progress overlay
            val progress = ProgressBar(requireContext())
            val pSize = dpToPx(40)
            val pLp = FrameLayout.LayoutParams(pSize, pSize)
            pLp.gravity = Gravity.CENTER
            progress.layoutParams = pLp
            progress.isIndeterminate = true
            progress.visibility = View.GONE
            val key = "existing:${img["public_id"]}"
            progressMap[key] = progress
            frame.addView(progress)

            ll.addView(frame)
        }

        // new local images
        for ((index, uri) in newImageUris.withIndex()) {
            val frame = FrameLayout(requireContext())
            val frameLp = LinearLayout.LayoutParams(sizePx, ViewGroup.LayoutParams.MATCH_PARENT)
            frameLp.setMargins(marginPx, marginPx, marginPx, marginPx)
            frame.layoutParams = frameLp

            val iv = ImageView(requireContext())
            iv.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            Glide.with(this).load(uri).into(iv)
            frame.addView(iv)

            val removeBtn = ImageButton(requireContext())
            val dbSize = dpToPx(36)
            val remLp = FrameLayout.LayoutParams(dbSize, dbSize)
            remLp.gravity = Gravity.END or Gravity.TOP
            remLp.topMargin = dpToPx(6)
            remLp.marginEnd = dpToPx(6)
            removeBtn.layoutParams = remLp
            removeBtn.setImageResource(R.drawable.ic_close)
            removeBtn.setBackgroundResource(android.R.color.transparent)
            removeBtn.setOnClickListener {
                newImageUris.removeAt(index)
                renderImages()
            }
            frame.addView(removeBtn)

            val progress = ProgressBar(requireContext())
            val pSize = dpToPx(40)
            val pLp = FrameLayout.LayoutParams(pSize, pSize)
            pLp.gravity = Gravity.CENTER
            progress.layoutParams = pLp
            progress.isIndeterminate = true
            progress.visibility = View.GONE
            val key = "new:$index"
            progressMap[key] = progress
            frame.addView(progress)

            ll.addView(frame)
        }

        // update count text
        val total = existingImages.size + newImageUris.size
        binding.tvEditImagesCount.text = if (total == 0) "No images" else "$total / 10 images"

        // disable/enable pick button based on max
        binding.btnPickMoreImages.isEnabled = total < 10

    }

    // -----------------------
    // Upload new images (only local ones). Returns list of maps { "url":..., "public_id":... }
    // -----------------------
    private suspend fun uploadNewImages(uris: List<Uri>): List<Map<String, String>> {
        if (uris.isEmpty()) return emptyList()
        val url = uploadUrl ?: throw Exception("Upload URL not available")
        val apiKey = imageCloudApiKey ?: ""

        // build pairs keyed by "new:<index>" so onProgress can map back to progressMap
        val pairs = uris.mapIndexed { idx, uri -> "new:$idx" to uri }

        return ImageUploadService.uploadUris(requireContext(), pairs, url, apiKey) { key, visible ->
            // key will be like "new:0"
            lifecycleScope.launchWhenStarted {
                progressMap[key]?.visibility = if (visible) View.VISIBLE else View.GONE
            }
        }
    }

    // -----------------------
    // Delete marked images via deleteUrl. Posts a JSON with "public_ids": [...]
    // Returns true on success (http 200)
    // -----------------------
    private suspend fun deleteMarkedImages(publicIds: List<String>): Boolean = withContext(Dispatchers.IO) {
        if (publicIds.isEmpty()) return@withContext true
        val url = deleteUrl ?: return@withContext false
        try {
            val client = OkHttpClient.Builder()
                .callTimeout(60, TimeUnit.SECONDS)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()

            for (id in publicIds) {
                val key = "existing:$id"
                withContext(Dispatchers.Main) { progressMap[key]?.visibility = View.VISIBLE }

                val root = JSONObject().put("public_id", id)
                if (!imageCloudApiKey.isNullOrBlank()) root.put("api_key", imageCloudApiKey)

                val body = root.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
                val request = Request.Builder()
                    .url(url)
                    .post(body)
                    .apply { if (!imageCloudApiKey.isNullOrBlank()) addHeader("x-api-key", imageCloudApiKey!!) }
                    .build()

                val resp = client.newCall(request).execute()
                withContext(Dispatchers.Main) { progressMap[key]?.visibility = View.GONE }
                if (!resp.isSuccessful) return@withContext false
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            withContext(Dispatchers.Main) { publicIds.forEach { progressMap["existing:$it"]?.visibility = View.GONE } }
            false
        }
    }


    // -----------------------
    // Update flow (uploads new images, deletes marked, then update firestore)
    // -----------------------
    private fun performUpdate() {
        val title = binding.etEditTitle.text?.toString()?.trim() ?: ""
        if (title.isEmpty()) {
            Toast.makeText(requireContext(), "Enter event title", Toast.LENGTH_SHORT).show()
            return
        }

        val date = binding.tvEditDate.text?.toString()?.trim() ?: ""
        if (date.isEmpty() || date == "Select date") {
            Toast.makeText(requireContext(), "Select event date", Toast.LENGTH_SHORT).show()
            return
        }

        val time = binding.tvEditTime.text?.toString()?.trim() ?: ""
        if (time.isEmpty() || time == "Select time") {
            Toast.makeText(requireContext(), "Select event time", Toast.LENGTH_SHORT).show()
            return
        }

        val descPrimary = binding.etEditDescriptionPrimary.text?.toString()?.trim() ?: ""
        val descSecondary = binding.etEditDescriptionSecondary.text?.toString()?.trim() ?: ""
        val descTertiary = binding.etEditDescriptionTertiary.text?.toString()?.trim() ?: ""

        if (descPrimary.isEmpty() || descSecondary.isEmpty() || descTertiary.isEmpty()) {
            Toast.makeText(requireContext(), "Please fill in all 3 descriptions", Toast.LENGTH_SHORT).show()
            return
        }

        val descriptions = mapOf(
            "primary" to descPrimary,
            "secondary" to descSecondary,
            "tertiary" to descTertiary
        )

        val eventType = binding.spinnerEditEventType.selectedItem?.toString() ?: "Other"

        val durationMinutes = binding.etEditDuration.text?.toString()?.toIntOrNull()
        if (durationMinutes == null || durationMinutes <= 0) {
            Toast.makeText(requireContext(), "Enter valid duration (minutes)", Toast.LENGTH_SHORT).show()
            return
        }

        val location = binding.etEditLocation.text?.toString()?.trim() ?: ""
        if (location.isEmpty()) {
            Toast.makeText(requireContext(), "Enter event location", Toast.LENGTH_SHORT).show()
            return
        }


        val isUpcoming = binding.switchEditUpcoming.isChecked

        // Check at least one image (existing or new)
        val totalImagesCount = existingImages.size + newImageUris.size - imagesToDelete.size
        if (totalImagesCount <= 0) {
            Toast.makeText(requireContext(), "At least one image is required", Toast.LENGTH_SHORT).show()
            return
        }

        // disable UI while processing
        binding.btnPickMoreImages.isEnabled = false
        binding.btnUpdate.isEnabled = false

        lifecycleScope.launchWhenStarted {
            try {
                // 1) upload new images
                val uploadedNewImages = if (newImageUris.isNotEmpty()) {
                    if (uploadUrl.isNullOrBlank()) throw Exception("Upload URL not available")
                    uploadNewImages(newImageUris)
                } else emptyList()

                // 2) delete marked images
                if (imagesToDelete.isNotEmpty()) {
                    if (deleteUrl.isNullOrBlank()) throw Exception("Delete URL not available")
                    val ok = deleteMarkedImages(imagesToDelete.toList())
                    if (!ok) throw Exception("Failed to delete some images")
                    // remove them locally from existingImages (already removed when user tapped delete, but ensure consistency)
                    existingImages.removeAll { imagesToDelete.contains(it["public_id"]) }
                    imagesToDelete.clear()
                }

                // 3) final images
                val finalImages = mutableListOf<Map<String, String>>()
                finalImages.addAll(existingImages.map { mapOf("url" to it["url"]!!, "public_id" to it["public_id"]!!) })
                finalImages.addAll(uploadedNewImages)

                // 4) update Firestore
                val updateMap = hashMapOf<String, Any?>(
                    "title" to title,
                    "event_type" to eventType,
                    "descriptions" to descriptions,
                    "date" to date,
                    "time" to time,
                    "duration_minutes" to durationMinutes,
                    "location" to location,
                    "is_upcoming" to isUpcoming,
                    "images" to finalImages
                )

                val docId = docIdArg
                if (docId.isNullOrBlank()) throw Exception("No document id to update")

                db.collection("users").document(docId).update(updateMap)
                    .addOnSuccessListener {
                        Toast.makeText(requireContext(), "Updated", Toast.LENGTH_SHORT).show()
                        // reflect new state
                        newImageUris.clear()
                        existingImages.clear()
                        existingImages.addAll(finalImages.map { mutableMapOf("url" to it["url"]!!, "public_id" to it["public_id"]!!) })
                        renderImages()
                        originalPrimaryText = descriptions["primary"] ?: ""
                    }
                    .addOnFailureListener { ex ->
                        Toast.makeText(requireContext(), "Update failed: ${ex.message}", Toast.LENGTH_LONG).show()
                    }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                binding.btnPickMoreImages.isEnabled = true
                binding.btnUpdate.isEnabled = true
            }
        }
    }

    // -----------------------
    // Translation: only run if primary text changed from originalPrimaryText
    // -----------------------
    private fun setupPrimaryTranslationWatcher() {
        binding.etEditDescriptionPrimary.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {

                if (isInitializing) return

                translationJob?.cancel()
                val currentText = s?.toString() ?: ""

                // clear translations for empty text
                if (currentText.trim().isEmpty()) {
                    binding.etEditDescriptionSecondary.setText("")
                    binding.etEditDescriptionTertiary.setText("")
                    return
                }

                // If text equals originally loaded text, skip auto-translate (user didn't change)
                if (currentText.trim() == originalPrimaryText.trim()) {
                    // do nothing: keep stored secondary/tertiary as-is
                    return
                }

                translationJob = lifecycleScope.launch {
                    try {
                        delay(3000) // debounce

                        val zUrl = zuluUrl
                        val aUrl = afrikaansUrl
                        val apiKey = apiKeyForTranslate

                        if (zUrl.isNullOrBlank() || aUrl.isNullOrBlank()) {
                            // endpoints missing: skip automatic translation
                            return@launch
                        }
                        if (apiKey.isNullOrBlank()) {
                            // API key missing: inform user once
                            withContext(Dispatchers.Main) {
                                Toast.makeText(requireContext(), "Translate API key missing (check Remote Config)", Toast.LENGTH_SHORT).show()
                            }
                            return@launch
                        }

                        withContext(Dispatchers.Main) {
                            binding.pbEditSecondaryTranslate.visibility = View.VISIBLE
                            binding.pbEditTertiaryTranslate.visibility = View.VISIBLE
                            binding.etEditDescriptionSecondary.setText("Translating...")
                            binding.etEditDescriptionTertiary.setText("Translating...")
                        }

                        val zuluDeferred = async(Dispatchers.IO) {
                            TranslationHelper.translateText(currentText, zUrl, apiKey)
                        }
                        val afrDeferred = async(Dispatchers.IO) {
                            TranslationHelper.translateText(currentText, aUrl, apiKey)
                        }

                        val zuluResult = try { zuluDeferred.await() } catch (_: Exception) { null }
                        val afrResult = try { afrDeferred.await() } catch (_: Exception) { null }

                        withContext(Dispatchers.Main) {
                            if (zuluResult != null) binding.etEditDescriptionSecondary.setText(zuluResult) else binding.etEditDescriptionSecondary.setText("")
                            if (afrResult != null) binding.etEditDescriptionTertiary.setText(afrResult) else binding.etEditDescriptionTertiary.setText("")
                        }
                    } catch (ex: CancellationException) {
                        withContext(Dispatchers.Main) {
                            binding.pbEditSecondaryTranslate.visibility = View.GONE
                            binding.pbEditTertiaryTranslate.visibility = View.GONE
                        }
                        throw ex
                    } catch (ex: Exception) {
                        withContext(Dispatchers.Main) {
                            binding.pbEditSecondaryTranslate.visibility = View.GONE
                            binding.pbEditTertiaryTranslate.visibility = View.GONE
                        }
                    } finally {
                        withContext(Dispatchers.Main) {
                            binding.pbEditSecondaryTranslate.visibility = View.GONE
                            binding.pbEditTertiaryTranslate.visibility = View.GONE
                        }
                    }
                }
            }

            override fun afterTextChanged(s: Editable?) {}
        })
    }

    // -----------------------
    // Utilities
    // -----------------------
    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        super.onDestroyView()
        translationJob?.cancel()
        _binding = null
    }
}
