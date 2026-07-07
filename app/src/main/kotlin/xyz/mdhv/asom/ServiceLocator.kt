package xyz.mdhv.asom

import android.content.Context
import java.security.SecureRandom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import xyz.mdhv.asom.catalogue.Catalogue
import xyz.mdhv.asom.catalogue.CatalogueParser
import xyz.mdhv.asom.catalogue.ProviderKind
import xyz.mdhv.asom.contract.Asom
import xyz.mdhv.asom.ledger.LedgerDatabase
import xyz.mdhv.asom.ledger.toEntity
import xyz.mdhv.asom.routing.CooldownRegistry
import xyz.mdhv.asom.routing.LatencyTracker
import xyz.mdhv.asom.server.AsomServerConfig
import xyz.mdhv.asom.server.auth.InMemoryTokenRegistry
import xyz.mdhv.asom.server.driver.AnthropicDriver
import xyz.mdhv.asom.server.driver.OpenAICompatDriver
import xyz.mdhv.asom.server.driver.ProviderDriver
import xyz.mdhv.asom.server.keys.KeyProvider
import xyz.mdhv.asom.server.ledger.LedgerSink
import xyz.mdhv.asom.vault.DataKeyVault
import xyz.mdhv.asom.vault.KeystoreWrappingCipher
import xyz.mdhv.asom.vault.RoomVaultStore

/**
 * App-scope wiring. Deliberately plain (no DI framework — §1.8 keeps the
 * dependency graph tiny). Everything the service and dashboard share.
 */
object ServiceLocator {

    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Fixture catalogue bundled as an asset until CATALOGUE_URL (OWNER-FILL). */
    val catalogue: Catalogue by lazy {
        CatalogueParser.parse(
            appContext.assets.open("catalogue.v1.json").readBytes().decodeToString(),
        )
    }

    val vault: DataKeyVault by lazy {
        DataKeyVault(KeystoreWrappingCipher(), RoomVaultStore(appContext))
    }

    val ledgerDb: LedgerDatabase by lazy { LedgerDatabase.open(appContext) }

    val cooldowns = CooldownRegistry()
    val latency = LatencyTracker()

    /**
     * P5 stopgap: one device-owner token, generated once, surfaced in the
     * Status tab for manual testing. P6 replaces this seam with the AIDL
     * pairing store (§5.7).
     */
    val devToken: String by lazy {
        val prefs = appContext.getSharedPreferences("asom", Context.MODE_PRIVATE)
        prefs.getString("dev_token", null) ?: buildString {
            val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
            bytes.forEach { append("%02x".format(it)) }
        }.also { prefs.edit().putString("dev_token", it).apply() }
    }

    val tokenRegistry: InMemoryTokenRegistry by lazy {
        InMemoryTokenRegistry().apply { issue(devToken, "device-owner") }
    }

    private val openAiCompatDriver by lazy { OpenAICompatDriver() }
    private val anthropicDriver by lazy { AnthropicDriver() }

    fun serverConfig(): AsomServerConfig = AsomServerConfig(
        port = Asom.DEFAULT_PORT,
        catalogue = { catalogue },
        tokens = tokenRegistry,
        keys = KeyProvider { vault.getKey(it) },
        drivers = { kind: ProviderKind ->
            when (kind) {
                ProviderKind.OPENAI_COMPAT -> openAiCompatDriver
                ProviderKind.ANTHROPIC -> anthropicDriver
            }
        },
        ledger = LedgerSink { record ->
            scope.launch { ledgerDb.dao().insert(record.toEntity()) }
        },
        cooldowns = cooldowns,
        latency = latency,
    )
}
