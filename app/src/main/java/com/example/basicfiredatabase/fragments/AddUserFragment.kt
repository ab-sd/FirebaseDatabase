package com.example.basicfiredatabase.fragments

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.basicfiredatabase.R
import com.example.basicfiredatabase.databinding.FragmentAddUserBinding
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.ktx.Firebase
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.remoteconfig.ktx.remoteConfig
import com.google.firebase.remoteconfig.ktx.remoteConfigSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

class AddUserFragment : Fragment(R.layout.fragment_add_user) {

    private var _binding: FragmentAddUserBinding? = null
    private val binding get() = _binding!!

    //handling screen size preventing overlay
    private var lastImeHeight = 0
    private var lastNavHeight = 0
    private var currentFocusedView: View? = null

    private val db by lazy { Firebase.firestore }

    // selections and uploaded results
    private val selectedImageUris = mutableListOf<Uri>()
    private val uploadedImages = mutableListOf<Map<String, String>>() // url + public_id

    private var imageUploadUrl: String? = null

    // maps image index -> progress spinner view (to show/hide while uploading)
    //lets user know which image is being uploaded as this could take several moments depending on size of image
    private val progressMap = mutableMapOf<Int, ProgressBar>()

    // pick multiple images
    private val pickImagesLauncher =
        registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            // Don't clear here if you want to allow incremental adds; currently we replace selection
//            selectedImageUris.clear()
            if (uris != null && uris.isNotEmpty()) {
                // limit to 10
                selectedImageUris.addAll(uris.take(10))
            }
            updateSelectedUI()
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentAddUserBinding.bind(view)

        // Vars to track IME + nav bar sizes
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
        val tvDate = binding.tvEventDate
        val tvTime = binding.tvEventTime
        val etDuration = binding.etDuration
        val etLocation = binding.etLocation
        val switchUpcoming = binding.switchUpcoming
        val btnPick = binding.btnPickImages
        val btnSave = binding.btnSave
        val tvImagesCount = binding.tvImagesCount
        val llImages = binding.llSelectedImages

        _tvImagesCount = tvImagesCount
        _llImages = llImages

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

        // Description inputs by language preference
        val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val preferredLang = prefs.getString("preferred_event_language", "primary") ?: "primary"
        etDescPrimary.visibility = if (preferredLang == "primary") View.VISIBLE else View.GONE
        etDescSecondary.visibility = if (preferredLang == "secondary") View.VISIBLE else View.GONE
        etDescTertiary.visibility = if (preferredLang == "tertiary") View.VISIBLE else View.GONE

        // Remote Config setup
        val remoteConfig = Firebase.remoteConfig
        val configSettings = remoteConfigSettings {
            minimumFetchIntervalInSeconds = 3600
        }
        remoteConfig.setConfigSettingsAsync(configSettings)
        remoteConfig.setDefaultsAsync(
            mapOf("uploadImage_url" to "https://cloudinaryserver.onrender.com/upload")
        )
        remoteConfig.fetchAndActivate()
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    imageUploadUrl = remoteConfig.getString("uploadImage_url")
                } else {
                    Toast.makeText(requireContext(), "Failed to fetch Remote Config", Toast.LENGTH_SHORT).show()
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
                    uploadSelectedImages(
                        onComplete = { images ->
                            uploadedImages.clear()
                            uploadedImages.addAll(images)
                            saveUser(title, eventType, descriptions, date, time, durationMinutes, location, isUpcoming)
                            btnPick.isEnabled = true
                            btnSave.isEnabled = true
                        },
                        onError = { err ->
                            Toast.makeText(requireContext(), "Upload error: $err", Toast.LENGTH_LONG).show()
                            btnPick.isEnabled = true
                            btnSave.isEnabled = true
                        }
                    )
                }
            } else {
                saveUser(title, eventType, descriptions, date, time, durationMinutes, location, isUpcoming)
            }
        }
    }



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
            "images" to uploadedImages, // list of {url, public_id}
            "location" to location,
            "is_upcoming" to isUpcoming
        )

        db.collection("users")
            .add(event)
            .addOnSuccessListener { docRef ->
                Toast.makeText(requireContext(), "Saved (id=${docRef.id})", Toast.LENGTH_SHORT).show()
                // reset UI fields
                val root = requireView()

                binding.etTitle.text?.clear()
                binding.etDescriptionPrimary.text?.clear()
                binding.etDescriptionSecondary.text?.clear()
                binding.etDescriptionTertiary.text?.clear()
                binding.tvEventDate.text = "Select date"
                binding.tvEventTime.text = "Select time"
                binding.etDuration.text?.clear()
                binding.etLocation.text?.clear()
                binding.switchUpcoming.isChecked = true


                selectedImageUris.clear()
                uploadedImages.clear()
                progressMap.clear()
                updateSelectedUI()
            }
            .addOnFailureListener { e ->
                Toast.makeText(requireContext(), "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }


    private var _tvImagesCount: TextView? = null
    private var _llImages: LinearLayout? = null

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    // --- robust scroll helper (paste inside the fragment class) ---
    private fun scrollToView(scrollView: ScrollView, view: View, extra: Int = 0) {
        // compute view rect in scrollView coordinates and scroll so bottom of view is visible
        val rect = android.graphics.Rect()
        view.getDrawingRect(rect)
        // translate rect to scrollView coordinates
        scrollView.offsetDescendantRectToMyCoords(view, rect)
        // visible height inside the ScrollView after accounting for IME + nav
        val visibleHeight = scrollView.height - lastImeHeight - lastNavHeight
        val targetBottom = rect.bottom
        val gap = extra
        val scrollY = targetBottom - (visibleHeight - gap)
        if (scrollY > 0) {
            scrollView.post { scrollView.smoothScrollTo(0, scrollY) }
        } else {
            // if already visible, a small adjust to avoid flush-to-keyboard feeling
            scrollView.post { scrollView.smoothScrollTo(0, maxOf(0, scrollView.scrollY - dpToPx(4))) }
        }
    }

    private fun updateSelectedUI() {
        val tv = _tvImagesCount ?: return
        val container = _llImages ?: return
        tv.text = "${selectedImageUris.size} image(s) selected"
        container.removeAllViews()
        progressMap.clear()

        // thumbnail size: 160dp
        val sizePx = dpToPx(160)
        val marginPx = dpToPx(6)

        for ((index, uri) in selectedImageUris.withIndex()) {
            val currentIndex = index // capture for lambda
            val frame = FrameLayout(requireContext())
            val frameLp = LinearLayout.LayoutParams(sizePx, sizePx)
            frameLp.setMargins(marginPx, marginPx, marginPx, marginPx)
            frame.layoutParams = frameLp

            // ImageView that fits the entire image inside the square (no crop)
            val iv = ImageView(requireContext())
            val ivLp = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            iv.layoutParams = ivLp
            // show whole image (no cropping). This will letterbox if aspect ratio differs.
            iv.scaleType = ImageView.ScaleType.CENTER_INSIDE

            //loads smaller thumbnail to save on memory space
            iv.setImageBitmap(loadThumbnail(uri))

            // Spinner (center)
            val progress = ProgressBar(requireContext())
            val pSize = dpToPx(36)
            val pLp = FrameLayout.LayoutParams(pSize, pSize)
            pLp.gravity = Gravity.CENTER
            progress.layoutParams = pLp
            progress.isIndeterminate = true
            try {
                progress.indeterminateTintList = resources.getColorStateList(android.R.color.holo_orange_light, null)
            } catch (_: Exception) { }
            progress.visibility = View.GONE
            progressMap[currentIndex] = progress

            // remove button (top-right)
            val btnRemove = ImageButton(requireContext())
            val btnSize = dpToPx(32) // larger to match bigger thumbnail
            val btnLp = FrameLayout.LayoutParams(btnSize, btnSize)
            btnLp.gravity = Gravity.TOP or Gravity.END
            btnRemove.layoutParams = btnLp
            btnRemove.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            btnRemove.background = null
            btnRemove.setOnClickListener {
                // remove item safely
                if (currentIndex < selectedImageUris.size) {
                    selectedImageUris.removeAt(currentIndex)
                    // if this index was already uploaded, remove uploaded mapping too
                    if (currentIndex < uploadedImages.size) uploadedImages.removeAt(currentIndex)
                    // rebuild UI
                    updateSelectedUI()
                }
            }

            // stacking order: image -> spinner -> remove button (so that remove is on top)
            frame.addView(iv)
            frame.addView(progress)
            frame.addView(btnRemove)

            container.addView(frame)
        }
    }

    private suspend fun uploadSelectedImages(
        onComplete: (List<Map<String, String>>) -> Unit,
        onError: (String) -> Unit
    ) {
        withContext(Dispatchers.IO) {
            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()

            val uploaded = mutableListOf<Map<String, String>>()

            try {
                for ((index, uri) in selectedImageUris.withIndex()) {
                    // show spinner for this image on main thread
                    withContext(Dispatchers.Main) {
                        progressMap[index]?.visibility = View.VISIBLE
                    }

                    val input: InputStream = requireContext().contentResolver.openInputStream(uri)
                        ?: throw Exception("Cannot open image #$index")
                    val rawBytes = input.readBytes()
                    input.close()

                    // if large (bytes > 2MB) or max dimension > 1080, compress
                    val bytesToUpload = maybeCompressIfNeeded(rawBytes)
                    val mediaType = "image/*".toMediaTypeOrNull()
                    val fileBody = bytesToUpload.toRequestBody(mediaType)

                    val requestBody = MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("file", "upload_$index.jpg", fileBody)
                        .build()

                    val request = Request.Builder()
                        .url(imageUploadUrl!!) // use Remote Config value (checked earlier)
                        .post(requestBody)
                        .build()

                    val response = client.newCall(request).execute()
                    val responseBody = response.body?.string()
                    if (!response.isSuccessful || responseBody.isNullOrEmpty()) {
                        withContext(Dispatchers.Main) { progressMap[index]?.visibility = View.GONE }
                        throw Exception("HTTP ${response.code}: ${responseBody ?: "empty response"}")
                    }

                    val json = JSONObject(responseBody)
                    val url = json.optString("url", "")
                    val publicId = json.optString("public_id", "")
                    if (url.isBlank() || publicId.isBlank()) {
                        withContext(Dispatchers.Main) { progressMap[index]?.visibility = View.GONE }
                        throw Exception("Missing url or public_id")
                    }

                    uploaded.add(mapOf("url" to url, "public_id" to publicId))

                    // hide spinner after this upload
                    withContext(Dispatchers.Main) { progressMap[index]?.visibility = View.GONE }
                }

                withContext(Dispatchers.Main) { onComplete(uploaded) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressMap.values.forEach { it.visibility = View.GONE }
                    onError(e.message ?: "Upload failed")
                }
            }
        }
    }

    private fun loadThumbnail(uri: Uri, maxSize: Int = 160): Bitmap {
        val input = requireContext().contentResolver.openInputStream(uri) ?: return Bitmap.createBitmap(1,1,Bitmap.Config.ARGB_8888)
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeStream(input, null, options)
        input.close()

        val width = options.outWidth
        val height = options.outHeight
        var scale = 1
        while (width / scale > maxSize || height / scale > maxSize) scale *= 2

        val input2 = requireContext().contentResolver.openInputStream(uri) ?: return Bitmap.createBitmap(1,1,Bitmap.Config.ARGB_8888)
        val opts2 = BitmapFactory.Options().apply { inSampleSize = scale }
        val bitmap = BitmapFactory.decodeStream(input2, null, opts2)
        input2.close()
        return bitmap ?: Bitmap.createBitmap(1,1,Bitmap.Config.ARGB_8888)
    }


    private fun maybeCompressIfNeeded(bytes: ByteArray): ByteArray {
        try {
            val sizeInBytes = bytes.size
            val twoMB = 2 * 1024 * 1024
            if (sizeInBytes <= twoMB) {
                val opts = BitmapFactory.Options()
                opts.inJustDecodeBounds = true
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                val maxDim = maxOf(opts.outWidth, opts.outHeight)
                if (maxDim <= 1080) return bytes
            }

            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return bytes
            val maxDim = maxOf(bmp.width, bmp.height)
            if (maxDim <= 1080 && sizeInBytes <= twoMB) return bytes

            val scale = 1080f / maxDim
            val newW = (bmp.width * scale).toInt()
            val newH = (bmp.height * scale).toInt()
            val scaled = Bitmap.createScaledBitmap(bmp, newW, newH, true)

            val baos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, baos)
            return baos.toByteArray()
        } catch (e: Exception) {
            return bytes
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }


}
