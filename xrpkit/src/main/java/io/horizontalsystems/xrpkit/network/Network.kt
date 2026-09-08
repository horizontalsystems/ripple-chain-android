package io.horizontalsystems.xrpkit.network

import java.net.URL

enum class Network(
    val id: Int,
    val isMainNet: Boolean,
    /** JSON-RPC endpoints in failover order. */
    val rpcUrls: List<URL>,
    val explorerUrl: String,
) {
    MainNet(
        id = 0,
        isMainNet = true,
        rpcUrls = listOf(
            URL("https://xrplcluster.com/"),
            URL("https://s2.ripple.com:51234/"),
            URL("https://s1.ripple.com:51234/"),
        ),
        explorerUrl = "https://livenet.xrpl.org",
    ),
    TestNet(
        id = 1,
        isMainNet = false,
        rpcUrls = listOf(
            URL("https://s.altnet.rippletest.net:51234/"),
            URL("https://testnet.xrpl-labs.com/"),
        ),
        explorerUrl = "https://testnet.xrpl.org",
    );

    fun transactionUrl(hash: String) = "$explorerUrl/transactions/$hash"
    fun accountUrl(address: String) = "$explorerUrl/accounts/$address"
}
