package io.horizontalsystems.xrpkit.codec

import io.horizontalsystems.xrpkit.crypto.Hex
import io.horizontalsystems.xrpkit.crypto.hexToBytes
import io.horizontalsystems.xrpkit.crypto.toHex

/**
 * Currency codes on the ledger are 160 bits. A standard 3-character ISO-style code is stored
 * in bytes 12..14 with everything else zero; anything else is given as 40 hex characters.
 */
object CurrencyCodec {
    const val XRP = "XRP"
    private val ISO_REGEX = Regex("^[A-Za-z0-9?!@#\$%^&*<>(){}\\[\\]|]{3}$")

    fun isStandardCode(code: String): Boolean = ISO_REGEX.matches(code) && code != XRP

    fun isHexCode(code: String): Boolean = code.length == 40 && Hex.isHex(code)

    fun isValid(code: String): Boolean = isStandardCode(code) || isHexCode(code)

    /** 20-byte ledger representation of a currency code. */
    fun toBytes(code: String): ByteArray = when {
        code == XRP -> ByteArray(20)
        isStandardCode(code) -> ByteArray(20).also { bytes ->
            code.toByteArray(Charsets.US_ASCII).copyInto(bytes, 12)
        }
        isHexCode(code) -> code.hexToBytes()
        else -> throw IllegalArgumentException("Invalid currency code '$code'")
    }

    /** Canonical string form: XRP, a 3-character code, or 40 upper-case hex characters. */
    fun fromBytes(bytes: ByteArray): String {
        require(bytes.size == 20) { "Currency must be 20 bytes" }
        if (bytes.all { it.toInt() == 0 }) return XRP
        val isStandard = (0 until 12).all { bytes[it].toInt() == 0 } &&
            (15 until 20).all { bytes[it].toInt() == 0 }
        if (isStandard) {
            val code = String(bytes, 12, 3, Charsets.US_ASCII)
            if (isStandardCode(code)) return code
        }
        return bytes.toHex()
    }

    /** Ledger form as it appears in JSON (3-char code or 40-hex). Normalizes lower-case hex. */
    fun normalize(code: String): String = when {
        code == XRP -> XRP
        isStandardCode(code) -> code
        isHexCode(code) -> code.uppercase()
        else -> throw IllegalArgumentException("Invalid currency code '$code'")
    }

    /**
     * Human-readable code: a 40-hex code whose bytes are printable ASCII followed by zero padding
     * (e.g. RLUSD, USDC) is decoded to text; otherwise the code is returned as is.
     */
    fun displayCode(code: String): String {
        if (!isHexCode(code)) return code
        val bytes = code.hexToBytes()
        val end = bytes.indexOfFirst { it.toInt() == 0 }.let { if (it < 0) bytes.size else it }
        if (end == 0) return code
        val text = bytes.copyOfRange(0, end)
        val printable = text.all { it in 0x21..0x7E }
        val padded = bytes.copyOfRange(end, bytes.size).all { it.toInt() == 0 }
        return if (printable && padded) String(text, Charsets.US_ASCII) else code
    }
}
