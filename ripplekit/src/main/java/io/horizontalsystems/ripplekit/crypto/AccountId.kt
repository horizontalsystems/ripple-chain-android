package io.horizontalsystems.ripplekit.crypto

/**
 * A 20-byte XRPL AccountID and its classic `r...` address encoding.
 */
class AccountId(val bytes: ByteArray) {

    init {
        require(bytes.size == 20) { "AccountID must be 20 bytes, got ${bytes.size}" }
    }

    val address: String
        get() = RippleBase58.encodeChecked(byteArrayOf(ADDRESS_PREFIX) + bytes)

    override fun equals(other: Any?): Boolean = other is AccountId && other.bytes.contentEquals(bytes)
    override fun hashCode(): Int = bytes.contentHashCode()
    override fun toString(): String = address

    companion object {
        private const val ADDRESS_PREFIX: Byte = 0x00

        /** AccountID of a 33-byte compressed secp256k1 (or 0xED-prefixed ed25519) public key. */
        fun fromPublicKey(publicKey: ByteArray): AccountId {
            require(publicKey.size == 33) { "Public key must be 33 bytes, got ${publicKey.size}" }
            return AccountId(Hashes.sha256Ripemd160(publicKey))
        }

        /** Parses a classic address; throws [IllegalArgumentException] when invalid. */
        fun fromAddress(address: String): AccountId {
            require(address.startsWith("r")) { "Classic XRPL addresses start with 'r'" }
            val payload = try {
                RippleBase58.decodeChecked(address)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("Invalid XRPL address: ${e.message}")
            }
            require(payload.size == 21 && payload[0] == ADDRESS_PREFIX) { "Invalid XRPL address payload" }
            return AccountId(payload.copyOfRange(1, 21))
        }

        fun isValidAddress(address: String): Boolean = try {
            fromAddress(address)
            true
        } catch (e: IllegalArgumentException) {
            false
        }
    }
}
