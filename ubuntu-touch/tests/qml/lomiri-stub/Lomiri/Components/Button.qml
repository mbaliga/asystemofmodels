import QtQuick 2.12

Rectangle {
    id: button
    property string text
    signal clicked()
    implicitWidth: 100
    implicitHeight: 30
    color: enabled ? "gray" : "darkgray"
    Text { anchors.centerIn: parent; text: button.text }
    MouseArea { anchors.fill: parent; onClicked: button.clicked() }
}
