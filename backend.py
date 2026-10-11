"""界面背后的逻辑层：登录、切换账号、自动登录、自动刷新、自动重连、账号保存、设置和日志导出，暴露给 QML 使用。

只依赖 QtCore/QtGui，不涉及界面细节，可以脱离 QML 单独测试（见 tests/test_backend.py）。
"""

from __future__ import annotations

import json
import os
import re
import sys
import threading
import time
from collections import deque
from datetime import datetime
from pathlib import Path

from PySide6.QtCore import Property, QObject, Qt, QTimer, QUrl, Signal, Slot
from PySide6.QtGui import QDesktopServices, QFontDatabase, QGuiApplication

import logfiles
import portal as portal_module
from portal import (CARRIERS, DEFAULT_HOST, DEFAULT_TERMINAL_TYPE, TERMINAL_TYPES, SELF_SERVICE_URL, LoginError, Portal, PortalError, Status, carrier_name,
                    format_fee, format_flow, format_limit, format_mac, format_minutes, format_seconds)
from portal import log as portal_log
from store import Account, AccountStore, app_dir

try:   # 网络状态通知（Windows 上来自系统的网络列表管理器），没有也能用，只是全靠定时检测
    from PySide6.QtNetwork import QNetworkInformation
except ImportError:   # pragma: no cover
    QNetworkInformation = None

APP_NAME = "校园网登录"
APP_VERSION = "2.3.3"
APP_AUTHOR = "JustLikeCheese"
APP_URL = "https://github.com/JustLikeCheese/Xiaoyuan"
APP_LICENSE = "GNU GPL v3"
APP_TITLE = f"{APP_NAME} v{APP_VERSION} · {APP_AUTHOR}"   # 窗口标题、设置“关于”里用
AUTO_REFRESH_MS = 5000
RECONNECT_MIN_INTERVAL = 5               # 兜底检测间隔的下限（秒），和门户的请求频率限制一致
RECONNECT_MAX_INTERVAL = 24 * 3600
RECONNECT_MAX_FAILURES = 3                # 连续失败这么多次就暂停，免得用错密码反复尝试
RECONNECT_COOLDOWN = 15                   # 失败后，被动看到离线时至少隔这么多秒（乘以失败次数）再试
NETWORK_EVENT_DELAY_MS = 2000             # 网络状态变化后等一会儿再查，变化往往一连串地来
ACCOUNT_INFO_TTL = 120                    # 账号信息（流量余额等）最多这么多秒更新一次，自动刷新不会每次都查

# Win11 自带应用的字体组合：英文数字用 Segoe UI Variable（Win10 退回 Segoe UI），中文用微软雅黑 UI
DEFAULT_FONTS = ["Segoe UI Variable Text", "Segoe UI", "Microsoft YaHei UI", "Microsoft YaHei"]
# 设置里可选的第三方字体，只列出本机已安装的。Noto Sans SC / 思源黑体在 Qt 里粗体渲染有问题，不列
OPTIONAL_FONTS = ["MiSans", "HarmonyOS Sans SC", "PingFang SC", "OPPO Sans", "Alibaba PuHuiTi 3.0"]

# 隐私模式下要打码的原始字段：账号、实名、IP、MAC
SENSITIVE_KEYS = {"uid", "AC", "NID", "v46ip", "v4ip", "lip", "ss5", "ss3", "olip", "olmac", "ss4", "myv6ip", "v6ip"}
# 账号信息（loadUserInfo）返回里属于个人身份的字段
SECRET_INFO_KEYS = {"account", "user_name", "username", "real_name", "name", "mobile", "phone", "id_card", "email"}


# ---------------------------------------------------------------- 设置

class Settings:
    """settings.json，沿用旧版（QFluentWidgets 时期）的分组结构，升级后设置不丢。"""

    DEFAULTS = {
        ("Login", "AutoLogin"): False,
        ("Login", "CloseAfterAutoLogin"): False,
        ("Login", "FormExpanded"): True,      # 首页“账号登录”卡片是否展开
        ("Portal", "Host"): f"http://{DEFAULT_HOST}",
        ("Portal", "TerminalType"): DEFAULT_TERMINAL_TYPE,   # 登录时告诉门户的终端类型，见 portal.TERMINAL_TYPES
        ("Appearance", "Theme"): "Auto",      # Light / Dark / Auto
        ("Appearance", "Font"): "",           # 空串表示系统默认
        ("Status", "AutoRefresh"): False,
        ("Reconnect", "Enabled"): False,
        ("Reconnect", "Interval"): 60,        # 兜底检测间隔（秒）；网络变化时会立即检测，不靠这个
        ("Reconnect", "Account"): "",         # 空串表示最近使用的账号
    }

    def __init__(self, path: Path | None = None):
        self.path = path or app_dir() / "settings.json"
        try:
            self.data = json.loads(self.path.read_text(encoding="utf-8"))
            if not isinstance(self.data, dict):
                self.data = {}
        except (OSError, ValueError):
            self.data = {}
        # 旧版的主题存在 QFluentWidgets.ThemeMode
        old_theme = self.data.get("QFluentWidgets", {}).get("ThemeMode")
        if old_theme in ("Light", "Dark", "Auto") and "Theme" not in self.data.get("Appearance", {}):
            self.data.setdefault("Appearance", {})["Theme"] = old_theme

    def get(self, group: str, name: str):
        value = self.data.get(group, {}).get(name)
        default = self.DEFAULTS[(group, name)]
        return value if isinstance(value, type(default)) else default

    def set(self, group: str, name: str, value) -> None:
        self.data.setdefault(group, {})[name] = value
        self.path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.path.with_suffix(".tmp")
        tmp.write_text(json.dumps(self.data, ensure_ascii=False, indent=4), encoding="utf-8")
        os.replace(tmp, self.path)


def installed_optional_fonts() -> list[str]:
    families = set(QFontDatabase.families())
    return [f for f in OPTIONAL_FONTS if f in families]


def ui_font_families(settings: Settings) -> list[str]:
    chosen = settings.get("Appearance", "Font")
    return ([chosen] if chosen in installed_optional_fonts() else []) + DEFAULT_FONTS


# ---------------------------------------------------------------- 后台任务

class _Job(QObject):
    done = Signal(object, object)


_jobs: set[_Job] = set()


def run_async(fn, *args, on_done) -> None:
    """在守护线程执行 fn(*args)，完成后在界面线程调用 on_done(result, error)。关窗口时不必等请求超时。"""
    job = _Job()
    _jobs.add(job)

    def finish(result, error):
        _jobs.discard(job)
        on_done(result, error)

    job.done.connect(finish, Qt.QueuedConnection)

    def work():
        try:
            job.done.emit(fn(*args), None)
        except Exception as e:  # 交给界面线程处理
            job.done.emit(None, e)

    threading.Thread(target=work, daemon=True).start()


def describe(err: Exception) -> str:
    if isinstance(err, (LoginError, PortalError)):
        return str(err)
    return f"{type(err).__name__}: {err}"


# ---------------------------------------------------------------- 状态详情

def _raw_int(raw: dict, key: str) -> int | None:
    try:
        return int(float(str(raw[key]).strip()))
    except (KeyError, ValueError):
        return None


def _fmt(key: str, fn):
    def get(raw: dict) -> str:
        v = _raw_int(raw, key)
        return "" if v is None else fn(v)
    return get


def _text(key: str, fn=lambda s: s):
    def get(raw: dict) -> str:
        v = str(raw.get(key, "")).strip()
        return fn(v) if v else ""
    return get


# (原始字段名, 标题, 取值函数, 是否敏感)。含义靠数值和页面代码推断，不确定的标了“推测”
DETAIL_FIELDS = [
    ("NID", "实名", _text("NID"), True),
    ("actt", "本次在线时长", _fmt("actt", format_seconds), False),
    ("fee", "余额", _fmt("fee", format_fee), False),
    ("olno", "本账号在线设备", _fmt("olno", lambda n: f"{n} 台"), False),
    ("olip", "同账号另一设备 IP（推测）", _text("olip"), True),
    ("olmac", "同账号另一设备 MAC（推测）", _text("olmac", format_mac), True),
    ("ollm", "在线设备上限（推测）", _fmt("ollm", lambda n: f"{n} 台"), False),
    ("oltime", "时长上限", _fmt("oltime", lambda v: format_limit(v, format_minutes)), False),
    ("olflow", "流量上限", _fmt("olflow", lambda v: format_limit(v, format_flow)), False),
    ("ss4", "本机 MAC", _text("ss4", lambda m: format_mac(m) or "门户未获取"), True),
    ("vid", "VLAN", _text("vid"), False),
    ("ss6", "认证服务器", _text("ss6"), False),
    ("aolno", "全校在线终端", _fmt("aolno", lambda n: f"{n} 台"), False),
    ("gid", "用户组（推测）", _text("gid"), False),
    ("ispid", "运营商编号", _text("ispid"), False),
]


# ---------------------------------------------------------------- 日志

# 日志里的敏感信息：请求参数里的账号、IP、MAC，返回里的账号、实名、IP、MAC，以及事件里的 acct=
_MASK_PATTERNS = [
    (re.compile(r"((?:user_account|wlan_user_ip|wlan_user_mac|DDDDD)=)[^&\s]*"), r"\1••••"),
    (re.compile(r'("(?:' + "|".join(sorted(SENSITIVE_KEYS | SECRET_INFO_KEYS)) + r')":\s*")[^"]*(")'), r"\1••••••••\2"),
    (re.compile(r"(acct=)\S+"), r"\1••••"),
]


def mask_log(text: str) -> str:
    for pattern, repl in _MASK_PATTERNS:
        text = pattern.sub(repl, text)
    return text


def log_event(message: str) -> None:
    """写进请求日志的“事件”行，账号用 acct= 标出，方便导出时打码。"""
    portal_log.info("[事件] %s", message)


def format_size(n: int) -> str:
    if n < 1024:
        return f"{n} B"
    if n < 1024 * 1024:
        return f"{n / 1024:.1f} KB"
    return f"{n / 1024 / 1024:.2f} MB"


def default_log_path() -> Path:
    return app_dir() / "logs" / "portal.log"


def terminal_name(value: int) -> str:
    return next((n for n, v in TERMINAL_TYPES if v == value), "默认")


def format_interval(seconds: int) -> str:
    if seconds % 3600 == 0:
        return f"{seconds // 3600} 小时"
    if seconds % 60 == 0:
        return f"{seconds // 60} 分钟"
    return f"{seconds} 秒"


# ---------------------------------------------------------------- 给 QML 的后端对象

class Backend(QObject):
    statusChanged = Signal()
    accountsChanged = Signal()
    busyChanged = Signal()
    revealedChanged = Signal()
    settingsChanged = Signal()
    reconnectChanged = Signal()
    logInfoChanged = Signal()
    accountInfoChanged = Signal()
    noticeChanged = Signal()
    throttleChanged = Signal()
    _waitSignal = Signal(float)             # 后台线程 -> 界面线程：认证请求要排队这么多秒
    toast = Signal(str, str, str, int)      # 类型(success/info/warning/error)、标题、内容、显示毫秒
    fillRequested = Signal(str)             # 让登录表单回填某个已保存账号
    pageRequested = Signal(int)             # 让界面切到某一页（0 登录、1 账号管理、2 自动重连、3 设置）
    closeRequested = Signal()

    def __init__(self, store: AccountStore | None = None, settings: Settings | None = None,
                 log_path: Path | None = None, parent=None):
        super().__init__(parent)
        self.store = store or AccountStore()
        self.settings = settings or Settings()
        self.log_path = log_path or default_log_path()
        self._status = self._status_dict("querying", "正在查询…", "")
        self._details: list[dict] = self._detail_list({})
        self._raw: list[dict] = []
        self._busy = False
        self._revealed = False
        self._refreshing = False
        self._refresh_waiters: list = []     # 查询进行中又有人要结果：等这一次的，不重复请求
        self.last_status: Status | None = None
        # 账号信息（page/loadUserInfo）和门户公告
        self._account_info: dict = {"shown": False}
        self._info_account = ""
        self._info_at = 0.0
        self._info_loading = False
        self._info_force = False
        self._notice = ""
        self._notice_dismissed = ""
        self.portal_override: str | None = os.environ.get("XIAOYUAN_PORTAL")

        # 门户请求频率限制：排队时界面显示倒计时
        self._throttle_until = 0.0
        self._throttleTimer = QTimer(self)
        self._throttleTimer.setInterval(250)
        self._throttleTimer.timeout.connect(self._tick_throttle)
        self._waitSignal.connect(self._on_wait, Qt.QueuedConnection)
        portal_module.set_wait_listener(self._waitSignal.emit)

        self.autoTimer = QTimer(self)
        self.autoTimer.setInterval(AUTO_REFRESH_MS)
        self.autoTimer.timeout.connect(lambda: self.refresh(True))
        if self.settings.get("Status", "AutoRefresh"):
            self.autoTimer.start()

        # 自动重连
        self._recon_paused = False          # 手动注销或连续失败后暂停
        self._recon_busy = False
        self._recon_failures = 0
        self._recon_count = 0
        self._recon_last_check = ""
        self._recon_last_state = ""          # online / offline / unreachable，用于只在状态变化时记一条
        self._recon_history: deque[dict] = deque(maxlen=100)
        self._recon_not_before = 0.0         # 失败后的冷却：被动看到离线时，这之前不再尝试
        self._recon_force = False            # 正在进行的查询是主动检测发起的（不受冷却限制）
        # 兜底检测：单次定时器，每拿到一次状态（无论谁查的）就重新计时，所以从不和别的查询叠在一起
        self.reconnectTimer = QTimer(self)
        self.reconnectTimer.setSingleShot(True)
        self.reconnectTimer.timeout.connect(self.reconnect_tick)
        # 网络状态变化后稍等再查
        self.networkTimer = QTimer(self)
        self.networkTimer.setSingleShot(True)
        self.networkTimer.setInterval(NETWORK_EVENT_DELAY_MS)
        self.networkTimer.timeout.connect(self._network_check)
        self._network_reason = ""
        self._net = None
        self._watch_network()
        self._apply_reconnect_timer()

    # ---- 属性 ----

    def _get_status(self):
        return self._status

    def _get_details(self):
        return self._details

    def _get_raw(self):
        return self._raw

    def _get_accounts(self):
        return [self._account_dict(a) for a in self.store.accounts]

    def _get_last_account(self):
        last = self.store.last()
        return last.account if last else ""

    def _get_busy(self):
        return self._busy

    def _get_revealed(self):
        return self._revealed

    def _get_settings(self):
        s = self.settings
        return {
            "autoLogin": s.get("Login", "AutoLogin"),
            "closeAfterAutoLogin": s.get("Login", "CloseAfterAutoLogin"),
            "loginExpanded": s.get("Login", "FormExpanded"),
            "portalHost": s.get("Portal", "Host"),
            "terminalType": s.get("Portal", "TerminalType"),
            "theme": s.get("Appearance", "Theme"),
            "font": s.get("Appearance", "Font"),
            "autoRefresh": s.get("Status", "AutoRefresh"),
        }

    def _get_reconnect(self):
        enabled = self.settings.get("Reconnect", "Enabled")
        if not enabled:
            state = "off"
        elif self._recon_busy:
            state = "reconnecting"
        elif self._recon_paused:
            state = "paused"
        else:
            state = "watching"
        return {
            "enabled": enabled,
            "interval": self.reconnect_interval(),
            "intervalText": format_interval(self.reconnect_interval()),
            "networkEvents": self._net is not None,
            "account": self.settings.get("Reconnect", "Account"),
            "state": state,
            "lastCheck": self._recon_last_check,
            "lastState": self._recon_last_state,
            "count": self._recon_count,
            "failures": self._recon_failures,
        }

    def _get_reconnect_history(self):
        return list(reversed(self._recon_history))   # 新的在前

    def _get_log_info(self):
        self._flush_log()
        info = logfiles.summary(self.log_path.parent, self.log_path)
        return {**info, "sizeText": format_size(info["size"]), "dir": str(self.log_path.parent),
                "keepDays": logfiles.KEEP_DAYS}

    def _get_throttle(self):
        return max(0, int(self._throttle_until - time.monotonic() + 0.999))

    def _get_carriers(self):
        return [{"name": n, "suffix": s} for n, s in CARRIERS]

    def _get_fonts(self):
        return [{"label": "系统默认（微软雅黑 UI）", "value": ""}] + [{"label": f, "value": f}
                                                                for f in installed_optional_fonts()]

    status = Property("QVariantMap", _get_status, notify=statusChanged)
    details = Property("QVariantList", _get_details, notify=statusChanged)
    rawFields = Property("QVariantList", _get_raw, notify=statusChanged)
    accounts = Property("QVariantList", _get_accounts, notify=accountsChanged)
    lastAccount = Property(str, _get_last_account, notify=accountsChanged)
    busy = Property(bool, _get_busy, notify=busyChanged)
    revealed = Property(bool, _get_revealed, notify=revealedChanged)
    settingsMap = Property("QVariantMap", _get_settings, notify=settingsChanged)
    reconnect = Property("QVariantMap", _get_reconnect, notify=reconnectChanged)
    reconnectHistory = Property("QVariantList", _get_reconnect_history, notify=reconnectChanged)
    logInfo = Property("QVariantMap", _get_log_info, notify=logInfoChanged)
    accountInfo = Property("QVariantMap", lambda self: self._account_info, notify=accountInfoChanged)
    noticeText = Property(str, lambda self: "" if self._notice == self._notice_dismissed else self._notice,
                          notify=noticeChanged)
    throttle = Property(int, _get_throttle, notify=throttleChanged)   # 还要排队几秒，0 表示没有
    carriers = Property("QVariantList", _get_carriers, constant=True)
    terminalTypes = Property("QVariantList", lambda self: [{"name": n, "value": v} for n, v in TERMINAL_TYPES],
                             constant=True)
    # 登录页、账号编辑用：第一项“默认”跟随设置里的全局值
    terminalOptions = Property("QVariantList", lambda self: [{"name": f"默认（{terminal_name(self.terminal_type())}）",
                                                             "value": -1}]
                               + [{"name": n, "value": v} for n, v in TERMINAL_TYPES], notify=settingsChanged)
    fonts = Property("QVariantList", _get_fonts, constant=True)
    appDir = Property(str, lambda self: str(app_dir()), constant=True)
    selfServiceUrl = Property(str, lambda self: SELF_SERVICE_URL, constant=True)
    defaultHost = Property(str, lambda self: f"http://{DEFAULT_HOST}", constant=True)
    version = Property(str, lambda self: f"{APP_NAME} v{APP_VERSION}", constant=True)
    appTitle = Property(str, lambda self: APP_TITLE, constant=True)
    author = Property(str, lambda self: APP_AUTHOR, constant=True)
    projectUrl = Property(str, lambda self: APP_URL, constant=True)
    license = Property(str, lambda self: APP_LICENSE, constant=True)
    autoRefreshSeconds = Property(int, lambda self: AUTO_REFRESH_MS // 1000, constant=True)

    # ---- 内部 ----

    def terminal_type(self, chosen: int = -1) -> int:
        """实际使用的终端类型：账号或登录页选了具体类型就用它，选“默认”（-1）时用设置里的全局值。"""
        valid = {v for _, v in TERMINAL_TYPES}
        if chosen in valid:
            return chosen
        global_type = self.settings.get("Portal", "TerminalType")
        return global_type if global_type in valid else DEFAULT_TERMINAL_TYPE

    def make_portal(self, terminal_type: int = -1) -> Portal:
        # 环境变量优先，方便连假门户测试
        return Portal(self.portal_override or self.settings.get("Portal", "Host"),
                      terminal_type=self.terminal_type(terminal_type))

    def _set_busy(self, on: bool) -> None:
        if self._busy != on:
            self._busy = on
            self.busyChanged.emit()

    @staticmethod
    def _status_dict(state: str, title: str, detail: str, st: Status | None = None) -> dict:
        online = bool(st and st.online)
        return {
            "state": state,                 # querying / online / offline / error
            "title": title,
            "detail": detail,
            "online": online,
            "account": st.account if online else "",
            "ip": st.ip if st else "",
            "duration": format_minutes(st.minutes) if online else "",
            "flow": format_flow(st.flow_kb) if online else "",
        }

    @staticmethod
    def _detail_list(raw: dict) -> list[dict]:
        return [{"key": k, "title": t, "value": get(raw), "secret": secret} for k, t, get, secret in DETAIL_FIELDS]

    @staticmethod
    def _account_dict(a: Account) -> dict:
        if a.password_lost:
            state = "lost"
        elif a.password:
            state = "saved"
        else:
            state = "none"
        return {
            "account": a.account, "isp": a.isp, "carrier": a.carrier, "note": a.note, "password": a.password,
            "passwordState": state, "full": a.full,
            "terminalType": a.terminal_type,
            "terminalName": terminal_name(a.terminal_type) if a.terminal_type >= 0 else "默认",
            "lastUsed": datetime.fromtimestamp(a.last_used).strftime("%Y-%m-%d %H:%M") if a.last_used else "-",
        }

    def _show_status(self, st: Status | None, err: Exception | None) -> None:
        if err is not None:
            self._status = self._status_dict("error", "无法连接门户", describe(err))
            self._details, self._raw = self._detail_list({}), []
        elif st.online:
            suffix = "@" + st.account.split("@", 1)[1] if "@" in st.account else ""
            self._status = self._status_dict("online", "已在线", f"{carrier_name(suffix)} · 认证通过，可以正常上网", st)
        else:
            self._status = self._status_dict("offline", "未登录", "选择账号后点击登录", st)
        if st is not None:
            self._details = self._detail_list(st.raw)
            self._raw = [{"key": k, "value": str(v), "secret": k in SENSITIVE_KEYS} for k, v in st.raw.items()]
        self.statusChanged.emit()

    # ---- 状态 ----

    @Slot()
    def startup(self) -> None:
        def after(st: Status | None):
            self.loadNotice()
            last = self.store.last()
            if st and not st.online and self.settings.get("Login", "AutoLogin") and last and last.password:
                self.fillRequested.emit(last.account)
                self._login(last.account, last.password, last.isp, True, source="auto",
                            terminal_type=last.terminal_type)

        self.refresh(False, after)

    @Slot()
    @Slot(bool)
    def refresh(self, quiet: bool = False, then=None) -> None:
        """查一次在线状态。quiet 用于自动刷新：不显示“正在查询”，正在登录时跳过。

        上一次查询还没回来时不再发请求，等那一次的结果（then 照样会被调用）。
        """
        if then:
            self._refresh_waiters.append(then)
        if self._refreshing:
            return
        if quiet and self._busy and not then:
            return
        self._refreshing = True
        if not quiet:
            self._status = {**self._status, "state": "querying", "title": "正在查询…"}
            self.statusChanged.emit()

        def done(st, err):
            self._refreshing = False
            st = st if err is None else None
            self.last_status = st
            self._show_status(st, err)
            self._update_account_info(st)
            waiters, self._refresh_waiters = self._refresh_waiters, []
            for fn in waiters:
                fn(st)
            self._observe(st)

        run_async(self.make_portal().status, on_done=done)

    @Slot()
    def refreshWithToast(self) -> None:
        self._info_force = True   # 手动刷新时账号信息也重新查
        self.loadNotice()

        def after(st):
            if st is not None:
                self.toast.emit("info", "状态已刷新", "当前在线" if st.online else "当前未登录", 2000)

        self.refresh(False, after)

    # ---- 账号信息 / 公告 ----

    def _update_account_info(self, st: Status | None) -> None:
        if st is None or not st.online:
            if self._account_info.get("shown"):
                self._account_info, self._info_account = {"shown": False}, ""
                self.accountInfoChanged.emit()
            return
        stale = time.monotonic() - self._info_at > ACCOUNT_INFO_TTL
        if self._info_force or st.account != self._info_account or stale:
            self._info_force = False
            self.loadAccountInfo()

    @Slot()
    def loadAccountInfo(self) -> None:
        """查账号信息（流量/时长用量、套餐等），只在在线时有结果。"""
        if self._info_loading:
            return
        self._info_loading = True
        account = self.last_status.account if self.last_status else ""

        def done(info, err):
            self._info_loading = False
            self._info_at = time.monotonic()
            self._info_account = account
            if err is not None:
                log_event(f"查询账号信息失败：{describe(err)}")
            if info is None:
                self._account_info = {"shown": False}
            else:
                self._account_info = {
                    "shown": True,
                    "progress": -1 if info.progress is None else info.progress,
                    "progressType": info.progress_type,
                    "used": info.used,
                    "left": info.available,
                    "items": [{"key": k, "title": t, "value": v, "secret": k in SECRET_INFO_KEYS}
                              for k, t, v in info.fields],
                }
            self.accountInfoChanged.emit()

        run_async(self.make_portal().user_info, on_done=done)

    @Slot()
    def loadNotice(self) -> None:
        def done(text, err):
            text = "" if err is not None else (text or "")
            if text != self._notice:
                self._notice = text
                self.noticeChanged.emit()

        run_async(self.make_portal().notice, on_done=done)

    @Slot()
    def dismissNotice(self) -> None:
        """关掉公告；同一条公告本次运行不再显示，内容变了会重新出现。"""
        self._notice_dismissed = self._notice
        self.noticeChanged.emit()

    @Slot(bool)
    def setAutoRefresh(self, on: bool) -> None:
        self.settings.set("Status", "AutoRefresh", on)
        self.settingsChanged.emit()
        if on:
            self.autoTimer.start()
            self.refresh(True)
        else:
            self.autoTimer.stop()

    # ---- 登录 / 注销 / 切换 ----

    @Slot(str, str, str, bool)
    @Slot(str, str, str, bool, int)
    def login(self, account: str, password: str, suffix: str, remember: bool, terminal_type: int = -1) -> None:
        self._login(account, password, suffix, remember, terminal_type=terminal_type)

    def _login(self, account: str, password: str, suffix: str, remember: bool, source: str = "manual",
               on_result=None, terminal_type: int = -1) -> None:
        """source：manual 手动 / auto 启动时自动登录 / reconnect 自动重连。on_result(ok, err) 在完成后回调。

        terminal_type 为登录页或账号里选的终端类型，-1 表示默认（跟随设置）；记住账号时一并保存。
        """
        if self._busy:
            return
        account = account.strip()
        if not account:
            self.toast.emit("warning", "请填写账号", "", 3000)
            return
        if not password:
            self.toast.emit("warning", "请填写密码", "", 3000)
            return
        st = self.last_status
        if st and st.online and st.account.lower() == (account + suffix).lower():
            self.toast.emit("info", "已经是这个账号在线", "", 3000)
            return
        # 已经用别的账号在线时，先注销再登录，就是“切换账号”
        switching = bool(st and st.online)
        label = {"auto": "自动登录", "reconnect": "自动重连"}.get(source, "切换账号" if switching else "登录")
        self._set_busy(True)

        def done(_, err):
            self._set_busy(False)
            self._set_throttle(0)
            if err is not None:
                log_event(f"{label}失败 acct={account}{suffix}：{describe(err)}")
                if source == "reconnect":
                    title = "自动重连失败"
                elif switching:
                    title = "切换失败"
                else:
                    title = "登录失败" if isinstance(err, LoginError) else "连接失败"
                QTimer.singleShot(400, self.refresh)   # 失败后以门户实际状态为准
                self.toast.emit("error", title, describe(err), 8000)
                if on_result:
                    on_result(False, err)
                return
            log_event(f"{label}成功 acct={account}{suffix}")
            existing = self.store.get(account)
            acc = None
            if remember or existing:
                note = existing.note if existing else ""
                saved_password = password if remember else (existing.password if existing else "")
                saved_type = terminal_type if remember else (existing.terminal_type if existing else -1)
                acc = self.store.upsert(Account(account, suffix, note, saved_password, terminal_type=saved_type),
                                        touch=True)
                self.accountsChanged.emit()
            # 提示里不露出账号：有备注显示备注，否则只显示运营商
            name = f"{acc.note} · {acc.carrier}" if acc and acc.note else carrier_name(suffix)
            title = {"reconnect": "已自动重连"}.get(source, "已切换账号" if switching else "登录成功")
            self.toast.emit("success", title, name, 3000)
            QTimer.singleShot(600, self.refresh)
            if source == "manual" and self._recon_paused:
                self._recon_paused = False
                self._recon_failures = 0
                self._recon_not_before = 0.0
                self._recon_note("info", "已登录，自动重连恢复")
            if source == "auto" and self.settings.get("Login", "CloseAfterAutoLogin"):
                QTimer.singleShot(1500, lambda: self.closeRequested.emit())
            if on_result:
                on_result(True, None)

        portal = self.make_portal(terminal_type)
        if switching:
            run_async(portal.switch, account, password, suffix, on_done=done)
        else:
            # 门户显示未在线、AC 却说 IP 已在线，一般是刚注销还没释放完，等几秒重试
            run_async(portal.login, account, password, suffix, 6.0, on_done=done)

    @Slot()
    def logout(self) -> None:
        if self._busy:
            return
        self._set_busy(True)

        def done(ok, err):
            self._set_busy(False)
            self._set_throttle(0)
            if err is not None:
                log_event(f"注销失败：{describe(err)}")
                self.toast.emit("error", "注销失败", describe(err), 8000)
                return
            log_event("注销成功" if ok else "注销：门户回复本来就不在线")
            if ok:
                self.toast.emit("success", "已注销", "", 3000)
            else:
                self.toast.emit("warning", "注销失败", "可能本来就没有登录", 3000)
            # 手动注销后不要马上又被自动重连登回去
            if self.settings.get("Reconnect", "Enabled") and not self._recon_paused:
                self._recon_paused = True
                self._recon_note("info", "手动注销，自动重连已暂停；再次登录后自动恢复")
            QTimer.singleShot(400, self.refresh)

        run_async(self.make_portal().logout, on_done=done)

    # ---- 请求排队 ----

    def _on_wait(self, seconds: float) -> None:
        self._set_throttle(seconds)

    def _set_throttle(self, seconds: float) -> None:
        self._throttle_until = time.monotonic() + seconds if seconds > 0 else 0.0
        if seconds > 0:
            self._throttleTimer.start()
        else:
            self._throttleTimer.stop()
        self.throttleChanged.emit()

    def _tick_throttle(self) -> None:
        if time.monotonic() >= self._throttle_until:
            self._throttle_until = 0.0
            self._throttleTimer.stop()
        self.throttleChanged.emit()

    # ---- 自动重连 ----
    #
    # 不靠高频轮询：
    # 1. 系统报告网络状态变化（断开、恢复、被门户拦截）时，稍等 2 秒查一次；
    # 2. 任何一次查状态（启动、手动刷新、自动刷新、登录后）看到离线，都直接用上，不另外再查；
    # 3. 兜底定时器：距离上次拿到状态满“检测间隔”才查，每拿到一次状态就重新计时。

    def reconnect_interval(self) -> int:
        v = self.settings.get("Reconnect", "Interval")
        return min(max(v, RECONNECT_MIN_INTERVAL), RECONNECT_MAX_INTERVAL)

    def _recon_note(self, kind: str, text: str) -> None:
        """自动重连页的一条记录；kind 为 success/info/warning/error。"""
        self._recon_history.append({"time": datetime.now().strftime("%m-%d %H:%M:%S"), "kind": kind, "text": text})
        self.reconnectChanged.emit()

    def reconnect_account(self) -> Account | None:
        """重连用的账号：设置里指定的（要有保存密码），否则最近使用且有密码的那个。"""
        chosen = self.store.get(self.settings.get("Reconnect", "Account"))
        if chosen and chosen.password:
            return chosen
        last = self.store.last()
        if last and last.password:
            return last
        return next((a for a in self.store.accounts if a.password), None)

    def _apply_reconnect_timer(self) -> None:
        self.reconnectTimer.setInterval(self.reconnect_interval() * 1000)
        if self.settings.get("Reconnect", "Enabled"):
            self.reconnectTimer.start()   # 重新计时
        else:
            self.reconnectTimer.stop()
            self.networkTimer.stop()

    def _watch_network(self) -> None:
        if QNetworkInformation is None:
            return
        try:
            loaded = QNetworkInformation.loadBackendByFeatures(QNetworkInformation.Feature.Reachability)
        except Exception:   # 没有可用的后端（比如精简打包时漏了插件）
            loaded = False
        info = QNetworkInformation.instance() if loaded else None
        if info is None:
            return
        self._net = info
        info.reachabilityChanged.connect(self._on_reachability)
        if info.supports(QNetworkInformation.Feature.CaptivePortal):
            info.isBehindCaptivePortalChanged.connect(self._on_captive_portal)

    def _on_reachability(self, reachability) -> None:
        R = QNetworkInformation.Reachability
        names = {R.Disconnected: "断开", R.Local: "仅本机", R.Site: "仅内网", R.Online: "可上网", R.Unknown: "未知"}
        log_event(f"系统报告网络状态：{names.get(reachability, str(reachability))}")
        portal_module.forget_terminal()   # 可能换了网络、换了 IP
        if reachability == R.Disconnected:
            self.networkTimer.stop()
            return
        self._schedule_network_check("网络状态变化")

    def _on_captive_portal(self, behind: bool) -> None:
        if behind:
            log_event("系统报告：需要登录门户才能上网")
            self._schedule_network_check("系统提示需要登录门户")

    def _schedule_network_check(self, reason: str) -> None:
        self._network_reason = reason
        self.networkTimer.start()   # 一连串变化只查一次

    def _network_check(self) -> None:
        log_event(f"{self._network_reason}，检测一次在线状态")
        if self.settings.get("Reconnect", "Enabled"):
            self.reconnect_tick()
        elif not self._busy:
            self.refresh(True)   # 没开自动重连也更新一下首页的状态

    @Slot()
    def reconnect_tick(self) -> None:
        """主动检测一次（兜底定时器、网络变化、开启/恢复时）。已有查询在进行时等它的结果，不重复请求。"""
        if not self.settings.get("Reconnect", "Enabled"):
            return
        if self._busy or self._recon_busy:
            self.reconnectTimer.start()
            return
        self._recon_force = True
        self.refresh(True)

    def _observe(self, st: Status | None, active: bool = False) -> None:
        """每拿到一次状态都会调到这里：开着自动重连、看到离线就重新登录。"""
        active = active or self._recon_force
        self._recon_force = False
        if not self.settings.get("Reconnect", "Enabled"):
            return
        self.reconnectTimer.start()   # 兜底定时器重新计时
        self._recon_last_check = datetime.now().strftime("%H:%M:%S")
        state = "unreachable" if st is None else ("online" if st.online else "offline")
        changed, self._recon_last_state = state != self._recon_last_state, state
        if state == "unreachable":
            if changed:
                self._recon_note("warning", "连不上校园网门户，等网络恢复后再检测")
        elif state == "online":
            if changed:
                self._recon_note("success", "在线，继续监控")
        elif not self._recon_paused and not self._busy and not self._recon_busy:
            if not active and time.monotonic() < self._recon_not_before:
                pass   # 刚失败过，等冷却结束或下一次主动检测
            else:
                acc = self.reconnect_account()
                if acc is None:
                    if changed:
                        self._recon_note("warning", "检测到离线，但没有保存了密码的账号可用")
                else:
                    self._recon_busy = True
                    # 记录会一直留着，不随右上角眼睛变化，所以这里不写账号号码
                    self._recon_note("info", f"检测到离线，正在用「{acc.note or '••••••••'} · {acc.carrier}」重新登录")
                    log_event(f"自动重连：检测到离线 acct={acc.full}")
                    self._login(acc.account, acc.password, acc.isp, True, source="reconnect",
                                on_result=self._reconnect_done, terminal_type=acc.terminal_type)
                    if not self._busy:   # 没能开始登录
                        self._recon_busy = False
        self.reconnectChanged.emit()

    def _reconnect_done(self, ok: bool, err) -> None:
        self._recon_busy = False
        self.reconnectTimer.start()
        if ok:
            self._recon_count += 1
            self._recon_failures = 0
            self._recon_not_before = 0.0
            self._recon_last_state = "online"
            self._recon_note("success", "自动重连成功")
            return
        self._recon_failures += 1
        self._recon_not_before = time.monotonic() + RECONNECT_COOLDOWN * self._recon_failures
        self._recon_note("error", f"自动重连失败：{describe(err)}")
        if self._recon_failures >= RECONNECT_MAX_FAILURES:
            self._recon_paused = True
            self._recon_note("error", f"连续失败 {RECONNECT_MAX_FAILURES} 次，已暂停。检查账号密码后点“恢复”")
            log_event(f"自动重连连续失败 {RECONNECT_MAX_FAILURES} 次，已暂停")
            self.toast.emit("warning", "自动重连已暂停", f"连续失败 {RECONNECT_MAX_FAILURES} 次，请检查账号密码", 6000)

    @Slot(bool)
    def setReconnectEnabled(self, on: bool) -> None:
        if self.settings.get("Reconnect", "Enabled") == on:
            return
        self.settings.set("Reconnect", "Enabled", on)
        self._recon_paused = False
        self._recon_failures = 0
        self._recon_not_before = 0.0
        self._recon_last_state = ""
        self._apply_reconnect_timer()
        self._recon_note("info", "已开启自动重连" if on else "已关闭自动重连")
        log_event("开启自动重连" if on else "关闭自动重连")
        self.settingsChanged.emit()
        if on:
            self.reconnect_tick()   # 开启后立刻检测一次

    @Slot(int)
    def setReconnectInterval(self, seconds: int) -> None:
        """兜底检测间隔，任意秒数，限制在 5 秒到 24 小时之间。"""
        seconds = min(max(int(seconds), RECONNECT_MIN_INTERVAL), RECONNECT_MAX_INTERVAL)
        if self.settings.get("Reconnect", "Interval") == seconds:
            self.reconnectChanged.emit()   # 界面上输入的值被夹到范围内时，让输入框回到实际值
            return
        self.settings.set("Reconnect", "Interval", seconds)
        self._apply_reconnect_timer()
        self.reconnectChanged.emit()

    @Slot(str)
    def setReconnectAccount(self, account: str) -> None:
        self.settings.set("Reconnect", "Account", account)
        self.reconnectChanged.emit()

    @Slot()
    def resumeReconnect(self) -> None:
        self._recon_paused = False
        self._recon_failures = 0
        self._recon_not_before = 0.0
        self._recon_last_state = ""
        self._recon_note("info", "已恢复自动重连")
        self.reconnect_tick()

    @Slot()
    def clearReconnectHistory(self) -> None:
        self._recon_history.clear()
        self.reconnectChanged.emit()

    # ---- 日志 ----

    def _flush_log(self) -> None:
        for h in portal_log.handlers:
            if isinstance(h, logfiles.ArchivingFileHandler) and h.path == self.log_path:
                h.flush()

    @Slot()
    def refreshLogInfo(self) -> None:
        self.logInfoChanged.emit()

    @Slot("QVariant", result=bool)
    def exportLog(self, target) -> bool:
        """把全部日志（含已压缩的旧日志）按时间顺序合并成一个文本文件。右上角眼睛关着时账号、IP、MAC 打码。"""
        path = Path(target.toLocalFile() if isinstance(target, QUrl) else QUrl(str(target)).toLocalFile() or str(target))
        self._flush_log()
        try:
            logfiles.export(self.log_path.parent, self.log_path, path,
                            transform=None if self._revealed else mask_log)
        except OSError as e:
            self.toast.emit("error", "导出失败", str(e), 6000)
            return False
        self.toast.emit("success", "日志已导出", str(path), 4000)
        return True

    @Slot(str)
    def switchTo(self, account: str) -> None:
        """一键切换：回填这个账号并登录；当前在线的是别的账号时会先自动注销。"""
        acc = self.store.get(account)
        if not acc or self._busy:
            return
        self.pageRequested.emit(0)
        self.fillRequested.emit(acc.account)
        if not acc.password:
            self.toast.emit("warning", "这个账号没有保存密码", "请输入密码后点击登录", 4000)
            return
        self.login(acc.account, acc.password, acc.isp, True, acc.terminal_type)

    # ---- 账号 ----

    @Slot(str, str, str, str, str, result=bool)
    @Slot(str, str, str, str, str, int, result=bool)
    def saveAccount(self, old_account: str, account: str, isp: str, note: str, password: str,
                    terminal_type: int = -1) -> bool:
        account = account.strip()
        if not account:
            return False
        old = self.store.get(old_account) if old_account else None
        acc = Account(account, isp, note.strip(), password, terminal_type=int(terminal_type))
        if old:
            acc.last_used = old.last_used
            acc = self.store.rename(old.account, acc)
            self.toast.emit("success", "已保存", self.account_title(acc), 3000)
        else:
            replaced = self.store.get(account) is not None
            acc = self.store.upsert(acc)
            self.toast.emit("success", "已更新" if replaced else "已保存", self.account_title(acc), 3000)
        self.accountsChanged.emit()
        return True

    @Slot(str)
    def deleteAccount(self, account: str) -> None:
        acc = self.store.get(account)
        if not acc:
            return
        name = self.account_title(acc)
        self.store.remove(account)
        self.accountsChanged.emit()
        self.toast.emit("success", "已删除", name, 3000)

    def account_title(self, acc: Account) -> str:
        """提示里怎么称呼一个账号：有备注只显示备注；没备注时，隐藏状态下打码。"""
        name = acc.note or (acc.account if self._revealed else "••••••••")
        return f"{name} · {acc.carrier}"

    # ---- 其他 ----

    @Slot(bool)
    def setRevealed(self, on: bool) -> None:
        if self._revealed != on:
            self._revealed = on
            self.revealedChanged.emit()

    @Slot(str, str)
    def copy(self, text: str, title: str) -> None:
        if text:
            QGuiApplication.clipboard().setText(text)
            self.toast.emit("success", "已复制", title, 1500)

    @Slot(str, "QVariant")
    def setSetting(self, key: str, value) -> None:
        mapping = {
            "autoLogin": ("Login", "AutoLogin"),
            "closeAfterAutoLogin": ("Login", "CloseAfterAutoLogin"),
            "loginExpanded": ("Login", "FormExpanded"),
            "portalHost": ("Portal", "Host"),
            "terminalType": ("Portal", "TerminalType"),
            "theme": ("Appearance", "Theme"),
            "font": ("Appearance", "Font"),
        }
        group, name = mapping[key]
        if key == "portalHost":
            value = str(value).strip() or f"http://{DEFAULT_HOST}"
        elif key == "terminalType":
            value = int(value)   # QML 传来的数字可能是浮点
            if value not in {v for _, v in TERMINAL_TYPES}:
                return
        if self.settings.get(group, name) == value:
            return
        self.settings.set(group, name, value)
        self.settingsChanged.emit()
        if key == "theme":
            apply_theme(value)
        elif key == "font":
            self.toast.emit("info", "重启软件后生效", "", 3000)

    @Slot()
    def openDataDir(self) -> None:
        # 还没保存过任何东西时目录不存在，资源管理器会打不开
        app_dir().mkdir(parents=True, exist_ok=True)
        QDesktopServices.openUrl(QUrl.fromLocalFile(str(app_dir())))

    @Slot()
    def openProjectPage(self) -> None:
        QDesktopServices.openUrl(QUrl(APP_URL))

    @Slot()
    def openSelfService(self) -> None:
        QDesktopServices.openUrl(QUrl(SELF_SERVICE_URL))


def apply_theme(theme: str) -> None:
    """Light / Dark / Auto（跟随系统，系统切换深浅色时自动跟着变）。"""
    scheme = {"Light": Qt.ColorScheme.Light, "Dark": Qt.ColorScheme.Dark}.get(theme, Qt.ColorScheme.Unknown)
    QGuiApplication.styleHints().setColorScheme(scheme)


def resource_dir() -> Path:
    return Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parent))
