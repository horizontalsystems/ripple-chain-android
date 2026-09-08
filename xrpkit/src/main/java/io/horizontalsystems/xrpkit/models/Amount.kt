package io.horizontalsystems.xrpkit.models

import java.math.BigDecimal
import java.math.BigInteger

/** An XRPL amount: either XRP (in drops) or an issued currency (IOU) balance. */
sealed class Amount {

    data class Xrp(val drops: BigInteger) : Amount() {
        val xrp: BigDecimal get() = BigDecimal(drops).movePointLeft(DECIMALS)

        companion object {
            const val DECIMALS = 6
            val ZERO = Xrp(BigInteger.ZERO)
            fun fromXrp(value: BigDecimal): Xrp = Xrp(value.movePointRight(DECIMALS).toBigIntegerExact())
            fun fromDrops(value: Long): Xrp = Xrp(BigInteger.valueOf(value))
        }
    }

    data class Issued(val value: BigDecimal, val currency: String, val issuer: String) : Amount()

    val isXrp: Boolean get() = this is Xrp

    /** Decimal value in the amount's own unit (XRP for [Xrp], token units for [Issued]). */
    val decimalValue: BigDecimal
        get() = when (this) {
            is Xrp -> xrp
            is Issued -> value
        }
}
