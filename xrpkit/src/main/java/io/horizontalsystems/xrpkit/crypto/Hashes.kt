package io.horizontalsystems.xrpkit.crypto

import io.horizontalsystems.hdwalletkit.Utils
import java.security.MessageDigest

internal object Hashes {

    /** First 32 bytes of SHA-512: the hash function used for signing and transaction ids. */
    fun sha512Half(vararg parts: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-512")
        parts.forEach { digest.update(it) }
        return digest.digest().copyOf(32)
    }

    fun sha256(bytes: ByteArray): ByteArray = Utils.sha256(bytes)

    fun doubleSha256(bytes: ByteArray): ByteArray = Utils.doubleDigest(bytes)

    /** RIPEMD160(SHA256(input)): the XRPL AccountID of a public key. */
    fun sha256Ripemd160(bytes: ByteArray): ByteArray = Utils.sha256Hash160(bytes)
}
