# 打包成单个 exe：在项目目录执行 .\build.ps1，输出 dist\校园网登录.exe
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

$python = Join-Path $PSScriptRoot '.venv\Scripts\python.exe'
if (-not (Test-Path $python)) {
    python -m venv .venv
    & $python -m pip install -r requirements.txt pyinstaller
}

# 只打包用到的 Qt 模块，规则见 xiaoyuan.spec
& $python -m PyInstaller --noconfirm --clean xiaoyuan.spec
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Remove-Item -Recurse -Force build -ErrorAction SilentlyContinue

$exe = Get-Item 'dist\校园网登录.exe'
'{0}  {1:N1} MB' -f $exe.FullName, ($exe.Length / 1MB)
