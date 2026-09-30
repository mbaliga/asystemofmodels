package xyz.mdhv.asom.lab.bench

/** Governor states (benchmark.md 11.4, with design 6.5 B9: an abort is followed by a FINALIZING of the partial results). */
enum class GState { IDLE, PREFLIGHT, AWAIT_CONSENT, PREPARING, COOLING, RUNNING, YIELDED, FINALIZING, ABORTING, DONE }

class IllegalTransition(val from: GState, val to: GState) : IllegalStateException("illegal governor transition $from -> $to")

/** Control flow inside a run: an abort or a yield is a signal, never a crash (the outcome is typed). */
sealed class GovSignal : RuntimeException(null, null, false, false) {
    class Abort(val reason: String) : GovSignal()
    class Yield : GovSignal()

    /** The sustain phase ends here, with its windows kept, and the run goes on to FINALIZING (a hard thermal ceiling, a soft ceiling held 120 s). */
    class SustainEnd(val endReason: String) : GovSignal()
}

enum class Phase { PREFLIGHT, TIER, SUSTAIN }

sealed interface Ceiling {
    data class Soft(val reason: String) : Ceiling
    data class Hard(val reason: String) : Ceiling
}

/** The runtime ceilings of benchmark.md 11.3, as a pure function of the readings. */
object Ceilings {
    const val ANDROID_SOFT_HEADROOM_PERMILLE = 950
    const val ANDROID_SOFT_BATTERY_DECIC = 420
    const val ANDROID_HARD_BATTERY_DECIC = 440
    const val LINUX_HARD_BATTERY_DECIC = 450
    const val LOW_BATTERY_PERMILLE = 200
    const val DECK_GPU_BUSY_PERMILLE = 200

    /** Returns the first ceiling that applies, hard before soft; null when none does. [gpuBusyHeldMs] is how long the Deck's other-process GPU load has stayed at or above the limit. */
    fun evaluate(platform: String, form: String, t: ThermalReading, p: PowerReading, presence: Presence, gpuBusyHeldMs: Long): Ceiling? {
        val batt = t.batteryTempDeciC
        val level = p.levelPermille
        when (platform) {
            "android" -> {
                if (t.code >= 3) return Ceiling.Hard("THERMAL_HARD")
                if (batt != null && batt >= ANDROID_HARD_BATTERY_DECIC) return Ceiling.Hard("BATTERY_TEMP")
                if (level != null && level < LOW_BATTERY_PERMILLE) return Ceiling.Hard("BATTERY_TEMP")
                val h = t.headroom10sPermille
                if ((h != null && h >= ANDROID_SOFT_HEADROOM_PERMILLE) || (batt != null && batt >= ANDROID_SOFT_BATTERY_DECIC)) return Ceiling.Soft("THERMAL_SOFT")
            }
            "ios", "ipados" -> {
                if (t.code >= 3 || presence.lowPowerMode || (level != null && level < LOW_BATTERY_PERMILLE)) return Ceiling.Hard("THERMAL_HARD")
            }
            "macos" -> {
                if (t.code >= 4) return Ceiling.Hard("THERMAL_HARD")
                if (form == "laptop" && p.source != "ac") return Ceiling.Hard("CHARGER_REMOVED")
                if (t.code >= 3) return Ceiling.Soft("THERMAL_SOFT")
            }
            else -> {
                if (t.code >= 4) return Ceiling.Hard("THERMAL_HARD")
                if (batt != null && batt >= LINUX_HARD_BATTERY_DECIC) return Ceiling.Hard("BATTERY_TEMP")
                if (form == "handheld" && gpuBusyHeldMs >= 10_000L) return Ceiling.Hard("DEVICE_BUSY")
                if (t.code >= 3) return Ceiling.Soft("THERMAL_SOFT")
            }
        }
        return null
    }
}

class PreflightReport(val reasons: List<PreflightReason>, val startClass: String, val tiers: List<TierPin>) {
    val ok: Boolean get() = reasons.isEmpty()
}

/** The start class of benchmark.md 6.1: COOL, WARM, or HOT (code 3 or more: the run refuses to start). */
object StartClass {
    fun of(t: ThermalReading, contentionPermille: Long): String {
        if (t.code >= 3) return "hot"
        val headroomOk = t.headroom10sPermille == null || t.headroom10sPermille <= 600
        val battOk = t.batteryTempDeciC == null || t.batteryTempDeciC <= 350
        return if (t.code == 0 && headroomOk && battOk && contentionPermille <= 100L) "cool" else "warm"
    }
}

object Preflight {
    private val batteryForms = setOf("phone", "tablet", "handheld", "laptop")
    const val CONTENTION_LIMIT_PERMILLE = 300L
    const val BATTERY_WARM_DECIC = 350
    const val STORAGE_EXTRA_BYTES = 1_000_000_000L

    /** The run list of benchmark.md 4.4: policy tiers (plus the opted-in ones) that fit, smallest file first. */
    fun tiersToRun(plan: RunPlan, set: BenchSetDef, form: String, optIn: List<String>, usable: Long): List<TierPin> {
        val policy = plan.tiers.getValue(form)
        val wanted = (policy.auto + policy.optIn.filter { it in optIn }).mapNotNull { set.pin(it) }
        var fit = wanted.filter { fits(it, usable) }
        if (fit.isEmpty() && policy.fallback != null) fit = listOfNotNull(set.pin(policy.fallback)).filter { fits(it, usable) }
        if (plan.plan == "quick" && fit.size > 1) fit = listOf(fit.maxBy { it.bytes!! })
        return fit.sortedBy { it.bytes }
    }

    fun fits(pin: TierPin, usable: Long): Boolean =
        Checked.add(Checked.add(pin.bytes!!, Checked.mul(pin.kvBytesPerToken!!, Derive.KV_CTX)), Derive.OVERHEAD_BYTES) <= usable

    fun usableBytes(mem: MemoryReading, form: String): Long {
        val limit = listOfNotNull(mem.availBytes, mem.processLimitBytes, mem.gpuWorkingSetBytes).min()
        return Checked.div(Checked.mul(limit, Derive.SAFETY_PERMILLE.getValue(form)), 1000L)
    }

    fun check(host: BenchHost, plan: RunPlan, set: BenchSetDef, optIn: List<String>, allowVirtual: Boolean, baselineContention: Long, harnessShell: String): PreflightReport {
        val p = host.probes
        val dev = p.device()
        val form = dev.form
        val reasons = mutableListOf<PreflightReason>()
        val thermal = p.thermal()
        val power = p.power()
        val presence = p.presence()
        val mem = p.memory()
        val usable = usableBytes(mem, form)
        val tiers = tiersToRun(plan, set, form, optIn, usable)
        val eng = host.engine
        val needsThermal = plan.plan == "standard" || plan.plan == "sustained" || plan.plan == "extended"

        if (runCatching { eng.info() }.isFailure) reasons += PreflightReason.NO_ENGINE
        if (tiers.any { host.models.verified(it) == null }) reasons += PreflightReason.MODELS_MISSING
        val missingBytes = tiers.filter { host.models.verified(it) == null }.sumOf { it.bytes!! }
        if (p.freeStorageBytes() < missingBytes + STORAGE_EXTRA_BYTES) reasons += PreflightReason.STORAGE
        if (needsThermal && !thermal.available) reasons += PreflightReason.NO_THERMAL_SIGNAL
        val startClass = StartClass.of(thermal, baselineContention)
        if (startClass == "hot") reasons += PreflightReason.TOO_WARM
        val batteryDevice = form in batteryForms && power.hasBattery
        if (batteryDevice) {
            if (presence.batterySaver || presence.lowPowerMode) reasons += PreflightReason.POWER_SAVER
            if (form in plan.chargerRequiredForms && power.source != "ac") reasons += PreflightReason.NEEDS_CHARGER
            val level = power.levelPermille
            if (level != null && level < plan.minBatteryPermille) reasons += PreflightReason.BATTERY_LOW
        }
        val bt = thermal.batteryTempDeciC
        if ((dev.platform == "android" || dev.platform == "linux") && bt != null && bt > BATTERY_WARM_DECIC) reasons += PreflightReason.BATTERY_WARM
        val gpu = p.gpuBusyByOthersPermille()
        if (baselineContention > CONTENTION_LIMIT_PERMILLE || (form == "handheld" && gpu != null && gpu >= Ceilings.DECK_GPU_BUSY_PERMILLE)) reasons += PreflightReason.DEVICE_BUSY
        if (host.coexistence.isDaemon && host.coexistence.inFlightRequests() > 0) reasons += PreflightReason.SERVING
        val mobileStandalone = (dev.form == "phone" || dev.form == "tablet") && harnessShell.endsWith("standalone")
        if (mobileStandalone && (!presence.foreground || !presence.screenOn)) reasons += PreflightReason.NOT_FOREGROUND
        val t0 = set.pin("T0")
        if (t0 != null && !fits(t0, usable)) reasons += PreflightReason.NOT_ENOUGH_MEMORY
        if (dev.virtualized && plan.plan != "ci" && !allowVirtual) reasons += PreflightReason.VIRTUALIZED
        return PreflightReport(reasons, startClass, tiers)
    }
}

/**
 * The governor state machine of benchmark.md 11.4. [to] refuses any edge that is not in the table, so a test that drives every
 * edge, and every non-edge, pins the machine. Ceilings, stop requests, wall caps and yields are all decided here in [check].
 */
class Governor(
    private val host: BenchHost,
    private val plan: RunPlan,
    private val trace: MutableList<String>,
    private val startMicros: () -> Long,
) {
    var state: GState = GState.IDLE
        private set
    var phaseName: String? = null
        private set

    @Volatile
    var stopRequested: Boolean = false

    var softSinceMicros: Long? = null
        private set
    private var gpuBusySinceMicros: Long? = null
    private var probeLostPolls = 0
    var yields: Int = 0
        private set

    companion object {
        val EDGES: Map<GState, Set<GState>> = mapOf(
            GState.IDLE to setOf(GState.PREFLIGHT),
            GState.PREFLIGHT to setOf(GState.IDLE, GState.AWAIT_CONSENT, GState.ABORTING),
            GState.AWAIT_CONSENT to setOf(GState.IDLE, GState.PREPARING, GState.ABORTING),
            GState.PREPARING to setOf(GState.COOLING, GState.ABORTING),
            GState.COOLING to setOf(GState.RUNNING, GState.FINALIZING, GState.ABORTING),
            GState.RUNNING to setOf(GState.COOLING, GState.FINALIZING, GState.YIELDED, GState.ABORTING),
            GState.YIELDED to setOf(GState.COOLING, GState.FINALIZING, GState.ABORTING),
            GState.ABORTING to setOf(GState.FINALIZING),
            GState.FINALIZING to setOf(GState.DONE),
            GState.DONE to emptySet(),
        )
        const val YIELD_LIMIT = 3
        const val SOFT_HOLD_MS = 120_000L
        const val PROBE_LOST_POLLS = 3
    }

    fun to(next: GState, phase: String? = null) {
        if (next !in EDGES.getValue(state)) throw IllegalTransition(state, next)
        trace += "STATE $state->$next" + (phase?.let { "($it)" } ?: "")
        state = next
        phaseName = phase
        if (next == GState.YIELDED) yields++
    }

    fun elapsedMs(): Long = (host.monotonicMicros() - startMicros()) / 1000L

    /** Raises [GovSignal.Abort], [GovSignal.Yield] or [GovSignal.SustainEnd] when a governor rule fires; otherwise returns. Called before every timed rep. */
    fun check(phase: Phase) {
        val dev = host.probes.device()
        val p = host.probes
        if (stopRequested) throw GovSignal.Abort("USER_STOP")
        val presence = p.presence()
        val mobileStandalone = (dev.form == "phone" || dev.form == "tablet") && host.harness().shell.endsWith("standalone")
        if (mobileStandalone && !presence.foreground) throw GovSignal.Abort("BACKGROUNDED")
        val power = p.power()
        if (dev.form in setOf("phone", "tablet", "handheld", "laptop") && dev.form in plan.chargerRequiredForms && power.hasBattery && power.source != "ac") {
            throw GovSignal.Abort("CHARGER_REMOVED")
        }
        plan.wallCapMs[dev.form]?.let { if (elapsedMs() >= it) throw GovSignal.Abort("WALL_CAP") }
        if (p.memoryPressure()) throw GovSignal.Abort("MEMORY_PRESSURE")
        val thermal = p.thermal()
        if (plan.plan != "quick" && plan.plan != "ci") {
            if (!thermal.available) {
                probeLostPolls++
                if (probeLostPolls >= PROBE_LOST_POLLS) throw GovSignal.Abort("PROBE_LOST")
            } else {
                probeLostPolls = 0
            }
        }
        val gpu = p.gpuBusyByOthersPermille()
        val now = host.monotonicMicros()
        if (gpu != null && gpu >= Ceilings.DECK_GPU_BUSY_PERMILLE) {
            if (gpuBusySinceMicros == null) gpuBusySinceMicros = now
        } else {
            gpuBusySinceMicros = null
        }
        val heldMs = gpuBusySinceMicros?.let { (now - it) / 1000L } ?: 0L
        when (val c = Ceilings.evaluate(dev.platform, dev.form, thermal, power, presence, heldMs)) {
            is Ceiling.Hard -> {
                if (phase == Phase.SUSTAIN && c.reason == "THERMAL_HARD") throw GovSignal.SustainEnd("THERMAL_HARD")
                throw GovSignal.Abort(c.reason)
            }
            is Ceiling.Soft -> {
                if (phase == Phase.SUSTAIN) {
                    if (softSinceMicros == null) softSinceMicros = now
                    if ((now - softSinceMicros!!) / 1000L >= SOFT_HOLD_MS) throw GovSignal.SustainEnd("THERMAL_SOFT")
                }
            }
            null -> if (phase == Phase.SUSTAIN) softSinceMicros = null
        }
        if (state == GState.RUNNING && host.coexistence.isDaemon && host.coexistence.requestPending()) throw GovSignal.Yield()
    }
}
