package io.horizontalsystems.xrpkit.sync

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import io.horizontalsystems.xrpkit.codec.AmountCodec
import io.horizontalsystems.xrpkit.crypto.hexToBytes
import io.horizontalsystems.xrpkit.models.Amount
import io.horizontalsystems.xrpkit.models.Transaction
import io.horizontalsystems.xrpkit.network.RawTransaction
import io.horizontalsystems.xrpkit.network.TxResult
import io.horizontalsystems.xrpkit.network.optLong
import io.horizontalsystems.xrpkit.network.optString
import io.horizontalsystems.xrpkit.network.requireString
import java.nio.charset.CodingErrorAction

/** Builds local [Transaction] records from rippled transaction JSON. */
internal object TransactionConverter {

    /** Seconds between the Unix epoch and the Ripple epoch (2000-01-01T00:00:00Z). */
    const val RIPPLE_EPOCH_OFFSET = 946_684_800L

    fun fromAccountTx(raw: RawTransaction): Transaction =
        convert(raw.hash, raw.tx, raw.meta, raw.validated, raw.ledgerIndex, raw.date)

    fun fromTxResult(result: TxResult): Transaction =
        convert(result.hash, result.tx, result.meta, result.validated, result.ledgerIndex, result.date)

    private fun convert(
        hash: String,
        tx: JsonObject,
        meta: JsonObject?,
        validated: Boolean,
        ledgerIndex: Long?,
        date: Long?,
    ): Transaction {
        val result = meta?.optString("TransactionResult")
        val amount = amountOf(tx.get("Amount") ?: tx.get("DeliverMax"))
        val delivered = meta?.get("delivered_amount")?.let { element ->
            if (element.isJsonPrimitive && element.asString == "unavailable") null else amountOf(element)
        } ?: if (result == "tesSUCCESS" && tx.optString("TransactionType") == "Payment" && !isPartialPayment(tx)) amount else null

        return Transaction(
            hash = hash,
            ledgerIndex = ledgerIndex,
            timestamp = date?.let { it + RIPPLE_EPOCH_OFFSET } ?: (System.currentTimeMillis() / 1000),
            type = tx.optString("TransactionType") ?: "Unknown",
            account = tx.requireString("Account"),
            destination = tx.optString("Destination"),
            amount = amount,
            deliveredAmount = delivered,
            feeDrops = tx.optString("Fee")?.toLongOrNull() ?: 0,
            sequence = tx.optLong("Sequence") ?: 0,
            destinationTag = tx.optLong("DestinationTag"),
            sourceTag = tx.optLong("SourceTag"),
            limitAmount = amountOf(tx.get("LimitAmount")),
            result = result,
            validated = validated,
            failed = validated && result != "tesSUCCESS",
            lastLedgerSequence = tx.optLong("LastLedgerSequence"),
            memo = memoOf(tx),
        )
    }

    private fun isPartialPayment(tx: JsonObject): Boolean =
        ((tx.optLong("Flags") ?: 0) and 0x00020000L) != 0L

    fun amountOf(element: JsonElement?): Amount? {
        if (element == null || element.isJsonNull) return null
        return try {
            if (element.isJsonPrimitive) {
                AmountCodec.fromDropsString(element.asString)
            } else {
                val obj = element.asJsonObject
                AmountCodec.issued(obj.requireString("value"), obj.requireString("currency"), obj.requireString("issuer"))
            }
        } catch (e: Exception) {
            null
        }
    }

    /** First memo decoded as text when its bytes are valid UTF-8, else null. */
    private fun memoOf(tx: JsonObject): String? {
        val memos = tx.getAsJsonArray("Memos") ?: return null
        val memo = memos.firstOrNull()?.asJsonObject?.getAsJsonObject("Memo") ?: return null
        val dataHex = memo.optString("MemoData") ?: return null
        return try {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(java.nio.ByteBuffer.wrap(dataHex.hexToBytes())).toString()
                .takeIf { text -> text.none { it.isISOControl() && it != '\n' && it != '\t' } }
        } catch (e: Exception) {
            null
        }
    }
}
