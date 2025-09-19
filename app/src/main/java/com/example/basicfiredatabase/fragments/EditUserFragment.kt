package com.example.basicfiredatabase.fragments

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.basicfiredatabase.R
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase

class EditUserFragment : Fragment(R.layout.fragment_edit_user) {

    private val db = Firebase.firestore
    private var userId: String? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val etName = view.findViewById<EditText>(R.id.et_edit_name)
        val etAge = view.findViewById<EditText>(R.id.et_edit_age)
        val btnUpdate = view.findViewById<Button>(R.id.btn_update)
        val btnDelete = view.findViewById<Button>(R.id.btn_delete)

        // Get args
        userId = arguments?.getString("id")
        val name = arguments?.getString("username") ?: ""
        val age = arguments?.getInt("age") ?: 0

        // Prefill inputs
        etName.setText(name)
        etAge.setText(age.toString())

        // Update
        btnUpdate.setOnClickListener {
            val newName = etName.text.toString().trim()
            val newAge = etAge.text.toString().toIntOrNull()

            if (userId == null || newName.isEmpty() || newAge == null) {
                Toast.makeText(requireContext(), "Fill in details", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            db.collection("users").document(userId!!)
                .update(mapOf(
                    "username" to newName,
                    "age" to newAge
                ))
                .addOnSuccessListener {
                    Toast.makeText(requireContext(), "Updated!", Toast.LENGTH_SHORT).show()
                    parentFragmentManager.popBackStack() // go back
                }
                .addOnFailureListener { e ->
                    Toast.makeText(requireContext(), "Update failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
        }

        // Delete
        btnDelete.setOnClickListener {
            if (userId == null) return@setOnClickListener
            db.collection("users").document(userId!!)
                .delete()
                .addOnSuccessListener {
                    Toast.makeText(requireContext(), "Deleted!", Toast.LENGTH_SHORT).show()
                    parentFragmentManager.popBackStack()
                }
                .addOnFailureListener { e ->
                    Toast.makeText(requireContext(), "Delete failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
        }
    }
}
