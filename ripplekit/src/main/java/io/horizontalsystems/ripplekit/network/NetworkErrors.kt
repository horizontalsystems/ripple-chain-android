package io.horizontalsystems.ripplekit.network

import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

internal object NetworkErrors {

    /**
     * True for failures that come from the device's connectivity rather than from the ledger or
     * the kit: DNS not ready after the app resumes, a dropped socket, a timeout. These clear
     * themselves within seconds and are not worth showing as a sync error right away.
     */
    fun isTransient(error: Throwable): Boolean {
        val cause = (error as? NoEndpointAvailable)?.cause ?: error
        return cause is UnknownHostException ||
            cause is ConnectException ||
            cause is SocketTimeoutException ||
            cause is SocketException ||
            cause is IOException
    }

    /** DNS failure: the network is usually just not up yet. */
    fun isDnsFailure(error: Throwable): Boolean = error is UnknownHostException
}
