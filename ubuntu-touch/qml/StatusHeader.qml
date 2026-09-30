import QtQuick 2.12
import "tokens"
import "js/Provenance.js" as Provenance

// The status header of every page (ubuntu-touch.md 3.5): what the node is doing, driven only by its `state` frames.
// Shape plus label, never colour alone. The app window is the only surface: nothing runs while it is not on screen.
Rectangle {
    id: header

    property string nodeState: "idle"
    property int sessions: 0
    property bool ready: false
    property string lending: "off"

    color: Tokens.surface
    height: row.implicitHeight + Tokens.gu
    implicitHeight: height

    Row {
        id: row
        anchors { left: parent.left; right: parent.right; verticalCenter: parent.verticalCenter; margins: Tokens.gu }
        spacing: Tokens.gu

        Glyph {
            objectName: "nodeShape"
            anchors.verticalCenter: parent.verticalCenter
            shape: Provenance.nodeStateShape(header.nodeState, Tokens)
            color: Tokens.text
        }
        Text {
            objectName: "nodeLabel"
            anchors.verticalCenter: parent.verticalCenter
            color: Tokens.text
            font.pixelSize: Tokens.fontBody
            text: "node: " + (header.ready ? Provenance.nodeStateLabel(header.nodeState, header.sessions) : "starting")
        }
        Glyph {
            anchors.verticalCenter: parent.verticalCenter
            shape: Tokens.shapeThisDevice
            color: Tokens.violet
        }
        Text {
            anchors.verticalCenter: parent.verticalCenter
            color: Tokens.textMuted
            font.pixelSize: Tokens.fontSmall
            text: Tokens.labelThisDevice
        }
        Text {
            objectName: "lendingBanner"
            visible: header.lending !== "off"
            anchors.verticalCenter: parent.verticalCenter
            color: Tokens.text
            font.pixelSize: Tokens.fontSmall
            text: "LENDING"
        }
    }
}
