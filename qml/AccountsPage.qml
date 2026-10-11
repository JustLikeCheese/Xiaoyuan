import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

// 账号管理：表格里的账号、密码跟随右上角眼睛打码
SmoothScrollView {
    id: page
    objectName: "accountsPage"
    property string selected: ""
    readonly property var selectedAcc: backend.accounts.find(a => a.account === selected) ?? null
    readonly property var columns: [0.16, 0.2, 0.14, 0.14, 0.16, 0.2]
    // 当前在线的账号（门户返回的 uid 带运营商后缀，和已保存账号的“账号+后缀”比对）
    readonly property string onlineFull: backend.status.online ? backend.status.account.toLowerCase() : ""
    readonly property var onlineAcc: backend.accounts.find(a => a.full.toLowerCase() === onlineFull) ?? null

    contentHeight: col.implicitHeight + 64

    function title(a) {
        return (a.note || (backend.revealed ? a.account : Theme.mask)) + " · " + a.carrier
    }
    function passwordText(a) {
        if (a.passwordState === "lost")
            return "需重新输入"
        if (a.passwordState === "none")
            return "未保存"
        return backend.revealed ? a.password : Theme.mask
    }

    ColumnLayout {
        id: col
        x: 36
        y: 28
        width: page.width - 72
        spacing: 16

        Label {
            text: "账号管理"
            font.pixelSize: 28
            font.weight: Font.DemiBold
            color: Theme.text
        }
        Label {
            text: "可以保存多个账号，密码用 Windows 加密保存。点窗口右上角的眼睛显示账号和密码"
            font.pixelSize: 12
            color: Theme.textTertiary
            wrapMode: Text.Wrap
            Layout.fillWidth: true
        }

        // 当前登录的账号
        Card {
            objectName: "currentLoginCard"
            Layout.fillWidth: true
            implicitHeight: 60
            RowLayout {
                anchors.fill: parent
                anchors.leftMargin: 18
                anchors.rightMargin: 16
                spacing: 12
                FIcon {
                    glyph: Theme.iWifi
                    size: 18
                    color: page.onlineFull ? Theme.success : Theme.neutral
                }
                Label {
                    text: "当前登录"
                    font.pixelSize: 14
                    color: Theme.text
                }
                Pill {
                    objectName: "currentLoginTag"
                    visible: page.onlineFull !== ""
                    tone: Theme.success
                    text: page.onlineAcc ? page.title(page.onlineAcc)
                                         : (backend.revealed ? backend.status.account : Theme.mask) + " · 未保存"
                }
                Label {
                    objectName: "currentLoginNone"
                    visible: page.onlineFull === ""
                    text: backend.status.state === "querying" ? "正在查询…"
                        : backend.status.state === "error" ? "无法连接门户" : "当前未登录"
                    font.pixelSize: 14
                    color: Theme.textTertiary
                }
                Item { Layout.fillWidth: true }
                IconButton {
                    flat: true
                    visible: page.onlineAcc !== null
                    glyph: Theme.iAccept
                    text: "在表格中选中"
                    onClicked: page.selected = page.onlineAcc.account
                }
            }
        }

        RowLayout {
            spacing: 8
            Layout.fillWidth: true
            IconButton { objectName: "addButton"; highlighted: true; glyph: Theme.iAdd; text: "新增账号"; onClicked: dialog.openFor(null) }
            IconButton { objectName: "editButton"; glyph: Theme.iEdit; text: "编辑"; enabled: page.selectedAcc !== null; onClicked: dialog.openFor(page.selectedAcc) }
            IconButton { objectName: "deleteButton"; glyph: Theme.iDelete; text: "删除"; enabled: page.selectedAcc !== null; onClicked: confirm.open() }
            Item { Layout.fillWidth: true }
            IconButton {
                objectName: "useButton"
                glyph: Theme.iSwitchUser
                text: "切换到此账号"
                enabled: page.selectedAcc !== null && !backend.busy
                onClicked: backend.switchTo(page.selected)
            }
        }

        Card {
            Layout.fillWidth: true
            implicitHeight: table.implicitHeight + 8

            Column {
                id: table
                objectName: "accountTable"
                x: 4
                y: 4
                width: parent.width - 8

                Row {
                    height: 40
                    Repeater {
                        model: ["备注", "账号", "运营商", "终端类型", "密码", "最近使用"]
                        Label {
                            required property string modelData
                            required property int index
                            width: table.width * page.columns[index]
                            height: 40
                            leftPadding: 16
                            verticalAlignment: Text.AlignVCenter
                            text: modelData
                            color: Theme.textSecondary
                        }
                    }
                }
                Rectangle { width: parent.width; height: 1; color: Theme.divider }

                Repeater {
                    model: backend.accounts
                    delegate: Rectangle {
                        id: rowItem
                        required property var modelData
                        readonly property bool isSelected: page.selected === modelData.account
                        readonly property bool isOnline: page.onlineFull !== "" && modelData.full.toLowerCase() === page.onlineFull
                        readonly property var cells: [modelData.note || "-",
                                                      backend.revealed ? modelData.account : Theme.mask,
                                                      modelData.carrier, modelData.terminalName,
                                                      page.passwordText(modelData), modelData.lastUsed]
                        objectName: "row_" + modelData.account
                        width: table.width
                        height: 44
                        radius: 5
                        color: isSelected ? Theme.selected : rowArea.containsMouse ? Theme.hover : "transparent"

                        Rectangle {
                            visible: rowItem.isSelected
                            x: 2
                            width: 3
                            height: 16
                            radius: 1.5
                            anchors.verticalCenter: parent.verticalCenter
                            color: Theme.accent
                        }
                        Row {
                            Repeater {
                                model: rowItem.cells
                                Item {
                                    required property string modelData
                                    required property int index
                                    width: table.width * page.columns[index]
                                    height: 44
                                    Row {
                                        x: 16
                                        width: parent.width - 16
                                        height: parent.height
                                        spacing: 8
                                        Label {
                                            objectName: "cell" + index
                                            width: Math.min(implicitWidth, parent.width - (onlineTag.visible ? onlineTag.width + 8 : 0))
                                            height: parent.height
                                            verticalAlignment: Text.AlignVCenter
                                            text: modelData
                                            color: Theme.text
                                            elide: Text.ElideRight
                                        }
                                        Pill {
                                            id: onlineTag
                                            objectName: "onlineTag"
                                            visible: index === 0 && rowItem.isOnline
                                            anchors.verticalCenter: parent.verticalCenter
                                            text: "在线"
                                        }
                                    }
                                }
                            }
                        }
                        MouseArea {
                            id: rowArea
                            anchors.fill: parent
                            hoverEnabled: true
                            onClicked: page.selected = rowItem.modelData.account
                            onDoubleClicked: {
                                page.selected = rowItem.modelData.account
                                dialog.openFor(rowItem.modelData)
                            }
                        }
                    }
                }

                Label {
                    visible: backend.accounts.length === 0
                    width: parent.width
                    padding: 16
                    text: "还没有保存的账号。点“新增账号”，或在登录页登录成功后自动保存。"
                    color: Theme.textTertiary
                    wrapMode: Text.Wrap
                }
            }
        }
    }

    AccountDialog { id: dialog; objectName: "accountDialog" }

    Dialog {
        id: confirm
        objectName: "confirmDelete"
        modal: true
        anchors.centerIn: Overlay.overlay
        width: 400
        title: "删除账号"
        Label {
            width: parent.width
            wrapMode: Text.Wrap
            text: page.selectedAcc ? "确定删除「" + page.title(page.selectedAcc) + "」？保存的密码也会一起删除。" : ""
        }
        footer: DialogButtonBox {
            Button { text: "删除"; highlighted: true; DialogButtonBox.buttonRole: DialogButtonBox.AcceptRole }
            Button { text: "取消"; DialogButtonBox.buttonRole: DialogButtonBox.RejectRole }
        }
        onAccepted: {
            backend.deleteAccount(page.selected)
            page.selected = ""
        }
    }
}
