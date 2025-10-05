package com.example.basicfiredatabase.utils

import java.util.Locale

object DescriptionKeyMapper {
    /**
     * Map a language code (e.g. "en", "af", "zu", or "en-US") to the Firestore
     * description map key: "primary" | "secondary" | "tertiary".
     */
    fun mapLangToKey(rawLang: String?): String {
        val code = (rawLang ?: "en").substringBefore('_').lowercase(Locale.getDefault())
        return when (code) {
            "zu" -> "secondary"
            "af" -> "tertiary"
            else -> "primary"
        }
    }
}