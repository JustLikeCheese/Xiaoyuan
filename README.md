# 校园网登录

作者：JustLikeCheese · 项目地址：<https://github.com/JustLikeCheese/Xiaoyuan> · 许可证：[GNU GPL v3](LICENSE)

直接调用 Dr.COM 门户（10.253.0.1）的认证接口登录校园网，不加载门户网页里的大图和脚本。

界面用 Qt Quick（QML）+ Qt 官方的 FluentWinUI3 风格：GPU 渲染，动画跟随屏幕刷新率（165Hz 屏实测 144–165 帧），
文字用系统原生渲染（ClearType），深浅色跟随系统或手动切换。

- 账号 + 密码 + 运营商（校园网 / 联通 / 电信 / 移动）登录、注销、一键切换账号（自动先注销再登录）
- 在线状态、IP、已用时长和流量；连接详情（默认折叠，可自动刷新，可看门户返回的全部原始字段）
- 点任意数值即复制；首页“账号登录”可以折叠（记住折叠状态）
- 账号信息：在线时显示门户的已用/可用流量（或时长）、用量进度条、姓名、套餐等（`page/loadUserInfo`，最多 2 分钟查一次）
- 门户公告：首页顶部显示学校通知（`notice/get_notice_content`），可关闭
- 终端类型：登录时发给门户的 `terminal_type`（电脑/手机/平板/其他），选手机时账号前缀为 `,1,`，和网页一致。
  设置 → 门户里是全局默认值；登录页和每个账号（账号管理里编辑）可以单独选，选“默认”就跟随全局设置。
  记住账号时登录页的选择一并保存，切换账号、自动登录、自动重连都用账号自己的设置
- 右上角眼睛：一键显示/隐藏账号、实名、密码、IP、MAC，默认隐藏
- 保存多个账号，密码用 Windows DPAPI 加密，只有当前 Windows 用户能解开；账号管理页顶部显示当前登录的账号，表格里标“在线”
- 自动重连：不靠高频轮询。系统报告网络断开/恢复/被门户拦截时立即检测；任何一次查状态（启动、刷新、自动刷新）
  看到离线都直接重连；另有可自定义的兜底检测间隔（5 秒～24 小时，默认 1 分钟），每拿到一次状态就重新计时。
  手动注销后暂停、再次登录后恢复，连续失败 3 次暂停并提醒，失败后有冷却，连不上门户时不尝试登录；有重连记录
- 请求频率：门户疑似限制请求频率（约 5 秒一次），登录、注销请求自动排队、两次之间至少隔 5 秒，排队时登录页显示倒计时；
  门户回复“繁忙/太频繁”时退避后自动重发。登录复用最近一次查到的本机 IP/MAC，切换账号只发“注销、登录”两个请求
- 日志：每天一个文件，过了零点（或单个文件超过 2 MB）自动压缩成 .log.gz，保留 30 天。
  设置 → 导出日志：把全部日志合并成一个文本文件，右上角眼睛关着时账号、IP、MAC 打码，密码始终不写进日志
- 可选：启动时自动登录、自动登录成功后关闭窗口

## 使用

直接运行 `dist\校园网登录.exe`。账号、设置保存在 `%APPDATA%\XiaoyuanLogin\`，请求日志在其中的 `logs\`（密码已打码）。

## 开发

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt pyinstaller
.\.venv\Scripts\python.exe main.py
```

| 文件 | 作用 |
|---|---|
| `portal.py` | 门户协议和请求频率控制，只用标准库；也能当命令行用：`python portal.py status` |
| `logfiles.py` | 日志按天轮转、压缩、清理和导出 |
| `store.py` | 多账号保存与 DPAPI 加密 |
| `backend.py` | 界面背后的逻辑（登录、切换、自动登录、自动刷新、自动重连、日志导出、设置），暴露给 QML |
| `main.py` | 程序入口：字体、文字渲染、主题，加载 QML |
| `qml/` | 界面 |
| `xiaoyuan.spec` | 打包配置，只打包用到的 Qt 模块 |

登录、注销的参数和门户网页（`a41.js`/`a40.js`，jsVersion 4.2.1）一致：先读 `/eportal/portal/page/loadConfig`，
账号前缀（`account_prefix`）、注销方式（`ac_logout`）、后台类型（`register_mode`）都取自门户配置；门户开了
`un_bind_mac` 时注销走 `/eportal/portal/mac/unbind`（失败再退回 logout）。`python portal.py config` 可以查看门户实际下发的值；`info`、`notice` 子命令分别查看账号信息和公告。

本校门户的认证方式是 `login_method=1`：登录、注销必须走 801 端口的 `/eportal/portal/login`、`/eportal/portal/logout`，
不能用 `/drcom/login`、`/drcom/logout`（内核会显示在线，但不放行上网）。测试里对此有专门检查。

## 测试

```powershell
.\.venv\Scripts\python.exe -m unittest tests.test_portal tests.test_backend tests.test_logfiles   # 协议、存储、逻辑、日志，约 45 秒
.\.venv\Scripts\python.exe tests\qml_check.py    # 界面全流程 + 截图到 tests\shots\，后台运行不弹窗
.\.venv\Scripts\python.exe tests\perf_check.py   # 真实屏幕上的帧率和浅色/深色截图（窗口置顶约 15 秒，不抢焦点）
```

以上都连本地假门户（`tests/mock_portal.py`），不会碰真门户。手动连假门户调界面：先运行
`python tests\mock_portal.py`，再设置环境变量 `XIAOYUAN_PORTAL=http://127.0.0.1:18080` 后运行 `main.py`
（假门户的正确密码是 `right`）。

## 打包

```powershell
.\build.ps1      # 输出 dist\校园网登录.exe（单文件，约 46 MB）
```

## 许可证

本项目以 [GNU 通用公共许可证第 3 版（GPL v3）](LICENSE) 发布。你可以自由使用、修改和再发布，
但再发布（包括修改后的版本和打包好的 exe）必须同样以 GPL v3 开源并附上源代码。
