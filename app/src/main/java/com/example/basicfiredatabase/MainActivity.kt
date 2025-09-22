package com.example.basicfiredatabase

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.example.basicfiredatabase.fragments.AddUserFragment
import com.example.basicfiredatabase.fragments.AllUsersFragment
import com.google.android.material.navigation.NavigationView
import com.google.firebase.ktx.Firebase
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.FirebaseApp
import kotlin.text.replace

class MainActivity : AppCompatActivity() {

    private lateinit var drawerLayout: DrawerLayout
    private val db by lazy { Firebase.firestore }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)


        // make content draw behind system bars so we can control padding precisely
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // near top of onCreate, after setDecorFitsSystemWindows(...)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        setContentView(R.layout.activity_main)
        
        drawerLayout = findViewById(R.id.drawer_layout)
        val appBarLayout = findViewById<com.google.android.material.appbar.AppBarLayout>(R.id.appBarLayout)
        val fragmentContainer = findViewById<FrameLayout>(R.id.fragment_container)

        // --- Drawer: only add left/top/right system bar insets (DON'T add IME here) ---
        ViewCompat.setOnApplyWindowInsetsListener(drawerLayout) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            // leave bottom for fragment container so drawer does not double-pad
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, 0)
            insets
        }

        // --- Toolbar padding for status bar ONLY ---
        ViewCompat.setOnApplyWindowInsetsListener(appBarLayout) { v, insets ->
            val statusBar = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            v.setPadding(0, statusBar.top, 0, 0)
            insets
        }

        // --- Fragment container padding for navigation bar ONLY (so fragments sit above nav bar) ---
        // Let the fragment's ScrollView handle navigation + IME insets (so we avoid mismatches).
        ViewCompat.setOnApplyWindowInsetsListener(fragmentContainer) { v, insets ->
            // Do not modify bottom here
            v.setPadding(0, 0, 0, 0)
            insets
        }

        // init firebase
        FirebaseApp.initializeApp(this)

        // Drawer + toolbar setup
        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        val navView = findViewById<com.google.android.material.navigation.NavigationView>(R.id.nav_view)

        val toggle =
            ActionBarDrawerToggle(this, drawerLayout, R.string.open_drawer, R.string.close_drawer)
        drawerLayout.addDrawerListener(toggle)
        toggle.syncState()
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        navView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_add_user -> {
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.fragment_container, AddUserFragment())
                        .addToBackStack(null)
                        .commit()
                    drawerLayout.closeDrawer(GravityCompat.START)
                    true
                }
                R.id.nav_view_users -> {
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.fragment_container, AllUsersFragment())
                        .addToBackStack(null)
                        .commit()
                    drawerLayout.closeDrawer(GravityCompat.START)
                    true
                }
                else -> false
            }
        }




    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val handled = (ActionBarDrawerToggle(this, drawerLayout, R.string.open_drawer, R.string.close_drawer))
            .onOptionsItemSelected(item)
        return handled || super.onOptionsItemSelected(item)
    }

    // 🔵 New function to open Google Translate
    private fun openGoogleTranslate() {
        val originalText = "Hey, how are you doing? I am translating live using a redirect URL"
        val encodedText = Uri.encode(originalText)

        // --- 1) Try to open Google Translate app via ACTION_SEND ---
        val translatePackage = "com.google.android.apps.translate"
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, originalText)
            setPackage(translatePackage) // target Google Translate specifically
        }

        try {
            // resolveActivity can be affected by package visibility on Android 11+
            if (sendIntent.resolveActivity(packageManager) != null) {
                startActivity(sendIntent)
                return
            } else {
                Log.d("MainActivity", "Translate app not installed or not visible to package queries")
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error while trying to open Translate app", e)
            // continue to fallback
        }

        // --- 2) Fallback: open Translate web with prefilled text (opens browser or any handler) ---
        val webUrl = "https://translate.google.com/?sl=auto&tl=af&text=$encodedText&op=translate"
        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(webUrl))
        val chooser = Intent.createChooser(browserIntent, "Open translation with")

        try {
            startActivity(chooser)
        } catch (e: ActivityNotFoundException) {
            Log.e("MainActivity", "No app found to open translation URL", e)
            Toast.makeText(this, "No app available to open translation", Toast.LENGTH_SHORT).show()
        }
    }

}
