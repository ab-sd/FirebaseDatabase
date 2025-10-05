package com.example.basicfiredatabase

import android.app.Application

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // initialize language prefs early so Activities can read the chosen language in attachBaseContext
        LanguagePrefs.init(applicationContext)
    }
}
