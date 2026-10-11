import QtQuick
import QtQuick.Controls

ApplicationWindow {
    id: win
    objectName: "mainWindow"
    width: 1000
    height: 720
    minimumWidth: 760
    minimumHeight: 560
    visible: true
    title: backend.appTitle
    color: Theme.windowBg

    property int current: 0
    readonly property bool compact: width < 900
    readonly property var pageItems: [homePage, accountsPage, reconnectPage, settingsPage]
    readonly property var navButtons: [navHome, navAccounts, navReconnect, navSettings]

    function showToast(kind, title, content, duration) {
        toasts.show(kind, title, content, duration)
    }

    // 切页：新页面从下方 24px 滑入并淡入，220ms
    function go(i) {
        if (i === current)
            return
        pageItems[current].visible = false
        current = i
        const p = pageItems[i]
        p.y = 24
        p.opacity = 0
        p.visible = true
        slideY.target = p
        fadeIn.target = p
        pageAnim.restart()
    }

    Component.onCompleted: {
        Theme.accent = Qt.binding(() => win.palette.accent)
        backend.startup()
    }

    Connections {
        target: backend
        function onToast(kind, title, content, duration) { toasts.show(kind, title, content, duration) }
        function onPageRequested(index) { win.go(index) }
        function onCloseRequested() { win.close() }
    }

    ParallelAnimation {
        id: pageAnim
        NumberAnimation { id: slideY; property: "y"; to: 0; duration: 220; easing.type: Easing.OutCubic }
        NumberAnimation { id: fadeIn; property: "opacity"; to: 1; duration: 180; easing.type: Easing.OutCubic }
    }

    // ---------------------------------------------------------------- 侧边导航
    Item {
        id: nav
        width: win.compact ? 64 : 220
        height: parent.height
        Behavior on width { NumberAnimation { duration: 200; easing.type: Easing.OutCubic } }

        Column {
            id: navTop
            x: 8
            y: 12
            width: parent.width - 16
            spacing: 4
            NavButton { id: navHome; objectName: "navHome"; width: parent.width; glyph: Theme.iHome; text: "登录"; compact: win.compact; selected: win.current === 0; onClicked: win.go(0) }
            NavButton { id: navAccounts; objectName: "navAccounts"; width: parent.width; glyph: Theme.iPeople; text: "账号管理"; compact: win.compact; selected: win.current === 1; onClicked: win.go(1) }
            NavButton { id: navReconnect; objectName: "navReconnect"; width: parent.width; glyph: Theme.iSync; text: "自动重连"; compact: win.compact; selected: win.current === 2; onClicked: win.go(2) }
        }
        NavButton {
            id: navSettings
            objectName: "navSettings"
            x: 8
            width: parent.width - 16
            anchors.bottom: parent.bottom
            anchors.bottomMargin: 12
            glyph: Theme.iSettings
            text: "设置"
            compact: win.compact
            selected: win.current === 3
            onClicked: win.go(3)
        }

        // 选中指示条：切换时滑到新的项
        Rectangle {
            id: indicator
            readonly property Item target: win.navButtons[win.current]
            x: 10
            width: 3
            height: 16
            radius: 1.5
            color: Theme.accent
            y: target.mapToItem(nav, 0, 0).y + (target.height - height) / 2
            Behavior on y { NumberAnimation { duration: 250; easing.type: Easing.OutCubic } }
        }
    }

    // ---------------------------------------------------------------- 页面
    Item {
        id: pages
        anchors.left: nav.right
        anchors.right: parent.right
        anchors.top: parent.top
        anchors.bottom: parent.bottom
        clip: true

        HomePage { id: homePage; width: pages.width; height: pages.height; onNavigate: (i) => win.go(i) }
        AccountsPage { id: accountsPage; width: pages.width; height: pages.height; visible: false }
        ReconnectPage { id: reconnectPage; width: pages.width; height: pages.height; visible: false }
        SettingsPage { id: settingsPage; width: pages.width; height: pages.height; visible: false }
    }

    // ---------------------------------------------------------------- 右上角：显示/隐藏敏感信息
    ToolButton {
        id: privacyButton
        objectName: "privacyButton"
        anchors.top: parent.top
        anchors.right: parent.right
        anchors.topMargin: 10
        anchors.rightMargin: 14
        width: 40
        height: 36
        flat: true
        contentItem: FIcon { glyph: backend.revealed ? Theme.iHide : Theme.iView; size: 16 }
        onClicked: backend.setRevealed(!backend.revealed)
        ToolTip.visible: hovered
        ToolTip.delay: 400
        ToolTip.text: backend.revealed ? "隐藏敏感信息" : "显示敏感信息（账号、实名、密码、IP、MAC）"
    }

    ToastHost {
        id: toasts
        objectName: "toastHost"
        z: 100
        anchors.top: parent.top
        anchors.topMargin: 54
        anchors.right: parent.right
        anchors.rightMargin: 16
    }
}
