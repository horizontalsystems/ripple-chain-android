package io.horizontalsystems.ripplekit.sync

import com.google.gson.JsonElement
import io.horizontalsystems.ripplekit.RippleKit.SyncError
import io.horizontalsystems.ripplekit.RippleKit.SyncState
import io.horizontalsystems.ripplekit.database.Storage
import io.horizontalsystems.ripplekit.models.Transaction
import io.horizontalsystems.ripplekit.models.TransactionSyncState
import io.horizontalsystems.ripplekit.network.RpcProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Keeps the local transaction table in step with `account_tx`.
 *
 * The first sync walks history newest-first (bounded by [MAX_INITIAL_PAGES]); later syncs fetch
 * only ledgers after the last synced one. Pending transactions the kit submitted are replaced
 * when they show up validated, and marked failed once the validated ledger passes their
 * LastLedgerSequence without them.
 */
internal class TransactionSyncer(
    private val address: String,
    private val rpcProvider: RpcProvider,
    private val storage: Storage,
) {

    var syncState: SyncState = SyncState.NotSynced(SyncError.NotStarted())
        private set(value) {
            if (value != field) {
                field = value
                _syncStateFlow.update { value }
            }
        }

    private val _syncStateFlow = MutableStateFlow(syncState)
    val syncStateFlow: StateFlow<SyncState> = _syncStateFlow

    private val _transactionsFlow = MutableSharedFlow<List<Transaction>>(extraBufferCapacity = 16)
    val transactionsFlow: SharedFlow<List<Transaction>> = _transactionsFlow

    fun setNotStarted() {
        syncState = SyncState.NotSynced(SyncError.NotStarted())
    }

    fun setNotSynced(error: Throwable) {
        syncState = SyncState.NotSynced(error)
    }

    suspend fun sync(validatedLedger: Long, accountExists: Boolean) {
        // initial sync shows Syncing; later incremental syncs keep the sticky Synced state
        val state = storage.getTransactionSyncState()
        if (state == null || !state.initialSyncDone) {
            syncState = SyncState.Syncing()
        }

        try {
            val changed = mutableListOf<Transaction>()
            if (accountExists) {
                if (state == null || !state.initialSyncDone) {
                    changed += initialSync(validatedLedger)
                } else if (validatedLedger > state.lastSyncedLedger) {
                    changed += incrementalSync(state.lastSyncedLedger + 1, validatedLedger)
                }
            }
            changed += expirePending(validatedLedger)

            if (changed.isNotEmpty()) {
                _transactionsFlow.tryEmit(changed)
            }
            syncState = SyncState.Synced()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // The account syncer owns the failure policy (it tolerates one transient blip after a
            // successful sync) and calls setNotSynced when the failure is real. Only a first sync
            // that never completed is reported here, so the list does not show stale "synced".
            if (state == null || !state.initialSyncDone) {
                syncState = SyncState.NotSynced(e)
            }
            throw e
        }
    }

    private suspend fun initialSync(validatedLedger: Long): List<Transaction> {
        val stored = mutableListOf<Transaction>()
        var marker: JsonElement? = null
        var pages = 0
        do {
            val page = rpcProvider.accountTx(
                address = address,
                ledgerIndexMin = -1,
                ledgerIndexMax = validatedLedger,
                limit = PAGE_LIMIT,
                forward = false,
                marker = marker,
            )
            val transactions = page.transactions.map { TransactionConverter.fromAccountTx(it) }
            storage.saveTransactions(transactions)
            stored += transactions
            marker = page.marker
            pages++
        } while (marker != null && pages < MAX_INITIAL_PAGES)

        storage.saveTransactionSyncState(TransactionSyncState(lastSyncedLedger = validatedLedger, initialSyncDone = true))
        return stored
    }

    private suspend fun incrementalSync(fromLedger: Long, toLedger: Long): List<Transaction> {
        val stored = mutableListOf<Transaction>()
        var marker: JsonElement? = null
        do {
            val page = rpcProvider.accountTx(
                address = address,
                ledgerIndexMin = fromLedger,
                ledgerIndexMax = toLedger,
                limit = PAGE_LIMIT,
                forward = true,
                marker = marker,
            )
            val transactions = page.transactions.map { TransactionConverter.fromAccountTx(it) }
            storage.saveTransactions(transactions)
            stored += transactions
            marker = page.marker
        } while (marker != null)

        storage.saveTransactionSyncState(TransactionSyncState(lastSyncedLedger = toLedger, initialSyncDone = true))
        return stored
    }

    /**
     * A transaction that is still unvalidated once the validated ledger is past its
     * LastLedgerSequence can never be included: mark it failed. Transactions submitted by the
     * kit always carry LastLedgerSequence.
     */
    private suspend fun expirePending(validatedLedger: Long): List<Transaction> {
        val expired = mutableListOf<Transaction>()
        for (pending in storage.getPendingTransactions()) {
            val last = pending.lastLedgerSequence ?: continue
            if (validatedLedger <= last) continue

            // one last look: the node may have validated it in a ledger the incremental sync
            // did not cover (e.g. a restart between submit and sync)
            val known = try {
                rpcProvider.tx(pending.hash)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                null
            }
            val updated = if (known != null && known.validated) {
                TransactionConverter.fromTxResult(known)
            } else {
                pending.copy(failed = true, result = pending.result ?: EXPIRED_RESULT)
            }
            storage.saveTransaction(updated)
            expired += updated
        }
        return expired
    }

    companion object {
        private const val PAGE_LIMIT = 200
        private const val MAX_INITIAL_PAGES = 25
        const val EXPIRED_RESULT = "expired"
    }
}
