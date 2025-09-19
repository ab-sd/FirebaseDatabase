package com.example.basicfiredatabase.fragments

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.basicfiredatabase.R
import com.example.basicfiredatabase.adapters.UserAdapter
import com.example.basicfiredatabase.models.User
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase

class AllUsersFragment : Fragment(R.layout.fragment_all_users) {

    private val db = Firebase.firestore
    private lateinit var rv: RecyclerView

    private val adapter = UserAdapter(
        onEdit = { user ->
            val fragment = EditUserFragment().apply {
                arguments = Bundle().apply {
                    putString("id", user.id)
                    putString("username", user.username)
                    putInt("age", user.age ?: 0)
                }
            }
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, fragment) // your container id
                .addToBackStack(null)
                .commit()
        },
        onDelete = { user ->
            db.collection("users").document(user.id)
                .delete()
                .addOnSuccessListener {
                    Toast.makeText(requireContext(), "Deleted ${user.username}", Toast.LENGTH_SHORT).show()
                }
                .addOnFailureListener { e ->
                    Log.w("AllUsersFragment", "Error deleting", e)
                    Toast.makeText(requireContext(), "Delete failed", Toast.LENGTH_SHORT).show()
                }
        }
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        rv = view.findViewById(R.id.rv_users)
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter

        // realtime listener - keeps UI updated
        db.collection("users")
            .addSnapshotListener { snapshots, e ->
                if (e != null) {
                    Log.w("AllUsersFragment", "Listen failed.", e)
                    return@addSnapshotListener
                }
                if (snapshots == null) return@addSnapshotListener

                val list = snapshots.documents.map { doc ->
                    val id = doc.id
                    val username = doc.getString("username") ?: "No name"
                    val age = doc.getLong("age")?.toInt()
                    val images = (doc.get("images") as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
                    User(id = id, username = username, age = age, images = images)
                }
                adapter.setItems(list)
            }
    }
}
