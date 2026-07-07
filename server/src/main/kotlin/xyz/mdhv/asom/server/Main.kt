package xyz.mdhv.asom.server

import java.io.File
import xyz.mdhv.asom.catalogue.CatalogueParser
import xyz.mdhv.asom.catalogue.ProviderKind
import xyz.mdhv.asom.contract.Asom
import xyz.mdhv.asom.server.auth.InMemoryTokenRegistry
import xyz.mdhv.asom.server.driver.FakeDriver
import xyz.mdhv.asom.server.keys.InMemoryKeyProvider
import xyz.mdhv.asom.server.ledger.LedgerSink

/**
 * Desktop entry point (P3 gate: `./gradlew :server:run`). Serves the committed
 * fixture catalogue with fake drivers — a full watched-object loop on a dev
 * box, no Android required. Real drivers arrive in P4 behind env keys.
 */
fun main() {
    val fixture = sequenceOf("fixtures/catalogue.v1.json", "../fixtures/catalogue.v1.json")
        .map(::File).firstOrNull { it.exists() }
        ?: error("fixtures/catalogue.v1.json not found; run from the repo root or server/")
    val catalogue = CatalogueParser.parse(fixture.readText())

    val devToken = System.getenv("ASOM_DEV_TOKEN") ?: "asom-dev-token"
    val tokens = InMemoryTokenRegistry().apply { issue(devToken, "desktop") }

    // Fake keys for every fixture provider — FakeDriver never leaves the process.
    val keys = InMemoryKeyProvider(catalogue.providers.associate { it.id to "fake-key-${it.id}" })

    val fake = FakeDriver()
    val drivers = { _: ProviderKind -> fake }

    // Desktop ledger: print each row — the watched object, visible in the terminal.
    val ledger = LedgerSink { record -> println("[ledger] $record") }

    val server = AsomServer(
        AsomServerConfig(
            port = Asom.DEFAULT_PORT,
            catalogue = { catalogue },
            tokens = tokens,
            keys = keys,
            drivers = drivers,
            ledger = ledger,
        ),
    )

    println("asom server ${Asom.VERSION} on http://${Asom.BIND_HOST}:${Asom.DEFAULT_PORT}")
    println("dev bearer token: $devToken")
    println("providers (fake drivers): ${catalogue.providers.joinToString { it.id }}")
    server.start(wait = true)
}
