package io.horizontalsystems.ripplekit.network

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import java.math.BigInteger
import java.net.URL
import java.util.logging.Logger

/**
 * rippled JSON-RPC client with endpoint failover. Transport and HTTP failures move to the next
 * URL; a well-formed rippled error (e.g. `actNotFound`) is returned to the caller as [RpcError]
 * without failover, because every node would answer the same.
 */
class RpcProvider private constructor(private val endpoints: List<Endpoint>) {

    private class Endpoint(val url: URL, val api: RpcApi)

    private interface RpcApi {
        @POST("/")
        suspend fun call(@Body body: JsonObject): JsonObject
    }

    private val logger = Logger.getLogger("RippleKit")

    @Volatile
    private var preferredIndex = 0

    suspend fun call(method: String, params: JsonObject = JsonObject()): JsonObject {
        val request = JsonObject().apply {
            addProperty("method", method)
            add("params", JsonArray().apply { add(params) })
        }

        var lastError: Throwable? = null
        for (attempt in endpoints.indices) {
            val index = (preferredIndex + attempt) % endpoints.size
            val endpoint = endpoints[index]
            try {
                val response = endpoint.api.call(request)
                val result = response.getAsJsonObject("result")
                    ?: throw InvalidResponse("$method: missing result")

                if (result.optString("status") == "error") {
                    throw RpcError(result.optString("error") ?: "unknown", result.optString("error_message"))
                }
                if (result.has("warning") && result.optString("warning") == "load") {
                    // rate-limit warning: prefer another endpoint for subsequent calls
                    preferredIndex = (index + 1) % endpoints.size
                } else {
                    preferredIndex = index
                }
                return result
            } catch (e: CancellationException) {
                throw e
            } catch (e: RpcError) {
                throw e
            } catch (e: Throwable) {
                logger.info("$method failed on ${endpoint.url}: ${e.message}")
                lastError = e
            }
        }
        throw NoEndpointAvailable(lastError)
    }

    /** Null when the account has never been funded (`actNotFound`). */
    suspend fun accountInfo(address: String): AccountInfo? {
        val result = try {
            call("account_info", JsonObject().apply {
                addProperty("account", address)
                addProperty("ledger_index", "validated")
            })
        } catch (e: RpcError) {
            if (e.code == "actNotFound") return null
            throw e
        }
        val data = result.getAsJsonObject("account_data") ?: throw InvalidResponse("account_info: missing account_data")
        return AccountInfo(
            address = data.requireString("Account"),
            balanceDrops = BigInteger(data.requireString("Balance")),
            sequence = data.requireLong("Sequence"),
            ownerCount = data.requireLong("OwnerCount"),
            flags = data.optLong("Flags") ?: 0,
        )
    }

    suspend fun accountLines(address: String): List<TrustLineInfo> {
        val lines = mutableListOf<TrustLineInfo>()
        var marker: JsonElement? = null
        do {
            val result = try {
                call("account_lines", JsonObject().apply {
                    addProperty("account", address)
                    addProperty("ledger_index", "validated")
                    addProperty("limit", 400)
                    marker?.let { add("marker", it) }
                })
            } catch (e: RpcError) {
                if (e.code == "actNotFound") return emptyList()
                throw e
            }
            val array = result.getAsJsonArray("lines") ?: throw InvalidResponse("account_lines: missing lines")
            for (element in array) {
                val line = element.asJsonObject
                lines.add(
                    TrustLineInfo(
                        currency = line.requireString("currency"),
                        issuer = line.requireString("account"),
                        balance = line.requireString("balance"),
                        limit = line.requireString("limit"),
                        limitPeer = line.optString("limit_peer") ?: "0",
                        noRipple = line.optBoolean("no_ripple"),
                        frozen = line.optBoolean("freeze"),
                        frozenByPeer = line.optBoolean("freeze_peer"),
                        authorized = line.optBoolean("authorized"),
                    )
                )
            }
            marker = result.get("marker")?.takeUnless { it.isJsonNull }
        } while (marker != null)
        return lines
    }

    suspend fun serverState(): ServerState {
        val result = call("server_state")
        val state = result.getAsJsonObject("state") ?: throw InvalidResponse("server_state: missing state")
        val ledger = state.getAsJsonObject("validated_ledger") ?: throw InvalidResponse("server_state: missing validated_ledger")
        return ServerState(
            validatedLedger = ledger.requireLong("seq"),
            reserveBaseDrops = ledger.requireLong("reserve_base"),
            reserveIncDrops = ledger.requireLong("reserve_inc"),
        )
    }

    suspend fun fee(): FeeInfo {
        val result = call("fee")
        val drops = result.getAsJsonObject("drops") ?: throw InvalidResponse("fee: missing drops")
        return FeeInfo(
            baseFeeDrops = drops.requireString("base_fee").toLong(),
            openLedgerFeeDrops = drops.requireString("open_ledger_fee").toLong(),
            minimumFeeDrops = drops.requireString("minimum_fee").toLong(),
            medianFeeDrops = drops.requireString("median_fee").toLong(),
        )
    }

    suspend fun accountTx(
        address: String,
        ledgerIndexMin: Long = -1,
        ledgerIndexMax: Long = -1,
        limit: Int = 200,
        forward: Boolean = false,
        marker: JsonElement? = null,
    ): AccountTxPage {
        val result = try {
            call("account_tx", JsonObject().apply {
                addProperty("account", address)
                addProperty("ledger_index_min", ledgerIndexMin)
                addProperty("ledger_index_max", ledgerIndexMax)
                addProperty("limit", limit)
                addProperty("forward", forward)
                marker?.let { add("marker", it) }
            })
        } catch (e: RpcError) {
            if (e.code == "actNotFound") return AccountTxPage(emptyList(), null)
            throw e
        }
        val array = result.getAsJsonArray("transactions") ?: throw InvalidResponse("account_tx: missing transactions")
        val transactions = array.mapNotNull { element ->
            val item = element.asJsonObject
            // API v1 nests the transaction under "tx"; v2 servers answering v1 keep the same shape
            val tx = item.getAsJsonObject("tx") ?: item.getAsJsonObject("tx_json") ?: return@mapNotNull null
            val meta = item.get("meta")?.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val hash = tx.optString("hash") ?: item.optString("hash") ?: return@mapNotNull null
            RawTransaction(
                hash = hash,
                tx = tx,
                meta = meta,
                validated = item.optBoolean("validated"),
                ledgerIndex = tx.optLong("ledger_index") ?: item.optLong("ledger_index") ?: return@mapNotNull null,
                date = tx.optLong("date"),
            )
        }
        return AccountTxPage(transactions, result.get("marker")?.takeUnless { it.isJsonNull })
    }

    suspend fun submit(txBlobHex: String): SubmitResult {
        val result = call("submit", JsonObject().apply {
            addProperty("tx_blob", txBlobHex)
            addProperty("fail_hard", false)
        })
        return SubmitResult(
            engineResult = result.requireString("engine_result"),
            engineResultCode = result.optLong("engine_result_code")?.toInt() ?: 0,
            engineResultMessage = result.optString("engine_result_message"),
            hash = result.getAsJsonObject("tx_json")?.optString("hash"),
            accepted = result.optBoolean("accepted"),
            applied = result.optBoolean("applied"),
            queued = result.optBoolean("queued"),
        )
    }

    /** Null when the node does not know the transaction (`txnNotFound`). */
    suspend fun tx(hash: String): TxResult? {
        val result = try {
            call("tx", JsonObject().apply { addProperty("transaction", hash) })
        } catch (e: RpcError) {
            if (e.code == "txnNotFound") return null
            throw e
        }
        val meta = result.get("meta")?.takeIf { it.isJsonObject }?.asJsonObject
        return TxResult(
            hash = result.optString("hash") ?: hash,
            validated = result.optBoolean("validated"),
            ledgerIndex = result.optLong("ledger_index"),
            tx = result,
            meta = meta,
            date = result.optLong("date"),
        )
    }

    companion object {
        fun create(urls: List<URL>, client: OkHttpClient = ApiClient.build()): RpcProvider {
            require(urls.isNotEmpty()) { "At least one RPC URL is required" }
            val endpoints = urls.map { url ->
                val retrofit = Retrofit.Builder()
                    .baseUrl(url)
                    .client(client)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()
                Endpoint(url, retrofit.create(RpcApi::class.java))
            }
            return RpcProvider(endpoints)
        }
    }
}

internal fun JsonObject.optString(name: String): String? =
    get(name)?.takeIf { it.isJsonPrimitive }?.asString

internal fun JsonObject.requireString(name: String): String =
    optString(name) ?: throw InvalidResponse("missing field '$name'")

internal fun JsonObject.optLong(name: String): Long? =
    get(name)?.takeIf { it.isJsonPrimitive }?.let { p ->
        if (p.asJsonPrimitive.isNumber) p.asLong else p.asString.toLongOrNull()
    }

internal fun JsonObject.requireLong(name: String): Long =
    optLong(name) ?: throw InvalidResponse("missing field '$name'")

internal fun JsonObject.optBoolean(name: String): Boolean =
    get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean ?: false
