package com.example.basicfiredatabase.utils

import android.content.Context
import android.content.SharedPreferences

object LanguagePrefs {
    private const val PREFS_NAME = "language_prefs"
    private const val KEY_LANG = "key_lang"
    private const val DEFAULT_LANG = "en"

    private lateinit var prefs: SharedPreferences

    // call once from Application.onCreate()
    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getLanguage(): String {
        if (!::prefs.isInitialized) throw IllegalStateException("LanguagePrefs not initialized. Call LanguagePrefs.init(context) in Application.onCreate()")
        return prefs.getString(KEY_LANG, DEFAULT_LANG) ?: DEFAULT_LANG
    }

    fun setLanguage(lang: String) {
        if (!::prefs.isInitialized) throw IllegalStateException("LanguagePrefs not initialized. Call LanguagePrefs.init(context) in Application.onCreate()")
        prefs.edit().putString(KEY_LANG, lang).apply()
    }
}
