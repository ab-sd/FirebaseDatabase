package com.example.basicfiredatabase.models

data class User(
    val id: String,
    val username: String,
    val age: Int? = null,
    val images: List<String> = emptyList(),
    var expanded: Boolean = false
)
