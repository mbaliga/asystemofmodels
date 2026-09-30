import QtQuick 2.12
import Lomiri.Components 1.3
import "tokens"
import "js/Frames.js" as Frames

// Pairing input (UT-1). UT-0 has no pairing: the node answers every pair frame with the plain words of ErrorText.js.
// The camera group is declared for the QR scan (UT-D4); paste works without it.
Item {
    id: page

    property var model

    Column {
        anchors { fill: parent; margins: Tokens.gu }
        spacing: Tokens.gu

        Label {
            width: parent.width
            wrapMode: Text.WordWrap
            color: Tokens.text
            font.pixelSize: Tokens.fontBody
            text: "Paste the pairing text shown by your other device. You will compare a short code on both screens before anything is trusted."
        }
        TextField {
            id: payload
            objectName: "pairPayload"
            width: parent.width
            placeholderText: "pairing text"
        }
        Button {
            objectName: "pairBegin"
            text: "Begin pairing"
            enabled: page.model.ready && payload.text !== ""
            onClicked: page.model.send(Frames.pairBegin(payload.text))
        }
        Label {
            objectName: "pairNotice"
            visible: page.model.notice !== ""
            width: parent.width
            wrapMode: Text.WordWrap
            color: Tokens.text
            font.pixelSize: Tokens.fontBody
            text: page.model.notice
        }
    }
}
