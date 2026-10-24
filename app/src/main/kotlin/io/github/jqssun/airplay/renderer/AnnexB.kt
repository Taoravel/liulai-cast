package io.github.jqssun.airplay.renderer

/** Detect random-access/parameter-set NALs even behind AUD/SEI or a three-byte start code. */
internal object AnnexB {
    fun canRestart(data: ByteArray, hevc: Boolean): Boolean {
        var i = 0
        while (i + 3 < data.size) {
            if (data[i] != 0.toByte() || data[i + 1] != 0.toByte()) { i++; continue }
            val header = when {
                data[i + 2] == 1.toByte() -> i + 3
                i + 4 < data.size && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte() -> i + 4
                else -> { i++; continue }
            }
            val nal = data[header].toInt() and 0xff
            val type = if (hevc) (nal shr 1) and 0x3f else nal and 0x1f
            if (if (hevc) type in 16..21 || type in 32..34 else type == 5 || type == 7 || type == 8) return true
            i = header + 1
        }
        return false
    }
}
