import QtQuick

// 系统自带的 Segoe Fluent Icons 图标（Win10 上回退到 Segoe MDL2 Assets）
Text {
    property string glyph
    property int size: 16
    text: glyph
    font.family: Theme.iconFont
    font.pixelSize: size
    color: Theme.text
    horizontalAlignment: Text.AlignHCenter
    verticalAlignment: Text.AlignVCenter
}
