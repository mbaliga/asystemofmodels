package xyz.mdhv.asom.ui.theme

import dev.aarso.hyle.tokens.HyleTokens
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Enforces Invariant §1.6 at the Hyle token seam: the two meaning-bearing hues are
 * exactly violet + cold-cyan, and no colour the seam consumes may be a red/green hue
 * (the owner is red-green colorblind, so red/green must never carry meaning).
 *
 * Pure-JVM by construction — asserts on the raw ARGB longs in [HyleSeam] / [HyleTokens],
 * never touching Compose — so it runs as a plain `:app` unit test.
 */
class HyleSeamTest {

    @Test
    fun violetIsHyleAccentViolet() {
        assertEquals(0xFF8E7BFFL, HyleSeam.Violet, "attention hue must be Hyle accent.violet #8E7BFF")
    }

    @Test
    fun cyanIsHyleColdCyan() {
        assertEquals(0xFF35E0FFL, HyleSeam.Cyan, "active hue must be Hyle provenance.cloud cold-cyan #35E0FF")
    }

    @Test
    fun noSeamColorIsAForbiddenRedOrGreenHue() {
        for (bound in HyleSeam.Bound) {
            assertFalse(
                bound in HyleSeam.ForbiddenRedGreen,
                "seam colour 0x${bound.toString(16).uppercase()} is a banned red/green hue (§1.6)",
            )
        }
    }

    @Test
    fun theRadiumGreenProvenanceHueIsNeverBound() {
        // Hyle ships a radium yellow-green as its "native / on-device" provenance hue;
        // §1.6 forbids it from carrying meaning in asom, so it must stay unbound.
        assertTrue(
            HyleTokens.Color.colorPaletteProvenanceNative !in HyleSeam.Bound,
            "Hyle radium-green (#C7EF9E) must not back any asom seam colour (§1.6)",
        )
    }
}
