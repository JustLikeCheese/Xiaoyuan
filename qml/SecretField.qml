import QtQuick
import QtQuick.Controls

// 默认打码的输入框：点一下小眼睛显示，再点一下隐藏
TextField {
    id: field
    property bool shown: false

    echoMode: shown ? TextInput.Normal : TextInput.Password
    rightPadding: eye.width + 8
    selectByMouse: true

    ToolButton {
        id: eye
        objectName: "eye"
        anchors.right: parent.right
        anchors.rightMargin: 4
        anchors.verticalCenter: parent.verticalCenter
        width: 30
        height: 26
        flat: true
        focusPolicy: Qt.NoFocus
        contentItem: FIcon { glyph: field.shown ? Theme.iHide : Theme.iView; size: 13; color: Theme.textSecondary }
        onClicked: field.shown = !field.shown
        ToolTip.visible: hovered
        ToolTip.delay: 600
        ToolTip.text: field.shown ? "隐藏" : "显示"
    }
}
