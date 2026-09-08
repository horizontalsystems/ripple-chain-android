package io.horizontalsystems.xrpkit.codec

import io.horizontalsystems.xrpkit.crypto.AccountId
import io.horizontalsystems.xrpkit.models.Amount
import java.math.BigDecimal
import java.math.BigInteger
import java.nio.ByteBuffer

/**
 * Serializes [Amount] to the 8-byte (XRP) or 48-byte (issued currency) ledger format.
 */
internal object AmountCodec {

    private const val MIN_EXPONENT = -96
    private const val MAX_EXPONENT = 80
    private val MIN_MANTISSA = BigInteger("1000000000000000")   // 1e15
    private val MAX_MANTISSA = BigInteger("9999999999999999")   // 1e16 - 1
    private val MAX_DROPS = BigInteger("100000000000000000")    // 1e17

    private const val NOT_XRP_BIT = 1L shl 63
    private const val POSITIVE_BIT = 1L shl 62

    fun encode(amount: Amount): ByteArray = when (amount) {
        is Amount.Xrp -> encodeXrp(amount.drops)
        is Amount.Issued -> encodeIssued(amount)
    }

    private fun encodeXrp(drops: BigInteger): ByteArray {
        require(drops.signum() >= 0) { "XRP amount must not be negative" }
        require(drops < MAX_DROPS) { "XRP amount exceeds 1e17 drops" }
        return ByteBuffer.allocate(8).putLong(drops.toLong() or POSITIVE_BIT).array()
    }

    private fun encodeIssued(amount: Amount.Issued): ByteArray {
        val value = amount.value.stripTrailingZeros()
        val head: Long = if (value.signum() == 0) {
            NOT_XRP_BIT
        } else {
            var mantissa = value.unscaledValue().abs()
            var exponent = -value.scale()
            while (mantissa < MIN_MANTISSA) {
                mantissa = mantissa.multiply(BigInteger.TEN)
                exponent -= 1
            }
            while (mantissa > MAX_MANTISSA) {
                require(mantissa.mod(BigInteger.TEN).signum() == 0) { "Amount has more than 16 significant digits: ${amount.value}" }
                mantissa = mantissa.divide(BigInteger.TEN)
                exponent += 1
            }
            require(exponent in MIN_EXPONENT..MAX_EXPONENT) { "Amount out of range: ${amount.value}" }
            val sign = if (value.signum() > 0) POSITIVE_BIT else 0L
            NOT_XRP_BIT or sign or ((exponent + 97).toLong() shl 54) or mantissa.toLong()
        }
        return ByteBuffer.allocate(48)
            .putLong(head)
            .put(CurrencyCodec.toBytes(amount.currency))
            .put(AccountId.fromAddress(amount.issuer).bytes)
            .array()
    }

    /** Parses the JSON form of an XRP amount: a string of drops. */
    fun fromDropsString(drops: String): Amount.Xrp = Amount.Xrp(BigInteger(drops))

    fun issued(value: String, currency: String, issuer: String): Amount.Issued =
        Amount.Issued(BigDecimal(value), CurrencyCodec.normalize(currency), issuer)
}
