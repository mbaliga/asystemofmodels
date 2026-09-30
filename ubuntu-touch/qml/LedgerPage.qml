import QtQuick 2.12
import Lomiri.Components 1.3
import "tokens"
import "js/Frames.js" as Frames

// The node's own ledger rows, as the node wrote them. Export through Content Hub (UT-1) shows the exact JSON first.
Item {
    id: page

    property var model

    Component.onCompleted: page.model.send(Frames.ledger(0, 50))

    Column {
        anchors { fill: parent; margins: Tokens.gu }
        spacing: Tokens.gu

        Button {
            objectName: "ledgerRefresh"
            text: "Refresh"
            enabled: page.model.ready
            onClicked: page.model.send(Frames.ledger(0, 50))
        }
        Label {
            objectName: "ledgerEmpty"
            visible: page.model.rows.length === 0
            width: parent.width
            wrapMode: Text.WordWrap
            color: Tokens.text
            font.pixelSize: Tokens.fontBody
            text: "The ledger is empty: this app has not sent anything anywhere."
        }
        Repeater {
            model: page.model.rows
            delegate: Label {
                width: page.width - 2 * Tokens.gu
                wrapMode: Text.WordWrap
                color: Tokens.text
                font.pixelSize: Tokens.fontSmall
                text: JSON.stringify(modelData)
            }
        }
    }
}
