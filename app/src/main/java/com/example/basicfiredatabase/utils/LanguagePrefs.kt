package com.example.basicfiredatabase.utils

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object LanguagePrefs {
    private const val PREFS_NAME = "language_prefs"
    private const val KEY_LANG = "key_language"
    private const val DEFAULT_LANG = "en"

    private lateinit var prefs: SharedPreferences

    // Start with a default so reading before init is safe (will be updated in init)
    private val _langFlow = MutableStateFlow(DEFAULT_LANG)
    val langFlow: StateFlow<String> = _langFlow

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_LANG, DEFAULT_LANG) ?: DEFAULT_LANG
        _langFlow.value = stored
    }

    fun setLanguage(context: Context, langCode: String) {
        // persist
        if (!::prefs.isInitialized) {
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
        prefs.edit().putString(KEY_LANG, langCode).apply()
        _langFlow.value = langCode
    }

    fun current(): String = _langFlow.value
}
