import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

// 新增 / 编辑账号。账号和密码默认跟随右上角眼睛的状态
Dialog {
    id: dlg
    property string oldAccount: ""

    modal: true
    anchors.centerIn: Overlay.overlay
    width: 420
    title: oldAccount ? "编辑账号" : "新增账号"
    standardButtons: Dialog.NoButton

    function openFor(acc) {
        oldAccount = acc ? acc.account : ""
        accountField.text = acc ? acc.account : ""
        passwordField.text = acc ? acc.password : ""
        noteField.text = acc ? acc.note : ""
        const i = backend.carriers.findIndex(c => c.suffix === (acc ? acc.isp : ""))
        ispBox.currentIndex = i >= 0 ? i : 0
        terminalBox.currentIndex = Math.max(0, terminalBox.indexOfValue(acc ? acc.terminalType : -1))
        accountField.shown = backend.revealed
        passwordField.shown = backend.revealed
        open()
        accountField.forceActiveFocus()
    }

    ColumnLayout {
        anchors.fill: parent
        spacing: 6
        Label { text: "账号"; color: Theme.textSecondary }
        SecretField { id: accountField; objectName: "dlgAccount"; placeholderText: "账号"; Layout.fillWidth: true }
        Label { text: "密码"; color: Theme.textSecondary; topPadding: 6 }
        SecretField { id: passwordField; objectName: "dlgPassword"; placeholderText: "密码（可留空，登录时再填）"; Layout.fillWidth: true }
        Label { text: "运营商"; color: Theme.textSecondary; topPadding: 6 }
        ComboBox {
            id: ispBox
            Layout.fillWidth: true
            model: backend.carriers
            textRole: "name"
            valueRole: "suffix"
        }
        Label { text: "终端类型"; color: Theme.textSecondary; topPadding: 6 }
        ComboBox {
            id: terminalBox
            objectName: "dlgTerminal"
            Layout.fillWidth: true
            model: backend.terminalOptions
            textRole: "name"
            valueRole: "value"
        }
        Label { text: "备注"; color: Theme.textSecondary; topPadding: 6 }
        TextField { id: noteField; placeholderText: "可选，例如：室友"; Layout.fillWidth: true }
    }

    footer: DialogButtonBox {
        Button {
            text: "保存"
            highlighted: true
            enabled: accountField.text.trim() !== ""
            DialogButtonBox.buttonRole: DialogButtonBox.AcceptRole
        }
        Button {
            text: "取消"
            DialogButtonBox.buttonRole: DialogButtonBox.RejectRole
        }
    }

    onAccepted: backend.saveAccount(oldAccount, accountField.text, ispBox.currentValue, noteField.text, passwordField.text,
                                    terminalBox.currentValue)
}
