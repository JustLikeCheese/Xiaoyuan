import QtQuick
import QtQuick.Controls

// 鼠标滚轮平滑滚动：每格 180ms 缓出，连续滚动会接着加速；触控板直接跟手。动画跟随屏幕刷新率
Flickable {
    id: flick
    property real target: 0
    readonly property real maxY: Math.max(0, contentHeight - height)

    clip: true
    interactive: false
    boundsBehavior: Flickable.StopAtBounds
    contentWidth: width
    ScrollBar.vertical: ScrollBar { policy: ScrollBar.AsNeeded }

    function clamp(v) {
        return Math.max(0, Math.min(maxY, v))
    }

    NumberAnimation {
        id: anim
        target: flick
        property: "contentY"
        duration: 180
        easing.type: Easing.OutCubic
    }

    WheelHandler {
        acceptedDevices: PointerDevice.Mouse | PointerDevice.TouchPad
        target: null
        onWheel: (event) => {
            if (event.pixelDelta.y !== 0 || event.angleDelta.y % 120 !== 0) {
                anim.stop()
                const d = event.pixelDelta.y !== 0 ? event.pixelDelta.y : event.angleDelta.y / 120 * 100
                flick.contentY = flick.clamp(flick.contentY - d)
                flick.target = flick.contentY
                return
            }
            const base = anim.running ? flick.target : flick.contentY
            flick.target = flick.clamp(base - event.angleDelta.y / 120 * 100)
            anim.from = flick.contentY
            anim.to = flick.target
            anim.restart()
        }
    }
}
