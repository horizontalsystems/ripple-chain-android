package io.horizontalsystems.ripplekit.crypto

import java.math.BigInteger

/**
 * Base58 with the XRP Ledger alphabet. Same algorithm as Bitcoin's, different symbol order,
 * so `r` is the leading character of an address instead of `1`.
 */
object RippleBase58 {
    const val ALPHABET = "rpshnaf39wBUDNEGHJKLM4PQRST7VWXYZ2bcdeCg65jkm8oFqi1tuvAxyz"
    private val ENCODED_ZERO = ALPHABET[0]
    private val INDEXES = IntArray(128) { -1 }.also { idx ->
        ALPHABET.forEachIndexed { i, c -> idx[c.code] = i }
    }
    private val BASE = BigInteger.valueOf(58)

    fun encode(input: ByteArray): String {
        if (input.isEmpty()) return ""
        var zeros = 0
        while (zeros < input.size && input[zeros].toInt() == 0) zeros++

        val sb = StringBuilder()
        var num = BigInteger(1, input)
        while (num.signum() > 0) {
            val divRem = num.divideAndRemainder(BASE)
            sb.append(ALPHABET[divRem[1].toInt()])
            num = divRem[0]
        }
        repeat(zeros) { sb.append(ENCODED_ZERO) }
        return sb.reverse().toString()
    }

    fun decode(input: String): ByteArray {
        if (input.isEmpty()) return ByteArray(0)
        var zeros = 0
        while (zeros < input.length && input[zeros] == ENCODED_ZERO) zeros++

        var num = BigInteger.ZERO
        for (c in input) {
            val digit = if (c.code < 128) INDEXES[c.code] else -1
            require(digit >= 0) { "Invalid base58 character '$c'" }
            num = num.multiply(BASE).add(BigInteger.valueOf(digit.toLong()))
        }
        val body = num.toByteArray().let { if (it.size > 1 && it[0].toInt() == 0) it.copyOfRange(1, it.size) else it }
        val bodyNoZero = if (num.signum() == 0) ByteArray(0) else body
        return ByteArray(zeros) + bodyNoZero
    }

    /** Encodes payload with a 4-byte double-SHA256 checksum appended. */
    fun encodeChecked(payload: ByteArray): String {
        val checksum = Hashes.doubleSha256(payload).copyOf(4)
        return encode(payload + checksum)
    }

    /** Decodes and verifies the 4-byte checksum, returning the payload. */
    fun decodeChecked(input: String): ByteArray {
        val decoded = decode(input)
        require(decoded.size >= 5) { "Input too short for a checksum" }
        val payload = decoded.copyOfRange(0, decoded.size - 4)
        val checksum = decoded.copyOfRange(decoded.size - 4, decoded.size)
        val expected = Hashes.doubleSha256(payload).copyOf(4)
        require(checksum.contentEquals(expected)) { "Invalid checksum" }
        return payload
    }
}
