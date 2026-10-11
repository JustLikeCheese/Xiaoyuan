import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import QtQuick.Dialogs
import QtCore

SmoothScrollView {
    id: page
    objectName: "settingsPage"
    readonly property var s: backend.settingsMap
    readonly property var logs: backend.logInfo

    // 切到设置页时更新日志占用
    onVisibleChanged: if (visible) backend.refreshLogInfo()

    contentHeight: col.implicitHeight + 64

    ColumnLayout {
        id: col
        x: 36
        y: 28
        width: page.width - 72
        spacing: 6

        Label {
            text: "设置"
            font.pixelSize: 28
            font.weight: Font.DemiBold
            color: Theme.text
        }
        Label {
            text: "登录行为、门户地址和外观"
            font.pixelSize: 12
            color: Theme.textTertiary
            bottomPadding: 4
        }

        GroupTitle { text: "登录" }
        SettingRow {
            glyph: Theme.iPlay
            title: "启动时自动登录"
            description: "打开软件时如果没在线，自动用最近使用的账号登录"
            Switch {
                objectName: "autoLoginSwitch"
                checked: page.s.autoLogin
                text: checked ? "开" : "关"
                onToggled: backend.setSetting("autoLogin", checked)
            }
        }
        SettingRow {
            glyph: Theme.iPower
            title: "自动登录成功后关闭窗口"
            description: "双击打开、登录完自动退出"
            Switch {
                objectName: "closeAfterSwitch"
                checked: page.s.closeAfterAutoLogin
                text: checked ? "开" : "关"
                onToggled: backend.setSetting("closeAfterAutoLogin", checked)
            }
        }

        GroupTitle { text: "门户" }
        SettingRow {
            glyph: Theme.iGlobe
            title: "门户地址"
            description: "默认 " + backend.defaultHost + "，一般不需要修改"
            TextField {
                objectName: "portalField"
                width: 240
                text: page.s.portalHost
                selectByMouse: true
                onEditingFinished: backend.setSetting("portalHost", text)
            }
        }
        SettingRow {
            glyph: Theme.iCode
            title: "默认终端类型"
            description: "全局设置：登录页和账号里终端类型选“默认”时用这里的值（terminal_type）。选手机时账号前缀为 ,1,"
            ComboBox {
                objectName: "terminalTypeBox"
                width: 160
                model: backend.terminalTypes
                textRole: "name"
                valueRole: "value"
                Component.onCompleted: currentIndex = Math.max(0, indexOfValue(page.s.terminalType))
                onActivated: backend.setSetting("terminalType", currentValue)
            }
        }
        SettingRow {
            glyph: Theme.iOpen
            title: "自助服务平台"
            description: "修改密码、查看账单和在线设备"
            Button { text: "打开"; onClicked: backend.openSelfService() }
        }

        GroupTitle { text: "个性化" }
        SettingRow {
            glyph: Theme.iColor
            title: "应用主题"
            description: "浅色、深色或跟随系统"
            ComboBox {
                objectName: "themeBox"
                width: 160
                model: [{ t: "浅色", v: "Light" }, { t: "深色", v: "Dark" }, { t: "跟随系统", v: "Auto" }]
                textRole: "t"
                valueRole: "v"
                Component.onCompleted: currentIndex = indexOfValue(page.s.theme)
                onActivated: backend.setSetting("theme", currentValue)
            }
        }
        SettingRow {
            glyph: Theme.iFont
            title: "界面字体"
            description: "修改后重启软件生效"
            ComboBox {
                objectName: "fontBox"
                width: 240
                model: backend.fonts
                textRole: "label"
                valueRole: "value"
                Component.onCompleted: currentIndex = Math.max(0, indexOfValue(page.s.font))
                onActivated: backend.setSetting("font", currentValue)
            }
        }

        GroupTitle { text: "日志" }
        SettingRow {
            objectName: "logRow"
            glyph: Theme.iDocument
            title: "导出日志"
            description: page.logs.files + " 个文件，共 " + page.logs.sizeText + "；超过一天的自动压缩，保留 "
                         + page.logs.keepDays + " 天"
            IconButton {
                objectName: "logExport"
                glyph: Theme.iExport
                text: "导出"
                ToolTip.visible: hovered
                ToolTip.delay: 400
                ToolTip.text: "合并成一个文本文件。右上角眼睛关着时，账号、IP、MAC 会打码；密码始终不写进日志"
                onClicked: {
                    const stamp = new Date().toLocaleString(Qt.locale(), "yyyyMMdd-HHmm")
                    exportDialog.selectedFile = StandardPaths.writableLocation(StandardPaths.DocumentsLocation)
                                                + "/校园网登录日志-" + stamp + ".log"
                    exportDialog.open()
                }
            }
        }

        GroupTitle { text: "关于" }
        SettingRow {
            glyph: Theme.iFolder
            title: "数据目录"
            description: backend.appDir
            Button { text: "打开目录"; onClicked: backend.openDataDir() }
        }
        SettingRow {
            objectName: "aboutRow"
            glyph: Theme.iInfo
            title: backend.appTitle
            description: "作者 " + backend.author + " · 开源许可 " + backend.license
        }
        SettingRow {
            objectName: "projectRow"
            glyph: Theme.iCode
            title: "GitHub"
            description: backend.projectUrl
            IconButton {
                flat: true
                glyph: Theme.iOpen
                text: "复制"
                onClicked: backend.copy(backend.projectUrl, "项目地址")
            }
            Button { text: "打开"; onClicked: backend.openProjectPage() }
        }
    }

    FileDialog {
        id: exportDialog
        title: "导出日志"
        fileMode: FileDialog.SaveFile
        nameFilters: ["日志文件 (*.log)", "文本文件 (*.txt)"]
        defaultSuffix: "log"
        onAccepted: backend.exportLog(selectedFile)
    }
}
