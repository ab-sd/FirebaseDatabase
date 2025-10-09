package com.example.basicfiredatabase

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.basicfiredatabase.utils.LanguagePrefs

class TransitionAnimationActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_LANG = "extra_lang"
        private const val TRANSITION_SHOW_MS = 800L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_transition_animation)

        val lang = intent?.getStringExtra(EXTRA_LANG) ?: run {
            finish()
            return
        }

        // persist language selection
        LanguagePrefs.setLanguage(applicationContext, lang)

        // small delay so TransitionAnimationActivity's enter animation is visible
        window.decorView.postDelayed({
            // Build intent to restart MainActivity, forwarding ALL extras we received
            val forward = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK

                // Copy extras from incoming intent except the language extra we've just handled.
                val incoming = intent?.extras
                if (incoming != null) {
                    for (key in incoming.keySet()) {
                        if (key == EXTRA_LANG) continue
                        val value = incoming.get(key)
                        when (value) {
                            is Int -> putExtra(key, value)
                            is Long -> putExtra(key, value)
                            is CharSequence -> putExtra(key, value)
                            is String -> putExtra(key, value)
                            is Boolean -> putExtra(key, value)
                            is Float -> putExtra(key, value)
                            is Double -> putExtra(key, value)
                            is Bundle -> putExtra(key, value)
                            is java.io.Serializable -> putExtra(key, value)
                            // Add other types here as needed
                            else -> {
                                // Unsupported extra type — skip
                            }
                        }
                    }
                }
            }

            startActivity(forward)
            overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
            finish()
        }, TRANSITION_SHOW_MS)
    }
}
