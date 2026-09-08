package io.horizontalsystems.xrpkit

import io.horizontalsystems.hdwalletkit.Mnemonic
import io.horizontalsystems.xrpkit.crypto.toHex
import io.horizontalsystems.xrpkit.models.Amount
import io.horizontalsystems.xrpkit.network.Network
import io.horizontalsystems.xrpkit.network.RpcProvider
import io.horizontalsystems.xrpkit.sync.TransactionConverter
import io.horizontalsystems.xrpkit.transaction.Signer
import io.horizontalsystems.xrpkit.transaction.TransactionBuilder
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * Live check against the XRP testnet: fund a fresh account from the faucet, send a tagged
 * payment with a memo, and confirm it validates. Runs only with RIPPLEKIT_INTEGRATION=true.
 */
class TestnetIntegrationTest {

    @Test
    fun fundSendAndValidate() = runBlocking {
        assumeTrue(System.getenv("RIPPLEKIT_INTEGRATION") == "true")

        val rpc = RpcProvider.create(Network.TestNet.rpcUrls)
        val serverState = rpc.serverState()
        println("validated ledger ${serverState.validatedLedger}, reserve ${serverState.reserveBaseDrops}/${serverState.reserveIncDrops}")
        assertTrue(serverState.validatedLedger > 0)

        val fee = rpc.fee()
        println("fee base=${fee.baseFeeDrops} open=${fee.openLedgerFeeDrops}")

        val sender = Signer.getInstance(Mnemonic().toSeed(Mnemonic().generate()))
        val receiver = Signer.getInstance(Mnemonic().toSeed(Mnemonic().generate()))
        println("sender ${sender.accountId.address}, receiver ${receiver.accountId.address}")

        // fund the sender
        val faucet = OkHttpClient().newCall(
            Request.Builder()
                .url("https://faucet.altnet.rippletest.net/accounts")
                .post("""{"destination":"${sender.accountId.address}"}""".toRequestBody("application/json".toMediaType()))
                .build()
        ).execute()
        println("faucet HTTP ${faucet.code}: ${faucet.body?.string()?.take(200)}")
        assertTrue(faucet.isSuccessful)

        var info = rpc.accountInfo(sender.accountId.address)
        var waited = 0
        while (info == null && waited < 60) {
            delay(3000); waited += 3
            info = rpc.accountInfo(sender.accountId.address)
        }
        assertNotNull("faucet did not fund the account", info)
        println("sender balance ${Amount.Xrp(info!!.balanceDrops).xrp} XRP, sequence ${info.sequence}")

        // receiver does not exist yet: payment must be >= base reserve to create it
        assertEquals(null, rpc.accountInfo(receiver.accountId.address))

        val common = TransactionBuilder.Common(
            account = sender.accountId.address,
            sequence = info.sequence,
            feeDrops = maxOf(fee.baseFeeDrops, fee.openLedgerFeeDrops),
            lastLedgerSequence = rpc.serverState().validatedLedger + 20,
            signingPubKeyHex = sender.publicKey.toHex(),
            memo = "xrp-android integration",
        )
        val tx = TransactionBuilder.payment(common, receiver.accountId.address, Amount.Xrp.fromXrp(BigDecimal("2.5")), destinationTag = 777)
        val signed = TransactionBuilder.sign(tx, sender)
        println("signed blob ${signed.blobHex}")

        val submit = rpc.submit(signed.blobHex)
        println("submit ${submit.engineResult} ${submit.engineResultMessage} hash=${submit.hash}")
        assertTrue(submit.engineResult, submit.isSuccess || submit.isQueued)
        assertEquals("computed hash must match the node's", signed.hash, submit.hash)

        var result = rpc.tx(signed.hash)
        waited = 0
        while ((result == null || !result.validated) && waited < 60) {
            delay(3000); waited += 3
            result = rpc.tx(signed.hash)
        }
        assertNotNull(result)
        assertTrue("not validated in time", result!!.validated)
        val record = TransactionConverter.fromTxResult(result)
        println("validated in ledger ${record.ledgerIndex}: ${record.type} result=${record.result} delivered=${record.deliveredAmount} tag=${record.destinationTag} memo=${record.memo}")
        assertEquals("tesSUCCESS", record.result)
        assertEquals(Amount.Xrp.fromXrp(BigDecimal("2.5")), record.deliveredAmount)
        assertEquals(777L, record.destinationTag)
        assertEquals("xrp-android integration", record.memo)

        val receiverInfo = rpc.accountInfo(receiver.accountId.address)
        assertNotNull(receiverInfo)
        assertEquals(2_500_000L, receiverInfo!!.balanceDrops.toLong())

        val page = rpc.accountTx(receiver.accountId.address)
        assertEquals(1, page.transactions.size)
        val fromHistory = TransactionConverter.fromAccountTx(page.transactions.first())
        assertEquals(signed.hash, fromHistory.hash)
        assertEquals(777L, fromHistory.destinationTag)
        println("history OK: ${fromHistory.hash} at ${fromHistory.timestamp}")
    }
}
