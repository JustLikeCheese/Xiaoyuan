import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import QtQuick.Dialogs
import QtCore

// 导出账号到手机：勾选账号（默认是账号管理里选中的）、设导入码（可自定义，按住小眼睛才显示），
// 显示二维码让手机扫，或保存成 .xyacc 文件发到手机
Dialog {
    id: dlg
    modal: true
    anchors.centerIn: Overlay.overlay
    width: 520
    title: "导出账号到手机"
    standardButtons: Dialog.NoButton

    property var chosen: []          // 勾选的账号
    property string payload: ""      // 加密后的迁移数据
    property var matrix: []          // 二维码，每行一个 "0101…"

    function openDialog(preselected) {
        chosen = (preselected || []).slice()
        codeField.text = backend.newTransferCode()
        payload = ""
        matrix = []
        open()
    }
    function build() {
        payload = backend.exportAccounts(chosen, codeField.text)
        return payload !== ""
    }
    function toggle(account, on) {
        const s = chosen.filter(a => a !== account)
        if (on)
            s.push(account)
        chosen = s
        payload = ""
        matrix = []
    }

    ColumnLayout {
        anchors.fill: parent
        spacing: 10

        Label {
            text: "选要导出的账号（保存的密码一起导出，用导入码加密）"
            color: Theme.textSecondary
            wrapMode: Text.Wrap
            Layout.fillWidth: true
        }
        Rectangle {
            Layout.fillWidth: true
            implicitHeight: Math.min(accountCol.implicitHeight + 8, 180)
            radius: 6
            color: "transparent"
            border.color: Theme.divider
            ScrollView {
                anchors.fill: parent
                anchors.margins: 4
                clip: true
                Column {
                    id: accountCol
                    width: parent.width
                    Repeater {
                        model: backend.accounts
                        CheckBox {
                            required property var modelData
                            objectName: "export_" + modelData.account
                            text: (modelData.note || (backend.revealed ? modelData.account : Theme.mask)) + " · " + modelData.carrier
                                  + (modelData.passwordState === "saved" ? "" : "（没有保存密码）")
                            checked: dlg.chosen.indexOf(modelData.account) >= 0
                            onToggled: dlg.toggle(modelData.account, checked)
                        }
                    }
                }
            }
        }

        Label { text: "导入码"; color: Theme.textSecondary; topPadding: 4 }
        RowLayout {
            Layout.fillWidth: true
            TextField {
                id: codeField
                objectName: "exportCode"
                Layout.fillWidth: true
                placeholderText: "至少 4 位，可以改成自己好记的"
                selectByMouse: true
                echoMode: peek.pressed ? TextInput.Normal : TextInput.Password   // 按住小眼睛才显示
                rightPadding: peek.width + 8
                onTextEdited: { dlg.payload = ""; dlg.matrix = [] }
                ToolButton {
                    id: peek
                    objectName: "exportCodePeek"
                    anchors.right: parent.right
                    anchors.rightMargin: 4
                    anchors.verticalCenter: parent.verticalCenter
                    width: 30
                    height: 26
                    flat: true
                    focusPolicy: Qt.NoFocus
                    contentItem: FIcon { glyph: peek.pressed ? Theme.iHide : Theme.iView; size: 13; color: Theme.textSecondary }
                    ToolTip.visible: hovered && !pressed
                    ToolTip.delay: 600
                    ToolTip.text: "按住显示导入码"
                }
            }
            Button {
                text: "换一个"
                onClicked: { codeField.text = backend.newTransferCode(); dlg.payload = ""; dlg.matrix = [] }
            }
        }
        Label {
            text: "在手机「账号管理」右上角点「导入」，扫码或选文件，再输入这个导入码"
            font.pixelSize: 12
            color: Theme.textTertiary
            wrapMode: Text.Wrap
            Layout.fillWidth: true
        }

        // 二维码：白底黑块，带白边，深色主题下也能扫
        Rectangle {
            objectName: "qrBox"
            visible: dlg.matrix.length > 0
            Layout.alignment: Qt.AlignHCenter
            Layout.topMargin: 6
            implicitWidth: 260
            implicitHeight: 260
            color: "white"
            radius: 6
            Canvas {
                id: qrCanvas
                anchors.fill: parent
                anchors.margins: 6
                onPaint: {
                    const ctx = getContext("2d")
                    ctx.reset()
                    const m = dlg.matrix
                    if (!m.length)
                        return
                    const n = m.length
                    const cell = Math.floor(Math.min(width, height) / n)
                    const off = Math.floor((width - cell * n) / 2)
                    ctx.fillStyle = "white"
                    ctx.fillRect(0, 0, width, height)
                    ctx.fillStyle = "black"
                    for (let y = 0; y < n; y++)
                        for (let x = 0; x < n; x++)
                            if (m[y].charAt(x) === "1")
                                ctx.fillRect(off + x * cell, off + y * cell, cell, cell)
                }
                Connections {
                    target: dlg
                    function onMatrixChanged() { qrCanvas.requestPaint() }
                }
            }
        }
    }

    footer: DialogButtonBox {
        Button {
            objectName: "showQrButton"
            text: "显示二维码"
            highlighted: true
            onClicked: if (dlg.build()) dlg.matrix = backend.qrMatrix(dlg.payload)
        }
        Button {
            text: "保存为文件…"
            onClicked: if (dlg.build()) {
                saveDialog.selectedFile = StandardPaths.writableLocation(StandardPaths.DocumentsLocation) + "/校园网账号迁移.xyacc"
                saveDialog.open()
            }
        }
        Button {
            text: "关闭"
            DialogButtonBox.buttonRole: DialogButtonBox.RejectRole
        }
    }

    FileDialog {
        id: saveDialog
        title: "保存账号迁移文件"
        fileMode: FileDialog.SaveFile
        nameFilters: ["账号迁移文件 (*.xyacc)"]
        defaultSuffix: "xyacc"
        onAccepted: backend.saveTransferFile(dlg.payload, selectedFile)
    }
}
