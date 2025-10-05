package com.example.basicfiredatabase.adapters

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.basicfiredatabase.LanguagePrefs
import com.example.basicfiredatabase.R
import com.example.basicfiredatabase.databinding.ItemUserBinding
import com.example.basicfiredatabase.fragments.ImageViewerFragment
import com.example.basicfiredatabase.models.User
import com.example.basicfiredatabase.utils.DescriptionKeyMapper
import com.example.basicfiredatabase.utils.EventTypeTranslator
import java.text.ParseException
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
            bindHeader(user)
            bindSummary(user)
            bindDetails(user)
            bindLocation(user)
            bindImages(user)
            bindActions(user, position)
        }

        private fun bindHeader(user: User) {
            binding.tvName.text = user.title

            // Use translator to show eventType in the user's selected app language.
            binding.tvEventType.text = EventTypeTranslator.getLocalized(ctx, user.eventType)


            // Set expand icon image depending on expanded state (no rotation animation)
            val expandRes =
                if (user.expanded) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right
            binding.ivExpand.setImageResource(expandRes)
            binding.ivExpand.imageTintList = null

            // Thumbnail (first image) - show only for upcoming events, when images exist,
            // and when the card is COLLAPSED. We keep the same fade-in/out behavior you had.
            val iv = binding.ivThumbnail
            val shouldShowThumb = !user.isComplete && !user.expanded && user.images.isNotEmpty()

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


        private fun bindSummary(user: User) {

            // Map the app language (e.g. "en","af","zu") to Firestore keys ("primary","secondary","tertiary")
            val descKey = DescriptionKeyMapper.mapLangToKey(LanguagePrefs.current())

            // Because you said a description will always be present, we directly read and set it.
            // (If you ever expect missing values, you can add safe fallbacks later.)
            val descToShow = user.descriptions[descKey] ?: user.descriptions.values.first()

            binding.tvSummary.text = descToShow


            // show only date in collapsed card (no time badge)
            val dateText = user.date.ifBlank { "No date set" }
            binding.tvDate.text = dateText

            // ensure time badge (if present elsewhere) isn't used in collapsed UI:
            // (we removed the tv_time view from layout, so no binding.tvTime usage here)
        }

        private fun bindDetails(user: User) {
            val durationMinutes = user.durationMinutes ?: 0

            // fallback text
            var timeRangeText = if (durationMinutes > 0) "${durationMinutes} min" else "N/A"

            val timePattern = "HH:mm" // adjust if your stored format differs
            val sdf = SimpleDateFormat(timePattern, Locale.getDefault())
            sdf.isLenient = false

            try {
                val startStr = user.time
                if (!startStr.isNullOrBlank()) {
                    val startDate = sdf.parse(startStr) // Date
                    if (startDate != null) {
                        val cal = Calendar.getInstance()
                        cal.time = startDate

                        // compute end time by adding minutes
                        val endCal = cal.clone() as Calendar
                        endCal.add(Calendar.MINUTE, durationMinutes)

                        val startFormatted = sdf.format(cal.time)
                        val endFormatted = sdf.format(endCal.time)
                        timeRangeText = "$startFormatted \u2013 $endFormatted (${durationMinutes} min)"
                    }
                }
            } catch (ex: ParseException) {
                if (durationMinutes > 0) timeRangeText = "${durationMinutes} min" else timeRangeText = "N/A"
            } catch (ex: Exception) {
                if (durationMinutes > 0) timeRangeText = "${durationMinutes} min" else timeRangeText = "N/A"
            }

            binding.root.findViewById<TextView>(R.id.tv_time_range)?.text = timeRangeText

            // keep hidden tvDetails text for compatibility
            val status = if (!user.isComplete) "Upcoming" else "Completed"
            val durationText = if (durationMinutes > 0) "$durationMinutes min" else "N/A"
            binding.tvDetails.text = buildString {
                append("Type: ${user.eventType}\n")
                append("Duration: $durationText\n")
                append("Status: $status")
            }

            binding.llDetails.visibility = if (user.expanded) View.VISIBLE else View.GONE
        }

        private fun bindLocation(user: User) {
            val tvLocation: TextView? = binding.root.findViewById(R.id.tv_location)
            val locationText = user.location ?: "N/A"

            tvLocation?.let { tv ->
                tv.text = locationText

                // Reset state (important for recycling)
                tv.setOnClickListener(null)
                tv.isClickable = false
                tv.isFocusable = false
                tv.visibility = if (user.expanded) View.VISIBLE else View.GONE
                tv.paintFlags = tv.paintFlags and Paint.UNDERLINE_TEXT_FLAG.inv()
                tv.setTextColor(ContextCompat.getColor(ctx, android.R.color.black))

                // If there is a map link — make it look clickable and open it
                if (!user.mapLink.isNullOrBlank()) {
                    tv.paintFlags = tv.paintFlags or Paint.UNDERLINE_TEXT_FLAG
                    tv.setTextColor(ContextCompat.getColor(ctx, android.R.color.holo_blue_dark))
                    tv.setOnClickListener {
                        try {
                            val uri = Uri.parse(user.mapLink)
                            val intent = Intent(Intent.ACTION_VIEW, uri)
                            ctx.startActivity(intent)
                        } catch (ex: Exception) {
                            Toast.makeText(ctx, "Unable to open link", Toast.LENGTH_SHORT).show()
                        }
                    }
                    tv.isClickable = true
                    tv.isFocusable = true
                }
            }
        }

        private fun bindImages(user: User) {
            // clear old image views
            binding.llImages.removeAllViews()

            // show/hide the "Images" label only when expanded, not completed, and images exist
            binding.imagesLabelContainer.visibility =
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
                        .replace(R.id.fragment_container, fragment)
                        .addToBackStack(null)
                        .commit()
                }

                binding.llImages.addView(iv)
            }
        }

        private fun bindActions(user: User, position: Int) {
            // Toggle expand/collapse when row is clicked. No rotation animation; just swap icon on rebind.
            binding.root.setOnClickListener {
                user.expanded = !user.expanded
                // immediately update this item so bindHeader runs and sets the correct chevron + thumbnail state
                this@UserAdapter.notifyItemChanged(position)
            }

            binding.btnEdit.setOnClickListener { onEdit(user) }
            binding.btnDelete.setOnClickListener { onDelete(user) }
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
}
