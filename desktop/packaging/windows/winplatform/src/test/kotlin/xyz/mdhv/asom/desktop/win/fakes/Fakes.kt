package xyz.mdhv.asom.desktop.win.fakes

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.net.InetAddress
import java.nio.file.Path
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import xyz.mdhv.asom.desktop.MonotonicClock
import xyz.mdhv.asom.desktop.NodeEnv
import xyz.mdhv.asom.desktop.win.WinEnv
import xyz.mdhv.asom.desktop.win.WinNative
import xyz.mdhv.asom.desktop.win.acl.Ace
import xyz.mdhv.asom.desktop.win.acl.AclSnapshot
import xyz.mdhv.asom.desktop.win.acl.SidResolver
import xyz.mdhv.asom.desktop.win.acl.WinAcl
import xyz.mdhv.asom.desktop.win.api.CngKeyHandle
import xyz.mdhv.asom.desktop.win.api.CngPort
import xyz.mdhv.asom.desktop.win.api.CngProvider
import xyz.mdhv.asom.desktop.win.api.CounterValue
import xyz.mdhv.asom.desktop.win.api.CpuLoadSource
import xyz.mdhv.asom.desktop.win.api.DpapiPort
import xyz.mdhv.asom.desktop.win.api.InterfaceLister
import xyz.mdhv.asom.desktop.win.api.InterfaceSnapshot
import xyz.mdhv.asom.desktop.win.api.KeyScope
import xyz.mdhv.asom.desktop.win.api.MutexPort
import xyz.mdhv.asom.desktop.win.api.MutexResult
import xyz.mdhv.asom.desktop.win.api.NotificationState
import xyz.mdhv.asom.desktop.win.api.PdhApi
import xyz.mdhv.asom.desktop.win.api.PdhCounterSet
import xyz.mdhv.asom.desktop.win.api.PowerRequestApi
import xyz.mdhv.asom.desktop.win.api.PowerRequestHandle
import xyz.mdhv.asom.desktop.win.api.ProcessRunner
import xyz.mdhv.asom.desktop.win.api.RawPowerStatus
import xyz.mdhv.asom.desktop.win.api.RunResult
import xyz.mdhv.asom.desktop.win.api.SessionApi
import xyz.mdhv.asom.desktop.win.api.SuspendNotificationApi
import xyz.mdhv.asom.desktop.win.api.SuspendSignal
import xyz.mdhv.asom.desktop.win.api.SystemPowerStatusSource
import xyz.mdhv.asom.desktop.win.api.TextFiles
import xyz.mdhv.asom.desktop.win.api.UserInputApi
import xyz.mdhv.asom.desktop.win.api.WinApiException
import xyz.mdhv.asom.desktop.win.api.WtsSession

// ---- shared ---------------------------------------------------------------------------------------------------------

class FakeClock(var now: Long = 1_000_000L) : MonotonicClock {
    override fun nowMs(): Long = now
}

class Captured {
    val out = ByteArrayOutputStream()
    val err = ByteArrayOutputStream()
    val outText: String get() = out.toString(Charsets.UTF_8)
    val errText: String get() = err.toString(Charsets.UTF_8)
    fun env(userName: String = "alice", vars: Map<String, String> = emptyMap()) =
        NodeEnv(userName, vars, PrintStream(out, true, Charsets.UTF_8), PrintStream(err, true, Charsets.UTF_8))
}

/** Report lines go to stdout and to `build/reports/desktop/report.txt`. */
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

// ---- CNG ------------------------------------------------------------------------------------------------------------

/**
 * A CNG stand-in backed by the JCA, so the tests exercise the real ES256 maths: keys are real P-256 keys, signatures are
 * really made and really verified. Faults are switches: [providerMissing], [failCreate], [badSignatureLength],
 * [corruptSignature], [badBlob].
 */
class FakeCng : CngPort {
    class Stored(val pair: KeyPair, val sddl: String?, val scope: KeyScope)

    val keys = ConcurrentHashMap<Pair<CngProvider, String>, Stored>()
    val created = ArrayList<String>()
    val deleted = ArrayList<String>()
    var providerMissing = emptySet<CngProvider>()

    /** The provider exists but cannot be opened right now (TBS stopped, TPM not ready): open and create throw, never "no key". */
    var providerUnavailable = emptySet<CngProvider>()
    var failCreate = emptySet<CngProvider>()
    var badSignatureLength = emptySet<CngProvider>()
    var corruptSignature = emptySet<CngProvider>()
    var badBlob = emptySet<CngProvider>()
    var opened = 0

    override fun createEcdsaP256(provider: CngProvider, name: String, scope: KeyScope, sddl: String?): CngKeyHandle {
        if (provider in providerMissing) throw WinApiException("provider \"${provider.providerName}\" is not available on this machine")
        if (provider in providerUnavailable) throw WinApiException("NCryptOpenStorageProvider failed (0x80090030)", 0x80090030.toInt())
        if (provider in failCreate) throw WinApiException("NCryptCreatePersistedKey failed (0x80090029)", 0x80090029.toInt())
        check(keys[provider to name] == null) { "key exists" }
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        keys[provider to name] = Stored(pair, sddl, scope)
        created += "${provider.name}/$name/${scope.name}"
        return Handle(provider, name, pair)
    }

    override fun open(provider: CngProvider, name: String, scope: KeyScope): CngKeyHandle? {
        if (provider in providerMissing) return null
        if (provider in providerUnavailable) throw WinApiException("NCryptOpenStorageProvider failed (0x80090030)", 0x80090030.toInt())
        opened++
        val s = keys[provider to name] ?: return null
        return Handle(provider, name, s.pair)
    }

    private inner class Handle(val provider: CngProvider, val name: String, val pair: KeyPair) : CngKeyHandle {
        override fun signHash(digest32: ByteArray): ByteArray {
            // NCryptSignHash signs the digest itself ("NONEwithECDSA" over the 32-byte hash), in the raw r||s form.
            val s = Signature.getInstance("NONEwithECDSAinP1363Format")
            s.initSign(pair.private)
            s.update(digest32)
            var sig = s.sign()
            if (provider in badSignatureLength) sig = sig.copyOf(sig.size - 1)
            if (provider in corruptSignature) sig = sig.copyOf().also { it[10] = (it[10].toInt() xor 0x55).toByte() }
            return sig
        }

        override fun exportPublicBlob(): ByteArray {
            val pub = pair.public as ECPublicKey
            fun fixed(n: java.math.BigInteger): ByteArray = n.toByteArray().let { b ->
                val t = if (b.size > 32) b.copyOfRange(b.size - 32, b.size) else b
                ByteArray(32 - t.size) + t
            }
            val magic = if (provider in badBlob) 0x314B4345 else 0x31534345
            val head = byteArrayOf(magic.toByte(), (magic shr 8).toByte(), (magic shr 16).toByte(), (magic shr 24).toByte(), 32, 0, 0, 0)
            return head + fixed(pub.w.affineX) + fixed(pub.w.affineY)
        }

        override fun delete() {
            keys.remove(provider to name)
            deleted += "${provider.name}/$name"
        }

        override fun close() {}
    }
}

/** Reversible and not the identity: the wrapped bytes differ from the plain ones and carry a marker. */
class FakeDpapi : DpapiPort {
    var protectCalls = 0
    override fun protect(plain: ByteArray): ByteArray {
        protectCalls++
        return "DPAPI!".toByteArray() + plain.map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
    }

    override fun unprotect(wrapped: ByteArray): ByteArray {
        require(wrapped.copyOfRange(0, 6).contentEquals("DPAPI!".toByteArray())) { "not a DPAPI blob" }
        return wrapped.copyOfRange(6, wrapped.size).map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
    }
}

// ---- ACL ------------------------------------------------------------------------------------------------------------

class FakeAcl(var ownerSid: String = "S-1-5-21-1-2-3-1001") : WinAcl {
    val store = HashMap<Path, AclSnapshot>()
    val replaced = ArrayList<Path>()

    /** Extra ACEs every snapshot carries: what a directory inherited from its parent (for example Users from %ProgramData%). */
    var inherited: List<Ace> = emptyList()

    /** Paths whose snapshot is what [replace] stored, plus [inherited] (false: exactly what was stored). */
    var mergeInherited = false

    override fun snapshot(path: Path): AclSnapshot =
        store[path]?.let { if (mergeInherited) it.copy(aces = it.aces + inherited) else it } ?: AclSnapshot(ownerSid, inherited)

    override fun replace(path: Path, aces: List<Ace>) {
        replaced.add(path)
        store[path] = AclSnapshot(ownerSid, aces)
    }
}

class FakeSids : SidResolver {
    override fun sidOf(accountName: String): String? = null
    override fun accountNameOf(sid: String): String? = null
}

// ---- power ----------------------------------------------------------------------------------------------------------

class FakePowerStatus(var raw: RawPowerStatus? = DESKTOP_AC) : SystemPowerStatusSource {
    override fun read(): RawPowerStatus? = raw

    companion object {
        val DESKTOP_AC = RawPowerStatus(acLineStatus = 1, batteryFlag = 128, batteryLifePercent = 255, systemStatusFlag = 0)
        val LAPTOP_AC = RawPowerStatus(1, 9, 87, 0)
        val LAPTOP_BATTERY = RawPowerStatus(0, 1, 60, 0)
    }
}

class FakePowerRequests : PowerRequestApi {
    class Req(val reason: String, private val failOnSet: () -> Boolean) : PowerRequestHandle {
        var required = false
        var closed = false
        override fun setSystemRequired() {
            if (failOnSet()) throw WinApiException("PowerSetRequest failed")
            required = true
        }
        override fun clearSystemRequired() { required = false }
        override fun close() { closed = true }
    }

    val requests = ArrayList<Req>()
    var refuse: String? = null
    var failOnSet = false

    override fun create(reason: String): PowerRequestHandle {
        refuse?.let { throw WinApiException(it) }
        return Req(reason) { failOnSet }.also { requests += it }
    }
}

class FakeSuspendApi : SuspendNotificationApi {
    private var listener: ((SuspendSignal) -> Unit)? = null
    var registrations = 0
    var unregistrations = 0
    val registered: Boolean get() = listener != null

    override fun register(onSignal: (SuspendSignal) -> Unit): AutoCloseable {
        registrations++
        listener = onSignal
        return AutoCloseable { unregistrations++; listener = null }
    }

    /** Fires even after unregistration, as an OS callback already in flight could. */
    var lastCallback: ((SuspendSignal) -> Unit)? = null

    fun fire(s: SuspendSignal) { (listener ?: lastCallback)?.invoke(s) }
    fun keepCallback() { lastCallback = listener }
}

// ---- presence -------------------------------------------------------------------------------------------------------

class FakeUserInput(var idle: Long? = 0, var state: NotificationState? = NotificationState.ACCEPTS_NOTIFICATIONS) : UserInputApi {
    override fun idleMs(): Long? = idle
    override fun notificationState(): NotificationState? = state
}

class FakeSessions(var console: WtsSession? = null, var current: WtsSession? = null, var throws: Boolean = false) : SessionApi {
    override fun consoleSession(): WtsSession? { if (throws) error("boom"); return console }
    override fun currentSession(): WtsSession? { if (throws) error("boom"); return current }
}

class FakeCpu(var system: Double? = 0.1, var process: Double? = 0.05) : CpuLoadSource {
    override fun systemLoad(): Double? = system
    override fun processLoad(): Double? = process
}

/** A scripted PDH: each call to `collect` returns the next entry (the last one repeats). */
class FakePdh(private val scripts: Map<String, List<List<CounterValue>?>> = emptyMap()) : PdhApi {
    val opened = ArrayList<String>()
    override fun open(englishCounterPath: String): PdhCounterSet? {
        opened += englishCounterPath
        val script = scripts[englishCounterPath] ?: return null
        return object : PdhCounterSet {
            var i = 0
            override fun collect(): List<CounterValue>? = script[minOf(i++, script.size - 1)]
            override fun close() {}
        }
    }
}

// ---- identity, exec, network, files ---------------------------------------------------------------------------------

class FakeMutex : MutexPort {
    val held = HashSet<String>()
    var result: MutexResult? = null

    override fun tryAcquire(name: String): MutexResult {
        result?.let { return it }
        if (!held.add(name)) return MutexResult.HeldByOther
        return MutexResult.Acquired(AutoCloseable { held.remove(name) })
    }
}

class FakeRunner(var output: String = "", var exitCode: Int = 0, var timedOut: Boolean = false, var throws: Boolean = false) : ProcessRunner {
    val calls = ArrayList<Pair<String, List<String>>>()
    override fun run(executable: String, args: List<String>, timeoutMs: Long): RunResult {
        calls += executable to args
        if (throws) throw java.io.IOException("cannot start")
        return RunResult(exitCode, output.toByteArray(Charsets.ISO_8859_1), timedOut)
    }
}

class FakeInterfaces(var nics: List<InterfaceSnapshot> = emptyList(), var throws: Boolean = false) : InterfaceLister {
    var reads = 0
    override fun list(): List<InterfaceSnapshot> {
        reads++
        if (throws) throw java.io.IOException("no interfaces")
        return nics
    }
}

class FakeFiles(val files: MutableMap<String, String> = HashMap()) : TextFiles {
    override fun read(path: Path): String? = files[path.toString()]
}

fun addr(text: String): InetAddress = InetAddress.getByName(text)

fun nic(alias: String, index: Int, vararg addresses: String, up: Boolean = true) =
    InterfaceSnapshot(alias, index, up, addresses.map { addr(it) })

/** A fully faked native bundle. Everything can be replaced by name. */
class FakeNative(
    val cng: FakeCng = FakeCng(),
    val dpapi: FakeDpapi = FakeDpapi(),
    val acl: FakeAcl = FakeAcl(),
    val powerStatus: FakePowerStatus = FakePowerStatus(),
    val powerRequests: FakePowerRequests = FakePowerRequests(),
    val suspend: FakeSuspendApi = FakeSuspendApi(),
    val userInput: FakeUserInput = FakeUserInput(),
    val sessions: FakeSessions = FakeSessions(),
    val pdh: FakePdh = FakePdh(),
    val cpu: FakeCpu = FakeCpu(),
    val mutex: FakeMutex = FakeMutex(),
    val runner: FakeRunner = FakeRunner(),
    val interfaces: FakeInterfaces = FakeInterfaces(),
    val files: FakeFiles = FakeFiles(),
) {
    fun bundle() = WinNative(cng, dpapi, FakeSids(), acl, powerStatus, powerRequests, suspend, userInput, sessions, pdh, cpu, mutex, runner, interfaces, files)
}

const val OWNER_SID = "S-1-5-21-1111111111-2222222222-3333333333-1001"

fun fakeEnv(
    userName: String = "alice",
    localAppData: String = "C:\\Users\\alice\\AppData\\Local",
    programData: String = "C:\\ProgramData",
    extra: Map<String, String> = emptyMap(),
    sid: String? = OWNER_SID,
): WinEnv = WinEnv(
    userName,
    mapOf("LOCALAPPDATA" to localAppData, "ProgramData" to programData, "ProgramFiles" to "C:\\Program Files", "SystemRoot" to "C:\\Windows") + extra,
) { sid }

/** `Structure.fieldOffset` is protected; the layout cross-checks read it reflectively. */
fun com.sun.jna.Structure.offsetOf(field: String): Int =
    com.sun.jna.Structure::class.java.getDeclaredMethod("fieldOffset", String::class.java).also { it.isAccessible = true }.invoke(this, field) as Int
