package com.example.basicfiredatabase.adapters

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.basicfiredatabase.R

class ImageListAdapter(
    private val onRemove: (position: Int) -> Unit
) : ListAdapter<Uri, ImageListAdapter.VH>(Diff) {

    // track positions currently uploading
    private val uploadingPositions = mutableSetOf<Int>()

    fun setUploading(position: Int, uploading: Boolean) {
        if (uploading) uploadingPositions.add(position) else uploadingPositions.remove(position)
        notifyItemChanged(position)
    }

    fun clearUploading() {
        val copy = uploadingPositions.toList()
        uploadingPositions.clear()
        copy.forEach { notifyItemChanged(it) }
    }

    private object Diff : DiffUtil.ItemCallback<Uri>() {
        override fun areItemsTheSame(oldItem: Uri, newItem: Uri) = oldItem == newItem
        override fun areContentsTheSame(oldItem: Uri, newItem: Uri) = oldItem == newItem
    }

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val iv: ImageView = itemView.findViewById(R.id.item_iv)
        val progress: ProgressBar = itemView.findViewById(R.id.item_progress)
        val btnRemove: ImageButton = itemView.findViewById(R.id.item_remove)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_selected_image, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val uri = getItem(position)

        // Use Glide to load thumbnail asynchronously, avoid main-thread bitmap creation
        Glide.with(holder.itemView)
            .load(uri)
            .override(512)          // request smaller size
            .centerInside()
            .into(holder.iv)

        holder.progress.visibility = if (position in uploadingPositions) View.VISIBLE else View.GONE

        holder.btnRemove.setOnClickListener {
            onRemove(holder.bindingAdapterPosition)
        }
    }
}
