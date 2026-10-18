import QtQuick 2.12
import Lomiri.Components 1.3
import "tokens"
import "js/Frames.js" as Frames

// Paired devices. Opening this screen is one of the three things that let the node dial (quiescence rule 2); leaving it closes it.
// Names are what the peer says about itself and are shown as such.
Item {
    id: page

    property var model

    Component.onCompleted: page.model.send(Frames.peers(true))
    Component.onDestruction: page.model.send(Frames.peers(false))

    Column {
        anchors { fill: parent; margins: Tokens.gu }
        spacing: Tokens.gu

        Label {
            objectName: "peersEmpty"
            textFormat: Text.PlainText
            visible: page.model.peers.length === 0
            width: parent.width
            wrapMode: Text.WordWrap
            color: Tokens.text
            font.pixelSize: Tokens.fontBody
            text: "No device is paired. Pair a laptop, a desktop or a Steam Deck of yours to borrow its models."
        }
        Repeater {
            model: page.model.peers
            delegate: Row {
                spacing: Tokens.gu
                Glyph {
                    anchors.verticalCenter: parent.verticalCenter
                    shape: Tokens.shapePeer
                    color: Tokens.cyan
                }
                Label {
                    objectName: "peerAlias"
                    textFormat: Text.PlainText
                    anchors.verticalCenter: parent.verticalCenter
                    color: Tokens.text
                    font.pixelSize: Tokens.fontBody
                    text: modelData.alias + " (self-reported)" + (modelData.keyStorage ? " · key: " + modelData.keyStorage + " (self-reported)" : "")
                }
            }
        }
        Label {
            textFormat: Text.PlainText
            width: parent.width
            wrapMode: Text.WordWrap
            color: Tokens.textMuted
            font.pixelSize: Tokens.fontSmall
            text: "Overlay networks are installed by you, outside asom. Without one this phone reaches only devices on the same Wi-Fi."
        }
    }
}
