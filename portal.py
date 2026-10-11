"""Dr.COM 校园网门户协议。只依赖标准库。

和门户网页（a41.js / a40.js，jsVersion 4.2.1）的做法保持一致：
- 网页加载时先调 /eportal/portal/page/loadConfig，拿到认证方式（login_method）、是否加账号前缀（account_prefix）、
  注销方式（ac_logout）、后台类型（register_mode）、注销时是否解绑 MAC（un_bind_mac）等，登录、注销的参数都取自这里，
  不能写死。本程序同样先读配置（缓存 CONFIG_TTL 秒），读不到时用本校实测的值（DEFAULT_CONFIG）。
- 本校 login_method=1（Portal 协议，由 AC 放行上网），登录、注销走 801 端口的 /eportal/portal/login、
  /eportal/portal/logout。只调 /drcom/login 时内核会记为在线，但 AC 不放行，表现为“显示在线却没网”。
- 注销（页面的 wc()）：门户开了 un_bind_mac 且 register_mode 为 1/3/4 时，网页调的是 /eportal/portal/mac/unbind
  （注销并解绑本机 MAC），解绑失败才退回 /eportal/portal/logout；否则直接 logout，ac_logout、register_mode 用配置里的值。
- 在线状态和页面一样查 /drcom/chkstatus。
- 另外和网页一样提供：账号信息（page/loadUserInfo，流量/时长用量和套餐等）、门户公告（notice/get_notice_content）。

门户可能限制请求频率（推测同一终端约 5 秒一次），所以登录、注销这类认证请求经过一个全局闸门（RequestGate），
两次之间至少隔 AUTH_INTERVAL 秒；门户回复“繁忙/太频繁”时退避后自动重发。登录、注销要带的本机 IP、MAC、VLAN
优先用最近一次查状态的结果，不再每次先查一遍，切换账号因此只有“注销 → 登录”两次请求。

命令行用法（不开界面快速测试）：
    python portal.py status
    python portal.py config             # 门户 page/loadConfig 下发的认证配置
    python portal.py login 账号 [--isp 校园网|联通|电信|移动]
    python portal.py logout
设置环境变量 XIAOYUAN_PORTAL=http://127.0.0.1:18080 可以改连本地假门户。
"""

from __future__ import annotations

import base64
import binascii
import html
import json
import logging
import os
import random
import re
import socket
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path

from logfiles import ArchivingFileHandler

DEFAULT_HOST = "10.253.0.1"
SELF_SERVICE_URL = "http://10.255.255.3:8080/Self"
JS_VERSION = "4.2.1"
USER_AGENT = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
              "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")
# 终端类型，对应页面 util.getTermType()；登录时作为 terminal_type 发送，也决定账号前缀
TERMINAL_TYPES = [("电脑", 1), ("手机", 2), ("平板（横屏）", 3), ("平板（竖屏）", 4), ("其他", 0)]
DEFAULT_TERMINAL_TYPE = 1


def account_prefix(cfg_prefix: int, terminal_type: int) -> str:
    """门户配置 account_prefix=1 时页面给账号加的前缀：手机 ",1,"，其他终端 ",0,"（a40.js login.init）。"""
    if cfg_prefix != 1:
        return ""
    return ",1," if terminal_type == 2 else ",0,"
CONFIG_TTL = 600.0        # page/loadConfig 的结果缓存这么多秒
NO_MAC = "000000000000"
AUTH_INTERVAL = 5.0       # 登录、注销请求之间的最短间隔（秒）
MAX_BACKOFF = 60.0        # 门户说太频繁时，退避的上限（秒）
RATE_RETRIES = 2          # 门户说太频繁时自动重发的次数
TERMINAL_TTL = 120.0      # 查状态得到的本机 IP/MAC/VLAN 在这么多秒内直接复用

# 运营商：后缀直接拼在账号后面，取值与门户页 ISP_select 下拉框一致
CARRIERS = [
    ("校园网", ""),
    ("中国联通", "@unicom"),
    ("中国电信", "@telecom"),
    ("中国移动", "@cmcc"),
]

log = logging.getLogger("xiaoyuan.portal")


def enable_log(path: Path) -> ArchivingFileHandler:
    """把每次请求和门户的原始返回写进日志（密码已打码），出问题时用来排查。

    当天的写在 path，旧的按天压缩成 .log.gz 放在同一目录，过期自动删除（见 logfiles.py）。
    """
    handler = ArchivingFileHandler(path)
    handler.setFormatter(logging.Formatter("%(asctime)s %(message)s"))
    log.addHandler(handler)
    log.setLevel(logging.INFO)
    return handler


# ---------------------------------------------------------------- 请求频率

class RequestGate:
    """同一门户的认证请求排队发出：两次之间至少隔 interval 秒；门户说太频繁时把间隔加倍退避，成功后恢复。

    线程安全；等待发生在调用者的线程里（后台线程），等待前回调 on_wait(秒数)，界面可以据此显示倒计时。
    """

    def __init__(self, interval: float | None = None):
        self.interval = AUTH_INTERVAL if interval is None else interval   # 运行时再读，set_auth_interval 之后新建的也生效
        self.backoff = 0.0
        self._lock = threading.Lock()
        self._next = 0.0

    def wait(self) -> float:
        """等到可以发下一个请求，返回等了多少秒。"""
        with self._lock:   # 持锁等待：多个请求按先来后到依次发出
            delay = self._next - time.monotonic()
            if delay > 0:
                if _wait_listener:
                    _wait_listener(delay)
                time.sleep(delay)
            self._next = time.monotonic() + max(self.interval, self.backoff)
            return max(delay, 0.0)

    def throttled(self) -> float:
        """门户说太频繁：加长间隔，返回新的间隔。"""
        with self._lock:
            self.backoff = min(MAX_BACKOFF, max(self.interval, 1.0, self.backoff) * 2)
            self._next = time.monotonic() + self.backoff
            return self.backoff

    def succeeded(self) -> None:
        self.backoff = 0.0

    def remaining(self) -> float:
        return max(0.0, self._next - time.monotonic())


_gates: dict[str, RequestGate] = {}
_terminals: dict[str, tuple[float, str, str, str, str]] = {}   # host -> (时间, IP, MAC, VLAN, 在线账号)
_configs: dict[str, tuple[float, "PortalConfig"]] = {}         # host -> (时间, 门户配置)
_wait_listener = None


def gate_for(host: str) -> RequestGate:
    gate = _gates.get(host)
    if gate is None:
        gate = _gates[host] = RequestGate()
    return gate


def set_auth_interval(seconds: float) -> None:
    """改所有门户的认证请求最短间隔（测试里用来加速）。"""
    global AUTH_INTERVAL
    AUTH_INTERVAL = seconds
    for g in _gates.values():
        g.interval = seconds
        g.backoff = 0.0
        g._next = 0.0


def set_wait_listener(fn) -> None:
    """fn(秒数)：认证请求因为频率限制要排队时调用（在后台线程里）。"""
    global _wait_listener
    _wait_listener = fn


def forget_terminal() -> None:
    """网络变了（换了 Wi-Fi、重新拿到 IP），下次登录前重新查本机 IP/MAC 和门户配置。"""
    _terminals.clear()
    _configs.clear()


def looks_rate_limited(d: dict) -> bool:
    """门户的回复是不是在说“太频繁 / 繁忙，稍后再试”。"""
    if _int_or_none(_str(d, "ret_code")) == 3:   # 系统繁忙
        return True
    msg = _decode_msg(_str(d, "msg")).lower()
    return any(k in msg for k in ("频繁", "过快", "too many", "too frequent", "frequently", "rate limit", "busy"))


def carrier_name(suffix: str) -> str:
    for name, s in CARRIERS:
        if s == (suffix or ""):
            return name
    return CARRIERS[0][0]


class PortalError(Exception):
    """连不上门户或门户返回异常。"""


class RateLimitedError(PortalError):
    """重发几次后门户仍然说太频繁。"""


class LoginError(Exception):
    """门户明确拒绝了登录，消息是给用户看的原因；code 为门户的 ret_code。"""

    def __init__(self, message: str, code: int | None = None):
        super().__init__(message)
        self.code = code


@dataclass
class Status:
    online: bool
    account: str = ""
    ip: str = ""
    minutes: int = 0
    flow_kb: int = 0
    mac: str = NO_MAC
    vlan: str = "0"
    raw: dict = field(default_factory=dict)   # chkstatus 的全部原始字段


@dataclass
class PortalConfig:
    """page/loadConfig 里和认证有关的几项，取值方式同 a41.js page.getPageInfo（parseInt(x) || 0）。"""
    login_method: int = 1      # 认证方式：1/9 Portal 协议（本校为 1）
    account_prefix: int = 1    # 1：账号前加 ",0,"（电脑端）
    ac_logout: int = 0         # 多 AC 注销方式：0 默认 1 通过 Radius 2 通过全业务接口
    register_mode: int = 1     # 后台类型：0 私有云 1 BS 后台 2 酒店版 3 访客系统 4 普教系统
    un_bind_mac: int = 0       # 1：本机注销时解绑 MAC
    user_info: str = ""        # 成功页要显示的账号信息字段，逗号分隔，空表示门户没配置
    program_index: str = ""    # 方案索引、页面索引：查账号信息时要带
    page_index: str = ""
    loaded: bool = False       # False 表示没读到门户配置，用的是默认值
    raw: dict = field(default_factory=dict)

    @classmethod
    def from_json(cls, data: dict) -> "PortalConfig":
        def num(key: str) -> int:
            try:
                return int(float(str(data.get(key, "")).strip()))
            except ValueError:
                return 0
        def text(key: str) -> str:
            v = data.get(key)
            return "" if v is None else str(v).strip()
        return cls(num("login_method"), num("account_prefix"), num("ac_logout"), num("register_mode"),
                   num("un_bind_mac"), text("user_info"),
                   text("program_index"), text("page_index"), True, data)

    @property
    def unbind_on_logout(self) -> bool:
        """对应页面 wc()：un_bind_mac==1 且 register_mode 为 1/3/4 时，注销走 mac/unbind。"""
        return self.un_bind_mac == 1 and self.register_mode in (1, 3, 4)


DEFAULT_CONFIG = PortalConfig()   # 读不到 loadConfig 时用本校实测的值


def ip_to_int(ip: str) -> str:
    """页面 util.ipToParseInt：点分 IP 转成十进制整数（mac/unbind 用）。"""
    try:
        a, b, c, d = (int(x) for x in ip.split("."))
    except ValueError:
        return "0"
    return str((a << 24 | b << 16 | c << 8 | d) & 0xFFFFFFFF)


@dataclass
class UserInfo:
    """page/loadUserInfo 的结果。fields 为 (字段名, 标题, 值)，顺序同门户配置的 user_info。"""
    fields: list = field(default_factory=list)
    progress: float | None = None   # flow_progress：已用百分比（0~100），门户没给时为 None
    progress_type: int = 0          # 1 按流量 2 按时长 其他 不限
    used: str = ""                  # 已用（流量或时长，门户格式化好的文字）
    available: str = ""             # 可用
    raw: dict = field(default_factory=dict)


# 门户没给字段标题（user_info_lang）时用的常见字段名
USER_INFO_TITLES = {
    "account": "账号", "user_name": "姓名", "username": "姓名", "real_name": "姓名",
    "use_flow": "已用流量", "available_flow": "可用流量", "use_time": "已用时长", "available_time": "可用时长",
    "balance": "余额", "fee": "余额", "package_name": "套餐", "service_name": "套餐", "group_name": "用户组",
    "expire_time": "到期时间", "end_time": "到期时间", "online_num": "在线设备",
}
_USER_INFO_SKIP = {"flow_progress", "progress_type"}


def strip_html(text: str) -> str:
    text = re.sub(r"(?i)<br\s*/?>|</p>|</div>", "\n", text)
    text = re.sub(r"<[^>]+>", "", text)
    return re.sub(r"\n\s*\n+", "\n", html.unescape(text)).strip()


UNLIMITED = 4294967295   # oltime / olflow 为 32 位最大值时表示不限


class Portal:
    def __init__(self, base: str | None = None, timeout: float = 8.0, terminal_type: int = DEFAULT_TERMINAL_TYPE):
        """base 为门户根地址，默认 http://10.253.0.1；也可由环境变量 XIAOYUAN_PORTAL 指定。

        真实门户的 eportal 接口在 801 端口；base 自带端口时（比如假门户）两类接口共用同一个地址。
        """
        base = (base or os.environ.get("XIAOYUAN_PORTAL") or f"http://{DEFAULT_HOST}").strip().rstrip("/")
        if "://" not in base:
            base = "http://" + base
        parts = urllib.parse.urlsplit(base)
        eportal_base = base if parts.port else f"{parts.scheme}://{parts.hostname}:801"
        self.host = parts.hostname or DEFAULT_HOST
        self.drcom_url = base + "/drcom/"
        self.eportal_url = eportal_base + "/eportal/portal/"
        self.timeout = timeout
        valid = {v for _, v in TERMINAL_TYPES}
        self.terminal_type = terminal_type if terminal_type in valid else DEFAULT_TERMINAL_TYPE
        self.gate = gate_for(self.host)
        # 门户在内网，绕过系统代理
        self._opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    # ---- 接口 ----

    def status(self) -> Status:
        d = self._call(self.drcom_url + "chkstatus", "dr1002")
        mac = _str(d, "ss4").replace("-", "").replace(":", "")
        st = Status(
            online=_ok(d),
            account=_str(d, "uid"),
            ip=_str(d, "v46ip") or _str(d, "ss5") or _str(d, "v4ip"),
            minutes=_num(d, "time"),
            flow_kb=_num(d, "flow"),
            mac=mac if mac and mac not in (NO_MAC, "111111111111") else NO_MAC,
            vlan=_str(d, "vid") or "0",
            raw=d,
        )
        if st.ip:
            _terminals[self.host] = (time.monotonic(), st.ip, st.mac, st.vlan, st.account if st.online else "")
        return st

    def config(self, fresh: bool = False) -> PortalConfig:
        """门户配置（page/loadConfig），缓存 CONFIG_TTL 秒；读不到时返回 DEFAULT_CONFIG。参数同 a41.js getPageInfo。"""
        cached = _configs.get(self.host)
        if cached and not fresh and time.monotonic() - cached[0] < CONFIG_TTL:
            return cached[1]
        ip, _, vlan, _ = self._terminal()
        b64 = lambda v: base64.b64encode(v.encode()).decode()   # noqa: E731
        try:
            d = self._call(self.eportal_url + "page/loadConfig", "dr1001", [
                ("program_index", ""),
                ("wlan_vlan_id", vlan),
                ("wlan_user_ip", b64(ip)),
                ("wlan_user_ipv6", ""),
                ("wlan_user_ssid", ""),
                ("wlan_user_areaid", ""),
                ("wlan_ac_ip", ""),
                ("wlan_ap_mac", NO_MAC),
                ("gw_id", NO_MAC),
            ])
        except PortalError as e:
            log.info("读取门户配置失败（%s），使用默认配置", e)
            return DEFAULT_CONFIG
        data = d.get("data")
        if not isinstance(data, dict):
            log.info("门户配置格式不对，使用默认配置")
            return DEFAULT_CONFIG
        cfg = PortalConfig.from_json(data)
        log.info("门户配置：login_method=%s account_prefix=%s ac_logout=%s register_mode=%s un_bind_mac=%s",
                 cfg.login_method, cfg.account_prefix, cfg.ac_logout, cfg.register_mode, cfg.un_bind_mac)
        _configs[self.host] = (time.monotonic(), cfg)
        return cfg

    def login(self, account: str, password: str, suffix: str = "", wait: float = 0) -> None:
        """登录，失败抛 LoginError。account 为账号，suffix 为运营商后缀。

        刚注销时 AC 还没释放旧会话，会返回“终端 IP 已经在线”（ret_code=2）；wait 秒内遇到这种情况就重试，
        重试同样受请求间隔限制（默认 5 秒一次）。
        """
        deadline = time.monotonic() + wait
        refreshed = False
        while True:
            try:
                self._login_once(account, password, suffix)
                return
            except LoginError as e:
                if e.code == 998 and not refreshed:
                    # 参数不全：多半是复用的本机 IP 已经过时，重新查一次再试
                    refreshed = True
                    forget_terminal()
                    continue
                if e.code != 2 or time.monotonic() + self.gate.remaining() >= deadline:
                    raise
                log.info("AC 还没释放旧会话，稍后重试")
                if self.gate.remaining() == 0:   # 有请求间隔时由闸门负责等待，没有时自己隔 1 秒
                    time.sleep(min(1.0, max(0.0, deadline - time.monotonic())))

    def _login_once(self, account: str, password: str, suffix: str) -> None:
        """对应页面 login.login_portal（login_method 1/9）。"""
        cfg = self.config()
        ip, mac, _, _ = self._terminal()
        prefix = account_prefix(cfg.account_prefix, self.terminal_type)
        d = self._auth_call(self.eportal_url + "login", "dr1003", [
            ("login_method", str(self._login_method(cfg))),
            ("user_account", prefix + account + suffix),
            ("user_password", password),
            ("wlan_user_ip", ip),
            ("wlan_user_ipv6", ""),
            ("wlan_user_mac", mac),
            ("wlan_ac_ip", ""),
            ("wlan_ac_name", ""),
            ("jsVersion", JS_VERSION),
            ("terminal_type", str(self.terminal_type)),
            ("lang", "zh-cn"),
        ])
        if not _ok(d):
            code = _int_or_none(_str(d, "ret_code"))
            raise LoginError(self._describe_login_error(d, ip, mac), code)

    def logout(self) -> bool:
        """注销，返回门户是否确认成功（本来就不在线时可能返回 False）。对应页面 wc()。

        门户要求注销时解绑 MAC 的，先调 mac/unbind（user.unbind_mac），成功就算注销完成；
        失败或不需要解绑时调 logout（logout.logout_portal），ac_logout、register_mode 用门户配置里的值。
        """
        cfg = self.config()
        ip, mac, vlan, account = self._terminal()
        if cfg.unbind_on_logout:
            if not account:
                # 解绑要带在线账号；缓存里没有（或缓存时不在线）就重新查一次
                st = self._fresh_status()
                ip, mac, vlan, account = (st.ip or ip), st.mac, st.vlan, (st.account if st.online else "")
            if account and self._unbind_mac(account, mac, ip):
                return True
        d = self._auth_call(self.eportal_url + "logout", "dr1004", [
            ("login_method", str(self._login_method(cfg))),
            ("user_account", "drcom"),
            ("user_password", "123"),
            ("ac_logout", str(cfg.ac_logout)),
            ("register_mode", str(cfg.register_mode)),
            ("wlan_user_ip", ip),
            ("wlan_user_ipv6", ""),
            ("wlan_vlan_id", vlan),
            ("wlan_user_mac", mac),
            ("wlan_ac_ip", ""),
            ("wlan_ac_name", ""),
            ("jsVersion", JS_VERSION),
        ])
        return _ok(d)

    def _unbind_mac(self, account: str, mac: str, ip: str) -> bool:
        """对应页面 user.unbind_mac('', '', 1)：注销并解绑本机 MAC。"""
        d = self._auth_call(self.eportal_url + "mac/unbind", "dr1006", [
            ("user_account", account.strip()),
            ("wlan_user_mac", mac.strip().upper()),
            ("wlan_user_ip", ip_to_int(ip.strip())),
            ("jsVersion", JS_VERSION),
        ])
        if _ok(d):
            return True
        log.info("解绑 MAC 失败（%s），改用普通注销", _str(d, "msg") or "门户没给原因")
        return False

    @staticmethod
    def _login_method(cfg: PortalConfig) -> int:
        # 只实现了网页里的 Portal 协议分支（1/9）；门户报了别的方式时仍按本校实测的 1 处理，并记日志
        if cfg.login_method in (1, 9):
            return cfg.login_method
        log.info("门户配置的认证方式 login_method=%s 未实现，按 1 处理", cfg.login_method)
        return 1

    def user_info(self) -> UserInfo | None:
        """账号信息（page/loadUserInfo），对应成功页 editInfo.query_user_info。不在线或门户不支持时返回 None。"""
        cfg = self.config()
        ip, mac, _, account = self._terminal()
        if not account:
            st = self._fresh_status()
            ip, mac, account = (st.ip or ip), st.mac, (st.account if st.online else "")
        if not account:
            return None
        d = self._call(self.eportal_url + "page/loadUserInfo", "dr1007", [
            ("lang", "zh-cn"),
            ("program_index", cfg.program_index),
            ("page_index", cfg.page_index),
            ("user_account", account.strip()),
            ("wlan_user_ip", ip_to_int(ip)),
            ("wlan_user_mac", mac),
        ])
        data = d.get("user_info")
        if not (_str(d, "code") == "1" or _ok(d)) or not isinstance(data, dict):
            return None
        titles = d.get("user_info_lang") if isinstance(d.get("user_info_lang"), dict) else {}
        keys = [k.strip() for k in cfg.user_info.split(",") if k.strip()] or list(data)
        fields = []
        for k in keys:
            if k in _USER_INFO_SKIP or k not in data:
                continue
            title = str(titles.get(k) or USER_INFO_TITLES.get(k) or k)
            fields.append((k, title, "" if data[k] is None else str(data[k]).strip()))
        try:
            progress = min(100.0, max(0.0, float(data["flow_progress"]))) if "flow_progress" in data else None
        except (TypeError, ValueError):
            progress = None
        ptype = _num(data, "progress_type")
        used, available = (_str(data, "use_flow"), _str(data, "available_flow")) if ptype == 1 else \
            (_str(data, "use_time"), _str(data, "available_time")) if ptype == 2 else ("", "")
        return UserInfo(fields, progress, ptype, used, available, data)

    def notice(self) -> str:
        """门户公告（notice/get_notice_content），去掉 HTML 标签；没有公告返回空串。"""
        d = self._call(self.eportal_url + "notice/get_notice_content", "dr1008")
        return strip_html(_str(d, "content")) if _ok(d) else ""

    def switch(self, account: str, password: str, suffix: str = "", wait: float = 8.0) -> None:
        """先注销当前账号再登录新账号。两次请求之间本来就要隔 AUTH_INTERVAL 秒，一般正好够 AC 释放旧会话。"""
        self.logout()
        self.login(account, password, suffix, wait)

    def _terminal(self) -> tuple[str, str, str, str]:
        """门户眼里本机的 IP、MAC、VLAN 和在线账号（不在线为空）。页面也是从 chkstatus 和页面变量里取的同一批值。

        最近 TERMINAL_TTL 秒内查过状态就直接用那次的结果，少发一个请求。
        """
        cached = _terminals.get(self.host)
        if cached and time.monotonic() - cached[0] < TERMINAL_TTL:
            return cached[1], cached[2], cached[3], cached[4]
        st = self._fresh_status()
        return st.ip or self._local_ip(), st.mac, st.vlan, (st.account if st.online else "")

    def _fresh_status(self) -> Status:
        try:
            return self.status()
        except PortalError:
            return Status(False)

    def _local_ip(self) -> str:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect((self.host, 80))
            return s.getsockname()[0]

    # ---- 错误信息，对应 a40.js 中 error.portal_login_err ----

    def _describe_login_error(self, d: dict, ip: str, mac: str) -> str:
        msg = _decode_msg(_str(d, "msg"))
        code = _int_or_none(_str(d, "ret_code"))
        fixed = {
            2: "本机 IP 已经在线（可能刚注销还没生效，稍等几秒再试）",
            3: "系统繁忙，请稍后再试",
            5: "REQ_CHALLENGE 失败，请联系网络中心",
            6: "REQ_CHALLENGE 超时，请联系网络中心",
            7: "Radius 认证失败",
            8: "Radius 认证超时",
            9: "Radius 下线失败",
            10: "Radius 下线超时",
            11: "发生其他错误，请稍后再试",
            998: "Portal 协议参数不全，请稍后再试",
        }
        if code in fixed:
            return fixed[code]
        if code in (1, 4) or code is None:
            if msg:
                return self._explain(msg)
            prompt = self._lookup("err_code", [("ip", ip), ("mac", mac)])
            return prompt or "AC 认证失败"
        return msg or "AC 认证失败"

    def _explain(self, code: str) -> str:
        """错误代码先问门户要中文提示，问不到再用本地的常见解释。"""
        if "Mac, IP, NASip, PORT err(6)" in code or code.startswith("error bind vid"):
            return f"首次登录需要先修改密码，请到自助服务平台（{SELF_SERVICE_URL}）修改后再登录"
        prompt = self._lookup("err_code/loadErrorPrompt", [("error_code", code)])
        if prompt and prompt != code:
            return prompt
        hint = _hint(code)
        return f"{hint}（{code}）" if hint else code

    def _lookup(self, path: str, args: list[tuple[str, str]]) -> str:
        try:
            d = self._call(self.eportal_url + path, "dr1005", args)
        except PortalError:
            return ""
        return _str(d, "error_prompt_zh") if _str(d, "result") == "1" else ""

    # ---- 传输 ----

    def _auth_call(self, url: str, callback: str, args: list[tuple[str, str]]) -> dict:
        """登录、注销：先过频率闸门；门户说太频繁就退避后重发，重发几次还不行就报错。"""
        for attempt in range(RATE_RETRIES + 1):
            self.gate.wait()
            try:
                d = self._call(url, callback, args)
            except PortalError as e:
                if getattr(e, "http_status", None) in (429, 503) and attempt < RATE_RETRIES:
                    log.info("门户返回 HTTP %s，%.0f 秒后重发", e.http_status, self.gate.throttled())
                    continue
                raise
            if _ok(d) or not looks_rate_limited(d):
                self.gate.succeeded()
                return d
            if attempt == RATE_RETRIES:
                break
            log.info("门户说请求太频繁，%.0f 秒后重发", self.gate.throttled())
        raise RateLimitedError("门户限制了请求频率，请过一会儿再试")

    def _call(self, url: str, callback: str, args: list[tuple[str, str]] | None = None) -> dict:
        # 参数顺序与网页 util._jsonp 一致：callback 在最前；没带 jsVersion 时补上；末尾是随机数和 lang
        args = list(args or [])
        query = [("callback", callback), *args]
        if not any(k == "jsVersion" for k, _ in args):
            query.append(("jsVersion", JS_VERSION))
        query += [("v", str(random.randint(500, 10499))), ("lang", "zh")]
        full = url + "?" + urllib.parse.urlencode(query, quote_via=urllib.parse.quote)
        log.info("GET %s", _redact(full))
        req = urllib.request.Request(full, headers={"User-Agent": USER_AGENT})
        try:
            with self._opener.open(req, timeout=self.timeout) as resp:
                # /drcom/ 接口返回 GBK，/eportal/ 接口返回 UTF-8，以响应头为准
                charset = resp.headers.get_content_charset() or "utf-8"
                text = resp.read().decode(charset, errors="replace")
        except urllib.error.HTTPError as e:
            log.info("<- HTTP %s", e.code)
            err = PortalError(f"门户返回错误 HTTP {e.code}")
            err.http_status = e.code
            raise err from e
        except (TimeoutError, socket.timeout) as e:
            log.info("<- 超时")
            raise PortalError("连接门户超时。请确认已连上校园网（HNIU 或有线），路由器卡的话稍后再试") from e
        except urllib.error.URLError as e:
            log.info("<- %s", e.reason)
            if isinstance(e.reason, (TimeoutError, socket.timeout)):
                raise PortalError("连接门户超时。请确认已连上校园网（HNIU 或有线），路由器卡的话稍后再试") from e
            raise PortalError("连不上校园网门户，请确认已连上校园网（HNIU 或有线）") from e
        except OSError as e:
            log.info("<- %s", e)
            raise PortalError(f"网络错误：{e}") from e
        log.info("<- %s", text.strip()[:2000])
        return parse_jsonp(text)


def parse_jsonp(text: str) -> dict:
    m = re.match(r"\s*[\w$.]+\s*\((.*)\)\s*;?\s*$", text, re.S)
    if not m:
        raise PortalError("门户返回了无法识别的内容")
    try:
        obj = json.loads(m.group(1))
    except ValueError as e:
        raise PortalError("门户返回了无法识别的内容") from e
    if not isinstance(obj, dict):
        raise PortalError("门户返回了无法识别的内容")
    return obj


def _redact(url: str) -> str:
    return re.sub(r"((?:user_password|upass)=)[^&]*", r"\1***", url)


def _decode_msg(msg: str) -> str:
    """有的 Dr.COM 版本把错误代码做了 Base64，能解成可读 ASCII 就用解码后的。"""
    if not msg or len(msg) % 4 or not re.fullmatch(r"[A-Za-z0-9+/]+=*", msg):
        return msg
    try:
        decoded = base64.b64decode(msg, validate=True).decode("ascii")
    except (binascii.Error, UnicodeDecodeError):
        return msg
    return decoded if decoded.isprintable() and decoded.strip() else msg


def _hint(code: str) -> str | None:
    c = code.lower()
    if c.startswith("userid error1"):
        return "账号不存在"
    if c.startswith("userid error2"):
        return "密码错误"
    if "ldap auth error" in c:
        return "账号或密码错误"
    if c.startswith("in use"):
        return "账号已在其他设备在线，在线数已满"
    if c.startswith("rad:"):
        return "运营商认证失败，请检查运营商是否选对、套餐是否有效"
    return None


def _int_or_none(s: str) -> int | None:
    try:
        return int(s)
    except ValueError:
        return None


def _ok(d: dict) -> bool:
    r = _str(d, "result")
    return r == "1" or r.lower() == "ok"


def _str(d: dict, key: str) -> str:
    v = d.get(key)
    return "" if v is None else str(v).strip()


def _num(d: dict, key: str) -> int:
    try:
        return int(float(_str(d, key)))
    except ValueError:
        return 0


def format_minutes(minutes: int) -> str:
    if minutes < 60:
        return f"{minutes} 分钟"
    return f"{minutes // 60} 小时 {minutes % 60} 分"


def format_flow(kb: int) -> str:
    """门户返回的流量单位是 KB。"""
    if kb < 1024:
        return f"{kb} KB"
    if kb < 1024 * 1024:
        return f"{kb / 1024:.2f} MB"
    return f"{kb / 1024 / 1024:.2f} GB"


def format_seconds(seconds: int) -> str:
    if seconds < 60:
        return f"{seconds} 秒"
    if seconds < 3600:
        return f"{seconds // 60} 分 {seconds % 60} 秒"
    return f"{seconds // 3600} 小时 {seconds % 3600 // 60} 分"


def format_mac(mac: str) -> str:
    """aa74c07503c6 -> AA:74:C0:75:03:C6；全 0 表示门户没拿到，返回空串。"""
    mac = (mac or "").replace("-", "").replace(":", "").upper()
    if len(mac) != 12 or mac.strip("0") == "":
        return ""
    return ":".join(mac[i:i + 2] for i in range(0, 12, 2))


def format_fee(fee: int) -> str:
    """与门户页面一致：fee 去掉末两位后除以 10000 为元。"""
    return f"{(fee - fee % 100) / 10000:.2f} 元"


def format_limit(value: int, fmt) -> str:
    return "不限" if value >= UNLIMITED else fmt(value)


def _main(argv: list[str]) -> int:
    import argparse
    import getpass

    parser = argparse.ArgumentParser(description="校园网门户命令行")
    sub = parser.add_subparsers(dest="cmd", required=True)
    sub.add_parser("status", help="查询在线状态")
    sub.add_parser("config", help="查看门户下发的认证配置（page/loadConfig）")
    sub.add_parser("info", help="查看账号信息（page/loadUserInfo）")
    sub.add_parser("notice", help="查看门户公告")
    p_login = sub.add_parser("login", help="登录")
    p_login.add_argument("account")
    p_login.add_argument("--isp", default="校园网", help="校园网 / 联通 / 电信 / 移动")
    sub.add_parser("logout", help="注销")
    parser.add_argument("--log", help="把请求和返回写到这个文件（密码打码）")
    parser.add_argument("--terminal-type", type=int, default=DEFAULT_TERMINAL_TYPE,
                        choices=[v for _, v in TERMINAL_TYPES],
                        help="终端类型：1 电脑（默认） 2 手机 3 平板横屏 4 平板竖屏 0 其他")
    args = parser.parse_args(argv)
    if args.log:
        enable_log(Path(args.log))

    portal = Portal(terminal_type=args.terminal_type)
    try:
        if args.cmd == "config":
            cfg = portal.config(fresh=True)
            if not cfg.loaded:
                print("没读到门户配置，将使用默认值：")
            for k in ("login_method", "account_prefix", "ac_logout", "register_mode", "un_bind_mac", "user_info"):
                print(f"{k:15s} {getattr(cfg, k)}")
            print("注销方式：" + ("先解绑 MAC（mac/unbind），失败再 logout" if cfg.unbind_on_logout else "logout"))
        elif args.cmd == "info":
            info = portal.user_info()
            if info is None:
                print("没有账号信息（不在线，或门户不支持）")
            else:
                for _, title, value in info.fields:
                    print(f"{title}：{value}")
                if info.progress is not None:
                    print(f"已用 {info.progress:.0f}%  已用 {info.used or '-'}  可用 {info.available or '-'}")
        elif args.cmd == "notice":
            print(portal.notice() or "没有公告")
        elif args.cmd == "status":
            st = portal.status()
            if st.online:
                print(f"在线  账号 {st.account}  IP {st.ip}  已用 {format_minutes(st.minutes)}，{format_flow(st.flow_kb)}")
            else:
                print(f"未登录  IP {st.ip or '-'}")
        elif args.cmd == "login":
            suffix = next((s for n, s in CARRIERS if args.isp in n), None)
            if suffix is None:
                parser.error(f"未知运营商：{args.isp}")
            portal.login(args.account, getpass.getpass("密码："), suffix)
            print("登录成功")
        else:
            print("已注销" if portal.logout() else "注销失败：可能本来就没有登录")
    except (PortalError, LoginError) as e:
        print(f"失败：{e}")
        return 1
    return 0


if __name__ == "__main__":
    import sys

    sys.exit(_main(sys.argv[1:]))
