import QtQuick 2.12
import "tokens"

// A shape drawn from the token seam. The shape is a signal in its own right: the same colour with a different shape means a
// different thing, and the same shape with a different colour means the same thing (Invariant 6).
Canvas {
    id: glyph

    property string shape: Tokens.shapeNone
    property color color: Tokens.text

    implicitWidth: Tokens.gu * 2
    implicitHeight: Tokens.gu * 2
    width: implicitWidth
    height: implicitHeight
    antialiasing: true

    onShapeChanged: requestPaint()
    onColorChanged: requestPaint()
    onWidthChanged: requestPaint()

    onPaint: {
        var c = getContext("2d")
        c.reset()
        c.fillStyle = glyph.color
        c.strokeStyle = glyph.color
        c.lineWidth = Math.max(1, width / 8)
        var w = width, h = height, m = c.lineWidth
        c.beginPath()
        if (shape === "diamond") {
            c.moveTo(w / 2, m); c.lineTo(w - m, h / 2); c.lineTo(w / 2, h - m); c.lineTo(m, h / 2); c.closePath()
            c.fill()
        } else if (shape === "triangle") {
            c.moveTo(w / 2, m); c.lineTo(w - m, h - m); c.lineTo(m, h - m); c.closePath()
            c.fill()
        } else if (shape === "square") {
            c.rect(m, m, w - 2 * m, h - 2 * m)
            c.fill()
        } else if (shape === "circle-filled") {
            c.arc(w / 2, h / 2, w / 2 - m, 0, 2 * Math.PI)
            c.fill()
        } else {
            c.arc(w / 2, h / 2, w / 2 - m, 0, 2 * Math.PI)
            c.stroke()
        }
    }
}
