package io.horizontalsystems.xrpkit

/** How the kit is keyed: a BIP39 seed that can sign, or an address that can only be watched. */
sealed interface XrpWallet {
    data class Seed(val seed: ByteArray) : XrpWallet {
        override fun equals(other: Any?) = other is Seed && other.seed.contentEquals(seed)
        override fun hashCode() = seed.contentHashCode()
    }

    data class WatchOnly(val address: String) : XrpWallet
}
