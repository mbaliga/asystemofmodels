// VENDORED from the Hyle Design System (mbaliga/Hyle-Design-System) — see README.md
// in this directory for the source coordinate and the re-sync procedure.
//
// Only the token surface of `dev.aarso:hyle` is vendored (this typealias + the
// generated HyleTokens object). Hyle's material contract (Finish/Pulse/Provenance)
// and its AGSL render layer are intentionally NOT vendored: asom v1 adopts Hyle at
// the token layer only (Invariant §1.7 — placeholder-functional, no visual ambition).
package dev.aarso.hyle

/** ARGB colour as a plain Long (0xAARRGGBB), so this module stays Compose-free. */
typealias Argb = Long
