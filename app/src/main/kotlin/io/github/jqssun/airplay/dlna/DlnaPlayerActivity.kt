package io.github.jqssun.airplay.dlna

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/** Full-screen ExoPlayer that renders media URLs pushed to the DLNA renderer. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class DlnaPlayerActivity : Activity(), DlnaController.PlayerControl {

    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private val handler = Handler(Looper.getMainLooper())

    private val progressTick = object : Runnable {
        override fun run() {
            player?.let {
                DlnaController.positionMs = it.currentPosition.coerceAtLeast(0)
                DlnaController.durationMs = if (it.duration > 0) it.duration else 0
            }
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        goFullscreen()

        val view = PlayerView(this).apply {
            useController = true
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
        }
        playerView = view
        setContentView(view)

        val exo = ExoPlayer.Builder(this).build()
        player = exo
        view.player = exo
        exo.setVolume(DlnaController.volume / 100f)
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) = syncState()
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = syncState()
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                DlnaController.transportState = DlnaController.STATE_STOPPED
            }
        })

        DlnaController.player = this
        playUri(DlnaController.uri ?: intent?.getStringExtra(EXTRA_URI))
        handler.post(progressTick)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        playUri(DlnaController.uri ?: intent?.getStringExtra(EXTRA_URI))
    }

    private fun playUri(uri: String?) {
        val p = player ?: return
        if (uri.isNullOrBlank()) return
        p.setMediaItem(MediaItem.fromUri(uri))
        p.prepare()
        p.playWhenReady = true
    }

    private fun syncState() {
        val p = player ?: return
        DlnaController.transportState = when (p.playbackState) {
            Player.STATE_BUFFERING -> DlnaController.STATE_TRANSITIONING
            Player.STATE_READY -> if (p.playWhenReady) DlnaController.STATE_PLAYING else DlnaController.STATE_PAUSED
            Player.STATE_ENDED -> DlnaController.STATE_STOPPED
            else -> DlnaController.STATE_STOPPED
        }
    }

    // ---- DlnaController.PlayerControl (called from renderer threads) ----
    override fun play() = handler.post { player?.play() }.let {}
    override fun pause() = handler.post { player?.pause() }.let {}
    override fun stop() = handler.post { finish() }.let {}
    override fun seekTo(ms: Long) = handler.post { player?.seekTo(ms) }.let {}
    override fun setVolume(percent: Int) = handler.post { player?.setVolume(percent / 100f) }.let {}

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
        handler.removeCallbacksAndMessages(null)
        if (DlnaController.player === this) DlnaController.player = null
        DlnaController.transportState = DlnaController.STATE_STOPPED
        player?.release()
        player = null
    }

    companion object {
        const val EXTRA_URI = "dlna_uri"
    }
}
