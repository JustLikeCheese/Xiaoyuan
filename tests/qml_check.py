"""QML 界面检查：后台加载整套界面，连本地假门户把主要操作点一遍，截图到 tests/shots/。

    python tests/qml_check.py

不弹窗口（offscreen + 软件渲染），用临时数据目录，不动真实账号，也不动真实剪贴板以外的东西。
"""

from __future__ import annotations

import os
import shutil
import sys
import tempfile
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))
os.environ["QT_QPA_PLATFORM"] = "offscreen"
os.environ["QT_QUICK_BACKEND"] = "software"
os.environ.setdefault("QT_QPA_FONTDIR", os.path.join(os.environ.get("WINDIR", r"C:\Windows"), "Fonts"))

from tests.mock_portal import GOOD_PASSWORD, MockPortal  # noqa: E402

mock = MockPortal(release_delay=0.5).start()
home_dir = tempfile.mkdtemp(prefix="xiaoyuan-qml-")
os.environ["XIAOYUAN_HOME"] = home_dir
os.environ["XIAOYUAN_PORTAL"] = mock.base

from PySide6.QtCore import QEventLoop, QObject, QPointF, Qt, QTimer, QUrl  # noqa: E402
from PySide6.QtGui import QGuiApplication  # noqa: E402
from PySide6.QtQml import QJSValue, QQmlApplicationEngine, QQmlEngine, QQmlExpression  # noqa: E402
from PySide6.QtTest import QTest  # noqa: E402

import main  # noqa: E402
from portal import enable_log  # noqa: E402
import backend as backend_module  # noqa: E402
from backend import Backend, Settings, apply_theme, resource_dir  # noqa: E402
from store import Account, AccountStore  # noqa: E402

SHOTS = ROOT / "tests" / "shots"
shutil.rmtree(SHOTS, ignore_errors=True)
SHOTS.mkdir()
failures: list[str] = []
warnings: list[str] = []
MASK = "••••••••"


def wait(ms: int) -> None:
    loop = QEventLoop()
    QTimer.singleShot(ms, loop.quit)
    loop.exec()


def wait_until(cond, timeout_ms: int = 6000) -> bool:
    deadline = time.monotonic() + timeout_ms / 1000
    while time.monotonic() < deadline:
        if cond():
            return True
        wait(40)
    return cond()


def check(name: str, ok: bool, detail: str = "") -> None:
    print(("  ok   " if ok else "  FAIL ") + name + (f"  ({detail})" if detail and not ok else ""))
    if not ok:
        failures.append(name)


store = AccountStore(Path(home_dir) / "accounts.json")
store.upsert(Account("2002010", "@telecom", "我的号", GOOD_PASSWORD), touch=True)
store.upsert(Account("2002011", "", "室友", GOOD_PASSWORD))
store.upsert(Account("2002012", "@cmcc", "", "secret-pw"))   # 没备注，用来测打码

main.prepare()
enable_log(Path(home_dir) / "logs" / "portal.log")
import portal  # noqa: E402
portal.set_auth_interval(0.5)   # 比真实的 5 秒短，界面流程跑得快些
app = QGuiApplication(sys.argv)
settings = Settings()
main.setup_fonts(app, settings)
apply_theme("Light")
backend = Backend(store, settings)
engine = QQmlApplicationEngine()
engine.warnings.connect(lambda ws: warnings.extend(w.toString() for w in ws))
engine.rootContext().setContextProperty("backend", backend)
engine.load(QUrl.fromLocalFile(str(resource_dir() / "qml" / "Main.qml")))
if not engine.rootObjects():
    print("QML 加载失败：", warnings)
    sys.exit(1)
win = engine.rootObjects()[0]


def items_named(name: str) -> list:
    """沿可视树找：Repeater 生成的项只挂在可视树上，findChild 找不到。"""
    found, stack = [], [win.contentItem()]
    while stack:
        it = stack.pop()
        if it.objectName() == name:
            found.append(it)
        stack.extend(it.childItems())
    return found


def obj(name: str) -> QObject:
    o = win.findChild(QObject, name)
    if o is None:
        items = items_named(name)
        o = items[0] if items else None
    if o is None:
        raise LookupError(name)
    return o


# 每个 QML 文件里随便一个内部对象：组件根对象的上下文是外层文件的，要用内部对象才能访问该文件里的 id
INNER = {"homePage": "statusCard", "accountsPage": "accountTable", "settingsPage": "themeBox",
         "mainWindow": "privacyButton", "accountDialog": "dlgAccount", "reconnectPage": "reconnectStatus"}


def js(component: str, expr: str):
    """在某个 QML 文件的上下文里求值（可以用该文件里的 id）。"""
    o = obj(INNER[component])
    e = QQmlExpression(QQmlEngine.contextForObject(o), o, expr)
    value, _ = e.evaluate()
    if e.hasError():
        raise RuntimeError(f"{component}: {expr}: {e.error().toString()}")
    return value.toVariant() if isinstance(value, QJSValue) else value


def hidden(field: str, context: str = "homePage") -> bool:
    return js(context, f"{field}.echoMode === TextInput.Password")


def text(name: str) -> str:
    return obj(name).property("text")


def child(item, name: str):
    """在某一项的可视子树里按 objectName 找。"""
    stack = list(item.childItems())
    while stack:
        it = stack.pop()
        if it.objectName() == name:
            return it
        stack.extend(it.childItems())
    raise LookupError(name)


def children(item, name: str) -> list:
    found, stack = [], list(item.childItems())
    while stack:
        it = stack.pop()
        if it.objectName() == name:
            found.append(it)
        stack.extend(it.childItems())
    return found


def value_text(field: str) -> str:
    return child(obj(field), "value").property("text")


def click(name_or_obj) -> None:
    item = obj(name_or_obj) if isinstance(name_or_obj, str) else name_or_obj
    pos = item.mapToScene(QPointF(item.property("width") / 2, item.property("height") / 2)).toPoint()
    QTest.mouseClick(win, Qt.LeftButton, Qt.NoModifier, pos)
    wait(60)


def toasts() -> list[str]:
    return [t.property("text") for t in items_named("toastTitle")]


def shot(name: str) -> None:
    wait(300)
    win.grabWindow().save(str(SHOTS / name))


print("登录页")
check("启动后查到未登录", wait_until(lambda: text("stateTitle") == "未登录"), text("stateTitle"))
check("自动选中最近使用的账号", obj("accountField").property("text") == "2002010"
      and js("homePage", "ispBox.currentText") == "中国电信",
      f"{obj('accountField').property('text')!r} {js('homePage', 'ispBox.currentText')!r} {js('homePage', 'savedBox.currentIndex')!r}")
check("账号、密码输入框默认打码", hidden("accountField") and hidden("passwordField"))
labels = js("homePage", "savedBox.model.map(x => x.label)")
check("已保存账号只显示备注，没备注的打码", list(labels) == ["我的号 · 中国电信", "室友 · 校园网", f"{MASK} · 中国移动"],
      str(labels))
check("连接详情默认折叠", obj("detailBody").property("height") == 0)
shot("1_home_offline.png")

click("loginButton")
check("登录成功", wait_until(lambda: text("stateTitle") == "已在线"), text("stateTitle"))
check("登录提示不露出账号", "登录成功" in toasts() and not any("2002010" in t for t in toasts()), str(toasts()))
check("当前账号、IP 默认打码", value_text("fieldAccount") == MASK and value_text("fieldIp") == MASK)
check("时长、流量正常显示", value_text("fieldDuration") == "258 小时 25 分" and value_text("fieldFlow") == "41.38 GB",
      f"{value_text('fieldDuration')} {value_text('fieldFlow')}")
check("账号信息卡片显示", wait_until(lambda: obj("accountInfoCard").property("visible")))
check("流量进度条", wait_until(lambda: obj("usageBar").property("value") == 41) and "可用 58.62G" in text("usageText"),
      text("usageText"))
check("账号信息里的姓名默认打码", value_text("info_user_name") == MASK and value_text("info_use_flow") == "41.38G",
      f"{value_text('info_user_name')} {value_text('info_use_flow')}")
mock.notice = "<p>今晚 23:00 停网维护</p>"
backend.refreshWithToast()
check("门户公告显示", wait_until(lambda: obj("noticeBar").property("visible") and "停网维护" in text("noticeText")))
shot("2_home_online.png")
wait(2600)   # 等“状态已刷新”提示条消失，它和关闭按钮都在右上角
click("noticeClose")
check("关闭公告", wait_until(lambda: not obj("noticeBar").property("visible")))

click("privacyButton")
check("右上角眼睛：显示账号和 IP", value_text("fieldAccount") == "2002010@telecom"
      and value_text("fieldIp") == "10.10.130.163")
check("右上角眼睛：登录框账号也显示", not hidden("accountField"))
check("右上角眼睛：没备注的已保存账号也显示", js("homePage", "savedBox.model[2].label") == "2002012 · 中国移动")
click("privacyButton")
check("再点一次全部打码", value_text("fieldAccount") == MASK and hidden("accountField"))

QGuiApplication.clipboard().setText("")
click(child(obj("fieldIp"), "value"))
check("点数值即复制真实内容", QGuiApplication.clipboard().text() == "10.10.130.163", QGuiApplication.clipboard().text())
check("复制后提示", "已复制" in toasts(), str(toasts()))

print("连接详情")
click("expandButton")
check("点箭头展开", wait_until(lambda: obj("detailBody").property("height") > 100))
check("详情数值", value_text("detail_actt") == "1 小时 2 分" and value_text("detail_fee") == "12.34 元"
      and value_text("detail_olno") == "2 台", value_text("detail_actt"))
check("详情敏感项打码", all(value_text(f"detail_{k}") == MASK for k in ("NID", "olip", "olmac", "ss4")))
click("rawButton")
check("原始字段展开", wait_until(lambda: obj("rawTable").property("visible")))
check("原始字段里账号、实名、IP 打码", all(text(f"raw_{k}") == MASK for k in ("uid", "NID", "v46ip")))
shot("3_details.png")
click("privacyButton")
check("眼睛：详情和原始字段都显示", value_text("detail_NID") == "测试用户" and text("raw_v46ip") == "10.10.130.163")
click("privacyButton")
click("rawButton")
click("expandButton")
check("再点收起", wait_until(lambda: obj("detailBody").property("height") == 0))

print("切换账号")
click("switchButton")
wait(250)
items = js("homePage", "Array.from({length: switchMenu.count}, (_, i) => switchMenu.itemAt(i).text)")
check("切换菜单列出账号并标出当前", list(items)[:3] == ["我的号 · 中国电信（当前）", "室友 · 校园网", f"{MASK} · 中国移动"],
      str(items))
shot("4_switch_menu.png")
js("homePage", "switchMenu.close()")
mock.requests.clear()
backend.switchTo("2002011")
check("切换到室友", wait_until(lambda: mock.uid == "2002011" and text("stateTitle") == "已在线", 8000))
paths = [p for p in mock.paths() if p.startswith("/eportal/portal/log")]
check("切换时先注销再登录", paths[:2] == ["/eportal/portal/logout", "/eportal/portal/login"], str(paths))

print("自动刷新")
wait(1200)   # 等切换账号后那次正常刷新（会显示“正在查询”）结束，再观察自动刷新
backend.autoTimer.setInterval(200)
before = mock.paths().count("/drcom/chkstatus")
click("autoRefreshButton")
seen: list[str] = []
for _ in range(25):
    seen.append(text("stateTitle"))
    wait(40)
check("打开后定时刷新", mock.paths().count("/drcom/chkstatus") - before >= 3)
check("自动刷新不闪“正在查询”", "正在查询…" not in seen, str(set(seen)))
click("autoRefreshButton")
check("关闭后停止", not backend.autoTimer.isActive())

print("账号登录折叠")
check("账号登录默认展开", obj("loginCard").property("expanded") is True)
click("loginExpandButton")
check("点击后收起", wait_until(lambda: obj("loginBody").property("height") == 0))
check("折叠状态写入配置", Settings(settings.path).get("Login", "FormExpanded") is False)
click("loginExpandButton")
check("再点展开", wait_until(lambda: obj("loginBody").property("height") > 200))

print("账号管理页")
click("navAccounts")
check("切到账号管理", wait_until(lambda: obj("accountsPage").property("visible") and obj("accountsPage").property("opacity") == 1))
cells = lambda c: [child(obj(f"row_{a}"), f"cell{c}").property("text") for a in ("2002010", "2002011", "2002012")]  # noqa: E731
check("账号、密码列默认打码", cells(1) == [MASK] * 3 and cells(4) == [MASK] * 3, f"{cells(1)} {cells(4)}")
check("终端类型列默认显示“默认”", cells(3) == ["默认"] * 3, str(cells(3)))
check("当前登录标签显示室友", obj("currentLoginTag").property("visible")
      and obj("currentLoginTag").property("text") == "室友 · 校园网", obj("currentLoginTag").property("text"))
tags = {a: any(t.property("visible") for t in children(obj(f"row_{a}"), "onlineTag"))
        for a in ("2002010", "2002011", "2002012")}
check("表格里只有在线的账号带“在线”标签", tags == {"2002010": False, "2002011": True, "2002012": False}, str(tags))
click("row_2002011")
check("点行选中后按钮可用", obj("editButton").property("enabled") and obj("useButton").property("enabled"))
shot("5_accounts.png")
click("privacyButton")
check("眼睛：账号和密码可见", cells(1) == ["2002010", "2002011", "2002012"] and cells(4)[2] == "secret-pw", str(cells(4)))
click("privacyButton")
click("editButton")
check("编辑窗口打开，账号和密码打码", wait_until(lambda: obj("accountDialog").property("visible"))
      and hidden("accountField", "accountDialog") and obj("dlgAccount").property("text") == "2002011")
shot("6_edit_dialog.png")
js("accountsPage", "dialog.close()")
wait(300)

print("自动重连页")
click("navReconnect")
check("切到自动重连", wait_until(lambda: obj("reconnectPage").property("visible")))
check("默认未开启", text("reconnectState") == "未开启", text("reconnectState"))
click("reconnectSwitch")
check("打开后进入监控", wait_until(lambda: text("reconnectState") == "监控中"), text("reconnectState"))
check("开关写入配置", Settings(settings.path).get("Reconnect", "Enabled") is True)
check("重连账号选项只列有密码的账号且打码",
      list(js("reconnectPage", "accountBox.model.map(x => x.label)")) ==
      ["最近使用的账号", "我的号 · 中国电信", "室友 · 校园网", f"{MASK} · 中国移动"],
      str(js("reconnectPage", "accountBox.model.map(x => x.label)")))
mock.online, mock.uid = False, ""          # 模拟掉线
backend.reconnect_tick()
check("掉线后自动重连成功", wait_until(lambda: backend.reconnect["count"] == 1 and mock.online, 8000), str(backend.reconnect))
check("重连记录有内容", wait_until(lambda: "自动重连成功" in [t.property("text") for t in items_named("historyText")]),
      str([t.property("text") for t in items_named("historyText")]))
shot("8_reconnect.png")
backend.logout()
check("手动注销后自动重连暂停", wait_until(lambda: text("reconnectState") == "已暂停"), text("reconnectState"))
check("暂停时显示“恢复”按钮", obj("resumeButton").property("visible"))
click("resumeButton")
check("恢复后重新监控并登回", wait_until(lambda: backend.reconnect["count"] == 2 and mock.online, 8000), str(backend.reconnect))

# 兜底检测间隔：数字 + 单位，任意时长
check("间隔显示为 1 分钟", (js("reconnectPage", "intervalSpin.value"), js("reconnectPage", "unitBox.currentText")) == (1, "分钟"),
      str((js("reconnectPage", "intervalSpin.value"), js("reconnectPage", "unitBox.currentText"))))
js("reconnectPage", "unitBox.currentIndex = 0, unitBox.activated(0)")       # 改成“秒”：1 秒，会被夹到 5 秒
check("间隔最短 5 秒", wait_until(lambda: backend.reconnect["interval"] == 5) and js("reconnectPage", "intervalSpin.value") == 5,
      str(backend.reconnect["interval"]))
js("reconnectPage", "intervalSpin.value = 45, intervalSpin.valueModified()")
check("自定义 45 秒", backend.reconnect["interval"] == 45 and Settings(settings.path).get("Reconnect", "Interval") == 45)
js("reconnectPage", "intervalSpin.value = 120, intervalSpin.valueModified()")
check("120 秒显示为 2 分钟", wait_until(lambda: (js("reconnectPage", "intervalSpin.value"), js("reconnectPage", "unitBox.currentText")) == (2, "分钟")))
click("reconnectSwitch")
check("关闭自动重连", wait_until(lambda: text("reconnectState") == "未开启"))

print("设置页")
click("navSettings")
check("切到设置", wait_until(lambda: obj("settingsPage").property("visible")))
check("没有存储管理入口", not items_named("navStorage"))
check("窗口标题带版本号和作者", win.property("title") == f"校园网登录 v{backend_module.APP_VERSION} · JustLikeCheese",
      win.property("title"))
check("关于里有版本、作者、许可证", "JustLikeCheese" in obj("aboutRow").property("title")
      and "GPL v3" in obj("aboutRow").property("description"))
check("关于里有 GitHub 地址", obj("projectRow").property("description") == "https://github.com/JustLikeCheese/Xiaoyuan")
check("设置里显示日志占用", wait_until(lambda: backend.logInfo["files"] >= 1 and "共" in obj("logRow").property("description")),
      obj("logRow").property("description"))
out = Path(home_dir) / "导出.log"
check("导出日志", backend.exportLog(QUrl.fromLocalFile(str(out))) and "/eportal/portal/login" in out.read_text(encoding="utf-8"))
check("导出的日志默认打码", "2002010" not in out.read_text(encoding="utf-8"))
shot("7_settings.png")
check("默认终端类型为电脑", js("settingsPage", "page.s.terminalType") == 1)
backend.setSetting("terminalType", 2)
check("默认终端类型写入配置", Settings(settings.path).get("Portal", "TerminalType") == 2)
check("登录页“默认”项跟随全局设置", js("homePage", "terminalBox.currentText") == "默认（手机）",
      str(js("homePage", "terminalBox.currentText")))
backend.setSetting("terminalType", 1)
click("autoLoginSwitch")
check("自动登录开关写入配置", Settings(settings.path).get("Login", "AutoLogin") is True)
click("autoLoginSwitch")
backend.setSetting("theme", "Dark")
if QGuiApplication.styleHints().colorScheme() == Qt.ColorScheme.Dark:
    check("切到深色", wait_until(lambda: js("settingsPage", "Theme.dark") is True))
else:
    # offscreen 平台不支持切换配色；真实窗口的深浅色切换由 tests/perf_check.py 截图验证
    print("  skip 切到深色（后台渲染平台不支持切换配色，用 perf_check.py 在真实窗口验证）")
check("主题写入配置", Settings(settings.path).get("Appearance", "Theme") == "Dark")
backend.setSetting("theme", "Light")

print("窗口宽度")
for w in (1000, 760):
    win.setProperty("width", w)
    wait(400)
    check(f"{w}px 宽时侧边栏{'收起' if w < 900 else '展开'}",
          js("mainWindow", "nav.width") == (64 if w < 900 else 220), str(js("mainWindow", "nav.width")))
    for page_name, col in (("homePage", "column"), ("accountsPage", "col"), ("reconnectPage", "col"),
                           ("settingsPage", "col")):
        # 实际摆放的范围（会换行的长文字自然宽度很大，但不会真的超出）；隐藏的项（比如关掉的公告）不算
        need = js(page_name, f"Math.max(0, ...{col}.children.filter(c => c.visible).map(c => c.x + c.width))")
        have = js(page_name, f"{col}.width")
        check(f"{w}px 宽时{page_name}内容不超出", need <= have + 0.5, f"需要 {need:.0f}，可用 {have:.0f}")
win.setProperty("width", 1000)

check("全程没有调用 /drcom/login、/drcom/logout", not {"/drcom/login", "/drcom/logout"} & mock.seen_paths)
real_warnings = [w for w in warnings if "fluentwinui3" not in w.lower()]
check("没有 QML 警告/错误", not real_warnings, "\n    " + "\n    ".join(real_warnings[:10]))

win.close()
mock.stop()
print(f"\n截图在 {SHOTS}")
print("全部通过" if not failures else f"{len(failures)} 项失败：{failures}")
sys.exit(1 if failures else 0)
