package io.horizontalsystems.xrpkit.network

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

internal object ApiClient {

    private val logger = Logger.getLogger("XrpKit")

    /**
     * Sent on every request. xrplcluster.com answers HTTP 418 to OkHttp's default agent string,
     * so an identifying agent is required, not just polite.
     */
    const val USER_AGENT = "xrp-android/1.0 (HorizontalSystems; +https://github.com/horizontalsystems/xrp-android)"

    fun build(userAgent: String = USER_AGENT): OkHttpClient {
        val loggingInterceptor = HttpLoggingInterceptor { message -> logger.info(message) }
            .setLevel(HttpLoggingInterceptor.Level.BASIC)

        return OkHttpClient.Builder()
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", userAgent)
                        .build()
                )
            }
            .addInterceptor(loggingInterceptor)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
