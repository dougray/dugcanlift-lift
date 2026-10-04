package com.dugcanlift.liftkit.link

/**
 * One frame: a fixed 8-byte header and its slice of a message's payload.
 *
 * ```
 *  offset size  field
 *  0      1     version   protocol version of this frame — frozen meaning, see LinkProtocol
 *  1      1     type      MessageType opcode
 *  2      1     flags     bit 0 FIRST, bit 1 LAST, bits 2-7 reserved and must be zero
 *  3      1     seq       message sequence, 0..255 wrapping; every frame of one message shares it
 *  4      2     length    big-endian uint16, payload bytes in THIS frame
 *  6      2     crc       big-endian CRC-16/CCITT-FALSE over bytes 0..5 and the payload
 *  8      N     payload
 * ```
 *
 * Fixed shape, one channel, versioned from the first byte: a frame is either wholly well-formed or
 * refused, and there is no place for a reader to guess. The CRC is not there because BLE is lossy —
 * the link layer already checks that — but because a truncated or concatenated write is the failure
 * an ATT stack *can* hand up, and a length field alone would let a short frame read past its end.
 */
data class LinkFrame(
    val version: Int,
    val type: Int,
    val flags: Int,
    val seq: Int,
    val payload: ByteArray,
) {
    val isFirst: Boolean get() = flags and FLAG_FIRST != 0
    val isLast: Boolean get() = flags and FLAG_LAST != 0

    /** Data classes compare ByteArray by identity; a frame is its bytes, so compare them. */
    override fun equals(other: Any?): Boolean =
        this === other || (other is LinkFrame && version == other.version && type == other.type &&
            flags == other.flags && seq == other.seq && payload.contentEquals(other.payload))

    override fun hashCode(): Int =
        (((version * 31 + type) * 31 + flags) * 31 + seq) * 31 + payload.contentHashCode()

    override fun toString(): String =
        "LinkFrame(v=$version type=0x${type.toString(16)} flags=$flags seq=$seq len=${payload.size})"

    companion object {
        const val HEADER_BYTES = 8
        const val FLAG_FIRST = 0x01
        const val FLAG_LAST = 0x02

        /** Everything above [FLAG_LAST]. A frame that sets any of these is from a build we do not
         *  understand, so it is refused rather than masked off and read anyway. */
        const val RESERVED_FLAGS = 0xFC
    }
}

/** What reading one inbound write produced. Every branch is a decision the caller must make. */
sealed interface FrameDecode {
    data class Ok(val frame: LinkFrame) : FrameDecode

    /** Byte 0 named a version this build does not speak. The caller answers with an ERROR at
     *  [LinkProtocol.ERROR_VERSION] and does not attempt to read the rest. */
    data class UnsupportedVersion(val version: Int) : FrameDecode

    /** Too short, wrong length, bad CRC, or a reserved flag bit set. */
    data class Malformed(val reason: String) : FrameDecode
}

object LinkFrames {
    /**
     * CRC-16/CCITT-FALSE: polynomial 0x1021, initial 0xFFFF, no reflection, no final xor.
     * [start] resumes a running CRC, which is how the two byte ranges either side of the CRC field
     * itself are covered by one value.
     */
    fun crc16(bytes: ByteArray, from: Int = 0, until: Int = bytes.size, start: Int = 0xFFFF): Int {
        var crc = start
        for (i in from until until) {
            crc = crc xor ((bytes[i].toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1
                crc = crc and 0xFFFF
            }
        }
        return crc
    }

    fun encode(frame: LinkFrame): ByteArray {
        require(frame.payload.size <= 0xFFFF) { "frame payload ${frame.payload.size} exceeds uint16" }
        val out = ByteArray(LinkFrame.HEADER_BYTES + frame.payload.size)
        out[0] = frame.version.toByte()
        out[1] = frame.type.toByte()
        out[2] = frame.flags.toByte()
        out[3] = frame.seq.toByte()
        out[4] = (frame.payload.size ushr 8).toByte()
        out[5] = frame.payload.size.toByte()
        frame.payload.copyInto(out, LinkFrame.HEADER_BYTES)
        // CRC covers bytes 0..5 and the payload; bytes 6..7 are where it lands, so they are skipped
        // rather than zeroed-and-included, which would be the same thing said less clearly.
        val crc = crc16(out, LinkFrame.HEADER_BYTES, out.size, crc16(out, 0, 6))
        out[6] = (crc ushr 8).toByte()
        out[7] = crc.toByte()
        return out
    }

    fun decode(bytes: ByteArray): FrameDecode {
        if (bytes.size < LinkFrame.HEADER_BYTES) return FrameDecode.Malformed("short header ${bytes.size}")
        val version = bytes[0].toInt() and 0xFF
        // Read the version before anything else, and refuse before reading anything else. A frame
        // from a newer build may not have this layout at all past byte 0.
        if (version < LinkProtocol.MIN_SUPPORTED_VERSION || version > LinkProtocol.VERSION) {
            return FrameDecode.UnsupportedVersion(version)
        }
        val flags = bytes[2].toInt() and 0xFF
        if (flags and LinkFrame.RESERVED_FLAGS != 0) return FrameDecode.Malformed("reserved flags set")
        val length = ((bytes[4].toInt() and 0xFF) shl 8) or (bytes[5].toInt() and 0xFF)
        if (bytes.size != LinkFrame.HEADER_BYTES + length) {
            return FrameDecode.Malformed("length $length but ${bytes.size - LinkFrame.HEADER_BYTES} bytes present")
        }
        val stated = ((bytes[6].toInt() and 0xFF) shl 8) or (bytes[7].toInt() and 0xFF)
        val crc = crc16(bytes, LinkFrame.HEADER_BYTES, bytes.size, crc16(bytes, 0, 6))
        if (crc != stated) return FrameDecode.Malformed("crc $stated, computed $crc")
        return FrameDecode.Ok(
            LinkFrame(
                version = version,
                type = bytes[1].toInt() and 0xFF,
                flags = flags,
                seq = bytes[3].toInt() and 0xFF,
                payload = bytes.copyOfRange(LinkFrame.HEADER_BYTES, bytes.size),
            )
        )
    }

}
