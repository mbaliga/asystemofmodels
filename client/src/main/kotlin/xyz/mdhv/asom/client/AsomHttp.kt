package xyz.mdhv.asom.client

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * The daemon's own upstream driver waits up to 180 s for a provider response
 * (`OpenAICompatDriver.defaultClient`), and emits nothing until that call has
 * returned. OkHttp's bare 10 s default would therefore abandon completions the
 * user is already being billed for — and the daemon's ledger would record an
 * egress the app never saw. Mirror the driver's budget instead.
 */
internal fun asomDefaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .readTimeout(180, TimeUnit.SECONDS)
    .build()
