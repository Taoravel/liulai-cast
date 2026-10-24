package io.github.jqssun.airplay.mirror

import android.content.Context
import io.github.jqssun.airplay.discovery.NsdServiceManager
import android.util.Log
import android.media.AudioTrack
import android.media.AudioAttributes
import android.media.AudioFormat
import android.view.Surface
import io.github.jqssun.airplay.renderer.VideoRenderer
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Receives an in-app screen-mirror stream from another device running this app and decodes
 * it through the same proven H.264 pipeline used for AirPlay. Advertised over mDNS so the
 * sender can discover it. One active mirror at a time.
 */
class MirrorReceiver(private val context: Context, private val deviceName: String) {

    private val tag = "MirrorReceiver"
    private var server: ServerSocket? = null
    private var nsd: NsdServiceManager? = null
    @Volatile private var running = false
    @Volatile private var client: Socket? = null

    val videoRenderer = VideoRenderer()

    fun setSurface(s: Surface) = videoRenderer.setSurface(s)
    fun clearSurface(s: Surface) = videoRenderer.clearSurface(s)

    fun start() {
        if (running) return
        running = true
        MirrorBridge.receiver = this
        val srv = ServerSocket(MirrorProtocol.DEFAULT_PORT)
        server = srv
        registerNsd(srv.localPort)
        thread(name = "mirror-accept", isDaemon = true) {
            while (running) {
                val sock = try {
                    srv.accept()
                } catch (e: Exception) {
                    if (running) Log.w(tag, "accept: ${e.message}"); break
                }
                client?.let { try { it.close() } catch (_: Exception) {} } // one mirror at a time
                client = sock
                thread(isDaemon = true) { handleClient(sock) }
            }
        }
        Log.i(tag, "Mirror receiver listening on ${srv.localPort}")
    }

    private fun handleClient(sock: Socket) {
        var audio: AudioTrack? = null
        try {
            sock.soTimeout = 15_000
            sock.tcpNoDelay = true
            val din = DataInputStream(BufferedInputStream(sock.getInputStream(), 1 shl 16))
            if (din.readInt() != MirrorProtocol.MAGIC) { Log.w(tag, "bad magic"); return }
            val w = din.readInt()
            val h = din.readInt()
            if (w <= 0 || h <= 0 || w > 7680 || h > 7680) { Log.w(tag, "bad dims ${w}x$h"); return }
            Log.i(tag, "Mirror stream ${w}x$h")
            sock.soTimeout = 0 // An unchanged screen can legitimately pause encoded output.
            videoRenderer.setResolution(w, h)
            MirrorBridge.launchDisplay?.invoke()
            while (running && !sock.isClosed) {
                val frame = MirrorProtocol.readFrame(din)
                if (client !== sock) break
                if (frame.flags == MirrorProtocol.FLAG_AUDIO) {
                    if (audio == null) {
                        val format = AudioFormat.Builder().setSampleRate(MirrorProtocol.AUDIO_SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
                        val minimum = AudioTrack.getMinBufferSize(MirrorProtocol.AUDIO_SAMPLE_RATE,
                            AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
                        audio = AudioTrack.Builder().setAudioFormat(format)
                            .setAudioAttributes(AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                            .setBufferSizeInBytes(maxOf(minimum * 2, 19200))
                            .setTransferMode(AudioTrack.MODE_STREAM).build().also { it.play() }
                    }
                    var offset = 0
                    while (offset < frame.payload.size && running && client === sock) {
                        val written = audio.write(frame.payload, offset, frame.payload.size - offset, AudioTrack.WRITE_BLOCKING)
                        if (written <= 0) break
                        offset += written
                    }
                } else {
                    videoRenderer.feedFrame(frame.payload, System.nanoTime(), false)
                }
            }
        } catch (e: Exception) {
            Log.i(tag, "client ended: ${e.message}")
        } finally {
            try { audio?.stop() } catch (_: Exception) {}
            try { audio?.release() } catch (_: Exception) {}
            try { sock.close() } catch (_: Exception) {}
            if (client === sock) {
                client = null
                videoRenderer.release()
                MirrorBridge.finishDisplay?.invoke()
            }
        }
    }

    private fun registerNsd(port: Int) {
        nsd = NsdServiceManager(context.applicationContext).apply {
            acquireMulticastLock()
            registerMirror(deviceName, port, MirrorProtocol.SERVICE_TYPE)
        }
    }

    fun stop() {
        if (!running) return
        running = false
        nsd?.release()
        nsd = null
        try { client?.close() } catch (_: Exception) {}
        client = null
        try { server?.close() } catch (_: Exception) {}
        server = null
        videoRenderer.release()
        if (MirrorBridge.receiver === this) MirrorBridge.receiver = null
        MirrorBridge.finishDisplay?.invoke()
        Log.i(tag, "Mirror receiver stopped")
    }
}
