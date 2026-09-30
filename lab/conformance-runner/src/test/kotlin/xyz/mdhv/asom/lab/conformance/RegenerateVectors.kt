package xyz.mdhv.asom.lab.conformance

import java.io.File
import java.util.SplittableRandom
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import xyz.mdhv.asom.contract.RouteRecord

/**
 * Writes the recorded vector files by running the REAL v1 code (LAB_SPEC 3.1, 4.10): W01-generated, W01b, R04.
 * Runs only through `./gradlew -p lab :conformance-runner:genVectors`. Every recorded observation is also
 * checked against a HAND expectation written here from the spec text; a disagreement aborts generation, so a
 * recording can never silently bless behaviour that the spec's laws contradict. All output is tagged
 * `"oracle": "self"`. A change to a generated file goes with a VERSION bump and a reviewed diff.
 */
class RegenerateVectors {

    private val version = File(Repo.conformance, "VERSION").readUtf8().trim()

    private fun envelope(family: String, refs: List<String>, vectors: List<JsonObject>): JsonObject = buildJsonObject {
        put("family", family)
        put("confVersion", version)
        put("specRefs", jsonStrings(refs))
        put("vectors", JsonArray(vectors))
    }

    private fun vector(id: String, origin: String, status: String, description: String, input: JsonObject, expect: JsonObject): JsonObject =
        buildJsonObject {
            put("id", id)
            put("origin", origin)
            put("status", status)
            put("oracle", "self")
            put("description", description)
            put("input", input)
            put("expect", expect)
        }

    private fun write(rel: String, doc: JsonObject) {
        val f = File(Repo.conformance, rel)
        f.parentFile.mkdirs()
        f.writeUtf8(renderJson(doc))
        println("wrote $rel")
    }

    private fun probe(family: String, input: JsonObject) = Vector(
        file = "probe", family = family, id = "$family-000", origin = "generated", status = "normative",
        oracle = "self", description = "", input = input, expectOk = null, expectReject = null, detail = null,
    )

    @Test
    fun regenerate() {
        assumeTrue(System.getProperty("asom.lab.regen") == "true", "vectors are regenerated only through :conformance-runner:genVectors")
        w01Generated()
        w01b()
        r04()
    }

    // ------------------------------------------------------------------ W01-generated

    private fun w01Generated() {
        val rnd = SplittableRandom(1)
        val vectors = (100..299).map { n ->
            val cost = StrictMath.pow(10.0, -9.0 + 11.0 * rnd.nextDouble())
            val basis = if (n % 2 == 0) "usage" else "heuristic"
            val input = buildJsonObject {
                put("servedProvider", "openrouter")
                put("servedModel", "llama-3.3-70b")
                put("egress", "cloud")
                put("costEst", cost.toString())
                put("costBasis", basis)
            }
            val expected = buildJsonObject {
                put("X-Asom-Cost-Basis", basis)
                put("X-Asom-Cost-Est", RouteRecord.formatUsd(cost))
                put("X-Asom-Egress", "cloud")
                put("X-Asom-Served-By", "openrouter/llama-3.3-70b")
            }
            vector(
                "W01-$n", "generated", "proposed",
                "log-uniform cost, SplittableRandom(1) draw ${n - 99}: 10^(-9 + 11u) via StrictMath.pow, shortest round-trip string; expected recorded from formatUsd on ${System.getProperty("java.version")}",
                input, buildJsonObject { put("ok", expected) },
            )
        }
        write(
            "wire/W01-generated.json",
            envelope("W01", listOf("LAB_SPEC.md 3.5 (W01-100..299)", "REVIEW_ROUND3.md R3-CLOSURE-11 (4)"), vectors),
        )
    }

    // ------------------------------------------------------------------------ W01b

    private val chatPath = "/v1/chat/completions"

    private fun chatRequest(model: String, stream: Boolean): JsonObject = buildJsonObject {
        put("path", chatPath)
        put(
            "body",
            buildJsonObject {
                put("model", model)
                put("stream", stream)
                put("messages", buildJsonArray { add(buildJsonObject { put("role", "user"); put("content", "hello lab") }) })
            },
        )
    }

    private class B(val id: String, val description: String, val input: JsonObject, val hand: (JsonObject) -> Unit)

    private fun rowsOf(o: JsonObject) = (o["rows"] as JsonArray).map { it as JsonObject }
    private fun headersOf(o: JsonObject) = o["headers"] as JsonObject
    private fun s(o: JsonObject, k: String): String? = (o[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    private fun w01b() {
        val ks = jsonStrings(listOf("openrouter", "groq"))
        val cases = listOf(
            B("W01b-001", "non-stream chat, FakeDriver, key present: 200, egress cloud, tokens from usage, basis usage",
                buildJsonObject { put("request", chatRequest("llama-3.3-70b", false)); put("keys", ks) }) { o ->
                check(s(o, "status") == "200")
                check(headersOf(o).keys == setOf("X-Asom-Served-By", "X-Asom-Egress", "X-Asom-Cost-Est", "X-Asom-Cost-Basis"))
                val r = rowsOf(o).single()
                check(s(r, "egress") == "cloud" && s(r, "costBasis") == "usage" && s(r, "servedProvider") == "openrouter")
                check(s(r, "tokensIn") != null && s(r, "tokensOut") != null)
            },
            B("W01b-002", "stream chat with a usage chunk: 200, basis usage; the committed stream headers carry no cost (CLOSURE-3)",
                buildJsonObject { put("request", chatRequest("llama-3.3-70b", true)); put("keys", ks) }) { o ->
                check(s(o, "status") == "200")
                check(headersOf(o).keys == setOf("X-Asom-Served-By", "X-Asom-Egress"))
                val r = rowsOf(o).single()
                check(s(r, "costBasis") == "usage" && s(r, "costEst") != null && s(r, "status") == "200")
            },
            B("W01b-003", "stream chat, omitStreamUsage = true: 200, basis heuristic",
                buildJsonObject {
                    put("request", chatRequest("llama-3.3-70b", true)); put("keys", ks)
                    put("driver", buildJsonObject { put("fake", buildJsonObject { put("omitStreamUsage", true) }) })
                }) { o ->
                check(s(o, "status") == "200")
                check(headersOf(o).keys == setOf("X-Asom-Served-By", "X-Asom-Egress"))
                check(s(rowsOf(o).single(), "costBasis") == "heuristic")
            },
            B("W01b-004", "first provider 503 (failWith), second serves: Served-By is the second provider; the request appends an attempt row for the failed candidate then the terminal row (the spec's 'one row' is wrong for v1, see ERRATA)",
                buildJsonObject {
                    put("request", chatRequest("llama-3.3-70b", false)); put("keys", ks)
                    put("driver", buildJsonObject { put("fake", buildJsonObject { put("failWith", buildJsonObject { put("openrouter", 503) }) }) })
                }) { o ->
                check(s(o, "status") == "200")
                check(s(headersOf(o), "X-Asom-Served-By") == "groq/llama-3.3-70b")
                val rows = rowsOf(o)
                check(rows.size == 2)
                check(s(rows[0], "servedProvider") == "openrouter" && s(rows[0], "status") == "503" && s(rows[0], "egress") == "cloud")
                check(s(rows[1], "servedProvider") == "groq" && s(rows[1], "status") == "200")
            },
            B("W01b-005", "model local-only: 501 LOCAL_ENGINE_ABSENT, egress local, no Served-By",
                buildJsonObject { put("request", chatRequest("local-only", false)); put("keys", ks) }) { o ->
                check(s(o, "status") == "501")
                check(headersOf(o).keys == setOf("X-Asom-Egress") && s(headersOf(o), "X-Asom-Egress") == "local")
                val r = rowsOf(o).single()
                check(s(r, "egress") == "local" && s(r, "servedProvider") == null && s(r, "status") == "501")
            },
            B("W01b-006", "unknown concrete model: 404 MODEL_UNKNOWN, egress local",
                buildJsonObject { put("request", chatRequest("no-such-model", false)); put("keys", ks) }) { o ->
                check(s(o, "status") == "404")
                check(s(headersOf(o), "X-Asom-Egress") == "local")
                check(s(rowsOf(o).single(), "egress") == "local")
            },
            B("W01b-007", "failMidStream after 2 chunks: the row written after the socket closes still agrees with the headers sent at stream start (egress cloud, same provider)",
                buildJsonObject {
                    put("request", chatRequest("llama-3.3-70b", true)); put("keys", ks)
                    put("driver", buildJsonObject { put("fake", buildJsonObject { put("failMidStream", buildJsonObject { put("openrouter", 2) }) }) })
                }) { o ->
                check(s(o, "status") == "200")
                check(headersOf(o).keys == setOf("X-Asom-Served-By", "X-Asom-Egress"))
                val r = rowsOf(o).single()
                check(s(r, "egress") == "cloud" && s(r, "servedProvider") == "openrouter")
                check(s(r, "status") != "200" && s(r, "status") != "400")
            },
            B("W01b-008", "fatal upstream 400 relayed verbatim: egress cloud, Served-By names the provider, no cost",
                buildJsonObject {
                    put("request", chatRequest("llama-3.3-70b", false)); put("keys", jsonStrings(listOf("openrouter")))
                    put(
                        "driver",
                        buildJsonObject {
                            put(
                                "scripts",
                                buildJsonObject {
                                    put("openrouter", buildJsonObject {
                                        put("kind", "error"); put("status", 400)
                                        put("bodyText", """{"error":{"message":"bad request","type":"invalid_request_error"}}""")
                                    })
                                },
                            )
                        },
                    )
                }) { o ->
                check(s(o, "status") == "400")
                check(headersOf(o).keys == setOf("X-Asom-Served-By", "X-Asom-Egress"))
                val r = rowsOf(o).single()
                check(s(r, "costBasis") == "none" && s(r, "costEst") == null && s(r, "status") == "400" && s(r, "egress") == "cloud")
            },
            B("W01b-009", "non-stream reply without usage, priced model: basis heuristic and the cost headers are present",
                buildJsonObject {
                    put("request", chatRequest("llama-3.3-70b", false)); put("keys", jsonStrings(listOf("openrouter")))
                    put(
                        "driver",
                        buildJsonObject {
                            put(
                                "scripts",
                                buildJsonObject {
                                    put("openrouter", buildJsonObject {
                                        put("kind", "json")
                                        put(
                                            "body",
                                            buildJsonObject {
                                                put("id", "scripted-1"); put("object", "chat.completion"); put("created", 0); put("model", "llama-3.3-70b")
                                                put(
                                                    "choices",
                                                    buildJsonArray {
                                                        add(buildJsonObject {
                                                            put("index", 0)
                                                            put("message", buildJsonObject { put("role", "assistant"); put("content", "twelve chars") })
                                                            put("finish_reason", "stop")
                                                        })
                                                    },
                                                )
                                            },
                                        )
                                    })
                                },
                            )
                        },
                    )
                }) { o ->
                check(s(o, "status") == "200")
                check(s(headersOf(o), "X-Asom-Cost-Basis") == "heuristic")
                check(s(rowsOf(o).single(), "costBasis") == "heuristic")
            },
        )
        val checker = W01bChecker()
        val vectors = cases.map { c ->
            val obs = checker.observe(probe("W01b", c.input)) as Observed.Ok
            val o = obs.value as JsonObject
            c.hand(o)
            vector(c.id, "generated", "normative", c.description, c.input, buildJsonObject { put("ok", o) })
        }
        val reach = vector(
            "W01b-reach", "hand", "proposed",
            "LabRouteRecord: the peer attempt receives the body, then SELF serves: header X-Asom-Egress: peer; the terminal row has egress = peer and servedClass = local; header value = row value (design 7.6; ruled by D3). Runs once :ledger-model (L0.4) exists.",
            buildJsonObject { put("lane", "lab-types"); put("decision", "D3") },
            buildJsonObject {
                put(
                    "ok",
                    buildJsonObject {
                        put("headers", buildJsonObject { put("X-Asom-Egress", "peer") })
                        put("terminalRow", buildJsonObject { put("egress", "peer"); put("servedClass", "local") })
                    },
                )
            },
        )
        write(
            "wire/W01b-one-record.json",
            envelope("W01b", listOf("ASOM_BUILD_BRIEF.md 5.4, 1.9", "LAB_SPEC.md 3.6", "REVIEW_ROUND3.md R3-CLOSURE-3"), vectors + reach),
        )
    }

    // ------------------------------------------------------------------------- R04

    private val allKeys = listOf("openrouter", "groq", "trainy-ai", "anthropic")
    private val llama = "llama-3.3-70b"
    private val cheapestAll = listOf(
        "trainy-ai/$llama", "openrouter/$llama", "openrouter/deepseek-v3", "groq/$llama", "anthropic/claude-sonnet-4-5",
    )

    private fun provider(id: String, models: List<String>, priced: Map<String, Pair<Double, Double>> = emptyMap()): JsonObject = buildJsonObject {
        put("id", id); put("displayName", id); put("kind", "openai-compat"); put("baseUrl", "https://$id.example/v1")
        put("auth", buildJsonObject { put("type", "bearer") })
        put("trainsOnData", false); put("programmaticAllowed", true)
        put("pricing", buildJsonObject { priced.forEach { (m, p) -> put(m, buildJsonObject { put("inPerMTok", p.first); put("outPerMTok", p.second) }) } })
        put("models", jsonStrings(models))
    }

    private fun modelEntry(id: String, rank: Int?): JsonObject = buildJsonObject {
        put("id", id); put("family", "f"); put("kind", "chat")
        if (rank != null) put("rank", rank)
    }

    private fun catalogue(providers: List<JsonObject>, models: List<JsonObject>): JsonObject = buildJsonObject {
        put("version", 1); put("updatedAt", "2026-09-30T00:00:00Z")
        put("providers", JsonArray(providers)); put("models", JsonArray(models))
    }

    /** Ties and unpriced: pc/m3 (1.0), then the 2.0 group by provider id then model id, then the unpriced pa/m2. */
    private val tieCatalogue = catalogue(
        listOf(
            provider("pa", listOf("m1", "m2"), mapOf("m1" to (1.0 to 1.0))),
            provider("pb", listOf("m1"), mapOf("m1" to (1.0 to 1.0))),
            provider("pc", listOf("m3"), mapOf("m3" to (0.5 to 0.5))),
            provider("pd", listOf("m0", "m9"), mapOf("m0" to (1.0 to 1.0), "m9" to (1.0 to 1.0))),
        ),
        listOf(modelEntry("m0", null), modelEntry("m1", null), modelEntry("m2", null), modelEntry("m3", null), modelEntry("m9", null)),
    )

    /** Unranked models sort last: m4 has no catalogue entry at all, m2 has an entry without a rank. */
    private val rankCatalogue = catalogue(
        listOf(provider("pa", listOf("m1", "m2", "m4")), provider("pb", listOf("m3"))),
        listOf(modelEntry("m1", 2), modelEntry("m2", null), modelEntry("m3", 1)),
    )

    private class R(
        val id: String,
        val description: String,
        val model: String,
        val keys: List<String>,
        val hand: Any,
        val latency: Map<String, String> = emptyMap(),
        val cooling: List<String> = emptyList(),
        val policyHeader: String? = null,
        val fallback: List<String> = emptyList(),
        val noTrain: Boolean = false,
        val catalogue: JsonElement = JsonPrimitive("fixture"),
        val seeds: List<Long>? = null,
    )

    private fun r04() {
        val cases = listOf(
            R("R04-001", "unknown model", "no-such-model", listOf("openrouter"), "MODEL_UNKNOWN"),
            R("R04-002", "no key stored", llama, emptyList(), "NO_PROVIDER_KEY"),
            R("R04-003", "every keyed serving provider is cooling", llama, listOf("openrouter", "groq"), "ALL_PROVIDERS_COOLING", cooling = listOf("openrouter", "groq")),
            R("R04-004", "local-only as the model", "local-only", allKeys, "LOCAL_ENGINE_ABSENT"),
            R("R04-005", "local-only through X-Asom-Policy on a concrete model", llama, allKeys, "LOCAL_ENGINE_ABSENT", policyHeader = "local-only"),
            R("R04-006", "cheapest: blended price ascending across the fixture", "cheapest", allKeys, cheapestAll),
            R("R04-007", "cheapest with No-Train excludes the training provider", "cheapest", allKeys,
                listOf("openrouter/$llama", "openrouter/deepseek-v3", "groq/$llama", "anthropic/claude-sonnet-4-5"), noTrain = true),
            R("R04-008", "cheapest: ties by provider id then model id; the unpriced model last", "cheapest", listOf("pa", "pb", "pc", "pd"),
                listOf("pc/m3", "pa/m1", "pb/m1", "pd/m0", "pd/m9", "pa/m2"), catalogue = tieCatalogue),
            R("R04-009", "fastest: EWMA ascending, unmeasured last, price within a provider", "fastest", allKeys,
                listOf("groq/$llama", "trainy-ai/$llama", "openrouter/$llama", "openrouter/deepseek-v3", "anthropic/claude-sonnet-4-5"),
                latency = mapOf("openrouter" to "400.0", "groq" to "120.0", "trainy-ai" to "300.0")),
            R("R04-010", "best-reasoning: rank ascending, price breaks rank ties", "best-reasoning", allKeys,
                listOf("anthropic/claude-sonnet-4-5", "openrouter/deepseek-v3", "trainy-ai/$llama", "openrouter/$llama", "groq/$llama")),
            R("R04-011", "best-reasoning: unranked models (no rank, or no catalogue entry) last, then provider id, model id", "best-reasoning", listOf("pa", "pb"),
                listOf("pb/m3", "pa/m1", "pa/m2", "pa/m4"), catalogue = rankCatalogue),
            R("R04-012", "auto: the factor-2.0 band is inclusive (200 = 2 x 100), unmeasured is in-band, out-of-band follows", "auto", allKeys,
                listOf("openrouter/$llama", "openrouter/deepseek-v3", "groq/$llama", "anthropic/claude-sonnet-4-5", "trainy-ai/$llama"),
                latency = mapOf("openrouter" to "100.0", "groq" to "200.0", "trainy-ai" to "201.0")),
            R("R04-013", "auto with no measurements is cheapest order", "auto", allKeys, cheapestAll),
            R("R04-014", "X-Asom-Policy fastest overrides the default policy for a concrete model", llama, listOf("openrouter", "groq", "trainy-ai"),
                listOf("groq/$llama", "trainy-ai/$llama", "openrouter/$llama"),
                latency = mapOf("openrouter" to "400.0", "groq" to "120.0", "trainy-ai" to "300.0"), policyHeader = "fastest"),
            R("R04-015", "X-Asom-Fallback restricts and orders", llama, allKeys, listOf("groq/$llama", "openrouter/$llama"), fallback = listOf("groq", "openrouter")),
            R("R04-016", "X-Asom-Fallback: repeated provider ids are deduplicated", llama, allKeys, listOf("groq/$llama", "openrouter/$llama"),
                fallback = listOf("groq", "groq", "openrouter", "groq")),
            R("R04-017", "X-Asom-Fallback naming only a non-programmatic provider", llama, listOf("webchat-only", "openrouter"), "NO_PROVIDER_KEY", fallback = listOf("webchat-only")),
            R("R04-018", "No-Train on a concrete model drops the training provider", llama, allKeys, listOf("openrouter/$llama", "groq/$llama"), noTrain = true),
            R("R04-019", "No-Train with only the training provider keyed", llama, listOf("trainy-ai"), "NO_PROVIDER_KEY", noTrain = true),
            R("R04-020", "a cooling provider is skipped", llama, listOf("openrouter", "groq", "trainy-ai"), listOf("trainy-ai/$llama", "groq/$llama"), cooling = listOf("openrouter")),
            R("R04-021", "a key for a non-programmatic provider does not count", llama, listOf("webchat-only"), "NO_PROVIDER_KEY"),
            R("R04-022", "model 'auto' with no measurements", "auto", allKeys, cheapestAll),
            R("R04-023", "fastest with no measurements falls back to cheapest order", "fastest", allKeys, cheapestAll),
            R("R04-024", "X-Asom-Fallback restricts a virtual selector too", "cheapest", allKeys, listOf("anthropic/claude-sonnet-4-5", "groq/$llama"), fallback = listOf("anthropic", "groq")),
            R("R04-025", "an unknown model is reported before a missing key", "no-such-model", emptyList(), "MODEL_UNKNOWN"),
            R("R04-026", "No-Train with a fallback naming only the training provider", llama, allKeys, "NO_PROVIDER_KEY", noTrain = true, fallback = listOf("trainy-ai")),
            R("R04-027", "a virtual model wins over X-Asom-Policy (cheapest beats local-only)", "cheapest", allKeys, cheapestAll, policyHeader = "local-only"),
            R("R04-perm", "determinism under catalogue permutation: 50 seeded shuffles of the tie catalogue, same plan", "cheapest", listOf("pa", "pb", "pc", "pd"),
                listOf("pc/m3", "pa/m1", "pb/m1", "pd/m0", "pd/m9", "pa/m2"), catalogue = tieCatalogue, seeds = (1L..50L).toList()),
        )
        val checker = R04Checker()
        val vectors = cases.map { c ->
            val input = buildJsonObject {
                put("catalogue", c.catalogue)
                put("keysPresent", jsonStrings(c.keys))
                put("latencyEwmaMs", buildJsonObject { c.latency.forEach { (k, v) -> put(k, v) } })
                put("cooling", jsonStrings(c.cooling))
                put(
                    "query",
                    buildJsonObject {
                        put("model", c.model)
                        if (c.policyHeader != null) put("policyHeader", c.policyHeader)
                        put("fallback", jsonStrings(c.fallback))
                        put("noTrain", c.noTrain)
                    },
                )
                if (c.seeds != null) put("permutationSeeds", buildJsonArray { c.seeds.forEach { add(JsonPrimitive(it)) } })
            }
            val obs = checker.observe(probe("R04", input))
            val expect = when (obs) {
                is Observed.Ok -> {
                    check(c.hand is List<*>) { "${c.id}: hand expectation is ${c.hand} but the router produced ${obs.value}" }
                    check(jsonStrings(c.hand.map { it as String }) == obs.value) { "${c.id}: hand ${c.hand} != router ${obs.value}" }
                    buildJsonObject { put("ok", obs.value) }
                }
                is Observed.Reject -> {
                    check(c.hand == obs.code) { "${c.id}: hand ${c.hand} != router reject ${obs.code}" }
                    buildJsonObject { put("reject", obs.code) }
                }
            }
            vector(c.id, "generated", "normative", c.description, input, expect)
        }
        write(
            "router/R04-v1-pins.json",
            envelope("R04", listOf("ASOM_BUILD_BRIEF.md 7", "LAB_SPEC.md 3.9", "core/routing Router.kt (frozen v1)"), vectors),
        )
    }
}
