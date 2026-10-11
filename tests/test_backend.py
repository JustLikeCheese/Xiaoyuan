"""后端逻辑测试（不开界面），连本地假门户：python -m unittest tests.test_backend -v"""

import json
import os
import sys
import tempfile
import time
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))
os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

from PySide6.QtCore import QEventLoop, QTimer, QUrl  # noqa: E402
from PySide6.QtGui import QGuiApplication  # noqa: E402

import portal as portal_module  # noqa: E402
from backend import Backend, Settings  # noqa: E402
from store import Account, AccountStore  # noqa: E402
from tests.mock_portal import GOOD_PASSWORD, MockPortal  # noqa: E402

app = QGuiApplication.instance() or QGuiApplication(sys.argv)


def wait_until(cond, timeout_ms: int = 6000) -> bool:
    deadline = time.monotonic() + timeout_ms / 1000
    while time.monotonic() < deadline:
        if cond():
            return True
        loop = QEventLoop()
        QTimer.singleShot(30, loop.quit)
        loop.exec()
    return cond()


class BackendTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.mock = MockPortal(release_delay=0.8).start()

    @classmethod
    def tearDownClass(cls):
        cls.mock.stop()

    def setUp(self):
        self.mock.reset()
        portal_module.set_auth_interval(0)
        portal_module.forget_terminal()
        self.dir = tempfile.TemporaryDirectory()
        home = Path(self.dir.name)
        store = AccountStore(home / "accounts.json")
        store.upsert(Account("2002010", "@telecom", "我的号", GOOD_PASSWORD), touch=True)
        store.upsert(Account("2002011", "", "室友", GOOD_PASSWORD))
        self.settings = Settings(home / "settings.json")
        self.b = Backend(store, self.settings)
        self.b.portal_override = self.mock.base
        self.toasts: list[tuple] = []
        self.b.toast.connect(lambda *a: self.toasts.append(a))
        self.closed = []
        self.b.closeRequested.connect(lambda: self.closed.append(True))

    def tearDown(self):
        # 本测试的 Backend 不会被销毁；关掉自动重连，免得稍后到的查询结果让它在下一个测试里登录假门户
        self.b.settings.set("Reconnect", "Enabled", False)
        self.b.autoTimer.stop()
        self.b.reconnectTimer.stop()
        self.b.networkTimer.stop()
        self.dir.cleanup()
        # 本校门户必须走 eportal，内核接口用了就会“显示在线却没网”
        self.assertFalse({"/drcom/login", "/drcom/logout"} & self.mock.seen_paths)

    def state(self) -> str:
        return self.b.status["state"]

    def online_as(self, uid: str) -> bool:
        return self.state() == "online" and self.mock.uid == uid

    def test_startup_offline(self):
        self.b.startup()
        self.assertTrue(wait_until(lambda: self.state() == "offline"), self.b.status)
        self.assertEqual(self.b.status["ip"], "10.10.130.163")

    def test_login_success_saves_account_and_hides_number_in_toast(self):
        self.b.refresh()
        wait_until(lambda: self.state() == "offline")
        self.b.login("2002012", GOOD_PASSWORD, "@cmcc", True)
        self.assertTrue(wait_until(lambda: self.online_as("2002012@cmcc")), self.b.status)
        self.assertEqual(self.b.status["account"], "2002012@cmcc")
        self.assertEqual(self.b.status["flow"], "41.38 GB")
        self.assertEqual(self.b.lastAccount, "2002012")
        toast = next(t for t in self.toasts if t[1] == "登录成功")
        self.assertEqual(toast[2], "中国移动")

    def test_login_wrong_password(self):
        self.b.login("2002010", "wrong", "@telecom", True)
        self.assertTrue(wait_until(lambda: any(t[0] == "error" for t in self.toasts)))
        err = next(t for t in self.toasts if t[0] == "error")
        self.assertEqual(err[1:3], ("登录失败", "账号或密码错误（ldap auth error）"))

    def test_switch_logs_out_first_and_waits_for_ac(self):
        self.b.login("2002010", GOOD_PASSWORD, "@telecom", True)
        self.assertTrue(wait_until(lambda: self.online_as("2002010@telecom")))
        self.mock.requests.clear()
        self.b.switchTo("2002011")
        self.assertTrue(wait_until(lambda: self.online_as("2002011"), 8000), self.b.status)
        paths = [p for p in self.mock.paths() if p.startswith("/eportal/portal/log")]
        self.assertEqual(paths[:2], ["/eportal/portal/logout", "/eportal/portal/login"])
        # 假门户先记下新账号、程序随后才收到回复，要等提示真正发出来
        self.assertTrue(wait_until(lambda: ("success", "已切换账号", "室友 · 校园网", 3000) in self.toasts), self.toasts)

    def test_same_account_login_is_noop(self):
        self.b.login("2002010", GOOD_PASSWORD, "@telecom", True)
        self.assertTrue(wait_until(lambda: self.online_as("2002010@telecom")))
        self.b.login("2002010", GOOD_PASSWORD, "@telecom", True)
        self.assertEqual(self.toasts[-1][1], "已经是这个账号在线")

    def test_auto_login_then_close(self):
        self.settings.set("Login", "AutoLogin", True)
        self.settings.set("Login", "CloseAfterAutoLogin", True)
        filled = []
        self.b.fillRequested.connect(filled.append)
        self.b.startup()
        self.assertTrue(wait_until(lambda: bool(self.closed), 8000), self.b.status)
        self.assertEqual(self.mock.uid, "2002010@telecom")
        self.assertEqual(filled, ["2002010"])

    def test_logout(self):
        self.b.login("2002010", GOOD_PASSWORD, "@telecom", True)
        self.assertTrue(wait_until(lambda: self.online_as("2002010@telecom")))
        self.b.logout()
        self.assertTrue(wait_until(lambda: self.state() == "offline"))
        self.assertIn(("success", "已注销", "", 3000), self.toasts)

    def test_details_and_raw_fields(self):
        self.b.login("2002010", GOOD_PASSWORD, "@telecom", True)
        self.assertTrue(wait_until(lambda: self.online_as("2002010@telecom")))
        d = {f["key"]: f for f in self.b.details}
        self.assertEqual(d["actt"]["value"], "1 小时 2 分")
        self.assertEqual(d["olmac"]["value"], "AA:74:C0:75:03:C6")
        self.assertTrue(d["NID"]["secret"] and d["olip"]["secret"] and not d["vid"]["secret"])
        raw = {f["key"]: f for f in self.b.rawFields}
        self.assertTrue(raw["uid"]["secret"] and raw["v46ip"]["secret"] and not raw["time"]["secret"])

    def test_auto_refresh(self):
        self.b.autoTimer.setInterval(200)
        self.b.setAutoRefresh(True)
        before = self.mock.paths().count("/drcom/chkstatus")
        wait_until(lambda: False, 1200)
        self.assertGreaterEqual(self.mock.paths().count("/drcom/chkstatus") - before, 3)
        self.assertTrue(json.loads(self.settings.path.read_text(encoding="utf-8"))["Status"]["AutoRefresh"])
        self.b.setAutoRefresh(False)
        self.assertFalse(self.b.autoTimer.isActive())

    def test_accounts_save_rename_delete(self):
        self.assertTrue(self.b.saveAccount("", "2002013", "@unicom", "新号", "pw"))
        self.assertIn("2002013", [a["account"] for a in self.b.accounts])
        self.assertTrue(self.b.saveAccount("2002013", "2002014", "@cmcc", "改名", "pw2"))
        accounts = {a["account"]: a for a in self.b.accounts}
        self.assertNotIn("2002013", accounts)
        self.assertEqual((accounts["2002014"]["carrier"], accounts["2002014"]["note"]), ("中国移动", "改名"))
        self.b.deleteAccount("2002014")
        self.assertNotIn("2002014", [a["account"] for a in self.b.accounts])
        # 没备注的账号在提示里打码
        self.b.saveAccount("", "2002015", "", "", "")
        self.assertEqual(self.toasts[-1][2], "•••••••• · 校园网")

    def test_terminal_type_setting(self):
        self.assertEqual(self.b.settingsMap["terminalType"], 1)
        self.b.setSetting("terminalType", 2.0)           # QML 的数字可能是浮点
        self.assertEqual(Settings(self.settings.path).get("Portal", "TerminalType"), 2)
        self.b.setSetting("terminalType", 9)             # 不认识的值忽略
        self.assertEqual(self.b.settingsMap["terminalType"], 2)
        self.b.login("2002010", GOOD_PASSWORD, "@telecom", True)
        self.assertTrue(wait_until(lambda: self.online_as("2002010@telecom")))
        q = dict(next(q for p, q in self.mock.requests if p == "/eportal/portal/login"))
        self.assertEqual((q["terminal_type"], q["user_account"]), ("2", ",1,2002010@telecom"))

    def test_per_login_and_per_account_terminal_type(self):
        def last_login() -> dict:
            return dict(next(q for p, q in reversed(self.mock.requests) if p == "/eportal/portal/login"))

        self.b.setSetting("terminalType", 3)
        self.assertEqual(self.b.terminalOptions[0], {"name": "默认（平板（横屏））", "value": -1})
        # 登录页选“默认”：用全局设置
        self.b.login("2002012", GOOD_PASSWORD, "", True, -1)
        self.assertTrue(wait_until(lambda: self.online_as("2002012")))
        self.assertEqual(last_login()["terminal_type"], "3")
        self.assertEqual(self.b.store.get("2002012").terminal_type, -1)
        # 登录页选手机并记住：账号里保存手机，切换回这个账号时也用手机
        self.b.logout()
        self.assertTrue(wait_until(lambda: self.state() == "offline"))
        self.b.login("2002012", GOOD_PASSWORD, "", True, 2)
        self.assertTrue(wait_until(lambda: self.online_as("2002012")))
        self.assertEqual((last_login()["terminal_type"], last_login()["user_account"]), ("2", ",1,2002012"))
        self.assertEqual(AccountStore(self.b.store.path).get("2002012").terminal_type, 2)
        acc = next(a for a in self.b.accounts if a["account"] == "2002012")
        self.assertEqual((acc["terminalType"], acc["terminalName"]), (2, "手机"))

    def test_account_terminal_type_used_by_switch_and_reconnect(self):
        self.assertTrue(self.b.saveAccount("2002011", "2002011", "", "室友", GOOD_PASSWORD, 4))
        self.assertEqual(self.b.store.get("2002011").terminal_type, 4)
        self.b.login("2002010", GOOD_PASSWORD, "@telecom", True)
        self.assertTrue(wait_until(lambda: self.online_as("2002010@telecom")))
        self.b.switchTo("2002011")
        self.assertTrue(wait_until(lambda: self.online_as("2002011"), 8000))
        q = dict(next(q for p, q in reversed(self.mock.requests) if p == "/eportal/portal/login"))
        self.assertEqual(q["terminal_type"], "4")
        # 自动重连用重连账号自己的终端类型
        self.mock.online, self.mock.uid = False, ""
        self.b.setReconnectAccount("2002011")
        self.b.setReconnectEnabled(True)
        self.assertTrue(wait_until(lambda: self.b.reconnect["count"] == 1), self.b.reconnectHistory)
        q = dict(next(q for p, q in reversed(self.mock.requests) if p == "/eportal/portal/login"))
        self.assertEqual(q["terminal_type"], "4")
        # 编辑时选回“默认”
        self.b.saveAccount("2002011", "2002011", "", "室友", GOOD_PASSWORD, -1)
        self.assertEqual(self.b.store.get("2002011").terminal_type, -1)

    def test_settings_persist_and_legacy_theme(self):
        self.b.setSetting("portalHost", "  ")
        self.assertEqual(self.b.settingsMap["portalHost"], "http://10.253.0.1")
        self.b.setSetting("theme", "Dark")
        self.b.setSetting("font", "MiSans")
        self.assertEqual(self.toasts[-1][1], "重启软件后生效")
        again = Settings(self.settings.path)
        self.assertEqual((again.get("Appearance", "Theme"), again.get("Appearance", "Font")), ("Dark", "MiSans"))
        legacy = Path(self.dir.name) / "legacy.json"
        legacy.write_text('{"QFluentWidgets": {"ThemeMode": "Light"}, "Login": {"AutoLogin": true}}', encoding="utf-8")
        old = Settings(legacy)
        self.assertEqual((old.get("Appearance", "Theme"), old.get("Login", "AutoLogin")), ("Light", True))

    # ---- 自动重连 ----

    def test_reconnect_logs_back_in_when_offline(self):
        self.b.setReconnectEnabled(True)   # 开启后立刻检测一次
        self.assertTrue(wait_until(lambda: self.online_as("2002010@telecom")), self.b.reconnect)
        self.assertTrue(wait_until(lambda: self.b.reconnect["count"] == 1))
        texts = [h["text"] for h in self.b.reconnectHistory]
        self.assertIn("自动重连成功", texts)
        self.assertTrue(any(t.startswith("检测到离线，正在用「我的号 · 中国电信」") for t in texts), texts)
        self.assertIn(("success", "已自动重连", "我的号 · 中国电信", 3000), self.toasts)
        self.assertTrue(json.loads(self.settings.path.read_text(encoding="utf-8"))["Reconnect"]["Enabled"])

    def test_manual_logout_pauses_reconnect_until_manual_login(self):
        self.b.login("2002010", GOOD_PASSWORD, "@telecom", True)
        self.assertTrue(wait_until(lambda: self.online_as("2002010@telecom")))
        self.b.setReconnectEnabled(True)
        wait_until(lambda: self.b.reconnect["lastState"] == "online")
        self.b.logout()
        self.assertTrue(wait_until(lambda: self.b.reconnect["state"] == "paused"))
        wait_until(lambda: self.state() == "offline")
        self.mock.requests.clear()
        self.b.reconnect_tick()
        wait_until(lambda: False, 800)
        self.assertNotIn("/eportal/portal/login", self.mock.paths())   # 暂停中不会登回去
        self.b.login("2002011", GOOD_PASSWORD, "", True)
        self.assertTrue(wait_until(lambda: self.b.reconnect["state"] == "watching"), self.b.reconnect)

    def test_reconnect_pauses_after_repeated_failures(self):
        self.b.store.upsert(Account("2002016", "", "错密码", "wrong"), touch=True)
        self.b.setReconnectAccount("2002016")
        self.b.setReconnectEnabled(True)
        for n in range(1, 4):
            self.assertTrue(wait_until(lambda: self.b.reconnect["failures"] == n), self.b.reconnect)
            wait_until(lambda: not self.b.busy and self.b.reconnect["state"] != "reconnecting")
            if n < 3:
                self.b.reconnect_tick()
        self.assertTrue(wait_until(lambda: self.b.reconnect["state"] == "paused"))
        self.assertTrue(any(t[1] == "自动重连已暂停" for t in self.toasts))
        self.b.resumeReconnect()
        self.assertEqual(self.b.reconnect["failures"], 0)

    def test_reconnect_does_not_login_when_portal_unreachable(self):
        self.b.portal_override = "http://127.0.0.1:1"
        self.b.setReconnectEnabled(True)
        self.assertTrue(wait_until(lambda: self.b.reconnect["lastState"] == "unreachable"))
        wait_until(lambda: False, 300)
        self.assertNotIn("/eportal/portal/login", self.mock.paths())
        self.assertEqual(self.b.reconnectHistory[0]["text"], "连不上校园网门户，等网络恢复后再检测")

    def test_reconnect_interval_is_custom_and_clamped(self):
        self.b.setReconnectInterval(7)
        self.assertEqual((self.b.reconnect["interval"], self.b.reconnect["intervalText"]), (7, "7 秒"))
        self.assertEqual(self.b.reconnectTimer.interval(), 7000)
        self.b.setReconnectInterval(2)      # 门户限制请求频率，最短 5 秒
        self.assertEqual(self.b.reconnect["interval"], 5)
        self.b.setReconnectInterval(90 * 60)
        self.assertEqual(self.b.reconnect["intervalText"], "90 分钟")
        self.b.setReconnectInterval(10 ** 7)
        self.assertEqual(self.b.reconnect["intervalText"], "24 小时")
        self.assertEqual(Settings(self.settings.path).get("Reconnect", "Interval"), 24 * 3600)

    def test_any_status_query_triggers_reconnect(self):
        """不用等兜底定时器：自动刷新、手动刷新看到离线就直接重连。"""
        self.b.setReconnectInterval(3600)
        self.b.login("2002010", GOOD_PASSWORD, "@telecom", True)
        self.assertTrue(wait_until(lambda: self.online_as("2002010@telecom")))
        self.b.setReconnectEnabled(True)
        self.assertTrue(wait_until(lambda: self.b.reconnect["lastState"] == "online"))
        self.mock.online, self.mock.uid = False, ""   # 被门户踢下线
        self.b.refresh(True)
        self.assertTrue(wait_until(lambda: self.b.reconnect["count"] == 1), self.b.reconnect)
        self.assertTrue(wait_until(lambda: self.online_as("2002010@telecom")))

    def test_network_event_triggers_check(self):
        self.b.setReconnectInterval(3600)
        self.b.setReconnectEnabled(True)
        self.assertTrue(wait_until(lambda: self.b.reconnect["count"] == 1))
        wait_until(lambda: not self.b.busy and self.b._refreshing is False, 2000)
        self.mock.online, self.mock.uid = False, ""
        self.b.networkTimer.setInterval(50)
        for _ in range(3):   # 一连串的变化只查一次
            self.b._on_captive_portal(True)
        self.assertTrue(wait_until(lambda: self.b.reconnect["count"] == 2, 4000), self.b.reconnectHistory)

    def test_reconnect_tick_joins_running_query(self):
        self.b.store.accounts.clear()   # 没有可用账号，只看检测本身
        self.settings.set("Reconnect", "Enabled", True)
        self.mock.requests.clear()
        self.b.refresh(True)
        self.b.reconnect_tick()          # 查询还没回来：等它的结果，不另发请求
        self.assertTrue(wait_until(lambda: self.b.reconnect["lastState"] == "offline"))
        self.assertEqual(self.mock.paths().count("/drcom/chkstatus"), 1)

    def test_concurrent_refreshes_share_one_request(self):
        got = []
        self.mock.requests.clear()
        self.b.refresh(False, got.append)
        self.b.refresh(True, got.append)
        self.b.refresh(False, got.append)
        self.assertTrue(wait_until(lambda: len(got) == 3))
        self.assertEqual(self.mock.paths().count("/drcom/chkstatus"), 1)

    def test_failed_reconnect_cools_down_on_passive_checks(self):
        self.b.store.upsert(Account("2002016", "", "错密码", "wrong"), touch=True)
        self.b.setReconnectAccount("2002016")
        self.b.setReconnectEnabled(True)
        self.assertTrue(wait_until(lambda: self.b.reconnect["failures"] == 1))
        wait_until(lambda: False, 1200)   # 失败后的刷新看到离线，但在冷却期内不会马上再试
        self.assertEqual(self.b.reconnect["failures"], 1)

    def test_throttle_countdown_during_switch(self):
        portal_module.set_auth_interval(1.5)
        self.b.login("2002010", GOOD_PASSWORD, "@telecom", True)
        self.assertTrue(wait_until(lambda: self.online_as("2002010@telecom"), 8000))
        seen = []
        self.b.throttleChanged.connect(lambda: seen.append(self.b.throttle))
        self.b.switchTo("2002011")
        self.assertTrue(wait_until(lambda: self.online_as("2002011"), 10000), self.b.status)
        wait_until(lambda: not self.b.busy)
        self.assertTrue(any(v > 0 for v in seen), seen)
        self.assertEqual(self.b.throttle, 0)

    # ---- 账号信息 / 公告 ----

    def test_account_info_loaded_when_online_and_cleared_when_offline(self):
        self.b.login("2002010", GOOD_PASSWORD, "@telecom", True)
        self.assertTrue(wait_until(lambda: self.b.accountInfo.get("shown") is True), self.b.accountInfo)
        info = self.b.accountInfo
        self.assertEqual((info["progress"], info["used"], info["left"]), (41.0, "41.38G", "58.62G"))
        items = {i["key"]: i for i in info["items"]}
        self.assertTrue(items["account"]["secret"] and items["user_name"]["secret"] and not items["use_flow"]["secret"])
        # 自动刷新不会每次都查账号信息
        before = self.mock.paths().count("/eportal/portal/page/loadUserInfo")
        for _ in range(3):
            self.b.refresh(True)
            wait_until(lambda: not self.b._refreshing, 2000)
        self.assertEqual(self.mock.paths().count("/eportal/portal/page/loadUserInfo"), before)
        self.b.logout()
        self.assertTrue(wait_until(lambda: self.b.accountInfo.get("shown") is False))

    def test_notice_and_dismiss(self):
        self.mock.notice = "<p>周末停网</p>"
        self.b.startup()
        self.assertTrue(wait_until(lambda: self.b.noticeText == "周末停网"))
        self.b.dismissNotice()
        self.assertEqual(self.b.noticeText, "")
        self.mock.notice = "新的公告"
        self.b.refreshWithToast()
        self.assertTrue(wait_until(lambda: self.b.noticeText == "新的公告"))

    # ---- 日志 ----

    def test_log_export_masks_and_includes_archives(self):
        import gzip
        import logging
        from datetime import date, timedelta

        from logfiles import archive_name
        log_path = Path(self.dir.name) / "logs" / "portal.log"
        handler = portal_module.enable_log(log_path)
        try:
            # 前一天的压缩日志
            old = archive_name(log_path, date.today() - timedelta(days=1))
            with gzip.open(old, "wt", encoding="utf-8") as g:
                g.write("2026-10-10 08:00:00,000 [事件] 昨天的记录 acct=2002099\n")
            self.b.log_path = log_path
            self.b.login("2002010", GOOD_PASSWORD, "@telecom", True)
            self.assertTrue(wait_until(lambda: self.online_as("2002010@telecom")))
            info = self.b.logInfo
            self.assertEqual(info["files"], 2)
            self.assertGreater(info["size"], 0)

            out = Path(self.dir.name) / "导出.log"
            self.assertTrue(self.b.exportLog(QUrl.fromLocalFile(str(out))))
            text = out.read_text(encoding="utf-8")
            self.assertLess(text.index("昨天的记录"), text.index("/eportal/portal/login"))   # 旧的在前
            self.assertIn("user_password=***", text)
            for secret in ("2002010", "2002099", "10.10.130.163", "测试用户"):
                self.assertNotIn(secret, text)                          # 默认打码
            self.b.setRevealed(True)
            self.assertTrue(self.b.exportLog(QUrl.fromLocalFile(str(out))))
            self.assertIn("acct=2002010@telecom", out.read_text(encoding="utf-8"))
        finally:
            portal_module.log.removeHandler(handler)
            handler.close()
            portal_module.log.setLevel(logging.NOTSET)

    def test_copy_and_reveal(self):
        self.b.copy("10.10.130.163", "IP 地址")
        self.assertEqual(QGuiApplication.clipboard().text(), "10.10.130.163")
        self.assertEqual(self.toasts[-1][:3], ("success", "已复制", "IP 地址"))
        self.b.setRevealed(True)
        self.assertTrue(self.b.revealed)


if __name__ == "__main__":
    unittest.main()
