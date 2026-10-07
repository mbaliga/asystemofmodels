import QtQuick 2.12
import Lomiri.Components 1.3
import "tokens"
import "js/Provenance.js" as Provenance

// The chat screen: the only local caller (ubuntu-touch.md 2.1). Each answer carries its provenance line, built by the node from
// the same record as the ledger row; the shape and label beside it repeat what the words say.
Item {
    id: page

    property var model
    readonly property var provenance: page.model.answer.record !== null ? Provenance.describe(page.model.answer.record, Tokens)
                                                                      : Provenance.describe(null, Tokens)

    Column {
        anchors { fill: parent; margins: Tokens.gu }
        spacing: Tokens.gu

        TextField {
            id: modelField
            objectName: "modelField"
            width: parent.width
            text: "auto"
            placeholderText: "model or policy (auto, cheapest, fastest, best-reasoning)"
        }
        TextField {
            id: promptField
            objectName: "promptField"
            width: parent.width
            placeholderText: "your prompt"
        }
        Row {
            spacing: Tokens.gu
            Button {
                objectName: "sendButton"
                text: "Send"
                enabled: page.model.ready && !page.model.inflight && promptField.text !== ""
                onClicked: page.model.borrow(modelField.text, promptField.text)
            }
            Button {
                objectName: "cancelButton"
                text: "Cancel"
                enabled: page.model.inflight
                onClicked: page.model.cancel()
            }
        }
        Label {
            objectName: "answerText"
            textFormat: Text.PlainText
            width: parent.width
            wrapMode: Text.WordWrap
            color: Tokens.text
            font.pixelSize: Tokens.fontBody
            text: page.model.answer.text
        }
        Label {
            objectName: "answerError"
            textFormat: Text.PlainText
            visible: page.model.answer.error !== ""
            width: parent.width
            wrapMode: Text.WordWrap
            color: Tokens.text
            font.pixelSize: Tokens.fontBody
            text: page.model.answer.error
        }
        Row {
            objectName: "provenanceRow"
            visible: page.model.answer.record !== null
            spacing: Tokens.gu
            Glyph {
                objectName: "provenanceShape"
                anchors.verticalCenter: parent.verticalCenter
                shape: page.provenance.shape
                color: Tokens[page.provenance.colorName] !== undefined ? Tokens[page.provenance.colorName] : Tokens.textMuted
            }
            Label {
                objectName: "provenanceLabel"
                textFormat: Text.PlainText
                anchors.verticalCenter: parent.verticalCenter
                color: Tokens.textMuted
                font.pixelSize: Tokens.fontSmall
                text: page.provenance.label
            }
        }
        Label {
            objectName: "provenanceText"
            textFormat: Text.PlainText
            visible: page.model.answer.record !== null
            width: parent.width
            wrapMode: Text.WordWrap
            color: Tokens.textMuted
            font.pixelSize: Tokens.fontSmall
            text: page.provenance.text + (page.model.answer.record !== null && page.model.answer.record.servedClass === "peer" ? "  (name is self-reported by the peer)" : "")
        }
    }
}
