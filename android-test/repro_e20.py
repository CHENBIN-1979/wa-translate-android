#!/usr/bin/env python3
# E20 假死锁复现（诊断专用，不改产品代码）。
# 关键：单独复现时"译"按钮要服务扫描后才挂出来 → 每步前 tap 一下屏幕造事件。
import subprocess, time, sys

ADB = "/home/chenbin/Android/platform-tools/adb"
HOST = "id.co.watranslate.testhost"
import urllib.request
MOCK = "http://127.0.0.1:8787"

def sh(*a):
    return subprocess.run([ADB, "-e"] + list(a), capture_output=True, text=True).stdout

def edtext():
    sh("shell", "uiautomator", "dump", "/sdcard/rr.xml")
    xml = sh("shell", "cat", "/sdcard/rr.xml")
    import re
    out = []
    for m in re.finditer(r'<node[^>]*>', xml):
        t = m.group(0)
        if 'class="android.widget.EditText"' in t:
            txt = re.search(r'text="([^"]*)"', t)
            v = txt.group(1) if txt else ''
            out.append(f'屏上EditText len={len(v)} head="{v[:24]}"')
    return "; ".join(out) or "(无EditText)"

def btnframe():
    import re
    out = sh("shell", "dumpsys", "window", "windows")
    seen = []
    for g in re.finditer(r'id\.co\.watranslate, frame=\[(\d+),(\d+),(\d+),(\d+)\]', out):
        l, t, r, b = map(int, g.groups())
        if (r-l) < 200 and (b-t) < 200:
            seen.append((l, t, r, b))
    return seen[0] if seen else None

def wait_btn(timeout=15):
    import re
    for _ in range(timeout):
        f = btnframe()
        if f: return f
        time.sleep(1)
    return None

def tapbtn():
    f = wait_btn()
    if not f:
        print("  !! 等不到译按钮"); return False
    cx, cy = (f[0]+f[2])//2, (f[1]+f[3])//2
    sh("shell", "input", "tap", str(cx), str(cy))
    return True

def svc_pid():
    return sh("shell", "pidof", "id.co.watranslate").strip()

def dumplog(n=12):
    L = sh("logcat", "-d", "-s", "wTranslate")
    lines = [x for x in L.splitlines() if any(k in x for k in
             ["compose start","写回","还原","拒写","restore","缓存","cache","输入框","冷却"])]
    for x in lines[-n:]:
        print("   ", x.split("wTranslate:")[-1].strip())

urllib.request.urlopen(MOCK + "/setecho?on=0").read()
urllib.request.urlopen(MOCK + "/setleak?on=0").read()

sh("shell", "am", "force-stop", HOST)
sh("shell", "am", "start", "-n", HOST + "/.TestHostActivity")
time.sleep(3)
sh("shell", "input", "keyevent", "111")   # 收遗留键盘
sh("shell", "am", "broadcast", "-a", "id.co.watranslate.SETINPUT", "--es", "text", "'库存已确认，明天可以装柜'")
time.sleep(2)
sh("shell", "logcat", "-c")

print("== 初始（期望中文12字）==", edtext(), "| 服务PID=", svc_pid())
print("--- C: 译（中文→印尼，写回✓） ---")
tapbtn(); time.sleep(4)
print("  结果:", edtext(), "| 服务PID=", svc_pid()); dumplog(6)
print("--- D: 再点（还原↩，期望回中文） ---")
tapbtn(); time.sleep(4)
print("  结果:", edtext(), "| 服务PID=", svc_pid()); dumplog(6)
print("--- E: 第三次点（期望 cache hit 中文→印尼 写回✓） ---")
tapbtn(); time.sleep(5)
print("  结果:", edtext(), "| 服务PID=", svc_pid()); dumplog(8)
