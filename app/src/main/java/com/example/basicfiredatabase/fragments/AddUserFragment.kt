package com.example.basicfiredatabase.fragments

import android.app.DatePickerDialog
import android.app.TimePickerDialog

import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher

import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.basicfiredatabase.R
import com.example.basicfiredatabase.adapters.ImageListAdapter
import com.example.basicfiredatabase.databinding.FragmentAddUserBinding
import com.example.basicfiredatabase.utils.ImageUploadService
import com.example.basicfiredatabase.utils.TranslationHelper
import com.example.basicfiredatabase.utils.TranslationHelper.pasteFromClipboard
import com.example.basicfiredatabase.utils.HorizontalSpaceItemDecoration
import com.google.firebase.ktx.Firebase
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.remoteconfig.ktx.remoteConfig
import com.google.firebase.remoteconfig.ktx.remoteConfigSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

class AddUserFragment : Fragment(R.layout.fragment_add_user) {

    private var _binding: FragmentAddUserBinding? = null
    private val binding get() = _binding!!

    private lateinit var imageAdapter: ImageListAdapter
    private val db by lazy { Firebase.firestore }

    // selections and uploaded results
    private val selectedImageUris = mutableListOf<Uri>()
    private val uploadedImages = mutableListOf<Map<String, String>>() // url + public_id

    // remote config values
    private var imageUploadUrl: String? = null
    private var imageCloudApiKey: String? = null
    private var apiKeyForTranslate: String? = null
    private var zuluUrl: String? = null
    private var afrikaansUrl: String? = null

    // translation debounce
    private var translationJob: Job? = null

    // image picker: delegates to handlePickedImages
    private val pickImagesLauncher =
        registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            handlePickedImages(uris)
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentAddUserBinding.bind(view)

        //Prevents state form being saved
        binding.recyclerSelectedImages.isSaveEnabled = false


        // --- high level orchestration only ---
        setupRecycler()
        setupPasteButtons()
        setupSpinner()
        setupFocusAndScrolling()
        setupTranslationWatcher()
        setupVerifyButtons(view)
        setupRemoteConfig()
        setupDateTimePickers()
        setupPickAndSaveHandlers()

        setupMapLinkToggle()

        // initial UI
        updateImageCountUI(binding.tvImagesCount, binding.btnPickImages)
    }

    private fun dpToPx(dp: Int) = (dp * resources.displayMetrics.density).toInt()


    // -----------------------
    // UI setup helpers
    // -----------------------
    private fun setupRecycler() {
        val recycler = binding.recyclerSelectedImages
        imageAdapter = ImageListAdapter(onRemove = { pos ->
            if (pos in selectedImageUris.indices) {
                selectedImageUris.removeAt(pos)
                if (pos < uploadedImages.size) uploadedImages.removeAt(pos)
                imageAdapter.submitList(selectedImageUris.toList())
                updateImageCountUI(binding.tvImagesCount, binding.btnPickImages)
            }
        })
        recycler.adapter = imageAdapter


        // horizontal layout
        val lm = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        recycler.layoutManager = lm
        recycler.setHasFixedSize(true)

        // padding + spacing
        val pad = dpToPx(8)
        recycler.setPadding(pad, pad, pad, pad)
        recycler.clipToPadding = false
        recycler.addItemDecoration(HorizontalSpaceItemDecoration(dpToPx(6)))
    }

    private fun setupPasteButtons() {
        binding.btnPasteSecondary.setOnClickListener {
            pasteFromClipboard(requireContext(), binding.etDescriptionSecondary)
        }
        binding.btnPasteTertiary.setOnClickListener {
            pasteFromClipboard(requireContext(), binding.etDescriptionTertiary)
        }

        // Map-link paste button (works the same way as your other paste buttons)
        binding.btnPasteMapLink.setOnClickListener {
            pasteFromClipboard(requireContext(), binding.etMapLink)
        }
    }

    private fun setupSpinner() {
        binding.spinnerEventType.adapter =
            ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, TYPES.toList())
    }


    private fun setupFocusAndScrolling() {
        // local vars for scrolling
        var lastImeHeight = 0
        var lastNavHeight = 0
        var currentFocusedView: View? = null


        fun scrollToView(scrollView: ScrollView, view: View, extra: Int = 0) {
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

        // keep scrollview properties
        binding.scrollView.isFillViewport = true
        binding.scrollView.isFocusableInTouchMode = true
        binding.scrollView.clipToPadding = false

        val focusableFields = listOf(
            binding.etTitle,
            binding.etDescriptionPrimary,
            binding.etDescriptionSecondary,
            binding.etDescriptionTertiary,
            binding.etDuration,
            binding.etLocation,
            binding.etMapLink
        )

        for (field in focusableFields) {
            field.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    currentFocusedView = v
                    v.postDelayed({ scrollToView(binding.scrollView, v, dpToPx(12)) }, 120)
                }
            }
            field.setOnClickListener { v ->
                currentFocusedView = v
                v.post { scrollToView(binding.scrollView, v, dpToPx(12)) }
            }
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.scrollView) { v, insets ->
            val navBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            lastNavHeight = navBottom
            lastImeHeight = imeBottom

            val extraGap = dpToPx(4)
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, navBottom + imeBottom + extraGap)

            val lp = binding.layoutActionButtons.layoutParams
            if (lp is ViewGroup.MarginLayoutParams) {
                val desiredBottomMargin = navBottom + dpToPx(6)
                if (lp.bottomMargin != desiredBottomMargin) {
                    lp.bottomMargin = desiredBottomMargin
                    binding.layoutActionButtons.layoutParams = lp
                }
            }

            if (imeBottom > 0) {
                currentFocusedView?.let { fv ->
                    v.post { scrollToView(binding.scrollView, fv, dpToPx(12)) }
                }
            }
            insets
        }
    }

    // -----------------------
    // Image picker handlers
    // -----------------------
    private fun handlePickedImages(uris: List<Uri>?) {
        if (uris == null || uris.isEmpty()) return

        // Filter duplicates (don't re-add URIs already selected)
        val newUnique = uris.filter { it !in selectedImageUris }
        if (newUnique.isEmpty()) {
            Toast.makeText(requireContext(), "Selected images already added", Toast.LENGTH_SHORT).show()
            return
        }

        // How many more can we add?
        val remaining = 10 - selectedImageUris.size
        if (remaining <= 0) {
            Toast.makeText(requireContext(), "Maximum 10 images allowed", Toast.LENGTH_SHORT).show()
            return
        }

        val toAdd = newUnique.take(remaining)
        selectedImageUris.addAll(toAdd)

        // Inform user if we dropped some selected items
        if (newUnique.size > toAdd.size) {
            Toast.makeText(requireContext(),
                "Only ${toAdd.size} added — maximum 10 images allowed",
                Toast.LENGTH_SHORT).show()
        }

        imageAdapter.submitList(selectedImageUris.toList()) {
            // run after DiffUtil commit — force RecyclerView to re-layout and redraw
            binding.recyclerSelectedImages.post {
                // Option A: force a full refresh (safe for <=10 items)
                imageAdapter.notifyDataSetChanged()

                // Optionally: ensure decorations/layout updated
                binding.recyclerSelectedImages.invalidateItemDecorations()
                binding.recyclerSelectedImages.requestLayout()
            }
        }
        binding.tvImagesCount.text = "${selectedImageUris.size} / 10 images selected"
        updateImageCountUI(binding.tvImagesCount, binding.btnPickImages)
    }

    // -----------------------
    // Translations (debounced)
    // -----------------------
    private fun setupTranslationWatcher() {
        val etPrimary = binding.etDescriptionPrimary
        val pbSecondary = binding.pbSecondaryTranslate
        val pbTertiary = binding.pbTertiaryTranslate
        val etSecondary = binding.etDescriptionSecondary
        val etTertiary = binding.etDescriptionTertiary

        etPrimary.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun afterTextChanged(s: Editable?) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                translationJob?.cancel()
                val currentText = s?.toString() ?: ""
                if (currentText.trim().isEmpty()) {
                    etSecondary.setText("")
                    etTertiary.setText("")
                    return
                }

                translationJob = lifecycleScope.launch {
                    try {
                        delay(3000) // debounce
                        val currentZuluUrl = zuluUrl
                        val currentAfrUrl = afrikaansUrl
                        val currentApiKey = apiKeyForTranslate
                        if (currentZuluUrl.isNullOrBlank() || currentAfrUrl.isNullOrBlank()) return@launch
                        if (currentApiKey.isNullOrBlank()) {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(requireContext(), "Translate API key missing", Toast.LENGTH_SHORT).show()
                            }
                            return@launch
                        }
                        withContext(Dispatchers.Main) {
                            pbSecondary.visibility = View.VISIBLE
                            pbTertiary.visibility = View.VISIBLE
                            etSecondary.setText("Translating...")
                            etTertiary.setText("Translating...")
                        }

                        val zuluDeferred = async(Dispatchers.IO) {
                            withTimeoutOrNull(10_000) {
                                TranslationHelper.translateText(currentText, currentZuluUrl!!, currentApiKey!!)
                            }
                        }
                        val afDeferred = async(Dispatchers.IO) {
                            withTimeoutOrNull(10_000) {
                                TranslationHelper.translateText(currentText, currentAfrUrl!!, currentApiKey!!)
                            }
                        }

                        val zuluResult = try { zuluDeferred.await() } catch (e: Exception){
                            withContext(Dispatchers.Main) {
                                Toast.makeText(requireContext(), "Zulu translation failed: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                            null
                        }

                        val afrResult = try { afDeferred.await() } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(requireContext(), "Afrikaans translation failed: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                            null
                        }

                        withContext(Dispatchers.Main) {
                            etSecondary.setText(zuluResult ?: "Translation timed out")
                            etTertiary.setText(afrResult ?: "Translation timed out")
                        }
                    } catch (ex: CancellationException) {
                        withContext(Dispatchers.Main) {
                            pbSecondary.visibility = View.GONE
                            pbTertiary.visibility = View.GONE
                        }
                        throw ex
                    } catch (ex: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(requireContext(), "Translation error: ${ex.message}", Toast.LENGTH_LONG).show()
                        }
                    } finally {
                        withContext(Dispatchers.Main) {
                            pbSecondary.visibility = View.GONE
                            pbTertiary.visibility = View.GONE
                        }
                    }
                }
            }
        })
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
                "uploadImage_url" to " https://ic-api-holder.onrender.com/upload",
                "translate_zulu_url" to " https://ic-api-holder.onrender.com/translate/zu",
                "translate_afrikaans_url" to " https://ic-api-holder.onrender.com/translate/af"
            )
        )

        remoteConfig.fetchAndActivate()
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    imageUploadUrl = remoteConfig.getString("uploadImage_url")
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
    // Date/time pickers
    // -----------------------
    private fun setupDateTimePickers() {
        binding.tvEventDate.setOnClickListener {
            val c = Calendar.getInstance()
            val dp = DatePickerDialog(
                requireContext(),
                { _, year, month, dayOfMonth ->
                    val cal = Calendar.getInstance().apply { set(year, month, dayOfMonth) }
                    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                    binding.tvEventDate.text = sdf.format(cal.time)
                },
                c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)
            )
            dp.show()
        }

        binding.tvEventTime.setOnClickListener {
            val c = Calendar.getInstance()
            val tp = TimePickerDialog(
                requireContext(),
                { _, hourOfDay, minute ->
                    binding.tvEventTime.text = String.format(Locale.US, "%02d:%02d", hourOfDay, minute)
                },
                c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), true
            )
            tp.show()
        }
    }

    //Focus on image being uploaded
    private fun focusOnPosition(index: Int) {
        val recycler = binding.recyclerSelectedImages
        recycler.post {
            val lm = recycler.layoutManager as? LinearLayoutManager ?: return@post
            // item width must match the item xml width (160dp). Adjust if you change it.
            val itemWidthPx = dpToPx(160)
            val centerOffset = recycler.width / 2 - itemWidthPx / 2
            // This will place the item roughly centered
            lm.scrollToPositionWithOffset(index, centerOffset)
        }
    }



    // -----------------------
    // Pick & Save button wiring
    // -----------------------
    private fun setupPickAndSaveHandlers() {
        binding.btnPickImages.setOnClickListener { pickImagesLauncher.launch("image/*") }


        binding.btnCancel.setOnClickListener {
            // Make sure UI is clear (hide progress)
            binding.pbSave.visibility = View.GONE

            // cancel any active translation debounce so it doesn't try to update UI while fragment closing
            translationJob?.cancel()

            // pop this fragment off the back stack
            parentFragmentManager.popBackStack()
        }


        binding.btnSave.setOnClickListener {

            val title = binding.etTitle.text?.toString()?.trim() ?: ""
            val eventType = binding.spinnerEventType.selectedItem?.toString() ?: "Other"
            val descPrimaryTxt = binding.etDescriptionPrimary.text?.toString()?.trim() ?: ""
            val descSecondaryTxt = binding.etDescriptionSecondary.text?.toString()?.trim() ?: ""
            val descTertiaryTxt = binding.etDescriptionTertiary.text?.toString()?.trim() ?: ""
            val date = binding.tvEventDate.text?.toString()?.trim() ?: ""
            val time = binding.tvEventTime.text?.toString()?.trim() ?: ""
            val location = binding.etLocation.text?.toString()?.trim() ?: ""
            val durationMinutes = binding.etDuration.text?.toString()?.toIntOrNull()
            val isUpcoming = binding.switchUpcoming.isChecked

            // map link data
            val (includeMapLink, mapLink) = getMapLinkData()

            if (title.isEmpty() || descPrimaryTxt.isEmpty() || descSecondaryTxt.isEmpty() || descTertiaryTxt.isEmpty() || location.isEmpty()) {
                Toast.makeText(requireContext(), "Please fill in all fields", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (date.isEmpty() || date == "Select date") {
                Toast.makeText(requireContext(), "Select event date", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (time.isEmpty() || time == "Select time") {
                Toast.makeText(requireContext(), "Select event time", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (durationMinutes == null) {
                Toast.makeText(requireContext(), "Enter a valid duration", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (selectedImageUris.isEmpty()) {
                Toast.makeText(requireContext(), "Please select at least one image", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (includeMapLink && mapLink.isNullOrEmpty()) {
                Toast.makeText(requireContext(), "Please paste the Google Maps link or uncheck \"Include Google Maps link\"", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            val descriptions = mutableMapOf<String, String>().apply {
                put("primary", descPrimaryTxt)
                put("secondary", descSecondaryTxt)
                put("tertiary", descTertiaryTxt)
            }

            // launch upload-and-save flow
            binding.btnPickImages.isEnabled = false
            binding.btnSave.isEnabled = false
            binding.btnCancel.isEnabled = false


            lifecycleScope.launchWhenStarted {
                try {
                    // ensure upload if needed
                    if (selectedImageUris.size != uploadedImages.size) {
                        if (imageUploadUrl.isNullOrEmpty()) {
                            Toast.makeText(requireContext(), "Backend URL not loaded", Toast.LENGTH_SHORT).show()
                            return@launchWhenStarted
                        }
                        val result = uploadSelectedImages { idx, visible ->
                            // report per-image upload state to adapter on main
                            lifecycleScope.launch {
                                imageAdapter.setUploading(idx, visible)
                                if (visible) {
                                    focusOnPosition(idx) // scroll into view only when uploading starts
                                }                            }
                        }
                        uploadedImages.clear()
                        uploadedImages.addAll(result)
                    }

                    // now save
                    saveUser(
                        title = title,
                        eventType = eventType,
                        descriptions = descriptions,
                        date = date,
                        time = time,
                        durationMinutes = durationMinutes,
                        location = location,
                        isUpcoming = isUpcoming,
                        includeMapLink = includeMapLink,
                        mapLink = mapLink
                    )
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "Upload error: ${e.message}", Toast.LENGTH_LONG).show()
                } finally {
                    binding.btnPickImages.isEnabled = true
                    binding.btnSave.isEnabled = true
                    binding.btnCancel.isEnabled = true

                }
            }
        }
    }

    // -----------------------
    // Upload function (suspending)
    // -----------------------
    private suspend fun uploadSelectedImages(
        onProgress: (idx: Int, visible: Boolean) -> Unit
    ): List<Map<String, String>> {
        val pairs = selectedImageUris.mapIndexed { idx, uri -> idx.toString() to uri }

        return try {
            ImageUploadService.uploadUris(
                requireContext(),
                pairs,
                imageUploadUrl!!,
                imageCloudApiKey ?: "",
                onProgress = { key, visible ->
                    val idx = key.toIntOrNull()
                    if (idx != null) onProgress(idx, visible)
                }
            )
        } catch (e: Exception) {
            // Show a toast to let the user know
            withContext(Dispatchers.Main) {
                Toast.makeText(requireContext(), "Upload failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
            emptyList()
        } finally {
            // always clear spinners if anything went wrong
            withContext(Dispatchers.Main) {
                selectedImageUris.indices.forEach { onProgress(it, false) }
            }
        }
    }


    // -----------------------
    // Save final event to Firestore
    // -----------------------
    private fun saveUser(
        title: String,
        eventType: String,
        descriptions: Map<String, String>,
        date: String,
        time: String,
        durationMinutes: Int,
        location: String,
        isUpcoming: Boolean,
        includeMapLink: Boolean,
        mapLink: String?
    ) {
        val event = hashMapOf<String, Any?>(
            "title" to title,
            "event_type" to eventType,
            "descriptions" to descriptions,
            "date" to date,
            "time" to time,
            "duration_minutes" to durationMinutes,
            "images" to uploadedImages,
            "location" to location,
            "is_upcoming" to isUpcoming,
            // new fields:
            "include_map_link" to includeMapLink,
            "map_link" to mapLink
        )

        binding.pbSave.visibility = View.VISIBLE

        db.collection("users")
            .add(event)
            .addOnSuccessListener { docRef ->
                Toast.makeText(requireContext(), "Saved (id=${docRef.id})", Toast.LENGTH_SHORT).show()

                // reset UI
                binding.etTitle.text?.clear()
                binding.etDescriptionPrimary.text?.clear()
                binding.etDescriptionSecondary.text?.clear()
                binding.etDescriptionTertiary.text?.clear()
                binding.tvEventDate.text = "Select date"
                binding.tvEventTime.text = "Select time"
                binding.etDuration.text?.clear()
                binding.etLocation.text?.clear()
                binding.switchUpcoming.isChecked = true

                // reset map link UI
                binding.cbIncludeMap.isChecked = false
                binding.tilMapLink.visibility = View.GONE
                binding.etMapLink.text?.clear()

                selectedImageUris.clear()
                uploadedImages.clear()
                imageAdapter.submitList(emptyList())
                binding.tvImagesCount.text = "No images selected"

                parentFragmentManager.popBackStack()
            }
            .addOnFailureListener { e ->
                Toast.makeText(requireContext(), "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
                binding.pbSave.visibility = View.GONE
            }
    }

    fun updateImageCountUI(tv: TextView, btnPick: Button) {
        tv.text = if (selectedImageUris.isEmpty()) "No images selected" else "${selectedImageUris.size} / 10 images selected"
        btnPick.isEnabled = selectedImageUris.size < 10
    }


    private fun setupVerifyButtons(view: View) {

        binding.tvVerifySecondary.setOnClickListener {
            TranslationHelper.openGoogleTranslateFromPrimary(
                this@AddUserFragment,
                binding.etDescriptionPrimary.text?.toString().orEmpty(),
                "zu"
            )
        }

        binding.tvVerifyTertiary.setOnClickListener {
            TranslationHelper.openGoogleTranslateFromPrimary(
                this@AddUserFragment,
                binding.etDescriptionPrimary.text?.toString().orEmpty(),
                "af"
            )
        }
    }


    private fun setupMapLinkToggle() {
        // When checkbox toggles, show/hide the entire row (input + paste + progress)
        binding.cbIncludeMap.setOnCheckedChangeListener { _, isChecked ->
            binding.layoutMapLinkRow.visibility = if (isChecked) View.VISIBLE else View.GONE

            if (!isChecked) {
                // clear input and any previous error when user unchecks
                binding.etMapLink.text?.clear()
                binding.tilMapLink.error = null
            }
        }
    }


    /**
     * Returns a pair: (includeMapLinkFlag, mapLinkOrNull)
     * If the checkbox isn't checked, mapLinkOrNull is null.
     * If checkbox checked but field empty, returns (true, null) — caller should validate.
     */
    private fun getMapLinkData(): Pair<Boolean, String?> {
        val include = binding.cbIncludeMap.isChecked
        val raw = binding.etMapLink.text?.toString()?.trim()
        val link = if (!raw.isNullOrEmpty()) raw else null
        return Pair(include, link)
    }




    override fun onDestroyView() {
        super.onDestroyView()
        binding.recyclerSelectedImages.adapter = null
        _binding = null
        translationJob?.cancel()
        selectedImageUris.clear()
        imageAdapter.submitList(emptyList())
    }

    companion object {
        private val TYPES = arrayOf(
            "Community Feeding Program",
            "Back-to-School Drive",
            "Children’s Health and Wellness Fair",
            "Sports and Recreation Day",
            "Community Clean-Up and Beautification",
            "Food and Hygiene Pack Distribution",
            "Emergency Relief Fundraising Event"
        )
    }
}
