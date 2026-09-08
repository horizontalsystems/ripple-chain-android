package io.horizontalsystems.ripplekit.database

import io.horizontalsystems.ripplekit.models.AccountState
import io.horizontalsystems.ripplekit.models.LedgerState
import io.horizontalsystems.ripplekit.models.Transaction
import io.horizontalsystems.ripplekit.models.TransactionSyncState
import io.horizontalsystems.ripplekit.models.TrustLine

internal class Storage(private val database: MainDatabase) {

    fun getAccountState(): AccountState? = database.accountStateDao().get()
    fun saveAccountState(state: AccountState) = database.accountStateDao().save(state)

    fun getLedgerState(): LedgerState? = database.ledgerStateDao().get()
    fun saveLedgerState(state: LedgerState) = database.ledgerStateDao().save(state)

    fun getTrustLines(): List<TrustLine> = database.trustLineDao().getAll()
    fun replaceTrustLines(lines: List<TrustLine>) = database.runInTransaction {
        database.trustLineDao().deleteAll()
        database.trustLineDao().insertAll(lines)
    }

    fun getTransaction(hash: String): Transaction? = database.transactionDao().get(hash)
    fun getTransactions(): List<Transaction> = database.transactionDao().getAll()
    fun getTransactionsBefore(timestamp: Long?, limit: Int): List<Transaction> =
        if (timestamp == null) database.transactionDao().getLatest(limit)
        else database.transactionDao().getBefore(timestamp, limit)
    fun getPendingTransactions(): List<Transaction> = database.transactionDao().getPending()
    fun saveTransactions(transactions: List<Transaction>) = database.transactionDao().insertAll(transactions)
    fun saveTransaction(transaction: Transaction) = database.transactionDao().insert(transaction)

    fun getTransactionSyncState(): TransactionSyncState? = database.transactionSyncStateDao().get()
    fun saveTransactionSyncState(state: TransactionSyncState) = database.transactionSyncStateDao().save(state)
}
