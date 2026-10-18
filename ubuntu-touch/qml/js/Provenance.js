.pragma library

// The provenance of an answer, from the `record` of an `end` frame. The record is the terminal ledger row's own members
// (UiProjection.kt; Invariant 9), so this file only chooses a shape and a label; it never recomputes a fact. Every class has
// its own shape AND its own label, so colour is never the only signal (Invariant 6).
function describe(record, tokens) {
    var cls = record && typeof record.servedClass === "string" ? record.servedClass : "none"
    var text = record && typeof record.provenance === "string" ? record.provenance : ""
    if (cls === "local")
        return { cls: cls, shape: tokens.shapeThisDevice, colorName: "violet", label: tokens.labelThisDevice, text: text }
    if (cls === "peer") {
        var via = record && typeof record.peerPath === "string" ? " · " + record.peerPath : ""
        return { cls: cls, shape: tokens.shapePeer, colorName: "cyan", label: tokens.labelPeer + via, text: text }
    }
    if (cls === "cloud")
        return { cls: cls, shape: tokens.shapePeer, colorName: "cyan", label: "cloud", text: text }
    return { cls: "none", shape: tokens.shapeNone, colorName: "muted", label: "not served", text: text }
}

// The header row: one shape and label per node state.
function nodeStateShape(state, tokens) {
    if (state === "active")
        return tokens.shapeActive
    if (state === "interrupted")
        return tokens.shapeInterrupted
    return tokens.shapeIdle
}

function nodeStateLabel(state, sessions) {
    if (state === "active")
        return "active (" + sessions + (sessions === 1 ? " session)" : " sessions)")
    if (state === "interrupted")
        return "interrupted"
    return "idle"
}
