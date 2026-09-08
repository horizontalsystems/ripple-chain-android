package io.horizontalsystems.xrpkit.crypto

/**
 * X-address (XLS-5d): a classic address and an optional destination tag packed into one
 * base58check string. Mainnet X-addresses start with `X`, testnet ones with `T`.
 */
object XAddress {
    private val MAINNET_PREFIX = byteArrayOf(0x05, 0x44)
    private val TESTNET_PREFIX = byteArrayOf(0x04, 0x93.toByte())

    class Decoded(val accountId: AccountId, val tag: Long?, val isTestnet: Boolean) {
        val classicAddress: String get() = accountId.address
    }

    fun isXAddress(input: String): Boolean = input.length in 40..50 && (input.startsWith("X") || input.startsWith("T"))

    fun encode(accountId: AccountId, tag: Long?, testnet: Boolean = false): String {
        require(tag == null || tag in 0..0xFFFFFFFFL) { "Destination tag must fit in 32 bits" }
        val payload = ByteArray(31)
        val prefix = if (testnet) TESTNET_PREFIX else MAINNET_PREFIX
        prefix.copyInto(payload, 0)
        accountId.bytes.copyInto(payload, 2)
        payload[22] = if (tag == null) 0 else 1
        if (tag != null) {
            payload[23] = (tag and 0xFF).toByte()
            payload[24] = ((tag shr 8) and 0xFF).toByte()
            payload[25] = ((tag shr 16) and 0xFF).toByte()
            payload[26] = ((tag shr 24) and 0xFF).toByte()
        }
        return XrpBase58.encodeChecked(payload)
    }

    fun decode(xAddress: String): Decoded {
        val payload = try {
            XrpBase58.decodeChecked(xAddress)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid X-address: ${e.message}")
        }
        require(payload.size == 31) { "Invalid X-address length" }
        val prefix = payload.copyOfRange(0, 2)
        val testnet = when {
            prefix.contentEquals(MAINNET_PREFIX) -> false
            prefix.contentEquals(TESTNET_PREFIX) -> true
            else -> throw IllegalArgumentException("Invalid X-address prefix")
        }
        val accountId = AccountId(payload.copyOfRange(2, 22))
        val tag: Long? = when (payload[22].toInt()) {
            0 -> null
            1 -> (payload[23].toLong() and 0xFF) or
                ((payload[24].toLong() and 0xFF) shl 8) or
                ((payload[25].toLong() and 0xFF) shl 16) or
                ((payload[26].toLong() and 0xFF) shl 24)
            else -> throw IllegalArgumentException("Unsupported X-address tag flag")
        }
        require(payload.copyOfRange(27, 31).all { it.toInt() == 0 }) { "Unsupported X-address tag width" }
        return Decoded(accountId, tag, testnet)
    }
}
