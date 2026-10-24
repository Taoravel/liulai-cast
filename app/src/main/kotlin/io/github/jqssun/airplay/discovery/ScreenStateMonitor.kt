package io.github.jqssun.airplay.discovery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Display
import androidx.core.content.ContextCompat

/** Uses both power and display state: TV standby is not the same as an idle app. */
class ScreenStateMonitor(private val context: Context, private val changed: (Boolean) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private val displays = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private var started = false
    private var receiverRegistered = false
    private var displayRegistered = false
    private val refresh = Runnable { if (started) changed(isAwake(context)) }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!started) return
            handler.removeCallbacks(refresh)
            if (intent.action == Intent.ACTION_SCREEN_OFF) changed(false)
            else handler.postDelayed(refresh, 300)
        }
    }
    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = schedule(displayId)
        override fun onDisplayRemoved(displayId: Int) = schedule(displayId)
        override fun onDisplayChanged(displayId: Int) = schedule(displayId)
        private fun schedule(id: Int) {
            if (id == Display.DEFAULT_DISPLAY && started) {
                handler.removeCallbacks(refresh)
                handler.post(refresh)
            }
        }
    }
    fun start() {
        if (started) return
        started = true
        try {
            ContextCompat.registerReceiver(context, receiver, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }, ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
            displays.registerDisplayListener(listener, handler)
            displayRegistered = true
        } finally { changed(isAwake(context)) }
    }
    fun close() {
        started = false
        handler.removeCallbacksAndMessages(null)
        if (receiverRegistered) runCatching { context.unregisterReceiver(receiver) }
        if (displayRegistered) runCatching { displays.unregisterDisplayListener(listener) }
        receiverRegistered = false
        displayRegistered = false
    }
    companion object {
        fun isAwake(context: Context): Boolean {
            val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val displays = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            return power.isInteractive && displays.getDisplay(Display.DEFAULT_DISPLAY)?.state == Display.STATE_ON
        }
    }
}
