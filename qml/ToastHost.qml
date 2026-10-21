import QtQuick
import QtQuick.Controls

// 右上角的提示条：从右侧滑入，到时间自动消失
Column {
    id: host
    spacing: 8
    width: 380

    function show(kind, title, content, duration) {
        model.append({ kind: kind, title: title, content: content, duration: duration })
        if (model.count > 4)
            model.remove(0)
    }

    ListModel { id: model }

    Repeater {
        model: model
        delegate: Rectangle {
            id: toast
            required property int index
            required property string kind
            required property string title
            required property string content
            required property int duration
            readonly property color accentColor: kind === "success" ? Theme.success
                                               : kind === "error" || kind === "offline" ? Theme.danger
                                               : kind === "warning" ? Theme.warning : Theme.accent

            objectName: "toast"
            width: host.width
            height: row.implicitHeight + 24
            radius: 8
            color: kind === "success" ? Theme.successBg : kind === "error" || kind === "offline" ? Theme.dangerBg
                 : kind === "warning" ? Theme.warningBg : Theme.infoBg
            border.color: Theme.cardBorder
            opacity: 0
            transform: Translate { id: shift; x: 60 }

            Component.onCompleted: enter.start()
            ParallelAnimation {
                id: enter
                NumberAnimation { target: toast; property: "opacity"; to: 1; duration: 200; easing.type: Easing.OutCubic }
                NumberAnimation { target: shift; property: "x"; to: 0; duration: 260; easing.type: Easing.OutCubic }
            }
            SequentialAnimation {
                id: leave
                ParallelAnimation {
                    NumberAnimation { target: toast; property: "opacity"; to: 0; duration: 160 }
                    NumberAnimation { target: shift; property: "x"; to: 40; duration: 160; easing.type: Easing.InCubic }
                }
                ScriptAction { script: { if (toast.index >= 0 && toast.index < model.count) model.remove(toast.index) } }
            }
            Timer { interval: toast.duration; running: true; onTriggered: leave.start() }

            Row {
                id: row
                x: 14
                y: 12
                width: parent.width - 28
                spacing: 12
                FIcon {
                    glyph: toast.kind === "success" ? Theme.iSuccess : toast.kind === "error" ? Theme.iError
                         : toast.kind === "offline" ? Theme.iPower
                         : toast.kind === "warning" ? Theme.iWarning : Theme.iInfo
                    color: toast.accentColor
                    size: 16
                    height: titleText.implicitHeight
                }
                Column {
                    width: parent.width - 16 - 12 - 28 - 12
                    spacing: 2
                    Label {
                        id: titleText
                        objectName: "toastTitle"
                        text: toast.title
                        font.pixelSize: 14
                        font.weight: Font.DemiBold
                        color: Theme.text
                        width: parent.width
                        wrapMode: Text.Wrap
                    }
                    Label {
                        objectName: "toastContent"
                        text: toast.content
                        visible: text !== ""
                        font.pixelSize: 13
                        color: Theme.textSecondary
                        width: parent.width
                        wrapMode: Text.Wrap
                    }
                }
                ToolButton {
                    width: 28
                    height: 28
                    flat: true
                    contentItem: FIcon { glyph: Theme.iCancel; size: 10; color: Theme.textSecondary }
                    onClicked: leave.start()
                }
            }
        }
    }
}
