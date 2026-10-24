package io.github.jqssun.airplay

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import io.github.jqssun.airplay.mirror.MirrorSenderActivity
import io.github.jqssun.airplay.ui.isTvDevice

/** Phones open the sender, TVs open the receiver, without starting an unused phone receiver. */
class LaunchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, if (isTvDevice()) MainActivity::class.java else MirrorSenderActivity::class.java))
        finish()
    }
}
