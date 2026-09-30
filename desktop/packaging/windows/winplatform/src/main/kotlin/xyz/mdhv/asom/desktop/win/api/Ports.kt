package xyz.mdhv.asom.desktop.win.api

import java.nio.file.Path

/*
 * The narrow interfaces behind which every Windows API call sits. The real implementations are JNA bindings (package
 * `win.jna`) that load their DLLs only on first use; the fakes in the tests implement the same interfaces, so the whole
 * module compiles and its pure tests run on any operating system. Nothing in this file touches JNA.
 */

/** A Windows API is not available here (not Windows, or the DLL or symbol is missing). Never thrown by a fake. */
class WinApiUnavailableException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** A Windows API call returned a failure status. [status] is the raw HRESULT, NTSTATUS or Win32 code, when there is one. */
class WinApiException(message: String, val status: Int? = null, cause: Throwable? = null) : RuntimeException(message, cause)

// ---- CNG (windows.md 5) ----------------------------------------------------------------------------------------------

enum class CngProvider(val providerName: String) {
    /** T2. Backed by the TPM; whether it can hold an ECDSA P-256 key on a given machine is assumption AW01 (spike S-W1). */
    PLATFORM("Microsoft Platform Crypto Provider"),

    /** T1. The key lives in the LSA key-isolation service; "non-exportable" is a policy flag [FW44]. */
    SOFTWARE("Microsoft Software Key Storage Provider"),
}

enum class KeyScope { USER, MACHINE }

interface CngKeyHandle : AutoCloseable {
    /** `NCryptSignHash` over a 32-byte SHA-256 digest; the result is the 64-byte r||s form (AW02, verified by JCA in the tests). */
    fun signHash(digest32: ByteArray): ByteArray

    /** `NCryptExportKey(BCRYPT_ECCPUBLIC_BLOB)`: magic, cbKey, X, Y. */
    fun exportPublicBlob(): ByteArray

    /** `NCryptDeleteKey`. The handle must not be used afterwards. */
    fun delete()
}

interface CngPort {
    /**
     * Creates a persisted, non-exportable, signing-only ECDSA P-256 key. [sddl], when not null, is the DACL to set on the
     * key before it is finalised (service mode, machine scope). An existing key of that name is an error, never overwritten.
     */
    fun createEcdsaP256(provider: CngProvider, name: String, scope: KeyScope, sddl: String? = null): CngKeyHandle

    /** Null when the provider or the key does not exist. */
    fun open(provider: CngProvider, name: String, scope: KeyScope): CngKeyHandle?
}

/** `CryptProtectData` / `CryptUnprotectData` with flags 0: user scope, never `CRYPTPROTECT_LOCAL_MACHINE` [FW14]. */
interface DpapiPort {
    fun protect(plain: ByteArray): ByteArray
    fun unprotect(wrapped: ByteArray): ByteArray
}

// ---- Power (windows.md 2.1, 3.4) --------------------------------------------------------------------------------------

/** The four bytes of `SYSTEM_POWER_STATUS` that matter [FW12]. */
data class RawPowerStatus(val acLineStatus: Int, val batteryFlag: Int, val batteryLifePercent: Int, val systemStatusFlag: Int)

interface SystemPowerStatusSource {
    /** Null when the call failed. */
    fun read(): RawPowerStatus?
}

interface PowerRequestHandle : AutoCloseable {
    /** `PowerSetRequest(PowerRequestSystemRequired)`. */
    fun setSystemRequired()

    /** `PowerClearRequest(PowerRequestSystemRequired)`. */
    fun clearSystemRequired()
}

interface PowerRequestApi {
    /** `PowerCreateRequest` with a simple reason string. Throws [WinApiException] if the OS refuses. */
    fun create(reason: String): PowerRequestHandle
}

enum class SuspendSignal { SUSPEND, RESUME_SUSPEND, RESUME_AUTOMATIC, DISPLAY_OFF, DISPLAY_ON, DISPLAY_DIMMED }

interface SuspendNotificationApi {
    /** Registers for suspend/resume and console-display-state callbacks; the handle unregisters. Callbacks arrive on an OS thread. */
    fun register(onSignal: (SuspendSignal) -> Unit): AutoCloseable
}

// ---- Presence (windows.md 2.1, 3.3) ----------------------------------------------------------------------------------

/** `SHQueryUserNotificationState`. */
enum class NotificationState {
    NOT_PRESENT, BUSY, RUNNING_D3D_FULL_SCREEN, PRESENTATION_MODE, ACCEPTS_NOTIFICATIONS, QUIET_TIME, APP;

    companion object {
        fun fromCode(code: Int): NotificationState? = when (code) {
            1 -> NOT_PRESENT
            2 -> BUSY
            3 -> RUNNING_D3D_FULL_SCREEN
            4 -> PRESENTATION_MODE
            5 -> ACCEPTS_NOTIFICATIONS
            6 -> QUIET_TIME
            7 -> APP
            else -> null
        }
    }
}

interface UserInputApi {
    /** Milliseconds since the last keyboard or mouse input in this session (`GetLastInputInfo`), or null if unreadable. */
    fun idleMs(): Long?

    fun notificationState(): NotificationState?
}

/** One session as `WTSQuerySessionInformation(WTSSessionInfoEx)` reports it. */
data class WtsSession(
    val sessionId: Int,
    val connectState: Int,
    val userName: String,
    /** The lock flag as the API reports it. Its polarity is disputed between Windows versions (AW07), so it is never used alone. */
    val lockFlagLocked: Boolean?,
    val idleMs: Long?,
)

interface SessionApi {
    /** The active console session, or null if there is none or it cannot be read. */
    fun consoleSession(): WtsSession?

    /** The session this process runs in, or null if it cannot be read. */
    fun currentSession(): WtsSession?
}

// ---- PDH counters (windows.md 2.1, 6) ---------------------------------------------------------------------------------

data class CounterValue(val instance: String, val value: Double)

interface PdhCounterSet : AutoCloseable {
    /**
     * Collects and formats every instance of the counter. Null when nothing valid came back, which includes the first
     * call of a rate counter (two collections are needed); a caller must never read null as zero.
     */
    fun collect(): List<CounterValue>?
}

interface PdhApi {
    /** Null when the counter object does not exist on this machine (`PDH_CSTATUS_NO_OBJECT` and similar). */
    fun open(englishCounterPath: String): PdhCounterSet?
}

/** The JDK's own CPU accounting (`com.sun.management`), which reads `GetSystemTimes` on Windows. Values are 0.0..1.0. */
interface CpuLoadSource {
    fun systemLoad(): Double?
    fun processLoad(): Double?
}

// ---- Single identity (windows.md 3.2) --------------------------------------------------------------------------------

sealed interface MutexResult {
    class Acquired(val handle: AutoCloseable) : MutexResult
    data object HeldByOther : MutexResult
    data object AccessDenied : MutexResult
    class Failed(val status: Int?) : MutexResult
}

interface MutexPort {
    fun tryAcquire(name: String): MutexResult
}

// ---- Firewall and network (windows.md 4) -----------------------------------------------------------------------------

data class RunResult(val exitCode: Int, val stdout: ByteArray, val timedOut: Boolean = false)

interface ProcessRunner {
    /** Runs one of the few read-only system tools the node is allowed to read from. Never a shell, never PowerShell. */
    fun run(executable: String, args: List<String>, timeoutMs: Long): RunResult
}

data class InterfaceSnapshot(
    val alias: String,
    val index: Int,
    val isUp: Boolean,
    val addresses: List<java.net.InetAddress>,
)

interface InterfaceLister {
    fun list(): List<InterfaceSnapshot>
}

// ---- Files the node reads that belong to other products --------------------------------------------------------------

interface TextFiles {
    /** UTF-8 (explicit; the default charset of JDK 17 on Windows is not UTF-8, AW20); null if the file does not exist or cannot be read. */
    fun read(path: Path): String?
}
