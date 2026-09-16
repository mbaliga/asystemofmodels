package xyz.mdhv.asom.ui.theme

import dev.aarso.hyle.Argb
import dev.aarso.hyle.tokens.HyleTokens

/**
 * The asom → Hyle colour binding, expressed as raw ARGB longs (no Compose import),
 * so the Invariant §1.6 guard ([HyleSeamTest]) can be a pure-JVM unit test. The
 * Compose seam [AsomTokens] wraps each of these in `androidx.compose.ui.graphics.Color`.
 *
 * §1.6 (owner is red-green colorblind — hard constraint): the two MEANING-BEARING
 * hues are violet (attention / needs-action) and cyan (active / healthy). Adopting
 * Hyle does NOT change that pair:
 *  - violet ← Hyle `accent.violet` (#8E7BFF) — a byte-for-byte match to asom's own.
 *  - cyan   ← Hyle `provenance.cloud` cold-cyan (#35E0FF) — asom standardises the
 *            radiant hue on Hyle's cyan candidate; Hyle's radium yellow-green
 *            provenance hue and its red/green `feedback` signals are deliberately
 *            NEVER bound into a meaning-bearing role. [HyleSeamTest] enforces this.
 *
 * Neutrals map to Hyle's field/ink ramp (dark-only system; UI surfaces sit at
 * #121212-class, never pure black, per Hyle's halation rule).
 */
internal object HyleSeam {
    /** Attention / inactive / needs-action. */
    const val Violet: Argb = HyleTokens.Color.colorPaletteAccentViolet

    /** Active / healthy / present. */
    const val Cyan: Argb = HyleTokens.Color.colorPaletteProvenanceCloud

    const val Background: Argb = HyleTokens.Color.colorPaletteFieldNear
    const val Surface: Argb = HyleTokens.Color.colorBackgroundSurface
    const val OnSurface: Argb = HyleTokens.Color.colorTextPrimary
    const val OnSurfaceDim: Argb = HyleTokens.Color.colorTextSecondary

    /**
     * Hues that MUST NOT back any meaning-bearing asom colour (§1.6). Kept here so
     * the guard test reads the ban from one authoritative list.
     */
    val ForbiddenRedGreen: Set<Argb> = setOf(
        HyleTokens.Color.colorPaletteProvenanceNative, // radium yellow-green #C7EF9E
        HyleTokens.Color.colorFeedbackSuccess,         // green #5BBF7A
        HyleTokens.Color.colorFeedbackDanger,          // red   #E5564B
        HyleTokens.Color.colorPaletteMaterialSmog,     // red   #CC1A0A
    )

    /** Every colour the Compose seam actually consumes. */
    val Bound: List<Argb> = listOf(Violet, Cyan, Background, Surface, OnSurface, OnSurfaceDim)
}
