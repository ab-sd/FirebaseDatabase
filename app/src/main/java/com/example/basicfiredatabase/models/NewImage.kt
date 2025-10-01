package com.example.basicfiredatabase.models

import android.net.Uri

data class NewImage(val id: String, val uri: Uri)

private val newImageUris = mutableListOf<NewImage>()
