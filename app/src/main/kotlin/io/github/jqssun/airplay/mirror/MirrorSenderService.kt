package io.github.jqssun.airplay.mirror

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.PendingIntent
import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioRecord
import android.media.AudioFormat
import android.media.AudioAttributes
import android.media.AudioPlaybackCaptureConfiguration
import androidx.core.content.ContextCompat
import android.widget.Toast
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.github.jqssun.airplay.R
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Sender side of in-app mirroring: captures this device's screen via MediaProjection,
 * H.264-encodes it, and streams it to a MirrorReceiver over TCP. Runs as a
 * mediaProjection foreground service (required on Android 10+).
 */
class MirrorSenderService : Service() {

    private val tag = "MirrorSender"
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: MediaCodec? = null
    private var socket: Socket? = null
    @Volatile private var running = false
    private var csd: ByteArray? = null // SPS+PPS
    @Volatile private var audioRecord: AudioRecord? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) { stopSelf(); return START_NOT_STICKY }
        if (intent.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (running) return START_NOT_STICKY // Projection grants are single-use on Android 14+.

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data = if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA)
        val host = intent.getStringExtra(EXTRA_HOST)
        val port = intent.getIntExtra(EXTRA_PORT, 0)
        if (data == null || host == null || port !in 1..65535 || resultCode != android.app.Activity.RESULT_OK) { stopSelf(); return START_NOT_STICKY }

        startForegroundNotification(host)

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        try {
        projection = mpm.getMediaProjection(resultCode, data).also {
            it.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopSelf() }
            }, Handler(Looper.getMainLooper()))
        }
        running = true
        thread(name = "mirror-stream", isDaemon = true) { stream(host, port) }
        MirrorSenderState.active = true
        MirrorSenderState.target = host
        } catch (e: Exception) {
            Log.w(tag, "Capture permission unavailable", e)
            mainHandler.post { Toast.makeText(this, R.string.mirror_failed, Toast.LENGTH_LONG).show() }
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun stream(host: String, port: Int) {
        var enc: MediaCodec? = null
        var inputSurface: android.view.Surface? = null
        var audioWorker: Thread? = null
        try {
            // capture size: real display, capped to 1080p, aligned to 16
            val dm = resources.displayMetrics
            val maxDim = 1280
            val scale = minOf(1f, maxDim.toFloat() / maxOf(dm.widthPixels, dm.heightPixels))
            val w = ((dm.widthPixels * scale).toInt() / 16) * 16
            val h = ((dm.heightPixels * scale).toInt() / 16) * 16
            val dpi = dm.densityDpi

            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, MirrorProtocol.DEFAULT_BITRATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, MirrorProtocol.DEFAULT_FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, MirrorProtocol.KEYFRAME_INTERVAL_S)
                if (Build.VERSION.SDK_INT >= 21) {
                    setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                }
            }
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            enc = codec
            encoder = codec
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = codec.createInputSurface()
            codec.start()

            virtualDisplay = projection!!.createVirtualDisplay(
                "airplay-mirror", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                inputSurface, null, null
            )

            val sock = Socket()
            socket = sock
            sock.tcpNoDelay = true
            sock.connect(InetSocketAddress(host, port), 8000)
            socket = sock
            val out = DataOutputStream(BufferedOutputStream(sock.getOutputStream(), 1 shl 16))
            out.writeInt(MirrorProtocol.MAGIC)
            out.writeInt(w)
            out.writeInt(h)
            out.flush()
            if (!running) return
            audioWorker = startAudio(out)
            Log.i(tag, "Mirroring ${w}x$h -> $host:$port")

            val info = MediaCodec.BufferInfo()
            while (running) {
                val idx = codec.dequeueOutputBuffer(info, 100_000)
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val output = codec.outputFormat
                    csd = listOfNotNull(output.getByteBuffer("csd-0"), output.getByteBuffer("csd-1"))
                        .fold(ByteArray(0)) { result, buf ->
                            val copy = buf.duplicate()
                            result + ByteArray(copy.remaining()).also { copy.get(it) }
                        }
                    continue
                }
                if (idx < 0) continue
                val buf = codec.getOutputBuffer(idx)
                if (buf != null && info.size > 0) {
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    val bytes = ByteArray(info.size)
                    buf.get(bytes)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        csd = bytes // SPS+PPS; prepended to keyframes
                    } else {
                        val keyframe = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                        val payload = if (keyframe && csd != null) csd!! + bytes else bytes
                        MirrorProtocol.writeFrame(out, if (keyframe) MirrorProtocol.FLAG_KEYFRAME else 0, payload)
                    }
                }
                codec.releaseOutputBuffer(idx, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        } catch (e: Exception) {
            Log.w(tag, "stream ended: ${e.message}")
            if (running) mainHandler.post { Toast.makeText(this, R.string.mirror_failed, Toast.LENGTH_LONG).show() }
        } finally {
            running = false
            try { audioRecord?.stop() } catch (_: Exception) {}
            try { socket?.close() } catch (_: Exception) {}
            audioWorker?.join(1000)
            try { virtualDisplay?.release() } catch (_: Exception) {}
            virtualDisplay = null
            try { enc?.stop() } catch (_: Exception) {}
            try { enc?.release() } catch (_: Exception) {}
            encoder = null
            inputSurface?.release()
            stopSelf()
        }
    }

    private fun startAudio(out: DataOutputStream): Thread? {
        if (Build.VERSION.SDK_INT < 29 || ContextCompat.checkSelfPermission(this,
                Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return null
        return thread(name = "mirror-audio", isDaemon = true) {
            var record: AudioRecord? = null
            try {
                val capture = AudioPlaybackCaptureConfiguration.Builder(projection!!)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build()
                val format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(MirrorProtocol.AUDIO_SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build()
                val minimum = AudioRecord.getMinBufferSize(MirrorProtocol.AUDIO_SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
                require(minimum > 0)
                record = AudioRecord.Builder().setAudioFormat(format)
                    .setBufferSizeInBytes(maxOf(minimum * 2, 19200))
                    .setAudioPlaybackCaptureConfig(capture).build()
                audioRecord = record
                check(record.state == AudioRecord.STATE_INITIALIZED)
                if (!running) return@thread
                record.startRecording()
                val bytes = ByteArray(3840) // 20 ms, 48 kHz stereo PCM16.
                while (running) {
                    val size = record.read(bytes, 0, bytes.size, AudioRecord.READ_BLOCKING)
                    if (size <= 0) break
                    val aligned = size / 4 * 4
                    if (aligned > 0) MirrorProtocol.writeFrame(out, MirrorProtocol.FLAG_AUDIO, bytes.copyOf(aligned))
                }
            } catch (e: Exception) {
                if (running) Log.w(tag, "Audio capture unavailable; continuing video", e)
            } finally {
                try { record?.stop() } catch (_: Exception) {}
                try { record?.release() } catch (_: Exception) {}
                if (audioRecord === record) audioRecord = null
            }
        }
    }

    private fun startForegroundNotification(host: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.mirror_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stop = PendingIntent.getService(this, 0, Intent(this, MirrorSenderService::class.java)
            .setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 1, Intent(this, MirrorSenderActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle(getString(R.string.mirror_notif_title))
            .setContentText(getString(R.string.mirror_notif_text, host))
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_media_pause, getString(R.string.mirror_stop), stop)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
        ServiceCompat.startForeground(this, NOTIF_ID, n, type)
    }

    override fun onDestroy() {
        running = false
        MirrorSenderState.active = false
        MirrorSenderState.target = null
        try { socket?.close() } catch (_: Exception) {}
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "mirror_sender"
        private const val NOTIF_ID = 42
        const val ACTION_STOP = "io.github.jqssun.airplay.MIRROR_STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
    }
}

/** Lightweight observable state for the sender UI. */
object MirrorSenderState {
    @Volatile var active: Boolean = false
    @Volatile var target: String? = null
}
