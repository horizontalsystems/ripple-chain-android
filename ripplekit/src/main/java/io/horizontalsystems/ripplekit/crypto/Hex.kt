package io.horizontalsystems.ripplekit.crypto

internal object Hex {
    private val digits = "0123456789ABCDEF".toCharArray()

    fun encode(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            out[i * 2] = digits[v ushr 4]
            out[i * 2 + 1] = digits[v and 0x0F]
        }
        return String(out)
    }

    fun decode(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "Hex string must have even length" }
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            require(hi >= 0 && lo >= 0) { "Invalid hex character in '$hex'" }
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    fun isHex(s: String): Boolean = s.isNotEmpty() && s.all { Character.digit(it, 16) >= 0 }
}

internal fun ByteArray.toHex(): String = Hex.encode(this)
internal fun String.hexToBytes(): ByteArray = Hex.decode(this)
