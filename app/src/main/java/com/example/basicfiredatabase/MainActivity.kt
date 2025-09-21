package com.example.basicfiredatabase

import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import android.view.WindowManager
import android.widget.FrameLayout
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
}
