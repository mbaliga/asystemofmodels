package xyz.mdhv.asom.server

import xyz.mdhv.asom.contract.Asom

/**
 * Desktop entry point (brief P3 gate: `./gradlew :server:run`).
 * P0 placeholder — the Ktor CIO server lands in P3.
 */
fun main() {
    println("asom server ${Asom.VERSION} — placeholder (P3 brings the Ktor CIO server on ${Asom.BIND_HOST}:${Asom.DEFAULT_PORT})")
}
