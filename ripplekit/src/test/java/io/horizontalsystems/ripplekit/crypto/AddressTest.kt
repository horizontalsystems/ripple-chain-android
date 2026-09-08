package io.horizontalsystems.ripplekit.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AddressTest {

    @Test
    fun base58RoundTrip() {
        val samples = listOf(
            ByteArray(0),
            byteArrayOf(0),
            byteArrayOf(0, 0, 1),
            byteArrayOf(0x7F, 0x00, 0xFF.toByte()),
            ByteArray(20) { it.toByte() },
            ByteArray(33) { (it * 7).toByte() },
        )
        for (sample in samples) {
            assertArrayEquals(sample, RippleBase58.decode(RippleBase58.encode(sample)))
        }
    }

    @Test
    fun accountIdFromPublicKey() {
        // ripple-keypairs fixture: secp256k1 key pair of seed sp5fghtJtpUorTwvof1NpDXAzNwf5
        val pubKey = "030D58EB48B4420B1F7B9DF55087E0E29FEF0E8468F9A6825B01CA2C361042D435".hexToBytes()
        assertEquals("rU6K7V3Po4snVhBBaU29sesqs2qTQJWDw1", AccountId.fromPublicKey(pubKey).address)

        // the genesis "masterpassphrase" account
        val master = "0330E7FC9D56BB25D6893BA3F317AE5BCF33B3291BD63DB32654A313222F7FD020".hexToBytes()
        assertEquals("rHb9CJAWyB4rj91VRWn96DkukG4bwdtyTh", AccountId.fromPublicKey(master).address)
    }

    @Test
    fun accountIdFromAddress() {
        val address = "rHb9CJAWyB4rj91VRWn96DkukG4bwdtyTh"
        val accountId = AccountId.fromAddress(address)
        assertEquals("B5F762798A53D543A014CAF8B297CFF8F2F937E8", accountId.bytes.toHex())
        assertEquals(address, accountId.address)
    }

    @Test
    fun invalidAddresses() {
        assertFalse(AccountId.isValidAddress(""))
        assertFalse(AccountId.isValidAddress("rHb9CJAWyB4rj91VRWn96DkukG4bwdtyTg")) // checksum
        assertFalse(AccountId.isValidAddress("GBHFGY3ZNEJWLNO4LBUKLYOCEK4V7ENEBJGPRHHX7JU47GWHBREH37UR")) // stellar
        assertFalse(AccountId.isValidAddress("0x8292bb45bf1ee4d140127049757c2e0ff06317ed"))
        assertFalse(AccountId.isValidAddress("rHb9CJAWyB4rj91VRWn96DkukG4bwdtyTh0")) // '0' not in alphabet
        assertTrue(AccountId.isValidAddress("rMxCKbEDwqr76QuheSUMdEGf4B9xJ8m5De"))
    }

    @Test
    fun xAddressEncodeDecode() {
        // ripple-address-codec README vectors
        val classic = AccountId.fromAddress("rGWrZyQqhTp9Xu7G5Pkayo7bXjH4k4QYpf")
        assertEquals("XVLhHMPHU98es4dbozjVtdWzVrDjtV18pX8yuPT7y4xaEHi", XAddress.encode(classic, 4294967295L))

        val decoded = XAddress.decode("XVLhHMPHU98es4dbozjVtdWzVrDjtV18pX8yuPT7y4xaEHi")
        assertEquals("rGWrZyQqhTp9Xu7G5Pkayo7bXjH4k4QYpf", decoded.classicAddress)
        assertEquals(4294967295L, decoded.tag)
        assertFalse(decoded.isTestnet)

        val testnet = AccountId.fromAddress("r3SVzk8ApofDJuVBPKdmbbLjWGCCXpBQ2g")
        assertEquals("T7oKJ3q7s94kDH6tpkBowhetT1JKfcfdSCmAXbS75iATyLD", XAddress.encode(testnet, 123, testnet = true))
        val decodedTest = XAddress.decode("T7oKJ3q7s94kDH6tpkBowhetT1JKfcfdSCmAXbS75iATyLD")
        assertEquals(123L, decodedTest.tag)
        assertTrue(decodedTest.isTestnet)

        val noTag = XAddress.decode(XAddress.encode(classic, null))
        assertNull(noTag.tag)
        assertEquals(classic, noTag.accountId)
    }
}
