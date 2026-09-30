package xyz.mdhv.asom

import android.content.Context
import android.util.Log
import java.security.SecureRandom
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import xyz.mdhv.asom.catalogue.Catalogue
import xyz.mdhv.asom.catalogue.CatalogueParser
import xyz.mdhv.asom.catalogue.ProviderKind
import xyz.mdhv.asom.contract.Asom
import xyz.mdhv.asom.contract.Egress
import xyz.mdhv.asom.contract.RouteRecord
import xyz.mdhv.asom.ledger.LedgerDatabase
import xyz.mdhv.asom.ledger.VerboseLogEntity
import xyz.mdhv.asom.ledger.VerbosePurgeWorker
import xyz.mdhv.asom.ledger.toEntity
import xyz.mdhv.asom.storage.DownloadLedgerBridge
import xyz.mdhv.asom.storage.DownloadLedgerSink
import xyz.mdhv.asom.storage.ModelDownloadManager
import xyz.mdhv.asom.pairing.PairingDatabase
import xyz.mdhv.asom.pairing.PairingRegistry
import xyz.mdhv.asom.pairing.PairingRegistryHolder
import xyz.mdhv.asom.pairing.TokenCheck
import xyz.mdhv.asom.server.auth.AuthResult
import xyz.mdhv.asom.server.auth.TokenValidator
import xyz.mdhv.asom.routing.CooldownRegistry
import xyz.mdhv.asom.routing.LatencyTracker
import xyz.mdhv.asom.server.AsomServerConfig
import xyz.mdhv.asom.server.auth.InMemoryTokenRegistry
import xyz.mdhv.asom.server.driver.AnthropicDriver
import xyz.mdhv.asom.server.driver.OpenAICompatDriver
import xyz.mdhv.asom.server.driver.ProviderDriver
import xyz.mdhv.asom.server.keys.KeyProvider
import xyz.mdhv.asom.server.ledger.BodyRecord
import xyz.mdhv.asom.server.ledger.BodySink
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

    /**
     * SupervisorJob stops sibling cancellation but NOT the uncaught-exception
     * path: without a handler, one failed Room/Keystore write on this scope
     * reaches Android's default handler and kills the daemon process. The
     * throwable's message is deliberately not logged (§1.4 keeps key material
     * out of every sink).
     */
    val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            CoroutineExceptionHandler { _, t -> Log.w(TAG, "background task failed: ${t.javaClass.name}") },
    )

    /** Set when a ledger row could not be written — surfaced in the Status tab. */
    val ledgerDegraded = MutableStateFlow(false)

    /**
     * §9 verbose mode, live. Persisted in [Settings] and mirrored here so the
     * capture gate, the Status tab and the foreground-service notification
     * cannot disagree about whether bodies are being recorded. Default OFF.
     */
    val verboseMode: MutableStateFlow<Boolean> by lazy {
        MutableStateFlow(Settings(appContext).verboseModeEnabled)
    }

    /**
     * The one write path for the §9 opt-in. Turning it OFF deliberately leaves
     * the purge job scheduled: rows already captured still owe their 24h TTL.
     */
    fun setVerboseMode(enabled: Boolean) {
        Settings(appContext).verboseModeEnabled = enabled
        verboseMode.value = enabled
        if (enabled) VerbosePurgeWorker.schedule(appContext)
    }

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

    val modelDownloadManager: ModelDownloadManager by lazy {
        ModelDownloadManager(appContext) { catalogue }
    }

    /**
     * §1.3: WorkManager cold-starts this process to run a queued download with
     * no UI composed, so the sink is installed from Application.onCreate —
     * never as a side effect of touching [modelDownloadManager] lazily. The
     * write is synchronous because the worker calls it from its own background
     * thread and must not return success while the row is still owed.
     */
    fun installDownloadLedgerSink() {
        DownloadLedgerBridge.sink = DownloadLedgerSink { modelId, status, latencyMs ->
            runCatching {
                ledgerDb.dao().insert(
                    RouteRecord(
                        ts = System.currentTimeMillis(),
                        callerPkg = "asom",
                        requestedModel = modelId,
                        egress = Egress.DOWNLOAD,
                        bytesOut = 0,
                        latencyMs = latencyMs,
                        status = status,
                    ).toEntity(),
                )
            }.onFailure { ledgerDegraded.value = true }
        }
    }

    val cooldowns = CooldownRegistry()

    private val latencyStore: SharedPrefsLatencyStore by lazy {
        SharedPrefsLatencyStore(appContext, scope)
    }

    /** §7 `fastest` is a persisted EWMA — a cold tracker silently routes as `cheapest`. */
    val latency: LatencyTracker by lazy {
        LatencyTracker(store = latencyStore).also { it.preload(latencyStore.load()) }
    }

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

    val pairingDb: PairingDatabase by lazy { PairingDatabase.open(appContext) }

    val pairingRegistry: PairingRegistry by lazy { PairingRegistryHolder.get(appContext) }

    /**
     * Auth for the HTTP layer (§5.7): pairing store first (constant-time
     * hash check, revocation per request), device-owner dev token second.
     */
    private val tokenValidator: TokenValidator by lazy {
        TokenValidator { raw ->
            when (val check = pairingRegistry.check(raw)) {
                is TokenCheck.Valid -> AuthResult.Valid(check.callerPkg)
                TokenCheck.Revoked -> AuthResult.Revoked
                TokenCheck.Unknown -> tokenRegistry.validate(raw)
            }
        }
    }

    private val openAiCompatDriver by lazy { OpenAICompatDriver() }
    private val anthropicDriver by lazy { AnthropicDriver() }

    /**
     * §9 verbose rows. Local storage only: `verbose_log` is not part of the
     * export payload (§1.1) and the server hands this seam bodies only — never
     * headers, so no BYOK key and no bearer token can reach it (§1.4).
     * A failed capture is swallowed: verbose mode is a debugging aid, and it
     * must never be able to fail a request or falsify [ledgerDegraded], which
     * means "the EGRESS ledger is incomplete".
     */
    private val verboseBodySink = object : BodySink {
        override fun isCapturing(): Boolean = verboseMode.value

        override suspend fun capture(record: BodyRecord) {
            withContext(Dispatchers.IO) {
                runCatching {
                    ledgerDb.dao().insertVerbose(
                        VerboseLogEntity(
                            ts = record.ts,
                            callerPkg = record.callerPkg,
                            requestBody = record.requestBody,
                            responseBody = record.responseBody,
                        ),
                    )
                }
            }
        }
    }

    fun serverConfig(activity: xyz.mdhv.asom.server.ActivityListener = xyz.mdhv.asom.server.ActivityListener { _, _ -> }): AsomServerConfig =
        AsomServerConfig(
            port = Asom.DEFAULT_PORT,
            catalogue = { catalogue },
            tokens = tokenValidator,
            keys = KeyProvider { vault.getKey(it) },
            drivers = { kind: ProviderKind ->
                when (kind) {
                    ProviderKind.OPENAI_COMPAT -> openAiCompatDriver
                    ProviderKind.ANTHROPIC -> anthropicDriver
                }
            },
            // §1.3: the row is owed for egress that already happened, so it is
            // committed inline — the server does not finish the response until
            // this returns. Handing it to a background scope would lose the row
            // to a process kill in exactly that window.
            ledger = LedgerSink { record ->
                withContext(Dispatchers.IO) {
                    runCatching { ledgerDb.dao().insert(record.toEntity()) }
                        .onFailure { ledgerDegraded.value = true }
                }
            },
            bodies = verboseBodySink,
            cooldowns = cooldowns,
            latency = latency,
            activity = activity,
        )

    private const val TAG = "asom"
}
