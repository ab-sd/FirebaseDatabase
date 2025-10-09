package com.example.basicfiredatabase

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.basicfiredatabase.utils.LanguagePrefs

class TransitionAnimationActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Use a no-action-bar theme / blank layout
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_transition_animation)

        // Get target language from intent
        val lang = intent?.getStringExtra(EXTRA_LANG) ?: run {
            finish()
            return
        }

        // Apply language preference (persist)
        LanguagePrefs.setLanguage(applicationContext, lang)

        // Optionally a very short posted runnable to let the animation start
        // (not a long wait; we immediately restart the app)
        window.decorView.post {
            // Start a fresh MainActivity and clear existing stack so we reload resources
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(intent)
            // fade animation as MainActivity comes back
            overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
            // finish this blank activity
            finish()
        }
    }

    companion object {
        const val EXTRA_LANG = "extra_lang"
    }
}