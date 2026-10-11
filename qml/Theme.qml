pragma Singleton
import QtQuick

// Win11 Fluent 配色和字号。深浅色跟随 styleHints（设置里可强制浅色/深色/跟随系统）
QtObject {
    readonly property bool dark: Qt.styleHints.colorScheme === Qt.ColorScheme.Dark
    property color accent: dark ? "#60CDFF" : "#005FB8"

    readonly property color windowBg: dark ? "#202020" : "#F3F3F3"
    readonly property color card: dark ? "#2B2B2B" : "#FBFBFB"
    readonly property color cardBorder: dark ? "#1D1D1D" : "#E5E5E5"
    readonly property color divider: dark ? "#3A3A3A" : "#E8E8E8"
    readonly property color hover: dark ? Qt.rgba(1, 1, 1, 0.06) : Qt.rgba(0, 0, 0, 0.04)
    readonly property color pressed: dark ? Qt.rgba(1, 1, 1, 0.04) : Qt.rgba(0, 0, 0, 0.02)
    readonly property color selected: dark ? Qt.rgba(1, 1, 1, 0.08) : Qt.rgba(0, 0, 0, 0.055)

    readonly property color text: dark ? "#FFFFFF" : "#1B1B1B"
    readonly property color textSecondary: dark ? "#CFCFCF" : "#5F5F5F"
    readonly property color textTertiary: dark ? "#9E9E9E" : "#8A8A8A"

    readonly property color success: dark ? "#6CCB5F" : "#0F7B0F"
    readonly property color danger: dark ? "#FF99A4" : "#C42B1C"
    readonly property color warning: dark ? "#FCE100" : "#9D5D00"
    readonly property color neutral: dark ? "#AAAAAA" : "#707070"

    readonly property color successBg: dark ? "#393D1B" : "#DFF6DD"
    readonly property color dangerBg: dark ? "#442726" : "#FDE7E9"
    readonly property color warningBg: dark ? "#433519" : "#FFF4CE"
    readonly property color infoBg: dark ? "#2B2B2B" : "#F6F6F6"

    readonly property string iconFont: "Segoe Fluent Icons"
    readonly property string mask: "••••••••"   // 固定长度，不暴露账号、密码的位数

    // Segoe Fluent Icons 码位
    readonly property string iHome: ""
    readonly property string iPeople: ""
    readonly property string iSettings: ""
    readonly property string iView: ""
    readonly property string iHide: ""
    readonly property string iRefresh: ""
    readonly property string iWifi: ""
    readonly property string iCancel: ""
    readonly property string iAccept: ""
    readonly property string iChevronRight: ""
    readonly property string iAdd: ""
    readonly property string iEdit: ""
    readonly property string iDelete: ""
    readonly property string iSend: ""
    readonly property string iCode: ""
    readonly property string iFont: ""
    readonly property string iGlobe: ""
    readonly property string iOpen: ""
    readonly property string iFolder: ""
    readonly property string iInfo: ""
    readonly property string iPlay: ""
    readonly property string iPower: ""
    readonly property string iColor: ""
    readonly property string iSwitchUser: ""
    readonly property string iSuccess: ""
    readonly property string iError: ""
    readonly property string iWarning: ""
    readonly property string iSync: ""
    readonly property string iDrive: ""
    readonly property string iExport: ""
    readonly property string iSearch: ""
    readonly property string iPause: ""
    readonly property string iHistory: ""
    readonly property string iDocument: ""
    readonly property string iTimer: ""

    function masked(text, secret, reveal) {
        if (!text)
            return "-"
        return secret && !reveal ? mask : text
    }
}
