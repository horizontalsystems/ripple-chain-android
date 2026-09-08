package io.horizontalsystems.ripplekit.network

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.math.BigInteger

/** rippled returned `status: error`. [code] is the rippled error token, e.g. `actNotFound`. */
class RpcError(val code: String, val errorMessage: String?) : Exception("$code${errorMessage?.let { ": $it" } ?: ""}")

/** Every endpoint failed, or the last one returned an unusable response. */
class NoEndpointAvailable(cause: Throwable?) : Exception("No XRPL endpoint responded", cause)

/** A response did not have the shape the kit expects. Treated as untrusted input. */
class InvalidResponse(message: String) : Exception(message)

class AccountInfo(
    val address: String,
    val balanceDrops: BigInteger,
    val sequence: Long,
    val ownerCount: Long,
    val flags: Long,
) {
    /** lsfRequireDestTag: payments to this account must carry a destination tag. */
    val requiresDestinationTag: Boolean get() = flags and 0x00020000L != 0L

    /** lsfDisallowXRP: the account advises against receiving XRP (not enforced on-ledger). */
    val disallowXrp: Boolean get() = flags and 0x00080000L != 0L

    /** lsfDepositAuth: only pre-authorized accounts may send payments to this one. */
    val depositAuth: Boolean get() = flags and 0x01000000L != 0L
}

class ServerState(
    val validatedLedger: Long,
    val reserveBaseDrops: Long,
    val reserveIncDrops: Long,
)

class FeeInfo(
    val baseFeeDrops: Long,
    val openLedgerFeeDrops: Long,
    val minimumFeeDrops: Long,
    val medianFeeDrops: Long,
)

class TrustLineInfo(
    val currency: String,
    val issuer: String,
    val balance: String,
    val limit: String,
    val limitPeer: String,
    val noRipple: Boolean,
    val frozen: Boolean,
    val frozenByPeer: Boolean,
    val authorized: Boolean,
)

/** One entry of an `account_tx` page (API v1 shape). */
class RawTransaction(
    val hash: String,
    val tx: JsonObject,
    val meta: JsonObject,
    val validated: Boolean,
    val ledgerIndex: Long,
    /** Ledger close time in seconds since the Ripple epoch (2000-01-01). */
    val date: Long?,
)

class AccountTxPage(
    val transactions: List<RawTransaction>,
    val marker: JsonElement?,
)

class SubmitResult(
    val engineResult: String,
    val engineResultCode: Int,
    val engineResultMessage: String?,
    val hash: String?,
    val accepted: Boolean,
    val applied: Boolean,
    val queued: Boolean,
) {
    val isSuccess: Boolean get() = engineResult.startsWith("tes")
    val isQueued: Boolean get() = engineResult == "terQUEUED" || queued
}

class TxResult(
    val hash: String,
    val validated: Boolean,
    val ledgerIndex: Long?,
    val tx: JsonObject,
    val meta: JsonObject?,
    val date: Long?,
)
