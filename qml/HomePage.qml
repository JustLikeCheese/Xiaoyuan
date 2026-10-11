import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

// 登录页：状态卡片、连接详情（默认折叠）、账号登录
SmoothScrollView {
    id: page
    objectName: "homePage"
    signal navigate(int index)

    property string selectedAccount: ""
    // 登录表单里账号的显示状态：右上角眼睛或账号框自己的小眼睛
    readonly property bool accountsShown: backend.revealed || accountField.shown

    contentHeight: column.implicitHeight + 64

    function accountLabel(a, reveal) {
        return (a.note || (reveal ? a.account : Theme.mask)) + " · " + a.carrier
    }
    function syncSaved() {
        savedBox.currentIndex = savedBox.model.findIndex(x => x.account === selectedAccount)
    }
    function fill(account) {
        const a = backend.accounts.find(x => x.account === account)
        if (!a)
            return
        selectedAccount = a.account
        syncSaved()
        accountField.text = a.account
        passwordField.text = a.password
        const i = backend.carriers.findIndex(c => c.suffix === a.isp)
        ispBox.currentIndex = i >= 0 ? i : 0
        terminalBox.select(a.terminalType)
        if (a.passwordState === "lost")
            ApplicationWindow.window.showToast("warning", "需要重新输入密码", "保存的密码解不开（可能换了电脑或 Windows 用户）", 4000)
    }
    function doLogin() {
        backend.login(accountField.text, passwordField.text, ispBox.currentValue, remember.checked,
                      terminalBox.currentValue)
    }

    Connections {
        target: backend
        function onFillRequested(account) { page.fill(account) }
        function onRevealedChanged() { accountField.shown = backend.revealed }
    }
    Component.onCompleted: fill(backend.lastAccount)

    ColumnLayout {
        id: column
        x: 36
        y: 28
        width: page.width - 72
        spacing: 16

        Label {
            text: "校园网登录"
            font.pixelSize: 28
            font.weight: Font.DemiBold
            color: Theme.text
        }

        // ---------------------------------------------------------------- 门户公告
        Rectangle {
            objectName: "noticeBar"
            visible: backend.noticeText !== ""
            Layout.fillWidth: true
            implicitHeight: noticeRow.implicitHeight + 24
            radius: 8
            color: Qt.rgba(Theme.accent.r, Theme.accent.g, Theme.accent.b, 0.10)
            border.color: Qt.rgba(Theme.accent.r, Theme.accent.g, Theme.accent.b, 0.30)
            RowLayout {
                id: noticeRow
                x: 16
                y: 12
                width: parent.width - 28
                spacing: 12
                FIcon { glyph: Theme.iInfo; size: 16; color: Theme.accent; Layout.alignment: Qt.AlignTop }
                Label {
                    objectName: "noticeText"
                    text: "门户公告：" + backend.noticeText
                    color: Theme.text
                    wrapMode: Text.Wrap
                    maximumLineCount: 6
                    elide: Text.ElideRight
                    Layout.fillWidth: true
                }
                ToolButton {
                    objectName: "noticeClose"
                    flat: true
                    Layout.alignment: Qt.AlignTop
                    contentItem: FIcon { glyph: Theme.iCancel; size: 11 }
                    onClicked: backend.dismissNotice()
                    ToolTip.visible: hovered
                    ToolTip.delay: 600
                    ToolTip.text: "关闭公告"
                }
            }
        }

        // ---------------------------------------------------------------- 状态卡片
        Card {
            id: statusCard
            objectName: "statusCard"
            readonly property var st: backend.status
            readonly property color stateColor: st.state === "online" ? Theme.success
                                              : st.state === "error" ? Theme.danger : Theme.neutral
            Layout.fillWidth: true
            implicitHeight: statusCol.implicitHeight + 32

            ColumnLayout {
                id: statusCol
                x: 24
                y: 16
                width: parent.width - 40
                spacing: 12

                RowLayout {
                    spacing: 16
                    Layout.fillWidth: true
                    Rectangle {
                        width: 56
                        height: 56
                        radius: 28
                        color: Qt.rgba(statusCard.stateColor.r, statusCard.stateColor.g, statusCard.stateColor.b, 0.14)
                        FIcon {
                            anchors.centerIn: parent
                            glyph: statusCard.st.state === "error" ? Theme.iCancel : Theme.iWifi
                            size: 24
                            color: statusCard.stateColor
                        }
                    }
                    ColumnLayout {
                        spacing: 2
                        Layout.fillWidth: true
                        Label {
                            objectName: "stateTitle"
                            text: statusCard.st.title
                            font.pixelSize: 20
                            font.weight: Font.DemiBold
                            color: statusCard.stateColor
                        }
                        Label {
                            objectName: "stateDetail"
                            text: statusCard.st.detail
                            visible: text !== ""
                            font.pixelSize: 12
                            color: Theme.textTertiary
                            wrapMode: Text.Wrap
                            Layout.fillWidth: true
                        }
                    }
                    IconButton {
                        id: switchButton
                        objectName: "switchButton"
                        flat: true
                        glyph: Theme.iSwitchUser
                        text: "切换账号"
                        Layout.alignment: Qt.AlignTop
                        onClicked: switchMenu.popup(switchButton, switchButton.width - switchMenu.width, switchButton.height + 4)
                    }
                    ToolButton {
                        objectName: "refreshButton"
                        flat: true
                        enabled: statusCard.st.state !== "querying"
                        Layout.alignment: Qt.AlignTop
                        contentItem: FIcon { glyph: Theme.iRefresh; size: 15; opacity: parent.enabled ? 1 : 0.4 }
                        onClicked: backend.refreshWithToast()
                        ToolTip.visible: hovered
                        ToolTip.delay: 600
                        ToolTip.text: "刷新状态"
                    }
                }
                Rectangle { height: 1; color: Theme.divider; Layout.fillWidth: true }
                RowLayout {
                    spacing: 16
                    Layout.fillWidth: true
                    InfoField { objectName: "fieldAccount"; title: "当前账号"; value: statusCard.st.account; secret: true }
                    Item { Layout.fillWidth: true }
                    InfoField { objectName: "fieldIp"; title: "IP 地址"; value: statusCard.st.ip; secret: true }
                    Item { Layout.fillWidth: true }
                    InfoField { objectName: "fieldDuration"; title: "已用时长"; value: statusCard.st.duration }
                    Item { Layout.fillWidth: true }
                    InfoField { objectName: "fieldFlow"; title: "已用流量"; value: statusCard.st.flow }
                }
            }
        }

        // ---------------------------------------------------------------- 账号信息（流量余额、套餐等）
        Card {
            id: infoCard
            objectName: "accountInfoCard"
            readonly property var info: backend.accountInfo
            visible: info.shown === true
            Layout.fillWidth: true
            implicitHeight: infoCol.implicitHeight + 32

            ColumnLayout {
                id: infoCol
                x: 24
                y: 16
                width: parent.width - 48
                spacing: 12

                RowLayout {
                    Layout.fillWidth: true
                    Label {
                        text: "账号信息"
                        font.pixelSize: 15
                        font.weight: Font.DemiBold
                        color: Theme.text
                    }
                    Label {
                        objectName: "usageText"
                        visible: infoCard.info.progress >= 0
                        text: (infoCard.info.progressType === 2 ? "时长" : "流量") + "已用 " + Math.round(infoCard.info.progress) + "%"
                              + (infoCard.info.used ? " · 已用 " + infoCard.info.used : "")
                              + (infoCard.info.left ? " · 可用 " + infoCard.info.left : "")
                        font.pixelSize: 12
                        color: Theme.textTertiary
                        elide: Text.ElideRight
                        horizontalAlignment: Text.AlignRight
                        Layout.fillWidth: true
                    }
                }
                ProgressBar {
                    objectName: "usageBar"
                    visible: infoCard.info.progress >= 0
                    from: 0
                    to: 100
                    value: Math.max(0, infoCard.info.progress || 0)
                    Layout.fillWidth: true
                }
                GridLayout {
                    columns: 3
                    columnSpacing: 24
                    rowSpacing: 14
                    Layout.fillWidth: true
                    Repeater {
                        model: infoCard.info.items || []
                        InfoField {
                            required property var modelData
                            objectName: "info_" + modelData.key
                            title: modelData.title
                            value: modelData.value
                            secret: modelData.secret
                            Layout.fillWidth: true
                            Layout.preferredWidth: 1
                        }
                    }
                }
            }
        }

        Menu {
            id: switchMenu
            objectName: "switchMenu"
            Instantiator {
                model: backend.accounts
                delegate: MenuItem {
                    required property var modelData
                    readonly property bool current: backend.status.online
                                                    && backend.status.account.toLowerCase() === modelData.full.toLowerCase()
                    text: page.accountLabel(modelData, backend.revealed) + (current ? "（当前）" : "")
                    enabled: !current
                    onTriggered: backend.switchTo(modelData.account)
                }
                onObjectAdded: (index, object) => switchMenu.insertItem(index, object)
                onObjectRemoved: (index, object) => switchMenu.removeItem(object)
            }
            MenuSeparator {}
            MenuItem {
                text: backend.accounts.length ? "管理账号…" : "还没有保存的账号，去添加…"
                onTriggered: page.navigate(1)
            }
        }

        // ---------------------------------------------------------------- 连接详情
        Card {
            id: detailCard
            objectName: "detailCard"
            property bool expanded: false
            property bool rawShown: false
            Layout.fillWidth: true
            implicitHeight: header.height + body.height
            clip: true

            Item {
                id: header
                width: parent.width
                height: 56
                MouseArea {
                    anchors.fill: parent
                    cursorShape: Qt.PointingHandCursor
                    onClicked: detailCard.expanded = !detailCard.expanded
                }
                Label {
                    x: 24
                    anchors.verticalCenter: parent.verticalCenter
                    text: "连接详情"
                    font.pixelSize: 15
                    font.weight: Font.DemiBold
                    color: Theme.text
                }
                Row {
                    anchors.right: parent.right
                    anchors.rightMargin: 12
                    anchors.verticalCenter: parent.verticalCenter
                    spacing: 4
                    IconButton {
                        objectName: "autoRefreshButton"
                        flat: true
                        checkable: true
                        checked: backend.settingsMap.autoRefresh
                        glyph: Theme.iRefresh
                        text: "自动刷新"
                        onToggled: backend.setAutoRefresh(checked)
                        ToolTip.visible: hovered
                        ToolTip.delay: 600
                        ToolTip.text: "每 " + backend.autoRefreshSeconds + " 秒刷新一次状态和详情"
                    }
                    IconButton {
                        objectName: "rawButton"
                        flat: true
                        visible: detailCard.expanded
                        glyph: Theme.iCode
                        text: detailCard.rawShown ? "收起原始字段" : "原始字段"
                        onClicked: detailCard.rawShown = !detailCard.rawShown
                    }
                    ToolButton {
                        objectName: "expandButton"
                        flat: true
                        contentItem: FIcon {
                            glyph: Theme.iChevronRight
                            size: 12
                            rotation: detailCard.expanded ? 90 : 0
                            Behavior on rotation { NumberAnimation { duration: 180; easing.type: Easing.OutCubic } }
                        }
                        onClicked: detailCard.expanded = !detailCard.expanded
                    }
                }
            }

            Item {
                id: body
                objectName: "detailBody"
                y: header.height
                width: parent.width
                height: detailCard.expanded ? bodyCol.implicitHeight + 41 : 0
                opacity: detailCard.expanded ? 1 : 0
                clip: true
                Behavior on height { NumberAnimation { duration: 220; easing.type: Easing.OutCubic } }
                Behavior on opacity { NumberAnimation { duration: 180 } }

                Rectangle { width: parent.width; height: 1; color: Theme.divider }
                ColumnLayout {
                    id: bodyCol
                    x: 24
                    y: 21
                    width: parent.width - 48
                    spacing: 16

                    GridLayout {
                        columns: 3
                        columnSpacing: 24
                        rowSpacing: 14
                        Layout.fillWidth: true
                        Repeater {
                            model: backend.details
                            InfoField {
                                required property var modelData
                                objectName: "detail_" + modelData.key
                                title: modelData.title
                                value: modelData.value
                                secret: modelData.secret
                                Layout.fillWidth: true
                                Layout.preferredWidth: 1
                            }
                        }
                    }

                    Rectangle {
                        objectName: "rawTable"
                        visible: detailCard.rawShown
                        Layout.fillWidth: true
                        implicitHeight: rawCol.implicitHeight
                        radius: 6
                        color: "transparent"
                        border.color: Theme.divider
                        Column {
                            id: rawCol
                            width: parent.width
                            Row {
                                height: 34
                                Label { width: rawCol.width / 2; leftPadding: 12; height: 34; verticalAlignment: Text.AlignVCenter; text: "字段"; color: Theme.textSecondary }
                                Label { width: rawCol.width / 2; height: 34; verticalAlignment: Text.AlignVCenter; text: "值"; color: Theme.textSecondary }
                            }
                            Repeater {
                                model: backend.rawFields
                                Rectangle {
                                    required property var modelData
                                    width: rawCol.width
                                    height: 30
                                    color: "transparent"
                                    Rectangle { width: parent.width; height: 1; color: Theme.divider }
                                    Label { x: 12; width: rawCol.width / 2 - 12; height: 30; verticalAlignment: Text.AlignVCenter; text: modelData.key; color: Theme.text; elide: Text.ElideRight }
                                    Label {
                                        objectName: "raw_" + modelData.key
                                        x: rawCol.width / 2
                                        width: rawCol.width / 2 - 12
                                        height: 30
                                        verticalAlignment: Text.AlignVCenter
                                        text: modelData.secret && !backend.revealed && modelData.value !== "" ? Theme.mask : modelData.value
                                        color: Theme.text
                                        elide: Text.ElideRight
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // ---------------------------------------------------------------- 账号登录（可折叠，记住折叠状态）
        Card {
            id: loginCard
            objectName: "loginCard"
            readonly property bool expanded: backend.settingsMap.loginExpanded
            function toggle() { backend.setSetting("loginExpanded", !expanded) }
            Layout.fillWidth: true
            implicitHeight: loginHeader.height + loginBody.height
            clip: true

            Item {
                id: loginHeader
                width: parent.width
                height: 52
                MouseArea {
                    anchors.fill: parent
                    cursorShape: Qt.PointingHandCursor
                    onClicked: loginCard.toggle()
                }
                Label {
                    x: 24
                    anchors.verticalCenter: parent.verticalCenter
                    text: "账号登录"
                    font.pixelSize: 15
                    font.weight: Font.DemiBold
                    color: Theme.text
                }
                Row {
                    anchors.right: parent.right
                    anchors.rightMargin: 12
                    anchors.verticalCenter: parent.verticalCenter
                    spacing: 8
                    Label {   // 折叠时也能看到正在登录/注销
                        objectName: "loginBusyLabel"
                        anchors.verticalCenter: parent.verticalCenter
                        visible: !loginCard.expanded && backend.busy
                        text: backend.throttle > 0 ? "排队中，" + backend.throttle + " 秒后发送…" : "正在处理…"
                        font.pixelSize: 12
                        color: Theme.textTertiary
                    }
                    ToolButton {
                        objectName: "loginExpandButton"
                        flat: true
                        contentItem: FIcon {
                            glyph: Theme.iChevronRight
                            size: 12
                            rotation: loginCard.expanded ? 90 : 0
                            Behavior on rotation { NumberAnimation { duration: 180; easing.type: Easing.OutCubic } }
                        }
                        onClicked: loginCard.toggle()
                        ToolTip.visible: hovered
                        ToolTip.delay: 600
                        ToolTip.text: loginCard.expanded ? "收起" : "展开"
                    }
                }
            }

            Item {
                id: loginBody
                objectName: "loginBody"
                y: loginHeader.height
                width: parent.width
                height: loginCard.expanded ? loginCol.implicitHeight : 0
                opacity: loginCard.expanded ? 1 : 0
                clip: true
                Behavior on height { NumberAnimation { duration: 220; easing.type: Easing.OutCubic } }
                Behavior on opacity { NumberAnimation { duration: 180 } }

                ColumnLayout {
                    id: loginCol
                    width: parent.width
                    spacing: 0

                    Rectangle { height: 1; color: Theme.divider; Layout.fillWidth: true }

                    GridLayout {
                        columns: 2
                        columnSpacing: 20
                        rowSpacing: 14
                        Layout.fillWidth: true
                        Layout.margins: 24

                        Label { text: "已保存账号"; color: Theme.text }
                        ComboBox {
                            id: savedBox
                            objectName: "savedBox"
                            Layout.fillWidth: true
                            model: backend.accounts.map(a => ({ account: a.account, label: page.accountLabel(a, page.accountsShown) }))
                            textRole: "label"
                            valueRole: "account"
                            displayText: currentIndex < 0 ? "选择已保存的账号" : currentText
                            onActivated: page.fill(currentValue)
                            onModelChanged: page.syncSaved()
                        }

                        Label { text: "账号"; color: Theme.text }
                        SecretField {
                            id: accountField
                            objectName: "accountField"
                            placeholderText: "账号"
                            Layout.fillWidth: true
                            onAccepted: passwordField.forceActiveFocus()
                        }

                        Label { text: "密码"; color: Theme.text }
                        SecretField {
                            id: passwordField
                            objectName: "passwordField"
                            placeholderText: "密码（初始密码为身份证后六位）"
                            Layout.fillWidth: true
                            onAccepted: page.doLogin()
                        }

                        Label { text: "运营商"; color: Theme.text }
                        ComboBox {
                            id: ispBox
                            objectName: "ispBox"
                            Layout.fillWidth: true
                            model: backend.carriers
                            textRole: "name"
                            valueRole: "suffix"
                        }

                        Label { text: "终端类型"; color: Theme.text }
                        ComboBox {
                            id: terminalBox
                            objectName: "terminalBox"
                            property int chosen: -1   // 记住选的值：全局设置变了，“默认”这一项的文字会跟着变
                            Layout.fillWidth: true
                            model: backend.terminalOptions
                            textRole: "name"
                            valueRole: "value"
                            function select(v) {
                                chosen = v
                                currentIndex = Math.max(0, indexOfValue(v))
                            }
                            onActivated: chosen = currentValue
                            onModelChanged: currentIndex = Math.max(0, indexOfValue(chosen))
                            ToolTip.visible: hovered
                            ToolTip.delay: 600
                            ToolTip.text: "登录时告诉门户本机是什么设备。“默认”跟随设置里的默认终端类型；勾选记住账号时一并保存"
                        }

                        Item { width: 1; height: 1 }
                        CheckBox {
                            id: remember
                            objectName: "rememberBox"
                            text: "记住账号和密码"
                            checked: true
                        }

                        Item { width: 1; height: 1 }
                        Flow {   // 窄窗口时按钮自动换行
                            spacing: 12
                            Layout.fillWidth: true
                            IconButton {
                                objectName: "loginButton"
                                highlighted: true
                                enabled: !backend.busy
                                glyph: Theme.iSend
                                text: "登录"
                                width: 160
                                onClicked: page.doLogin()
                            }
                            IconButton {
                                objectName: "logoutButton"
                                enabled: !backend.busy
                                glyph: Theme.iPower
                                text: "注销"
                                onClicked: backend.logout()
                            }
                        }

                        ProgressBar {
                            indeterminate: true
                            visible: backend.busy
                            Layout.columnSpan: 2
                            Layout.fillWidth: true
                        }
                        Label {
                            objectName: "throttleLabel"
                            visible: backend.busy && backend.throttle > 0
                            text: "门户限制了请求频率，" + backend.throttle + " 秒后发送…"
                            font.pixelSize: 12
                            color: Theme.textTertiary
                            Layout.columnSpan: 2
                        }
                    }
                }
            }
        }
    }
}
