package com.example.basicfiredatabase.fragments

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.example.basicfiredatabase.R
import com.example.basicfiredatabase.adapters.ImageListAdapter
import com.example.basicfiredatabase.databinding.FragmentAddUserBinding
import com.example.basicfiredatabase.utils.ImageUploadService
import com.example.basicfiredatabase.utils.TranslationHelper
import com.example.basicfiredatabase.utils.TranslationHelper.pasteFromClipboard
import com.google.android.material.textfield.TextInputEditText
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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

class AddUserFragment : Fragment(R.layout.fragment_add_user) {

    private var _binding: FragmentAddUserBinding? = null
    private val binding get() = _binding!!

    // handling screen size preventing overlay
    private var lastImeHeight = 0
    private var lastNavHeight = 0
    private var currentFocusedView: View? = null

    private lateinit var imageAdapter: ImageListAdapter

    private val db by lazy { Firebase.firestore }

    // selections and uploaded results
    private val selectedImageUris = mutableListOf<Uri>()
    private val uploadedImages = mutableListOf<Map<String, String>>() // url + public_id

    // firebase remote config
    private var imageUploadUrl: String? = null
    private var imageCloudApiKey: String? = null
    private var apiKeyForTranslate: String? = null
    private var zuluUrl: String? = null
    private var afrikaansUrl: String? = null

    // pick multiple images
    private val pickImagesLauncher =
        registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            if (uris == null || uris.isEmpty()) return@registerForActivityResult

            // Filter duplicates (don't re-add URIs already selected)
            val newUnique = uris.filter { it !in selectedImageUris }

            if (newUnique.isEmpty()) {
                Toast.makeText(requireContext(), "Selected images already added", Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }

            // How many more can we add?
            val remaining = 10 - selectedImageUris.size
            if (remaining <= 0) {
                Toast.makeText(requireContext(), "Maximum 10 images allowed", Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }

            val toAdd = newUnique.take(remaining)
            selectedImageUris.addAll(toAdd)

            // Inform user if we dropped some selected items
            if (newUnique.size > toAdd.size) {
                Toast.makeText(requireContext(),
                    "Only ${toAdd.size} added — maximum 10 images allowed",
                    Toast.LENGTH_SHORT).show()
            }

            // Update adapter & count
            imageAdapter.submitList(selectedImageUris.toList())
            binding.tvImagesCount.text = "${selectedImageUris.size} / 10 images selected"
        }

    // --- TRANSLATION ---
    private var translationJob: Job? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentAddUserBinding.bind(view)

        // Vars to track IME + nav bar sizes (local)
        var lastImeHeight = 0
        var lastNavHeight = 0
        var currentFocusedView: View? = null

        fun dpToPx(dp: Int): Int {
            return (dp * resources.displayMetrics.density).toInt()
        }

        // helper to scroll a child into visible area of ScrollView
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

        // Ensure ScrollView stretches properly
        binding.scrollView.isFillViewport = true
        binding.scrollView.isFocusableInTouchMode = true
        binding.scrollView.clipToPadding = false

        // Inputs
        val etTitle = binding.etTitle
        val spinnerType = binding.spinnerEventType
        val etDescPrimary = binding.etDescriptionPrimary
        val etDescSecondary = binding.etDescriptionSecondary
        val etDescTertiary = binding.etDescriptionTertiary
        val btnPasteSecondary = binding.btnPasteSecondary
        val btnPasteTertiary = binding.btnPasteTertiary
        val tvDate = binding.tvEventDate
        val tvTime = binding.tvEventTime
        val etDuration = binding.etDuration
        val etLocation = binding.etLocation
        val switchUpcoming = binding.switchUpcoming
        val btnPick = binding.btnPickImages
        val btnSave = binding.btnSave
        val tvImagesCount = binding.tvImagesCount

        val pbSecondary = binding.pbSecondaryTranslate
        val pbTertiary = binding.pbTertiaryTranslate

        // RecyclerView setup for images
        val recycler = binding.recyclerSelectedImages
        imageAdapter = ImageListAdapter(onRemove = { pos ->
            if (pos in selectedImageUris.indices) {
                selectedImageUris.removeAt(pos)
                if (pos < uploadedImages.size) uploadedImages.removeAt(pos)
                imageAdapter.submitList(selectedImageUris.toList())
                tvImagesCount.text = "${selectedImageUris.size} / 10 images selected"
            }
        })
        recycler.adapter = imageAdapter
        recycler.layoutManager = GridLayoutManager(requireContext(), 3)

        btnPasteSecondary.setOnClickListener {
            pasteFromClipboard(requireContext(), binding.etDescriptionSecondary)
        }

        btnPasteTertiary.setOnClickListener {
            pasteFromClipboard(requireContext(), binding.etDescriptionTertiary)
        }

        // Focus listeners for bottom fields
        val focusableFields = listOf(
            etTitle,
            etDescPrimary,
            etDescSecondary,
            etDescTertiary,
            etDuration,
            etLocation
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

        // Handle insets (IME + nav) on ScrollView
        ViewCompat.setOnApplyWindowInsetsListener(binding.scrollView) { v, insets ->
            val navBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            lastNavHeight = navBottom
            lastImeHeight = imeBottom

            val extraGap = dpToPx(4)
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, navBottom + imeBottom + extraGap)

            // Always give Save button margin above nav bar
            val lp = btnSave.layoutParams
            if (lp is ViewGroup.MarginLayoutParams) {
                val desiredBottomMargin = navBottom + dpToPx(6)
                if (lp.bottomMargin != desiredBottomMargin) {
                    lp.bottomMargin = desiredBottomMargin
                    btnSave.layoutParams = lp
                }
            }

            // If keyboard visible, ensure focused field is shown
            if (imeBottom > 0) {
                currentFocusedView?.let { fv ->
                    v.post { scrollToView(binding.scrollView, fv, dpToPx(12)) }
                }
            }
            insets
        }

        // Spinner setup
        val types = listOf(
            "Community Feeding Program",
            "Back-to-School Drive",
            "Children’s Health and Wellness Fair",
            "Sports and Recreation Day",
            "Community Clean-Up and Beautification",
            "Food and Hygiene Pack Distribution",
            "Emergency Relief Fundraising Event"
        )
        spinnerType.adapter =
            ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, types)

        // ALWAYS show all three description fields (user requested)
        etDescPrimary.visibility = View.VISIBLE
        etDescSecondary.visibility = View.VISIBLE
        etDescTertiary.visibility = View.VISIBLE

        // TextWatcher + debounce for translations
        etDescPrimary.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                // cancel previous pending translate job
                translationJob?.cancel()

                val currentText = s?.toString() ?: ""

                // don't start a job for empty text; clear translations instead
                if (currentText.trim().isEmpty()) {
                    etDescSecondary.setText("")
                    etDescTertiary.setText("")
                    return
                }

                // start a new job with debounce
                translationJob = lifecycleScope.launch {
                    try {
                        delay(3000) // debounce

                        val currentZuluUrl = zuluUrl
                        val currentAfrUrl = afrikaansUrl
                        val currentApiKey = apiKeyForTranslate

                        // ensure endpoints exist
                        if (currentZuluUrl.isNullOrBlank() || currentAfrUrl.isNullOrBlank()) {
                            Log.w("AddUserFragment", "Translation endpoints not configured — skipping auto-translate")
                            return@launch
                        }

                        // API key is required by your backend; if missing, stop and warn
                        if (currentApiKey.isNullOrBlank()) {
                            Log.w("AddUserFragment", "Translate API key missing — cannot proceed with automatic translations")
                            withContext(Dispatchers.Main) {
                                Toast.makeText(requireContext(), "Translate API key missing (check Remote Config)", Toast.LENGTH_SHORT).show()
                            }
                            return@launch
                        }

                        // show progress placeholders immediately on main thread
                        withContext(Dispatchers.Main) {
                            pbSecondary.visibility = View.VISIBLE
                            pbTertiary.visibility = View.VISIBLE
                            etDescSecondary.setText("Translating...")
                            etDescTertiary.setText("Translating...")
                        }

                        // Kick off both translations concurrently using TranslationHelper
                        val zuluDeferred = async(Dispatchers.IO) {
                            TranslationHelper.translateText(currentText, currentZuluUrl!!, currentApiKey!!)
                        }
                        val afDeferred = async(Dispatchers.IO) {
                            TranslationHelper.translateText(currentText, currentAfrUrl!!, currentApiKey!!)
                        }

                        // await results (exceptions will bubble to the catch)
                        val zuluResult = try { zuluDeferred.await() } catch (e: Exception) { null }
                        val afResult = try { afDeferred.await() } catch (e: Exception) { null }

                        withContext(Dispatchers.Main) {
                            if (zuluResult != null) etDescSecondary.setText(zuluResult) else etDescSecondary.setText("")
                            if (afResult != null) etDescTertiary.setText(afResult) else etDescTertiary.setText("")
                        }
                    } catch (ex: CancellationException) {
                        // user kept typing; ignore but ensure UI reset
                        Log.d("AddUserFragment", "Translation job cancelled")
                        withContext(Dispatchers.Main) {
                            pbSecondary.visibility = View.GONE
                            pbTertiary.visibility = View.GONE
                        }
                        throw ex
                    } catch (ex: Exception) {
                        Log.w("AddUserFragment", "Translation job failed", ex)
                        withContext(Dispatchers.Main) {
                            pbSecondary.visibility = View.GONE
                            pbTertiary.visibility = View.GONE
                        }
                    } finally {
                        // ALWAYS hide progress bars after everything (success, failure or cancellation)
                        withContext(Dispatchers.Main) {
                            pbSecondary.visibility = View.GONE
                            pbTertiary.visibility = View.GONE
                        }
                    }
                }
            }

            override fun afterTextChanged(s: Editable?) {}
        })

        // Verify links - open Google Translate with primary text
        val tvVerifySecondary = requireView().findViewById<TextView>(R.id.tv_verify_secondary)
        val tvVerifyTertiary = requireView().findViewById<TextView>(R.id.tv_verify_tertiary)

        tvVerifySecondary.setOnClickListener {
            TranslationHelper.openGoogleTranslateFromPrimary(
                this@AddUserFragment,
                binding.etDescriptionPrimary.text?.toString().orEmpty(),
                "zu"
            )
        }

        tvVerifyTertiary.setOnClickListener {
            TranslationHelper.openGoogleTranslateFromPrimary(
                this@AddUserFragment,
                binding.etDescriptionPrimary.text?.toString().orEmpty(),
                "af"
            )
        }

        // Remote Config setup
        val remoteConfig = Firebase.remoteConfig
        val configSettings = remoteConfigSettings {
            minimumFetchIntervalInSeconds = 3600
        }
        remoteConfig.setConfigSettingsAsync(configSettings)

        // defaults
        remoteConfig.setDefaultsAsync(
            mapOf(
                "uploadImage_url" to "https://cloudinaryserver.onrender.com/upload",
                "translate_zulu_url" to "https://mymemoryserver.onrender.com/translate/zu",
                "translate_afrikaans_url" to "https://mymemoryserver.onrender.com/translate/af"
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
                    Log.d("AddUserFragment", "RemoteConfig loaded: zulu=${zuluUrl.takeIf { !it.isNullOrBlank() }}, af=${afrikaansUrl.takeIf { !it.isNullOrBlank() }}, apiKeyPresent=${!apiKeyForTranslate.isNullOrBlank()}")
                    Toast.makeText(requireContext(), "Remote config loaded (translations ${if (!zuluUrl.isNullOrBlank() && !afrikaansUrl.isNullOrBlank()) "ready" else "missing endpoints"})", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "Failed to fetch Remote Config", Toast.LENGTH_SHORT).show()
                    Log.w("AddUserFragment", "RemoteConfig fetch failed")
                }
            }

        // Date picker
        tvDate.setOnClickListener {
            val c = Calendar.getInstance()
            val dp = DatePickerDialog(
                requireContext(),
                { _, year, month, dayOfMonth ->
                    val cal = Calendar.getInstance()
                    cal.set(year, month, dayOfMonth)
                    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                    tvDate.text = sdf.format(cal.time)
                },
                c.get(Calendar.YEAR),
                c.get(Calendar.MONTH),
                c.get(Calendar.DAY_OF_MONTH)
            )
            dp.show()
        }

        // Time picker
        tvTime.setOnClickListener {
            val c = Calendar.getInstance()
            val tp = TimePickerDialog(
                requireContext(),
                { _, hourOfDay, minute ->
                    val formatted = String.format(Locale.US, "%02d:%02d", hourOfDay, minute)
                    tvTime.text = formatted
                },
                c.get(Calendar.HOUR_OF_DAY),
                c.get(Calendar.MINUTE),
                true
            )
            tp.show()
        }

        btnPick.setOnClickListener { pickImagesLauncher.launch("image/*") }

        btnSave.setOnClickListener {
            // Ensure button itself visible above keyboard
            scrollToView(binding.scrollView, btnSave, dpToPx(12))

            val title = etTitle.text?.toString()?.trim() ?: ""
            if (title.isEmpty()) {
                Toast.makeText(requireContext(), "Enter event title", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val eventType = spinnerType.selectedItem?.toString() ?: "Other"

            // collect descriptions
            val descPrimaryTxt = etDescPrimary.text?.toString()?.trim() ?: ""
            val descSecondaryTxt = etDescSecondary.text?.toString()?.trim() ?: ""
            val descTertiaryTxt = etDescTertiary.text?.toString()?.trim() ?: ""
            val descriptions = mutableMapOf<String, String>()
            if (descPrimaryTxt.isNotEmpty()) descriptions["primary"] = descPrimaryTxt
            if (descSecondaryTxt.isNotEmpty()) descriptions["secondary"] = descSecondaryTxt
            if (descTertiaryTxt.isNotEmpty()) descriptions["tertiary"] = descTertiaryTxt

            val date = tvDate.text?.toString()?.trim() ?: ""
            if (date.isEmpty()) {
                Toast.makeText(requireContext(), "Select event date", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val time = tvTime.text?.toString()?.trim() ?: ""
            if (time.isEmpty()) {
                Toast.makeText(requireContext(), "Select event time", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val durationMinutes = etDuration.text?.toString()?.toIntOrNull()
            val location = etLocation.text?.toString()?.trim()
            val isUpcoming = switchUpcoming.isChecked

            if (selectedImageUris.size != uploadedImages.size) {
                if (selectedImageUris.isEmpty()) {
                    saveUser(title, eventType, descriptions, date, time, durationMinutes, location, isUpcoming)
                    return@setOnClickListener
                }

                if (imageUploadUrl.isNullOrEmpty()) {
                    Toast.makeText(requireContext(), "Backend URL not loaded", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                btnPick.isEnabled = false
                btnSave.isEnabled = false

                lifecycleScope.launchWhenStarted {
                    try {
                        // build pairs of (key, uri). key = index as string so we can map back to Int in onProgress
                        val pairs = selectedImageUris.mapIndexed { idx, uri -> idx.toString() to uri }

                        val result = ImageUploadService.uploadUris(
                            requireContext(),
                            pairs,
                            imageUploadUrl!!,
                            imageCloudApiKey!!,
                            onProgress = { key, visible ->
                                val idx = key.toIntOrNull()
                                if (idx != null) {
                                    // ensure UI update happens on main thread
                                    lifecycleScope.launch {
                                        imageAdapter.setUploading(idx, visible)
                                    }
                                }
                            }
                        )

                        // success -> store uploaded images and save
                        uploadedImages.clear()
                        uploadedImages.addAll(result)
                        saveUser(title, eventType, descriptions, date, time, durationMinutes, location, isUpcoming)
                    } catch (e: Exception) {
                        Toast.makeText(requireContext(), "Upload error: ${e.message}", Toast.LENGTH_LONG).show()
                    } finally {
                        btnPick.isEnabled = true
                        btnSave.isEnabled = true
                    }
                }
            } else {
                saveUser(title, eventType, descriptions, date, time, durationMinutes, location, isUpcoming)
            }
        }
    } // end onViewCreated

    private fun saveUser(
        title: String,
        eventType: String,
        descriptions: Map<String, String>,
        date: String,
        time: String,
        durationMinutes: Int?,
        location: String?,
        isUpcoming: Boolean
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
            "is_upcoming" to isUpcoming
        )

        db.collection("users")
            .add(event)
            .addOnSuccessListener { docRef ->
                Toast.makeText(requireContext(), "Saved (id=${docRef.id})", Toast.LENGTH_SHORT).show()

                // reset UI fields
                binding.etTitle.text?.clear()
                binding.etDescriptionPrimary.text?.clear()
                binding.etDescriptionSecondary.text?.clear()
                binding.etDescriptionTertiary.text?.clear()
                binding.tvEventDate.text = "Select date"
                binding.tvEventTime.text = "Select time"
                binding.etDuration.text?.clear()
                binding.etLocation.text?.clear()
                binding.switchUpcoming.isChecked = true

                // clear images and adapter
                selectedImageUris.clear()
                uploadedImages.clear()
                imageAdapter.submitList(emptyList())
                binding.tvImagesCount.text = "No images selected"
            }
            .addOnFailureListener { e ->
                Toast.makeText(requireContext(), "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    // updateSelectedUI removed on purpose — RecyclerView + adapter now handle all view work

    override fun onDestroyView() {
        super.onDestroyView()
        // avoid leaks
        binding.recyclerSelectedImages.adapter = null
        _binding = null
        translationJob?.cancel()
    }
}
