package io.horizontalsystems.ripplekit.models

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.math.BigDecimal
import java.math.BigInteger

/** On-ledger state of the kit's account. A single row; [exists] is false until the account is funded. */
@Entity
data class AccountState(
    @PrimaryKey val id: Int = 1,
    val exists: Boolean,
    val balanceDrops: Long,
    val sequence: Long,
    val ownerCount: Long,
    val flags: Long,
) {
    val balance: Amount.Xrp get() = Amount.Xrp(BigInteger.valueOf(balanceDrops))

    companion object {
        val EMPTY = AccountState(exists = false, balanceDrops = 0, sequence = 0, ownerCount = 0, flags = 0)
    }
}

/** Validated ledger index and reserve parameters, refreshed every sync. */
@Entity
data class LedgerState(
    @PrimaryKey val id: Int = 1,
    val validatedLedger: Long,
    val reserveBaseDrops: Long,
    val reserveIncDrops: Long,
)

@Entity(primaryKeys = ["currency", "issuer"])
data class TrustLine(
    val currency: String,
    val issuer: String,
    val balance: BigDecimal,
    val limit: BigDecimal,
    val noRipple: Boolean,
    /** Frozen by the issuer: the balance cannot be sent. */
    val frozen: Boolean,
    /** Frozen by this account (rare for an end-user wallet). */
    val frozenByHolder: Boolean,
    val authorized: Boolean,
) {
    val amount: Amount.Issued get() = Amount.Issued(balance, currency, issuer)
}

@Entity
data class TransactionSyncState(
    @PrimaryKey val id: Int = 1,
    /** Highest validated ledger whose transactions are stored. */
    val lastSyncedLedger: Long,
    /** False while the initial backwards walk of history has not reached the account's first tx. */
    val initialSyncDone: Boolean,
)
