package com.example.basicfiredatabase.adapters

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.basicfiredatabase.R
import com.example.basicfiredatabase.databinding.ItemUserBinding
import com.example.basicfiredatabase.models.User
import com.example.basicfiredatabase.fragments.ImageViewerFragment

class UserAdapter(
    private val items: MutableList<User> = mutableListOf(),
    private val onEdit: (User) -> Unit,
    private val onDelete: (User) -> Unit
) : RecyclerView.Adapter<UserAdapter.VH>() {

    class VH(val binding: ItemUserBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemUserBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val user = items[position]
        val b = holder.binding
        val ctx = holder.itemView.context

        // Title
        b.tvName.text = user.title

        b.tvEventType.text = user.eventType

        // Preferred language for description
        val prefs = ctx.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val preferredLang = prefs.getString("preferred_event_language", "primary") ?: "primary"
        val descToShow = user.descriptions[preferredLang] ?: user.descriptions.values.firstOrNull() ?: ""

        // Meta fields
        val status = if (user.isUpcoming) "Upcoming" else "Completed"
        val durationText = user.durationMinutes?.let { "$it min" } ?: "N/A"
        val locationText = user.location ?: "N/A"
        val dateText = user.date.ifBlank { "No date set" }
        val timeText = user.time.ifBlank { "No time set" }

        // Thumbnail (first image) - always visible in header (or hidden if none)
        if (user.images.isNotEmpty()) {
            b.ivThumbnail.visibility = View.VISIBLE
            Glide.with(ctx)
                .load(user.images[0].url)
                .centerCrop()
                .into(b.ivThumbnail)
        } else {
            b.ivThumbnail.visibility = View.GONE
        }



        // COLLAPSED summary: description + date/time (always visible under title)
        b.tvSummary.text = if (descToShow.isNotBlank()) descToShow else "No description"
        b.tvDate.text = "Date: " + dateText
        b.tvTime.text = "Time: " + timeText
        // EXPANDED details: Type, Location, Duration, Status
        b.tvDetails.text = buildString {
            append("Location: $locationText\n")
            append("Duration: $durationText\n")
            append("Status: $status")
        }

        // Expand/collapse UI
        b.llDetails.visibility = if (user.expanded) View.VISIBLE else View.GONE
        b.ivExpand.rotation = if (user.expanded) 180f else 0f

        // Images (only shown when expanded)
        b.llImages.removeAllViews()
        if (user.expanded && user.images.isNotEmpty()) {
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

                b.llImages.addView(iv)
            }
        }

        // Toggle expand/collapse when row is clicked
        b.root.setOnClickListener {
            user.expanded = !user.expanded
            // animate arrow then rebind to refresh content
            b.ivExpand.animate().rotation(if (user.expanded) 180f else 0f).setDuration(150).start()
            notifyItemChanged(position)
        }

        // Edit / Delete callbacks
        b.btnEdit.setOnClickListener { onEdit(user) }
        b.btnDelete.setOnClickListener { onDelete(user) }
    }

    override fun getItemCount(): Int = items.size

    fun setItems(newItems: List<User>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }
}
