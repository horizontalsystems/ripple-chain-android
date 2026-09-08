package io.horizontalsystems.xrpkit.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.horizontalsystems.xrpkit.XrpKit
import io.horizontalsystems.xrpkit.models.Amount
import io.horizontalsystems.xrpkit.models.Transaction
import io.horizontalsystems.xrpkit.network.Network

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen()
                }
            }
        }
    }
}

@Composable
fun MainScreen(viewModel: MainViewModel = viewModel()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val kit = viewModel.kit
        if (kit == null) {
            SetupSection(viewModel)
        } else {
            AccountSection(viewModel, kit)
        }
    }
}

@Composable
private fun SetupSection(viewModel: MainViewModel) {
    Text("XrpKit Sample", style = MaterialTheme.typography.h6)

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Network.values().forEach { network ->
            OutlinedButton(onClick = { viewModel.network = network }) {
                Text(if (network == viewModel.network) "✓ ${network.name}" else network.name)
            }
        }
    }

    OutlinedTextField(
        value = viewModel.mnemonic,
        onValueChange = { viewModel.mnemonic = it },
        label = { Text("Mnemonic") },
        modifier = Modifier.fillMaxWidth()
    )
    Button(onClick = { viewModel.startFromMnemonic() }, modifier = Modifier.fillMaxWidth()) {
        Text("Restore")
    }

    OutlinedTextField(
        value = viewModel.watchAddress,
        onValueChange = { viewModel.watchAddress = it },
        label = { Text("Watch address (r...)") },
        modifier = Modifier.fillMaxWidth()
    )
    OutlinedButton(onClick = { viewModel.startWatch() }, modifier = Modifier.fillMaxWidth()) {
        Text("Watch")
    }

    viewModel.error?.let { Text("Error: $it", color = MaterialTheme.colors.error) }
}

@Composable
private fun AccountSection(viewModel: MainViewModel, kit: XrpKit) {
    Text("${kit.network.name} · ledger ${viewModel.ledgerIndex}", style = MaterialTheme.typography.caption)
    Text(kit.receiveAddress, style = MaterialTheme.typography.body2)
    Text("Sync: ${viewModel.syncState}", style = MaterialTheme.typography.caption)
    Text("Tx sync: ${viewModel.transactionsSyncState}", style = MaterialTheme.typography.caption)

    Divider()
    if (!viewModel.activated) {
        Text("Account not activated: first deposit must be at least ${kit.baseReserve.xrp.toPlainString()} XRP", color = MaterialTheme.colors.error)
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Balance")
        Text("${viewModel.balance} XRP")
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Available")
        Text("${viewModel.available} XRP")
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Reserve")
        Text("${viewModel.reserve} XRP")
    }
    viewModel.trustLines.forEach { line ->
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${XrpKit.displayCurrencyCode(line.currency)} · ${line.issuer.take(8)}…" + if (line.frozen) " (frozen)" else "")
            Text(line.balance.toPlainString())
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { viewModel.refresh() }) { Text("Refresh") }
        if (!kit.isMainNet) {
            OutlinedButton(onClick = { viewModel.fundFromFaucet() }) { Text("Faucet") }
        }
    }
    viewModel.faucetResult?.let { Text(it, style = MaterialTheme.typography.caption) }

    Divider()
    if (!kit.isWatchOnly) {
        SendSection(viewModel)
        Divider()
    }

    Text("Transactions", style = MaterialTheme.typography.h6)
    viewModel.transactions.forEach { tx -> TransactionRow(tx, kit.receiveAddress) }

    Divider()
    OutlinedButton(onClick = { viewModel.stop() }, modifier = Modifier.fillMaxWidth()) {
        Text("Stop")
    }
}

@Composable
private fun SendSection(viewModel: MainViewModel) {
    var to by rememberSaveable { mutableStateOf("") }
    var amount by rememberSaveable { mutableStateOf("") }
    var tag by rememberSaveable { mutableStateOf("") }
    var memo by rememberSaveable { mutableStateOf("") }

    Text("Send XRP", style = MaterialTheme.typography.h6)
    OutlinedTextField(value = to, onValueChange = { to = it }, label = { Text("To (r... or X...)") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(value = amount, onValueChange = { amount = it }, label = { Text("Amount (XRP)") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(value = tag, onValueChange = { tag = it }, label = { Text("Destination tag (optional)") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(value = memo, onValueChange = { memo = it }, label = { Text("Memo (optional)") }, modifier = Modifier.fillMaxWidth())
    Button(
        onClick = { viewModel.send(to, amount, tag, memo) },
        enabled = to.isNotBlank() && amount.isNotBlank(),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("Send")
    }
    viewModel.sendResult?.let { Text(it, style = MaterialTheme.typography.caption) }
}

@Composable
private fun TransactionRow(tx: Transaction, ownAddress: String) {
    val direction = when {
        tx.isIncoming(ownAddress) -> "IN"
        tx.isOutgoing(ownAddress) -> "OUT"
        else -> ""
    }
    val status = when {
        tx.isPending -> "pending"
        tx.failed -> "failed ${tx.result ?: ""}"
        else -> "ok"
    }
    val amount = (tx.deliveredAmount ?: tx.amount)?.let { formatAmount(it) } ?: ""
    Column(modifier = Modifier.fillMaxWidth()) {
        Text("${tx.type} $direction $amount · $status", style = MaterialTheme.typography.body2)
        Text(tx.hash.take(16) + "… " + (tx.destinationTag?.let { "tag $it " } ?: "") + (tx.memo ?: ""), style = MaterialTheme.typography.caption)
    }
}

private fun formatAmount(amount: Amount): String = when (amount) {
    is Amount.Xrp -> "${amount.xrp.stripTrailingZeros().toPlainString()} XRP"
    is Amount.Issued -> "${amount.value.stripTrailingZeros().toPlainString()} ${XrpKit.displayCurrencyCode(amount.currency)}"
}
