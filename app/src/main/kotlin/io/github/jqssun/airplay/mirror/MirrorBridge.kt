package io.github.jqssun.airplay.mirror

import android.view.Surface

/** Bridges the running MirrorReceiver and the activity that displays the decoded mirror. */
object MirrorBridge {
    @Volatile var receiver: MirrorReceiver? = null

    /** set by the service: brings up the full-screen receiver display activity */
    @Volatile var launchDisplay: (() -> Unit)? = null

    /** set by the display activity so the receiver can dismiss it on disconnect */
    @Volatile var finishDisplay: (() -> Unit)? = null

    fun onDisplaySurface(surface: Surface) { receiver?.setSurface(surface) }
    fun onDisplaySurfaceGone(surface: Surface) { receiver?.clearSurface(surface) }
}
