pragma Singleton
import QtQuick 2.12

// The token-contract seam (Invariants 6 and 7). Every colour and size the UI uses is defined here and nowhere else: a page that
// needs a colour names a token, so the design system can replace this file without touching a page.
//
// Invariant 6: red and green never carry meaning (the owner is colour-blind). The semantic pair is violet / cyan, and every use
// of it comes with a shape and a label, so colour is never the only signal. tools/check_tokens.py fails the build on any colour
// literal outside this file and on any hue in the red or green zones.
QtObject {
    // the semantic pair (sourced from Hyle accent.violet and provenance.cloud)
    readonly property color violet: "#8E7BFF"
    readonly property color cyan: "#35E0FF"

    // neutrals (low saturation on purpose)
    readonly property color background: "#14121F"
    readonly property color surface: "#221F33"
    readonly property color outline: "#5B5780"
    readonly property color text: "#ECEAF7"
    readonly property color textMuted: "#A9A5C2"

    // shapes are the second signal: each meaning has one shape and one label
    readonly property string shapeThisDevice: "diamond"
    readonly property string shapePeer: "triangle"
    readonly property string shapeIdle: "circle-open"
    readonly property string shapeActive: "circle-filled"
    readonly property string shapeInterrupted: "square"
    readonly property string shapeNone: "circle-open"

    readonly property string labelThisDevice: "this device"
    readonly property string labelPeer: "peer"

    // one grid unit in pixels; Main.qml sets it from Lomiri's units.gu(1) when the UI toolkit is present
    property real gu: 8
    readonly property real fontSmall: gu * 1.6
    readonly property real fontBody: gu * 2
    readonly property real fontTitle: gu * 2.6
}
