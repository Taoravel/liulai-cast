package io.github.jqssun.airplay.renderer

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log
import android.view.Surface

class VideoRenderer {

    private val lock = Object()
    private val pipeline = VideoPipeline()
    private var codec: MediaCodec? = null
    private var outputWorker: Thread? = null
    private var displaySurface: Surface? = null
    private var currentH265 = false
    private var videoWidth = 0
    private var videoHeight = 0

    // stats
    @Volatile var fps = 0; private set
    @Volatile var bitrateBps = 0L; private set
    @Volatile var frameCount = 0L; private set
    @Volatile var codecName = ""; private set
    @Volatile var droppedFrames = 0L; private set
    @Volatile var framePacingJitterUs = 0L; private set

    var enforceSdr = true
    var keyAllowFrameDrop = true
    var realtimeDecoderPriority = true
    var operatingRateHint = false
    var scheduledOutputBufferRelease = true
    var benchmarkLog = false
    var benchmarkLogCallback: ((String) -> Unit)? = null
    private var _framesThisSec = 0
    private var _bytesThisSec = 0L
    private var _lastStatReset = 0L
    private val _frameIntervalsNs = LongArray(120)
    private var _frameIntervalIdx = 0
    private var _frameIntervalCount = 0
    private var _lastOutputFrameNs = 0L
    // anchors that map decoder PTS (us) to System.nanoTime() for scheduled rendering
    private var _ptsBaseUs = Long.MIN_VALUE
    private var _wallBaseNs = 0L
    private val outputInfo = MediaCodec.BufferInfo()

    fun setResolution(w: Int, h: Int) = synchronized(lock) {
        if (w <= 0 || h <= 0 || (w == videoWidth && h == videoHeight)) return@synchronized
        videoWidth = w
        videoHeight = h
        pipeline.setVideoSize(w, h)
    }

    // doesn't restart codec; decoder renders into pipeline's own persistent surface
    fun setSurface(surface: Surface) = synchronized(lock) {
        displaySurface = surface
        pipeline.setDisplaySurface(surface)
    }

    fun clearSurface(surface: Surface) = synchronized(lock) {
        if (displaySurface !== surface) return@synchronized
        displaySurface = null
        pipeline.setDisplaySurface(null)
    }

    private fun _updateStats(size: Int) {
        val now = System.currentTimeMillis()
        if (now - _lastStatReset >= 1000) {
            fps = _framesThisSec
            bitrateBps = _bytesThisSec * 8
            framePacingJitterUs = _computeFramePacingJitterUs()
            _framesThisSec = 0
            _bytesThisSec = 0
            _lastStatReset = now
            if (benchmarkLog) _emitBenchmarkLine()
        }
        _framesThisSec++
        _bytesThisSec += size
        frameCount++
    }

    private fun _emitBenchmarkLine() {
        val msg = "fps=$fps bitrate=${bitrateBps / 1000}kbps " +
            "jitter=${framePacingJitterUs}us frames=$frameCount " +
            "dropped=$droppedFrames codec=$codecName " +
            "res=${videoWidth}x${videoHeight}"
        Log.i(BENCH_TAG, msg)
        benchmarkLogCallback?.invoke(msg)
    }

    fun feedFrame(data: ByteArray, ntpTimeNs: Long, isH265: Boolean) {
        _updateStats(data.size)

        synchronized(lock) {
            if (videoWidth == 0 || videoHeight == 0) return

            if (codec == null || isH265 != currentH265) {
                // a stale reference frame decodes to corruption, so wait for a keyframe to (re)start
                if (!AnnexB.canRestart(data, isH265)) {
                    if (codec != null) stopCodec()
                    return
                }
                stopCodec()
                startCodec(isH265)
            }

            // drain ready output first to free input buffers, then feed, then drain again.
            // dropping an input frame corrupts the stream until the next keyframe, so we try
            // hard not to drop unless the decoder is genuinely saturated.
            try {
                drainOutput()
                _feedToCodec(data, ntpTimeNs)
                drainOutput()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Decoder failed; waiting for a clean restart frame", e)
                droppedFrames++
                stopCodec()
            }
        }
    }

    private fun _feedToCodec(data: ByteArray, ntpTimeNs: Long) {
        val c = codec ?: return
        var idx = c.dequeueInputBuffer(2000)
        val deadline = System.nanoTime() + 100_000_000L
        // Release decoded outputs while waiting, instead of discarding reference frames.
        while (idx < 0 && System.nanoTime() < deadline) {
            drainOutput()
            idx = c.dequeueInputBuffer(2000)
        }
        if (idx >= 0) {
            val buf = c.getInputBuffer(idx)
            if (buf == null) {
                c.queueInputBuffer(idx, 0, 0, 0, 0)
                stopCodec()
                return
            }
            buf.clear()
            if (data.size > buf.capacity()) {
                droppedFrames++
                Log.w(TAG, "Frame ${data.size}B exceeds input buffer ${buf.capacity()}B; resetting decoder")
                // Returning without queueing leaks this input-buffer slot permanently.
                c.queueInputBuffer(idx, 0, 0, 0, 0)
                stopCodec()
                return
            }
            buf.put(data)
            c.queueInputBuffer(idx, 0, data.size, ntpTimeNs / 1000, 0)
        } else {
            droppedFrames++
            Log.w(TAG, "Decoder stalled; resynchronizing at the next restart frame. drops=$droppedFrames")
            // After losing a compressed reference frame, later P-frames cannot be decoded safely.
            stopCodec()
        }
    }

    private fun startCodec(h265: Boolean, allowAdaptive: Boolean = true) {
        pipeline.start()
        pipeline.setVideoSize(videoWidth, videoHeight)
        val s = pipeline.inputSurface ?: return
        currentH265 = h265
        val mime = if (h265) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC

        val format = MediaFormat.createVideoFormat(mime, videoWidth, videoHeight)
        // size the input buffer to the resolution so large keyframes are never truncated
        // (a truncated keyframe corrupts the stream until the next IDR -> visible glitching)
        val maxInputSize = maxOf(4_000_000, videoWidth * videoHeight / 2)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxInputSize)
        if (enforceSdr) {
            format.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
            format.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            format.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
        }
        if (realtimeDecoderPriority) {
            format.setInteger(MediaFormat.KEY_PRIORITY, 0)
        }
        if (operatingRateHint && android.os.Build.VERSION.SDK_INT >= 23) {
            format.setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt())
        }
            if (android.os.Build.VERSION.SDK_INT >= 31) {
            format.setInteger(MediaFormat.KEY_ALLOW_FRAME_DROP, if (keyAllowFrameDrop) 1 else 0)
        }
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            // real-time mirroring: ask the decoder to minimize internal buffering so frames
            // surface as soon as they are decoded instead of being held back several frames
            // (this is the main contributor to perceived latency / "lag", even on ethernet)
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }

        val decoder = MediaCodec.createDecoderByType(mime)
        var adaptive = false
        if (allowAdaptive) {
            val side = maxOf(videoWidth, videoHeight)
            val capabilities = decoder.codecInfo.getCapabilitiesForType(mime)
            // Reserve both orientations only within the decoder's advertised limits.
            // Avoid a large square allocation for 4K mirroring on memory-limited TVs.
            if (side <= 1920 && capabilities.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_AdaptivePlayback)
                && capabilities.videoCapabilities?.isSizeSupported(side, side) == true) {
                format.setInteger(MediaFormat.KEY_MAX_WIDTH, side)
                format.setInteger(MediaFormat.KEY_MAX_HEIGHT, side)
                adaptive = true
            }
        }
        try {
            decoder.configure(format, s, null, 0)
            decoder.start()
        } catch (e: RuntimeException) {
            runCatching { decoder.release() }
            if (adaptive) {
                Log.w(TAG, "Adaptive configuration rejected; using compatible decoder settings", e)
                startCodec(h265, allowAdaptive = false)
                return
            }
            throw e
        }
        codec = decoder
        codecName = if (h265) "H.265" else "H.264"
        Log.i(TAG, "Video codec started: $mime ${videoWidth}x${videoHeight}")
        outputWorker = Thread({
            try {
                while (!Thread.currentThread().isInterrupted) {
                    synchronized(lock) {
                        if (outputWorker !== Thread.currentThread() || codec == null) return@Thread
                        try { drainOutput() } catch (e: IllegalStateException) {
                            Log.w(TAG, "Output drain failed; resetting decoder", e)
                            stopCodec()
                            return@Thread
                        }
                    }
                    Thread.sleep(5)
                }
            } catch (_: InterruptedException) { /* Codec release interrupts its output worker. */ }
        }, "VideoOutput").also { it.start() }
    }

    private fun stopCodec() {
        outputWorker?.interrupt()
        outputWorker = null
        _frameIntervalIdx = 0
        _frameIntervalCount = 0
        _lastOutputFrameNs = 0L
        _ptsBaseUs = Long.MIN_VALUE
        _wallBaseNs = 0L
        codec?.let {
            try {
                it.stop()
                it.release()
            } catch (_: Exception) {}
        }
        codec = null
    }

    private fun drainOutput() {
        val c = codec ?: return
        val info = outputInfo
        var pending = -1
        var pendingPtsUs = 0L
        while (true) {
            val idx = c.dequeueOutputBuffer(info, 0)
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED || idx == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) continue
            if (idx < 0) break
            // Keep compressed reference frames intact; only skip already decoded backlog.
            if (pending >= 0) c.releaseOutputBuffer(pending, false)
            pending = idx
            pendingPtsUs = info.presentationTimeUs
        }
        if (pending >= 0) {
            _recordOutputFrameTime()
            if (scheduledOutputBufferRelease) {
                // schedule frame at VSYNC matching its NTP presentation time
                val ptsUs = pendingPtsUs
                val now = System.nanoTime()
                if (_ptsBaseUs == Long.MIN_VALUE) {
                    _ptsBaseUs = ptsUs
                    _wallBaseNs = now
                }
                var target = _wallBaseNs + (ptsUs - _ptsBaseUs) * 1000L
                if (target < now - 100_000_000L || target > now + 100_000_000L) {
                    _ptsBaseUs = ptsUs
                    _wallBaseNs = now
                    target = now
                }
                c.releaseOutputBuffer(pending, target.coerceAtLeast(now))
            } else {
                c.releaseOutputBuffer(pending, true)
            }
        }
    }

    fun release() = synchronized(lock) {
        stopCodec()
        pipeline.release()
        fps = 0; bitrateBps = 0; frameCount = 0; codecName = ""
        droppedFrames = 0; framePacingJitterUs = 0
        _framesThisSec = 0; _bytesThisSec = 0
        _frameIntervalIdx = 0; _frameIntervalCount = 0; _lastOutputFrameNs = 0L
    }

    private fun _recordOutputFrameTime() {
        val now = System.nanoTime()
        if (_lastOutputFrameNs > 0) {
            _frameIntervalsNs[_frameIntervalIdx % _frameIntervalsNs.size] = now - _lastOutputFrameNs
            _frameIntervalIdx++
            _frameIntervalCount++
        }
        _lastOutputFrameNs = now
    }

    private fun _computeFramePacingJitterUs(): Long {
        val count = _frameIntervalCount.coerceAtMost(_frameIntervalsNs.size)
        if (count < 2) return 0

        var sum = 0.0
        var sumSq = 0.0
        for (i in 0 until count) {
            val interval = _frameIntervalsNs[i].toDouble()
            sum += interval
            sumSq += interval * interval
        }
        val mean = sum / count
        val variance = (sumSq / count) - (mean * mean)
        return (kotlin.math.sqrt(variance.coerceAtLeast(0.0)) / 1000.0).toLong()
    }

    companion object {
        private const val TAG = "VideoRenderer"
        private const val BENCH_TAG = "BENCHMARK"

        fun supportsH265(): Boolean {
            val list = MediaCodecList(MediaCodecList.ALL_CODECS)
            return list.codecInfos.any { info ->
                !info.isEncoder && info.supportedTypes.any {
                    it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true)
                }
            }
        }
    }
}
