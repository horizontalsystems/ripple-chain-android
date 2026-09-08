package io.horizontalsystems.xrpkit.transaction

import io.horizontalsystems.xrpkit.crypto.toHex
import io.horizontalsystems.xrpkit.database.Storage
import io.horizontalsystems.xrpkit.models.Amount
import io.horizontalsystems.xrpkit.models.Transaction
import io.horizontalsystems.xrpkit.network.RpcProvider
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.math.BigDecimal

/**
 * Builds, signs and submits transactions, recording each accepted one locally as pending so the
 * wallet shows it immediately. Sends are serialized: concurrent sends would fetch the same
 * account sequence and one of them would fail with tefPAST_SEQ.
 */
internal class TransactionSender(
    private val address: String,
    private val rpcProvider: RpcProvider,
    private val storage: Storage,
) {
    private val sendMutex = Mutex()

    suspend fun estimateFeeDrops(): Long = feeDrops(rpcProvider.fee())

    suspend fun sendPayment(
        signer: Signer,
        destination: String,
        amount: Amount,
        destinationTag: Long?,
        memo: String?,
        sendMax: Amount? = null,
    ): Transaction = submit(signer, memo) { common ->
        TransactionBuilder.payment(common, destination, amount, destinationTag, sendMax)
    }

    suspend fun setTrustLine(signer: Signer, currency: String, issuer: String, limit: BigDecimal, memo: String?): Transaction =
        submit(signer, memo) { common -> TransactionBuilder.trustSet(common, currency, issuer, limit) }

    private suspend fun submit(
        signer: Signer,
        memo: String?,
        build: (TransactionBuilder.Common) -> MutableMap<String, Any?>,
    ): Transaction = sendMutex.withLock {
        if (signer.accountId.address != address) {
            throw SendError.SignerMismatch()
        }
        val info = rpcProvider.accountInfo(address) ?: throw SendError.AccountNotFound()
        val fee = rpcProvider.fee()
        val serverState = rpcProvider.serverState()

        val common = TransactionBuilder.Common(
            account = address,
            sequence = info.sequence,
            feeDrops = feeDrops(fee),
            lastLedgerSequence = serverState.validatedLedger + LAST_LEDGER_OFFSET,
            signingPubKeyHex = signer.publicKey.toHex(),
            memo = memo,
        )
        val tx = build(common)
        val signed = TransactionBuilder.sign(tx, signer)

        val result = rpcProvider.submit(signed.blobHex)
        if (!result.isSuccess && !result.isQueued) {
            throw SendError.Rejected(result.engineResult, result.engineResultMessage)
        }

        val pending = pendingRecord(tx, signed.hash, common)
        storage.saveTransaction(pending)
        pending
    }

    private fun pendingRecord(tx: Map<String, Any?>, hash: String, common: TransactionBuilder.Common): Transaction = Transaction(
        hash = hash,
        ledgerIndex = null,
        timestamp = System.currentTimeMillis() / 1000,
        type = tx["TransactionType"] as String,
        account = common.account,
        destination = tx["Destination"] as String?,
        amount = tx["Amount"] as Amount?,
        deliveredAmount = null,
        feeDrops = common.feeDrops,
        sequence = common.sequence,
        destinationTag = tx["DestinationTag"] as Long?,
        sourceTag = null,
        limitAmount = tx["LimitAmount"] as Amount?,
        result = null,
        validated = false,
        failed = false,
        lastLedgerSequence = common.lastLedgerSequence,
        memo = common.memo,
    )

    /** Current open-ledger cost, never below the base fee and capped so a load spike cannot drain the account. */
    private fun feeDrops(fee: io.horizontalsystems.xrpkit.network.FeeInfo): Long =
        maxOf(fee.baseFeeDrops, fee.openLedgerFeeDrops).coerceAtMost(MAX_FEE_DROPS)

    sealed class SendError(message: String? = null) : Exception(message) {
        /** The kit's own account has never been funded, so it has no sequence to sign with. */
        class AccountNotFound : SendError("Account is not activated")

        /** The signer's key does not derive the kit's address (e.g. a watch-only kit). */
        class SignerMismatch : SendError("Signer does not match the kit address")

        /** rippled rejected the transaction. [engineResult] is the tec/tef/tem/ter code. */
        class Rejected(val engineResult: String, val engineMessage: String?) : SendError("$engineResult${engineMessage?.let { ": $it" } ?: ""}")
    }

    companion object {
        /** Ledgers close every 3-5 s; ~20 ledgers gives a submission about a minute to be included. */
        const val LAST_LEDGER_OFFSET = 20L
        const val MAX_FEE_DROPS = 10_000L
    }
}
