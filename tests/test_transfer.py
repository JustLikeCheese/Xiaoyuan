"""账号迁移格式的测试：python -m unittest tests.test_transfer -v"""

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import transfer  # noqa: E402
from store import Account  # noqa: E402

# 安卓端 TransferTest.kt 用同一份数据检查两边能互相解开
VECTOR_SALT = bytes(range(16))
VECTOR_CODE = "AB12CD"
VECTOR_ACCOUNTS = [Account("2002010", "@telecom", "我的号", "p@ss中文", terminal_type=-1),
                   Account("2002011", "", "", "", terminal_type=2)]


class TransferTest(unittest.TestCase):
    def test_round_trip(self):
        text = transfer.encode(VECTOR_ACCOUNTS, "abcd1234")
        self.assertTrue(text.startswith(transfer.PREFIX))
        got = transfer.decode(text, "abcd1234")
        self.assertEqual([(a.account, a.isp, a.note, a.password, a.terminal_type) for a in got],
                         [(a.account, a.isp, a.note, a.password, a.terminal_type) for a in VECTOR_ACCOUNTS])

    def test_wrong_code(self):
        text = transfer.encode(VECTOR_ACCOUNTS, "abcd1234")
        with self.assertRaisesRegex(transfer.TransferError, "导入码不对"):
            transfer.decode(text, "abcd1235")

    def test_password_not_in_plain_text(self):
        text = transfer.encode(VECTOR_ACCOUNTS, "abcd1234")
        self.assertNotIn("2002010", text)
        self.assertNotIn("p@ss", text)

    def test_tolerates_surrounding_text_and_line_breaks(self):
        text = transfer.encode(VECTOR_ACCOUNTS, "abcd1234")
        wrapped = "导入数据：" + "\n".join(text[i:i + 40] for i in range(0, len(text), 40)) + "\n请用导入码解开"
        self.assertEqual(len(transfer.decode(wrapped, "abcd1234")), 2)

    def test_tampered_or_garbage(self):
        text = transfer.encode(VECTOR_ACCOUNTS, "abcd1234")
        broken = text[:-3] + ("A" if text[-3] != "A" else "B") + text[-2:]
        with self.assertRaises(transfer.TransferError):
            transfer.decode(broken, "abcd1234")
        with self.assertRaisesRegex(transfer.TransferError, "不是"):
            transfer.decode("hello", "abcd1234")

    def test_code_too_short(self):
        with self.assertRaises(transfer.TransferError):
            transfer.encode(VECTOR_ACCOUNTS, "abc")

    def test_random_code(self):
        code = transfer.random_code()
        self.assertEqual(len(code), 8)
        self.assertTrue(all(c in transfer.CODE_ALPHABET for c in code))

    def test_vector_is_stable(self):
        # 固定 salt 时结果固定；安卓端的测试解的就是这一串
        a = transfer.encode(VECTOR_ACCOUNTS, VECTOR_CODE, VECTOR_SALT)
        self.assertEqual(a, transfer.encode(VECTOR_ACCOUNTS, VECTOR_CODE, VECTOR_SALT))
        self.assertEqual(len(transfer.decode(a, VECTOR_CODE)), 2)


if __name__ == "__main__":
    unittest.main()
