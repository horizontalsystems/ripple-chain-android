package io.horizontalsystems.ripplekit

import android.content.Context
import io.horizontalsystems.ripplekit.codec.CurrencyCodec
import io.horizontalsystems.ripplekit.crypto.AccountId
import io.horizontalsystems.ripplekit.crypto.XAddress
import io.horizontalsystems.ripplekit.database.RippleDatabaseManager
import io.horizontalsystems.ripplekit.database.Storage
import io.horizontalsystems.ripplekit.models.AccountState
import io.horizontalsystems.ripplekit.models.Amount
import io.horizontalsystems.ripplekit.models.LedgerState
import io.horizontalsystems.ripplekit.models.Transaction
import io.horizontalsystems.ripplekit.models.TrustLine
import io.horizontalsystems.ripplekit.network.AccountInfo
import io.horizontalsystems.ripplekit.network.ConnectionManager
import io.horizontalsystems.ripplekit.network.Network
import io.horizontalsystems.ripplekit.network.RpcProvider
import io.horizontalsystems.ripplekit.sync.SyncTimer
import io.horizontalsystems.ripplekit.sync.Syncer
import io.horizontalsystems.ripplekit.sync.TransactionSyncer
import io.horizontalsystems.ripplekit.transaction.Signer
import io.horizontalsystems.ripplekit.transaction.TransactionSender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.math.BigDecimal
import java.math.BigInteger
import java.net.URL
import java.util.Objects

class RippleKit private constructor(
    val receiveAddress: String,
    val network: Network,
    private val signer: Signer?,
    private val syncer: Syncer,
    private val transactionSyncer: TransactionSyncer,
    private val transactionSender: TransactionSender,
    private val rpcProvider: RpcProvider,
    private val storage: Storage,
) {

    private var started = false
    private var scope: CoroutineScope? = null

    val isMainNet: Boolean get() = network.isMainNet
    val isWatchOnly: Boolean get() = signer == null

    val syncState: SyncState get() = syncer.syncState
    val syncStateFlow: StateFlow<SyncState> get() = syncer.syncStateFlow

    val transactionsSyncState: SyncState get() = transactionSyncer.syncState
    val transactionsSyncStateFlow: StateFlow<SyncState> get() = transactionSyncer.syncStateFlow

    val ledgerStateFlow: StateFlow<LedgerState?> get() = syncer.ledgerStateFlow
    val lastLedgerIndex: Long get() = syncer.ledgerStateFlow.value?.validatedLedger ?: 0

    val accountState: AccountState get() = syncer.accountStateFlow.value
    val accountStateFlow: StateFlow<AccountState> get() = syncer.accountStateFlow

    /** True once the account has received its first payment of at least the base reserve. */
    val isAccountActivated: Boolean get() = accountState.exists

    val balance: Amount.Xrp get() = accountState.balance
    val balanceFlow: Flow<Amount.Xrp> get() = accountStateFlow.map { it.balance }.distinctUntilChanged()

    val trustLines: List<TrustLine> get() = syncer.trustLinesFlow.value
    val trustLinesFlow: StateFlow<List<TrustLine>> get() = syncer.trustLinesFlow

    /** Emits every batch of new or updated transactions found by a sync. */
    val transactionsFlow: SharedFlow<List<Transaction>> get() = transactionSyncer.transactionsFlow

    /**
     * XRP locked by the reserve: base reserve plus one increment per owned object (trust
     * lines, offers, escrows). Zero for an account that does not exist yet.
     */
    val minimumBalance: Amount.Xrp get() = minimumBalance(accountState, syncer.ledgerStateFlow.value)
    val minimumBalanceFlow: Flow<Amount.Xrp>
        get() = combine(accountStateFlow, ledgerStateFlow) { account, ledger -> minimumBalance(account, ledger) }.distinctUntilChanged()

    /** Base reserve in XRP as reported by the network, or the current default before the first sync. */
    val baseReserve: Amount.Xrp get() = Amount.Xrp.fromDrops(syncer.ledgerStateFlow.value?.reserveBaseDrops ?: DEFAULT_BASE_RESERVE_DROPS)
    val ownerReserve: Amount.Xrp get() = Amount.Xrp.fromDrops(syncer.ledgerStateFlow.value?.reserveIncDrops ?: DEFAULT_OWNER_RESERVE_DROPS)

    private fun minimumBalance(account: AccountState, ledger: LedgerState?): Amount.Xrp {
        if (!account.exists) return Amount.Xrp.ZERO
        val base = ledger?.reserveBaseDrops ?: DEFAULT_BASE_RESERVE_DROPS
        val inc = ledger?.reserveIncDrops ?: DEFAULT_OWNER_RESERVE_DROPS
        return Amount.Xrp.fromDrops(base + inc * account.ownerCount)
    }

    /** Spendable XRP: balance minus the reserve. Never negative. */
    val availableBalance: Amount.Xrp
        get() = Amount.Xrp((balance.drops - minimumBalance.drops).max(BigInteger.ZERO))

    fun getTokenBalance(currency: String, issuer: String): Amount.Issued? =
        trustLines.firstOrNull { it.currency == currency && it.issuer == issuer }?.amount

    fun getTokenBalanceFlow(currency: String, issuer: String): Flow<Amount.Issued?> =
        trustLinesFlow.map { lines -> lines.firstOrNull { it.currency == currency && it.issuer == issuer }?.amount }.distinctUntilChanged()

    fun getTransactions(beforeTimestamp: Long? = null, limit: Int = 50): List<Transaction> =
        storage.getTransactionsBefore(beforeTimestamp, limit)

    fun getAllTransactions(): List<Transaction> = storage.getTransactions()

    fun getTransaction(hash: String): Transaction? = storage.getTransaction(hash)

    fun getPendingTransactions(): List<Transaction> = storage.getPendingTransactions()

    fun start() {
        if (started) return
        started = true
        scope = CoroutineScope(Dispatchers.IO).also { syncer.start(it) }
    }

    fun stop() {
        started = false
        syncer.stop()
        scope?.cancel()
        scope = null
    }

    fun pause() = syncer.pause()

    fun resume() = syncer.resume()

    fun refresh() = syncer.refresh()

    // ---- network queries ----

    /** Current transaction cost in XRP, from the `fee` command. */
    suspend fun estimateFee(): Amount.Xrp = Amount.Xrp.fromDrops(transactionSender.estimateFeeDrops())

    /** Account details for any address, or null when the account has never been funded. */
    suspend fun getAccountInfo(address: String): AccountInfo? = rpcProvider.accountInfo(address)

    suspend fun doesAccountExist(address: String): Boolean = getAccountInfo(address) != null

    /** True when the destination has the RequireDestTag flag: an untagged payment would fail. */
    suspend fun requiresDestinationTag(address: String): Boolean = getAccountInfo(address)?.requiresDestinationTag ?: false

    /** Whether [address] holds a trust line for the token (any limit or balance). */
    suspend fun isTrustLineSet(currency: String, issuer: String, address: String = receiveAddress): Boolean {
        val normalized = CurrencyCodec.normalize(currency)
        return rpcProvider.accountLines(address).any { it.currency == normalized && it.issuer == issuer }
    }

    // ---- sending ----

    /**
     * Sends XRP. A payment to an unfunded address must be at least the base reserve, or the
     * network rejects it with tecNO_DST_INSUF_XRP. Returns the pending transaction; its hash is
     * final and the record is updated by later syncs.
     */
    suspend fun sendXrp(destination: String, amount: BigDecimal, destinationTag: Long? = null, memo: String? = null): Transaction {
        val signer = requireSigner()
        val (address, tag) = resolveDestination(destination, destinationTag)
        val tx = transactionSender.sendPayment(signer, address, Amount.Xrp.fromXrp(amount), tag, memo)
        syncAfterSend()
        return tx
    }

    /**
     * Sends an issued token over the trust line. SendMax equals the amount so an issuer transfer
     * fee surfaces as a failure rather than a silent shortfall.
     */
    suspend fun sendToken(currency: String, issuer: String, destination: String, amount: BigDecimal, destinationTag: Long? = null, memo: String? = null): Transaction {
        val signer = requireSigner()
        val (address, tag) = resolveDestination(destination, destinationTag)
        val issued = Amount.Issued(amount, CurrencyCodec.normalize(currency), issuer)
        val tx = transactionSender.sendPayment(signer, address, issued, tag, memo, sendMax = issued)
        syncAfterSend()
        return tx
    }

    /** Creates or updates a trust line. Costs one owner reserve increment while the line exists. */
    suspend fun setTrustLine(currency: String, issuer: String, limit: BigDecimal = DEFAULT_TRUST_LIMIT, memo: String? = null): Transaction {
        val signer = requireSigner()
        val tx = transactionSender.setTrustLine(signer, currency, issuer, limit, memo)
        syncAfterSend()
        return tx
    }

    private fun requireSigner(): Signer = signer ?: throw WalletError.WatchOnly()

    private fun resolveDestination(destination: String, tag: Long?): Pair<String, Long?> {
        if (XAddress.isXAddress(destination)) {
            val decoded = XAddress.decode(destination)
            require(decoded.isTestnet == !network.isMainNet) { "X-address is for a different network" }
            require(tag == null || decoded.tag == null || tag == decoded.tag) { "Destination tag conflicts with the X-address tag" }
            return decoded.classicAddress to (decoded.tag ?: tag)
        }
        return AccountId.fromAddress(destination).address to tag
    }

    private suspend fun syncAfterSend() {
        try {
            syncer.syncNow()
        } catch (e: Exception) {
            // the send already succeeded; the next timer tick will pick the state up
        }
    }

    fun statusInfo(): Map<String, Any> = linkedMapOf(
        "Started" to started,
        "Address" to receiveAddress,
        "Network" to network.name,
        "Watch Only" to isWatchOnly,
        "Validated Ledger" to lastLedgerIndex,
        "Sync State" to syncState.toString(),
        "Transactions Sync State" to transactionsSyncState.toString(),
        "Account Activated" to isAccountActivated,
        "Balance" to balance.xrp.toPlainString(),
        "Reserve" to minimumBalance.xrp.toPlainString(),
        "Trust Lines" to trustLines.size,
    )

    sealed class SyncState {
        class Synced : SyncState()
        class NotSynced(val error: Throwable) : SyncState()
        class Syncing(val progress: Double? = null) : SyncState()

        override fun toString(): String = when (this) {
            is Syncing -> "Syncing ${progress?.let { "${it * 100}" } ?: ""}"
            is NotSynced -> "NotSynced ${error.javaClass.simpleName} - message: ${error.message}"
            else -> this.javaClass.simpleName
        }

        override fun equals(other: Any?): Boolean {
            if (other !is SyncState) return false
            if (other.javaClass != this.javaClass) return false
            if (other is Syncing && this is Syncing) return other.progress == this.progress
            return true
        }

        override fun hashCode(): Int {
            if (this is Syncing) return Objects.hashCode(this.progress)
            return Objects.hashCode(this.javaClass.name)
        }
    }

    sealed class SyncError : Throwable() {
        class NotStarted : SyncError()
        class NoNetworkConnection : SyncError()
    }

    sealed class WalletError(message: String) : Exception(message) {
        class WatchOnly : WalletError("Watch-only wallet cannot sign")
    }

    companion object {
        /** Network defaults since December 2024, used until the first `server_state` response. */
        const val DEFAULT_BASE_RESERVE_DROPS = 1_000_000L
        const val DEFAULT_OWNER_RESERVE_DROPS = 200_000L
        val DEFAULT_TRUST_LIMIT: BigDecimal = BigDecimal("1000000000000000")

        fun getAddress(seed: ByteArray): String = Signer.accountId(seed).address

        fun getAddress(wallet: RippleWallet): String = when (wallet) {
            is RippleWallet.Seed -> getAddress(wallet.seed)
            is RippleWallet.WatchOnly -> AccountId.fromAddress(wallet.address).address
        }

        fun getInstance(
            context: Context,
            wallet: RippleWallet,
            network: Network,
            walletId: String,
            syncInterval: Long = 10,
            rpcUrls: List<URL> = network.rpcUrls,
        ): RippleKit {
            val signer = (wallet as? RippleWallet.Seed)?.let { Signer.getInstance(it.seed) }
            val address = signer?.accountId?.address ?: getAddress(wallet)

            val rpcProvider = RpcProvider.create(rpcUrls)
            val storage = Storage(RippleDatabaseManager.getDatabase(context, network, walletId))
            val transactionSyncer = TransactionSyncer(address, rpcProvider, storage)
            val syncTimer = SyncTimer(syncInterval, ConnectionManager(context))
            val syncer = Syncer(address, syncTimer, rpcProvider, transactionSyncer, storage)
            val transactionSender = TransactionSender(address, rpcProvider, storage)

            return RippleKit(address, network, signer, syncer, transactionSyncer, transactionSender, rpcProvider, storage)
        }

        fun clear(context: Context, network: Network, walletId: String) {
            RippleDatabaseManager.clear(context, network, walletId)
        }

        /** Throws [IllegalArgumentException] for anything that is not a classic r-address or an X-address. */
        fun validateAddress(address: String) {
            if (XAddress.isXAddress(address)) XAddress.decode(address) else AccountId.fromAddress(address)
        }

        fun isValidAddress(address: String): Boolean = try {
            validateAddress(address)
            true
        } catch (e: IllegalArgumentException) {
            false
        }

        /** Classic address and tag packed in an X-address, or null when [address] is not one. */
        fun decodeXAddress(address: String): Pair<String, Long?>? =
            if (XAddress.isXAddress(address)) XAddress.decode(address).let { it.classicAddress to it.tag } else null

        fun isValidCurrencyCode(code: String): Boolean = CurrencyCodec.isValid(code)

        /** Display form of a currency code: hex codes that spell printable text are decoded (RLUSD, USDC). */
        fun displayCurrencyCode(code: String): String = CurrencyCodec.displayCode(code)
    }
}
