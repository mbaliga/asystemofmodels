package xyz.mdhv.asom.lab.sim

import java.io.File

/**
 * `./gradlew -p lab :mesh-sim:run --args='[--seed N] [--variant B3|B0|B1|B2] [--out DIR] [SC01 SC04 ...]'`
 * Runs the named scenarios (all of `lab/mesh-sim/scenarios/` by default) and prints one line per scenario. Every line carries the evidence label.
 */
fun main(args: Array<String>) {
    System.setOut(java.io.PrintStream(java.io.FileOutputStream(java.io.FileDescriptor.out), true, "UTF-8"))
    var verbose = false
    var replay = false
    var seed: Long? = null
    var seeds: LongRange? = null
    var variant = Variant.B3
    var out: File? = null
    val names = ArrayList<String>()
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--seed" -> seed = args[++i].toLong()
            "--seeds" -> args[++i].split("-").let { seeds = it[0].toLong()..it[1].toLong() }
            "--variant" -> variant = Variant.valueOf(args[++i])
            "--out" -> out = File(args[++i])
            "--verbose" -> verbose = true
            "--replay" -> replay = true
            else -> names += args[i]
        }
        i++
    }
    val root = System.getProperty("asom.repoRoot")?.let { File(it) } ?: Reports.findRepoRoot(File("").absoluteFile)
    val dir = File(root, "lab/mesh-sim/scenarios")
    val files = (if (names.isEmpty()) dir.listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name } else names.map { File(dir, "$it.json") })
    println("scenario | seed | variant | decision | reason | ledger rows | law violations | $SIM_LABEL")
    var bad = 0
    val runs = files.flatMap { f -> (seeds?.toList() ?: listOf(seed)).map { f to it } }
    for ((f, sd) in runs) {
        val sim = Simulation.load(f, root, sd, variant)
        val res = sim.run()
        val s = Reports.summarize(res, sim.sc.self.id)
        println(s.tableLine())
        out?.let { Reports.writeOutputs(res, File(it, res.scenario)) }
        if (verbose || s.lawViolations > 0) SimLaws.violations(res).take(12).forEach { println("    violation: $it") }
        if (verbose) {
            res.decisions.take(8).forEach { d -> println("    ${d.requestId} t=${d.t} attempts=${d.attempts} excluded=${d.excluded} error=${d.error}") }
            println("    kept-to-discrepant ${res.keptObservationsToDiscrepant} first ${res.discrepantAfterKept}")
            res.requests.chunked(25).forEachIndexed { i, c -> println("    bucket $i: " + c.groupingBy { r -> r.servedBy ?: r.error ?: "?" }.eachCount().toSortedMap()) }
            res.requests.flatMap { r -> r.attempts }.groupingBy { a -> "${a.tier}:${a.target}:${a.status}" }.eachCount().toSortedMap().forEach { (k, n) -> println("    attempt $k x$n") }
            res.requests.groupingBy { r -> "${r.status}/${r.error}/${r.servedBy}" }.eachCount().toSortedMap().forEach { (k, n) -> println("    outcome $k x$n") }
        }
        if (replay) {
            val dirOut = out?.let { File(it, res.scenario) }
            val r = if (dirOut != null) {
                Replay.run(sim.sc, sim.catalogue, File(dirOut, "events.jsonl").readLines(Charsets.UTF_8), File(dirOut, "decisions.jsonl").readLines(Charsets.UTF_8))
            } else {
                Replay.run(sim.sc, sim.catalogue, res)
            }
            println("    replay ${res.scenario}: ${r.decisions} decisions replayed from events.jsonl, diff ${if (r.empty) "EMPTY" else "NOT EMPTY (${r.diff.size})"} | $SIM_LABEL")
            r.diff.take(3).forEach { println("    $it") }
            if (!r.empty) bad++
        }
        if (s.lawViolations > 0) bad++
    }
    println("SIMULATED — NOT DEVICE EVIDENCE: ${runs.size} run(s) of ${files.size} scenario(s), $bad with law violations")
    if (bad > 0) System.exit(1)
}
