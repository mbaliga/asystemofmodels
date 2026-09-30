package xyz.mdhv.asom.desktop

import java.util.ServiceLoader

/** Finds the host through `ServiceLoader<DesktopPlatform>`. Exactly one host may be on the classpath; two is a refusal. */
sealed interface HostLookup {
    data class Found(val platform: DesktopPlatform) : HostLookup
    data object None : HostLookup
    data class Ambiguous(val ids: List<String>) : HostLookup
}

object HostFinder {
    fun find(candidates: Iterable<DesktopPlatform> = ServiceLoader.load(DesktopPlatform::class.java)): HostLookup {
        val all = candidates.toList()
        return when (all.size) {
            0 -> HostLookup.None
            1 -> HostLookup.Found(all[0])
            else -> HostLookup.Ambiguous(all.map { it.id })
        }
    }

    /** Prints the reason and returns an exit code when no single host exists; null when [lookup] is usable. */
    fun failure(lookup: HostLookup, env: NodeEnv): Int? = when (lookup) {
        is HostLookup.Found -> null
        HostLookup.None -> {
            env.err.println("asom: no host module on the classpath (expected one DesktopPlatform service)")
            ExitCodes.NO_HOST
        }
        is HostLookup.Ambiguous -> {
            env.err.println("asom: more than one host module on the classpath (${lookup.ids.joinToString()}); refusing to guess")
            ExitCodes.REFUSED
        }
    }
}
