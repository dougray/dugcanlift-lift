package com.dugcanlift.liftkit.link

import java.security.MessageDigest

/**
 * The short number both ends show while pairing, so a user cannot adopt the wrong watch.
 *
 * It is derived from both handshake nonces and nothing else, which gives it the two properties
 * that matter: **both ends compute it independently** — neither sends it, so a device that made up
 * its own would show a different number — and it is **fresh per handshake**, so the number seen
 * last week proves nothing today. The nonces are ordered before hashing because each end sees them
 * in the opposite roles and the answer has to be the same on both wrists.
 *
 * This is a confirmation, not a key exchange. The link's confidentiality comes from BLE bonding and
 * encrypted characteristics; this only answers "is the thing I am looking at the thing I am talking
 * to", which encryption alone does not.
 */
object PairingCode {
    const val DIGITS = 6

    fun of(ours: ByteArray, theirs: ByteArray): String {
        val first: ByteArray
        val second: ByteArray
        if (compareUnsigned(ours, theirs) <= 0) { first = ours; second = theirs }
        else { first = theirs; second = ours }
        val digest = MessageDigest.getInstance("SHA-256").digest(first + second)
        var value = 0L
        repeat(4) { value = (value shl 8) or (digest[it].toLong() and 0xFF) }
        return (value % 1_000_000L).toString().padStart(DIGITS, '0')
    }

    private fun compareUnsigned(a: ByteArray, b: ByteArray): Int {
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            val d = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (d != 0) return d
        }
        return a.size - b.size
    }
}
