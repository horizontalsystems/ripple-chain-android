package io.horizontalsystems.ripplekit.sample

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.horizontalsystems.hdwalletkit.Mnemonic
import io.horizontalsystems.ripplekit.RippleKit
import io.horizontalsystems.ripplekit.RippleWallet
import io.horizontalsystems.ripplekit.models.Transaction
import io.horizontalsystems.ripplekit.models.TrustLine
import io.horizontalsystems.ripplekit.network.Network
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.math.BigDecimal

class MainViewModel : ViewModel() {

    var network by mutableStateOf(Network.TestNet)
    var mnemonic by mutableStateOf("")
    var watchAddress by mutableStateOf("")
    var error by mutableStateOf<String?>(null)

    var kit by mutableStateOf<RippleKit?>(null)
        private set

    var syncState by mutableStateOf("")
    var transactionsSyncState by mutableStateOf("")
    var ledgerIndex by mutableStateOf(0L)
    var activated by mutableStateOf(false)
    var balance by mutableStateOf("0")
    var available by mutableStateOf("0")
    var reserve by mutableStateOf("0")
    var trustLines by mutableStateOf<List<TrustLine>>(emptyList())
    var transactions by mutableStateOf<List<Transaction>>(emptyList())
    var sendResult by mutableStateOf<String?>(null)
    var faucetResult by mutableStateOf<String?>(null)

    private val jobs = mutableListOf<Job>()

    fun startFromMnemonic() {
        val words = mnemonic.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        start {
            Mnemonic().validate(words)
            RippleWallet.Seed(Mnemonic().toSeed(words))
        }
    }

    fun startWatch() {
        start { RippleWallet.WatchOnly(watchAddress.trim()) }
    }

    private fun start(walletProvider: () -> RippleWallet) {
        error = null
        try {
            val wallet = walletProvider()
            val kit = RippleKit.getInstance(App.instance, wallet, network, walletId = "sample-${network.name}")
            this.kit = kit
            observe(kit)
            kit.start()
        } catch (e: Exception) {
            error = e.message ?: e.toString()
        }
    }

    private fun observe(kit: RippleKit) {
        jobs += viewModelScope.launch { kit.syncStateFlow.collect { syncState = it.toString() } }
        jobs += viewModelScope.launch { kit.transactionsSyncStateFlow.collect { transactionsSyncState = it.toString() } }
        jobs += viewModelScope.launch { kit.ledgerStateFlow.collect { ledgerIndex = it?.validatedLedger ?: 0 } }
        jobs += viewModelScope.launch {
            kit.accountStateFlow.collect {
                activated = it.exists
                balance = kit.balance.xrp.toPlainString()
                available = kit.availableBalance.xrp.toPlainString()
                reserve = kit.minimumBalance.xrp.toPlainString()
            }
        }
        jobs += viewModelScope.launch { kit.trustLinesFlow.collect { trustLines = it } }
        jobs += viewModelScope.launch {
            transactions = kit.getTransactions(limit = 30)
            kit.transactionsFlow.collect { transactions = kit.getTransactions(limit = 30) }
        }
    }

    fun send(to: String, amount: String, tag: String, memo: String) {
        val kit = kit ?: return
        sendResult = "Sending…"
        viewModelScope.launch {
            sendResult = try {
                val tx = withContext(Dispatchers.IO) {
                    kit.sendXrp(to.trim(), BigDecimal(amount.trim()), tag.trim().toLongOrNull(), memo.takeIf { it.isNotBlank() })
                }
                "Submitted ${tx.hash}"
            } catch (e: Exception) {
                "Error: ${e.message}"
            }
        }
    }

    /** Testnet only: asks the public faucet to fund the kit's address. */
    fun fundFromFaucet() {
        val kit = kit ?: return
        faucetResult = "Requesting…"
        viewModelScope.launch {
            faucetResult = try {
                withContext(Dispatchers.IO) {
                    val body = """{"destination":"${kit.receiveAddress}"}""".toRequestBody("application/json".toMediaType())
                    val request = Request.Builder().url("https://faucet.altnet.rippletest.net/accounts").post(body).build()
                    OkHttpClient().newCall(request).execute().use { "Faucet: HTTP ${it.code}" }
                }
            } catch (e: Exception) {
                "Faucet error: ${e.message}"
            }
        }
    }

    fun refresh() {
        kit?.refresh()
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        kit?.stop()
        kit = null
    }

    override fun onCleared() {
        stop()
    }
}
