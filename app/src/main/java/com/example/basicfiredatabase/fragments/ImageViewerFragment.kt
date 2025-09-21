package com.example.basicfiredatabase.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.Glide
import com.example.basicfiredatabase.R

class ImageViewerFragment : Fragment() {

    private lateinit var viewPager: ViewPager2
    private lateinit var images: List<String>
    private var startIndex: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        images = arguments?.getStringArrayList("images") ?: emptyList()
        startIndex = arguments?.getInt("startIndex") ?: 0
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = inflater.inflate(R.layout.fragment_image_viewer, container, false)
        viewPager = root.findViewById(R.id.viewPager)
        val closeBtn = root.findViewById<ImageButton>(R.id.btn_close)

        viewPager.adapter = ImagePagerAdapter(images)
        viewPager.setCurrentItem(startIndex, false)

        closeBtn.setOnClickListener {
            // close the viewer by popping back stack
            parentFragmentManager.popBackStack()
        }

        return root
    }

    class ImagePagerAdapter(private val urls: List<String>) :
        RecyclerView.Adapter<ImagePagerAdapter.VH>() {

        class VH(val imageView: ImageView) : RecyclerView.ViewHolder(imageView)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val iv = ImageView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                // Fit full image in view (no cropping)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            return VH(iv)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            Glide.with(holder.imageView.context)
                .load(urls[position])
                .into(holder.imageView)
        }

        override fun getItemCount(): Int = urls.size
    }

    companion object {
        fun newInstance(urls: List<String>, startIndex: Int) = ImageViewerFragment().apply {
            arguments = Bundle().apply {
                putStringArrayList("images", ArrayList(urls))
                putInt("startIndex", startIndex)
            }
        }
    }
}
