package io.github.jqssun.airplay.mirror

import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MirrorProtocolTest {
    @Test fun mixedVideoAndAudioRoundTrip() {
        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        val video = byteArrayOf(0, 0, 0, 1, 0x67, 12, 23)
        val audio = ByteArray(3840) { (it % 127).toByte() }
        MirrorProtocol.writeFrame(out, MirrorProtocol.FLAG_KEYFRAME, video)
        MirrorProtocol.writeFrame(out, MirrorProtocol.FLAG_AUDIO, audio)
        val input = DataInputStream(ByteArrayInputStream(bytes.toByteArray()))
        val first = MirrorProtocol.readFrame(input)
        assertEquals(MirrorProtocol.FLAG_KEYFRAME, first.flags)
        assertArrayEquals(video, first.payload)
        val second = MirrorProtocol.readFrame(input)
        assertEquals(MirrorProtocol.FLAG_AUDIO, second.flags)
        assertArrayEquals(audio, second.payload)
        assertEquals(0, input.available())
    }

    @Test fun concurrentAudioAndVideoWritesDoNotInterleave() {
        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        val workers = Executors.newFixedThreadPool(2)
        try {
            for (flag in listOf(0, MirrorProtocol.FLAG_AUDIO)) {
                workers.submit { repeat(100) { MirrorProtocol.writeFrame(out, flag, ByteArray(400) { flag.toByte() }) } }
            }
            workers.shutdown()
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS))
            val input = DataInputStream(ByteArrayInputStream(bytes.toByteArray()))
            repeat(200) {
                val frame = MirrorProtocol.readFrame(input)
                assertEquals(400, frame.payload.size)
                assertTrue(frame.payload.all { it == frame.flags.toByte() })
            }
            assertEquals(0, input.available())
        } finally { workers.shutdownNow() }
    }

    @Test fun invalidFramesAreRejectedBeforeAllocation() {
        for ((size, flag) in listOf(0 to 0, -1 to 0, (MirrorProtocol.MAX_FRAME_BYTES + 1) to 0,
            4 to 3, 3 to MirrorProtocol.FLAG_AUDIO)) {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).apply { writeInt(size); writeByte(flag) }
            assertThrows(IllegalArgumentException::class.java) {
                MirrorProtocol.readFrame(DataInputStream(ByteArrayInputStream(bytes.toByteArray())))
            }
        }
    }

    @Test fun truncatedPayloadFails() {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).apply { writeInt(100); writeByte(0); write(byteArrayOf(1, 2, 3)) }
        assertThrows(EOFException::class.java) {
            MirrorProtocol.readFrame(DataInputStream(ByteArrayInputStream(bytes.toByteArray())))
        }
    }
}
