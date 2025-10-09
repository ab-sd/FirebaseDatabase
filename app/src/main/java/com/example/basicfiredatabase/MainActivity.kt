package com.example.basicfiredatabase

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.example.basicfiredatabase.fragments.AddUserFragment
import com.example.basicfiredatabase.fragments.EventsFragment
import com.example.basicfiredatabase.fragments.GalleryFragment
import com.example.basicfiredatabase.utils.LanguagePrefs
import com.example.basicfiredatabase.utils.LocaleHelper
import com.google.android.material.navigation.NavigationView
import com.google.firebase.ktx.Firebase
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.FirebaseApp


class MainActivity : AppCompatActivity() {

    companion object {
        // forwarded to TransitionAnimationActivity (and then back to MainActivity)
        const val EXTRA_FORWARD_FRAGMENT_CLASS = "extra_forward_fragment_class"
        const val EXTRA_FORWARD_FRAGMENT_ARGS = "extra_forward_fragment_args"
    }

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var drawerToggle: ActionBarDrawerToggle
    private val db by lazy { Firebase.firestore }

    // Wrap the base context so resources use the selected locale
    override fun attachBaseContext(newBase: Context) {
        val lang = LanguagePrefs.current() // LanguagePrefs should be initialized in MyApplication.onCreate()
        val wrapped = LocaleHelper.wrapContext(newBase, lang)
        super.attachBaseContext(wrapped)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // make content draw behind system bars so we can control padding precisely
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // near top of onCreate, after setDecorFitsSystemWindows(...)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        setContentView(R.layout.activity_main)

        // --- Restore forwarded fragment after language change (if any) ----------------
        intent?.let { launchIntent ->
            val fragClassName = launchIntent.getStringExtra(EXTRA_FORWARD_FRAGMENT_CLASS)
            val fragArgs = launchIntent.getBundleExtra(EXTRA_FORWARD_FRAGMENT_ARGS)

            if (!fragClassName.isNullOrBlank()) {
                try {
                    val clazz = Class.forName(fragClassName).asSubclass(androidx.fragment.app.Fragment::class.java)
                    val newFrag = clazz.newInstance() // uses zero-arg constructor
                    // Apply forwarded arguments if present
                    if (fragArgs != null) newFrag.arguments = fragArgs

                    // Replace the container with the restored fragment (do not add to back stack)
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.fragment_container, newFrag)
                        .commitAllowingStateLoss()
                } catch (e: Exception) {
                    // If anything fails (class not found, instantiation problem), silently fallback to default
                    e.printStackTrace()
                }
            }
        }


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
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.setPadding(navBars.left, 0, navBars.right, navBars.bottom)
            insets
        }

        // init firebase
        FirebaseApp.initializeApp(this)

        // Drawer + toolbar setup
        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        // make action view align with nav icon
        toolbar.contentInsetEndWithActions = 0
        toolbar.contentInsetStartWithNavigation = 0
        toolbar.setContentInsetsRelative(0, 0)

        val navView = findViewById<NavigationView>(R.id.nav_view)

        drawerToggle = ActionBarDrawerToggle(this, drawerLayout, R.string.open_drawer, R.string.close_drawer)
        drawerLayout.addDrawerListener(drawerToggle)
        drawerToggle.syncState()
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
                        .replace(R.id.fragment_container, EventsFragment())
                        .addToBackStack(null)
                        .commit()
                    drawerLayout.closeDrawer(GravityCompat.START)
                    true
                }
                R.id.nav_gallery -> {
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.fragment_container, GalleryFragment())
                        .addToBackStack(null)
                        .commit()
                    drawerLayout.closeDrawer(GravityCompat.START)
                    true
                }
                else -> false
            }
        }
    }

    // Inflate the top-right overflow menu (res/menu/main_menu.xml)
    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)

        // Ensure toolbar insets are minimized so icon lines up with nav icon
        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        toolbar.contentInsetEndWithActions = 0
        toolbar.contentInsetStartWithNavigation = 0
        toolbar.setContentInsetsRelative(0, 0)

        // Find the action view's button and wire up popup
        val langItem = menu?.findItem(R.id.action_language)
        val actionView = langItem?.actionView
        val iconBtn = actionView?.findViewById<View>(R.id.btn_lang_icon)

        iconBtn?.setOnClickListener { anchor ->
            val popup = androidx.appcompat.widget.PopupMenu(this, anchor)
            popup.menuInflater.inflate(R.menu.lang_popup_menu, popup.menu)

            // Make the menu single-choice-looking (optional)
            popup.menu.setGroupCheckable(0, true, true)
            when (LanguagePrefs.current()) {
                "en" -> popup.menu.findItem(R.id.lang_en)?.isChecked = true
                "af" -> popup.menu.findItem(R.id.lang_af)?.isChecked = true
                "zu" -> popup.menu.findItem(R.id.lang_zu)?.isChecked = true
            }

            popup.setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    R.id.lang_en -> { changeLanguage("en"); true }
                    R.id.lang_af -> { changeLanguage("af"); true }
                    R.id.lang_zu -> { changeLanguage("zu"); true }
                    else -> false
                }
            }

            popup.show()
        }

        return true
    }



    // Handle drawer toggle + Up button (language choices handled by the popup on the action view)
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Let the drawer toggle (hamburger) handle nav first
        if (drawerToggle.onOptionsItemSelected(item)) return true

        // Treat the action bar Up (home) button as a "pop fragment" action
        if (item.itemId == android.R.id.home) {
            // If drawer is not open and we have back stack entries, pop one
            if (!drawerLayout.isDrawerOpen(GravityCompat.START) &&
                supportFragmentManager.backStackEntryCount > 0) {
                supportFragmentManager.popBackStack()
                return true
            }
        }

        return super.onOptionsItemSelected(item)
    }


    private fun changeLanguage(langCode: String) {
        // Determine the current fragment hosted in R.id.fragment_container
        val currentFragment = supportFragmentManager.findFragmentById(R.id.fragment_container)

        val forwardIntent = Intent(this, TransitionAnimationActivity::class.java).apply {
            putExtra(TransitionAnimationActivity.EXTRA_LANG, langCode)

            // If a fragment exists, pass its class name so the app can restore it after restart
            currentFragment?.let { frag ->
                // pass fragment's class name
                putExtra(EXTRA_FORWARD_FRAGMENT_CLASS, frag::class.java.name)
                // pass arguments bundle if any (Bundle is Parcelable)
                frag.arguments?.let { putExtra(EXTRA_FORWARD_FRAGMENT_ARGS, it) }
            }
        }

        startActivity(forwardIntent)
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
    }

}
