package io.github.jqssun.airplay.mirror

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager

/** Full-screen display for an incoming in-app screen mirror. */
class MirrorReceiverActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        goFullscreen()
        MirrorBridge.finishDisplay = { runOnUiThread { finish() } }

        val view = SurfaceView(this)
        view.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) = MirrorBridge.onDisplaySurface(h.surface)
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) = MirrorBridge.onDisplaySurface(h.surface)
            override fun surfaceDestroyed(h: SurfaceHolder) = MirrorBridge.onDisplaySurfaceGone(h.surface)
        })
        setContentView(view)
    }

    private fun goFullscreen() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.hide(android.view.WindowInsets.Type.systemBars())
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
                android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        MirrorBridge.finishDisplay = null
    }
}
