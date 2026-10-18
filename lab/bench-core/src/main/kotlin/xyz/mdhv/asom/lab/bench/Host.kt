package xyz.mdhv.asom.lab.bench

/** The host SPI of benchmark.md 2.3: everything a shell supplies. The core never touches a clock, a probe or an engine except through it. */
data class DeviceInfo(
    val platform: String,
    val form: String,
    val maker: String,
    val model: String,
    val soc: String,
    val osVersion: String,
    val osBuild: String?,
    val gpu: String?,
    val gpuDriver: String?,
    val memTotalBytes: Long,
    val unifiedMemory: Boolean,
    val virtualized: Boolean,
)

data class HarnessInfo(
    val shell: String,
    val coreImpl: String,
    val coreVersion: String,
    val confVersion: String,
    val timingSource: String,
    val engine: BEngine,
)

/** [code] is the normalised thermal code 0..4 (benchmark.md 6.1); [available] is false when no live thermal source exists. */
data class ThermalReading(val available: Boolean, val code: Int, val headroom10sPermille: Int?, val batteryTempDeciC: Int?)

data class PowerReading(val source: String, val levelPermille: Int?, val hasBattery: Boolean)

data class MemoryReading(
    val availBytes: Long,
    val processLimitBytes: Long?,
    val gpuWorkingSetBytes: Long?,
    val footprintBytes: Long,
    val swapUsedBytes: Long?,
    val limitSource: String = "unknown",
)

data class Presence(val foreground: Boolean, val screenOn: Boolean, val batterySaver: Boolean, val lowPowerMode: Boolean)

interface ProbeSource {
    fun device(): DeviceInfo
    fun thermal(): ThermalReading
    fun power(): PowerReading
    fun memory(): MemoryReading
    fun presence(): Presence
    fun gpuBusyByOthersPermille(): Int?

    /** A fixed single-thread integer loop of about 200 ms; returns its duration in microseconds (benchmark.md 8). */
    fun contentionSpinMicros(): Long

    /** The lowest spin this device fingerprint has ever recorded, or null on the first run (then the run's own baseline is used). */
    fun bestSpinMicros(): Long?
    fun memoryPressure(): Boolean
    fun freeStorageBytes(): Long
}

data class VerifiedModelFile(val tier: String, val sha256: String, val bytes: Long)

interface BenchModelStore {
    /** The file whose sha256 matched the compiled-in pin, or null. */
    fun verified(pin: TierPin): VerifiedModelFile?

    /** Best-effort cache eviction before a cold load; returns `evicted`, `best-effort` or `unknown`. */
    fun evictCache(pin: TierPin): String

    /** The owner-computed CPU reference (milli-nats per token) for this file at this engine commit, or null. */
    fun numericsReference(engineCommit: String, pin: TierPin): Long?
}

class EngineOom(message: String) : Exception(message)

interface BenchModel : AutoCloseable {
    val kvBytesPerToken: Long
    fun tokenize(utf8: ByteArray): IntArray

    /** One batched decode at the current KV position; returns the greedy argmax of the last position. */
    fun prefill(tokens: IntArray, seq: Int = 0): Int
    fun decodeGreedy(seqs: IntArray): IntArray
    fun truncateKv(seq: Int, keepTokens: Int)
    fun clearKv()

    /** Teacher-forced sum of -ln p over the tokens, in micro-nats. */
    fun nllMicroNats(tokens: IntArray): Long
    fun cancel()
}

interface BenchEngine {
    fun info(): BEngine

    /** Out of memory is a typed failure, never a crash. */
    fun load(file: VerifiedModelFile, nCtx: Int): BenchModel
}

/** Daemon shells arm a benchmarking availability and yield to real requests within 1 s; a standalone shell uses [NoCoexistence]. */
interface Coexistence {
    val isDaemon: Boolean
    fun armBenchmarking()
    fun disarm()
    fun requestPending(): Boolean
    fun requestServed()
    fun inFlightRequests(): Int
}

object NoCoexistence : Coexistence {
    override val isDaemon: Boolean = false
    override fun armBenchmarking() {}
    override fun disarm() {}
    override fun requestPending(): Boolean = false
    override fun requestServed() {}
    override fun inFlightRequests(): Int = 0
}

/** Progress and abort notices. It never asks for consent (consent is [ConsentSheet]). Wake lock and brightness are resources every exit path must release. */
interface BenchProgressUi {
    fun acquireWakeLock()
    fun releaseWakeLock()
    fun dimBrightness()
    fun restoreBrightness()
    fun notice(text: String)
}

interface BenchHost {
    fun monotonicMicros(): Long
    fun epochMillis(): Long

    /** Lets the clock pass with no engine work (cool-down polls, the 30 s idle wait, inter-rep pauses). */
    fun sleepMillis(ms: Long)
    val probes: ProbeSource
    val engine: BenchEngine
    val models: BenchModelStore
    val coexistence: Coexistence
    val progress: BenchProgressUi
    fun harness(): HarnessInfo
}
