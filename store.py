"""账号保存。

数据放在 %APPDATA%\\XiaoyuanLogin\\accounts.json（可用环境变量 XIAOYUAN_HOME 改目录）。
密码用 Windows DPAPI 加密，只有当前 Windows 用户能解开；换电脑或换用户后需要重新输入。
"""

from __future__ import annotations

import base64
import ctypes
import json
import os
import sys
import time
from dataclasses import asdict, dataclass, field
from pathlib import Path

from portal import carrier_name


def app_dir() -> Path:
    custom = os.environ.get("XIAOYUAN_HOME")
    if custom:
        return Path(custom)
    return Path(os.environ.get("APPDATA") or Path.home()) / "XiaoyuanLogin"


@dataclass
class Account:
    account: str
    isp: str = ""          # 运营商后缀，如 @telecom
    note: str = ""
    password: str = ""     # 仅在内存中是明文
    last_used: float = 0.0
    terminal_type: int = -1   # 登录时的终端类型（portal.TERMINAL_TYPES 的值）；-1 表示默认，跟随全局设置
    password_lost: bool = field(default=False, compare=False)   # 保存过密码但解不开

    @property
    def full(self) -> str:
        return self.account + self.isp

    @property
    def carrier(self) -> str:
        return carrier_name(self.isp)


class AccountStore:
    def __init__(self, path: Path | None = None):
        self.path = path or app_dir() / "accounts.json"
        self.accounts: list[Account] = []
        self.load()

    def load(self) -> None:
        self.accounts = []
        try:
            data = json.loads(self.path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return
        for item in data.get("accounts", []):
            if not isinstance(item, dict) or not item.get("account"):
                continue
            cipher = item.get("password", "")
            password = _unprotect(cipher)
            self.accounts.append(Account(
                account=str(item["account"]),
                isp=str(item.get("isp", "")),
                note=str(item.get("note", "")),
                password=password,
                last_used=float(item.get("last_used", 0) or 0),
                terminal_type=_int(item.get("terminal_type"), -1),
                password_lost=bool(cipher) and not password,
            ))

    def save(self) -> None:
        items = []
        for a in self.accounts:
            d = asdict(a)
            d.pop("password_lost")
            d["password"] = _protect(a.password)
            items.append(d)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.path.with_suffix(".tmp")
        tmp.write_text(json.dumps({"accounts": items}, ensure_ascii=False, indent=2), encoding="utf-8")
        os.replace(tmp, self.path)

    def get(self, account: str) -> Account | None:
        return next((a for a in self.accounts if a.account == account), None)

    def upsert(self, acc: Account, touch: bool = False) -> Account:
        """按账号新增或覆盖；touch 为 True 时记为最近使用。"""
        existing = self.get(acc.account)
        if existing:
            existing.isp, existing.note, existing.password = acc.isp, acc.note, acc.password
            existing.terminal_type = acc.terminal_type
            existing.password_lost = False
            acc = existing
        else:
            self.accounts.append(acc)
        if touch:
            acc.last_used = time.time()
        self.save()
        return acc

    def rename(self, old_account: str, acc: Account) -> Account:
        """编辑时账号可能被改掉，先删旧的再写入。"""
        old = self.get(old_account)
        if old and old_account != acc.account:
            acc.last_used = max(acc.last_used, old.last_used)
            self.accounts.remove(old)
        return self.upsert(acc)

    def remove(self, account: str) -> None:
        self.accounts = [a for a in self.accounts if a.account != account]
        self.save()

    def last(self) -> Account | None:
        used = [a for a in self.accounts if a.last_used]
        return max(used, key=lambda a: a.last_used) if used else (self.accounts[0] if self.accounts else None)


def _int(value, default: int) -> int:
    try:
        return int(value)
    except (TypeError, ValueError):
        return default


# ---- Windows DPAPI ----

_ENTROPY = b"XiaoyuanLogin"


class _Blob(ctypes.Structure):
    _fields_ = [("cbData", ctypes.c_uint32), ("pbData", ctypes.POINTER(ctypes.c_char))]


def _blob(data: bytes) -> _Blob:
    buf = ctypes.create_string_buffer(data, len(data))
    return _Blob(len(data), ctypes.cast(buf, ctypes.POINTER(ctypes.c_char)))


def _dpapi(data: bytes, protect: bool) -> bytes:
    crypt32, kernel32 = ctypes.windll.crypt32, ctypes.windll.kernel32
    src, ent, out = _blob(data), _blob(_ENTROPY), _Blob()
    fn = crypt32.CryptProtectData if protect else crypt32.CryptUnprotectData
    ok = fn(ctypes.byref(src), None, ctypes.byref(ent), None, None, 0x1, ctypes.byref(out))  # CRYPTPROTECT_UI_FORBIDDEN
    if not ok:
        raise OSError("DPAPI 调用失败")
    try:
        return ctypes.string_at(out.pbData, out.cbData)
    finally:
        kernel32.LocalFree(out.pbData)


def _protect(plain: str) -> str:
    if not plain:
        return ""
    if sys.platform != "win32":
        return "b64:" + base64.b64encode(plain.encode()).decode()
    return base64.b64encode(_dpapi(plain.encode("utf-8"), True)).decode()


def _unprotect(cipher: str) -> str:
    """解不开（换了电脑或 Windows 用户、数据损坏）时返回空串。"""
    if not cipher:
        return ""
    try:
        if cipher.startswith("b64:"):
            return base64.b64decode(cipher[4:]).decode()
        return _dpapi(base64.b64decode(cipher), False).decode("utf-8")
    except (OSError, ValueError):
        return ""
