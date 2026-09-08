package io.horizontalsystems.xrpkit.transaction

import io.horizontalsystems.hdwalletkit.ECDSASignature
import io.horizontalsystems.hdwalletkit.ECKey
import io.horizontalsystems.hdwalletkit.HDWallet
import io.horizontalsystems.xrpkit.crypto.AccountId
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.signers.ECDSASigner
import org.bouncycastle.crypto.signers.HMacDSAKCalculator
import java.math.BigInteger

/**
 * secp256k1 signer for XRPL transactions.
 *
 * Keys follow the BIP44 path used by Ledger, Trust Wallet, Exodus and Xaman for mnemonic accounts:
 * m/44'/144'/0'/0/0, with the BIP32 leaf private key used directly as the XRPL signing key.
 */
class Signer(private val privateKey: BigInteger) {

    /** 33-byte compressed public key, the value of the SigningPubKey transaction field. */
    val publicKey: ByteArray = ECKey(privateKey, true).pubKey

    val accountId: AccountId = AccountId.fromPublicKey(publicKey)

    /**
     * Signs a 32-byte digest (SHA-512Half of the signing prefix and serialized transaction) with
     * deterministic RFC 6979 nonces and a canonical low-S value, DER-encoded, as XRPL requires.
     */
    fun sign(digest: ByteArray): ByteArray {
        require(digest.size == 32) { "Invalid digest size: ${digest.size}" }

        val signer = ECDSASigner(HMacDSAKCalculator(SHA256Digest()))
        signer.init(true, ECPrivateKeyParameters(privateKey, ECKey.ecParams))
        val components = signer.generateSignature(digest)
        var s = components[1]
        if (s > ECKey.HALF_CURVE_ORDER) {
            s = ECKey.ecParams.n.subtract(s)
        }
        return ECDSASignature(components[0], s).encodeToDER()
    }

    companion object {
        const val COIN_TYPE = 144

        fun getInstance(seed: ByteArray, account: Int = 0): Signer = Signer(privateKey(seed, account))

        fun privateKey(seed: ByteArray, account: Int = 0): BigInteger {
            val hdWallet = HDWallet(seed, COIN_TYPE, HDWallet.Purpose.BIP44)
            return hdWallet.privateKey(account, 0, true).privKey
        }

        fun accountId(seed: ByteArray, account: Int = 0): AccountId {
            val publicKey = ECKey(privateKey(seed, account), true).pubKey
            return AccountId.fromPublicKey(publicKey)
        }
    }
}
