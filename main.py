"""校园网登录 —— Qt Quick（QML）+ 官方 FluentWinUI3 风格。

GPU 渲染，动画跟随屏幕刷新率（165Hz 屏就是 165 帧）；文字用系统原生渲染（ClearType）。
界面在 qml/，逻辑在 backend.py，门户协议在 portal.py，账号保存在 store.py。

    python main.py
设置环境变量 XIAOYUAN_PORTAL=http://127.0.0.1:18080 可改连本地假门户（见 tests/mock_portal.py）。
"""

from __future__ import annotations

import sys

from PySide6.QtCore import QUrl
from PySide6.QtGui import QFont, QGuiApplication, QIcon
from PySide6.QtQml import QQmlApplicationEngine
from PySide6.QtQuick import QQuickWindow
from PySide6.QtQuickControls2 import QQuickStyle

import logfiles
from backend import APP_NAME, APP_VERSION, Backend, Settings, apply_theme, default_log_path, resource_dir, ui_font_families
from portal import enable_log
from store import app_dir

LATIN_FAMILIES = ("Segoe UI Variable", "Segoe UI Variable Text", "Segoe UI Variable Display",
                  "Segoe UI Variable Small", "Segoe UI")


def setup_fonts(app: QGuiApplication, settings: Settings) -> None:
    families = ui_font_families(settings)
    font = app.font()
    font.setFamilies(families)
    font.setHintingPreference(QFont.PreferFullHinting)   # 笔画对齐像素，175% 等缩放下更清晰
    app.setFont(font)
    # FluentWinUI3 控件自己指定了 Segoe UI Variable，遇到中文时 Windows 默认回退到宋体；改成回退到选定的中文字体
    cjk = [f for f in families if f not in LATIN_FAMILIES]
    for fam in LATIN_FAMILIES:
        QFont.insertSubstitutions(fam, cjk)


def create_engine(app: QGuiApplication, backend: Backend) -> QQmlApplicationEngine:
    engine = QQmlApplicationEngine()
    engine.rootContext().setContextProperty("backend", backend)
    engine.load(QUrl.fromLocalFile(str(resource_dir() / "qml" / "Main.qml")))
    return engine


def prepare() -> None:
    """必须在创建 QGuiApplication 之前调用。"""
    if sys.platform == "win32":
        import ctypes
        # 让任务栏使用本程序的图标，而不是 python.exe 的
        ctypes.windll.shell32.SetCurrentProcessExplicitAppUserModelID("Xiaoyuan.CampusLogin")
    # 系统原生文字渲染（ClearType + 微调），比 Qt Quick 默认的距离场渲染更锐利
    QQuickWindow.setTextRenderType(QQuickWindow.TextRenderType.NativeTextRendering)
    QQuickStyle.setStyle("FluentWinUI3")


def main() -> int:
    prepare()
    log_path = default_log_path()
    # 旧版把日志直接放在数据目录（portal.log、portal.log.1），压缩后挪进 logs/
    logfiles.migrate_legacy([app_dir() / "portal.log.1", app_dir() / "portal.log"], log_path.parent)
    enable_log(log_path)
    app = QGuiApplication(sys.argv)
    app.setApplicationName(APP_NAME)
    app.setApplicationVersion(APP_VERSION)
    app.setWindowIcon(QIcon(str(resource_dir() / "app.ico")))
    settings = Settings()
    setup_fonts(app, settings)
    apply_theme(settings.get("Appearance", "Theme"))

    backend = Backend(settings=settings)
    engine = create_engine(app, backend)
    if not engine.rootObjects():
        return 1
    return app.exec()


if __name__ == "__main__":
    sys.exit(main())
