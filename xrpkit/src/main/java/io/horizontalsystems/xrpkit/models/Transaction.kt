package io.horizontalsystems.xrpkit.models

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A transaction touching the kit's account, as stored locally. Amounts are already resolved:
 * [deliveredAmount] is what the destination actually received (from `meta.delivered_amount`),
 * which for partial payments is less than [amount].
 */
@Entity(indices = [Index("ledgerIndex"), Index("timestamp")])
data class Transaction(
    @PrimaryKey val hash: String,
    /** Ledger the transaction was included in; null while pending. */
    val ledgerIndex: Long?,
    /** Unix seconds. For pending transactions this is the submission time. */
    val timestamp: Long,
    /** rippled transaction type, e.g. Payment, TrustSet, AccountDelete, OfferCreate. */
    val type: String,
    val account: String,
    val destination: String?,
    val amount: Amount?,
    val deliveredAmount: Amount?,
    val feeDrops: Long,
    val sequence: Long,
    val destinationTag: Long?,
    val sourceTag: Long?,
    /** For TrustSet: the trust line being changed. */
    val limitAmount: Amount?,
    /** Engine result, e.g. tesSUCCESS or tecPATH_DRY; null while pending. */
    val result: String?,
    /** In a validated ledger. */
    val validated: Boolean,
    /** Validated with a failure result, or expired unvalidated past [lastLedgerSequence]. */
    val failed: Boolean,
    val lastLedgerSequence: Long?,
    /** First memo's data decoded as UTF-8 when it is text, else null. */
    val memo: String?,
) {
    val isPending: Boolean get() = !validated && !failed
    val isSuccess: Boolean get() = validated && result == "tesSUCCESS"

    fun isIncoming(ownAddress: String): Boolean = destination == ownAddress && account != ownAddress
    fun isOutgoing(ownAddress: String): Boolean = account == ownAddress
}
