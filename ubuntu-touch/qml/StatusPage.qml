import QtQuick 2.12
import Lomiri.Components 1.3
import "tokens"
import "js/ErrorText.js" as ErrorText

// Node state, the self-test (UT0.3 and the S-UT1 spike, DV-UT01) and the About block.
Item {
    id: page

    property var model

    function selfTestSummary(r) {
        if (r === null || r === undefined)
            return ""
        var lines = ["self-test: " + r.selftest]
        if (r.tls !== undefined) lines.push("TLS " + r.tls + " · ALPN " + r.alpn + " · client certificate " + (r.clientAuth ? "seen" : "NOT seen"))
        if (r.vectors !== undefined) lines.push("vectors M01 " + r.vectors.M01 + " · M02 " + r.vectors.M02 + " · M03 " + r.vectors.M03 + " · failed " + r.vectors.failed)
        if (r.jsonl !== undefined) lines.push("ledger appends " + r.jsonl.appends + " in " + r.jsonl.ms + " ms")
        if (r.thermalReadable !== undefined) lines.push("thermal readable: " + r.thermalReadable + " · battery readable: " + r.batteryReadable)
        if (r.rssKiB !== undefined) lines.push("memory (RSS): " + r.rssKiB + " KiB")
        if (r.failed !== undefined) lines.push("FAILED: " + r.failed.join(", "))
        return lines.join("\n")
    }

    Column {
        anchors { fill: parent; margins: Tokens.gu }
        spacing: Tokens.gu

        Label {
            objectName: "failureText"
            visible: page.model.failure !== ""
            width: parent.width
            wrapMode: Text.WordWrap
            color: Tokens.text
            font.pixelSize: Tokens.fontBody
            text: page.model.failureText
        }
        Label {
            width: parent.width
            wrapMode: Text.WordWrap
            color: Tokens.text
            font.pixelSize: Tokens.fontBody
            text: "This app runs only while it is open on screen. Nothing runs in the background, and nothing leaves this phone until you ask for an answer."
        }
        Button {
            objectName: "selfTestButton"
            text: "Run self-test"
            enabled: page.model.ready
            onClicked: page.model.send('{"t":"selftest"}')
        }
        Label {
            objectName: "selfTestText"
            width: parent.width
            wrapMode: Text.WordWrap
            color: Tokens.text
            font.pixelSize: Tokens.fontSmall
            text: page.selfTestSummary(page.model.selfTestResult)
        }
        Label {
            width: parent.width
            wrapMode: Text.WordWrap
            color: Tokens.textMuted
            font.pixelSize: Tokens.fontSmall
            text: "About: node key storage \"" + (page.model.keyStorage === "" ? "not started" : page.model.keyStorage) +
                  "\" (a file in this app's private folder; anything running as you outside the app's confinement can read it). " +
                  "Program checksum " + page.model.process.jarSha256 + ". Runtime list checksum " + page.model.process.runtimeManifestSha256 +
                  ". Compare them with the values published beside the release. Copying this app's data folder to another device copies the node's identity: pair again instead."
        }
    }
}
