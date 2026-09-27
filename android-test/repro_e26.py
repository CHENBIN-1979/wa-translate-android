#!/usr/bin/env python3
"""E26 手动取证：长消息气泡 → tap → 服务 tap-copy 全链路逐步观察。
不跑全套 e2e，只复现 E26 一个用例，输出：候选、菜单节点、点击坐标、剪贴板读数、testhost handler 日志。"""
import subprocess, time, re, sys, urllib.request

ADB = "/home/chenbin/Android/platform-tools/adb"
HOST = "id.co.watranslate.testhost"
PKG = "id.co.watranslate"
MOCK = "http://127.0.0.1:8787"

def sh(*a):
    return subprocess.run([ADB, "-e"] + list(a), capture_output=True, text=True).stdout

def logs(tag="wTranslate"):
    return sh("logcat", "-d", "-s", tag)

def find_bounds(text):
    sh("shell", "uiautomator", "dump", "/sdcard/e26.xml")
    xml = sh("shell", "cat", "/sdcard/e26.xml")
    for m in re.finditer(r'<node[^>]*>', xml):
        t = m.group(0)
        tm = re.search(r'text="([^"]*)"', t)
        if tm and text in tm.group(1):
            b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', t)
            if b:
                l, tp, r, bo = map(int, b.groups())
                return (l + r) // 2, (tp + bo) // 2
    return None

urllib.request.urlopen(MOCK + "/setecho?on=0").read()
# 用 run-8 后的设备状态：包已装、SP 已配。清映射+杀服务重启，模拟 E26 起始
sh("shell", "rm", "-f", "/data/data/id.co.watranslate/shared_prefs/wtrans_fulltext.xml")
sh("shell", "kill", "$(pidof %s)" % PKG)
time.sleep(2)
sh("shell", "am", "force-stop", HOST)
sh("logcat", "-c")
sh("shell", "am", "start", "-n", HOST + "/.TestHostActivity")
time.sleep(2)
sh("shell", "am", "broadcast", "-a", "id.co.watranslate.LONGMSG")
time.sleep(1)

# 找气泡：截断覆盖层 text = 前80字
xy = None
for _ in range(15):
    xy = find_bounds("To Semua Mesin")
    if xy: break
    time.sleep(1)
print("气泡中心:", xy)
L = logs()
sc = re.findall(r"scan cands=\d+: .*", L)
print("候选日志尾3:", sc[-3:] if sc else "(无)")
print("服务是否绑定:", "service connected" in L)

sh("shell", "input", "tap", str(xy[0]), str(xy[1]))
for i in range(20):
    time.sleep(1)
    L = logs()
    if "tap-copy ✓" in L or "tap-copy 放弃" in L:
        break
print("--- 服务 wTranslate tap-copy 链路 ---")
for ln in L.splitlines():
    if any(k in ln for k in ["tap-copy", "菜单", "复制", "手势", "gesture", "inject", "row ", "直发", "single"]):
        print("  ", ln.split("wTranslate:")[-1].strip())
print("--- testhost 侧（菜单 handler）---")
TH = logs("wTranslateHost")
print(TH.strip() if TH.strip() else "(无 wTranslateHost 日志——注意：若 testhost 没打该 tag 说明 handler 未执行或版本旧)")
CLIP = sh("shell", "cmd", "clipboard", "get")
print("--- 设备剪贴板现状（cmd 可读部分）---")
print(CLIP[:300] if CLIP.strip() else "(cmd clipboard get 不可用)")
