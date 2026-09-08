package io.horizontalsystems.ripplekit.transaction

import io.horizontalsystems.hdwalletkit.Mnemonic
import io.horizontalsystems.ripplekit.crypto.Hashes
import io.horizontalsystems.ripplekit.crypto.hexToBytes
import io.horizontalsystems.ripplekit.crypto.toHex
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigInteger

class SignerTest {

    @Test
    fun signatureMatchesRippleKeypairsFixture() {
        // ripple-keypairs test/fixtures/api.json, secp256k1: sign("test message") is
        // ECDSA over SHA-512Half of the message with RFC 6979 nonces, so it is reproducible
        val privateKey = BigInteger(1, "D78B9735C3F26501C7337B8A5727FD53A6EFDBC6AA55984F098488561F985E23".hexToBytes())
        val signer = Signer(privateKey)

        assertEquals("030D58EB48B4420B1F7B9DF55087E0E29FEF0E8468F9A6825B01CA2C361042D435", signer.publicKey.toHex())
        assertEquals("rU6K7V3Po4snVhBBaU29sesqs2qTQJWDw1", signer.accountId.address)

        val digest = Hashes.sha512Half("test message".toByteArray(Charsets.US_ASCII))
        val signature = signer.sign(digest).toHex()
        assertEquals(
            "30440220583A91C95E54E6A651C47BEC22744E0B101E2C4060E7B08F6341657DAD9BC3EE02207D1489C7395DB0188D3A56A977ECBA54B36FA9371B40319655B1B4429E33EF2D",
            signature,
        )
    }

    @Test
    fun bip44DerivationIsDeterministic() {
        val words = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" ")
        val seed = Mnemonic().toSeed(words)
        val address = Signer.accountId(seed).address
        // m/44'/144'/0'/0/0 with secp256k1; the same words give the same address in Ledger and Trust Wallet
        assertEquals(address, Signer.getInstance(seed).accountId.address)
        assert(address.startsWith("r"))
        assertEquals(34, address.length)
    }
}
