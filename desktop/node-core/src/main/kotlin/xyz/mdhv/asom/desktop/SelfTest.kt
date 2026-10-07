package xyz.mdhv.asom.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import xyz.mdhv.asom.contract.Egress
import xyz.mdhv.asom.contract.RouteRecord
import xyz.mdhv.asom.desktop.ledger.JsonlLedgerSink

/**
 * `asom-node --mode=selftest`. Reports each check as `ok`, `not-yet-implemented (<track>)` or `FAIL`. A member the host
 * declares as not yet implemented must actually throw [NotYetImplementedException] when called; a declaration that is
 * not true is a FAIL, so the report cannot flatter the host. Nothing is bound and no directory outside a deleted temp
 * dir is created. Evidence label: LAB when run in CI or a container, never device evidence.
 */
class SelfTest(private val platform: DesktopPlatform, private val env: NodeEnv, private val mode: HostMode = HostMode.FOREGROUND) {
    private enum class Status(val tag: String) { OK("ok"), NYI("not-yet-implemented"), FAIL("FAIL") }
    private class Result(val name: String, val status: Status, val detail: String)

    fun run(): Int {
        val results = ArrayList<Result>()
        fun check(name: String, body: () -> String) {
            results += try {
                Result(name, Status.OK, body())
            } catch (e: HostRefusedException) {
                Result(name, Status.FAIL, "refused: ${e.message}")
            } catch (e: Throwable) {
                Result(name, Status.FAIL, "${e::class.simpleName}: ${e.message}")
            }
        }

        check("host-identity") { require(platform.id.isNotBlank()); platform.id }
        var paths: NodePaths? = null
        check("paths") {
            val p = platform.paths(mode)
            paths = p
            "state=${p.stateDir} ledger=${p.ledgerDir} socket=${p.controlSocket} (resolved only; nothing created)"
        }
        check("listener-gate") {
            val d = platform.listenerGate().decision()
            "$d (nothing listens in this wave regardless)"
        }
        check("power-probe") {
            val r = platform.power().read()
            "source=${r.source.wire} charging=${r.charging} battery=${r.batteryBand?.wire ?: "none"}"
        }
        check("thermal-probe") {
            val r = platform.thermal().read()
            "band=${r.band} sensors=${r.watchedSensors.size} hold-threshold=${r.holdThresholdMilliC ?: "unknown"}"
        }
        check("presence-probe") {
            val s = platform.presence().sample()
            "cpuOtherPermille=${s.cpuOtherPermille ?: "n/a (first sample)"}"
        }
        check("gpu-contention") {
            if (platform.gpuContention() == null) "rule off: no attributable GPU counter (asom doctor says so)" else "present"
        }
        check("engine") {
            val e = xyz.mdhv.asom.desktop.engine.EngineWiring()
            require(!e.engine.hasLocalEngine)
            "NoopEngine (hasLocalEngine=false; local-only answers 501 LOCAL_ENGINE_ABSENT)"
        }
        check("config-defaults") {
            val c = NodeConfig.parse("{}")
            require(!c.lending) { "lending must default to OFF" }
            require(c.presenceHoldDownMs >= NodeConfig.MIN_HOLD_DOWN_MS)
            "lending OFF by default; hold-down ${c.presenceHoldDownMs} ms"
        }
        check("provider-fsm") {
            val rt = NodeRuntime(platform, mode)
            require(rt.fsm.state.name == "OFF") { "a fresh node must be OFF" }
            "fresh node FSM = OFF"
        }
        check("ledger-roundtrip") { ledgerRoundtrip() }

        val nyi = ArrayList<NotYetImplementedFeature>()
        (platform as? PartiallyImplemented)?.let { nyi += it.notYetImplemented }
        paths?.let { p ->
            runCatching { platform.nikStore(p) }.getOrNull()?.let { (it as? PartiallyImplemented)?.let { x -> nyi += x.notYetImplemented } }
            runCatching { platform.controlSocket(p) }.getOrNull()?.let { (it as? PartiallyImplemented)?.let { x -> nyi += x.notYetImplemented } }
        }
        runCatching { platform.power() }.getOrNull()?.let { (it as? PartiallyImplemented)?.let { x -> nyi += x.notYetImplemented } }
        for (f in nyi) {
            results += try {
                f.probe()
                Result(f.feature, Status.FAIL, "declared not yet implemented (${f.track}) but the call succeeded")
            } catch (_: NotYetImplementedException) {
                Result(f.feature, Status.NYI, f.track)
            } catch (e: Throwable) {
                Result(f.feature, Status.FAIL, "declared not yet implemented (${f.track}) but threw ${e::class.simpleName}")
            }
        }

        env.out.println("asom-node selftest (host ${platform.id}, mode ${mode.cliName}) ${NodeVersion.BANNER}")
        for (r in results) env.out.println("  [${r.status.tag}] ${r.name}: ${r.detail}")
        val ok = results.count { it.status == Status.OK }
        val pending = results.count { it.status == Status.NYI }
        val failed = results.count { it.status == Status.FAIL }
        env.out.println("selftest: $ok ok, $pending not-yet-implemented, $failed failed")
        return if (failed == 0) ExitCodes.OK else ExitCodes.ERROR
    }

    private fun ledgerRoundtrip(): String {
        val dir: Path = Files.createTempDirectory("asom-selftest-")
        try {
            val file = dir.resolve("ledger").resolve("ledger.jsonl")
            val row = RouteRecord(
                ts = 0, callerPkg = "selftest", requestedModel = "selftest", egress = Egress.LOCAL, latencyMs = 0, status = 200,
            )
            JsonlLedgerSink.open(file).use { it.appendLine(JsonlLedgerSink.JSON.encodeToString(RouteRecord.serializer(), row)) }
            val lines = Files.readAllLines(file, Charsets.UTF_8)
            require(lines.size == 1) { "expected one row, found ${lines.size}" }
            val back = JsonlLedgerSink.JSON.decodeFromString(RouteRecord.serializer(), lines[0])
            require(back == row) { "row did not round-trip" }
            return "one row appended, forced and read back intact (temp dir removed)"
        } finally {
            Files.walk(dir).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }
}
