package com.example.basicfiredatabase.adapters

import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.basicfiredatabase.R
import com.example.basicfiredatabase.models.GalleryItem

private const val TYPE_HEADER = 0
private const val TYPE_IMAGE = 1

class GalleryAdapter(
    private val onImageClick: (String, String) -> Unit
) : ListAdapter<GalleryItem, RecyclerView.ViewHolder>(Diff) {

    companion object {
        private val Diff = object : DiffUtil.ItemCallback<GalleryItem>() {
            override fun areItemsTheSame(oldItem: GalleryItem, newItem: GalleryItem): Boolean {
                return when {
                    oldItem is GalleryItem.Header && newItem is GalleryItem.Header ->
                        oldItem.eventId == newItem.eventId
                    oldItem is GalleryItem.Image && newItem is GalleryItem.Image ->
                        oldItem.imageUrl == newItem.imageUrl
                    else -> false
                }
            }

            override fun areContentsTheSame(oldItem: GalleryItem, newItem: GalleryItem): Boolean {
                return oldItem == newItem
            }
        }
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is GalleryItem.Header -> TYPE_HEADER
            is GalleryItem.Image -> TYPE_IMAGE
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val li = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            val v = li.inflate(R.layout.item_gallery_header, parent, false)
            HeaderVH(v)
        } else {
            val v = li.inflate(R.layout.item_gallery_image, parent, false)
            ImageVH(v)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is GalleryItem.Header -> (holder as HeaderVH).bind(item)
            is GalleryItem.Image -> (holder as ImageVH).bind(item)
        }
    }

    inner class HeaderVH(view: View) : RecyclerView.ViewHolder(view) {
        private val tvTitle: TextView = view.findViewById(R.id.tv_gallery_header_title)
        private val tvDate: TextView = view.findViewById(R.id.tv_gallery_header_date)
        fun bind(h: GalleryItem.Header) {
            tvTitle.text = h.title
            tvDate.text = h.date
        }
    }

    inner class ImageVH(view: View) : RecyclerView.ViewHolder(view) {
        private val iv: ImageView = view.findViewById(R.id.iv_gallery_image)
        fun bind(img: GalleryItem.Image) {
            Glide.with(iv.context)
                .load(img.imageUrl)
                .centerCrop()
                .placeholder(R.drawable.ic_placeholder)
                .into(iv)

            iv.setOnClickListener {
                // protect against invalid adapter position
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener

                // pass tapped image url + its event id so caller can build the event image list
                onImageClick(img.imageUrl, img.eventId)
            }
        }
    }
}
