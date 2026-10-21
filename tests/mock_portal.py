"""本地假门户，模拟 Dr.COM 的几个接口，测试时不会碰真门户、也不会把自己踢下线。

    python tests/mock_portal.py          # 监听 127.0.0.1:18080
    set XIAOYUAN_PORTAL=http://127.0.0.1:18080
    python app.py                        # 界面改连假门户

按真实门户（login_method=1）的行为模拟：
- 只有 /eportal/portal/login 会让 AC 放行（online=True）；/drcom/login、/drcom/logout 只记录，不改变上网状态
- 已在线时再登录返回 ret_code=2（终端 IP 已经在线）；注销后 AC 要过 release_delay 秒才接受新登录
- 登录参数要和网页一致：login_method=1、账号带 ",0," 前缀、wlan_user_ip 是本机 IP，否则返回 998
约定：密码 right 登录成功；账号以 nouser 开头 -> 账号不存在；以 radfail 开头 -> 运营商认证失败；
以 slow 开头 -> 10 秒后才响应（测超时）；其余 -> 密码错误（错误代码按 Base64 返回）。
busy_replies 设为 n 时，接下来 n 次登录/注销回复 ret_code=3（系统繁忙），模拟门户限制请求频率。
config 是 page/loadConfig 返回的配置；登录、注销、解绑会按它校验参数（参数和配置不符时不生效），
和真实门户一样：页面上的 login_method、account_prefix、ac_logout、register_mode 都要取自这里。
notice：门户公告（HTML）。

单独运行时另有 /__mock__/ 控制接口，给安卓端的 Kotlin 测试在另一个进程里改状态、读请求记录（不计入 requests）：
    /__mock__/reset、/__mock__/clear（只清请求记录）、/__mock__/state（读状态）
    /__mock__/set?online=1&uid=x&release_delay=0&busy_replies=2&config_available=0&notice=...&config.ac_logout=1
都返回 JSON：online、uid、requests（[{path, query: [[键, 值]...], time}]）、seen_paths。
"""

from __future__ import annotations

import base64
import ipaddress
import json
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qsl, urlsplit

GOOD_PASSWORD = "right"
CLIENT_IP = "10.10.130.163"
CLIENT_MAC = "00e04c681234"
DEFAULT_CONFIG = {"login_method": "1", "account_prefix": "1", "ac_logout": "0", "register_mode": "1",
                  "un_bind_mac": "0",
                  "user_info": "account,user_name,use_flow,available_flow,flow_progress",
                  "program_index": "cWOgVj1776852143", "page_index": "oEOHaX1776852171"}


class MockPortal:
    def __init__(self, port: int = 0, release_delay: float = 1.2):
        self.online = False
        self.uid = ""
        self.release_delay = release_delay
        self.released_at = 0.0
        self.busy_replies = 0
        self.config = dict(DEFAULT_CONFIG)
        self.config_available = True
        self.notice = ""
        self.requests: list[tuple[str, list[tuple[str, str]]]] = []
        self.times: list[float] = []         # 每个请求到达的时间（monotonic），和 requests 一一对应
        self.seen_paths: set[str] = set()   # 整个生命周期见过的路径，不随 requests 清空
        self.server = ThreadingHTTPServer(("127.0.0.1", port), self._handler())
        self.port = self.server.server_address[1]
        self.base = f"http://127.0.0.1:{self.port}"

    def start(self) -> "MockPortal":
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        return self

    def stop(self) -> None:
        self.server.shutdown()
        self.server.server_close()

    def reset(self) -> None:
        self.online, self.uid, self.released_at, self.busy_replies = False, "", 0.0, 0
        self.config, self.config_available = dict(DEFAULT_CONFIG), True
        self.notice = ""
        self.mac_bound = True   # False 时解绑回复“mac不存在”（本校门户：要求注销时解绑，但没绑 MAC）
        self.requests.clear()
        self.times.clear()

    def paths(self) -> list[str]:
        return [p for p, _ in self.requests]

    def _handler(self):
        mock = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def reply(self, cb: str, obj: dict, charset: str) -> None:
                body = f"     {cb}({json.dumps(obj, ensure_ascii=False)})".encode(charset)
                self.send_response(200)
                self.send_header("Content-Type", f"application/javascript; charset={charset}")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def do_GET(self):
                url = urlsplit(self.path)
                query = parse_qsl(url.query, keep_blank_values=True)
                if url.path.startswith("/__mock__/"):
                    self.control(url.path, query)
                    return
                mock.requests.append((url.path, query))
                mock.times.append(time.monotonic())
                mock.seen_paths.add(url.path)
                q = dict(query)
                cb = q.get("callback", "cb")

                if url.path == "/drcom/chkstatus":
                    base = {"v46ip": CLIENT_IP, "ss5": CLIENT_IP, "vid": 0, "ss4": CLIENT_MAC,
                            "ss6": "10.253.0.1", "aolno": 1833}
                    if mock.online:
                        # 字段和真实门户一致（取值为示例）
                        self.reply(cb, {"result": 1, "time": 15505, "flow": 43392041, "fsele": 1, "fee": 123400,
                                        "oltime": 4294967295, "olflow": 4294967295, "uid": mock.uid + "   ",
                                        "AC": mock.uid, "NID": "测试用户", "olno": 2, "olip": "10.10.132.62",
                                        "olmac": "aa74c07503c6", "ollm": 10, "gid": 3, "ispid": 0, "actt": 3725,
                                        **base}, "gbk")
                    else:
                        self.reply(cb, {"result": 0, **base}, "gbk")
                elif url.path in ("/drcom/login", "/drcom/logout"):
                    # 真实门户上这两个接口不会让 AC 放行，用了就是没网
                    self.reply(cb, {"result": 1, "msg": "内核接口"}, "gbk")
                elif url.path == "/eportal/portal/page/loadConfig":
                    if not mock.config_available:
                        self.send_response(404)
                        self.end_headers()
                        return
                    self.reply(cb, {"code": 1, "data": mock.config}, "utf-8")
                elif url.path in ("/eportal/portal/login", "/eportal/portal/logout", "/eportal/portal/mac/unbind") \
                        and mock.busy_replies > 0:
                    mock.busy_replies -= 1
                    self.reply(cb, {"result": 0, "msg": "", "ret_code": 3}, "utf-8")
                elif url.path == "/eportal/portal/login":
                    self.login(cb, q)
                elif url.path == "/eportal/portal/page/loadUserInfo":
                    ok_ip = q.get("wlan_user_ip") == str(int(ipaddress.IPv4Address(CLIENT_IP)))
                    if not mock.online or q.get("user_account") != mock.uid or not ok_ip \
                            or q.get("program_index") != mock.config["program_index"]:
                        self.reply(cb, {"code": 0, "msg": "用户不在线"}, "utf-8")
                    else:
                        self.reply(cb, {"code": 1, "user_info": {
                            "account": mock.uid, "user_name": "测试用户", "use_flow": "41.38G", "available_flow": "58.62G",
                            "flow_progress": "41", "progress_type": 1, "group_name": "学生"},
                            "user_info_lang": {"account": "账号", "user_name": "姓名", "use_flow": "已使用流量",
                                               "available_flow": "可使用流量", "flow_progress": "流量进度"}}, "utf-8")
                elif url.path == "/eportal/portal/notice/get_notice_content":
                    self.reply(cb, {"result": 1, "content": mock.notice} if mock.notice else {"result": 0}, "utf-8")
                elif url.path == "/eportal/portal/mac/unbind":
                    # 页面 user.unbind_mac：账号不带前缀、MAC 大写、IP 是十进制整数
                    ok_ip = q.get("wlan_user_ip") == str(int(ipaddress.IPv4Address(CLIENT_IP)))
                    if mock.config.get("un_bind_mac") != "1":
                        self.reply(cb, {"result": 0, "msg": "未启用注销解绑MAC"}, "utf-8")
                    elif not mock.mac_bound:
                        self.reply(cb, {"result": 0, "msg": "mac不存在"}, "utf-8")
                    elif not mock.online or q.get("user_account") != mock.uid or not ok_ip \
                            or q.get("wlan_user_mac") != q.get("wlan_user_mac", "").upper():
                        self.reply(cb, {"result": 0, "msg": "解绑失败"}, "utf-8")
                    else:
                        mock.online, mock.uid = False, ""
                        mock.released_at = time.monotonic() + mock.release_delay
                        self.reply(cb, {"result": 1, "msg": "解绑成功"}, "utf-8")
                elif url.path == "/eportal/portal/logout":
                    c = mock.config
                    if (q.get("login_method") != c["login_method"] or q.get("user_account") != "drcom"
                            or q.get("ac_logout") != c["ac_logout"] or q.get("register_mode") != c["register_mode"]):
                        # 参数和门户配置不符：真实门户上表现为“注销成功”但会话还在，这里直接报错便于测试发现
                        self.reply(cb, {"result": 0, "msg": "参数不对", "ret_code": 998}, "utf-8")
                    elif mock.online:
                        mock.online, mock.uid = False, ""
                        mock.released_at = time.monotonic() + mock.release_delay
                        self.reply(cb, {"result": 1, "msg": "Radius注销成功！"}, "utf-8")
                    else:
                        self.reply(cb, {"result": 0, "msg": "终端不在线", "ret_code": 11}, "utf-8")
                elif url.path == "/eportal/portal/err_code/loadErrorPrompt":
                    code = q.get("error_code", "")
                    zh = {"userid error1": "账号不存在", "userid error2": "密码错误"}.get(code, code)
                    self.reply(cb, {"result": 1, "error_code": code, "error_prompt_zh": zh}, "utf-8")
                elif url.path == "/eportal/portal/err_code":
                    self.reply(cb, {"result": 1, "error_prompt_zh": "认证失败（门户返回的详细原因）"}, "utf-8")
                else:
                    self.send_response(404)
                    self.end_headers()

            def control(self, path: str, query: list[tuple[str, str]]) -> None:
                if path == "/__mock__/reset":
                    mock.reset()
                elif path == "/__mock__/clear":
                    mock.requests.clear()
                    mock.times.clear()
                elif path == "/__mock__/set":
                    for k, v in query:
                        if k.startswith("config."):
                            mock.config[k[len("config."):]] = v
                        elif k in ("online", "config_available"):
                            setattr(mock, k, v == "1")
                        elif k == "release_delay":
                            mock.release_delay = float(v)
                        elif k == "busy_replies":
                            mock.busy_replies = int(v)
                        elif k in ("uid", "notice"):
                            setattr(mock, k, v)
                elif path != "/__mock__/state":
                    self.send_response(404)
                    self.end_headers()
                    return
                body = json.dumps({
                    "online": mock.online, "uid": mock.uid,
                    "requests": [{"path": p, "query": q, "time": t} for (p, q), t in zip(mock.requests, mock.times)],
                    "seen_paths": sorted(mock.seen_paths),
                }, ensure_ascii=False).encode("utf-8")
                self.send_response(200)
                self.send_header("Content-Type", "application/json; charset=utf-8")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def login(self, cb: str, q: dict) -> None:
                account = q.get("user_account", "")
                # 页面 login.init：手机（terminal_type=2）前缀 ,1,，其他终端 ,0,
                prefix = (",1," if q.get("terminal_type") == "2" else ",0,") if mock.config["account_prefix"] == "1" else ""
                if (q.get("login_method") != mock.config["login_method"] or not account.startswith(prefix)
                        or (not prefix and account.startswith(",")) or q.get("wlan_user_ip") != CLIENT_IP):
                    self.reply(cb, {"result": 0, "msg": "", "ret_code": 998}, "utf-8")
                    return
                account = account[len(prefix):]
                if account.startswith("slow"):
                    time.sleep(10)
                if mock.online or time.monotonic() < mock.released_at:
                    self.reply(cb, {"result": 0, "msg": "", "ret_code": 2}, "utf-8")
                elif account.startswith("nouser"):
                    self.reply(cb, {"result": 0, "msg": "userid error1", "ret_code": 1}, "utf-8")
                elif account.startswith("radfail"):
                    self.reply(cb, {"result": 0, "msg": "Rad:Oppp error: 1", "ret_code": 1}, "utf-8")
                elif q.get("user_password") == GOOD_PASSWORD:
                    mock.online, mock.uid = True, account
                    self.reply(cb, {"result": 1, "msg": "Portal协议认证成功！"}, "utf-8")
                else:
                    msg = base64.b64encode(b"ldap auth error").decode()
                    self.reply(cb, {"result": 0, "msg": msg, "ret_code": 1}, "utf-8")

        return Handler


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 18080
    mock = MockPortal(port)
    print(f"假门户已启动：{mock.base}（Ctrl+C 退出）", flush=True)   # 端口传 0 时测试从这行读实际端口
    try:
        mock.server.serve_forever()
    except KeyboardInterrupt:
        pass
