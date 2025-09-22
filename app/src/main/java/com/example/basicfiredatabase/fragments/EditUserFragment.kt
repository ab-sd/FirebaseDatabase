package com.example.basicfiredatabase.fragments

import android.app.DatePickerDialog
import android.app.TimePickerDialog
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
import com.bumptech.glide.Glide
import com.example.basicfiredatabase.R
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
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

class EditUserFragment : Fragment(R.layout.fragment_edit_user) {

    private val db = Firebase.firestore
    private var userId: String? = null

    // mutable lists for existing images (from server) and newly selected local image URIs
    private val existingImages = mutableListOf<MutableMap<String, String>>() // list of maps: "url","public_id"
    private val newImageUris = mutableListOf<Uri>()

    // public_ids that user chose to delete (these will be sent to delete endpoint on Update)
    private val imagesToDelete = mutableListOf<String>()

    //firebase remote config values
    private var imageCloudApiKey: String? = null
    private var uploadUrl: String? = null
    private var deleteUrl: String? = null

    // progress bars for items (keys = "existing:<public_id>" or "new:<index>")
    private val progressMap = mutableMapOf<String, ProgressBar>()

    private val pickImagesLauncher =
        registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            if (uris == null) return@registerForActivityResult
            // ensure total images won't exceed 10
            val currentTotal = existingImages.size + newImageUris.size
            val spaceLeft = maxOf(0, 10 - currentTotal)
            if (uris.isNotEmpty() && spaceLeft <= 0) {
                Toast.makeText(requireContext(), "Maximum 10 images allowed", Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }
            val toAdd = uris.take(spaceLeft)
            newImageUris.addAll(toAdd)
            updateImagesUI()
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val scrollView = view.findViewById<ScrollView>(R.id.scrollView_edit) // Wrap your layout in ScrollView
        var lastImeHeight = 0
        var lastNavHeight = 0
        var currentFocusedView: View? = null

        fun scrollToView(view: View) {
            val rect = android.graphics.Rect()
            view.getDrawingRect(rect)
            scrollView.offsetDescendantRectToMyCoords(view, rect)
            val visibleHeight = scrollView.height - lastImeHeight - lastNavHeight
            val scrollY = rect.bottom - visibleHeight + dpToPx(12)
            scrollView.post { scrollView.smoothScrollTo(0, maxOf(0, scrollY)) }
        }

        val etTitle = view.findViewById<EditText>(R.id.et_edit_title)
        val spinnerType = view.findViewById<Spinner>(R.id.spinner_edit_event_type)
        val etDescPrimary = view.findViewById<EditText>(R.id.et_edit_description_primary)
        val etDescSecondary = view.findViewById<EditText>(R.id.et_edit_description_secondary)
        val etDescTertiary = view.findViewById<EditText>(R.id.et_edit_description_tertiary)
        val tvDate = view.findViewById<TextView>(R.id.tv_edit_date)
        val tvTime = view.findViewById<TextView>(R.id.tv_edit_time)
        val etDuration = view.findViewById<EditText>(R.id.et_edit_duration)
        val etLocation = view.findViewById<EditText>(R.id.et_edit_location)
        val switchUpcoming = view.findViewById<Switch>(R.id.switch_edit_upcoming)

        val btnUpdate = view.findViewById<Button>(R.id.btn_update)
        val btnPick = view.findViewById<Button>(R.id.btn_pick_more_images)
        val tvCount = view.findViewById<TextView>(R.id.tv_edit_images_count)
        val llImages = view.findViewById<LinearLayout>(R.id.ll_edit_images)


        // Focus listeners for all EditTexts near bottom
        val focusableFields = listOf(
            etTitle, etDescPrimary, etDescSecondary, etDescTertiary, etDuration, etLocation
        )
        for (field in focusableFields) {
            field.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    currentFocusedView = v
                    v.postDelayed({ scrollToView(v) }, 120)
                }
            }
            field.setOnClickListener { v ->
                currentFocusedView = v
                v.post { scrollToView(v) }
            }
        }

        // Handle insets (IME + nav bar)
        ViewCompat.setOnApplyWindowInsetsListener(scrollView) { v, insets ->
            val navBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            lastNavHeight = navBottom
            lastImeHeight = imeBottom

            // Bottom padding ensures scrolling works for all content
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, navBottom + imeBottom + dpToPx(8))

            // Update button margin so it's not flush against nav bar
            val lp = btnUpdate.layoutParams
            if (lp is ViewGroup.MarginLayoutParams) {
                val desiredBottomMargin = navBottom + dpToPx(16)
                if (lp.bottomMargin != desiredBottomMargin) {
                    lp.bottomMargin = desiredBottomMargin
                    btnUpdate.layoutParams = lp
                }
            }

            // Scroll focused field if keyboard is showing
            if (imeBottom > 0) currentFocusedView?.let { fv -> v.post { scrollToView(fv) } }

            insets
        }

        // read arguments
        userId = arguments?.getString("id")
        val title = arguments?.getString("title") ?: ""
        val eventTypeArg = arguments?.getString("event_type") ?: "Other"
        @Suppress("UNCHECKED_CAST")
        val descriptionsArg = (arguments?.getSerializable("descriptions") as? HashMap<*, *>)?.mapNotNull { e ->
            val k = e.key as? String; val v = e.value as? String
            if (k != null && v != null) k to v else null
        }?.toMap() ?: emptyMap()

        val dateArg = arguments?.getString("date") ?: ""
        val timeArg = arguments?.getString("time") ?: ""
        val durationArg = arguments?.getInt("duration_minutes")
        val locationArg = arguments?.getString("location")
        val isUpcomingArg = arguments?.getBoolean("is_upcoming") ?: true

        @Suppress("UNCHECKED_CAST")
        val imgs = arguments?.getSerializable("images") as? ArrayList<*>
        existingImages.clear()
        if (imgs != null) {
            for (item in imgs) {
                val map = item as? Map<*, *>
                val url = map?.get("url") as? String ?: continue
                val publicId = map?.get("public_id") as? String ?: continue
                existingImages.add(mutableMapOf("url" to url, "public_id" to publicId))
            }
        }

        // Spinner items (same as AddUserFragment)
        val types = listOf("Conference", "Meetup", "Workshop", "Seminar", "Party", "Other")
        spinnerType.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, types)

        // set initial UI from args
        etTitle.setText(title)
        val idx = types.indexOf(eventTypeArg).let { if (it >= 0) it else types.size - 1 }
        spinnerType.setSelection(idx)

        etDescPrimary.setText(descriptionsArg["primary"] ?: "")
        etDescSecondary.setText(descriptionsArg["secondary"] ?: "")
        etDescTertiary.setText(descriptionsArg["tertiary"] ?: "")

        tvDate.text = if (dateArg.isNotBlank()) dateArg else "Select date"
        tvTime.text = if (timeArg.isNotBlank()) timeArg else "Select time"
        etDuration.setText(durationArg?.takeIf { it > 0 }?.toString() ?: "")
        etLocation.setText(locationArg ?: "")
        switchUpcoming.isChecked = isUpcomingArg


        // Remote Config setup: get both upload & delete endpoints
        val remoteConfig = Firebase.remoteConfig
        val configSettings = remoteConfigSettings {
            minimumFetchIntervalInSeconds = 3600
        }
        remoteConfig.setConfigSettingsAsync(configSettings)

        // default fallbacks (so app still works offline)
        remoteConfig.setDefaultsAsync(
            mapOf(
                "uploadImage_url" to "https://cloudinaryserver.onrender.com/upload",
                "deleteImage_url" to "https://cloudinaryserver.onrender.com/delete"
            )
        )

        remoteConfig.fetchAndActivate()
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    uploadUrl = remoteConfig.getString("uploadImage_url")
                    deleteUrl = remoteConfig.getString("deleteImage_url")
                    imageCloudApiKey = remoteConfig.getString("cloud_api_key")
                    Toast.makeText(requireContext(), "RC: upload and delete URLs loaded", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "Failed to fetch Remote Config", Toast.LENGTH_SHORT).show()
                }
            }

        // set local reference for updates
        _tvCount = tvCount
        _llImages = llImages

        updateImagesUI()

        // date picker
        tvDate.setOnClickListener {
            val c = Calendar.getInstance()
            val dp = DatePickerDialog(
                requireContext(),
                { _, year, month, day ->
                    val cal = Calendar.getInstance()
                    cal.set(year, month, day)
                    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                    tvDate.text = sdf.format(cal.time)
                },
                c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)
            )
            dp.show()
        }


        // time picker
        tvTime.setOnClickListener {
            val c = Calendar.getInstance()
            val tp = TimePickerDialog(
                requireContext(),
                { _, hourOfDay, minute ->
                    tvTime.text = String.format(Locale.US, "%02d:%02d", hourOfDay, minute)
                },
                c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), true
            )
            tp.show()
        }



        btnPick.setOnClickListener {
            // allow picking images (will enforce 10 limit in launcher callback)
            pickImagesLauncher.launch("image/*")
        }

        btnUpdate.setOnClickListener {
            val newTitle = etTitle.text.toString().trim()
            val newType = spinnerType.selectedItem.toString()
            val descPrimaryTxt = etDescPrimary.text.toString().trim()
            val descSecondaryTxt = etDescSecondary.text.toString().trim()
            val descTertiaryTxt = etDescTertiary.text.toString().trim()
            val descriptions = mutableMapOf<String, String>()
            if (descPrimaryTxt.isNotEmpty()) descriptions["primary"] = descPrimaryTxt
            if (descSecondaryTxt.isNotEmpty()) descriptions["secondary"] = descSecondaryTxt
            if (descTertiaryTxt.isNotEmpty()) descriptions["tertiary"] = descTertiaryTxt

            val date = tvDate.text.toString().trim()
            val time = tvTime.text.toString().trim()
            val durationMinutes = etDuration.text.toString().toIntOrNull()
            val location = etLocation.text.toString().trim().takeIf { it.isNotEmpty() }
            val isUpcoming = switchUpcoming.isChecked

            if (userId == null || newTitle.isEmpty()) {
                Toast.makeText(requireContext(), "Fill in title", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            btnUpdate.isEnabled = false
            btnPick.isEnabled = false

            lifecycleScope.launchWhenStarted {
                try {
                    // 1) upload new images if any
                    val uploadedNewImages = if (newImageUris.isNotEmpty()) {
                        if (uploadUrl.isNullOrEmpty()) throw Exception("Upload URL not available")
                        uploadNewImages(newImageUris)
                    } else emptyList()

                    // 2) delete marked images
                    if (imagesToDelete.isNotEmpty()) {
                        if (deleteUrl.isNullOrEmpty()) throw Exception("Delete URL not available")
                        val delOk = deleteMarkedImages(imagesToDelete)
                        if (!delOk) throw Exception("Failed to delete some images")
                    }

                    // 3) build final images array
                    val finalImages = mutableListOf<Map<String, String>>()
                    finalImages.addAll(existingImages.map { mapOf("url" to it["url"]!!, "public_id" to it["public_id"]!!) })
                    finalImages.addAll(uploadedNewImages)

                    // 4) update map (all event fields)
                    val updateMap = hashMapOf<String, Any?>(
                        "title" to newTitle,
                        "event_type" to newType,
                        "descriptions" to descriptions,
                        "date" to (if (date == "Select date") "" else date),
                        "time" to (if (time == "Select time") "" else time),
                        "duration_minutes" to durationMinutes,
                        "images" to finalImages,
                        "location" to location,
                        "is_upcoming" to isUpcoming
                    )

                    withContext(Dispatchers.Main) {
                        db.collection("users").document(userId!!)
                            .update(updateMap)
                            .addOnSuccessListener {
                                Toast.makeText(requireContext(), "Updated!", Toast.LENGTH_SHORT).show()
                                parentFragmentManager.popBackStack()
                            }
                            .addOnFailureListener { e ->
                                Toast.makeText(requireContext(), "Update failed: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(), "Operation failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                } finally {
                    withContext(Dispatchers.Main) {
                        btnUpdate.isEnabled = true
                        btnPick.isEnabled = true
                    }
                }
            }
        }
    }

    // convenience references
    private var _tvCount: TextView? = null
    private var _llImages: LinearLayout? = null

    private fun updateImagesUI() {
        val tv = _tvCount ?: return
        val container = _llImages ?: return
        val total = existingImages.size + newImageUris.size
        tv.text = "$total image(s) (max 10)"

        container.removeAllViews()
        progressMap.clear()

        val sizePx = (resources.displayMetrics.density * 160).toInt()
        val marginPx = (resources.displayMetrics.density * 6).toInt()

        // show existing images first (they have url & public_id)
        for ((idx, img) in existingImages.withIndex()) {
            val frame = FrameLayout(requireContext())
            val frameLp = LinearLayout.LayoutParams(sizePx, sizePx)
            frameLp.setMargins(marginPx, marginPx, marginPx, marginPx)
            frame.layoutParams = frameLp

            val iv = ImageView(requireContext())
            val ivLp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            iv.layoutParams = ivLp

            iv.scaleType = ImageView.ScaleType.FIT_CENTER
            iv.adjustViewBounds = true

            Glide.with(requireContext()).load(img["url"]).into(iv)

            // small delete button (overlay)
            val deleteBtn = ImageButton(requireContext())
            val dbSize = (resources.displayMetrics.density * 22).toInt()
            val dbLp = FrameLayout.LayoutParams(dbSize, dbSize)
            dbLp.gravity = Gravity.END or Gravity.TOP
            deleteBtn.layoutParams = dbLp
            deleteBtn.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            deleteBtn.scaleType = ImageView.ScaleType.CENTER
            deleteBtn.setBackgroundResource(android.R.color.transparent)
            deleteBtn.setOnClickListener {
                // mark this image for deletion and remove from existingImages
                val publicId = img["public_id"] ?: return@setOnClickListener
                imagesToDelete.add(publicId)
                existingImages.removeAt(idx)
                updateImagesUI()
            }

            val progress = ProgressBar(requireContext())
            val pSize = (resources.displayMetrics.density * 32).toInt()
            val pLp = FrameLayout.LayoutParams(pSize, pSize)
            pLp.gravity = Gravity.CENTER
            progress.layoutParams = pLp
            progress.isIndeterminate = true
            try {
                progress.indeterminateTintList = resources.getColorStateList(android.R.color.holo_orange_light, null)
            } catch (_: Exception) { }
            progress.visibility = View.GONE

            val key = "existing:${img["public_id"]}"
            progressMap[key] = progress

            frame.addView(iv)
            frame.addView(deleteBtn)
            frame.addView(progress)
            container.addView(frame)
        }

        // show new (local) images
        for ((index, uri) in newImageUris.withIndex()) {
            val frame = FrameLayout(requireContext())
            val frameLp = LinearLayout.LayoutParams(sizePx, sizePx)
            frameLp.setMargins(marginPx, marginPx, marginPx, marginPx)
            frame.layoutParams = frameLp

            val iv = ImageView(requireContext())
            val ivLp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            iv.layoutParams = ivLp
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            iv.setImageURI(uri)

            // small delete button for removing selection (not server delete)
            val deleteBtn = ImageButton(requireContext())
            val dbSize = (resources.displayMetrics.density * 22).toInt()
            val dbLp = FrameLayout.LayoutParams(dbSize, dbSize)
            dbLp.gravity = Gravity.END or Gravity.TOP
            deleteBtn.layoutParams = dbLp
            deleteBtn.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            deleteBtn.setBackgroundResource(android.R.color.transparent)
            deleteBtn.setOnClickListener {
                newImageUris.removeAt(index)
                updateImagesUI()
            }

            val progress = ProgressBar(requireContext())
            val pSize = (resources.displayMetrics.density * 32).toInt()
            val pLp = FrameLayout.LayoutParams(pSize, pSize)
            pLp.gravity = Gravity.CENTER
            progress.layoutParams = pLp
            progress.isIndeterminate = true
            try {
                progress.indeterminateTintList = resources.getColorStateList(android.R.color.holo_orange_light, null)
            } catch (_: Exception) { }
            progress.visibility = View.GONE

            val key = "new:$index"
            progressMap[key] = progress

            frame.addView(iv)
            frame.addView(deleteBtn)
            frame.addView(progress)
            container.addView(frame)
        }
    }

    // Upload only the new local images and return list of maps { "url":..., "public_id":... }
    private suspend fun uploadNewImages(uris: List<Uri>): List<Map<String, String>> {
        return withContext(Dispatchers.IO) {
            val result = mutableListOf<Map<String, String>>()
            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()

            try {
                for ((index, uri) in uris.withIndex()) {
                    val key = "new:$index"
                    // show spinner for this new image
                    withContext(Dispatchers.Main) {
                        progressMap[key]?.visibility = View.VISIBLE
                    }

                    val input: InputStream = requireContext().contentResolver.openInputStream(uri)
                        ?: throw Exception("Cannot open selected image")
                    val rawBytes = input.readBytes()
                    input.close()

                    val bytesToUpload = maybeCompressIfNeeded(rawBytes)

                    val mediaType = "image/*".toMediaTypeOrNull()
                    val fileBody = bytesToUpload.toRequestBody(mediaType)

                    val requestBody = MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("file", "upload_$index.jpg", fileBody)
                        .build()

                    val request = Request.Builder()
                        .url(uploadUrl!!)
                        .post(requestBody)
                        .addHeader("x-api-key", imageCloudApiKey!!) // match your Render env var
                        .build()

                    val response = client.newCall(request).execute()
                    val responseBody = response.body?.string()
                    if (!response.isSuccessful || responseBody.isNullOrEmpty()) {
                        withContext(Dispatchers.Main) {
                            progressMap[key]?.visibility = View.GONE
                        }
                        throw Exception("HTTP ${response.code}: ${responseBody ?: "empty response"}")
                    }

                    val json = JSONObject(responseBody)
                    val url = json.optString("url", "")
                    val publicId = json.optString("public_id", "")
                    if (url.isBlank() || publicId.isBlank()) {
                        withContext(Dispatchers.Main) { progressMap[key]?.visibility = View.GONE }
                        throw Exception("Missing url or public_id")
                    }

                    result.add(mapOf("url" to url, "public_id" to publicId))

                    withContext(Dispatchers.Main) { progressMap[key]?.visibility = View.GONE }
                }

                // after successful upload, clear local newImageUris (we'll handle UI separately after returning)
                withContext(Dispatchers.Main) {
                    // we won't mutate newImageUris here because caller manages final assignment
                }

                result
            } catch (e: Exception) {
                // hide any spinners
                withContext(Dispatchers.Main) {
                    progressMap.values.forEach { it.visibility = View.GONE }
                }
                throw e
            }
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    // Delete the public_ids passed (calls the deleteUrl endpoint). Returns true if all deletions succeeded.
    private suspend fun deleteMarkedImages(publicIds: List<String>): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build()

                for (id in publicIds) {
                    val key = "existing:$id"
                    withContext(Dispatchers.Main) { progressMap[key]?.visibility = View.VISIBLE }

                    val body = JSONObject().put("public_id", id).toString()
                        .toRequestBody("application/json".toMediaTypeOrNull())

                    val request = Request.Builder()
                        .url(deleteUrl!!)
                        .post(body)
                        .addHeader("x-api-key", imageCloudApiKey!!) // match your Render env var
                        .build()

                    val response = client.newCall(request).execute()
                    withContext(Dispatchers.Main) { progressMap[key]?.visibility = View.GONE }
                    if (!response.isSuccessful) {
                        return@withContext false
                    }
                }
                true
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { progressMap.values.forEach { it.visibility = View.GONE } }
                false
            }
        }
    }

    /**
     * Same compression logic as AddUserFragment:
     * - If bytes > 2MB or max dimension >1080 => scale down to max 1080 and compress JPEG 85%
     * - Otherwise return original bytes
     */
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
            val scaled = android.graphics.Bitmap.createScaledBitmap(bmp, newW, newH, true)

            val baos = ByteArrayOutputStream()
            scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, baos)
            return baos.toByteArray()
        } catch (e: Exception) {
            return bytes
        }
    }
}
