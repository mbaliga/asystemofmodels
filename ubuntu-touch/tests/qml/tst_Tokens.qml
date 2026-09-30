import QtQuick 2.12
import QtTest 1.2
import "../../qml/tokens"

// Invariant 6 and 7 at the seam: the semantic pair is exactly violet #8E7BFF and cyan #35E0FF; no token is red or green; every
// shape token differs from the others, so shape can carry what colour cannot.
TestCase {
    name: "Tokens"

    function hue(c) {
        var r = c.r, g = c.g, b = c.b
        var mx = Math.max(r, g, b), mn = Math.min(r, g, b), d = mx - mn
        if (d === 0)
            return { h: 0, s: 0 }
        var h
        if (mx === r) h = ((g - b) / d) % 6
        else if (mx === g) h = (b - r) / d + 2
        else h = (r - g) / d + 4
        h = h * 60
        if (h < 0) h += 360
        return { h: h, s: mx === 0 ? 0 : d / mx }
    }

    function test_semanticPair() {
        compare(Tokens.violet.toString().toLowerCase(), "#8e7bff")
        compare(Tokens.cyan.toString().toLowerCase(), "#35e0ff")
    }

    function test_noTokenIsRedOrGreen() {
        var names = ["violet", "cyan", "background", "surface", "outline", "text", "textMuted"]
        for (var i = 0; i < names.length; i++) {
            var x = hue(Tokens[names[i]])
            var saturated = x.s > 0.25
            var red = x.h < 30 || x.h >= 330
            var green = x.h >= 75 && x.h < 165
            verify(!(saturated && (red || green)), names[i] + " is a saturated red or green (hue " + x.h + ", saturation " + x.s + ")")
        }
    }

    function test_theHueCheckHasBite() {
        var red = hue(Qt.rgba(1, 0.1, 0.1, 1)), green = hue(Qt.rgba(0.1, 0.8, 0.2, 1))
        verify(red.s > 0.25 && (red.h < 30 || red.h >= 330))
        verify(green.s > 0.25 && green.h >= 75 && green.h < 165)
    }

    function test_everyMeaningHasItsOwnShape() {
        var shapes = [Tokens.shapeThisDevice, Tokens.shapePeer, Tokens.shapeActive, Tokens.shapeInterrupted]
        for (var i = 0; i < shapes.length; i++)
            for (var j = i + 1; j < shapes.length; j++)
                verify(shapes[i] !== shapes[j], "two meanings share the shape " + shapes[i])
        verify(Tokens.shapeThisDevice !== Tokens.shapePeer)
        verify(Tokens.labelThisDevice !== "" && Tokens.labelPeer !== "")
    }
}
