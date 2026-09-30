package xyz.mdhv.asom.clientcloud

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * OkHttp's bare 10 s read timeout is shorter than a routine completion takes to
 * generate, so the default client would abandon requests the user's own key is
 * already being billed for. Matches the budget the daemon's driver uses for the
 * same upstream calls.
 */
internal fun cloudDefaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .readTimeout(180, TimeUnit.SECONDS)
    .build()
