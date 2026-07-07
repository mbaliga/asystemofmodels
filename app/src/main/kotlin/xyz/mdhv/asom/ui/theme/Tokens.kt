package xyz.mdhv.asom.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Token-contract seam (invariant §1.7): placeholder values only — the Hyle
 * design system replaces THESE VALUES later; every screen must consume
 * colors/shapes exclusively through this object, never inline.
 *
 * Invariant §1.6 (owner is red-green colorblind — hard constraint):
 * red/green NEVER carry meaning. The semantic hue pair is violet/cyan and
 * every state indicator also carries a shape/label redundancy — see
 * [StateGlyph].
 */
object AsomTokens {
    /** Semantic "attention / inactive / needs-action" hue. */
    val Violet = Color(0xFF8E7BFF)

    /** Semantic "active / healthy / present" hue. */
    val Cyan = Color(0xFF08FED5)

    val Background = Color(0xFF101014)
    val Surface = Color(0xFF1A1A20)
    val OnSurface = Color(0xFFE8E8F0)
    val OnSurfaceDim = Color(0xFF9A9AAC)

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
