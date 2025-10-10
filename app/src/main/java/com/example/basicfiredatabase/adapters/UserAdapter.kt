package com.example.basicfiredatabase.adapters

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.net.Uri
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.basicfiredatabase.R
import com.example.basicfiredatabase.databinding.ItemUserBinding
import com.example.basicfiredatabase.fragments.ImageViewerFragment
import com.example.basicfiredatabase.models.User
import com.example.basicfiredatabase.utils.DescriptionKeyMapper
import com.example.basicfiredatabase.utils.EventTypeTranslator
import com.example.basicfiredatabase.utils.LanguagePrefs
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class UserAdapter(
    private val items: MutableList<User> = mutableListOf(),
    private val onEdit: (User) -> Unit,
    private val onDelete: (User) -> Unit
) : RecyclerView.Adapter<UserAdapter.VH>() {

    inner class VH(val binding: ItemUserBinding) : RecyclerView.ViewHolder(binding.root) {
        private val ctx: Context get() = binding.root.context

        fun bind(user: User, position: Int) {
            // Wrap calls in with(binding) to use viewbinding properties directly
            with(binding) {
                bindHeader(user)
                bindSummary(user)
                bindDetails(user)
                bindLocation(user)
                bindImages(user)
                bindActions(user, position)
            }
        }

        private fun bindHeader(user: User) = with(binding) {
            tvName.text = user.title
            tvEventType.text = EventTypeTranslator.getLocalized(ctx, user.eventType)

            val expandRes =
                if (user.expanded) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right
            ivExpand.setImageResource(expandRes)
            ivExpand.imageTintList = null

            // Thumbnail (first image) - show only for upcoming events, when images exist,
            // and when the card is COLLAPSED.
            val iv = ivThumbnail
            val shouldShowThumb = shouldShowThumbnail(user)

            if (shouldShowThumb) {
                Glide.with(ctx)
                    .load(user.images[0].url)
                    .centerCrop()
                    .into(iv)

                if (iv.visibility != View.VISIBLE) {
                    iv.alpha = 0f
                    iv.visibility = View.VISIBLE
                    iv.animate().alpha(1f).setDuration(180).start()
                } else {
                    iv.animate().alpha(1f).setDuration(120).start()
                }
            } else {
                if (iv.visibility == View.VISIBLE) {
                    iv.animate().alpha(0f).setDuration(180).withEndAction {
                        iv.visibility = View.GONE
                        iv.alpha = 0f
                    }.start()
                } else {
                    iv.visibility = View.GONE
                    iv.alpha = 0f
                }
            }
        }

        private fun bindSummary(user: User) = with(binding) {
            val descKey = DescriptionKeyMapper.mapLangToKey(LanguagePrefs.current())
            val descToShow = user.descriptions[descKey] ?: user.descriptions.values.firstOrNull().orEmpty()
            tvSummary.text = descToShow

            // If collapsed -> show only a couple of lines with ellipsize; if expanded -> show full.
            if (user.expanded) {
                tvSummary.maxLines = Integer.MAX_VALUE
                tvSummary.ellipsize = null
            } else {
                tvSummary.maxLines = 2 // same number as XML default; keep in sync
                tvSummary.ellipsize = android.text.TextUtils.TruncateAt.END
            }

            var ellipseExists = false

            // detect overflow after layout
            // 3️⃣ After layout, check if text was actually truncated
            tvSummary.post {
                // Only check if not expanded
                if (!user.expanded) {
                    val layout = tvSummary.layout
                    if (layout != null) {
                        for (i in 0 until layout.lineCount) {
                            if (layout.getEllipsisCount(i) > 0) {
                                ellipseExists = true
                                break
                            }
                        }
                    }
                }

                // 4️⃣ Use your boolean + expanded check for visibility
                tvReadMore.visibility =
                    if (ellipseExists && !user.expanded) View.VISIBLE else View.GONE
            }

            val dateText = user.date.ifBlank { "No date set" }
            tvDate.text = dateText
        }

        private fun bindDetails(user: User) = with(binding) {
            val durationMinutes = user.durationMinutes ?: 0
            val btnViewImages = btnViewImages // viewbinding property

            if (user.isComplete) {
                // Completed events: hide time and duration UI, and images area
                tvTimeRange.visibility = View.GONE
                ivDurationBox.visibility = View.GONE

                imagesLabelContainer.visibility = View.GONE
                llImages.removeAllViews()
                llImages.visibility = View.GONE

                // Configure View Images button appearance and enabled state (reset for recycled views)
                btnViewImages.apply {
                    alpha = 1f
                    isEnabled = true
                    isClickable = true
                    setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
                }

                if (user.images.isNullOrEmpty()) {
                    btnViewImages.apply {
                        text = context.getString(R.string.no_images_available)
                        isEnabled = false
                        isClickable = false
                        setCompoundDrawablesWithIntrinsicBounds(R.drawable.outline_broken_image_24, 0, 0, 0)
                        alpha = 0.6f
                        try {
                            backgroundTintList = ContextCompat.getColorStateList(ctx, android.R.color.darker_gray)
                        } catch (e: Exception) { /* ignore */ }
                    }
                } else {
                    btnViewImages.apply {
                        text = context.getString(R.string.view_images)
                        isEnabled = true
                        isClickable = true
                        alpha = 1f
                        setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_gallery, 0, 0, 0)
                        compoundDrawablePadding = (8 * ctx.resources.displayMetrics.density).toInt()
                        try {
                            backgroundTintList = ContextCompat.getColorStateList(ctx, R.color.teal_200)
                        } catch (e: Exception) { /* ignore */ }
                    }
                }

                // Show the button only when expanded
                btnViewImages.visibility = if (user.expanded) View.VISIBLE else View.GONE

            } else {
                // Upcoming event: compute time range and show details as before
                val timePattern = "HH:mm"
                val sdf = SimpleDateFormat(timePattern, Locale.getDefault()).apply { isLenient = false }

                var timeRangeText = if (durationMinutes > 0) "${durationMinutes} min" else "N/A"
                try {
                    val startStr = user.time
                    if (!startStr.isNullOrBlank()) {
                        val startDate = sdf.parse(startStr)
                        if (startDate != null) {
                            val cal = Calendar.getInstance().apply { time = startDate }
                            val endCal = cal.clone() as Calendar
                            endCal.add(Calendar.MINUTE, durationMinutes)
                            val startFormatted = sdf.format(cal.time)
                            val endFormatted = sdf.format(endCal.time)
                            timeRangeText = "$startFormatted \u2013 $endFormatted (${durationMinutes} min)"
                        }
                    }
                } catch (ex: Exception) {
                    timeRangeText = if (durationMinutes > 0) "${durationMinutes} min" else "N/A"
                }

                tvTimeRange.apply {
                    text = timeRangeText
                    visibility = if (user.expanded) View.VISIBLE else View.GONE
                }
                ivDurationBox.visibility = if (user.expanded) View.VISIBLE else View.GONE

                imagesLabelContainer.visibility =
                    if (user.expanded && user.images.isNotEmpty() && !user.isComplete) View.VISIBLE else View.GONE

                if (!user.expanded || user.images.isEmpty() || user.isComplete) {
                    llImages.removeAllViews()
                    llImages.visibility = View.GONE
                } else {
                    llImages.visibility = View.VISIBLE
                    // bindImages will populate when called from bind()
                }

                // hide view images button for upcoming events
                btnViewImages.visibility = View.GONE
            }

            val status = if (!user.isComplete) "Upcoming" else "Completed"
            val durationText = if (durationMinutes > 0) "$durationMinutes min" else "N/A"
            tvDetails.text = buildString {
                append("Type: ${user.eventType}\n")
                append("Duration: $durationText\n")
                append("Status: $status")
            }

            llDetails.visibility = if (user.expanded) View.VISIBLE else View.GONE
        }

        private fun bindLocation(user: User) = with(binding) {
            // If tvLocation / ivLocationBox exist in layout they are available via binding
            val locationText = user.location ?: "N/A"

            tvLocation.apply {
                text = locationText
                // reset state for recycled views
                setOnClickListener(null)
                isClickable = false
                isFocusable = false
                visibility = if (user.expanded) View.VISIBLE else View.GONE
                paintFlags = paintFlags and Paint.UNDERLINE_TEXT_FLAG.inv()
                setTextColor(ContextCompat.getColor(ctx, android.R.color.black))
            }

            ivLocationBox.visibility = if (user.expanded) View.VISIBLE else View.GONE

            // If the event is completed, hide location entirely and return
            if (user.isComplete) {
                tvLocation.visibility = View.GONE
                ivLocationBox.visibility = View.GONE
                return
            }

            val shouldShow = user.expanded
            tvLocation.visibility = if (shouldShow) View.VISIBLE else View.GONE
            ivLocationBox.visibility = if (shouldShow) View.VISIBLE else View.GONE

            if (shouldShow && !user.mapLink.isNullOrBlank()) {
                tvLocation.apply {
                    paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
                    setTextColor(ContextCompat.getColor(ctx, android.R.color.holo_blue_dark))
                    setOnClickListener {
                        try {
                            val uri = Uri.parse(user.mapLink)
                            val intent = Intent(Intent.ACTION_VIEW, uri)
                            ctx.startActivity(intent)
                        } catch (ex: Exception) {
                            Toast.makeText(ctx, "Unable to open link", Toast.LENGTH_SHORT).show()
                        }
                    }
                    isClickable = true
                    isFocusable = true
                }
            }
        }


        private fun bindImages(user: User) = with(binding) {
            // clear old image views
            llImages.removeAllViews()

            imagesLabelContainer.visibility =
                if (user.expanded && user.images.isNotEmpty() && !user.isComplete) View.VISIBLE else View.GONE

            if (!user.expanded || user.images.isEmpty() || user.isComplete) return

            val sizePx = (ctx.resources.displayMetrics.density * 200).toInt()
            val margin = (ctx.resources.displayMetrics.density * 6).toInt()

            for ((index, img) in user.images.withIndex()) {
                val iv = ImageView(ctx)
                val lp = LinearLayout.LayoutParams(sizePx, sizePx)
                lp.setMargins(margin, margin, margin, margin)
                iv.layoutParams = lp
                iv.scaleType = ImageView.ScaleType.FIT_CENTER
                iv.adjustViewBounds = true


                Glide.with(ctx).load(img.url).into(iv)

                iv.setOnClickListener {
                    val fragment = ImageViewerFragment.newInstance(
                        user.images.map { it.url }, index
                    )
                    val activity = ctx as AppCompatActivity
                    activity.supportFragmentManager.beginTransaction()
                        .add(R.id.fragment_container, fragment)
                        .addToBackStack(null)
                        .commit()
                }

                llImages.addView(iv)
            }
        }


        private fun bindActions(user: User, position: Int) = with(binding) {
            // Toggle expand/collapse when row is clicked.
            root.setOnClickListener {
                user.expanded = !user.expanded
                this@UserAdapter.notifyItemChanged(position)
            }

            btnEdit.setOnClickListener { onEdit(user) }
            btnDelete.setOnClickListener { onDelete(user) }

            // Safe handler for the "View images" button (uses binding btnViewImages)
            btnViewImages.setOnClickListener { btn ->
                // Defensive: do nothing if disabled (recycled views may leak old listeners)
                if (!btn.isEnabled) return@setOnClickListener

                val activity = ctx as? AppCompatActivity
                if (activity == null) {
                    Toast.makeText(ctx, "Unable to open gallery", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                try {
                    // Preferred: open GalleryFragment filtered to this event
                    val frag = com.example.basicfiredatabase.fragments.GalleryFragment.newInstance(user.id)
                    activity.supportFragmentManager.beginTransaction()
                        .replace(R.id.fragment_container, frag)
                        .addToBackStack(null)
                        .commit()
                } catch (ex: NoSuchMethodError) {
                    // Fallback to ImageViewer if GalleryFragment.newInstance(...) is not present
                    if (user.images.isNullOrEmpty()) {
                        Toast.makeText(ctx, "No images available", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    val urls = user.images.map { it.url }
                    val frag = ImageViewerFragment.newInstance(urls, 0)
                    activity.supportFragmentManager.beginTransaction()
                        .replace(R.id.fragment_container, frag)
                        .addToBackStack(null)
                        .commit()
                } catch (e: Exception) {
                    Toast.makeText(ctx, "Could not open images", Toast.LENGTH_SHORT).show()
                    Log.w("UserAdapter", "Error opening images for event ${user.id}", e)
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemUserBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position], position)
    }

    override fun getItemCount(): Int = items.size

    fun setItems(newItems: List<User>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }


    companion object {
        /**
         * Pure logic helper used to decide whether the thumbnail should be visible.
         * Kept here so existing code can call it (optional) and tests can exercise it.
         */
        @JvmStatic
        fun shouldShowThumbnail(user: com.example.basicfiredatabase.models.User): Boolean {
            return !user.isComplete && !user.expanded && !user.images.isNullOrEmpty()
        }
    }

}
