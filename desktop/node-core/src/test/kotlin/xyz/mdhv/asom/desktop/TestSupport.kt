package xyz.mdhv.asom.desktop

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Report lines go to stdout and to `build/reports/desktop/report.txt`, which `desktopTest` prints at the end. */
object Report {
    @Synchronized
    fun line(text: String) {
        println(text)
        val f = System.getProperty("asom.report")?.let { File(it) } ?: return
        f.parentFile.mkdirs()
        f.appendText(text + "\n", Charsets.UTF_8)
    }
}

/** Non-vacuity: a named law counts the cases that exercised it, and [assertAllExercised] fails if any count is zero. */
class LawCounter(private val laws: Collection<String>) {
    private val counts = ConcurrentHashMap<String, AtomicLong>().also { m -> laws.forEach { m[it] = AtomicLong() } }

    fun hit(law: String) {
        counts.getValue(law).incrementAndGet()
    }

    fun count(law: String): Long = counts.getValue(law).get()

    fun assertAllExercised(family: String) {
        for (l in laws) Report.line("law $family/$l: cases exercised: ${count(l)}")
        val zero = laws.filter { count(it) == 0L }
        check(zero.isEmpty()) { "vacuous laws in $family (exercised zero cases): $zero" }
    }
}

class Captured(val out: ByteArrayOutputStream = ByteArrayOutputStream(), val err: ByteArrayOutputStream = ByteArrayOutputStream()) {
    val outText: String get() = out.toString(Charsets.UTF_8)
    val errText: String get() = err.toString(Charsets.UTF_8)
    fun env(userName: String = "alice", vars: Map<String, String> = emptyMap()) =
        NodeEnv(userName, vars, PrintStream(out, true, Charsets.UTF_8), PrintStream(err, true, Charsets.UTF_8))
}

class FakeClock(var now: Long = 1_000_000L) : MonotonicClock {
    override fun nowMs(): Long = now
}

class FakePower(var reading: PowerReading = ac()) : PowerPort {
    override fun read(): PowerReading = reading
    override fun hold(kind: LockKind): KeepAwakeHold = throw NotYetImplementedException("keep-awake", "DL2")
    override fun onSleepEvents(listener: (SleepEvent) -> Unit): AutoCloseable = throw NotYetImplementedException("sleep", "DL2")

    companion object {
        fun ac() = PowerReading(PowerSource.AC, charging = false, hasBattery = false, batteryPercent = null, batteryBand = null, saver = null)
        fun battery(percent: Int = 50) = PowerReading(PowerSource.BATTERY, false, true, percent, BatteryBand.ofPercent(percent), null)
    }
}

class FakePlatform(
    override val id: String = "fake",
    val power: FakePower = FakePower(),
    var thermal: ThermalReading = ThermalReading(0, 40_000, 80_000, listOf("fake sensor")),
    var cpuOther: Int? = 0,
    val refuse: String? = null,
    private val extraNyi: List<NotYetImplementedFeature> = emptyList(),
) : DesktopPlatform, PartiallyImplemented {
    override fun paths(mode: HostMode): NodePaths {
        if (refuse != null) throw HostRefusedException(refuse)
        val base = Path.of("/fake/${mode.cliName}")
        return NodePaths(mode, base.resolve("state"), base.resolve("data"), base.resolve("state/ledger"), base.resolve("data/identity"), base.resolve("run"), base.resolve("run/ctl.sock"))
    }

    override fun nikStore(paths: NodePaths): NikStore = NotYetImplementedNikStore(KeyStorage.FILE)
    override fun power(): PowerPort = power
    override fun presence(): PresencePort = object : PresencePort {
        override fun sample() = PresenceSample(cpuOther, null)
    }
    override fun gpuContention(): GpuContentionPort? = null
    override fun thermal(): ThermalPort = object : ThermalPort {
        override fun read() = thermal
    }
    override fun listenerGate(): ListenerGate = OpenListenerGate
    override fun controlSocket(paths: NodePaths): ControlSocketServer = NotYetImplementedControlSocket(paths.controlSocket)
    override val notYetImplemented: List<NotYetImplementedFeature> get() = extraNyi
}
