package io.github.jqssun.airplay.dlna

/**
 * Shared bridge between the UPnP/DLNA renderer (SSDP + HTTP/SOAP) and the ExoPlayer
 * activity that actually renders the casted media. The renderer mutates the requested
 * transport (uri/metadata) and issues play/pause/stop/seek; the player registers itself
 * and reports back live position/duration/state for GetPositionInfo / GetTransportInfo.
 */
object DlnaController {

    interface PlayerControl {
        fun play()
        fun pause()
        fun stop()
        fun seekTo(ms: Long)
        fun setVolume(percent: Int)
    }

    // UPnP transport states
    const val STATE_NO_MEDIA = "NO_MEDIA_PRESENT"
    const val STATE_STOPPED = "STOPPED"
    const val STATE_PLAYING = "PLAYING"
    const val STATE_PAUSED = "PAUSED_PLAYBACK"
    const val STATE_TRANSITIONING = "TRANSITIONING"

    @Volatile var uri: String? = null
    @Volatile var metadata: String = ""
    @Volatile var transportState: String = STATE_NO_MEDIA
    @Volatile var volume: Int = 50
    @Volatile var muted: Boolean = false

    @Volatile var positionMs: Long = 0
    @Volatile var durationMs: Long = 0

    @Volatile var player: PlayerControl? = null

    /** invoked by the renderer to bring up / focus the player activity for a new uri */
    @Volatile var launchPlayer: ((uri: String, metadata: String) -> Unit)? = null

    fun reset() {
        uri = null
        metadata = ""
        transportState = STATE_NO_MEDIA
        positionMs = 0
        durationMs = 0
    }
}
