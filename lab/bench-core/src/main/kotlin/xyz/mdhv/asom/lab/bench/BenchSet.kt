package xyz.mdhv.asom.lab.bench

/** The order of tiers within a set; also the order of `results` rows in a projection. */
val TIER_ORDER: List<String> = listOf("T0", "T1", "T2", "T3", "T4", "T5")

enum class PinStatus { PROPOSED, CONFIRMED, UNPINNED }

/**
 * One pinned file of a bench set. [sha256], [bytes] and [revision] are null only in a set whose status is [PinStatus.UNPINNED]
 * (nothing may then be decoded against it).
 */
data class TierPin(
    val tier: String,
    val modelId: String,
    val name: String,
    val quant: String,
    val bytes: Long?,
    val sha256: String?,
    val revision: String?,
    val params: Long?,
    val kvBytesPerToken: Long?,
)

data class BenchSetDef(val id: String, val status: PinStatus, val tiers: List<TierPin>) {
    fun pin(tier: String): TierPin? = tiers.firstOrNull { it.tier == tier }
}

/** Bytes per token of a file's tokenizer over the reference prompts, and its cap (design 6.2, LAB_SPEC 5). */
data class BptPins(val bptPermille: Long, val bptCapPermille: Long, val provisional: Boolean)

/** Typed placeholder: nothing may use an MLPerf metric formula until spike S-B1 pins it (LAB_SPEC 5, design 6.1). */
sealed interface MlperfMetricMapping {
    data object UNPINNED : MlperfMetricMapping
}

object BenchSets {
    const val Q1_ID: String = "qwen3-dense-1"
    const val L1_ID: String = "llama-instruct-1"

    /**
     * Q1 (Qwen3 dense, Apache-2.0): `benchmark.md` 4.2, copied as data. The owner has not yet downloaded each file and confirmed
     * `sha256sum` [A17], so every pin is PROPOSED.
     */
    val Q1: BenchSetDef = BenchSetDef(
        Q1_ID, PinStatus.PROPOSED,
        listOf(
            TierPin("T0", "qwen3-0.6b", "Qwen3-0.6B", "Q8_0", 639_446_688L, "9465e63a22add5354d9bb4b99e90117043c7124007664907259bd16d043bb031", "23749fefcc72300e3a2ad315e1317431b06b590a", 751_632_384L, 114_688L),
            TierPin("T1", "qwen3-1.7b", "Qwen3-1.7B", "Q8_0", 1_834_426_016L, "061b54daade076b5d3362dac252678d17da8c68f07560be70818cace6590cb1a", "90862c4b9d2787eaed51d12237eafdfe7c5f6077", 2_031_739_904L, 114_688L),
            TierPin("T2", "qwen3-4b", "Qwen3-4B", "Q4_K_M", 2_497_280_256L, "7485fe6f11af29433bc51cab58009521f205840f5b4ae3a32fa7f92e8534fdf5", "bc640142c66e1fdd12af0bd68f40445458f3869b", 4_022_468_096L, 147_456L),
            TierPin("T3", "qwen3-8b", "Qwen3-8B", "Q4_K_M", 5_027_783_488L, "d98cdcbd03e17ce47681435b5150e34c1417f50b5c0019dd560e4882c5745785", "7c41481f57cb95916b40956ab2f0b139b296d974", 8_190_735_360L, 147_456L),
            TierPin("T4", "qwen3-14b", "Qwen3-14B", "Q4_K_M", 9_001_752_960L, "500a8806e85ee9c83f3ae08420295592451379b4f8cf2d0f41c15dffeb6b81f0", "530227a7d994db8eca5ab5ced2fb692b614357fd", 14_768_307_200L, 163_840L),
            TierPin("T5", "qwen3-32b", "Qwen3-32B", "Q4_K_M", 19_762_149_024L, "efd971561896866f0e910cce52761ca77b1b138090c7f15fe284676d57d1f689", "938a7432affaec9157f883a87164e2646ae17555", 32_762_123_264L, 262_144L),
        ),
    )

    /**
     * L1 (Llama 3.2 1B/3B, 3.1 8B Instruct; design 6.2): BLOCKED(D18) for the file hashes, sizes and revisions, because the file
     * source is an open owner decision and nothing in the spec defines them. The table names the models and quants only; every pin
     * is null and the set is UNPINNED, so no document can be decoded against it. The tier ids and the set id are placeholders.
     */
    val L1: BenchSetDef = BenchSetDef(
        L1_ID, PinStatus.UNPINNED,
        listOf(
            TierPin("L1a", "llama-3.2-1b-instruct", "Llama-3.2-1B-Instruct", "Q8_0", null, null, null, null, null),
            TierPin("L1b", "llama-3.2-3b-instruct", "Llama-3.2-3B-Instruct", "Q4_K_M", null, null, null, null, null),
            TierPin("L1c", "llama-3.1-8b-instruct", "Llama-3.1-8B-Instruct", "Q4_K_M", null, null, null, null, null),
        ),
    )

    /** The sets a verifier or a renderer knows by default. L1 is never among them. */
    fun defaults(): List<BenchSetDef> = listOf(Q1)

    /** L1 is loaded only when a D18 ruling flag is set. Without the flag this throws, so no default path can reach it. */
    fun withD18Ruling(d18RulingFlag: Boolean): List<BenchSetDef> {
        require(d18RulingFlag) { "the L1 table is loaded only when a D18 ruling flag is set" }
        return defaults() + L1
    }

    fun find(id: String, sets: List<BenchSetDef> = defaults()): BenchSetDef? = sets.firstOrNull { it.id == id }
}

object BytesPerToken {
    /** PROVISIONAL class defaults (design 6.2, [A37]): 4000 and 8000, until the owner's reference CPU run supplies per-file values. */
    val CLASS_DEFAULT: BptPins = BptPins(4000L, 8000L, provisional = true)

    private val perFile: Map<String, BptPins> = emptyMap()

    fun forFile(fileSha256: String): BptPins = perFile[fileSha256] ?: CLASS_DEFAULT
}

/**
 * Numerics references compiled into the pin, keyed by (engine commit, file sha256) (design 6.5 B5). The owner has not computed
 * any, so the table is empty and a document's own reference is self-attested (ERRATA ERR-BENCH-6).
 */
object NumericsReferences {
    private val table: Map<Pair<String, String>, Long> = emptyMap()

    fun lookup(engineCommit: String, fileSha256: String): Long? = table[engineCommit to fileSha256]
}

/** Editorial constants of `asom.text/1` (benchmark.md 12.3; PROVISIONAL until the owner rules D18). */
object Editorial {
    const val COMFORT_DECODE_MTPS: Long = 10_000L
    const val USABLE_DECODE_MTPS: Long = 4_000L
    const val COMFORT_TTFT_US: Long = 2_000_000L
    const val USABLE_TTFT_US: Long = 10_000_000L
    const val ROLE_STRONG_MTPS: Long = 10_000L
    const val ROLE_HELPER_MTPS: Long = 8_000L

    /** The descriptive MLPerf note ships disabled (design 6.1 item 3; D18; law LM-9). */
    const val MLPERF_NOTE_ENABLED: Boolean = false

    /** The descriptive note text. It never contains the string forbidden by law LM-9. */
    const val MLPERF_NOTE: String =
        "same model set and metric definitions as MLPerf Mobile v6.0; not an MLPerf result; not comparable with MLPerf results"
}
