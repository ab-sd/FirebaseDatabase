package com.example.basicfiredatabase.models

data class User(
    val id: String,
    val username: String,
    val age: Int? = null,
    val images: List<UserImage> = emptyList(),
    var expanded: Boolean = false
)

data class UserImage(
    val url: String,
    val public_id: String
)
