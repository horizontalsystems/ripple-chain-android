package io.horizontalsystems.ripplekit.sync

import io.horizontalsystems.ripplekit.RippleKit.SyncError
import io.horizontalsystems.ripplekit.RippleKit.SyncState
import io.horizontalsystems.ripplekit.database.Storage
import io.horizontalsystems.ripplekit.models.AccountState
import io.horizontalsystems.ripplekit.models.LedgerState
import io.horizontalsystems.ripplekit.models.TrustLine
import io.horizontalsystems.ripplekit.network.NetworkErrors
import io.horizontalsystems.ripplekit.network.RpcProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Drives one sync cycle per timer tick: ledger and reserve parameters, account state, trust
 * lines, then transaction history.
 */
internal class Syncer(
    private val address: String,
    private val syncTimer: SyncTimer,
    private val rpcProvider: RpcProvider,
    private val transactionSyncer: TransactionSyncer,
    private val storage: Storage,
) : SyncTimer.Listener {

    private val syncing = AtomicBoolean(false)
    private var scope: CoroutineScope? = null

    // A connectivity blip right after the app resumes fails one cycle and clears itself within
    // seconds. Once the kit has synced successfully, the first transient failure keeps the
    // current state and retries shortly; only a repeated failure is reported.
    private var hasSyncedOnce = false
    private var consecutiveFailures = 0

    var syncState: SyncState = SyncState.NotSynced(SyncError.NotStarted())
        private set(value) {
            if (value != field) {
                field = value
                _syncStateFlow.update { value }
            }
        }

    private val _syncStateFlow = MutableStateFlow(syncState)
    val syncStateFlow: StateFlow<SyncState> = _syncStateFlow

    private val _ledgerStateFlow = MutableStateFlow(storage.getLedgerState())
    val ledgerStateFlow: StateFlow<LedgerState?> = _ledgerStateFlow

    private val _accountStateFlow = MutableStateFlow(storage.getAccountState() ?: AccountState.EMPTY)
    val accountStateFlow: StateFlow<AccountState> = _accountStateFlow

    private val _trustLinesFlow = MutableStateFlow(storage.getTrustLines())
    val trustLinesFlow: StateFlow<List<TrustLine>> = _trustLinesFlow

    fun start(scope: CoroutineScope) {
        this.scope = scope
        syncTimer.start(this, scope)
    }

    fun stop() {
        syncState = SyncState.NotSynced(SyncError.NotStarted())
        transactionSyncer.setNotStarted()
        syncTimer.stop()
    }

    fun pause() = syncTimer.pause()

    fun resume() = syncTimer.resume()

    fun refresh() {
        when (syncTimer.state) {
            SyncTimer.State.Ready -> sync()
            is SyncTimer.State.NotReady -> scope?.let { syncTimer.start(this, it) }
        }
    }

    override fun onUpdateSyncTimerState(state: SyncTimer.State) {
        syncState = when (state) {
            is SyncTimer.State.NotReady -> {
                transactionSyncer.setNotSynced(state.error)
                SyncState.NotSynced(state.error)
            }
            SyncTimer.State.Ready -> SyncState.Syncing()
        }
    }

    override fun sync() {
        val scope = this.scope ?: return
        if (!syncing.compareAndSet(false, true)) return

        scope.launch {
            try {
                performSync()
            } finally {
                syncing.set(false)
            }
        }
    }

    /** Runs a full cycle now, outside the timer (used after a send). */
    suspend fun syncNow() {
        if (!syncing.compareAndSet(false, true)) return
        try {
            performSync()
        } finally {
            syncing.set(false)
        }
    }

    private suspend fun performSync() {
        try {
            val serverState = rpcProvider.serverState()
            val ledgerState = LedgerState(
                validatedLedger = serverState.validatedLedger,
                reserveBaseDrops = serverState.reserveBaseDrops,
                reserveIncDrops = serverState.reserveIncDrops,
            )
            if (ledgerState != _ledgerStateFlow.value) {
                storage.saveLedgerState(ledgerState)
                _ledgerStateFlow.update { ledgerState }
            }

            val info = rpcProvider.accountInfo(address)
            val accountState = if (info == null) {
                AccountState.EMPTY
            } else {
                AccountState(
                    exists = true,
                    balanceDrops = info.balanceDrops.toLong(),
                    sequence = info.sequence,
                    ownerCount = info.ownerCount,
                    flags = info.flags,
                )
            }
            if (accountState != _accountStateFlow.value) {
                storage.saveAccountState(accountState)
                _accountStateFlow.update { accountState }
            }

            val trustLines = if (accountState.exists && accountState.ownerCount > 0) {
                rpcProvider.accountLines(address).map {
                    TrustLine(
                        currency = it.currency,
                        issuer = it.issuer,
                        balance = BigDecimal(it.balance),
                        limit = BigDecimal(it.limit),
                        noRipple = it.noRipple,
                        // account_lines reports "freeze" from the account's own side and
                        // "freeze_peer" from the issuer's; the issuer's freeze is what blocks sends
                        frozen = it.frozenByPeer,
                        frozenByHolder = it.frozen,
                        authorized = it.authorized,
                    )
                }
            } else {
                emptyList()
            }
            if (trustLines != _trustLinesFlow.value) {
                storage.replaceTrustLines(trustLines)
                _trustLinesFlow.update { trustLines }
            }

            transactionSyncer.sync(serverState.validatedLedger, accountState.exists)

            hasSyncedOnce = true
            consecutiveFailures = 0
            syncState = SyncState.Synced()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            consecutiveFailures++
            val tolerate = hasSyncedOnce && consecutiveFailures < MAX_TOLERATED_FAILURES && NetworkErrors.isTransient(error)
            if (tolerate) {
                scope?.launch {
                    delay(TRANSIENT_RETRY_DELAY_MS)
                    sync()
                }
            } else {
                syncState = SyncState.NotSynced(error)
                transactionSyncer.setNotSynced(error)
            }
        }
    }

    companion object {
        private const val MAX_TOLERATED_FAILURES = 2
        private const val TRANSIENT_RETRY_DELAY_MS = 3000L
    }
}
