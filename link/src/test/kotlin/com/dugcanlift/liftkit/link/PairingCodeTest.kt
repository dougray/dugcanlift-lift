package com.dugcanlift.liftkit.link

import org.junit.Assert.*
import org.junit.Test

class PairingCodeTest {
    private val a = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
    private val b = byteArrayOf(-1, -2, -3, -4, -5, -6, -7, -8)

    @Test fun `both ends compute the same code from opposite points of view`() {
        assertEquals(PairingCode.of(a, b), PairingCode.of(b, a))
    }

    @Test fun `it is six digits, zero padded`() {
        repeat(200) { i ->
            val code = PairingCode.of(byteArrayOf(i.toByte(), 0, 0, 0, 0, 0, 0, 0), b)
            assertEquals(code, PairingCode.DIGITS, code.length)
            assertTrue(code, code.all { it.isDigit() })
        }
    }

    @Test fun `a different watch gives a different number`() {
        val other = byteArrayOf(-1, -2, -3, -4, -5, -6, -7, 0)
        assertNotEquals(PairingCode.of(a, b), PairingCode.of(a, other))
    }

    @Test fun `one flipped bit changes it`() {
        val flipped = a.copyOf().also { it[7] = (it[7].toInt() xor 1).toByte() }
        assertNotEquals(PairingCode.of(a, b), PairingCode.of(flipped, b))
    }

    @Test fun `it is stable, so the two apps and this test agree`() {
        // Not a round trip against ourselves: a literal, so a change to the derivation is a failing
        // test on both sides rather than two apps quietly showing different numbers.
        assertEquals("943851", PairingCode.of(a, b))
    }

    @Test fun `codes are spread across the range rather than clustering`() {
        val codes = (0 until 500).map {
            PairingCode.of(byteArrayOf((it and 0xFF).toByte(), (it shr 8).toByte(), 0, 0, 0, 0, 0, 0), b)
        }
        assertEquals(500, codes.toSet().size)
    }
}
