import QtQuick
import QtQuick.Controls

// 圆角小标签，比如“在线”“事件”
Rectangle {
    id: pill
    property alias text: label.text
    property color tone: Theme.success

    implicitWidth: label.implicitWidth + 16
    implicitHeight: label.implicitHeight + 4
    radius: height / 2
    color: Qt.rgba(tone.r, tone.g, tone.b, Theme.dark ? 0.22 : 0.13)

    Label {
        id: label
        anchors.centerIn: parent
        font.pixelSize: 12
        font.weight: Font.DemiBold
        color: pill.tone
    }
}
