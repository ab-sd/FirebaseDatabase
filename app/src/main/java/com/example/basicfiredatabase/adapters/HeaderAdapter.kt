package com.example.basicfiredatabase.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.basicfiredatabase.R

data class HeaderData(
    val title: String,
    val description: String,
    val showCta: Boolean = false,
    val ctaText: String = "View all past images",
    val imageRes: Int? = null
)

class HeaderAdapter(
    private var data: HeaderData,
    private val onCtaClick: (() -> Unit)? = null
) : RecyclerView.Adapter<HeaderAdapter.HVH>() {

    inner class HVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvTitle: TextView = itemView.findViewById(R.id.tv_header_title)
        val ivImage: ImageView = itemView.findViewById(R.id.iv_header_image)
        val tvDesc: TextView = itemView.findViewById(R.id.tv_header_desc)
        val btnCta: Button = itemView.findViewById(R.id.btn_header_cta)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HVH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_list_header, parent, false)
        return HVH(view)
    }

    override fun onBindViewHolder(holder: HVH, position: Int) {
        holder.tvTitle.text = data.title
        holder.tvDesc.text = data.description
        holder.btnCta.visibility = if (data.showCta) View.VISIBLE else View.GONE
        holder.btnCta.text = data.ctaText

        holder.btnCta.setOnClickListener { onCtaClick?.invoke() }

        data.imageRes?.let {
            holder.ivImage.setImageResource(it)
        } ?: run {
            holder.ivImage.setImageResource(R.drawable.ic_placeholder)
        }


        // For now we use the placeholder image defined in XML. If you later want a dynamic image:
        // Glide.with(holder.itemView).load(imageUrl).centerCrop().into(holder.ivImage)
    }

    override fun getItemCount(): Int = 1

    fun setData(newData: HeaderData) {
        this.data = newData
        notifyItemChanged(0)
    }
}
