import QtQuick
import QtQuick.Controls

// 标题 + 数值。点数值即复制（打码时复制的也是真实内容）；secret 项在隐藏敏感信息时打码
Item {
    id: root
    property string title
    property string value
    property bool secret: false
    readonly property string shown: Theme.masked(value, secret, backend.revealed)

    implicitWidth: Math.max(titleLabel.implicitWidth, valueLabel.implicitWidth)
    implicitHeight: titleLabel.implicitHeight + 2 + valueLabel.implicitHeight

    Label {
        id: titleLabel
        text: root.title
        font.pixelSize: 12
        color: Theme.textTertiary
        width: parent.width
        elide: Text.ElideRight
    }
    Label {
        id: valueLabel
        objectName: "value"
        y: titleLabel.implicitHeight + 2
        width: Math.min(implicitWidth, parent.width)
        text: root.shown
        font.pixelSize: 14
        font.weight: Font.DemiBold
        font.underline: area.containsMouse && root.value !== ""
        color: Theme.text
        elide: Text.ElideRight

        MouseArea {
            id: area
            anchors.fill: parent
            hoverEnabled: true
            enabled: root.value !== ""
            cursorShape: root.value !== "" ? Qt.PointingHandCursor : Qt.ArrowCursor
            onClicked: backend.copy(root.value, root.title)
        }
        ToolTip.visible: area.containsMouse && root.value !== ""
        ToolTip.delay: 600
        ToolTip.text: "点击复制"
    }
}
