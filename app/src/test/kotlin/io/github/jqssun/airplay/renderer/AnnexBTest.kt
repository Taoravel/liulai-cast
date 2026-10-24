package io.github.jqssun.airplay.renderer

import org.junit.Assert.*
import org.junit.Test

class AnnexBTest {
    @Test fun avcIdrWithThreeByteStartCode() {
        assertTrue(AnnexB.canRestart(byteArrayOf(0, 0, 1, 0x65, 4), false))
    }
    @Test fun avcSpsBehindAudAndSei() {
        assertTrue(AnnexB.canRestart(byteArrayOf(0, 0, 0, 1, 9, 4,
            0, 0, 1, 6, 4, 0, 0, 0, 1, 0x67, 4), false))
    }
    @Test fun predictiveAvcFrameCannotRestart() {
        assertFalse(AnnexB.canRestart(byteArrayOf(0, 0, 0, 1, 0x41, 5), false))
    }
    @Test fun hevcIdrBehindAud() {
        assertTrue(AnnexB.canRestart(byteArrayOf(0, 0, 1, 70, 1,
            0, 0, 1, 38, 1), true))
    }
    @Test fun hevcCraCanRestart() {
        assertTrue(AnnexB.canRestart(byteArrayOf(0, 0, 0, 1, 42, 1), true))
    }
    @Test fun truncatedAndEmptyDataCannotRestart() {
        for (bytes in listOf(byteArrayOf(), byteArrayOf(0, 0, 1), byteArrayOf(0, 0, 0, 1))) {
            assertFalse(AnnexB.canRestart(bytes, false))
        }
    }
}
