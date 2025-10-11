package com.example.basicfiredatabase.fragments

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.example.basicfiredatabase.R
import com.google.android.material.tabs.TabLayoutMediator
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout

class EventsFragment : Fragment(R.layout.fragment_events) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val viewPager = view.findViewById<ViewPager2>(R.id.viewPager)
        val tabLayout = view.findViewById<TabLayout>(R.id.tabLayout)

        viewPager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int = 3


            override fun createFragment(position: Int) = when (position) {
                0 -> AllUsersFragment.newInstance(true)
                1 -> AllUsersFragment.newInstance(false)
                2 -> HelloFragment.newInstance()
                else -> throw IndexOutOfBoundsException("Invalid page index")
            }
        }

        // keep titles tidy — fetch strings from resources
        val titles = arrayOf(
            getString(R.string.tab_upcoming_events),
            getString(R.string.tab_past_events),
            getString(R.string.tab_hello)
        )

        TabLayoutMediator(tabLayout, viewPager) { tab, pos ->
            tab.text = titles[pos]
            tab.setIcon(
                when (pos) {
                    0 -> R.drawable.ic_calendar
                    1 -> R.drawable.ic_broken_image
                    else -> R.drawable.ic_location_boxed
                }
            )
        }.attach()
    }
}
