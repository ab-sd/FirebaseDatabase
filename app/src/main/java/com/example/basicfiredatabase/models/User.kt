package com.example.basicfiredatabase.models

data class User(
    val id: String = "",
    val title: String = "",
    val eventType: String = "",
    val descriptions: Map<String, String> = emptyMap(), // keys: "primary","secondary","tertiary"
    val date: String = "", // yyyy-MM-dd
    val time: String = "", // HH:mm
    val durationMinutes: Int? = null,
    val images: List<UserImage> = emptyList(),
    val location: String? = null,

    // NEW: whether a map link was included and the actual link (nullable)
    val includeMapLink: Boolean = false,
    val mapLink: String? = null,

    val isUpcoming: Boolean = true,
    var expanded: Boolean = false
)

data class UserImage(
    val url: String = "",
    val public_id: String = ""
)
