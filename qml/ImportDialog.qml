import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

// 从手机导出的迁移文件导入账号：先输入导入码解开，再勾选要导入的账号
Dialog {
    id: dlg
    modal: true
    anchors.centerIn: Overlay.overlay
    width: 480
    title: "导入账号"
    standardButtons: Dialog.NoButton

    property string data: ""
    property var items: []       // 解开后的账号
    property var chosen: []

    function openWith(text) {
        data = text
        items = []
        chosen = []
        codeField.text = ""
        open()
        codeField.forceActiveFocus()
    }
    function unlock() {
        items = backend.previewImport(data, codeField.text)
        chosen = items.map(a => a.account)
    }
    function toggle(account, on) {
        const s = chosen.filter(a => a !== account)
        if (on)
            s.push(account)
        chosen = s
    }

    ColumnLayout {
        anchors.fill: parent
        spacing: 10

        Label { text: "导入码"; color: Theme.textSecondary; visible: dlg.items.length === 0 }
        TextField {
            id: codeField
            objectName: "importCode"
            visible: dlg.items.length === 0
            Layout.fillWidth: true
            placeholderText: "导出时显示的导入码"
            selectByMouse: true
            onAccepted: dlg.unlock()
        }

        Label {
            visible: dlg.items.length > 0
            text: "选要导入的账号（已有的会更新）"
            color: Theme.textSecondary
        }
        Repeater {
            model: dlg.items
            CheckBox {
                required property var modelData
                text: modelData.label + (modelData.exists ? "（已有，将更新）" : "")
                      + (modelData.hasPassword ? "" : "（没有密码）")
                checked: dlg.chosen.indexOf(modelData.account) >= 0
                onToggled: dlg.toggle(modelData.account, checked)
            }
        }
    }

    footer: DialogButtonBox {
        Button {
            text: dlg.items.length === 0 ? "解开" : "导入"
            highlighted: true
            enabled: dlg.items.length === 0 ? codeField.text.trim().length >= 4 : dlg.chosen.length > 0
            onClicked: {
                if (dlg.items.length === 0) {
                    dlg.unlock()
                } else {
                    backend.applyImport(dlg.chosen)
                    dlg.close()
                }
            }
        }
        Button {
            text: "取消"
            DialogButtonBox.buttonRole: DialogButtonBox.RejectRole
        }
    }
}
