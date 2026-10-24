package io.github.jqssun.airplay.mirror

/**
 * Tiny framing protocol for in-app screen mirroring (Android device -> this receiver).
 *
 * After the TCP connection opens, the sender writes:
 *   [MAGIC int][width int][height int]            -- stream header (once)
 * then a sequence of frames, each:
 *   [payload length int][flags byte][payload ...]  -- one H.264 Annex-B access unit
 *
 * flags 0/1 = H.264 video (1 carries SPS+PPS+IDR), 2 = stereo 48 kHz PCM16 audio.
 * AMR2 deliberately rejects the upstream AMR1 video-only protocol. Integers are big-endian;
 * PCM samples are little-endian as produced by Android AudioRecord.
 */
object MirrorProtocol {
    const val SERVICE_TYPE = "_airmirror._tcp"
    const val DEFAULT_PORT = 7100
    const val MAGIC = 0x414D5232 // AMR2: video plus optional PCM16 audio.
    const val FLAG_KEYFRAME = 0x01
    const val FLAG_AUDIO = 0x02
    const val MAX_FRAME_BYTES = 8 shl 20
    const val AUDIO_SAMPLE_RATE = 48_000

    // sane defaults for the encoder
    const val DEFAULT_BITRATE = 4_000_000
    const val DEFAULT_FPS = 30
    const val KEYFRAME_INTERVAL_S = 2

    data class Frame(val flags: Int, val payload: ByteArray)

    fun writeFrame(out: java.io.DataOutputStream, flags: Int, payload: ByteArray) {
        require(payload.size in 1..MAX_FRAME_BYTES && flags in 0..FLAG_AUDIO)
        if (flags == FLAG_AUDIO) require(payload.size % 4 == 0)
        synchronized(out) {
            out.writeInt(payload.size)
            out.writeByte(flags)
            out.write(payload)
            out.flush()
        }
    }

    fun readFrame(input: java.io.DataInputStream): Frame {
        val size = input.readInt()
        require(size in 1..MAX_FRAME_BYTES) { "Invalid frame size" }
        val flags = input.readUnsignedByte()
        require(flags in 0..FLAG_AUDIO) { "Invalid frame type" }
        if (flags == FLAG_AUDIO) require(size % 4 == 0) { "Invalid PCM frame" }
        return Frame(flags, ByteArray(size).also(input::readFully))
    }
}
