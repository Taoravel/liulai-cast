package io.github.jqssun.airplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import io.github.jqssun.airplay.service.AirPlayService
import io.github.jqssun.airplay.ui.isTvDevice

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!context.isTvDevice()) return // Sender phones do not need a receiver at boot.

        val prefs = context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(Prefs.BOOT_AUTO_START, Prefs.DEF_BOOT_AUTO_START)) return

        val serviceIntent = Intent(context, AirPlayService::class.java)
            .setAction(AirPlayService.ACTION_START_SERVER)
        ContextCompat.startForegroundService(context, serviceIntent)
    }
}
