package com.example.basicfiredatabase.fragments

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.res.Resources
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
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
import com.example.basicfiredatabase.utils.MAX_IMAGES
import com.example.basicfiredatabase.utils.TranslationHelper
import com.example.basicfiredatabase.utils.TranslationHelper.pasteFromClipboard
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.google.firebase.remoteconfig.ktx.remoteConfig
import com.google.firebase.remoteconfig.ktx.remoteConfigSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
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

// EditUserFragment.kt (refactored, behavior-preserving)

class EditUserFragment : Fragment(R.layout.fragment_edit_user) {

    private var _binding: FragmentEditUserBinding? = null
    private val binding get() = _binding!!

    private val db by lazy { Firebase.firestore }

    // Image state
    private val existingImages = mutableListOf<MutableMap<String, String>>()
    private val newImageUris = mutableListOf<Uri>()
    private val imagesToDelete = mutableListOf<String>()

    // progress UI map: keys like "existing:<public_id>" or "new:<index>"
    private val progressMap = mutableMapOf<String, ProgressBar>()

    // Remote Config values
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

    // For scrolling/insets
    private var lastImeHeight = 0
    private var lastNavHeight = 0
    private var currentFocusedView: View? = null

    // Launcher (keeps same behavior)
    private val pickImagesLauncher =
        registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            handlePickedImages(uris)
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentEditUserBinding.bind(view)


        // --- high-level orchestration only ---
        setupInsetsAndFocus()
        setupRemoteConfig()
        setupPasteAndVerifyButtons()
        setupDateTimePickers()
        setupSpinner()
        setupPrimaryTranslationWatcher()
        setupPickAndUpdateHandlers()

        setupMapLinkToggle()

        handleIncomingArguments()
        renderImages() // initial render (may be empty)
    }

    // -----------------------
    // Setup helpers (keeps onViewCreated tidy)
    // -----------------------
    private fun setupInsetsAndFocus() {
        val focusableFields = listOf(
            binding.etEditTitle,
            binding.etEditDescriptionPrimary,
            binding.etEditDescriptionSecondary,
            binding.etEditDescriptionTertiary,
            binding.etEditDuration,
            binding.etEditLocation,
            binding.etEditMapLink
        )

        for (field in focusableFields) {
            field.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    currentFocusedView = v
                    v.postDelayed({
                        // guard: bail out if view destroyed or fragment not attached to UI
                        if (_binding == null || !isAdded) return@postDelayed
                        scrollToView(binding.scrollViewEdit, v, dpToPx(12))
                    }, 120)
                }
            }
            field.setOnClickListener { v ->
                currentFocusedView = v
                v.post { scrollToView(binding.scrollViewEdit, v, dpToPx(12)) }
            }
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.scrollViewEdit) { v, insets ->
            val navBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            lastNavHeight = navBottom
            lastImeHeight = imeBottom

            val extraGap = dpToPx(4)
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, navBottom + imeBottom + extraGap)

            val lp = binding.etActionButtons.layoutParams
            if (lp is ViewGroup.MarginLayoutParams) {
                val desiredBottomMargin = navBottom + dpToPx(6)
                if (lp.bottomMargin != desiredBottomMargin) {
                    lp.bottomMargin = desiredBottomMargin
                    binding.etActionButtons.layoutParams = lp
                }
            }

            if (imeBottom > 0) {
                currentFocusedView?.let { fv ->
                    v.post { scrollToView(binding.scrollViewEdit, fv, dpToPx(12)) }
                }
            }
            insets
        }
    }

    private fun setupPasteAndVerifyButtons() {
        binding.btnPasteSecondaryEdit.setOnClickListener {
            pasteFromClipboard(requireContext(), binding.etEditDescriptionSecondary)
        }
        binding.btnPasteTertiaryEdit.setOnClickListener {
            pasteFromClipboard(requireContext(), binding.etEditDescriptionTertiary)
        }

        //maps
        binding.btnPasteMapLinkEdit.setOnClickListener {
            pasteFromClipboard(requireContext(), binding.etEditMapLink)
            // optional: request focus so keyboard appears if user wants to edit
            binding.etEditMapLink.requestFocus()
        }


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

    }

    private fun setupDateTimePickers() {
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
    }

    private fun setupSpinner() {
        val types = listOf(
            "Community Feeding Program",
            "Back-to-School Drive",
            "Children’s Health and Wellness Fair",
            "Sports and Recreation Day",
            "Community Clean-Up and Beautification",
            "Food and Hygiene Pack Distribution",
            "Emergency Relief Fundraising Event"
        )

        // Use your custom layouts for the closed view and dropdown items
        val adapter = ArrayAdapter(requireContext(), R.layout.spinner_item, types)
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        binding.spinnerEditEventType.adapter = adapter

        // Forward clicks from container and arrow to the spinner so whole area opens the dropdown
        binding.spinnerEditContainer.setOnClickListener {
            binding.spinnerEditEventType.performClick()
        }

        binding.layoutEventStatus.setOnClickListener {
            binding.switchEditIsComplete.toggle()
        }

    }

    private fun setupPickAndUpdateHandlers() {
        binding.btnPickMoreImages.setOnClickListener { pickImagesLauncher.launch("image/*") }
        binding.btnUpdate.setOnClickListener { performUpdate() }

        binding.etBtnCancel.setOnClickListener {
            // Make sure UI is clear (hide progress)
            binding.pbUpdate.visibility = View.GONE

            // cancel any active translation debounce so it doesn't try to update UI while fragment closing
            translationJob?.cancel()

            // pop this fragment off the back stack
            parentFragmentManager.popBackStack()
        }

    }

    private fun handleIncomingArguments() {
        val argDocId = arguments?.getString("docId")
        val argId = arguments?.getString("id")
        docIdArg = argDocId ?: argId

        if (!docIdArg.isNullOrBlank()) {
            loadDocumentAndPopulate(docIdArg!!)
        } else {
            populateFromArgumentsIfPresent()
            renderImages()
        }
    }

    // -----------------------
    // Existing implementations (kept mostly unchanged) but grouped
    // -----------------------
    private fun handlePickedImages(uris: List<Uri>?) {
        if (uris == null || uris.isEmpty()) return
        val currentTotal = existingImages.size + newImageUris.size
        val spaceLeft = maxOf(0, MAX_IMAGES - currentTotal)
        if (spaceLeft <= 0) {
            Toast.makeText(requireContext(), "Maximum $MAX_IMAGES images allowed", Toast.LENGTH_SHORT).show()
            return
        }
        val toAdd = uris.take(spaceLeft)
        newImageUris.addAll(toAdd)
        renderImages()

        binding.hsvEditImages.post {
            if (_binding == null || !isAdded) return@post
            binding.hsvEditImages.smoothScrollTo(binding.llEditImages.measuredWidth, 0)
        }
    }

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
                val isComplete = snapshot.getBoolean("is_complete") ?: false

                val includeMapLink = snapshot.getBoolean("include_map_link") ?: false
                val mapLink = snapshot.getString("map_link") ?: ""


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

                originalPrimaryText = primary


                binding.tvEditDate.text = if (date.isNotBlank()) date else "Select date"
                binding.tvEditTime.text = if (time.isNotBlank()) time else "Select time"
                binding.etEditDuration.setText(duration)
                binding.etEditLocation.setText(location)
                binding.switchEditIsComplete.isChecked = isComplete

                // NEW: map fields
                binding.cbEditIncludeMap.isChecked = includeMapLink
                if (includeMapLink) {
                    binding.layoutEditMapLinkRow.visibility = View.VISIBLE
                    binding.etEditMapLink.setText(mapLink)
                } else {
                    binding.layoutEditMapLinkRow.visibility = View.GONE
                    binding.etEditMapLink.text?.clear()
                }

                isInitializing = false
                renderImages()
            }
            .addOnFailureListener { ex ->
                Toast.makeText(requireContext(), "Failed to load: ${ex.message}", Toast.LENGTH_LONG).show()
            }
    }

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
        val isCompleteArg = args.getBoolean("is_complete", false)

        // NEW: optional map args
        val includeMapArg = args.getBoolean("include_map_link", false)
        val mapLinkArg = args.getString("map_link") ?: ""

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
        binding.switchEditIsComplete.isChecked = isCompleteArg


        // NEW: apply include-map and link from arguments
        binding.cbEditIncludeMap.isChecked = includeMapArg
        if (includeMapArg) {
            binding.layoutEditMapLinkRow.visibility = View.VISIBLE
            binding.etEditMapLink.setText(mapLinkArg)
        } else {
            binding.layoutEditMapLinkRow.visibility = View.GONE
            binding.etEditMapLink.text?.clear()
        }

        isInitializing = false

    }

    private fun renderImages() {
        val ll = binding.llEditImages
        ll.removeAllViews()
        progressMap.clear()

        val inflater = LayoutInflater.from(requireContext())
        val sizePx = dpToPx(160)
        val marginPx = dpToPx(6)

        // existing images
        for (img in existingImages.toList()) {
            val frame = inflater.inflate(R.layout.item_selected_image, ll, false) as FrameLayout
            val lp = LinearLayout.LayoutParams(sizePx, sizePx)
            lp.setMargins(marginPx, marginPx, marginPx, marginPx)
            frame.layoutParams = lp

            //Adding to see if it focuses on uploading images after hitting save button
            frame.isFocusable = true
            frame.isFocusableInTouchMode = true

            val iv = frame.findViewById<ImageView>(R.id.item_iv)
            val removeBtn = frame.findViewById<ImageButton>(R.id.item_remove)
            val progress = frame.findViewById<ProgressBar>(R.id.item_progress)

            Glide.with(this).load(img["url"]).into(iv)

            removeBtn.setOnClickListener {
                val publicId = img["public_id"] ?: return@setOnClickListener
                imagesToDelete.add(publicId)
                existingImages.remove(img)
                renderImages()
            }

            // stable key for existing ones
            val key = "existing:${img["public_id"]}"
            // initialize progressbar defensively
            progress.visibility = View.GONE
            progress.isIndeterminate = false
            progress.max = 100
            progress.progress = 0
            progressMap[key] = progress

            ll.addView(frame)
        }

        // new local images
        for (uri in newImageUris.toList()) {
            val frame = inflater.inflate(R.layout.item_selected_image, ll, false) as FrameLayout
            val lp = LinearLayout.LayoutParams(sizePx, sizePx)
            lp.setMargins(marginPx, marginPx, marginPx, marginPx)
            frame.layoutParams = lp

            val iv = frame.findViewById<ImageView>(R.id.item_iv)
            val removeBtn = frame.findViewById<ImageButton>(R.id.item_remove)
            val progress = frame.findViewById<ProgressBar>(R.id.item_progress)

            Glide.with(this).load(uri).into(iv)

            removeBtn.setOnClickListener {
                newImageUris.remove(uri)
                renderImages()
            }

            // stable key based on uri string (must match uploadNewImages)
            val key = "new:${uri.toString()}"
            // initialize progressbar defensively
            progress.visibility = View.GONE
            progress.isIndeterminate = false
            progress.max = 100
            progress.progress = 0
            progressMap[key] = progress

            ll.addView(frame)
        }

        // update count text
        val total = existingImages.size + newImageUris.size
        binding.tvEditImagesCount.text =
            if (total == 0) "No images" else "$total / $MAX_IMAGES images"

        binding.btnPickMoreImages.isEnabled = total < MAX_IMAGES
    }






    private suspend fun uploadNewImages(uris: List<Uri>): List<Map<String, String>> {
        if (uris.isEmpty()) return emptyList()
        val url = uploadUrl ?: throw Exception("Upload URL not available")
        val apiKey = imageCloudApiKey ?: ""

        // Use uri string as the stable key so it matches renderImages() keys
        val pairs = uris.map { uri -> "new:${uri.toString()}" to uri }

        return try {
            ImageUploadService.uploadUris(requireContext(), pairs, url, apiKey) { key, visible ->
                // callback invoked on upload start/stop for a particular key
                lifecycleScope.launchWhenStarted {
                    val progress = progressMap[key]
                    if (progress != null) {
                        progress.visibility = if (visible) View.VISIBLE else View.GONE

                        if (visible) {
                            // progress.parent should be the FrameLayout inflated for that item
                            val itemView = progress.parent as? View ?: return@launchWhenStarted


                            //Adding to see if it focuses on uploading images after hitting save button
// request focus on the item (so keyboard focus stays away from inputs)
                            itemView.isFocusable = true
                            itemView.isFocusableInTouchMode = true
                            itemView.requestFocus()



                            // post to ensure measured/laid-out values are available
                            binding.hsvEditImages.post {
                                // center the item in the HSV (so it's clearly visible)
                                val itemLeft = itemView.left
                                val itemWidth = itemView.width
                                val hsvWidth = binding.hsvEditImages.width
                                val targetX = (itemLeft + itemWidth / 2 - hsvWidth / 2).coerceAtLeast(0)
                                binding.hsvEditImages.smoothScrollTo(targetX, 0)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Ensure all progress bars for these new URIs are hidden on failure
            withContext(Dispatchers.Main) {
                pairs.forEach { (key, _) ->
                    progressMap[key]?.visibility = View.GONE
                }
            }
            throw e
        }
    }



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

    private fun performUpdate() {
        // validations (same as before)...
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

        // NEW: read map checkbox + link (no validation per your request)
        val includeMapLink = binding.cbEditIncludeMap.isChecked
        val mapLink: String? = if (includeMapLink) {
            binding.etEditMapLink.text?.toString()?.trim().takeIf { !it.isNullOrEmpty() }
        } else {
            null
        }



        val isComplete = binding.switchEditIsComplete.isChecked

        //uploading images is optional
//        val totalImagesCount = existingImages.size + newImageUris.size
//        if (totalImagesCount <= 0) {
//            Toast.makeText(requireContext(), "At least one image is required", Toast.LENGTH_SHORT).show()
//            return
//        }


        // disable UI while processing (do this immediately so user cannot change content)
        binding.btnPickMoreImages.isEnabled = false
        binding.btnUpdate.isEnabled = false
        binding.etBtnCancel.isEnabled = false


//        // Clear focus to avoid focus change scrolling to an image view
//        binding.root.requestFocus()
//        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
//        imm?.hideSoftInputFromWindow(binding.root.windowToken, 0)

        //Adding to see if it focuses on uploading images after hitting save button
        //Above commented code was to test this
        // --- Make image area take focus so it stays visible while uploading ---
        binding.hsvEditImages.isFocusable = true
        binding.hsvEditImages.isFocusableInTouchMode = true

// Request focus on HSV (so other EditText focus handlers won't kick in)
        binding.hsvEditImages.requestFocus()

// optionally clear focus from EditTexts (without focusing root)
        val focusableFields = listOf(
            binding.etEditTitle,
            binding.etEditDescriptionPrimary,
            binding.etEditDescriptionSecondary,
            binding.etEditDescriptionTertiary,
            binding.etEditDuration,
            binding.etEditLocation,
            binding.etEditMapLink
        )
        focusableFields.forEach { it.clearFocus() }

// hide keyboard (use a specific window token; using an EditText token is safer)
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(binding.etEditTitle.windowToken, 0)

// finally, make sure HSV scrolls to the end so uploaded items are visible
        binding.hsvEditImages.post {
            binding.hsvEditImages.smoothScrollTo(binding.llEditImages.measuredWidth, 0)
        }





        // DO NOT show the big pbUpdate yet — let per-image progress be visible during uploads.

        lifecycleScope.launchWhenStarted {
            var updateSucceeded = false

            try {
                // 1) upload new images (per-image progress/UI and horizontal auto-scroll will still be visible)
                val uploadedNewImages = if (newImageUris.isNotEmpty()) {
                    if (uploadUrl.isNullOrBlank()) throw Exception("Upload URL not available")
                    uploadNewImages(newImageUris) // make sure this function scrolls only the horizontal HSV
                } else emptyList()

                // 2) Now that images are uploaded, show global "finalizing" progress (overlay)
                withContext(Dispatchers.Main) {
                    binding.pbUpdate.visibility = View.VISIBLE
                    // optionally bring to front to ensure overlay
                    binding.pbUpdate.bringToFront()
                }

                // 3) Delete marked images (if any)
                if (imagesToDelete.isNotEmpty()) {
                    if (deleteUrl.isNullOrBlank()) throw Exception("Delete URL not available")
                    val ok = deleteMarkedImages(imagesToDelete.toList())
                    if (!ok) throw Exception("Failed to delete some images")
                    // ensure local state is consistent
                    existingImages.removeAll { imagesToDelete.contains(it["public_id"]) }
                    imagesToDelete.clear()
                }

                // 4) Build final images and update Firestore
                val finalImages = mutableListOf<Map<String, String>>()
                finalImages.addAll(existingImages.map { mapOf("url" to it["url"]!!, "public_id" to it["public_id"]!!) })
                finalImages.addAll(uploadedNewImages)

                val updateMap = hashMapOf<String, Any?>(
                    "title" to title,
                    "event_type" to eventType,
                    "descriptions" to descriptions,
                    "date" to date,
                    "time" to time,
                    "duration_minutes" to durationMinutes,
                    "location" to location,
                    "is_complete" to isComplete,
                    "images" to finalImages,

                    // NEW fields:
                    "include_map_link" to includeMapLink,
                    "map_link" to mapLink // null if not provided
                )

                val docId = docIdArg
                if (docId.isNullOrBlank()) throw Exception("No document id to update")

                // IMPORTANT: await the update task so we keep pbUpdate visible until Firestore finishes
                db.collection("users").document(docId).update(updateMap).await()


// mark success — do NOT pop the fragment here
                updateSucceeded = true


                // success path (update UI on main)
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Updated", Toast.LENGTH_SHORT).show()

                    newImageUris.clear()
                    existingImages.clear()
                    existingImages.addAll(finalImages.map { mutableMapOf("url" to it["url"]!!, "public_id" to it["public_id"]!!) })
                    renderImages()
                    originalPrimaryText = descriptions["primary"] ?: ""

                    parentFragmentManager.popBackStack()
                }

            } catch (e: Exception) {
                // show error (main thread)
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                // always re-enable UI and hide the big progress
                withContext(Dispatchers.Main) {
                    binding.btnPickMoreImages.isEnabled = true
                    binding.btnUpdate.isEnabled = true
                    binding.pbUpdate.visibility = View.GONE
                    binding.etBtnCancel.isEnabled = true

                }

                // Now finally pop the fragment **after** UI cleaned up and only if update succeeded
                if (updateSucceeded && isAdded) {
                    parentFragmentManager.popBackStack()
                }

            }
        }
    }


    private fun setupPrimaryTranslationWatcher() {
        binding.etEditDescriptionPrimary.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {

                if (isInitializing) return

                translationJob?.cancel()
                val currentText = s?.toString() ?: ""

                if (currentText.trim().isEmpty()) {
                    binding.etEditDescriptionSecondary.setText("")
                    binding.etEditDescriptionTertiary.setText("")
                    return
                }

                if (currentText.trim() == originalPrimaryText.trim()) return

                translationJob = lifecycleScope.launch {
                    try {
                        delay(3000)

                        val zUrl = zuluUrl
                        val aUrl = afrikaansUrl
                        val apiKey = apiKeyForTranslate

                        if (zUrl.isNullOrBlank() || aUrl.isNullOrBlank()) return@launch
                        if (apiKey.isNullOrBlank()) {
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
                            withTimeoutOrNull(10_000) {
                                TranslationHelper.translateText(currentText, zUrl, apiKey)
                            }
                        }
                        val afrDeferred = async(Dispatchers.IO) {
                            withTimeoutOrNull(10_000) {
                                TranslationHelper.translateText(currentText, aUrl, apiKey)
                            }
                        }

                        val zuluResult = try { zuluDeferred.await() } catch (_: Exception) { null }
                        val afrResult = try { afrDeferred.await() } catch (_: Exception) { null }

                        withContext(Dispatchers.Main) {
                            binding.etEditDescriptionSecondary.setText(zuluResult ?: "Translation timed out")
                            binding.etEditDescriptionTertiary.setText(afrResult ?: "Translation timed out")
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

    // Scroll utility preserved
    private fun scrollToView(scrollView: ScrollView, view: View, extra: Int = 0) {
        val rect = android.graphics.Rect()
        view.getDrawingRect(rect)
        scrollView.offsetDescendantRectToMyCoords(view, rect)
        val visibleHeight = scrollView.height - lastImeHeight - lastNavHeight
        val targetBottom = rect.bottom
        val scrollY = targetBottom - (visibleHeight - extra)
        if (scrollY > 0) {
            scrollView.post { scrollView.smoothScrollTo(0, scrollY) }
        } else {
            scrollView.post {
                scrollView.smoothScrollTo(0, maxOf(0, scrollView.scrollY - dpToPx(4)))
            }
        }
    }


    private fun setupMapLinkToggle() {
        binding.cbEditIncludeMap.setOnCheckedChangeListener { _, isChecked ->
            // show/hide the entire row (input + paste + progress)
            binding.layoutEditMapLinkRow.visibility = if (isChecked) View.VISIBLE else View.GONE

            if (!isChecked) {
                // clear input & hide progress when unchecked
                binding.etEditMapLink.text?.clear()
                binding.tilEditMapLink.error = null
            }
        }
    }



    private fun dpToPx(dp: Int): Int {
        val density = view?.resources?.displayMetrics?.density
            ?: _binding?.root?.resources?.displayMetrics?.density
            ?: Resources.getSystem().displayMetrics.density
        return (dp * density).toInt()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        translationJob?.cancel()
        _binding = null
    }
}
