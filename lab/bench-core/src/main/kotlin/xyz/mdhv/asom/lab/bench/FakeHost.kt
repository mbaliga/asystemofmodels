package xyz.mdhv.asom.lab.bench

import java.util.SplittableRandom

/**
 * A deterministic fake host: a virtual clock, a scripted engine, a heat model and scripted injections. Everything it produces is
 * SIMULATED - NOT DEVICE EVIDENCE (LAB_SPEC R7). It exists so that the run-plan interpreter and the governor FSM can be driven, and
 * their event logs pinned as executor-trace vectors (design 6.5 B23), without any engine or device.
 */
data class TierSpeed(
    val loadColdMicros: Long,
    val loadWarmMicros: Long,
    val prefillUsPerToken: Long,
    val decodeUsPerToken: Long,
    /** Extra decode time per token when the KV already holds 2048 or more tokens, in permille. */
    val depthSlowdownPermille: Long,
    val kvBytesPerToken: Long,
    val nllMilliNatsPerToken: Long,
    val peakFootprintBytes: Long,
    val tokenizeUs: Long = 900L,
)

/** From [fromHeatMs] of accumulated busy time upward, decode takes [slowdownPermille]/1000 of its cool time and the thermal readings are [code] and [headroomPermille]. */
data class HeatStep(val fromHeatMs: Long, val slowdownPermille: Long, val code: Int, val headroomPermille: Int)

/** At [atMs] of virtual time, something happens: CHARGER_REMOVED, CHARGER_ATTACHED, BACKGROUND, FOREGROUND, REAL_REQUEST, MEMORY_PRESSURE, THERMAL_CODE, BATTERY_TEMP, GPU_BUSY, THERMAL_LOST, THERMAL_BACK, STOP. */
data class Injection(val atMs: Long, val kind: String, val value: Long = 0L)

data class FakeConfig(
    val device: DeviceInfo,
    val harness: HarnessInfo,
    val memory: MemoryReading,
    val power: PowerReading,
    val presence: Presence = Presence(foreground = true, screenOn = true, batterySaver = false, lowPowerMode = false),
    val baseBatteryTempDeciC: Int? = 310,
    val tiers: Map<String, TierSpeed>,
    val heat: List<HeatStep> = listOf(HeatStep(0, 1000, 0, 400)),
    /** Heat removed per second of idle time, in milliseconds of busy time. */
    val coolMsPerIdleSecond: Long = 2000L,
    val injections: List<Injection> = emptyList(),
    val modelsPresent: Set<String>? = null,
    val numericsRefs: Map<String, Long> = emptyMap(),
    val spins: List<Long> = emptyList(),
    val bestSpinMicros: Long? = 200_000L,
    val seed: Long = 1L,
    val jitterPermille: Int = 6,
    val daemon: Boolean = false,
    val freeStorageBytes: Long = 50_000_000_000L,
    val epochStartMs: Long = 1_790_668_800_000L,
    val engineOomTiers: Set<String> = emptySet(),
    val thermalAvailable: Boolean = true,
)

class FakeHost(val cfg: FakeConfig) : BenchHost {
    private var micros = 0L
    private var heatMs = 0L
    private val rnd = SplittableRandom(cfg.seed)
    private val applied = HashSet<Int>()
    private var forcedCode: Int? = null
    private var batteryTemp: Int? = cfg.baseBatteryTempDeciC
    private var chargerOn = cfg.power.source == "ac"
    private var foreground = cfg.presence.foreground
    private var memPressure = false
    private var gpuBusy: Int? = null
    private var thermalLost = !cfg.thermalAvailable
    private var pending = false
    private var spinCalls = 0
    var served = 0
        private set
    var wakeLocksHeld = 0
        private set
    var brightnessDimmed = false
        private set
    var openModels = 0
        private set
    var armed = false
        private set
    var onStop: (() -> Unit)? = null
    val notices = mutableListOf<String>()
    val engineCalls = mutableListOf<String>()

    /** Resources still held: every exit path of a run must leave this empty (benchmark.md 11.4). */
    fun leaks(): List<String> = buildList {
        if (wakeLocksHeld != 0) add("wake lock held ($wakeLocksHeld)")
        if (brightnessDimmed) add("brightness not restored")
        if (openModels != 0) add("$openModels model(s) still loaded")
        if (armed) add("availability still armed for benchmarking")
    }

    private fun tick() {
        val ms = micros / 1000L
        cfg.injections.forEachIndexed { i, inj ->
            if (i !in applied && ms >= inj.atMs) {
                applied += i
                when (inj.kind) {
                    "CHARGER_REMOVED" -> chargerOn = false
                    "CHARGER_ATTACHED" -> chargerOn = true
                    "BACKGROUND" -> foreground = false
                    "FOREGROUND" -> foreground = true
                    "REAL_REQUEST" -> pending = true
                    "MEMORY_PRESSURE" -> memPressure = true
                    "THERMAL_CODE" -> forcedCode = inj.value.toInt()
                    "BATTERY_TEMP" -> batteryTemp = inj.value.toInt()
                    "GPU_BUSY" -> gpuBusy = inj.value.toInt()
                    "THERMAL_LOST" -> thermalLost = true
                    "THERMAL_BACK" -> thermalLost = false
                    "STOP" -> onStop?.invoke()
                    else -> error("unknown injection ${inj.kind}")
                }
            }
        }
    }

    private fun step(): HeatStep = cfg.heat.lastOrNull { heatMs >= it.fromHeatMs } ?: cfg.heat.first()

    private fun jitter(us: Long): Long {
        if (cfg.jitterPermille == 0) return us
        val j = rnd.nextInt(2 * cfg.jitterPermille + 1) - cfg.jitterPermille
        return us + us * j / 1000L
    }

    private fun busy(us: Long) {
        micros += us
        heatMs += us / 1000L
        tick()
    }

    override fun monotonicMicros(): Long = micros

    override fun epochMillis(): Long = cfg.epochStartMs + micros / 1000L

    override fun sleepMillis(ms: Long) {
        micros += ms * 1000L
        heatMs = maxOf(0L, heatMs - ms * cfg.coolMsPerIdleSecond / 1000L)
        tick()
    }

    override val probes: ProbeSource = object : ProbeSource {
        override fun device(): DeviceInfo = cfg.device

        override fun thermal(): ThermalReading {
            tick()
            val s = step()
            return ThermalReading(!thermalLost, forcedCode ?: s.code, s.headroomPermille, batteryTemp)
        }

        override fun power(): PowerReading {
            tick()
            return PowerReading(if (chargerOn) "ac" else "battery", cfg.power.levelPermille, cfg.power.hasBattery)
        }

        override fun memory(): MemoryReading = cfg.memory.copy(footprintBytes = loadedTier?.let { cfg.tiers.getValue(it).peakFootprintBytes } ?: cfg.memory.footprintBytes)

        override fun presence(): Presence {
            tick()
            return cfg.presence.copy(foreground = foreground)
        }

        override fun gpuBusyByOthersPermille(): Int? {
            tick()
            return gpuBusy
        }

        override fun contentionSpinMicros(): Long {
            val v = cfg.spins.getOrNull(spinCalls) ?: 200_000L
            spinCalls++
            busy(v)
            return v
        }

        override fun bestSpinMicros(): Long? = cfg.bestSpinMicros

        override fun memoryPressure(): Boolean {
            tick()
            return memPressure
        }

        override fun freeStorageBytes(): Long = cfg.freeStorageBytes
    }

    override val models: BenchModelStore = object : BenchModelStore {
        override fun verified(pin: TierPin): VerifiedModelFile? =
            if (cfg.modelsPresent == null || pin.tier in cfg.modelsPresent) VerifiedModelFile(pin.tier, pin.sha256!!, pin.bytes!!) else null

        override fun evictCache(pin: TierPin): String = "best-effort"

        override fun numericsReference(engineCommit: String, pin: TierPin): Long? = cfg.numericsRefs[pin.tier]
    }

    override val engine: BenchEngine = object : BenchEngine {
        override fun info(): BEngine = cfg.harness.engine

        override fun load(file: VerifiedModelFile, nCtx: Int): BenchModel {
            if (file.tier in cfg.engineOomTiers) throw EngineOom("scripted out of memory for ${file.tier}")
            val sp = cfg.tiers.getValue(file.tier)
            engineCalls += "load ${file.tier}"
            val first = openLoadCount++ == 0 || lastLoadTier != file.tier
            lastLoadTier = file.tier
            busy(jitter(if (first) sp.loadColdMicros else sp.loadWarmMicros))
            openModels++
            loadedTier = file.tier
            return FakeModel(file.tier, sp)
        }
    }

    private var loadedTier: String? = null
    private var openLoadCount = 0
    private var lastLoadTier: String? = null

    override val coexistence: Coexistence = object : Coexistence {
        override val isDaemon: Boolean = cfg.daemon

        override fun armBenchmarking() {
            armed = true
        }

        override fun disarm() {
            armed = false
        }

        override fun requestPending(): Boolean {
            tick()
            return pending
        }

        override fun requestServed() {
            if (pending) served++
            pending = false
        }

        override fun inFlightRequests(): Int = if (pending) 1 else 0
    }

    override val progress: BenchProgressUi = object : BenchProgressUi {
        override fun acquireWakeLock() {
            wakeLocksHeld++
        }

        override fun releaseWakeLock() {
            if (wakeLocksHeld > 0) wakeLocksHeld--
        }

        override fun dimBrightness() {
            brightnessDimmed = true
        }

        override fun restoreBrightness() {
            brightnessDimmed = false
        }

        override fun notice(text: String) {
            notices += text
        }
    }

    override fun harness(): HarnessInfo = cfg.harness

    private inner class FakeModel(val tier: String, val sp: TierSpeed) : BenchModel {
        private var kv = 0
        private var closed = false
        override val kvBytesPerToken: Long = sp.kvBytesPerToken

        override fun tokenize(utf8: ByteArray): IntArray {
            busy(jitter(sp.tokenizeUs))
            val text = String(utf8, Charsets.US_ASCII)
            val n = text.substringAfterLast('/').toIntOrNull() ?: PinnedTexts.NUMERICS_TOKENS
            return IntArray(n)
        }

        override fun prefill(tokens: IntArray, seq: Int): Int {
            busy(jitter(tokens.size * sp.prefillUsPerToken))
            kv += tokens.size
            return 1
        }

        override fun decodeGreedy(seqs: IntArray): IntArray {
            val slow = step().slowdownPermille
            val depth = if (kv >= 2048) 1000L + sp.depthSlowdownPermille else 1000L
            busy(jitter(sp.decodeUsPerToken * slow / 1000L * depth / 1000L))
            kv += 1
            return IntArray(seqs.size) { 1 }
        }

        override fun truncateKv(seq: Int, keepTokens: Int) {
            kv = minOf(kv, keepTokens)
        }

        override fun clearKv() {
            kv = 0
        }

        override fun nllMicroNats(tokens: IntArray): Long {
            busy(jitter(tokens.size * sp.prefillUsPerToken))
            return sp.nllMilliNatsPerToken * 1000L * tokens.size
        }

        override fun cancel() {
            engineCalls += "cancel $tier"
        }

        override fun close() {
            if (!closed) {
                closed = true
                openModels--
                if (openModels == 0) loadedTier = null
            }
        }
    }
}
