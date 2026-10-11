"""真实屏幕上的帧率和外观检查（窗口置顶显示约 15 秒，但不抢键盘焦点）：python tests/perf_check.py

连本地假门户，测切页、滚动、展开详情、提示条、侧边栏的实际出帧率，并截浅色、深色两张图到 tests/shots/。
"""

from __future__ import annotations

import os
import statistics
import sys
import tempfile
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from tests.mock_portal import GOOD_PASSWORD, MockPortal  # noqa: E402

mock = MockPortal(release_delay=0.3).start()
home_dir = tempfile.mkdtemp(prefix="xiaoyuan-perf-")
os.environ["XIAOYUAN_HOME"] = home_dir
os.environ["XIAOYUAN_PORTAL"] = mock.base

from PySide6.QtCore import QEventLoop, QObject, QPoint, QPointF, Qt, QTimer, QUrl  # noqa: E402
from PySide6.QtGui import QGuiApplication, QWheelEvent  # noqa: E402
from PySide6.QtQml import QQmlApplicationEngine  # noqa: E402

import main  # noqa: E402
from portal import enable_log  # noqa: E402
from backend import Backend, Settings, apply_theme, resource_dir  # noqa: E402
from store import Account, AccountStore  # noqa: E402

SHOTS = ROOT / "tests" / "shots"
SHOTS.mkdir(exist_ok=True)

store = AccountStore(Path(home_dir) / "accounts.json")
store.upsert(Account("2002010", "@telecom", "我的号", GOOD_PASSWORD), touch=True)
store.upsert(Account("2002011", "", "室友", GOOD_PASSWORD))

main.prepare()
enable_log(Path(home_dir) / "logs" / "portal.log")
app = QGuiApplication(sys.argv)
settings = Settings()
main.setup_fonts(app, settings)
apply_theme("Light")
backend = Backend(store, settings)
engine = QQmlApplicationEngine()
engine.rootContext().setContextProperty("backend", backend)
engine.load(QUrl.fromLocalFile(str(resource_dir() / "qml" / "Main.qml")))
win = engine.rootObjects()[0]
# 置顶避免被遮挡后被系统降频；不接受焦点，不打断正在打字的用户
win.setFlags(win.flags() | Qt.WindowStaysOnTopHint | Qt.WindowDoesNotAcceptFocus)
win.show()

frames: list[float] = []
win.frameSwapped.connect(lambda: frames.append(time.perf_counter()))


def wait(ms: int) -> None:
    loop = QEventLoop()
    QTimer.singleShot(ms, loop.quit)
    loop.exec()


def obj(name: str) -> QObject:
    o = win.findChild(QObject, name)
    if o is None:
        raise LookupError(name)
    return o


def measure(name: str, action, window_ms: int) -> None:
    frames.clear()
    action()
    wait(window_ms + 150)
    fs = [f for f in frames if f <= frames[0] + window_ms / 1000] if frames else []
    if len(fs) < 3:
        print(f"  {name:<10} 帧数不足（{len(fs)}）")
        return
    gaps = sorted((b - a) * 1000 for a, b in zip(fs, fs[1:]))
    print(f"  {name:<10} {len(fs) / (fs[-1] - fs[0]):6.1f} 帧/秒   帧间隔 中位 {gaps[len(gaps) // 2]:4.1f}ms  "
          f"最慢 {gaps[-1]:5.1f}ms")


def wheel_down() -> None:
    page = obj("homePage")
    pos = QPointF(win.width() / 2, win.height() / 2)
    for _ in range(3):
        ev = QWheelEvent(pos, QPointF(win.mapToGlobal(pos.toPoint())), QPoint(0, 0), QPoint(0, -120),
                         Qt.NoButton, Qt.NoModifier, Qt.NoScrollPhase, False)
        QGuiApplication.sendEvent(win, ev)
    _ = page


def shot(name: str) -> None:
    wait(400)
    g = win.frameGeometry()
    win.screen().grabWindow(0, g.x(), g.y(), g.width(), g.height()).save(str(SHOTS / name))


wait(1500)
backend.login("2002010", GOOD_PASSWORD, "@telecom", True)
wait(1500)
print(f"屏幕 {win.screen().refreshRate():.0f}Hz，缩放 {win.devicePixelRatio()}，渲染 {win.rendererInterface().graphicsApi().name}")
shot("live_light.png")

measure("切页", lambda: win.go(1), 230)
wait(300)
measure("切回", lambda: win.go(0), 230)
wait(300)
detail = obj("detailCard")
measure("展开详情", lambda: detail.setProperty("expanded", True), 230)
wait(300)
measure("滚轮滚动", wheel_down, 200)
wait(400)
measure("提示条", lambda: backend.toast.emit("info", "测试", "提示条动画", 1500), 270)
wait(300)
measure("侧边栏收起", lambda: win.setProperty("width", 820), 210)
wait(400)
win.setProperty("width", 1000)
wait(400)
detail.setProperty("expanded", False)

backend.setReconnectEnabled(True)
for index, name in ((1, "live_accounts.png"), (2, "live_reconnect.png"), (3, "live_settings.png")):
    win.go(index)
    shot(name)
backend.setReconnectEnabled(False)
win.go(0)

apply_theme("Dark")
wait(300)
shot("live_dark.png")
win.go(3)
shot("live_settings_dark.png")
apply_theme("Light")
win.close()
mock.stop()
