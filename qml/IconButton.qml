import QtQuick
import QtQuick.Controls

// 带 Fluent 图标的按钮（图标用系统图标字体，跟文字一样清晰）
Button {
    id: b
    property string glyph
    readonly property color fg: b.highlighted ? (Theme.dark ? "#000000" : "#FFFFFF") : Theme.text

    contentItem: Item {
        implicitWidth: row.implicitWidth
        implicitHeight: row.implicitHeight
        opacity: b.enabled ? 1 : 0.4
        Row {
            id: row
            anchors.centerIn: parent
            spacing: 8
            FIcon {
                glyph: b.glyph
                visible: b.glyph !== ""
                size: 14
                color: b.fg
                height: label.implicitHeight
            }
            Label {
                id: label
                text: b.text
                font: b.font
                color: b.fg
            }
        }
    }
}
