package xyz.mdhv.asom.contract

/**
 * Routing policies (brief §5.3, §5.5). A request's `model` field may name a
 * concrete model id or one of these virtual policies; `X-Asom-Policy` may
 * also carry one (it overrides the default policy for concrete models).
 */
enum class Policy(val wire: String) {
    AUTO("auto"),
    CHEAPEST("cheapest"),
    FASTEST("fastest"),
    BEST_REASONING("best-reasoning"),
    LOCAL_ONLY("local-only"),
    ;

    companion object {
        /** All virtual model names accepted in the `model` field (§5.5). */
        val VIRTUAL_MODELS: Set<String> = entries.map { it.wire }.toSet()

        fun fromWire(value: String): Policy? = entries.firstOrNull { it.wire == value }
    }
}
