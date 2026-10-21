"""账号迁移：把选中的账号（含密码）加密成一段文字，存成文件或显示成二维码，在另一台设备上用导入码解开。只依赖标准库。

格式（安卓端 android/.../core/Transfer.kt 与此一致）：
    XYACC1:<Base64url，无填充>( salt 16 字节 | 密文 | 校验 16 字节 )
    密钥：PBKDF2-HMAC-SHA256(导入码, salt, 100000 次) → 64 字节，前 32 字节加密、后 32 字节校验
    加密：明文与 HMAC-SHA256(加密密钥, 4 字节大端计数器 0,1,2…) 拼成的密钥流逐字节异或
    校验：HMAC-SHA256(校验密钥, "XYACC1" + salt + 密文) 的前 16 字节
    明文：zlib 压缩的 JSON {"v":1,"accounts":[{"a":账号,"i":运营商后缀,"n":备注,"p":密码,"t":终端类型}]}
导入码不对时校验不通过，报“导入码不对”。
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import os
import re
import secrets
import zlib

from store import Account

PREFIX = "XYACC1:"
ITERATIONS = 100_000
CODE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"   # 去掉了容易看错的 0 O 1 I
MIN_CODE = 4


class TransferError(Exception):
    """内容不是迁移数据、导入码不对或数据损坏；消息是给用户看的。"""


def random_code(length: int = 8) -> str:
    return "".join(secrets.choice(CODE_ALPHABET) for _ in range(length))


def _keys(code: str, salt: bytes) -> tuple[bytes, bytes]:
    k = hashlib.pbkdf2_hmac("sha256", code.encode("utf-8"), salt, ITERATIONS, 64)
    return k[:32], k[32:]


def _stream(key: bytes, data: bytes) -> bytes:
    out = bytearray()
    counter = 0
    while len(out) < len(data):
        out += hmac.new(key, counter.to_bytes(4, "big"), hashlib.sha256).digest()
        counter += 1
    return bytes(a ^ b for a, b in zip(data, out))


def _tag(key: bytes, salt: bytes, body: bytes) -> bytes:
    return hmac.new(key, PREFIX[:-1].encode() + salt + body, hashlib.sha256).digest()[:16]


def encode(accounts: list[Account], code: str, salt: bytes | None = None) -> str:
    if len(code) < MIN_CODE:
        raise TransferError(f"导入码至少 {MIN_CODE} 位")
    items = [{"a": a.account, "i": a.isp, "n": a.note, "p": a.password, "t": a.terminal_type} for a in accounts]
    plain = zlib.compress(json.dumps({"v": 1, "accounts": items}, ensure_ascii=False, separators=(",", ":")).encode(), 9)
    salt = salt or os.urandom(16)
    enc, mac = _keys(code, salt)
    body = _stream(enc, plain)
    blob = salt + body + _tag(mac, salt, body)
    return PREFIX + base64.urlsafe_b64encode(blob).decode().rstrip("=")


def looks_like(text: str) -> bool:
    return PREFIX in text


def decode(text: str, code: str) -> list[Account]:
    """解开迁移数据（前后有别的文字也行），返回账号列表（last_used 为 0）。"""
    i = text.find(PREFIX)
    if i < 0:
        raise TransferError("不是校园网登录的账号迁移数据")
    # 去掉换行、空格（聊天软件可能折行），取前缀后面连续的 Base64url 字符
    m = re.match(r"[A-Za-z0-9_-]*", "".join(text[i + len(PREFIX):].split()))
    b64 = m.group(0)
    try:
        blob = base64.urlsafe_b64decode(b64 + "=" * (-len(b64) % 4))
    except ValueError as e:
        raise TransferError("迁移数据不完整") from e
    if len(blob) < 16 + 16 + 1:
        raise TransferError("迁移数据不完整")
    salt, body, tag = blob[:16], blob[16:-16], blob[-16:]
    enc, mac = _keys(code, salt)
    if not hmac.compare_digest(_tag(mac, salt, body), tag):
        raise TransferError("导入码不对")
    try:
        data = json.loads(zlib.decompress(_stream(enc, body)).decode("utf-8"))
        return [Account(str(x["a"]), str(x.get("i", "")), str(x.get("n", "")), str(x.get("p", "")),
                        terminal_type=int(x.get("t", -1))) for x in data["accounts"] if str(x.get("a", "")).strip()]
    except (zlib.error, ValueError, KeyError, TypeError) as e:
        raise TransferError("迁移数据已损坏") from e
