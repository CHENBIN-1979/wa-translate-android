#!/usr/bin/env python3
"""E28（v0.3.8 P2 专项回归）：点击模式下「新消息推入→已译气泡滚出视口→滚回来自动重投」。

真机取证（9/25 问题②）：新消息→列表滚动→已译气泡滚出→stale 撤行→点击模式不重投→
用户必须重新点击。v0.3.8：tappedKeys 记忆表 + 屏上无行时自动重投（缓存命中零 API）。

场景（testhost 广播驱动，模拟新消息推入）：
  1. SEED（列表回顶部）→ 点「Selamat pagi Pak」→ 译文行出现 + row result。
  2. APPEND 两条长消息 → testhost 自动 fullScroll(FOCUS_DOWN) 滚到底
     （= 真机新消息到达滚底）→ 第一条气泡滚出视口 → 断言 overlay 行被撤。
  3. 反向 swipe 回顶部 → 断言「滚回重投: Selamat pagi」+ 行重建 + mock hits 零增量（缓存）。
  4. 点气泡关闭（tap 关闭）→ 再 APPEND+swipe 出去/回来 → 断言不再重投（closedKeys 语义）。
"""
import subprocess, sys, time, re, json, urllib.request

ADB = "/home/chenbin/Android/platform-tools/adb"
PKG_APP = "id.co.watranslate"
HOST_PKG = "id.co.watranslate.testhost"
SVC = PKG_APP + "/.WaTranslateService"
MOCK = "http://127.0.0.1:8787"

def sh(*a):
    return subprocess.run([ADB, "-e"] + list(a), capture_output=True, text=True).stdout

def logs():
    return sh("logcat", "-d", "-s", "wTranslate")

def mock_hits(reset=False):
    return json.load(urllib.request.urlopen(MOCK + "/hits" + ("?reset=1" if reset else "")))["n"]

def own_frames():
    out = sh("shell", "dumpsys", "window", "windows")
    seen = set()
    for g in re.findall(r"id\.co\.watranslate, frame=\[(\d+),(\d+),(\d+),(\d+)\]", out):
        seen.add(tuple(int(v) for v in g))
    return list(seen)

def row_frame():
    rows_ = [f for f in own_frames() if (f[2] - f[0]) >= 200]
    return rows_[0] if rows_ else None

def find_bounds(text):
    sh("shell", "uiautomator", "dump", "/sdcard/e28.xml")
    xml = sh("shell", "cat", "/sdcard/e28.xml")
    for m in re.finditer(r'<node[^>]*>', xml):
        tag = m.group(0)
        t = re.search(r'text="([^"]*)"', tag)
        if not t or text not in t.group(1): continue
        if ('class="android.widget.TextView"' not in tag and
            'class="android.widget.Button"' not in tag): continue
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', tag)
        if b:
            l, tp, r, bo = map(int, b.groups())
            return (l + r) // 2, (tp + bo) // 2
    return None

def bcast(action, **extras):
    cmd = ["shell", "am", "broadcast", "-a", action]
    for k, v in extras.items():
        cmd += ["--es", k, "'" + v + "'"] if isinstance(v, str) else ["--ez", k, "true"]
    return sh(*cmd)

def wait_for(pred, timeout=40, step=2):
    t0 = time.time()
    while time.time() - t0 < timeout:
        try:
            if pred(): return True
        except Exception: pass
        time.sleep(step)
    return False

def main():
    # ---- 点击模式 SP + 缓存清零 + 服务重启（e2e reseed 配方简化版） ----
    sp = ('<?xml version=\'1\' encoding=\'utf-8\' standalone=\'yes\' ?>\n<map>\n'
          '<string name="apiBase">http://10.0.2.2:8787/v1</string>\n'
          '<string name="apiKey">test-key</string>\n'
          '<string name="model">mock-model</string>\n'
          '<string name="temperature">0.2</string>\n'
          '<boolean name="enabled" value="true" />\n'
          '<boolean name="auto" value="false" />\n'
          '<string name="dict">mixing = 轧轮机</string>\n'
          '</map>')
    open("/tmp/wtrans_sp_e28.xml", "w").write(sp)
    sh("push", "/tmp/wtrans_sp_e28.xml", "/data/local/tmp/wtrans_sp_e28.xml")
    sh("shell", "mkdir -p /data/data/" + PKG_APP + "/shared_prefs && cp /data/local/tmp/wtrans_sp_e28.xml /data/data/" + PKG_APP + "/shared_prefs/wtrans.xml && chown $(stat -c '%U:%G' /data/data/" + PKG_APP + ") /data/data/" + PKG_APP + "/shared_prefs/wtrans.xml && chmod 660 /data/data/" + PKG_APP + "/shared_prefs/wtrans.xml")
    sh("shell", "kill $(pidof " + PKG_APP + ") 2>/dev/null; true")
    time.sleep(2)
    sh("shell", "rm -f /data/data/" + PKG_APP + "/shared_prefs/wtrans_cache_v5.xml /data/data/" + PKG_APP + "/shared_prefs/wtrans_cache_v4.xml /data/data/" + PKG_APP + "/shared_prefs/wtrans_cache_v3.xml /data/data/" + PKG_APP + "/shared_prefs/wtrans_cache_v2.xml /data/data/" + PKG_APP + "/shared_prefs/wtrans_cache.xml /data/data/" + PKG_APP + "/shared_prefs/wtrans_fulltext.xml")
    sh("shell", "settings", "put", "secure", "enabled_accessibility_services", SVC)
    sh("shell", "settings", "put", "secure", "accessibility_enabled", "1")
    sh("shell", "am", "force-stop", HOST_PKG)
    sh("shell", "am", "start", "-n", HOST_PKG + "/.TestHostActivity")
    time.sleep(4)
    bcast("id.co.watranslate.SEED")   # 默认 7 行（3 条可译），列表在顶部
    time.sleep(3)
    assert wait_for(lambda: "scan cands=" in logs(), timeout=30), "E28 FAIL: 服务未扫描"
    sh("shell", "logcat", "-c")

    # ---- 步骤 1：点第一条对方气泡 → 出译文行 ----
    xy = None
    t0 = time.time()
    while time.time() - t0 < 30 and not xy:
        xy = find_bounds("Selamat pagi Pak"); time.sleep(2)
    assert xy, "E28 FAIL: 找不到第一条气泡"
    sh("shell", "input", "tap", str(xy[0]), str(xy[1]))
    assert wait_for(lambda: "tap 首译: Selamat pagi" in logs(), timeout=25), "E28-1 FAIL: 点击未出译\n" + logs()[-800:]
    assert wait_for(lambda: row_frame() is not None, timeout=25), "E28-1 FAIL: overlay 行未出现"
    assert wait_for(lambda: "row result: Selamat pagi" in logs(), timeout=40), "E28-1 FAIL: 无 row result"
    hits1 = mock_hits()
    print("E28-1 点击出译 通过 (hits=%d)" % hits1)

    # ---- 步骤 2：树重建（GROUPROW 替换整棵树 = 等价于真机气泡滚出虚拟化列表被摘节点）→ 行被撤 ----
    # testhost 的 ScrollView 非虚拟化：真滚出视口的节点仍留在无障碍树里 → liveKeys 仍含
    # 该 key → 不撤是正确行为。真机 WhatsApp ListView 虚拟化=滚出即摘出树。用 GROUPROW
    # （msgs 整体替换=旧 key 从树消失）走同一条 stale 撤行代码路径。
    bcast("id.co.watranslate.GROUPROW")
    gone = wait_for(lambda: row_frame() is None, timeout=20, step=1)
    assert gone, "E28-2 FAIL: 旧 key 消失后译文行未撤（stale 路径失效）"
    time.sleep(2)
    assert "滚回重投" not in logs(), "E28-2 FAIL: 撤行期间误重投"
    print("E28-2 旧 key 消失→撤行 通过")

    # ---- 步骤 3：树恢复（SEED）→ 滚回重投 + 零 API ----
    hits_before = mock_hits()
    bcast("id.co.watranslate.SEED")
    ok = wait_for(lambda: "滚回重投: Selamat pagi" in logs(), timeout=30)
    assert ok, "E28-3 FAIL: key 重现后未自动重建译文行\n" + logs()[-1500:]
    assert wait_for(lambda: row_frame() is not None, timeout=20), "E28-3 FAIL: 重投后 overlay 行未出现"
    time.sleep(2)
    assert mock_hits() == hits_before, "E28-3 FAIL: 重投新增 API 调用（应缓存命中）: %d→%d" % (hits_before, mock_hits())
    print("E28-3 滚回重投+零API 通过")

    # ---- 步骤 4：手动关闭 → 再消失/重现 → 不再重投 ----
    # 绝不 dump 找坐标（e2e E9 实证：uiautomator dump 触发服务重绑，rows/tappedKeys/
    # closedKeys 内存态清零 → 二次点击会变成「首译」而不是「关闭」）。SEED 后布局与
    # 步骤 1 完全一致，直接复用 xy。
    sh("shell", "input", "tap", str(xy[0]), str(xy[1]))
    assert wait_for(lambda: "close row: Selamat pagi" in logs(), timeout=25), "E28-4 FAIL: 二次点击未关闭"
    sh("shell", "logcat", "-c")
    bcast("id.co.watranslate.GROUPROW")
    time.sleep(4)
    bcast("id.co.watranslate.SEED")
    time.sleep(8)
    tail = logs()
    assert "滚回重投: Selamat pagi" not in tail, "E28-4 FAIL: 手动关闭后仍被滚回重投（closedKeys 语义破坏）"
    assert row_frame() is None, "E28-4 FAIL: 关闭后行复活（overlay 窗口重现）"
    print("E28-4 关闭后不复活 通过")
    # 收尾复原列表
    bcast("id.co.watranslate.SEED")
    print("E28 ALL PASS")

if __name__ == "__main__":
    try:
        main()
    except AssertionError as e:
        print("E28 FAIL:", e)
        subprocess.run([ADB, "-e", "exec-out", "screencap", "-p"], stdout=open("/tmp/wa_e28_fail.png", "wb"))
        print("截图: /tmp/wa_e28_fail.png")
        sys.exit(1)
