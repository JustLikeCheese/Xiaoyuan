import QtQuick
import QtQuick.Controls

// 侧边导航的一项。选中指示条由 Main.qml 统一画，切换时会滑过去
AbstractButton {
    id: btn
    property string glyph
    property bool selected: false
    property bool compact: false

    implicitHeight: 40
    hoverEnabled: true

    background: Rectangle {
        radius: 5
        color: btn.selected ? Theme.selected : btn.pressed ? Theme.pressed : btn.hovered ? Theme.hover : "transparent"
        Behavior on color { ColorAnimation { duration: 120 } }
    }
    contentItem: Row {
        leftPadding: 14
        spacing: 14
        FIcon {
            glyph: btn.glyph
            size: 16
            height: btn.height
        }
        Label {
            text: btn.text
            visible: !btn.compact
            font.pixelSize: 14
            color: Theme.text
            height: btn.height
            verticalAlignment: Text.AlignVCenter
        }
    }
    ToolTip.visible: compact && hovered
    ToolTip.delay: 400
    ToolTip.text: text
}
