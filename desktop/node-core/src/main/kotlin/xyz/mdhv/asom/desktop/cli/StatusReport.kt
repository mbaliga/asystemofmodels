package xyz.mdhv.asom.desktop.cli

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import xyz.mdhv.asom.desktop.NodeRuntime
import xyz.mdhv.asom.desktop.NodeVersion
import xyz.mdhv.asom.desktop.PartiallyImplemented

/**
 * `asom status --json`. This wave there is no control socket, so the CLI cannot ask a running node: the report is a
 * LOCAL SNAPSHOT of what a freshly started node would be (`"source":"local-snapshot"`), never presented as the state of
 * a live process. The first four keys are the plan's exact shape (`host`, `fsm`, `listeners`, `locks`); the rest is
 * additive. Integers, strings, booleans and null only (C1).
 */
object StatusReport {
    fun build(rt: NodeRuntime): JsonObject {
        val paths = rt.platform.paths(rt.mode)
        val nik = rt.platform.nikStore(paths)
        val socket = rt.platform.controlSocket(paths)
        val power = runCatching { rt.platform.power().read() }.getOrNull()
        val thermal = runCatching { rt.platform.thermal().read() }.getOrNull()
        val gpuPort = rt.platform.gpuContention()

        val nyi = buildList {
            (rt.platform as? PartiallyImplemented)?.let { addAll(it.notYetImplemented) }
            (nik as? PartiallyImplemented)?.let { addAll(it.notYetImplemented) }
            (socket as? PartiallyImplemented)?.let { addAll(it.notYetImplemented) }
            (runCatching { rt.platform.power() }.getOrNull() as? PartiallyImplemented)?.let { addAll(it.notYetImplemented) }
        }

        return buildJsonObject {
            put("host", rt.mode.label)
            put("fsm", rt.fsm.state.name)
            put("listeners", JsonArray(emptyList()))
            put("locks", JsonArray(emptyList()))
            put("source", "local-snapshot")
            put("version", NodeVersion.STRING)
            put("platform", rt.platform.id)
            put("mode", rt.mode.cliName)
            put("lending", rt.config.lending)
            put("keyStorage", nik.keyStorage.wire)
            putJsonObject("engine") {
                put("backend", rt.engine.backend)
                put("hasLocalEngine", rt.engine.engine.hasLocalEngine)
            }
            put("peers", JsonArray(emptyList()))
            put("sessions", 0)
            put("inflight", 0)
            putJsonObject("paths") {
                put("state", paths.stateDir.toString())
                put("ledger", paths.ledgerDir.toString())
                put("controlSocket", paths.controlSocket.toString())
            }
            putJsonObject("governors") {
                putJsonObject("power") {
                    put("source", power?.source?.wire)
                    put("charging", power?.charging)
                    put("batteryBand", power?.batteryBand?.wire)
                }
                putJsonObject("thermal") {
                    put("band", thermal?.band)
                    put("watchedSensors", thermal?.watchedSensors?.size)
                }
                put("gpuContention", if (gpuPort == null) "off (no attributable counter)" else "amdgpu")
                put("rules", rt.rules.label)
            }
            put("keepAwake", "not-implemented")
            putJsonArray("notYetImplemented") { nyi.forEach { add(JsonPrimitive("${it.feature} (${it.track})")) } }
        }
    }

    fun human(o: JsonObject): String = buildString {
        fun s(k: String): String = (o[k] as? JsonPrimitive)?.content ?: "-"
        appendLine("host:        ${s("host")}   (${s("platform")}, mode ${s("mode")})")
        appendLine("fsm:         ${s("fsm")}   (lending ${if ((o["lending"] as? JsonPrimitive)?.content == "true") "requested" else "OFF"})")
        appendLine("listeners:   none")
        appendLine("locks:       none   (keep-awake: ${s("keepAwake")})")
        appendLine("key tier:    ${s("keyStorage")}")
        appendLine("source:      ${s("source")}: no control socket exists yet, so this is not a live node")
        val nyi = (o["notYetImplemented"] as? JsonArray).orEmpty()
        if (nyi.isNotEmpty()) appendLine("not yet implemented: " + nyi.joinToString("; ") { (it as JsonPrimitive).content })
    }

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
}
