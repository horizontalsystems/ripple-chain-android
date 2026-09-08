package io.horizontalsystems.ripplekit.transaction

import io.horizontalsystems.ripplekit.codec.BinarySerializer
import io.horizontalsystems.ripplekit.codec.CurrencyCodec
import io.horizontalsystems.ripplekit.crypto.toHex
import io.horizontalsystems.ripplekit.models.Amount
import java.math.BigDecimal

/** Builds transaction JSON maps and signs them. */
internal object TransactionBuilder {

    /** tfFullyCanonicalSig: harmless since RequireFullyCanonicalSig, still set by reference clients. */
    const val TF_FULLY_CANONICAL_SIG = 0x80000000L

    /** TrustSet flag: opt the trust line out of rippling, the default for end-user wallets. */
    const val TF_SET_NO_RIPPLE = 0x00020000L

    /** Payment flag: the receiver may get less than Amount. Only set when a SendMax is meaningful. */
    const val TF_PARTIAL_PAYMENT = 0x00020000L

    class Common(
        val account: String,
        val sequence: Long,
        val feeDrops: Long,
        val lastLedgerSequence: Long,
        val signingPubKeyHex: String,
        val memo: String? = null,
    )

    fun payment(
        common: Common,
        destination: String,
        amount: Amount,
        destinationTag: Long?,
        sendMax: Amount? = null,
    ): MutableMap<String, Any?> = base(common, "Payment").apply {
        put("Destination", destination)
        put("Amount", amount)
        put("DestinationTag", destinationTag)
        put("SendMax", sendMax)
    }

    fun trustSet(common: Common, currency: String, issuer: String, limit: BigDecimal): MutableMap<String, Any?> =
        base(common, "TrustSet").apply {
            put("Flags", TF_FULLY_CANONICAL_SIG or TF_SET_NO_RIPPLE)
            put("LimitAmount", Amount.Issued(limit, CurrencyCodec.normalize(currency), issuer))
        }

    fun accountDelete(common: Common, destination: String, destinationTag: Long?): MutableMap<String, Any?> =
        base(common, "AccountDelete").apply {
            put("Destination", destination)
            put("DestinationTag", destinationTag)
        }

    private fun base(common: Common, type: String): MutableMap<String, Any?> = linkedMapOf(
        "TransactionType" to type,
        "Account" to common.account,
        "Sequence" to common.sequence,
        "Fee" to Amount.Xrp.fromDrops(common.feeDrops),
        "LastLedgerSequence" to common.lastLedgerSequence,
        "Flags" to TF_FULLY_CANONICAL_SIG,
        "SigningPubKey" to common.signingPubKeyHex,
        "Memos" to common.memo?.takeIf { it.isNotEmpty() }?.let { listOf(memo(it)) },
    )

    private fun memo(text: String): Map<String, Any?> = mapOf(
        "Memo" to mapOf(
            "MemoType" to "Memo".toByteArray(Charsets.US_ASCII).toHex(),
            "MemoFormat" to "text/plain".toByteArray(Charsets.US_ASCII).toHex(),
            "MemoData" to text.toByteArray(Charsets.UTF_8).toHex(),
        )
    )

    class Signed(val blob: ByteArray, val hash: String) {
        val blobHex: String get() = blob.toHex()
    }

    fun sign(tx: MutableMap<String, Any?>, signer: Signer): Signed {
        val signingHash = BinarySerializer.signingHash(tx)
        tx["TxnSignature"] = signer.sign(signingHash).toHex()
        val blob = BinarySerializer.serialize(tx)
        val hash = BinarySerializer.transactionHash(blob).toHex()
        return Signed(blob, hash)
    }
}
