package com.example.basicfiredatabase.fragments

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.example.basicfiredatabase.R

class HelloFragment : Fragment(R.layout.fragment_hello) {
    companion object {
        fun newInstance(): HelloFragment = HelloFragment()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // If you want to change the text programmatically:
         view.findViewById<TextView>(R.id.hello_text).text = "Hello from code!"
    }
}
