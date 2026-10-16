package xyz.mdhv.asom.server

import java.io.File
import xyz.mdhv.asom.catalogue.CatalogueParser
import xyz.mdhv.asom.catalogue.ProviderKind
import xyz.mdhv.asom.contract.Asom
import xyz.mdhv.asom.server.auth.InMemoryTokenRegistry
import xyz.mdhv.asom.server.driver.AnthropicDriver
import xyz.mdhv.asom.server.driver.FakeDriver
import xyz.mdhv.asom.server.driver.OpenAICompatDriver
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

    // Real drivers opt-in (P4 owner smoke): ASOM_REAL_DRIVERS=1 plus keys via
    // ASOM_KEY_<PROVIDER_ID> env vars (e.g. ASOM_KEY_OPENROUTER=sk-…).
    val realMode = System.getenv("ASOM_REAL_DRIVERS") == "1"

    val keys = if (realMode) {
        InMemoryKeyProvider(
            catalogue.providers.mapNotNull { p ->
                System.getenv("ASOM_KEY_${p.id.uppercase().replace('-', '_')}")?.let { p.id to it }
            }.toMap(),
        )
    } else {
        // Fake keys for every fixture provider — FakeDriver never leaves the process.
        InMemoryKeyProvider(catalogue.providers.associate { it.id to "fake-key-${it.id}" })
    }

    val drivers: (ProviderKind) -> xyz.mdhv.asom.server.driver.ProviderDriver = if (realMode) {
        val openaiCompat = OpenAICompatDriver()
        val anthropic = AnthropicDriver()
        fun(kind: ProviderKind): xyz.mdhv.asom.server.driver.ProviderDriver = when (kind) {
            ProviderKind.OPENAI_COMPAT -> openaiCompat
            ProviderKind.ANTHROPIC -> anthropic
        }
    } else {
        val fake = FakeDriver()
        fun(_: ProviderKind): xyz.mdhv.asom.server.driver.ProviderDriver = fake
    }

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
    val mode = if (realMode) "REAL drivers (BYOK env keys)" else "fake drivers"
    println("providers ($mode): ${catalogue.providers.joinToString { it.id }}")
    server.start(wait = true)
}
