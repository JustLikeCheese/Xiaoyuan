#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
wlan_mac_changer.py —— 修改 Windows 无线网卡 (WLAN) 的 MAC 地址，并测试网络连通性。

原理：
  在网卡驱动的注册表项
  HKLM\\SYSTEM\\CurrentControlSet\\Control\\Class\\{4d36e972-e325-11ce-bfc1-08002be10318}\\00xx
  写入 NetworkAddress（12 位十六进制），然后禁用/启用网卡让驱动重新加载。

注意：
  * 需要管理员权限（脚本会自动弹 UAC 提权）。
  * Windows 无线网卡只接受“本地管理 + 单播”地址：第 1 字节的第 2 个十六进制位
    必须是 2 / 6 / A / E（例如 02-xx-xx-xx-xx-xx），否则驱动会忽略。
  * 如果该 Wi-Fi 网络开启了 Windows「随机硬件地址」，它可能覆盖这里设置的 MAC。

用法：
  python wlan_mac_changer.py --list                    列出无线网卡
  python wlan_mac_changer.py                           随机生成新 MAC，应用后测试连通性
  python wlan_mac_changer.py --mac 02:11:22:33:44:55   使用指定的 MAC
  python wlan_mac_changer.py --restore                 恢复原来的 MAC
  python wlan_mac_changer.py --test-only               只测试连通性
  python wlan_mac_changer.py --dry-run                 只显示将要做什么，不修改
  可选参数：--adapter WLAN  --profile <Wi-Fi 配置名>  --ping 192.168.1.254（可重复）
"""

import argparse
import ctypes
import json
import os
import random
import re
import socket
import subprocess
import sys
import time
import winreg
from datetime import datetime

NIC_CLASS_KEY = r"SYSTEM\CurrentControlSet\Control\Class\{4d36e972-e325-11ce-bfc1-08002be10318}"
BACKUP_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "wlan_mac_backup.json")
DEFAULT_PING_TARGETS = ["223.5.5.5", "119.29.29.29"]
DEFAULT_DNS_HOST = "www.baidu.com"


# ------------------------------------------------------------------ 基础工具

def run_ps(command, check=True):
    """执行 PowerShell 命令，按 UTF-8 返回 stdout。"""
    full = ("[Console]::OutputEncoding=[Text.Encoding]::UTF8; "
            "$ErrorActionPreference='Stop'; " + command)
    proc = subprocess.run(["powershell", "-NoProfile", "-NonInteractive", "-Command", full],
                          capture_output=True)
    out = proc.stdout.decode("utf-8", errors="replace").strip()
    if check and proc.returncode != 0:
        err = proc.stderr.decode("utf-8", errors="replace").strip()
        raise RuntimeError(f"PowerShell 执行失败：{command}\n{err}")
    return out


def run_cmd(args):
    """执行 netsh / ping 等命令，按系统 OEM 代码页解码（兼容中文系统）。"""
    proc = subprocess.run(args, capture_output=True)
    return proc.returncode, proc.stdout.decode("oem", errors="replace")


def ps_quote(s):
    return "'" + s.replace("'", "''") + "'"


def is_admin():
    try:
        return bool(ctypes.windll.shell32.IsUserAnAdmin())
    except Exception:
        return False


def relaunch_as_admin():
    params = subprocess.list2cmdline([os.path.abspath(__file__)] + sys.argv[1:] + ["--pause"])
    rc = ctypes.windll.shell32.ShellExecuteW(None, "runas", sys.executable, params, os.getcwd(), 1)
    if rc <= 32:
        sys.exit("提权失败（可能在 UAC 弹窗里点了“否”）。请用管理员身份打开终端再运行。")
    print("已在新的管理员窗口中运行，请查看那个窗口的输出。")
    sys.exit(0)


# ------------------------------------------------------------------ MAC 地址

def clean_mac(mac):
    return re.sub(r"[\s:\-.]", "", mac or "").upper()


def fmt_mac(mac):
    m = clean_mac(mac)
    return "-".join(m[i:i + 2] for i in range(0, 12, 2)) if len(m) == 12 else (m or "-")


def parse_mac(text):
    mac = clean_mac(text)
    if not re.fullmatch(r"[0-9A-F]{12}", mac):
        raise ValueError(f"MAC 地址格式不对：{text}")
    if mac[1] not in "26AE":
        raise ValueError(f"无线网卡要求第 1 字节的第 2 位是 2/6/A/E（例如 {fmt_mac(mac[0] + '2' + mac[2:])}），"
                         f"否则驱动会忽略：{fmt_mac(mac)}")
    return mac


def random_wlan_mac():
    b = bytearray(os.urandom(6))
    b[0] = (b[0] & 0xF0) | random.choice((0x2, 0x6, 0xA, 0xE))  # 本地管理 + 单播
    return b.hex().upper()


# ------------------------------------------------------------------ 网卡 / 注册表

def get_wifi_adapters():
    out = run_ps(
        "ConvertTo-Json -Compress -InputObject @(Get-NetAdapter -Physical | "
        "Where-Object { $_.NdisPhysicalMedium -eq 9 -or $_.PhysicalMediaType -like '*802.11*' } | "
        "Select-Object Name, InterfaceDescription, InterfaceGuid, MacAddress, PermanentAddress, "
        "@{n='Status';e={[string]$_.Status}})"
    )
    data = json.loads(out or "[]")
    if isinstance(data, dict):
        data = data.get("value", [data])
    return data


def pick_adapter(name):
    adapters = get_wifi_adapters()
    if not adapters:
        raise RuntimeError("没有找到无线网卡。")
    if name:
        for a in adapters:
            if a["Name"].lower() == name.lower():
                return a
        raise RuntimeError(f"找不到无线网卡 {name}，可用：{', '.join(a['Name'] for a in adapters)}")
    if len(adapters) == 1:
        return adapters[0]
    up = [a for a in adapters if a["Status"] == "Up"]
    if len(up) == 1:
        return up[0]
    raise RuntimeError("有多块无线网卡，请用 --adapter 指定：" + ", ".join(a["Name"] for a in adapters))


def find_reg_path(guid):
    with winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, NIC_CLASS_KEY) as cls:
        i = 0
        while True:
            try:
                sub = winreg.EnumKey(cls, i)
            except OSError:
                break
            i += 1
            try:
                with winreg.OpenKey(cls, sub) as k:
                    if winreg.QueryValueEx(k, "NetCfgInstanceId")[0].lower() == guid.lower():
                        return NIC_CLASS_KEY + "\\" + sub
            except OSError:
                continue  # Properties 等子键没有权限或没有该值
    raise RuntimeError(f"注册表中找不到网卡 {guid} 对应的配置项")


def read_network_address(path):
    try:
        with winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, path) as k:
            return winreg.QueryValueEx(k, "NetworkAddress")[0] or None
    except FileNotFoundError:
        return None


def write_network_address(path, mac):
    """mac 为 None 时删除 NetworkAddress，网卡回到默认地址。"""
    with winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, path, 0, winreg.KEY_SET_VALUE) as k:
        if mac:
            winreg.SetValueEx(k, "NetworkAddress", 0, winreg.REG_SZ, mac)
        else:
            try:
                winreg.DeleteValue(k, "NetworkAddress")
            except FileNotFoundError:
                pass


def get_wlan_info(adapter_name):
    """解析 netsh wlan show interfaces，取当前 SSID 和配置文件名（兼容中英文系统）。"""
    _, out = run_cmd(["netsh", "wlan", "show", "interfaces"])
    info, current = {}, None
    for line in out.splitlines():
        m = re.match(r"^\s*([^:]+?)\s*:\s*(.*?)\s*$", line)
        if not m:
            continue
        key, val = m.groups()
        if key in ("Name", "名称"):
            current = val
        elif current and current.lower() == adapter_name.lower():
            if key == "SSID":
                info["ssid"] = val
            elif key in ("Profile", "配置文件"):
                info["profile"] = val
    if not info.get("ssid"):
        # Win11 新版本里 netsh wlan 需要“定位”权限；读不到时取网络名，仅用于显示
        # （可能带 " 2" 之类的编号，不一定等于 Wi-Fi 配置文件名，所以不拿来做 netsh connect）
        info["network"] = run_ps(f"(Get-NetConnectionProfile -InterfaceAlias {ps_quote(adapter_name)} "
                                 "-ErrorAction SilentlyContinue | Select-Object -First 1).Name", check=False)
    return info


def get_adapter_state(name):
    q = ps_quote(name)
    out = run_ps(
        f"$a = Get-NetAdapter -Name {q}; "
        f"$ip = @(Get-NetIPAddress -InterfaceAlias {q} -AddressFamily IPv4 -ErrorAction SilentlyContinue | "
        "Where-Object { $_.IPAddress -notlike '169.254.*' } | ForEach-Object { $_.IPAddress }); "
        "[pscustomobject]@{Status=[string]$a.Status; Mac=$a.MacAddress; IPs=($ip -join ',')} | "
        "ConvertTo-Json -Compress"
    )
    st = json.loads(out)
    st["IPs"] = [x for x in (st.get("IPs") or "").split(",") if x]
    return st


def restart_adapter(name):
    q = ps_quote(name)
    run_ps(f"Disable-NetAdapter -Name {q} -Confirm:$false; Start-Sleep -Seconds 3; "
           f"Enable-NetAdapter -Name {q} -Confirm:$false")


def wait_online(name, profile, timeout):
    """等网卡重新连上 Wi-Fi 并通过 DHCP 拿到地址。"""
    start, reconnect_sent, st = time.time(), False, {}
    while time.time() - start < timeout:
        try:
            st = get_adapter_state(name)
        except RuntimeError:
            st = {}
        if st.get("Status") == "Up" and st.get("IPs"):
            return st
        if profile and not reconnect_sent and time.time() - start > 15:
            print(f"  还没自动连上，执行 netsh wlan connect name={profile}")
            run_cmd(["netsh", "wlan", "connect", f"name={profile}", f"interface={name}"])
            reconnect_sent = True
        time.sleep(2)
    return st


# ------------------------------------------------------------------ 备份

def load_backups():
    try:
        with open(BACKUP_FILE, encoding="utf-8") as f:
            return json.load(f)
    except (FileNotFoundError, ValueError):
        return {}


def save_backup(adapter, reg_path, original_value):
    backups = load_backups()
    guid = adapter["InterfaceGuid"]
    if guid in backups:
        return  # 只保留第一次修改前的状态，避免把改过的 MAC 当成原始值
    backups[guid] = {
        "adapter": adapter["Name"],
        "reg_path": reg_path,
        "original_network_address": original_value,
        "mac_before": adapter["MacAddress"],
        "permanent_address": adapter["PermanentAddress"],
        "time": datetime.now().isoformat(timespec="seconds"),
    }
    with open(BACKUP_FILE, "w", encoding="utf-8") as f:
        json.dump(backups, f, ensure_ascii=False, indent=2)
    print(f"  原始配置已备份到 {BACKUP_FILE}")


# ------------------------------------------------------------------ 连通性测试

def report(ok, title, detail):
    print(f"  [{' OK ' if ok else 'FAIL'}] {title}：{detail}")


def ping(host, count=4):
    _, out = run_cmd(["ping", "-n", str(count), "-w", "1000", host])
    # 只认带 TTL= 的行，避免“无法访问目标主机”这类回复被当成成功；中英文系统通用
    replies = [line for line in out.splitlines() if "TTL=" in line.upper()]
    times = [int(t) for t in re.findall(r"[=<](\d+)\s*ms", "\n".join(replies), re.I)]
    avg = sum(times) / len(times) if times else None
    return len(replies), count, avg


def get_gateway(name):
    return run_ps(f"(Get-NetIPConfiguration -InterfaceAlias {ps_quote(name)}).IPv4DefaultGateway.NextHop "
                  "| Select-Object -First 1", check=False)


def connectivity_test(name, extra_targets, dns_host):
    print("\n== 网络连通性测试 ==")
    results = []

    targets = []
    gw = get_gateway(name)
    if gw:
        targets.append(("网关", gw))
    else:
        print("  [WARN] 没有获取到默认网关")
    targets += [("自定义", t) for t in extra_targets]
    targets += [("公网", t) for t in DEFAULT_PING_TARGETS]

    for label, host in targets:
        recv, total, avg = ping(host)
        detail = f"{recv}/{total} 回复" + (f"，平均 {avg:.0f} ms" if avg is not None else "")
        report(recv > 0, f"Ping {label} {host}", detail)
        results.append(recv > 0)

    try:
        ips = sorted({ai[4][0] for ai in socket.getaddrinfo(dns_host, None, socket.AF_INET)})
        report(True, f"DNS 解析 {dns_host}", ", ".join(ips[:3]))
        results.append(True)
        if any(ip.startswith(("198.18.", "198.19.")) for ip in ips):
            print("  [WARN] 解析结果在 198.18.0.0/15，疑似代理软件的 fake-ip（TUN 模式），"
                  "下面的 TCP 结果只代表到本机代理，建议测试时先关掉代理")
        t0 = time.time()
        try:
            with socket.create_connection((dns_host, 443), timeout=3):
                report(True, f"TCP {dns_host}:443", f"{(time.time() - t0) * 1000:.0f} ms")
                results.append(True)
        except OSError as e:
            report(False, f"TCP {dns_host}:443", str(e))
            results.append(False)
    except OSError as e:
        report(False, f"DNS 解析 {dns_host}", str(e))
        results.append(False)

    print(f"\n结果：{sum(results)}/{len(results)} 项通过")
    return all(results)


# ------------------------------------------------------------------ 主流程

def run(args):
    if args.list:
        for a in get_wifi_adapters():
            print(f"{a['Name']}  {a['InterfaceDescription']}  状态 {a['Status']}  "
                  f"当前 {fmt_mac(a['MacAddress'])}  出厂 {fmt_mac(a['PermanentAddress'])}")
        return True

    adapter = pick_adapter(args.adapter)
    name = adapter["Name"]
    if args.test_only:
        return connectivity_test(name, args.ping, args.dns_host)

    reg_path = find_reg_path(adapter["InterfaceGuid"])
    current_reg = read_network_address(reg_path)
    wlan = get_wlan_info(name)
    profile = args.profile or wlan.get("profile") or wlan.get("ssid")

    print(f"网卡       : {name} ({adapter['InterfaceDescription']})")
    print(f"注册表     : HKLM\\{reg_path}")
    print(f"出厂 MAC   : {fmt_mac(adapter['PermanentAddress'])}")
    print(f"当前 MAC   : {fmt_mac(adapter['MacAddress'])}")
    print(f"注册表设置 : {fmt_mac(current_reg) if current_reg else '(未设置)'}")
    print(f"当前 Wi-Fi : {wlan.get('ssid') or wlan.get('network') or '(未连接)'}，"
          f"修改后连接配置：{profile or '(由系统自动连接，可用 --profile 指定)'}")

    if args.restore:
        backup = load_backups().get(adapter["InterfaceGuid"])
        new_value = backup.get("original_network_address") if backup else None
        print(f"操作       : 恢复为 {fmt_mac(new_value) if new_value else '网卡默认地址（删除 NetworkAddress）'}")
    else:
        new_value = parse_mac(args.mac) if args.mac else random_wlan_mac()
        print(f"新 MAC     : {fmt_mac(new_value)}")
        if not current_reg and clean_mac(adapter["MacAddress"]) != clean_mac(adapter["PermanentAddress"]):
            print("[注意] 当前 MAC 与出厂 MAC 不同，但注册表没设置 NetworkAddress，"
                  "说明这个 Wi-Fi 开启了 Windows「随机硬件地址」，它可能覆盖本次设置。")

    if args.dry_run:
        print("\n--dry-run：未做任何修改。")
        return True

    if not args.restore:
        save_backup(adapter, reg_path, current_reg)
    print("\n[1/3] 写入注册表 NetworkAddress ...")
    write_network_address(reg_path, new_value)
    print("[2/3] 重启网卡（Wi-Fi 会短暂断开）...")
    restart_adapter(name)
    print(f"[3/3] 等待重新连接并获取 IP（最多 {args.timeout} 秒）...")
    st = wait_online(name, profile, args.timeout)
    actual = clean_mac(st.get("Mac"))
    print(f"  状态 {st.get('Status', '?')}  MAC {fmt_mac(actual)}  IP {', '.join(st.get('IPs') or []) or '无'}")

    if new_value and actual != new_value:
        print("[警告] 实际 MAC 与设置值不一致：驱动不支持修改，或被「随机硬件地址」覆盖。\n"
              "       可在 设置 → 网络和 Internet → WLAN → 管理已知网络 → 该网络 → 随机硬件地址 关闭后重试。")
    elif new_value:
        print("  MAC 修改已生效。")
    if not st.get("IPs"):
        print("[警告] 没有拿到有效的 IPv4 地址（未连上 Wi-Fi 或 DHCP 失败），继续做连通性测试。")

    return connectivity_test(name, args.ping, args.dns_host)


def parse_args():
    p = argparse.ArgumentParser(description="修改 Windows WLAN 网卡 MAC 地址并测试网络连通性")
    g = p.add_mutually_exclusive_group()
    g.add_argument("--mac", help="新 MAC，如 02:11:22:33:44:55；不填则随机生成")
    g.add_argument("--restore", action="store_true", help="恢复原来的 MAC")
    g.add_argument("--list", action="store_true", help="列出无线网卡")
    g.add_argument("--test-only", action="store_true", help="只做连通性测试")
    p.add_argument("--adapter", help="网卡名称，如 WLAN（默认自动选择）")
    p.add_argument("--profile", help="修改后要连接的 Wi-Fi 配置文件名（默认用当前连接的）")
    p.add_argument("--ping", action="append", default=[], metavar="IP",
                   help="额外 ping 的地址，可重复使用，如交换机管理 IP")
    p.add_argument("--dns-host", default=DEFAULT_DNS_HOST, help="DNS 解析和 TCP 测试使用的域名")
    p.add_argument("--timeout", type=int, default=60, help="等待重新连接的秒数（默认 60）")
    p.add_argument("--dry-run", action="store_true", help="只显示将要做什么，不实际修改")
    p.add_argument("--pause", action="store_true", help=argparse.SUPPRESS)
    return p.parse_args()


def main():
    if sys.platform != "win32":
        sys.exit("本脚本只支持 Windows。")
    sys.stdout.reconfigure(errors="replace")
    args = parse_args()

    if not (args.list or args.test_only or args.dry_run) and not is_admin():
        relaunch_as_admin()

    try:
        ok = run(args)
    except Exception as e:
        print(f"\n[错误] {e}")
        ok = False
    if args.pause:
        input("\n按回车键退出...")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
