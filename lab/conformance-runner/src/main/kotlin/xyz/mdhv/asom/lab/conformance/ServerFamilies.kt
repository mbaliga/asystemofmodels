package xyz.mdhv.asom.lab.conformance

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import xyz.mdhv.asom.server.driver.ProviderDriver

private fun expectedRowCount(v: Vector): Int? =
    (v.expectOk as? JsonObject)?.arrOrNull("rows")?.size

private fun observedExchange(x: Exchange, includeRows: Boolean): JsonObject = buildJsonObject {
    put("status", x.http.status)
    put("headers", headerMapJson(x.http.xAsom))
    if (includeRows) put("rows", buildJsonArray { x.rows.forEach { add(rowJson(it)) } })
}

/**
 * W01b: one record, two renderings, against the real `:server` (LAB_SPEC 3.6). The observation is the HTTP
 * status, the X-Asom-* headers actually sent, and every ledger row the request appended. The laws of
 * [verifyExchangeLaws] run on every vector; the vector's `expect.ok` is the full recorded observation.
 *
 * Erratum (round-3 CLOSURE-3): the spec's law "row.toEchoHeaders() equals the response headers" is false for
 * today's v1 on streams, so it is asserted on non-stream responses and weakened to the commit-time headers for
 * streams. Also: a failover writes an attempt row per failed candidate before the terminal row, so a request
 * may append more than one row; the terminal row is the last.
 */
class W01bChecker(private val wrap: (ProviderDriver) -> ProviderDriver = { it }) : FamilyChecker("W01b") {
    override val requiredLaws = setOf("no-new-headers", "header-equal-nonstream", "header-commit-stream", "key-leak-checks", "exchanges")

    override fun observe(v: Vector): Observed {
        if (v.input.strOrNull("lane") == "lab-types") {
            throw NotRunnable("needs LabRouteRecord from :ledger-model (L0.4); ruled by ${v.input.strOrNull("decision")}")
        }
        val x = runExchange(v.input, expectedRowCount(v), wrap)
        bump("exchanges")
        verifyExchangeLaws(x, ::bump)
        return Observed.Ok(observedExchange(x, includeRows = true))
    }
}

/**
 * W02: typed error envelopes against the real `:server` (LAB_SPEC 3.7). Expected: HTTP status, `error.code`,
 * `error.type`; the message text is excluded from the comparison. The same exchange laws apply, including the
 * key-leak law.
 */
class W02Checker(private val wrap: (ProviderDriver) -> ProviderDriver = { it }) : FamilyChecker("W02") {
    override val requiredLaws = setOf("no-new-headers", "header-equal-nonstream", "key-leak-checks", "exchanges")

    override fun observe(v: Vector): Observed {
        val x = runExchange(v.input, 1, wrap)
        bump("exchanges")
        verifyExchangeLaws(x, ::bump)
        val status = x.http.status
        if (status in 200..299) return Observed.Ok(JsonPrimitive(status))
        val err = try {
            parseJson(String(x.http.body ?: ByteArray(0), Charsets.UTF_8)).jsonObject.obj("error")
        } catch (e: Exception) {
            throw LawViolation("status $status without an OpenAI error envelope")
        }
        val code = err.strOrNull("code") ?: "<none>"
        val detail = buildJsonObject {
            put("httpStatus", status)
            put("errorType", err.strOrNull("type") ?: "<none>")
        }
        return Observed.Reject(code, detail)
    }
}

/**
 * W03: SSE bytes (LAB_SPEC 3.8). Server side: the response body equals the scripted chunks concatenated, whatever
 * the chunking. Parse side: the lab's reference parser turns those bytes into events.
 */
class W03Checker(private val wrap: (ProviderDriver) -> ProviderDriver = { it }) : FamilyChecker("W03") {
    override val requiredLaws = setOf("no-new-headers", "header-commit-stream", "key-leak-checks", "byte-passthrough", "parse")

    override fun observe(v: Vector): Observed {
        val script = v.input.obj("driver").obj("default")
        val sent = ScriptedDriver.streamChunks(script).fold(ByteArray(0)) { a, b -> a + b }
        val x = runExchange(v.input, 1, wrap)
        verifyExchangeLaws(x, ::bump)
        if (x.http.status != 200) throw LawViolation("stream request answered ${x.http.status}")
        val got = x.http.body ?: throw LawViolation("no body")
        if (!got.contentEquals(sent)) {
            throw LawViolation("byte pass-through broken: sent ${sent.size} bytes, received ${got.size} bytes")
        }
        bump("byte-passthrough")
        val parsed = Sse.parse(got)
        bump("parse")
        return Observed.Ok(
            buildJsonObject {
                put("bytesB64", b64(got))
                put("events", buildJsonArray { parsed.events.forEach { add(JsonPrimitive(it)) } })
                put("done", parsed.done)
                put("truncated", parsed.truncated)
            },
        )
    }
}
