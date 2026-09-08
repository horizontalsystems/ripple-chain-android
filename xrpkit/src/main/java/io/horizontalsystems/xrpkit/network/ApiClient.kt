package io.horizontalsystems.xrpkit.network

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

internal object ApiClient {

    private val logger = Logger.getLogger("XrpKit")

    fun build(): OkHttpClient {
        val loggingInterceptor = HttpLoggingInterceptor { message -> logger.info(message) }
            .setLevel(HttpLoggingInterceptor.Level.BASIC)

        return OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
