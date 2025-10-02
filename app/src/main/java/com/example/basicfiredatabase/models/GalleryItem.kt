package com.example.basicfiredatabase.models

sealed class GalleryItem {
    data class Header(val eventId: String, val title: String, val date: String) : GalleryItem()
    data class Image(val eventId: String, val imageUrl: String) : GalleryItem()
}
