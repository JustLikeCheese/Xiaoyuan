"""日志轮转、压缩、清理和导出：python -m unittest tests.test_logfiles -v"""

import gzip
import logging
import os
import sys
import tempfile
import time
import unittest
from datetime import date, timedelta
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import logfiles  # noqa: E402
from logfiles import ArchivingFileHandler, archive_name, archives, export, migrate_legacy, prune, summary  # noqa: E402


class LogFilesTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.logs = Path(self.dir.name) / "logs"
        self.path = self.logs / "portal.log"
        self.log = logging.getLogger(f"test.logfiles.{self.id()}")
        self.log.propagate = False
        self.log.setLevel(logging.INFO)

    def tearDown(self):
        for h in list(self.log.handlers):
            self.log.removeHandler(h)
            h.close()
        self.dir.cleanup()

    def handler(self, **kw) -> ArchivingFileHandler:
        h = ArchivingFileHandler(self.path, **kw)
        h.setFormatter(logging.Formatter("%(message)s"))
        self.log.addHandler(h)
        return h

    def test_day_change_compresses_previous_day(self):
        h = self.handler()
        self.log.info("昨天")
        yesterday = date.today() - timedelta(days=1)
        h._day = yesterday   # 模拟跨过零点
        self.log.info("今天")
        h.flush()
        gz = archive_name(self.path, yesterday)
        self.assertTrue(gz.exists())
        with gzip.open(gz, "rt", encoding="utf-8") as g:
            self.assertEqual(g.read(), "昨天\n")
        self.assertEqual(self.path.read_text(encoding="utf-8"), "今天\n")

    def test_size_limit_rolls_over_same_day(self):
        self.handler(max_bytes=200)
        for i in range(30):
            self.log.info("第 %02d 行 %s", i, "x" * 20)
        olds = archives(self.logs)
        self.assertGreaterEqual(len(olds), 2)
        self.assertEqual([seq for _, seq, _ in olds], list(range(1, len(olds) + 1)))
        self.assertLessEqual(self.path.stat().st_size, 200 + 40)
        # 合并导出后，30 行一行不少、顺序不乱
        out = Path(self.dir.name) / "all.log"
        export(self.logs, self.path, out)
        lines = out.read_text(encoding="utf-8").splitlines()
        self.assertEqual([int(l.split()[1]) for l in lines], list(range(30)))

    def test_leftover_log_from_earlier_day_is_archived_on_start(self):
        self.logs.mkdir(parents=True)
        self.path.write_text("上次运行\n", encoding="utf-8")
        old = time.time() - 3 * 86400
        os.utime(self.path, (old, old))
        self.handler()
        day = date.fromtimestamp(old)
        self.assertTrue(archive_name(self.path, day).exists())
        self.assertFalse(self.path.exists())   # 下次写入时才新建
        self.log.info("新的一天")
        self.assertEqual(self.path.read_text(encoding="utf-8"), "新的一天\n")

    def test_prune_removes_archives_older_than_keep_days(self):
        self.logs.mkdir(parents=True)
        today = date.today()
        for days in (1, 10, logfiles.KEEP_DAYS + 1, 90):
            with gzip.open(archive_name(self.path, today - timedelta(days=days)), "wt") as g:
                g.write("x\n")
        prune(self.logs, logfiles.KEEP_DAYS)
        self.assertEqual(sorted((today - d).days for d, _, _ in archives(self.logs)), [1, 10])

    def test_migrate_legacy_and_summary(self):
        root = Path(self.dir.name)
        (root / "portal.log.1").write_text("更旧\n", encoding="utf-8")
        (root / "portal.log").write_text("旧\n", encoding="utf-8")
        migrate_legacy([root / "portal.log.1", root / "portal.log"], self.logs)
        self.assertFalse((root / "portal.log").exists())
        info = summary(self.logs, self.path)
        self.assertEqual(info["files"], 2)
        out = root / "out.log"
        export(self.logs, self.path, out, transform=str.upper)
        self.assertEqual(out.read_text(encoding="utf-8"), "更旧\n旧\n")

    def test_export_skips_corrupt_archive(self):
        self.logs.mkdir(parents=True)
        archive_name(self.path, date.today() - timedelta(days=2)).write_bytes(b"not gzip")
        self.path.write_text("当前\n", encoding="utf-8")
        out = Path(self.dir.name) / "out.log"
        export(self.logs, self.path, out)
        self.assertEqual(out.read_text(encoding="utf-8"), "当前\n")


if __name__ == "__main__":
    unittest.main()
