"""请求日志的保存、轮转和导出。只依赖标准库。

当天的日志写在 logs/portal.log；过了零点（或单个文件超过 MAX_BYTES）就压缩成 logs/portal-日期.log.gz，
压缩包保留 KEEP_DAYS 天，更早的自动删除。
"""

from __future__ import annotations

import gzip
import logging
import os
import re
import shutil
from datetime import date, timedelta
from pathlib import Path

KEEP_DAYS = 30
MAX_BYTES = 2 * 1024 * 1024
_ARCHIVE = re.compile(r"^(?P<stem>.+)-(?P<day>\d{4}-\d\d-\d\d)(?:-(?P<seq>\d+))?\.log\.gz$")


class ArchivingFileHandler(logging.FileHandler):
    """只放当天日志的文件处理器；换天或文件过大时把旧内容压缩归档。"""

    def __init__(self, path: Path, keep_days: int = KEEP_DAYS, max_bytes: int = MAX_BYTES):
        path = Path(path)
        path.parent.mkdir(parents=True, exist_ok=True)
        self.keep_days = keep_days
        self.max_bytes = max_bytes
        super().__init__(path, encoding="utf-8")
        self._day = _content_day(path)
        # 上次运行留下的旧日志，启动时就归档，不用等到下一次写入
        if self._day != date.today():
            self.acquire()
            try:
                self.rollover()
            finally:
                self.release()

    @property
    def path(self) -> Path:
        return Path(self.baseFilename)

    def emit(self, record: logging.LogRecord) -> None:
        try:
            day = date.fromtimestamp(record.created)
            if day != self._day or (self.stream is not None and self.stream.tell() >= self.max_bytes):
                self.rollover(day)
        except Exception:
            self.handleError(record)
        super().emit(record)

    def rollover(self, new_day: date | None = None) -> None:
        """把当前文件压缩归档并清掉过期的压缩包。调用方需持有处理器的锁（emit 里已持有）。"""
        if self.stream is not None:
            self.stream.close()
            self.stream = None   # FileHandler.emit 会在下次写入时重新打开
        if archive(self.path, self._day):
            self._day = new_day or date.today()
        prune(self.path.parent, self.keep_days)


def _content_day(path: Path) -> date:
    """文件里内容属于哪一天：以最后修改时间为准，空文件或不存在算今天。"""
    try:
        st = path.stat()
    except OSError:
        return date.today()
    return date.fromtimestamp(st.st_mtime) if st.st_size else date.today()


def archive_name(path: Path, day: date, seq: int = 1) -> Path:
    stem = path.name.removesuffix(".log")
    return path.with_name(f"{stem}-{day.isoformat()}{'' if seq == 1 else f'-{seq}'}.log.gz")


def archive(src: Path, day: date, dest_dir: Path | None = None, dest_name: str | None = None) -> bool:
    """把 src 压缩成 dest_dir/名字-日期.log.gz 并删除 src。src 不存在或为空时什么都不做，返回 True。

    dest_name 为压缩包用的日志名（默认就是 src 的文件名），同一天的多个包按序号区分。

    先把 src 改名再压缩：别的程序还开着它时改名会失败，这时原样保留、返回 False，下次再试。
    """
    try:
        if src.stat().st_size == 0:
            return True
    except OSError:
        return True
    dest_dir = dest_dir or src.parent
    dest_dir.mkdir(parents=True, exist_ok=True)
    claimed = src.with_name(src.name + ".archiving")
    try:
        os.replace(src, claimed)
    except OSError:
        return False
    seq = 1
    while (target := archive_name(dest_dir / (dest_name or src.name), day, seq)).exists():
        seq += 1
    tmp = target.with_name(target.name + ".tmp")
    try:
        with open(claimed, "rb") as f, gzip.open(tmp, "wb") as g:
            shutil.copyfileobj(f, g)
        os.replace(tmp, target)
    except OSError:
        tmp.unlink(missing_ok=True)
        os.replace(claimed, src)   # 压缩失败就放回去，日志不丢
        return False
    claimed.unlink(missing_ok=True)
    return True


def archives(log_dir: Path) -> list[tuple[date, int, Path]]:
    """目录下的压缩日志，按日期和序号从旧到新排列。"""
    out = []
    try:
        names = os.listdir(log_dir)
    except OSError:
        return []
    for name in names:
        m = _ARCHIVE.match(name)
        if m:
            try:
                day = date.fromisoformat(m["day"])
            except ValueError:
                continue
            out.append((day, int(m["seq"] or 1), log_dir / name))
    return sorted(out)


def prune(log_dir: Path, keep_days: int) -> None:
    oldest = date.today() - timedelta(days=keep_days)
    for day, _, path in archives(log_dir):
        if day < oldest:
            path.unlink(missing_ok=True)


def migrate_legacy(old_files: list[Path], log_dir: Path, name: str = "portal.log") -> None:
    """旧版把日志直接放在数据目录（portal.log、portal.log.1），按修改日期压缩进 logs/。old_files 从旧到新排。"""
    for p in old_files:
        if p.exists():
            archive(p, date.fromtimestamp(p.stat().st_mtime), log_dir, name)


def summary(log_dir: Path, current: Path) -> dict:
    """日志占用：文件数、字节数、最早的日期。"""
    files = [p for _, _, p in archives(log_dir)] + ([current] if current.exists() else [])
    size = 0
    for p in files:
        try:
            size += p.stat().st_size
        except OSError:
            pass
    olds = archives(log_dir)
    return {"files": len(files), "size": size, "since": olds[0][0].isoformat() if olds else ""}


def export(log_dir: Path, current: Path, target: Path, transform=None) -> int:
    """把全部日志（压缩的先解压）按时间顺序合并写进 target，返回写入的字节数。

    transform(行) -> 行：导出前逐行处理（比如打码）；为 None 时原样复制。
    """
    written = 0
    tmp = target.with_name(target.name + ".tmp")
    try:
        with open(tmp, "wb") as out:
            for _, _, p in archives(log_dir):
                try:
                    with gzip.open(p, "rb") as g:
                        written += _copy(g, out, transform)
                except (OSError, EOFError):
                    continue   # 损坏的压缩包跳过，不影响其他日志
            if current.exists():
                with open(current, "rb") as f:
                    written += _copy(f, out, transform)
        os.replace(tmp, target)
    finally:
        tmp.unlink(missing_ok=True)
    return written


def _copy(src, dst, transform=None) -> int:
    n = 0
    if transform is None:
        while chunk := src.read(256 * 1024):
            dst.write(chunk)
            n += len(chunk)
        return n
    for raw in src:
        line = transform(raw.decode("utf-8", errors="replace")).encode("utf-8")
        dst.write(line)
        n += len(line)
    return n
