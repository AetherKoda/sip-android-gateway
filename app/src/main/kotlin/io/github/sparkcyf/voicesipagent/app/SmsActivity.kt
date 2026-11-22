package io.github.sparkcyf.voicesipagent.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** Retains the SENDTO handler required by the default SMS role without exposing message contents. */
class SmsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }
}
