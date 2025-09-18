package com.example.basicfiredatabase.fragments

import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.example.basicfiredatabase.R
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import com.google.firebase.ktx.Firebase
import com.google.firebase.firestore.ktx.firestore

class AddUserFragment : Fragment(R.layout.fragment_add_user) {

    private val db by lazy { Firebase.firestore }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val etUsername = view.findViewById<EditText>(R.id.et_username)
        val etAge = view.findViewById<EditText>(R.id.et_age)
        val btnSave = view.findViewById<Button>(R.id.btn_save)

        btnSave.setOnClickListener {
            val username = etUsername.text.toString().trim()
            val ageTxt = etAge.text.toString().trim()

            if (username.isEmpty() || ageTxt.isEmpty()) {
                Toast.makeText(requireContext(), "Fill both fields", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val age = ageTxt.toIntOrNull()
            if (age == null) {
                Toast.makeText(requireContext(), "Enter a valid age", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val user = hashMapOf(
                "username" to username,
                "age" to age
            )

            db.collection("users")
                .add(user)
                .addOnSuccessListener { docRef ->
                    Toast.makeText(requireContext(), "Saved (id=${docRef.id})", Toast.LENGTH_SHORT).show()
                    etUsername.text?.clear()
                    etAge.text?.clear()
                }
                .addOnFailureListener { e ->
                    Toast.makeText(requireContext(), "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
        }
    }
}
