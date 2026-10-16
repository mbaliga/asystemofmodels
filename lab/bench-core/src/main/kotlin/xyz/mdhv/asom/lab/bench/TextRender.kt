package xyz.mdhv.asom.lab.bench

import java.security.MessageDigest
import xyz.mdhv.asom.lab.json.Base64Strict

/**
 * Options of the `asom.text/1` renderer. [meshAvailable] is a shell fact, not a document field (design 6.5 B17): without it,
 * question 5 says the version does not share work. [mlperfNoteEnabled] defaults to the compiled-in flag (design 6.1 item 3).
 */
data class RenderOptions(val meshAvailable: Boolean = false, val mlperfNoteEnabled: Boolean = Editorial.MLPERF_NOTE_ENABLED)

/**
 * `asom.text/1`: the plain-text report (benchmark.md 12), a pure function of the derived measurement document. ASCII 0x20-0x7E and
 * LF only, at most 72 columns, a fixed English template, no locale. There is no other text source anywhere (law LM-3).
 */
object TextRender {
    const val MAX_COLS = 72
    const val FORBIDDEN_LABEL = "MLPerf-comparable"

    fun render(d: Derived, opts: RenderOptions = RenderOptions()): String {
        val text = build(d, opts)
        check(text.all { it == '\n' || it.code in 0x20..0x7E }) { "renderer produced a non-ASCII byte" }
        check(!text.contains(FORBIDDEN_LABEL)) { "renderer produced the forbidden label" }
        return text
    }

    fun textSha256B64u(text: String): String = Base64Strict.encodeUrlNoPad(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.US_ASCII)))

    fun asciiOnly(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            sb.append(if (cp in 32..126) cp.toChar() else '?')
            i += Character.charCount(cp)
        }
        return sb.toString()
    }

    fun rate(mtps: Long): String {
        val t = mtps / 100
        return "${t / 10}.${t % 10}"
    }

    fun gb(b: Long): String {
        val t = b / 100_000_000L
        return "${t / 10}.${t % 10} GB"
    }

    fun gbFile(b: Long): String {
        val t = (b + 50_000_000L) / 100_000_000L
        return "${t / 10}.${t % 10} GB"
    }

    fun dur(us: Long, estimated: Boolean): String {
        val s = us / 1_000_000L
        val out = when {
            us < 10_000_000L -> {
                val t = us / 100_000L
                "${t / 10}.${t % 10} s"
            }
            s < 60 -> "$s s"
            s < 600 -> {
                val m = s / 60
                var r = s % 60
                r = if (estimated) r / 10 * 10 else r / 5 * 5
                if (r == 0L) "$m min" else "$m min $r s"
            }
            else -> "${s / 60} min"
        }
        return if (estimated) "about $out" else out
    }

    private fun secsColumn(us: Long): String = if (us < 10_000_000L) {
        val t = us / 100_000L
        "${t / 10}.${t % 10}"
    } else {
        "${us / 1_000_000L}"
    }

    fun pct(permille: Long): String = "${permille / 10}%"

    private fun params(p: Long): String = "${p / 1_000_000_000L}-billion-parameter"

    private fun label(pin: TierPin): String = "${pin.name} ${if (pin.quant.startsWith("Q4")) "4-bit" else "8-bit"}"

    private val VERDICT = mapOf(
        "comfortable" to "COMFORTABLE", "usable" to "USABLE, NOT COMFORTABLE", "too-slow" to "TOO SLOW FOR CHAT",
        "cannot-hold" to "CANNOT HOLD IT", "not-measured" to "NOT MEASURED",
    )
    private val POWER = mapOf("ac" to "on charger", "battery" to "on battery")
    private val TEST_WORDS = mapOf(
        "pp512@d0" to "reading a 512-token prompt", "pp2048@d0" to "reading a 2048-token prompt", "tg128@d0" to "writing 128 tokens",
        "tg128@d2048" to "writing 128 tokens 2k tokens into a chat", "tg128@d8192" to "writing 128 tokens 8k tokens into a chat",
    )

    /** Role code to (title, sentence lines). The wrapped lines are part of the template. */
    private val ROLE = mapOf(
        "strong-provider" to ("STRONG PROVIDER" to listOf("can serve 7-8B models to your other devices.")),
        "small-model-provider" to ("PROVIDER FOR SMALL MODELS" to listOf("can serve 4B-class models to your", "other devices.")),
        "occasional-helper" to ("OCCASIONAL HELPER" to listOf("can run small models for your", "other devices while charging; send 7-8B work to a stronger", "device when one is available.")),
        "requester" to ("REQUESTER" to listOf("best used to send work to your other devices.")),
        "requester-foreground-helper" to (
            "REQUESTER (HELPS ONLY WHILE OPEN)" to listOf(
                "iPhone and iPad", "apps cannot serve other devices in the background; an iPad can", "lend compute while the app is open.",
            )
            ),
    )

    /** Word wrap at [MAX_COLS]: the first line starts with [lead], continuation lines with [cont]; a word longer than a line is split. */
    private fun wrap(lead: String, text: String, cont: String): List<String> {
        val out = mutableListOf<String>()
        var line = StringBuilder(lead)
        var empty = true
        for (word in text.split(" ").filter { it.isNotEmpty() }) {
            var w = word
            while (w.isNotEmpty()) {
                val need = (if (empty) 0 else 1) + w.length
                if (line.length + need <= MAX_COLS) {
                    if (!empty) line.append(' ')
                    line.append(w)
                    empty = false
                    w = ""
                } else if (empty) {
                    val room = MAX_COLS - line.length
                    line.append(w, 0, room)
                    out += line.toString()
                    line = StringBuilder(cont)
                    w = w.substring(room)
                } else {
                    out += line.toString()
                    line = StringBuilder(cont)
                    empty = true
                }
            }
        }
        out += line.toString()
        return out
    }

    private fun build(d: Derived, opts: RenderOptions): String {
        val doc = d.doc
        val dev = doc.device
        val run = doc.run
        val eng = doc.harness.engine
        val ans = d.answers
        val set = BenchSets.find(doc.benchSet, BenchSets.defaults() + BenchSets.L1)!!
        val L = mutableListOf<String>()
        L += "ASOM DEVICE REPORT (asom.text/1)"
        L += "Generated from this device's benchmark data. (measured) = timed on"
        L += "this device. (estimated) = calculated from measured numbers."
        L += ""
        L += "DEVICE (as reported by the device itself)"
        L += wrap("  ", "${asciiOnly(dev.maker)} ${asciiOnly(dev.model)} - ${dev.platform} ${asciiOnly(dev.osVersion)}", "    ")
        L += wrap("  ", "Chip: ${asciiOnly(dev.soc)} | Memory: ${gb(dev.memTotalBytes)} total, ${gb(doc.memory.availAtStartBytes)} free at start", "    ")
        L += "  Engine: ${eng.name} ${eng.commit.take(8)}, ${eng.backend} backend"
        L += "  Tested: ${run.dayUtc}, ${run.plan} test, ${POWER.getValue(run.powerSource)}, started ${run.startThermal}"
        L += "  Overall confidence: ${ans.overallConfidence.wire.uppercase()}"
        if (doc.device.virtualized) L += "  VIRTUAL MACHINE: results are capped at MEDIUM confidence."
        if (opts.mlperfNoteEnabled && set.id == BenchSets.L1_ID && mlperfConditionsHold(doc)) L += "  Note: ${Editorial.MLPERF_NOTE}."
        L += ""
        L += "ANSWERS"
        q1(L, d)
        q2(L, d)
        q3(L, d)
        q4(L, d)
        q5(L, d, opts)
        L += ""
        L += "DETAILS (tokens per second, higher is better; start = seconds)"
        L += "  model              size     read   write  write@2k  start@512"
        for (t in d.tiers) {
            val pp = t.test("pp512@d0")
            val tg = t.test("tg128@d0")
            val tg2 = t.test("tg128@d2048")
            val read = pp?.value?.let { rate(it) } ?: "-"
            val write = tg?.value?.let { rate(it) } ?: "-"
            val write2k = tg2?.value?.let { rate(it) } ?: "-"
            val start = pp?.ttftMicros?.let { secsColumn(it) } ?: "-"
            L += "  ${label(t.pin).padEnd(18)} ${gbFile(t.pin.bytes!!).replace(" GB", "GB").padEnd(7)} ${read.padStart(6)}  ${write.padStart(6)}  ${write2k.padStart(8)}  ${start.padStart(9)}"
        }
        L += ""
        L += "NOTES"
        for (n in notes(d)) L += wrap("  - ", n, "    ")
        L += ""
        L += "WHAT THIS REPORT DOES NOT TELL YOU"
        L += "- This text proves nothing by itself. It can be checked only by"
        L += "  opening the signed report in a verifier. A valid signature shows"
        L += "  the report is unchanged since signing and which key signed it,"
        L += "  not that the test was honest."
        L += "- Speeds are for the listed test models. Other models of the same size"
        L += "  usually behave alike, but not always (mixture-of-experts models"
        L += "  differ most)."
        L += "- Heat, battery level, other apps and long conversations change speed."
        L += "- Nothing here measures how good the answers are."
        return L.joinToString("\n") + "\n"
    }

    /** All three conditions of design 6.1 item 2 must hold. The third cannot: no MLPerf metric mapping is pinned ([MlperfMetricMapping.UNPINNED], spike S-B1). */
    @Suppress("UNUSED_PARAMETER")
    private fun mlperfConditionsHold(doc: BenchDoc): Boolean = false

    private fun q1(L: MutableList<String>, d: Derived) {
        val q = d.answers.q7b
        val set = BenchSets.Q1
        L += "1. Can it run a 7-8B model comfortably?"
        if (q.basis == "measured" || q.basis == "estimated") {
            L += "   ${VERDICT.getValue(q.verdict)} (${q.basis} with ${label(set.pin("T3")!!)})."
            val est = q.basis == "estimated"
            val src = if (q.basis == "measured") d.tier("T3") else d.tier("T2")
            val drift = src?.test("tg128@d0")?.stat?.drift == true
            if (drift) {
                L += "   First-minute speed: about ${rate(q.decodeMtps!!)} tokens/s, and it starts"
                L += "   answering a 512-token prompt after ${dur(q.ttft512Micros!!, est)}. We call a model"
                L += "   comfortable at 10 tokens/s or more and under 2 s to start."
            } else {
                L += "   It writes about ${rate(q.decodeMtps!!)} tokens/s and starts answering a 512-token"
                L += "   prompt after ${dur(q.ttft512Micros!!, est)}. We call a model comfortable at 10"
                L += "   tokens/s or more and under 2 s to start."
            }
        } else {
            L += "   ${VERDICT.getValue(q.verdict)}."
        }
    }

    private fun q2(L: MutableList<String>, d: Derived) {
        val mh = d.answers.maxHold
        L += "2. What is the largest model it can hold?"
        if (mh == null) {
            L += "   Not measured (no model was loaded)."
            return
        }
        val pin = BenchSets.Q1.pin(mh.largestLoadedTier)!!
        L += "   About ${gb(mh.weightBytes)} of model file (estimated), roughly a"
        L += "   ${params(mh.approxParamsQ4)} model at 4-bit. Largest actually loaded:"
        L += "   ${label(pin)}, ${gbFile(pin.bytes!!)} file (measured)."
    }

    private fun q3(L: MutableList<String>, d: Derived) {
        val a = d.answers.answer2000
        L += "3. How long will a 2000-token answer take?"
        if (a == null) {
            L += "   Not measured (no model finished the speed tests)."
            return
        }
        val pin = BenchSets.Q1.pin(a.tier)!!
        val text = dur(a.micros, true)
        L += "   ${text.replaceFirstChar { it.uppercase() }} with ${label(pin)} for a 512-token"
        L += "   prompt, starting cool (estimated from the measured speeds" + if (a.thermalModel) " and" else ")."
        if (a.thermalModel) L += "   heat test)."
    }

    private fun q4(L: MutableList<String>, d: Derived) {
        val th = d.answers.throttle
        val run = d.doc.run
        L += "4. Will it slow down when it gets warm?"
        if (th == null) {
            L += "   Not measured (the heat test was not run)."
        } else if (th.onsetMs == null) {
            L += "   No slowdown seen during ${dur(th.testedMs * 1000, false)} of continuous writing"
            L += "   (${POWER.getValue(run.powerSource)}, started ${run.startThermal}; measured)."
        } else {
            val pin = BenchSets.Q1.pin(th.tier)!!
            L += "   Yes. After ${dur(th.onsetMs * 1000, false)} of continuous writing, speed fell to"
            L += "   ${pct(th.stabilityPermille)} of its starting speed and stayed there (measured for"
            L += "   ${dur(th.testedMs * 1000, false)}, ${POWER.getValue(run.powerSource)}, with ${label(pin)})."
        }
    }

    private fun q5(L: MutableList<String>, d: Derived, opts: RenderOptions) {
        L += "5. What role suits it in a group of your devices?"
        if (!opts.meshAvailable) {
            L += "   Not applicable: this version does not share work between devices."
            return
        }
        val role = d.answers.role
        val (title, lines) = ROLE.getValue(role.code)
        L += "   $title: ${lines[0]}"
        for (x in lines.drop(1)) L += "   $x"
        val tierUsed = when (role.code) {
            "strong-provider" -> "T3"
            "small-model-provider", "occasional-helper" -> "T2"
            else -> null
        }
        if (tierUsed != null && d.sustain?.doc?.tier != tierUsed) L += "   Basis: estimated from the measured speeds and heat test."
    }

    private fun notes(d: Derived): List<String> {
        val out = mutableListOf<String>()
        for (t in d.tiers) {
            val lab = label(t.pin)
            for (r in t.results) {
                val words = TEST_WORDS[r.name] ?: "running ${r.name}"
                val excluded = r.samples.size - r.stat.kept
                if ("OUTLIER_EXCLUDED" in r.stat.flags) out += "$lab, $words: $excluded of ${r.samples.size} timings discarded as outliers."
                if (r.stat.drift) out += "$lab, $words: speed drifted during the test."
                if (r.confidence == Confidence.LOW || r.confidence == Confidence.INSUFFICIENT) out += "$lab, $words: ${r.confidence.wire} confidence."
            }
            val nv = t.numerics
            val dev = nv.deviationPermille
            out += if (dev == null) "$lab output check: ${nv.verdict}." else "$lab output check: ${nv.verdict} (${dev / 10}.${dev % 10}% from reference)."
        }
        if (d.sustain != null) {
            val s = d.sustain
            if (s.confidence == Confidence.LOW || s.confidence == Confidence.INSUFFICIENT) out += "Heat test: ${s.confidence.wire} confidence."
            if ("PLATEAU_NOT_REACHED" in s.flags) out += "Heat test: the slowed-down speed had not settled when the test ended."
            if ("HARD_CEILING" in s.flags) out += "Heat test: stopped early at a safety limit."
        }
        d.doc.run.abort?.let { out += "The run stopped early (${it.reason}); only the finished tests are shown." }
        out += "Battery use: not measured (needs an unplugged battery test)."
        return out
    }
}
