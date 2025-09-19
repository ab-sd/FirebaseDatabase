package com.example.basicfiredatabase.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.basicfiredatabase.R
import com.example.basicfiredatabase.models.User

class UserAdapter(
    private val items: MutableList<User> = mutableListOf(),
    private val onEdit: (User) -> Unit,
    private val onDelete: (User) -> Unit
) : RecyclerView.Adapter<UserAdapter.VH>() {

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvName: TextView = itemView.findViewById(R.id.tv_name)
        val ivExpand: ImageView = itemView.findViewById(R.id.iv_expand)
        val llDetails: LinearLayout = itemView.findViewById(R.id.ll_details)
        val tvAge: TextView = itemView.findViewById(R.id.tv_age)
        val llImages: LinearLayout = itemView.findViewById(R.id.ll_images)

        // new buttons for edit & delete
        val btnEdit: Button = itemView.findViewById(R.id.btn_edit)
        val btnDelete: Button = itemView.findViewById(R.id.btn_delete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_user, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val user = items[position]
        holder.tvName.text = user.username
        holder.tvAge.text = "Age: ${user.age ?: "N/A"}"

        // expand/collapse UI
        holder.llDetails.visibility = if (user.expanded) View.VISIBLE else View.GONE
        holder.ivExpand.rotation = if (user.expanded) 180f else 0f

        // populate images
        holder.llImages.removeAllViews()
        if (user.expanded && user.images.isNotEmpty()) {
            val ctx = holder.itemView.context
            val sizePx = (ctx.resources.displayMetrics.density * 80).toInt()
            val margin = (ctx.resources.displayMetrics.density * 6).toInt()
            for (url in user.images) {
                val iv = ImageView(ctx)
                val lp = LinearLayout.LayoutParams(sizePx, sizePx)
                lp.setMargins(margin, margin, margin, margin)
                iv.layoutParams = lp
                iv.scaleType = ImageView.ScaleType.CENTER_CROP
                Glide.with(ctx).load(url).into(iv)
                holder.llImages.addView(iv)
            }
        }

        // expand/collapse on row click
        holder.itemView.setOnClickListener {
            user.expanded = !user.expanded
            holder.ivExpand.animate().rotation(if (user.expanded) 180f else 0f).setDuration(150).start()
            holder.llDetails.visibility = if (user.expanded) View.VISIBLE else View.GONE
            notifyItemChanged(position)
        }

        // edit/delete button actions
        holder.btnEdit.setOnClickListener { onEdit(user) }
        holder.btnDelete.setOnClickListener { onDelete(user) }
    }

    override fun getItemCount(): Int = items.size

    fun setItems(newItems: List<User>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }
}
