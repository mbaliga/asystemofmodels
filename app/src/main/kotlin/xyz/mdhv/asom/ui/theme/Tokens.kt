package xyz.mdhv.asom.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Token-contract seam (invariant §1.7): every screen consumes colors/shapes
 * exclusively through this object, never inline. As of the Hyle adoption the
 * VALUES are now sourced from the vendored Hyle design tokens (see
 * `dev/aarso/hyle/README.md`) via [HyleSeam] — the field names below are kept
 * stable so screens are untouched.
 *
 * Invariant §1.6 (owner is red-green colorblind — hard constraint):
 * red/green NEVER carry meaning. The semantic hue pair is violet/cyan and
 * every state indicator also carries a shape/label redundancy — see
 * [StateGlyph]. The violet/cyan binding is pinned in [HyleSeam] and guarded by
 * `HyleSeamTest`; Hyle's radium-green and red/green feedback hues are never bound.
 */
object AsomTokens {
    /** Semantic "attention / inactive / needs-action" hue — Hyle `accent.violet` #8E7BFF. */
    val Violet = Color(HyleSeam.Violet)

    /** Semantic "active / healthy / present" hue — Hyle `provenance.cloud` cold-cyan #35E0FF. */
    val Cyan = Color(HyleSeam.Cyan)

    val Background = Color(HyleSeam.Background)
    val Surface = Color(HyleSeam.Surface)
    val OnSurface = Color(HyleSeam.OnSurface)
    val OnSurfaceDim = Color(HyleSeam.OnSurfaceDim)

    /**
     * Shape/label redundancy (§1.6): color alone never encodes state.
     * Active = filled circle + label; inactive = hollow circle + label.
     */
    object StateGlyph {
        const val ACTIVE = "●"
        const val INACTIVE = "○"
    }
}

@Composable
fun AsomTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = AsomTokens.Cyan,
            secondary = AsomTokens.Violet,
            background = AsomTokens.Background,
            surface = AsomTokens.Surface,
            onSurface = AsomTokens.OnSurface,
        ),
        typography = Typography(),
        content = content,
    )
}
