# PyInstaller 打包配置，由 build.ps1 调用：python -m PyInstaller --noconfirm --clean xiaoyuan.spec
# PyInstaller 默认会把 PySide6 自带的全部 QML 模块和依赖都打进来（浏览器内核、3D、地图……约 400MB），
# 这里只留本程序用到的：QtQuick、Controls（Basic/Fusion/FluentWinUI3）、Templates、Effects、Shapes、Layouts、Window、
# Dialogs（导出日志的“另存为”），QtCore（StandardPaths），以及 QtNetwork 的网络状态通知（自动重连用）。
import re
from pathlib import Path

ROOT = Path(SPECPATH)

DROP = [re.compile(p, re.I) for p in (
    # 不用的 QML 模块
    r"pyside6/qml/(qt|qt3d|qt5compat|qtcharts|qtdatavisualization|qtgraphs|qtlocation|qtmultimedia|qtnetwork|"
    r"qtpositioning|qtquick3d|qtremoteobjects|qtscxml|qtsensors|qttest|qttexttospeech|qtwebchannel|"
    r"qtwebengine|qtwebsockets|qtwebview)/",
    r"pyside6/qml/qtquick/(localstorage|nativestyle|particles|pdf|scene2d|scene3d|timeline|"
    r"vectorimage|virtualkeyboard|tooling)/",   # Dialogs 要留着：导出日志用系统的“另存为”对话框
    r"pyside6/qml/qtquick/controls/(material|imagine|universal|windows|macos|ios|designer)/",
    # 不用的 Qt 库
    r"pyside6/qt6(webengine|quick3d|3d|pdf|charts|graphs|datavisualization|multimedia|location|positioning|"
    r"sensors|remoteobjects|scxml|texttospeech|webchannel|websockets|webview|virtualkeyboard|bluetooth|nfc|"
    r"serialport|designer|help|test|spatialaudio|httpserver|statemachine|quickparticles|quicktimeline|"
    r"labs|uitools|sql|svgwidgets|printsupport|quickvectorimage|concurrent|xml|"
    r"quickcontrols2(material|imagine|universal|windowsstyle|macos|ios))",
    # plugins/networkinformation 要留着：自动重连靠它接收系统的网络状态变化通知
    r"pyside6/(translations|plugins/(tls|qmltooling|position|sqldrivers|multimedia|"
    r"sensors|geoservices|texttospeech|webview|designer|canbus|generic|styles))/",
    r"opengl32sw\.dll$",                 # 软件 OpenGL 兜底，Win10/11 用 D3D11 不需要
    r"pyside6/(libcrypto|libssl)",       # Qt 网络库的 TLS，本程序只用 http
    r"qfluentwidgets|qframelesswindow",  # 旧版界面库
)]


def keep(entry) -> bool:
    return not any(p.search(entry[0].replace("\\", "/")) for p in DROP)


a = Analysis(
    [str(ROOT / "main.py")],
    pathex=[str(ROOT)],
    datas=[(str(ROOT / "qml"), "qml"), (str(ROOT / "app.ico"), "."), (str(ROOT / "LICENSE"), ".")],
    excludes=["tkinter", "unittest", "pydoc", "qfluentwidgets", "qframelesswindow", "PySide6.QtWidgets",
              "PySide6.QtWebEngineCore", "PySide6.QtWebEngineQuick", "PySide6.QtQuick3D", "PySide6.QtPdf",
              "PySide6.QtMultimedia", "PySide6.QtCharts", "PySide6.QtGraphs", "PySide6.QtTest"],
)
a.binaries = [e for e in a.binaries if keep(e)]
a.datas = [e for e in a.datas if keep(e)]

pyz = PYZ(a.pure)
exe = EXE(
    pyz,
    a.scripts,
    a.binaries,
    a.datas,
    name="校园网登录",
    icon=str(ROOT / "app.ico"),
    console=False,
    upx=False,
)
