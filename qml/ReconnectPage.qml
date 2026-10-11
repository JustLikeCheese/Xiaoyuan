import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

// 自动重连：网络变化时立即检测、平时按兜底间隔检测，掉线后自动用保存的账号重新登录
SmoothScrollView {
    id: page
    objectName: "reconnectPage"
    readonly property var r: backend.reconnect
    readonly property color stateColor: r.state === "watching" ? Theme.success
                                      : r.state === "reconnecting" ? Theme.accent
                                      : r.state === "paused" ? Theme.warning : Theme.neutral
    // 可选的重连账号：最近使用 + 保存了密码的账号
    readonly property var accountOptions: [{ label: "最近使用的账号", value: "" }].concat(
        backend.accounts.filter(a => a.passwordState === "saved").map(a => ({
            label: (a.note || (backend.revealed ? a.account : Theme.mask)) + " · " + a.carrier,
            value: a.account
        })))

    contentHeight: col.implicitHeight + 64

    // 检测间隔的单位：秒 / 分钟 / 小时
    readonly property var units: [{ t: "秒", v: 1 }, { t: "分钟", v: 60 }, { t: "小时", v: 3600 }]
    function unitFor(s) {
        return s % 3600 === 0 ? 3600 : s % 60 === 0 ? 60 : 1
    }
    function lastStateText(s) {
        return s === "online" ? "在线" : s === "offline" ? "离线" : s === "unreachable" ? "连不上门户" : ""
    }

    ColumnLayout {
        id: col
        x: 36
        y: 28
        width: page.width - 72
        spacing: 6

        Label {
            text: "自动重连"
            font.pixelSize: 28
            font.weight: Font.DemiBold
            color: Theme.text
        }
        Label {
            text: "网络断开、恢复或被门户拦截时立即检测，平时按兜底间隔检测；掉线后自动用保存的账号重新登录"
            font.pixelSize: 12
            color: Theme.textTertiary
            bottomPadding: 10
            wrapMode: Text.Wrap
            Layout.fillWidth: true
        }

        // ---------------------------------------------------------------- 状态
        Card {
            objectName: "reconnectStatus"
            Layout.fillWidth: true
            implicitHeight: statusRow.implicitHeight + 36
            RowLayout {
                id: statusRow
                x: 24
                y: 18
                width: parent.width - 44
                spacing: 16
                Rectangle {
                    width: 56
                    height: 56
                    radius: 28
                    color: Qt.rgba(page.stateColor.r, page.stateColor.g, page.stateColor.b, 0.14)
                    FIcon {
                        id: syncIcon
                        anchors.centerIn: parent
                        glyph: page.r.state === "paused" ? Theme.iPause : Theme.iSync
                        size: 22
                        color: page.stateColor
                        RotationAnimator on rotation {
                            running: page.r.state === "reconnecting"
                            from: 0
                            to: 360
                            duration: 1000
                            loops: Animation.Infinite
                            onRunningChanged: if (!running) syncIcon.rotation = 0
                        }
                    }
                }
                ColumnLayout {
                    spacing: 2
                    Layout.fillWidth: true
                    Label {
                        objectName: "reconnectState"
                        text: page.r.state === "watching" ? "监控中" : page.r.state === "reconnecting" ? "正在重连…"
                            : page.r.state === "paused" ? "已暂停" : "未开启"
                        font.pixelSize: 20
                        font.weight: Font.DemiBold
                        color: page.stateColor
                    }
                    Label {
                        text: page.r.state === "off" ? "开启后，掉线时会自动用保存的账号重新登录"
                            : page.r.state === "paused" ? "手动注销或连续失败后暂停，再次登录或点“恢复”继续"
                            : (page.r.networkEvents ? "网络变化时立即检测，另外每 " : "每 ")
                              + page.r.intervalText + "兜底检测一次"
                              + (page.r.lastCheck ? " · 上次检测 " + page.r.lastCheck + "（" + page.lastStateText(page.r.lastState) + "）" : "")
                        font.pixelSize: 12
                        color: Theme.textTertiary
                        wrapMode: Text.Wrap
                        Layout.fillWidth: true
                    }
                }
                InfoField { title: "已自动重连"; value: page.r.count + " 次"; Layout.alignment: Qt.AlignVCenter }
                IconButton {
                    objectName: "resumeButton"
                    visible: page.r.state === "paused"
                    highlighted: true
                    glyph: Theme.iPlay
                    text: "恢复"
                    onClicked: backend.resumeReconnect()
                }
            }
        }

        // ---------------------------------------------------------------- 设置
        GroupTitle { text: "设置" }
        SettingRow {
            glyph: Theme.iSync
            title: "开启自动重连"
            description: "检测到掉线后自动重新登录；手动注销后会暂停，再次登录后自动恢复"
            Switch {
                objectName: "reconnectSwitch"
                checked: page.r.enabled
                text: checked ? "开" : "关"
                onToggled: backend.setReconnectEnabled(checked)
            }
        }
        SettingRow {
            glyph: Theme.iTimer
            title: "兜底检测间隔"
            description: page.r.networkEvents ? "网络变化时会立即检测，这里只是兜底，可以设长些；最短 5 秒"
                                              : "系统没有网络变化通知，全靠这个间隔检测；最短 5 秒"
            SpinBox {
                id: intervalSpin
                objectName: "intervalSpin"
                width: 130
                from: 1
                to: 9999
                editable: true
                function sync() {
                    const u = page.unitFor(page.r.interval)
                    unitBox.currentIndex = unitBox.indexOfValue(u)
                    value = page.r.interval / u
                }
                Component.onCompleted: sync()
                onActiveFocusChanged: if (!activeFocus) sync()
                onValueModified: backend.setReconnectInterval(value * unitBox.currentValue)
            }
            ComboBox {
                id: unitBox
                objectName: "intervalUnitBox"
                width: 96
                model: page.units
                textRole: "t"
                valueRole: "v"
                onActivated: backend.setReconnectInterval(intervalSpin.value * currentValue)
            }
            Connections {
                target: backend
                // 后端把值夹到 5 秒~24 小时，或换算成更大的单位后，输入框跟着更新
                function onReconnectChanged() {
                    if (!intervalSpin.activeFocus)
                        intervalSpin.sync()
                }
            }
        }
        SettingRow {
            glyph: Theme.iPeople
            title: "重连使用的账号"
            description: "只能选保存了密码的账号；连续失败 3 次会暂停并提醒"
            ComboBox {
                id: accountBox
                objectName: "reconnectAccountBox"
                width: 240
                model: page.accountOptions
                textRole: "label"
                valueRole: "value"
                function sync() {
                    const i = indexOfValue(page.r.account)
                    currentIndex = i >= 0 ? i : 0
                }
                Component.onCompleted: sync()
                onModelChanged: sync()
                onActivated: backend.setReconnectAccount(currentValue)
            }
        }

        // ---------------------------------------------------------------- 记录
        RowLayout {
            Layout.fillWidth: true
            GroupTitle { text: "重连记录" }
            Item { Layout.fillWidth: true }
            IconButton {
                flat: true
                glyph: Theme.iDelete
                text: "清空记录"
                enabled: backend.reconnectHistory.length > 0
                onClicked: backend.clearReconnectHistory()
                Layout.alignment: Qt.AlignBottom
            }
        }
        Card {
            objectName: "reconnectHistory"
            Layout.fillWidth: true
            implicitHeight: historyCol.implicitHeight + 8
            Column {
                id: historyCol
                x: 4
                y: 4
                width: parent.width - 8
                Repeater {
                    model: backend.reconnectHistory
                    Item {
                        required property var modelData
                        required property int index
                        width: historyCol.width
                        height: 40
                        Rectangle {
                            visible: index > 0
                            x: 16
                            width: parent.width - 32
                            height: 1
                            color: Theme.divider
                        }
                        Row {
                            x: 16
                            height: parent.height
                            spacing: 12
                            FIcon {
                                anchors.verticalCenter: parent.verticalCenter
                                glyph: modelData.kind === "success" ? Theme.iSuccess : modelData.kind === "error" ? Theme.iError
                                     : modelData.kind === "warning" ? Theme.iWarning : Theme.iInfo
                                color: modelData.kind === "success" ? Theme.success : modelData.kind === "error" ? Theme.danger
                                     : modelData.kind === "warning" ? Theme.warning : Theme.accent
                                size: 14
                            }
                            Label {
                                anchors.verticalCenter: parent.verticalCenter
                                text: modelData.time
                                font.pixelSize: 12
                                color: Theme.textTertiary
                                width: 110
                            }
                            Label {
                                objectName: "historyText"
                                anchors.verticalCenter: parent.verticalCenter
                                text: modelData.text
                                color: Theme.text
                                width: historyCol.width - 16 - 14 - 110 - 24 - 16
                                elide: Text.ElideRight
                            }
                        }
                    }
                }
                Label {
                    visible: backend.reconnectHistory.length === 0
                    width: parent.width
                    padding: 16
                    text: "还没有记录。开启自动重连后，检测结果和每次重连都会记在这里。"
                    color: Theme.textTertiary
                    wrapMode: Text.Wrap
                }
            }
        }
    }
}
