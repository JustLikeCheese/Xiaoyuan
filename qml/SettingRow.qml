import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

// 设置页的一行：图标、标题、说明，右侧放控件（直接写在 SettingRow { ... } 里）
Card {
    id: row
    property string glyph
    property string title
    property string description
    default property alias control: slot.data

    Layout.fillWidth: true
    implicitHeight: Math.max(68, content.implicitHeight + 24)

    RowLayout {
        id: content
        anchors.fill: parent
        anchors.leftMargin: 18
        anchors.rightMargin: 16
        spacing: 16

        FIcon { glyph: row.glyph; size: 18; Layout.preferredWidth: 22 }
        ColumnLayout {
            spacing: 2
            Layout.fillWidth: true
            Label {
                text: row.title
                font.pixelSize: 14
                color: Theme.text
                Layout.fillWidth: true
                elide: Text.ElideRight
            }
            Label {
                text: row.description
                visible: text !== ""
                font.pixelSize: 12
                color: Theme.textTertiary
                Layout.fillWidth: true
                elide: Text.ElideMiddle
            }
        }
        Row {
            id: slot
            spacing: 8
            Layout.alignment: Qt.AlignVCenter
        }
    }
}
