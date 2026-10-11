"""协议层和账号存储的测试，全部跑在本地假门户上：python -m unittest -v"""

import os
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import logging  # noqa: E402
import time  # noqa: E402

import portal as portal_module  # noqa: E402
from portal import (LoginError, Portal, PortalError, RateLimitedError, _decode_msg, _redact, format_flow,  # noqa: E402
                    format_minutes, parse_jsonp)
from store import Account, AccountStore  # noqa: E402
from tests.mock_portal import CLIENT_IP, CLIENT_MAC, GOOD_PASSWORD, MockPortal  # noqa: E402


class PortalTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.mock = MockPortal().start()

    @classmethod
    def tearDownClass(cls):
        cls.mock.stop()

    def setUp(self):
        self.mock.reset()
        self.mock.release_delay = 0
        portal_module.set_auth_interval(0)
        portal_module.forget_terminal()
        self.portal = Portal(self.mock.base, timeout=3)

    def request(self, path: str) -> list[tuple[str, str]]:
        return next(q for p, q in self.mock.requests if p == path)

    def tearDown(self):
        # 本校门户必须走 eportal，内核的 /drcom/login、/drcom/logout 用了就会“显示在线却没网”
        self.assertFalse({"/drcom/login", "/drcom/logout"} & set(self.mock.paths()), self.mock.paths())

    def test_login_sends_same_params_as_web_page(self):
        self.portal.login("2002010", GOOD_PASSWORD, "@telecom")
        query = self.request("/eportal/portal/login")
        self.assertEqual([k for k, _ in query], [
            "callback", "login_method", "user_account", "user_password", "wlan_user_ip", "wlan_user_ipv6",
            "wlan_user_mac", "wlan_ac_ip", "wlan_ac_name", "jsVersion", "terminal_type", "lang", "v", "lang"])
        q = dict(query[:-1])
        self.assertEqual(q["login_method"], "1")
        self.assertEqual(q["user_account"], ",0,2002010@telecom")
        self.assertEqual(q["wlan_user_ip"], CLIENT_IP)
        self.assertEqual(q["wlan_user_mac"], CLIENT_MAC)
        self.assertEqual(q["terminal_type"], "1")
        self.assertEqual(q["lang"], "zh-cn")
        self.assertEqual(query[-1], ("lang", "zh"))
        self.assertTrue(self.mock.online)

    def test_logout_sends_same_params_as_web_page(self):
        self.portal.login("2002010", GOOD_PASSWORD)
        self.assertTrue(self.portal.logout())
        q = dict(self.request("/eportal/portal/logout"))
        self.assertEqual((q["login_method"], q["user_account"], q["user_password"]), ("1", "drcom", "123"))
        self.assertEqual((q["ac_logout"], q["register_mode"], q["wlan_vlan_id"]), ("0", "1", "0"))
        self.assertEqual((q["wlan_user_ip"], q["wlan_user_mac"]), (CLIENT_IP, CLIENT_MAC))
        self.assertFalse(self.mock.online)

    def test_password_special_characters_round_trip(self):
        password = "p@ss+&/中文 =x?"
        with self.assertRaises(LoginError):
            self.portal.login("nouser9", password)
        # 账号不存在会失败，这里只关心密码是否原样到达门户
        self.assertEqual(dict(self.request("/eportal/portal/login"))["user_password"], password)

    def test_login_while_online_reports_ip_already_online(self):
        self.portal.login("2002010", GOOD_PASSWORD)
        with self.assertRaises(LoginError) as cm:
            self.portal.login("2002011", GOOD_PASSWORD)
        self.assertEqual(cm.exception.code, 2)

    def test_switch_waits_for_ac_to_release_old_session(self):
        self.mock.release_delay = 1.2
        self.portal.login("2002010", GOOD_PASSWORD, "@telecom")
        started = time.monotonic()
        self.portal.switch("2002011", GOOD_PASSWORD)
        self.assertGreaterEqual(time.monotonic() - started, 1.0)
        self.assertEqual(self.mock.uid, "2002011")
        paths = [p for p in self.mock.paths() if p.startswith("/eportal/portal/log")]
        self.assertEqual(paths[:3], ["/eportal/portal/login", "/eportal/portal/logout", "/eportal/portal/login"])
        self.assertGreater(paths.count("/eportal/portal/login"), 2)   # 中间重试过

    def test_login_right_after_logout_retries(self):
        self.mock.release_delay = 1.2
        self.portal.login("2002010", GOOD_PASSWORD)
        self.portal.logout()
        with self.assertRaises(LoginError):
            self.portal.login("2002010", GOOD_PASSWORD)          # 不等就会撞上 AC 还没释放
        self.portal.login("2002010", GOOD_PASSWORD, wait=5)      # 等着重试就能登上
        self.assertTrue(self.mock.online)

    def test_switch_gives_up_after_wait(self):
        self.mock.release_delay = 5
        self.portal.login("2002010", GOOD_PASSWORD)
        with self.assertRaises(LoginError) as cm:
            self.portal.switch("2002011", GOOD_PASSWORD, wait=1.5)
        self.assertEqual(cm.exception.code, 2)

    # ---- 请求频率 ----

    def auth_times(self) -> list[float]:
        return [t for (p, _), t in zip(self.mock.requests, self.mock.times) if p.startswith("/eportal/portal/log")]

    def test_auth_requests_are_spaced(self):
        portal_module.set_auth_interval(0.6)
        waits = []
        portal_module.set_wait_listener(waits.append)
        try:
            self.portal.login("2002010", GOOD_PASSWORD)
            self.portal.logout()
            self.portal.login("2002010", GOOD_PASSWORD)
        finally:
            portal_module.set_wait_listener(None)
        times = self.auth_times()
        self.assertEqual(len(times), 3)
        self.assertTrue(all(b - a >= 0.55 for a, b in zip(times, times[1:])), times)
        self.assertEqual(len(waits), 2)   # 第 2、3 个请求都排了队，界面能显示倒计时
        self.assertTrue(self.mock.online)

    def test_status_is_not_rate_limited(self):
        portal_module.set_auth_interval(5)
        started = time.monotonic()
        for _ in range(3):
            self.portal.status()
        self.assertLess(time.monotonic() - started, 2)

    def test_switch_reuses_terminal_info(self):
        self.portal.login("2002010", GOOD_PASSWORD)
        self.portal.status()
        self.mock.requests.clear()
        self.portal.switch("2002011", GOOD_PASSWORD)
        # 只有注销和登录两个请求，不再各查一次状态
        self.assertEqual(self.mock.paths(), ["/eportal/portal/logout", "/eportal/portal/login"])
        self.assertEqual(self.mock.uid, "2002011")

    def test_switch_with_auth_interval_needs_no_retry(self):
        # 默认的请求间隔本身就给了 AC 释放旧会话的时间
        portal_module.set_auth_interval(1.0)
        self.mock.release_delay = 0.8
        self.portal.login("2002010", GOOD_PASSWORD)
        self.mock.requests.clear()
        self.portal.switch("2002011", GOOD_PASSWORD)
        self.assertEqual([p for p in self.mock.paths() if p.startswith("/eportal")],
                         ["/eportal/portal/logout", "/eportal/portal/login"])

    def test_busy_reply_backs_off_and_retries(self):
        portal_module.set_auth_interval(0.2)
        self.mock.busy_replies = 2
        self.portal.login("2002010", GOOD_PASSWORD)
        self.assertTrue(self.mock.online)
        times = self.auth_times()
        self.assertEqual(len(times), 3)
        self.assertGreaterEqual(times[2] - times[1], times[1] - times[0] - 0.05)   # 间隔逐次加长
        self.assertGreaterEqual(times[1] - times[0], 0.35)
        self.assertEqual(self.portal.gate.backoff, 0)   # 成功后恢复正常间隔

    def test_busy_reply_gives_up(self):
        portal_module.set_auth_interval(0.1)
        self.mock.busy_replies = 10
        with self.assertRaises(RateLimitedError):
            self.portal.login("2002010", GOOD_PASSWORD)
        self.assertEqual(len(self.auth_times()), portal_module.RATE_RETRIES + 1)
        self.assertFalse(self.mock.online)

    def test_stale_terminal_ip_is_refreshed(self):
        portal_module._terminals[self.portal.host] = (time.monotonic(), "10.0.0.99", CLIENT_MAC, "0", "")
        self.portal.login("2002010", GOOD_PASSWORD)
        self.assertTrue(self.mock.online)
        logins = [dict(q)["wlan_user_ip"] for p, q in self.mock.requests if p == "/eportal/portal/login"]
        self.assertEqual(logins, ["10.0.0.99", CLIENT_IP])

    # ---- 门户配置（page/loadConfig），对照 a41.js / a40.js ----

    def test_logout_uses_ac_logout_and_register_mode_from_config(self):
        self.mock.config.update(ac_logout="1", register_mode="4")
        self.portal.login("2002010", GOOD_PASSWORD)
        self.assertTrue(self.portal.logout())
        q = dict(self.request("/eportal/portal/logout"))
        self.assertEqual((q["ac_logout"], q["register_mode"]), ("1", "4"))
        self.assertFalse(self.mock.online)

    def test_logout_unbinds_mac_when_portal_requires_it(self):
        self.mock.config.update(un_bind_mac="1", register_mode="1")
        self.portal.login("2002010", GOOD_PASSWORD, "@telecom")
        self.portal.status()             # 页面也是先查状态拿到在线账号
        self.mock.requests.clear()
        self.assertTrue(self.portal.logout())
        self.assertEqual(self.mock.paths(), ["/eportal/portal/mac/unbind"])   # 解绑成功就不再调 logout
        q = dict(self.request("/eportal/portal/mac/unbind")[:-1])
        self.assertEqual([k for k, _ in self.request("/eportal/portal/mac/unbind")],
                         ["callback", "user_account", "wlan_user_mac", "wlan_user_ip", "jsVersion", "v", "lang"])
        self.assertEqual(q["user_account"], "2002010@telecom")              # 不带 ,0, 前缀
        self.assertEqual(q["wlan_user_mac"], CLIENT_MAC.upper())
        self.assertEqual(q["wlan_user_ip"], "168460963")                     # 10.10.130.163 的十进制
        self.assertFalse(self.mock.online)

    def test_unbind_not_used_for_other_register_modes(self):
        self.mock.config.update(un_bind_mac="1", register_mode="2")   # 页面只在 1/3/4 时解绑
        self.portal.login("2002010", GOOD_PASSWORD)
        self.assertTrue(self.portal.logout())
        self.assertNotIn("/eportal/portal/mac/unbind", self.mock.paths())

    def test_unbind_failure_falls_back_to_logout(self):
        self.mock.config.update(un_bind_mac="1")
        self.portal.login("2002010", GOOD_PASSWORD)
        self.portal.status()
        portal_module._terminals[self.portal.host] = (time.monotonic(), CLIENT_IP, CLIENT_MAC, "0", "别人的账号")
        self.assertTrue(self.portal.logout())
        paths = [p for p in self.mock.paths() if p in ("/eportal/portal/mac/unbind", "/eportal/portal/logout")]
        self.assertEqual(paths, ["/eportal/portal/mac/unbind", "/eportal/portal/logout"])
        self.assertFalse(self.mock.online)

    def test_unbind_gets_account_from_fresh_status_when_not_cached(self):
        self.mock.config.update(un_bind_mac="1")
        self.portal.login("2002010", GOOD_PASSWORD)
        portal_module._terminals[self.portal.host] = (time.monotonic(), CLIENT_IP, CLIENT_MAC, "0", "")
        self.assertTrue(self.portal.logout())
        self.assertIn("/eportal/portal/mac/unbind", self.mock.paths())
        self.assertNotIn("/eportal/portal/logout", self.mock.paths())

    def test_account_prefix_follows_config(self):
        self.mock.config.update(account_prefix="0")
        self.portal.login("2002010", GOOD_PASSWORD)
        self.assertEqual(dict(self.request("/eportal/portal/login"))["user_account"], "2002010")
        self.assertEqual(self.mock.uid, "2002010")

    def test_terminal_type_and_phone_prefix(self):
        Portal(self.mock.base, timeout=3, terminal_type=2).login("2002010", GOOD_PASSWORD)
        q = dict(self.request("/eportal/portal/login"))
        self.assertEqual((q["terminal_type"], q["user_account"]), ("2", ",1,2002010"))   # 手机前缀 ,1,
        self.assertTrue(self.mock.online)
        self.mock.reset()
        Portal(self.mock.base, timeout=3, terminal_type=3).login("2002010", GOOD_PASSWORD)
        q = dict(self.request("/eportal/portal/login"))
        self.assertEqual((q["terminal_type"], q["user_account"]), ("3", ",0,2002010"))
        self.assertEqual(Portal(self.mock.base, terminal_type=7).terminal_type, 1)        # 不认识的值按电脑

    def test_config_is_loaded_once_and_cached(self):
        self.portal.login("2002010", GOOD_PASSWORD)
        self.portal.logout()
        self.portal.login("2002010", GOOD_PASSWORD)
        self.assertEqual(self.mock.paths().count("/eportal/portal/page/loadConfig"), 1)
        q = dict(self.request("/eportal/portal/page/loadConfig"))
        self.assertEqual(q["wlan_user_ip"], "MTAuMTAuMTMwLjE2Mw==")   # 页面传的是 Base64 的 IP
        self.assertEqual(q["wlan_vlan_id"], "0")

    def test_missing_config_falls_back_to_defaults(self):
        self.mock.config_available = False
        self.portal.login("2002010", GOOD_PASSWORD)
        self.assertTrue(self.mock.online)
        self.assertFalse(self.portal.config().loaded)

    # ---- 账号信息 / 公告 ----

    def test_user_info(self):
        self.assertIsNone(self.portal.user_info())          # 不在线没有账号信息
        self.portal.login("2002010", GOOD_PASSWORD, "@telecom")
        info = self.portal.user_info()
        self.assertEqual([(k, t) for k, t, _ in info.fields],   # 顺序按门户配置的 user_info，标题用门户给的
                         [("account", "账号"), ("user_name", "姓名"), ("use_flow", "已使用流量"),
                          ("available_flow", "可使用流量")])
        self.assertEqual((info.progress, info.progress_type, info.used, info.available), (41.0, 1, "41.38G", "58.62G"))
        q = dict(self.request("/eportal/portal/page/loadUserInfo"))
        self.assertEqual((q["user_account"], q["program_index"], q["wlan_user_ip"]),
                         ("2002010@telecom", "cWOgVj1776852143", "168460963"))

    def test_user_info_without_portal_field_list(self):
        self.mock.config.update(user_info="")   # 门户没配置要显示哪些：全部显示，标题用内置的
        self.portal.login("2002010", GOOD_PASSWORD)
        keys = [k for k, _, _ in self.portal.user_info().fields]
        self.assertEqual(keys, ["account", "user_name", "use_flow", "available_flow", "group_name"])
        self.assertEqual(self.portal.user_info().fields[-1][1], "用户组")

    def test_notice(self):
        self.assertEqual(self.portal.notice(), "")
        self.mock.notice = "<p>今晚 <b>23:00</b> 停网维护</p><p>请提前保存</p>"
        self.assertEqual(self.portal.notice(), "今晚 23:00 停网维护\n请提前保存")

    def test_password_never_written_to_log(self):
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / "portal.log"
            portal_module.enable_log(path)
            try:
                self.portal.login("2002010", GOOD_PASSWORD)
            finally:
                for h in list(portal_module.log.handlers):
                    portal_module.log.removeHandler(h)
                    h.close()
                portal_module.log.setLevel(logging.NOTSET)
            text = path.read_text(encoding="utf-8")
        self.assertIn("/eportal/portal/login", text)
        self.assertIn("user_password=***", text)
        self.assertNotIn(GOOD_PASSWORD + "&", text)

    def test_status_online_parses_gbk_response(self):
        self.portal.login("2002010", GOOD_PASSWORD, "@cmcc")
        st = self.portal.status()
        self.assertTrue(st.online)
        self.assertEqual(st.account, "2002010@cmcc")
        self.assertEqual(st.ip, "10.10.130.163")
        self.assertEqual((st.minutes, st.flow_kb), (15505, 43392041))

    def test_status_offline(self):
        st = self.portal.status()
        self.assertFalse(st.online)
        self.assertEqual(st.ip, "10.10.130.163")

    def test_wrong_password_base64_code_is_decoded_and_explained(self):
        with self.assertRaises(LoginError) as cm:
            self.portal.login("2002010", "wrong")
        self.assertEqual(str(cm.exception), "账号或密码错误（ldap auth error）")

    def test_unknown_account_uses_portal_prompt(self):
        with self.assertRaises(LoginError) as cm:
            self.portal.login("nouser1", "x")
        self.assertEqual(str(cm.exception), "账号不存在")

    def test_radius_failure(self):
        with self.assertRaisesRegex(LoginError, "^运营商认证失败"):
            self.portal.login("radfail", "x", "@unicom")

    def test_logout_when_offline(self):
        self.assertFalse(self.portal.logout())

    def test_timeout_is_reported_as_portal_error(self):
        portal = Portal(self.mock.base, timeout=0.5)
        with self.assertRaisesRegex(PortalError, "超时"):
            portal.login("slow1", "x")

    def test_unreachable_portal(self):
        with self.assertRaises(PortalError):
            Portal("http://127.0.0.1:1", timeout=1).status()


class HelpersTest(unittest.TestCase):
    def test_decode_msg(self):
        self.assertEqual(_decode_msg("bGRhcCBhdXRoIGVycm9y"), "ldap auth error")
        self.assertEqual(_decode_msg("userid error1"), "userid error1")
        self.assertEqual(_decode_msg("error0"), "error0")
        self.assertEqual(_decode_msg("账号不存在"), "账号不存在")
        self.assertEqual(_decode_msg(""), "")

    def test_redact(self):
        self.assertEqual(_redact("a?x=1&user_password=p%40ss&y=2"), "a?x=1&user_password=***&y=2")

    def test_parse_jsonp(self):
        self.assertEqual(parse_jsonp('   dr1002({"result":1});'), {"result": 1})
        with self.assertRaises(PortalError):
            parse_jsonp("<html>404</html>")

    def test_formatting(self):
        self.assertEqual(format_minutes(45), "45 分钟")
        self.assertEqual(format_minutes(15505), "258 小时 25 分")
        self.assertEqual(format_flow(43392041), "41.38 GB")
        self.assertEqual(format_flow(2048), "2.00 MB")


class StoreTest(unittest.TestCase):
    def test_round_trip_with_encrypted_password(self):
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / "accounts.json"
            store = AccountStore(path)
            store.upsert(Account("2002010", "@telecom", "我的号", "p@ss中文"), touch=True)
            store.upsert(Account("2002011", "", "室友", "abc"))
            raw = path.read_text(encoding="utf-8")
            self.assertNotIn("p@ss中文", raw)

            again = AccountStore(path)
            self.assertEqual([a.account for a in again.accounts], ["2002010", "2002011"])
            self.assertEqual(again.get("2002010").password, "p@ss中文")
            self.assertEqual(again.last().account, "2002010")
            self.assertEqual(again.get("2002010").carrier, "中国电信")

    def test_terminal_type_saved_and_old_files_default(self):
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / "accounts.json"
            path.write_text('{"accounts": [{"account": "old1", "isp": ""}]}', encoding="utf-8")   # 旧版没有这个字段
            store = AccountStore(path)
            self.assertEqual(store.get("old1").terminal_type, -1)
            store.upsert(Account("a2", terminal_type=2))
            self.assertEqual(AccountStore(path).get("a2").terminal_type, 2)

    def test_rename_and_remove(self):
        with tempfile.TemporaryDirectory() as d:
            store = AccountStore(Path(d) / "accounts.json")
            store.upsert(Account("a1", "", "", "x"), touch=True)
            store.rename("a1", Account("a2", "@cmcc", "", "y"))
            self.assertIsNone(store.get("a1"))
            self.assertTrue(store.get("a2").last_used)
            store.remove("a2")
            self.assertEqual(AccountStore(store.path).accounts, [])

    def test_undecryptable_password_is_flagged(self):
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / "accounts.json"
            path.write_text('{"accounts":[{"account":"a1","password":"bm90LWRwYXBp"}]}', encoding="utf-8")
            acc = AccountStore(path).get("a1")
            self.assertEqual(acc.password, "")
            self.assertTrue(acc.password_lost)


if __name__ == "__main__":
    os.chdir(Path(__file__).resolve().parent.parent)
    unittest.main()
