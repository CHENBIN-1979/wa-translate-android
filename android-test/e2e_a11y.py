#!/usr/bin/env python3
"""e2e_a11y.py v2 — 模拟器验证 WA 翻译悬浮服务（假 WhatsApp 壳）。

关键决策：TYPE_ACCESSIBILITY_OVERLAY 悬浮窗不进 uiautomator dump（已实测），
所以一切断言走【服务 logcat 真值】+【截图证据】：
  E1 3 条对方印尼语消息 → inject row ×3（水印/⟳ 视觉在截图中确认，vision 自检另做）
  E2 噪声零泄漏：inject 的 src 不含 日期/加密提示/已删除/title/按钮
  E3 自己消息不进请求（mock_bodies.log）
  E4 词典强制：Baiklah… → 轧轮机（row result 行）
  E5 回显防护：全新文本 echo on → row fail（且无原文回显注入）
  E6 再点已译气泡=强制重译（v0.1.3 ⟳ 图标已删）：tap 气泡中心 → doRefresh → row result 再现
  E7 滚动后不堆积：inject 总数不变
Phase B（点击翻译模式 auto=false，v0.1.2）：
  E8 不点=零行；点对方气泡→仅该行出现且翻译正确；点自己消息/未点的不译
  E9 再点同一条→关闭译文行（close row；关闭不复活；三点重开）；E6 点行文字→重译
"""
import subprocess, sys, time, json, re, os, urllib.request

ADB = "/home/chenbin/Android/platform-tools/adb"
PKG_APP = "id.co.watranslate"
SVC = PKG_APP + "/.WaTranslateService"
HOST_PKG = "id.co.watranslate.testhost"
MOCK = "http://127.0.0.1:8787"

def sh(*a):
    return subprocess.run([ADB, "-e"] + list(a), capture_output=True, text=True).stdout

def logs():
    return sh("logcat", "-d", "-s", "wTranslate")

def injects():
    return [(int(m.group(1)), int(m.group(2)), m.group(3))
            for m in re.finditer(r"inject row @(\d+),(\d+): (.*)", logs())]

def results():
    return [(m.group(1), m.group(2)) for m in re.finditer(r"row result: (.*?) -> (.*)", logs())]

def fails():
    return [m.group(1) for m in re.finditer(r"row fail: (.*?) echo-or-err=", logs())]

def wait_for(pred, timeout=60, step=2):
    t0 = time.time()
    while time.time() - t0 < timeout:
        try:
            if pred(): return True
        except Exception:
            pass
        time.sleep(step)
    return False

def _own_frames():
    """本包 overlay 窗口矩形列表；dumpsys window 只读、不引发服务重绑（uiautomator dump 会）。"""
    out = sh("shell", "dumpsys", "window", "windows")
    seen = set()
    for g in re.findall(r"id\.co\.watranslate, frame=\[(\d+),(\d+),(\d+),(\d+)\]", out):
        seen.add(tuple(int(v) for v in g))
    return list(seen)

def row_frame():
    """译文行窗口（宽条）；v0.1.9 起屏上可能同时有输入框「译」按钮（~105px 方块），按形状区分。"""
    rows_ = [f for f in _own_frames() if (f[2] - f[0]) >= 200]
    return rows_[0] if rows_ else None


def mock_hits(reset=False):
    return json.load(urllib.request.urlopen(MOCK + "/hits" + ("?reset=1" if reset else "")))["n"]

def set_echo(on):
    urllib.request.urlopen(MOCK + "/setecho?on=" + ("1" if on else "0")).read()

def leak(on):  # v0.2.5 E20：mock 思考泄漏开关（复刻真机 MiniMax-M2.7 无法关思考）
    urllib.request.urlopen(MOCK + "/setleak?on=" + ("1" if on else "0")).read()

def bcast(action, **extras):
    cmd = ["shell", "am", "broadcast", "-a", action]
    for k, v in extras.items():
        # adb -e shell 会把参数 join 成字符串后【在设备 shell 再解析一次】——
        # 带空格的值必须自己带设备侧引号（2026-09-07 实证：否则被切分）
        cmd += ["--es", k, "'" + v + "'"] if isinstance(v, str) else ["--ez", k, "true"]
    return sh(*cmd)

def find_bounds(text, clickable_only=True):
    """uiautomator dump 抓含指定文本节点的 bounds 中心点（testhost 原生视图在 dump 里；overlay 不在）。
    节点属性序：text=... clickable=... bounds="[l,t][r,b]"（2026-09-07 实证 bounds 格式）。
    clickable_only：跳过操作区 EditText（它预填了与消息相同的文本，2026-09-07 E8 假阴性根因）"""
    sh("shell", "uiautomator", "dump", "/sdcard/e8.xml")
    xml = sh("shell", "cat", "/sdcard/e8.xml")
    for m in re.finditer(r'<node[^>]*>', xml):
        tag = m.group(0)
        t = re.search(r'text="([^"]*)"', tag)
        if not t or text not in t.group(1):
            continue
        if clickable_only:
            # EditText 也 clickable 且预填同文本（2026-09-07 dump 实证）→ 限定 TextView/Button
            if ('class="android.widget.TextView"' not in tag and
                'class="android.widget.Button"' not in tag):
                continue
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', tag)
        if b:
            l, tp, r, bo = map(int, b.groups())
            return (l + r) // 2, (tp + bo) // 2
    return None

def tap_text(text, timeout=20):
    xy = None
    t0 = time.time()
    while time.time() - t0 < timeout:
        xy = find_bounds(text)
        if xy: break
        time.sleep(2)
    assert xy, "找不到含文本节点: " + text
    sh("shell", "input", "tap", str(xy[0]), str(xy[1]))
    return xy

def main():
    # ---- 配置注入 + 服务重绑（配方：SP→delete→2s→put→install -r 强制重绑）----
    sp = ('<?xml version=\'1\' encoding=\'utf-8\' standalone=\'yes\' ?>\n<map>\n'
          '<string name="apiBase">http://10.0.2.2:8787/v1</string>\n'
          '<string name="apiKey">test-key</string>\n'
          '<string name="model">mock-model</string>\n'
          '<string name="temperature">0.2</string>\n'
          '<boolean name="enabled" value="true" />\n'
          '<boolean name="auto" value="true" />\n'
          '<string name="dict">mixing = 轧轮机\nmixer = 混合桶\nemboss = 压花轮</string>\n'
          '</map>')
    open("/tmp/wtrans_sp.xml", "w").write(sp)
    sh("push", "/tmp/wtrans_sp.xml", "/data/local/tmp/wtrans_sp.xml")
    # release 包不可 run-as（非 debuggable）——模拟器已 adb root，直接 root 写 + stat 继承属主
    # （toybox chown 无 --reference，必须 stat -c '%U:%G'，2026-09-07 实证）
    sh("shell", f"mkdir -p /data/data/{PKG_APP}/shared_prefs && cp /data/local/tmp/wtrans_sp.xml /data/data/{PKG_APP}/shared_prefs/wtrans.xml && chown $(stat -c '%U:%G' /data/data/{PKG_APP}) /data/data/{PKG_APP}/shared_prefs/wtrans.xml && chmod 660 /data/data/{PKG_APP}/shared_prefs/wtrans.xml")
    # v0.1.7：翻译缓存落盘 wtrans_cache_v2.xml —— E5 要求「全新文本」，必须连它一起清（同旧版杀进程清零语义）
    sh("shell", f"rm -f /data/data/{PKG_APP}/shared_prefs/wtrans_cache_v5.xml /data/data/id.co.watranslate/shared_prefs/wtrans_cache_v4.xml /data/data/id.co.watranslate/shared_prefs/wtrans_cache_v3.xml /data/data/id.co.watranslate/shared_prefs/wtrans_cache_v2.xml /data/data/{PKG_APP}/shared_prefs/wtrans_cache.xml")
    apk = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)),
                        "../app/build/outputs/apk/release/app-release.apk"))
    assert os.path.exists(apk), "APK 不存在: " + apk
    _here = os.path.dirname(os.path.abspath(__file__))
    host_apk = os.path.abspath(os.path.join(_here, "../testhost/build/outputs/apk/debug/testhost-debug.apk"))
    assert os.path.exists(host_apk), "testhost APK 不存在: " + host_apk
    set_echo(False)
    # v0.2.5 run-13 取证：上一轮 e2e 被外部杀死时可能停在 E20 的 leak(1) 之后、leak(0)
    # 之前——mock 全局开关粘住 on，E4 的三闸门把一切判成思考泄漏。setup 一律复位所有开关。
    for sw in ("setechoonly", "setfail", "setbadlen", "setreject400", "setleak"):
        urllib.request.urlopen(MOCK + "/" + sw + "?on=0").read()

    # ---- 绑定验证循环：僵尸绑定（connected 但 0 事件）用 install -r 重绑自愈 ----
    # testhost 常驻设备：E8 依赖气泡 isClickable（v0.1.2），必须每次确保最新（失败也必须可见）
    irh = subprocess.run([ADB, "-e", "install", "-r", host_apk], capture_output=True, text=True)
    assert "Success" in irh.stdout + irh.stderr, "testhost install 失败: " + (irh.stdout+irh.stderr).strip()[:120]

    def reseed_trigger():
        """完整配方：杀服务进程（rows 清零，防上一轮遗留行被本轮误计，v0.1.4 E1 竞态实证
        2026-09-07：半布局窗口的巨型锚点遗留行混进统计）→ 清 logcat → testhost 冷启动后
        再 SEED（窗口已 settled，扫描不再抓到跨屏假候选）→ install -r 强制重绑 → 全新扫描。
        v0.2.4 E17 取证：翻译缓存 rm 必须在杀进程【之后】——上一轮 E17 学习的王小明哨兵在
        旧进程 SP 内存快照里，rm 早于 kill 会被死亡回写/重启加载复活（cached 假命中）。"""
        sh("shell", "kill $(pidof " + PKG_APP + ") 2>/dev/null; true")
        time.sleep(2)   # 等进程真正消亡，防止内存 SP 回写
        sh("shell", "rm -f /data/data/id.co.watranslate/shared_prefs/wtrans_cache_v5.xml /data/data/id.co.watranslate/shared_prefs/wtrans_cache_v4.xml /data/data/id.co.watranslate/shared_prefs/wtrans_cache_v3.xml /data/data/id.co.watranslate/shared_prefs/wtrans_cache_v2.xml /data/data/id.co.watranslate/shared_prefs/wtrans_cache.xml /data/data/id.co.watranslate/shared_prefs/wtrans_fulltext.xml")
        sh("shell", "am", "force-stop", HOST_PKG)
        sh("logcat", "-c")
        sh("shell", "am", "start", "-n", HOST_PKG + "/.TestHostActivity")
        time.sleep(2)
        bcast("id.co.watranslate.SEED")   # 重置为默认 3 条（并持久化 + 自动滚底）
        time.sleep(1)
        sh("shell", "am", "force-stop", HOST_PKG)
        sh("logcat", "-c")
        # rows 去重集清零 = 杀服务进程（配方开头）。绝不可用 am force-stop PKG_APP：
        # 它会把 enabled_accessibility_services 整个清空（2026-09-07 实证的回归元凶）。
        # root kill -> AMS 检测 binder 死亡自动重连，settings 保持不动。
        ir = subprocess.run([ADB, "-e", "install", "-r", apk], capture_output=True, text=True)
        assert "Success" in ir.stdout + ir.stderr, "install -r 失败: " + (ir.stdout + ir.stderr).strip()[:400]  # 兜底重绑；失败必须可见
        time.sleep(2)
        sh("shell", "am", "start", "-n", HOST_PKG + "/.TestHostActivity")
        time.sleep(7)
        return injects()

    inj = []
    for attempt in range(3):
        # put（覆盖式，不 delete——delete 会杀掉活绑定）+ 等系统 rebind；
        # 进程没起就 host install -r（package-replaced = 僵尸绑定唯一解药），再等
        sh("shell", "settings", "put", "secure", "enabled_accessibility_services", SVC)
        sh("shell", "settings", "put", "secure", "accessibility_enabled", "1")
        time.sleep(3)
        if not sh("shell", "pidof", PKG_APP).strip():
            subprocess.run([ADB, "-e", "install", "-r", apk], capture_output=True)
            time.sleep(3)
        assert wait_for(lambda: bool(sh("shell", "pidof", PKG_APP).strip()), timeout=20), "服务进程未启动"
        inj = reseed_trigger()
        if len(inj) < 3:
            lg = logs()
            idx = lg.find("scan cands")
            print("RAWDUMP>>>\n" + lg[max(0,idx-200):idx+500] + "\n<<<RAWDUMP")
        if len(inj) >= 3:
            break
        lg = logs()
        print("DBG 第%d轮 inject=%d events=%d connected=%d :: %s" % (
            attempt + 1, len(inj), lg.count("event pkg="),
            lg.count("service connected"), lg.strip().splitlines()[-1][:100] if lg.strip() else "-"))
        # 可能只是慢：再等 15s 看会不会迟到
        if wait_for(lambda: len(injects()) >= 3, timeout=15):
            inj = injects(); break
    W, H = 1080, 2340

    print("E1 inject rows:", len(inj))
    for x, y, t in inj: print("   ", x, y, t[:36])
    assert len(inj) == 3, f"E1 FAIL: {inj}"
    srcs = [t for _, _, t in inj]
    print("E1 对方消息浮出译文行×3 通过")

    for noise in ["3/9/2026", "extremo", "这条消息已删除", "WA测试壳", "我发的", "追加", "清空"]:
        assert all(noise not in t for t in srcs), f"E2 FAIL: 噪声泄漏 {noise}"
    print("E2 噪声零泄漏 通过")

    body_log = open("/tmp/wt-test/mock_bodies.log").read()[-6000:] if os.path.exists("/tmp/wt-test/mock_bodies.log") else ""
    assert "你好，明天上午开会" not in body_log, "E3 FAIL: own 消息被送去翻译"
    print("E3 自己消息不进请求 通过")

    ok = wait_for(lambda: any("Baiklah" in r and "轧轮机" in o for r, o in results()), timeout=45)
    print("E4 row results:", results())
    assert ok, "E4 FAIL: 无 Baiklah→轧轮机"
    print("E4 词典强制 轧轮机 通过")

    # E5 回显防护（v0.2.6 路由重构：auto 下 N=1 一律直发 single——P1；
    # single 链两轮全回显 → KEEP 灰显落缓存——P2 共享重试链）
    # E5  = echo 全开：直发 single 回显→【重译】重试仍回显→灰显〔保留原文〕+落缓存+零红⚠
    # E5b = P1 效率断言：该文本全程只有 single 请求、绝无 batch（省一次注定被思考模型
    #       无视数组契约而白烧的 4-5s 请求 = 真机 39/134 次浪费的修复）
    def set_echo_only(on):
        urllib.request.urlopen(MOCK + "/setechoonly?on=" + ("1" if on else "0")).read()
    sh("shell", "logcat", "-c")   # E5b 路由计数从干净窗口开始（E1/E4 的 batch 不计入）
    # echo_only 保持关：single 路径【重译】轮也回显 → 验证两轮回显的 KEEP 终态
    set_echo(True)
    bcast("id.co.watranslate.APPEND", text="Saya akan lembur malam ini bersama tim")
    time.sleep(1)
    ok = wait_for(lambda: any("Saya akan lembur" in t for _, _, t in injects()), timeout=20)
    if not ok:
        sh("shell", "am", "force-stop", HOST_PKG)
        sh("shell", "am", "start", "-n", HOST_PKG + "/.TestHostActivity")
        time.sleep(3)
    ok = wait_for(lambda: "row keep-as-is (cached): Saya akan lembur" in logs(), timeout=45)
    print("E5 fails:", fails())
    assert ok, "E5 FAIL: single 链两轮回显未走灰显保留（无 row keep-as-is 日志）"
    assert "回显强化重试(1条)" in logs(), "E5 FAIL: 未走 v0.2.4 强化重试"
    assert "回显保留(重试仍回显)" in logs(), "E5 FAIL: 无 回显保留 明细日志"
    assert not any("Saya akan lembur" in f for f in fails()), "E5 FAIL: KEEP 灰显不应出红⚠ row fail"
    set_echo_only(False); set_echo(False)
    # E5b P1 路由断言（本窗口）：直发 single 监控行存在、traffic 全是 single、零 batch
    win = logs()
    assert "直发single(1条)" in win, "E5b FAIL: 无 直发single(1条) 监控行（P1 未生效）"
    n_batch_e5 = len([l for l in win.splitlines() if "api→ mock-model batch" in l])
    n_single_e5 = len([l for l in win.splitlines() if "api→ mock-model single" in l])
    print("E5b 路由计数(本窗口): batch=%d single=%d" % (n_batch_e5, n_single_e5))
    assert n_single_e5 >= 2, "E5b FAIL: lembur 未走直发 single（期望≥2 次：首轮+重试）"
    assert n_batch_e5 == 0, "E5b FAIL: v0.2.6 P1 后 lembur 窗口不应有任何 batch 请求"
    print("E5+E5b single 直发+两轮回显 KEEP 灰显 通过")

    # E6（v0.1.8 定案 / v0.1.9 修多窗口定位）：点 Saya lembur 那行的文字 = 强制重译
    # 屏上同时有多个译文行+「译」按钮 → 目标行 y 从 injects() 日志拿（x,y,text），再按 y 匹配窗口
    n_inj_e6 = len(injects())
    def saya_row_tap():
        rows_log = [(x, y, t) for x, y, t in injects() if "Saya akan lembur" in t]
        if not rows_log: return None
        ty = rows_log[-1][1]   # inject row @x,y 的 y = 行窗口左上角 y（同源实测一致）
        cands = [f for f in _own_frames() if (f[2]-f[0]) >= 200]
        if not cands: return None
        best = min(cands, key=lambda f: abs(f[1] - ty))   # v0.1.9 修：多行共存必须取最近者（<160 容差会撞车）
        if abs(best[1] - ty) > 60: return None
        return ((best[0]+best[2])//2, (best[1]+best[3])//2)
    hit = False
    for rnd in range(4):
        xy6 = saya_row_tap()
        if not xy6:
            sh("shell", "input", "swipe", "540", "1400", "540", "1200")
            time.sleep(3)
            continue
        sh("shell", "input", "tap", str(xy6[0]), str(xy6[1]))
        time.sleep(2)
        if "点行重译" in logs() or "doRefresh" in logs():
            hit = True
            break
        sh("shell", "input", "swipe", "540", "1400", "540", "1200")
        time.sleep(3)
    assert hit, "E6 FAIL: 点行文字未触发重译（无 点行重译/doRefresh 日志）"
    # E5 期间该行只有 "row fail"；refresh 成功才有新 "row result"
    ok = wait_for(lambda: "row result: Saya akan lembur" in logs(), timeout=45)
    assert ok, "E6 FAIL: doRefresh 后无新 row result"
    assert len(injects()) == n_inj_e6, "E6 FAIL: 点行重译不应新增/删除行"
    print("E6 点译文行→强制重译（行数不变）通过")

    # E7 滚动不堆积（input swipe = 原生注入，不经 uiautomator）
    n_before = len(injects())
    for _ in range(2):
        sh("shell", "input", "swipe", "540", "1400", "540", "800")
        time.sleep(2.5)
    n_after = len(injects())
    assert n_after == n_before, f"E7 FAIL: 滚动后 inject 从 {n_before} 变 {n_after}"
    print("E7 滚动行数稳定 通过")

    # ================= Phase B：点击翻译模式（v0.1.2 默认形态） =================
    sp_b = sp.replace('<boolean name="auto" value="true" />', '<boolean name="auto" value="false" />')
    open("/tmp/wtrans_sp.xml", "w").write(sp_b)
    sh("push", "/tmp/wtrans_sp.xml", "/data/local/tmp/wtrans_sp.xml")
    sh("shell", f"cp /data/local/tmp/wtrans_sp.xml /data/data/{PKG_APP}/shared_prefs/wtrans.xml && chmod 660 /data/data/{PKG_APP}/shared_prefs/wtrans.xml")
    inj_b = reseed_trigger()  # 内部已 kill 服务进程重读 SP + 全新 SEED 窗口
    print("B inject rows (应=0，不点不译):", len(inj_b))
    assert len(inj_b) == 0, f"E8 FAIL: 点击模式下自动出行了 {inj_b}"
    print("E8a 不点=零行 通过")

    # 【tap 前把所有坐标一次取齐】uiautomator dump 会重绑无障碍服务（rows 集合清零，
    # 11:49 日志实证 service connected）——tap 序列中间绝不能再 dump，否则 toggle 状态被清。
    xy_peer = None; xy_own = None; xy_peer2 = None
    t0 = time.time()
    while time.time() - t0 < 30 and not (xy_peer and xy_own and xy_peer2):
        xy_peer = xy_peer or find_bounds("Selamat pagi Pak")
        xy_own = xy_own or find_bounds("你好，明天上午开会")
        xy_peer2 = xy_peer2 or find_bounds("Baiklah, nanti")
        time.sleep(2)
    assert xy_peer and xy_own and xy_peer2, f"取坐标失败 peer={xy_peer} own={xy_own} peer2={xy_peer2}"
    time.sleep(3)  # 最后一次 dump 引发的重绑稳定

    sh("shell", "input", "tap", str(xy_peer[0]), str(xy_peer[1]))
    ok = wait_for(lambda: any("Selamat pagi" in t for _, _, t in injects()), timeout=25)
    n_peer = len([1 for _, _, t in injects() if "Selamat pagi" in t])
    assert ok and n_peer == 1, "E8 FAIL: 点击对方气泡未出唯一译文行"
    ok = wait_for(lambda: any("Selamat pagi" in r for r, _ in results()), timeout=45)
    print("E8b 点对方气泡→唯一行+翻译 通过")

    # 点自己消息：pick 已滤 own → miss，不新增行
    before = len(injects())
    sh("shell", "input", "tap", str(xy_own[0]), str(xy_own[1]))
    time.sleep(4)
    assert len(injects()) == before, "E8 FAIL: 点自己消息出了译文行"
    print("E8c 点自己消息=无反应 通过（未点消息全程零行）")

    # E9（v0.1.8 用户定案）：再点同一气泡 = 关闭译文行；关闭后不自动弹回；第三次点重新出译文
    n_inj = len(injects())
    sh("shell", "input", "tap", str(xy_peer[0]), str(xy_peer[1]))
    ok = wait_for(lambda: "close row: Selamat pagi" in logs(), timeout=25)
    assert ok, "E9 FAIL: 二次点击未触发行关闭（无 close row 日志）"
    assert row_frame() is None, "E9 FAIL: 关闭后 overlay 窗口仍在"
    time.sleep(4)
    assert "自动首译: Selamat pagi" not in logs(), "E9 FAIL: 关闭后被自动模式复活"
    sh("shell", "input", "tap", str(xy_peer[0]), str(xy_peer[1]))
    ok = wait_for(lambda: len([1 for _, _, t in injects() if "Selamat pagi" in t]) > n_inj, timeout=25)
    assert ok, "E9 FAIL: 第三次点击未重新出译文行"
    print("E9 再点关闭→不复活→三点重开 通过")

    # E10 点另一条气泡 = 单行互斥（v0.1.4 用户定案：一次只译一条，旧行自动消失）
    sh("shell", "input", "tap", str(xy_peer2[0]), str(xy_peer2[1]))
    ok = wait_for(lambda: any("Baiklah" in t for _, _, t in injects()), timeout=25)
    assert ok, "E10 FAIL: 点第二条气泡未出译文行"
    assert "single-row: drop" in logs(), "E10 FAIL: 旧行未自动消失（无 single-row 日志）"
    print("E10 点新气泡→旧行自动消失（单行互斥）通过")

    # ================= E11（v0.1.9）：聊天输入框「译」按钮闭环 =================
    def compose_frame():
        # 「译」按钮 = 小方块（40dp≈105px）；译文行 = 宽条，两者按形状区分
        btn = [f for f in _own_frames() if (f[2] - f[0]) < 200 and (f[3] - f[1]) < 200]
        return btn[0] if btn else None

    # v0.1.9 修：绝不在这里 uiautomator dump —— 它触发服务重绑（install -r 级配方才能自愈，
    # settings put 无效，11:45 实证），把 E11 自己搞死。输入框预填文本是 testhost 固定值，
    # 不需要读 DOM；闭环全部用 logcat + dumpsys window（两者只读不重绑）断言。
    ok = wait_for(lambda: compose_frame() is not None, timeout=20)
    assert ok, "E11 FAIL: 「译」按钮未出现（findComposer 没命中输入框）"
    fr = compose_frame()
    cx, cy = (fr[0]+fr[2])//2, (fr[1]+fr[3])//2
    sh("shell", "input", "tap", str(cx), str(cy))
    ok = wait_for(lambda: "compose result:" in logs(), timeout=30)
    assert ok, "E11 FAIL: 点「译」无 compose result 日志（翻译或 ACTION_SET_TEXT 失败）"
    time.sleep(2)
    fr2 = compose_frame()
    assert fr2, "E11 FAIL: 写回后按钮消失"
    cx2, cy2 = (fr2[0]+fr2[2])//2, (fr2[1]+fr2[3])//2
    sh("shell", "input", "tap", str(cx2), str(cy2))
    ok = wait_for(lambda: "compose restore:" in logs(), timeout=30)
    assert ok, "E11 FAIL: 再点未还原原文（无 compose restore 日志）"
    print("E11 输入框「译」→写回→再点还原 通过")

    # ================= E12（v0.2.0）：节点 text 滞后时点击现读（真机"早上好→pagi"取证） =================
    # SETINPUT 只动 EditText.text 不触发 build()/内容事件 → overlay 缓存快照停在旧值；
    # 修复后 onComposeTranslate 点击瞬间 rootInActiveWindow 现读，compose result 必须带全文。
    bcast("id.co.watranslate.SETINPUT", text="Selamat pagi Pak Chen hari ini baik")
    time.sleep(2)   # 故意不给 scan 触发机会（无窗口事件）——模拟滞后窗口
    fr3 = compose_frame()
    assert fr3, "E12 FAIL: SETINPUT 后按钮消失"
    cx3, cy3 = (fr3[0]+fr3[2])//2, (fr3[1]+fr3[3])//2
    sh("shell", "logcat", "-c")   # 清缓冲让 compose result 断言只看本轮
    sh("shell", "input", "tap", str(cx3), str(cy3))
    # take(20) 截断实证："Selamat pagi Pak Chen" 21 字符被切成 "Pak Che" → 断言用前 15 字符
    ok = wait_for(lambda: "compose result: Selamat pagi Pa" in logs() and "陈总早上好" in logs(), timeout=30)
    assert ok, "E12 FAIL: 点击未现读最新输入（result 日志缺全文，text 滞后未修）"
    print("E12 输入框节点滞后→点击现读 通过")

    # ================= E13（v0.2.0）：退出 WhatsApp → 「译」按钮清除（rows 空也要生效） =================
    sh("shell", "am", "start", "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME")
    ok = wait_for(lambda: compose_frame() is None, timeout=15)
    assert ok, "E13 FAIL: 退桌面后「译」按钮残留（detachAll 未覆盖 composeWin）"
    print("E13 退出聊天界面→「译」按钮清除 通过")

    # ================= E14（v0.2.1）：「译」按钮与头像列对齐（用户定案左移） =================
    sh("shell", "am", "start", "-n", HOST_PKG + "/.TestHostActivity")
    ok = wait_for(lambda: compose_frame() is not None, timeout=25)
    assert ok, "E14 FAIL: 回聊天页按钮未重建"
    fr4 = compose_frame()
    # testhost 对方气泡左缘 x=27（E1 inject 日志同源）；按钮 x 必须贴同一列（±8px），
    # 不再是输入框左缘 154（v0.2.0 被用户判"还在中间"）
    assert abs(fr4[0] - 27) <= 8, f"E14 FAIL: 按钮 x={fr4[0]} 未与头像列(27)对齐"
    print(f"E14 「译」按钮 x={fr4[0]} 与头像列齐 通过")
    # ================= E15（v0.2.2 监控）：tap miss 归因 + drop 归因通道（真机取证预演） =================
    # 点自己消息 → pick 已滤 own → 候选无命中 → logTapMiss 必须产出「原因=own」；
    # 点删除占位 → 归因「原因=deleted」。这两行以前只在 logcat 的 click miss（无归因），
    # 现在带被点节点 id/cls/规则名——真机「点了没反应」从此可查。
    sh("shell", "logcat", "-c")
    tap_text("你好，明天上午开会")   # own 气泡
    ok = wait_for(lambda: "tap miss" in logs() and "原因=own" in logs(), timeout=20)
    assert ok, "E15 FAIL: 点自己消息无 原因=own 归因"
    sh("shell", "logcat", "-c")
    xy_del2 = tap_text("这条消息已删除")  # system 占位
    ok = wait_for(lambda: "原因=deleted" in logs(), timeout=20)
    assert ok, "E15 FAIL: 点占位消息无 原因=deleted 归因"
    assert "drop deleted" in logs(), "E15 FAIL: drop deleted 归因未进日志"
    # v0.2.3 M3c 内容去重：同实例再点同一占位（裸坐标 tap，不 dump 不重绑）→ drop 不重复记
    n_drop1 = logs().count("drop deleted")
    sh("shell", "input", "tap", str(xy_del2[0]), str(xy_del2[1]))
    time.sleep(3)
    assert logs().count("drop deleted") == n_drop1, \
        f"E15 FAIL: drop 内容去重未生效（{n_drop1}→{logs().count('drop deleted')}）"
    print("E15 tap miss/drop 归因监控通道 + 内容去重 通过")

    # ================= E16（v0.2.2 监控）：长译文几何量化 measure =================
    # APPEND 单行长汇报（mock 返 12 行译文）→ measureRow 必须产出量化行。
    # 模拟器实证 maxLines=10 未裁剪 12 行（lineCount=12）→ 病因判定交给真机日志，
    # measure 行恒记录（译文行/显示行/行底/屏高/截断?/溢出?），本用例只断言通道活着。
    LAPORAN = 'LAPORAN KERUSAKAN Mesin A006 stop total 6 item detail gearbox'
    bcast("id.co.watranslate.APPEND", text=LAPORAN)
    time.sleep(3)
    sh("shell", "logcat", "-c")
    tap_text("LAPORAN KERUSAKAN Mesin A006")
    ok = wait_for(lambda: "row result: LAPORAN" in logs(), timeout=40)
    assert ok, "E16 FAIL: 长文行未出结果（row result 日志缺失）"
    ok = wait_for(lambda: "measure: src=" in logs() and "译文12行" in logs(), timeout=15)
    assert ok, "E16 FAIL: measure 量化行未产出或行数错（期望 译文12行）"
    print("E16 长译文 measure 量化监控 通过")

    # ================= E17（v0.2.3 问题②）：人名回显学习 = 灰显不发 API =================
    # 真机取证：谭效平 HTTP200 434B【空内容】→ isEcho(src,"")=true →"显示的是原文"。
    # mock 词条 '王小明':'' 复刻同路径。新机制：回显+像人名 → 本次即灰显「〔人名·保留
    # 原文〕」+ 落缓存哨兵；此后点击秒命中零 API（mock hits 计数不涨）。
    # 【红线】E16 之后禁止任何 find_bounds（uiautomator dump = 服务重绑 rows 清零）——
    # 坐标只从 tap_text 拿一次（它 dump 在 tap 之前，重绑先于点击，无害），后续全部裸 tap。
    NAME17 = "王小明"
    bcast("id.co.watranslate.APPEND", text=NAME17)
    time.sleep(3)
    sh("shell", "logcat", "-c")
    mock_hits(reset=True)
    xy17 = tap_text(NAME17)
    ok = wait_for(lambda: "name learned (echo->grey, cached)" in logs(), timeout=45)
    assert ok, "E17 FAIL: 回显未触发人名学习（无 name learned 日志）"
    assert "row name-only" not in logs(), "E17 FAIL: 首次学习不应走缓存分支"
    h1 = mock_hits()
    assert h1 >= 1, "E17 FAIL: 首译未请求 API（学习需要先观察一次回显）"
    # 再点气泡 = 关闭（三态）；第三次点 = 重开 → 必须走缓存哨兵分支、零 API
    sh("shell", "logcat", "-c")
    sh("shell", "input", "tap", str(xy17[0]), str(xy17[1]))
    ok = wait_for(lambda: "tap 关闭" in logs() or "close row" in logs(), timeout=20)
    assert ok, "E17 FAIL: 二次点击未关闭"
    sh("shell", "logcat", "-c")
    sh("shell", "input", "tap", str(xy17[0]), str(xy17[1]))
    ok = wait_for(lambda: "row name-only (cached):" in logs(), timeout=25)
    assert ok, "E17 FAIL: 重开未走人名缓存分支"
    assert mock_hits() == h1, f"E17 FAIL: 人名缓存分支请求了 API（hits 应={h1}）"
    # 点行文字重译 = 不 forget、不请求（"人名·跳过API" 或直接灰显缓存）
    sh("shell", "logcat", "-c")
    rows17b = [(x, y, t) for x, y, t in injects() if NAME17 in t]
    cands17b = [f for f in _own_frames() if (f[2] - f[0]) >= 200]
    best17b = min(cands17b, key=lambda f: abs(f[1] - rows17b[-1][1])) if (rows17b and cands17b) else None
    if best17b and abs(best17b[1] - rows17b[-1][1]) <= 60:
        sh("shell", "input", "tap", str((best17b[0] + best17b[2]) // 2), str((best17b[1] + best17b[3]) // 2))
        ok = wait_for(lambda: "人名·跳过API" in logs() or "row name-only (cached)" in logs(), timeout=25)
        assert ok, "E17 FAIL: 点行重译未跳过 API"
        assert mock_hits() == h1, "E17 FAIL: 点行重译仍请求了 API"
    print("E17 人名回显学习=灰显+零API缓存 通过")

    # ================= E18（v0.3.1 反转）：点击输入框文字 = 只消费、绝不触发翻译 =================
    # v0.2.4 旧行为（点文字直接译）被真机 9/19 取证推翻：WhatsApp/IME 编辑期自发
    # VIEW_CLICKED（移光标/候选上屏/自动更正）全部误触发 → 长中文消息一校对就自动
    # 翻译写回、中↔印来回翻转（8 连击实锤）。v0.3.1 起输入框翻译入口只认「译」按钮。
    # 本用例 = 防回归闸门：预填文本 → 裸点文字 → 断言【无】任何翻译链路 + 框内文本不变。
    COMPOSE18 = "Pak tolong cek stok gudang B"
    bcast("id.co.watranslate.SETINPUT", text=COMPOSE18)
    time.sleep(2)
    xy_pre = find_bounds("tolong cek stok", clickable_only=False)
    assert xy_pre, "E18 FAIL: 找不到输入框预填文本"
    sh("shell", "input", "tap", str(xy_pre[0]), str(xy_pre[1]))
    time.sleep(3)                                    # 键盘弹出、窗口 re-layout
    bcast("id.co.watranslate.SETINPUT", text=COMPOSE18)  # 重置为原文（与旧流程一致的确定性保障）
    time.sleep(2)
    xy_in = find_bounds("tolong cek stok", clickable_only=False)   # 键盘态新坐标
    assert xy_in, "E18 FAIL: 键盘弹出后输入框不可见"
    sh("shell", "logcat", "-c")
    sh("shell", "input", "tap", str(xy_in[0]), str(xy_in[1]))      # 再点文字（键盘已开，布局稳定）
    time.sleep(6)                                     # 给误触发留足传播时间（scan 450ms 节流×多轮）
    lg18 = logs()
    assert "composer tap -> translate:" not in lg18 and "输入框点击译" not in lg18 \
        and "compose start:" not in lg18, \
        "E18 FAIL(v0.3.1 回归): 点输入框文字仍触发翻译链路\n" + lg18[-600:]
    ok = bool(find_bounds("tolong cek stok", clickable_only=False))   # 原文未被写回替换
    assert ok, "E18 FAIL(v0.3.1 回归): 框内文本被改动（幽灵翻译复活？）"
    print("E18 点输入框文字=零触发（只消费，翻译只认「译」按钮）通过")

    # ================= E19（v0.2.4 问题②）：测试按钮诚实性 = 测表单当前输入 =================
    # 真机取证：用户加 MiniMax 模型，「测试可以一保存就不行」——旧实现 resolve() 在 profiles
    # 非空时只读已保存配置，表单输入被忽略（说谎的绿灯）。v0.2.4 直接测表单现值。
    # mock 对含 ghost 的模型名回 404 → 点测试后 ghost 必须出现在请求体（旧代码不会有）。
    sh("shell", "am", "start", "-n", PKG_APP + "/.MainActivity")
    time.sleep(3)
    # 打开「默认配置」profile 的编辑表单（LatinIME 纯英文，input text 可靠）。
    # v0.4.1 四标签页：表单在「云端」页 → 先切标签再找。
    def find_scroll(txt, tab=None):
        for _ in range(5):
            xy = find_bounds(txt, clickable_only=False)
            if xy: return xy
            if tab:
                t = find_bounds(tab)
                if t: sh("shell", "input", "tap", str(t[0]), str(t[1])); time.sleep(1)
            sh("shell", "input", "swipe", "540", "1400", "540", "600")
            time.sleep(1.5)
        return None
    xy_ed = find_scroll("mock-model", tab="云端")   # EditText 模型框（clickable_only=False 才收 EditText）
    assert xy_ed, "E19 FAIL: 找不到模型输入框"
    sh("shell", "input", "tap", str(xy_ed[0]), str(xy_ed[1]))
    time.sleep(1)
    sh("shell", "input", "keyevent", "KEYCODE_MOVE_END")
    for _ in range(12):
        sh("shell", "input", "keyevent", "KEYCODE_DEL")      # 清空旧值（mock-model=10 字，多删无妨）
    sh("shell", "input", "text", "ghost")                     # 表单输入 ghost（mock: 该模型必 404）
    time.sleep(1)
    # 「测试连接」在表单底部、软键盘弹出时被完全遮挡（run-7 截图实证）→
    # 不BACK收键盘（无键盘时 BACK 会退出页面，E18 教训），改为在可见视口内上滑把按钮滚出来。
    xy_tb = None
    for _ in range(6):
        xy_tb = find_bounds("测试连接", clickable_only=False)
        if xy_tb: break
        sh("shell", "input", "swipe", "540", "900", "540", "400", "300")
        time.sleep(1.2)
    assert xy_tb, "E19 FAIL: 找不到测试连接按钮"
    before_len = os.path.getsize("/tmp/wt-test/mock_bodies.log") if os.path.exists("/tmp/wt-test/mock_bodies.log") else 0
    sh("shell", "input", "tap", str(xy_tb[0]), str(xy_tb[1]))
    time.sleep(8)
    # 注意：mock_bodies.log 每行含大量 CJK（UTF-8 多字节）→ 字节偏移≠字符偏移。
    # 旧写法 read()[字节数:] 会切进 ghost 行内部丢掉 "model" 前缀（run-8 假阴性根因）。按字节 seek。
    body_new = ""
    if os.path.exists("/tmp/wt-test/mock_bodies.log"):
        with open("/tmp/wt-test/mock_bodies.log", "rb") as bf:
            bf.seek(before_len)
            body_new = bf.read().decode("utf-8", "replace")
    assert re.search(r'"model":\s*"[^"]*ghost', body_new, re.I), \
        "E19 FAIL: 测试按钮未使用表单当前模型（ghost 没进请求体=旧说谎行为复活）"
    print("E19 测试按钮=表单当前输入（ghost 进请求体）通过")

    # ================= E20（v0.2.5）：思考泄漏三闸门 + 输入框写回全链路监控 =================
    # 真机取证 9/18 07:23：MiniMax M2.x 思考无法关闭（官方 doc 证实）→ 思考文当译文
    # 缓存+画绿行（forget→refetch 同一坨）。v0.2.5 三闸门 = extractExpectedArray 数量
    # 契约 / translateSingle strip+元叙述闸门抛错 / 泄漏绝不写缓存。E20 同时是 ①② 监控
    # 行（写回✓/拒写/还原↩/cache hit 带值）的首次端到端闭环——07:41 型断点从此可判。
    # 键盘纪律：E19 遗留模型框焦点（run-9 实证）→ 先 BACK 收它（此刻键盘确实在屏，安全）。
    # 链路 A-E 全走「译」按钮（E11 通道：小方块窗口中心裸 tap，零 dump、零焦点变化）；
    # 点文字触发 = v0.2.4 E18 已覆盖，此处不重复（只多 1 次 dump 拿按钮存在性即可）。
    sh("shell", "keyevent", "111")                                   # 收 E19 遗留键盘（此刻在屏，安全）
    time.sleep(1.5)
    sh("shell", "am", "start", "-n", HOST_PKG + "/.TestHostActivity")  # 输入框必须在前台，「译」才挂得上
    time.sleep(3)
    COMPOSE20 = "库存已确认，明天可以装柜"
    bcast("id.co.watranslate.SETINPUT", text=COMPOSE20)
    time.sleep(2)
    def compose_frame20():
        btn = [f for f in _own_frames() if (f[2] - f[0]) < 200 and (f[3] - f[1]) < 200]
        return btn[0] if btn else None
    ok = wait_for(lambda: compose_frame20() is not None, timeout=20)
    assert ok, "E20 FAIL: 「译」按钮未出现"
    bx, by = (lambda f: ((f[0]+f[2])//2, (f[1]+f[3])//2))(compose_frame20())
    leak(1)
    sh("shell", "logcat", "-c")
    sh("shell", "input", "tap", str(bx), str(by))                    # A：译按钮（泄漏态）
    ok = wait_for(lambda: "single 思考泄漏" in logs(), timeout=60)
    assert ok, "E20 FAIL: 泄漏未触发 translateSingle 闸门（批量throw→逐条single也应命中）"
    ok = wait_for(lambda: "输入框拒写" in logs() and "模型输出思考文本" in logs(), timeout=20)
    assert ok, "E20 FAIL: 泄漏未拒写（无 输入框拒写+原因 行）"
    assert "输入框写回✓" not in logs(), "E20 FAIL: 思考泄漏被写回=毒写回"
    # B：立刻再点 → v0.3.8 P3 语义：「译」按钮=用户主动意图，冷却旁路直接重试
    #（旧 v0.2.5 行为=吃冷却拒打 API；真机 9/25 取证冷却惩罚误伤主动点击）。
    # 泄漏态下重试仍必须被闸门拒写（绝不因旁路而毒写回）。
    sh("shell", "input", "tap", str(bx), str(by))
    ok = wait_for(lambda: "冷却旁路(用户点译)" in logs(), timeout=20)
    assert ok, "E20 FAIL: 泄漏失败后再点「译」未走冷却旁路（v0.3.8 P3 语义）"
    ok = wait_for(lambda: logs().count("输入框拒写") >= 2, timeout=30)
    assert ok, "E20 FAIL: 旁路重试未被泄漏闸门二次拒写"
    assert "输入框写回✓" not in logs(), "E20 FAIL: 旁路重试写回=毒写回"
    sh("shell", "logcat", "-c")   # B 旁路重试已留 2 条拒写；清零让 C 的「写回✓」断言只测 C
    time.sleep(6)   # 等 tap A 的异步 compose 回调全部落账（A 与 B 并发在途，防竞态误判）
    leak(0)
    time.sleep(16)                                                   # 冷却到期
    sh("shell", "logcat", "-c")
    sh("shell", "input", "tap", str(bx), str(by))                    # C：恢复正常 → 写回✓
    ok = wait_for(lambda: "输入框写回✓" in logs(), timeout=45)
    assert ok, "E20 FAIL: 恢复后写回链路不通（07:41 同型断点将无从定位——此行即监控本体）"
    assert "Stok sudah" in logs(), "E20 FAIL: 写回内容非正常译文"
    sh("shell", "input", "tap", str(bx), str(by))                    # D：再点 = 还原原文
    ok = wait_for(lambda: "输入框还原↩" in logs(), timeout=25)
    assert ok, "E20 FAIL: 再点未还原（无 输入框还原↩ 行）"
    # D 的还原 SET_TEXT 进 IME 队列后 EditText 异步更新，a11y live-node 快照滞后——
    # E 立刻点译会读到滞后译文 → 反向请求 → mock 回显拒写（v0.3.5 run5/6/7 + v0.4.0/v0.4.1
    # 同一 flaky 实锤；v0.2.0 注释「节点滞后」的输入框版）。等落定再点。
    time.sleep(3)
    sh("shell", "logcat", "-c")
    sh("shell", "input", "tap", str(bx), str(by))                    # E：cache hit 带值→再写回
    ok = wait_for(lambda: "库存已确认，明天可以装柜 → Stok sudah" in logs()
                  and "输入框写回✓" in logs(), timeout=30)
    assert ok, "E20 FAIL: cache hit 未带值（② 未闭环）"
    assert "用户要求我把这条消息" not in logs(), "E20 FAIL: 思考文混进了监控行/写回"
    # 收尾还原：E 又写回了译文 → 再点一次回到原文，并收 C 步可能弹出的键盘（保 E9b BACK 安全）
    sh("shell", "input", "tap", str(bx), str(by))
    wait_for(lambda: "输入框还原↩" in logs(), timeout=25)
    print("E20 思考泄漏闸门+输入框写回全链路监控（✓/✘/拒写/还原↩/hit带值）通过")

    sh("shell", "am", "start", "-n", HOST_PKG + "/.TestHostActivity")
    time.sleep(4)

    # E9b 诊断导出 → 公共 Download/（v0.1.2：MediaStore 路径）
    sh("shell", "am", "start", "-n", PKG_APP + "/.MainActivity")
    time.sleep(2)
    # v0.2.5：E20 已收掉 E19 遗留键盘且自身全程裸 tap（overlay 点击不改焦点）→ 此处不能再
    # BACK（无键盘时 BACK 直接退出 MainActivity，run-9 教训反用）。滚动重试逻辑不变：
    # 「导出诊断」在页面顶部，E19/E20 留下的下滚状态用朝上回滚（内容下移=去页首）找。
    # v0.4.1 四标签页：E19/E20 停在「云端」页 → 导出诊断在「常规」页，先点回标签（Button 可点）。
    xy_tab = find_bounds("常规")
    if xy_tab: sh("shell", "input", "tap", str(xy_tab[0]), str(xy_tab[1])); time.sleep(1)
    xy = None
    for _ in range(6):
        xy = find_bounds("导出诊断")
        if xy: break
        sh("shell", "input", "swipe", "540", "500", "540", "1500", "300")
        time.sleep(1.2)
    assert xy, "找不到导出诊断按钮"
    sh("shell", "input", "tap", str(xy[0]), str(xy[1]))
    time.sleep(2)
    dl = sh("shell", "ls /storage/emulated/0/Download/wa-translate-diag.txt 2>&1")
    n = sh("shell", "wc -l /storage/emulated/0/Download/wa-translate-diag.txt 2>/dev/null")
    print("E9b Download 诊断文件:", dl.strip(), "|", n.strip())
    assert "wa-translate-diag.txt" in dl and "No such" not in dl, "E9b FAIL: Download/ 无诊断文件"
    assert int((re.search(r"(\d+)", n) or [0, "0"])[1] if re.search(r"(\d+)", n) else 0) >= 10, "E9b FAIL: 诊断文件行数异常"
    print("E9b 诊断导出到 Download 通过")

    # ================= E21（v0.2.7）：引用回复结构识别+点击归位（真机 09-21 08:59 案复刻） =================
    # testhost 广播注入仿 WhatsApp 无 id 引用树 → 断言三件事：
    #  ① pick 级：引用文字被竖条规则剔除（drop quote-bar）
    #  ② 点击归位：点引用文字 → 归位通道 → 译出的是【正文】而非引用原句
    #  ③ 关闭语义：点正文 → 关行（v0.1.8 三态不变）
    sh("shell", "am", "start", "-n", HOST_PKG + "/.TestHostActivity")
    time.sleep(1.5)
    bcast("id.co.watranslate.QUOTE")
    time.sleep(3)                      # 触发扫描（树已换，rows 重绑无害——本用例不依赖旧 rows）
    L21 = logs()
    assert "drop quote-bar" in L21, "E21 FAIL: 引用文字未被竖条规则剔除（drop quote-bar 缺失）"
    sh("shell", "logcat", "-c")
    xy_q = find_bounds("Area komposisi", clickable_only=False)
    assert xy_q, "E21 FAIL: 找不到引用文字节点"
    sh("shell", "input", "tap", str(xy_q[0]), str(xy_q[1]))
    ok = False
    for _ in range(12):
        time.sleep(2)
        lg = logs()
        # 证据链（logcat 通道，双通道规则）：直发single 带正文前缀 + 正文行结果
        if "row result: Baik terimakasih" in lg:
            ok = True; break
    lg = logs()
    assert ok, "E21 FAIL: 点引用区未译出正文（row result: Baik... 缺失）\n" + lg[-800:]
    assert "tap 首译: Baik terimakasih" in lg, "E21 FAIL: 点击归位目标非正文"
    assert "直发single(1条): Baik terimakas" in lg, "E21 FAIL: 翻译目标不是正文（无直发single正文行）"
    # 通道说明：模拟器有 rect 且正文候选 anchor=整泡 → bubbleInRect 直判命中；真机
    # event.source 无 rect 才走 归位引用/归位容器 通道（纯函数由 J21 单测覆盖，殊途同归=正文）。
    assert not re.search(r"inject row @\d+,\d+: Area komposisi", lg), "E21 FAIL: 仍在译引用原句（回归 v0.2.6 bug）"
    print("E21 引用回复→竖条剔除+点击归位正文 通过")
    # 收尾：再点正文行气泡 = 关行（v0.1.8 三态）；然后 SEED 恢复默认消息树
    xy_b = find_bounds("Baik terimakasih", clickable_only=False)
    if xy_b:
        sh("shell", "input", "tap", str(xy_b[0]), str(xy_b[1]))
        time.sleep(2.5)
    bcast("id.co.watranslate.SEED")
    time.sleep(2)

    # ================= E22（v0.2.8 问题⑤）：输入框哨兵死锁→清哨兵重译（真机 9/21「A009修理完成了吗？」复刻） =================
    # 死锁链=模型回显→KEEP 哨兵落缓存→输入框每次 cache hit 毫秒拒写（消息行有「点行重译」出口，
    # 输入框没有）。修复=onComposeTranslate 命中哨兵先 forget 再真重译。三段：
    #   段1 echo 开 → 两轮回显 → 输入框拒写（KEEP 落缓存）
    #   段2 echo 关 → 再点 = 「输入框重译(清哨兵)」→ 真 API → 写回✓（修复前这里=再拒写=FAIL）
    #   段3 内容==译文 再点 = 还原原文（顺带守卫 E11 还原语义不回归）
    COMPOSE22 = "Apakah mesin A009 sudah selesai diperbaiki"
    bcast("id.co.watranslate.SETINPUT", text=COMPOSE22)
    time.sleep(2)
    ok = wait_for(lambda: compose_frame() is not None, timeout=15)
    assert ok, "E22 FAIL: 「译」按钮未出现"
    def tap_btn22():
        fr = compose_frame()
        assert fr, "E22 FAIL: 「译」按钮消失"
        sh("shell", "input", "tap", str((fr[0]+fr[2])//2), str((fr[1]+fr[3])//2))
    sh("shell", "logcat", "-c")
    set_echo(True)
    tap_btn22()                                        # 段1：两轮全回显 → KEEP 灰化拒写 + 落缓存
    ok = wait_for(lambda: "输入框拒写: Apakah mesin" in logs(), timeout=40)
    assert ok, "E22 FAIL: 段1 回显未拒写\n" + logs()[-600:]
    assert "回显保留(重试仍回显): Apakah mesin" in logs(), "E22 FAIL: 段1 未走 KEEP 链（哨兵没落缓存）"
    set_echo(False)
    mock_hits(reset=True)
    tap_btn22()                                        # 段2：修复点——命中哨兵 → forget → 真重译
    ok = wait_for(lambda: "输入框重译(清哨兵): Apakah mesin A0" in logs(), timeout=20)
    assert ok, "E22 FAIL: 段2 哨兵缓存未清死锁回归\n" + logs()[-600:]
    ok = wait_for(lambda: "输入框写回✓: Apakah mesin" in logs() and "A009机器修理完成了吗" in logs(), timeout=40)
    assert ok, "E22 FAIL: 段2 重译未写回\n" + logs()[-800:]
    assert mock_hits() >= 1, "E22 FAIL: 段2 零 API=仍 cache hit（forget 没生效）"
    tap_btn22()                                        # 段3：还原
    ok = wait_for(lambda: "输入框还原↩: Apakah mesin A0" in logs(), timeout=20)
    assert ok, "E22 FAIL: 段3 写回后再点未还原（E11 语义回归）"
    print("E22 输入框哨兵死锁→清哨兵重译→写回→还原 通过")

    # ================= E24（v0.3.2）：气泡内时间戳过滤（真机 09-23「部分可以翻译,大部份不行」复刻） =================
    # 现场=中文 WhatsApp 气泡右下角「早上6:21」+ 日期胶囊「2026年9月23日」，全树无 view-id。
    # 旧 CLOCK_RES 缺「早上」→ 碎片 mergeFragments 拼进正文 → 含汉字 detectTarget 判 id →
    # 印尼文回显合法过闸门（LAPORAN KERUSAKAN 不译的直接根因）。修复后两碎片各自被文本正则剔除。
    # 本段在 Phase B（auto=false，点击才译）：drop 断言靠注入扫描，译文走正文点击（E21 同型）。
    # 注意：clock/divider 在 dropSkip 静音集合（Service L533，防 2000 环刷屏）= 不打 drop 行，
    # 过滤是否生效以【候选纯净度】为准：scan cands=1 且正文干净（时间戳/日期碎片已消失）。
    sh("shell", "am", "start", "-n", HOST_PKG + "/.TestHostActivity")
    time.sleep(1.5)
    sh("shell", "logcat", "-c")
    bcast("id.co.watranslate.CLOCKROW")
    ok = wait_for(lambda: "scan cands=1: Laporan kerusaka" in logs(), timeout=20)
    L24 = logs()
    assert ok, "E24 FAIL: 候选不是干净的 1 条正文（碎片漏进 merge=根因回归）\n" + L24[-800:]
    assert not re.search(r"scan cands=\d+: .*早上", L24), "E24 FAIL: 候选含「早上6:21」碎片（clock 规则失效）"
    assert not re.search(r"scan cands=\d+: .*2026年", L24), "E24 FAIL: 候选含日期胶囊（divider 规则失效）"
    # 端到端效果：点印尼文正文气泡 → 候选键名必须是干净正文（无时间戳碎片）→ mock 命中真译文
    xy24 = find_bounds("Laporan kerusakan", clickable_only=False)
    assert xy24, "E24 FAIL: 找不到正文节点"
    sh("shell", "logcat", "-c")
    sh("shell", "input", "tap", str(xy24[0]), str(xy24[1]))
    ok = wait_for(lambda: any("Laporan kerusakan pisau" in r and "A006刀具故障报告" in o for r, o in results()), timeout=45)
    lg24 = logs()
    assert ok, "E24 FAIL: 正文未被干净译出（时间戳混进候选键名=根因回归）\n" + lg24[-800:]
    assert not re.search(r"row result: .*早上", lg24), "E24 FAIL: 译文行键名含「早上」（merge 污染回归）"
    assert not re.search(r"row result: .*2026年", lg24), "E24 FAIL: 译文行键名含日期胶囊（divider 泄漏回归）"
    # 收尾恢复默认树
    bcast("id.co.watranslate.SEED")
    time.sleep(2)
    print("E24 气泡内时间戳+日期胶囊过滤 通过")

    # ================= E25（v0.3.3）：组名+「已转发」污染树（真机 09-23「凭空加词」复刻） =================
    # 现场=群聊对方气泡三碎片：小字发送者名「陈副厂长」+「已转发」角标+印尼文正文，全树无 id。
    # 旧行为：名+角标 merge 进 src → detectTarget 翻转 → 译文多出 Wakil Direktur Chen /
    # Sudah dikirimkan（demo 纯文本粘贴无此现象=用户报「翻译模型凭空添加中文」）。
    # 修复链：②a FORWARDED_RES 单节点剔除（drop forwarded，非静音集合可见）；②b senderNameLine
    # merge 层整行剔除（trace sender-name-line）；①方向多数表决兜底。断言三件套：
    # 候选纯净 + 两条 drop 归因 + 点击出中文净译文（键名/译文都不含污染碎片）。
    sh("shell", "logcat", "-c")
    bcast("id.co.watranslate.GROUPROW")
    ok = wait_for(lambda: "scan cands=1: Mau lihat perba" in logs(), timeout=20)
    L25 = logs()
    assert ok, "E25 FAIL: 候选不是干净的 1 条正文（名行/角标漏进 merge=根因回归）\n" + L25[-800:]
    assert not re.search(r"scan cands=\d+: [^;\n]*陈副厂长", L25), "E25 FAIL: 候选键名含发送者名（senderNameLine 失效）"
    assert "drop forwarded:" in L25, "E25 FAIL: 「已转发」未被单节点过滤（FORWARDED_RES 失效）\n" + L25[-800:]
    assert "drop sender-name-line:" in L25, "E25 FAIL: 名行未被 merge 层剔除（sender-name-line trace 缺失）\n" + L25[-800:]
    xy25 = find_bounds("Mau lihat", clickable_only=False)
    assert xy25, "E25 FAIL: 找不到正文节点"
    sh("shell", "logcat", "-c")
    sh("shell", "input", "tap", str(xy25[0]), str(xy25[1]))
    ok = wait_for(lambda: any("Mau lihat" in r and "我想看看A4的对比件" in o for r, o in results()), timeout=45)
    lg25 = logs()
    assert ok, "E25 FAIL: 正文未被干净译出中文（方向/污染回归）\n" + lg25[-800:]
    assert not re.search(r"row result: [^\n]*陈副厂长", lg25), "E25 FAIL: 译文行键名含发送者名（污染回归）"
    assert not re.search(r"row result: [^\n]*已转发", lg25), "E25 FAIL: 译文行键名含「已转发」（角标泄漏回归）"
    assert not re.search(r"row result: .* -> [^\n]*(Wakil|Dikirimkan)", lg25), "E25 FAIL: 译文出现凭空加词形态"
    # 收尾恢复默认树
    bcast("id.co.watranslate.SEED")
    time.sleep(2)
    print("E25 组名+已转发污染过滤（凭空加词修复） 通过")

    # ================= E26（v0.3.5）：点按气泡→自动复制全文→重译（WhatsApp 80字截断根治） =================
    # 测试面=testhost 仿真机双层气泡：可见 TextView 全文渲染但退出 a11y 树，
    # 透明覆盖层=节点树里唯一可点击文本（恰 80 字符，复刻真机截断形态）+长按弹「复制」菜单。
    # mock 只配【全文】词条——截断形态无词条：拿截断文本翻译必走 ZH[回显]→闸门拦→行失败。
    # 断言链：①候选=80字截断形态 ②点击→tap-copy 启动→菜单→剪贴板全文 ✓→全文重译
    # ③row result 键名=全文且译文正确 ④映射落盘（wtrans_fulltext SP 出现）⑤二次点击不再
    # 注入长按（映射自愈：logcat 无第二条「tap-copy 启动」且行正常=旧行关闭重开）。
    sh("shell", "am", "force-stop", HOST_PKG)
    sh("shell", "logcat", "-c")
    sh("shell", "am", "start", "-n", HOST_PKG + "/.TestHostActivity")
    time.sleep(1.5)
    bcast("id.co.watranslate.LONGMSG")
    ok = wait_for(lambda: "scan cands=1: To Semua Mesin" in logs(), timeout=20)
    L26 = logs()
    assert ok, "E26 FAIL: 长消息候选未出现\\n" + L26[-600:]
    # 候选必须是 80 字截断形态（scan dbg 行截 16 字看不出——用缓存 miss 日志的 take(24) 也不够，
    # 直接点译文行键名断言阶段核）
    xy26 = find_bounds("To Semua Mesin", clickable_only=False)
    assert xy26, "E26 FAIL: 找不到长消息节点"
    sh("shell", "logcat", "-c")
    sh("shell", "input", "tap", str(xy26[0]), str(xy26[1]))
    ok = wait_for(lambda: "tap-copy ✓" in logs(), timeout=30)
    L26 = logs()
    assert ok, "E26 FAIL: tap-copy 未走通（无 ✓ 行）\\n" + L26[-1200:]
    assert "tap-copy 启动" in L26, "E26 FAIL: 无启动日志（长文本点击未触发探测）"
    # v0.3.6：长按探测改事件线程同步派发 → 菜单先弹，本轮 scan 看不到气泡候选 →
    # 截断形态译文行【不再闪现】（旧版是"先闪截断行→再全文重译"）。全文行直接建：
    assert "全文重译" in L26 or "全文建行" in L26, "E26 FAIL: 全文未触发重译/建行\\n" + L26[-800:]
    # row result 日志 src 截 24 字：截断形态与全文前 24 字相同——判全文的唯一证据=
    # 「致全部压延机」译文（截断键在 mock 无词条→ZH[回显]→闸门拦→行失败，绝不出此译文）
    ok = wait_for(lambda: any("致全部压延机" in o for _, o in results()), timeout=45)
    L26b = logs()
    assert ok, "E26 FAIL: 译文行键名非全文或译文缺失\\n" + L26b[-1000:]
    # 映射落盘 = 真机重启后仍免疫截断键（③持久化断言）
    ftsp = sh("shell", "cat", "/data/data/id.co.watranslate/shared_prefs/wtrans_fulltext.xml")
    assert "kantor pusat" in ftsp, "E26 FAIL: 全文映射未落盘\\n" + ftsp[:300]
    # 二次点击：不再注入长按探测（映射已命中→直接换全文行，无第二条「tap-copy 启动」）
    sh("shell", "logcat", "-c")
    sh("shell", "input", "tap", str(xy26[0]), str(xy26[1]))
    time.sleep(3)   # 给误触发留窗口
    L26c = logs()
    assert "tap-copy 启动" not in L26c, "E26 FAIL: 映射已命中仍注入长按探测（骚扰菜单回归）"
    ok = wait_for(lambda: "tap 首译: To Semua Mesin" in logs() or "close row" in logs(), timeout=15)
    assert ok, "E26 FAIL: 二次点击无正常响应（映射文本 toggle 失效）\\n" + L26c[-600:]
    bcast("id.co.watranslate.SEED")
    time.sleep(2)
    print("E26 点按→自动复制全文→全文重译+映射落盘+免二次探测 通过")

    # ================= E27（v0.3.5）：「译」球长按拖拽+位置持久化 =================
    # 真拖拽=input draganddrop（API 34 可用）从当前球心拖到别处 → 服务 onTouch 长按 400ms
    # 起拖判定生效 → 松手落位+SP 持久化。断言：frame 移位到目标附近 → 杀进程重启（服务
    # 重连、窗口重建）后仍固定 → 触发重锚场景（swipe）不被劫持。
    sh("shell", "am", "start", "-n", HOST_PKG + "/.TestHostActivity")
    ok = wait_for(lambda: compose_frame() is not None, timeout=20)
    assert ok, "E27 FAIL: 「译」按钮未出现"
    fr = compose_frame()
    cx0, cy0 = (fr[0]+fr[2])//2, (fr[1]+fr[3])//2
    tx, ty = 700, 400
    sh("shell", "input", "draganddrop", str(cx0), str(cy0), str(tx), str(ty), "1200")
    ok = wait_for(lambda: "译球已固定" in logs(), timeout=10)
    L27 = logs()
    assert ok, "E27 FAIL: 拖拽未触发固定（无『译球已固定』日志）\n" + L27[-600:]
    time.sleep(2)
    fr2 = compose_frame()
    assert fr2, "E27 FAIL: 拖后按钮消失"
    assert abs((fr2[0]+fr2[2])//2 - tx) <= 30 and abs((fr2[1]+fr2[3])//2 - ty) <= 30, \
        "E27 FAIL: 拖后 frame 未跟手 " + str(fr2)
    # 杀服务进程重连（窗口重建）→ 固定位必须仍在（SP 恢复分支）
    sh("shell", "kill $(pidof " + PKG_APP + ") 2>/dev/null; true")
    time.sleep(3)
    sh("shell", "am", "start", "-n", HOST_PKG + "/.TestHostActivity")
    ok = wait_for(lambda: compose_frame() is not None, timeout=20)
    assert ok, "E27 FAIL: 重启后按钮未出现"
    fr3 = compose_frame()
    assert abs((fr3[0]+fr3[2])//2 - tx) <= 30 and abs((fr3[1]+fr3[3])//2 - ty) <= 30, \
        "E27 FAIL: 重启后固定位丢失 " + str(fr3)
    # 重锚劫持回归：swipe 触发多轮 scan → 固定位不动（拖拽分支 !composeDragged 生效）
    sh("shell", "input", "swipe", "500", "1200", "500", "700", "300")
    time.sleep(2)
    fr4 = compose_frame()
    assert fr4 and abs(fr4[0] - fr3[0]) <= 6, "E27 FAIL: 固定位被重锚劫持 " + str(fr4)
    # 点一下固定位的球：仍可翻译（E11 语义在用户位复验——热区重建正确）
    sh("shell", "logcat", "-c")
    cx4, cy4 = (fr4[0]+fr4[2])//2, (fr4[1]+fr4[3])//2
    sh("shell", "input", "tap", str(cx4), str(cy4))
    ok = wait_for(lambda: "compose result:" in logs() or "输入框点击译" in logs(), timeout=30)
    assert ok, "E27 FAIL: 固定位点击不生效（热区 miss）\n" + logs()[-500:]
    # 清理用户位（恢复自动锚定，防污染后续/重跑）
    sp_restore = ('<?xml version=\'1\' encoding=\'utf-8\' standalone=\'yes\' ?>\n<map>\n'
          '<string name="apiBase">http://10.0.2.2:8787/v1</string>\n'
          '<string name="apiKey">test-key</string>\n'
          '<string name="model">mock-model</string>\n'
          '<string name="temperature">0.2</string>\n'
          '<boolean name="enabled" value="true" />\n'
          '<boolean name="auto" value="true" />\n'
          '<string name="dict">mixing = 轧轮机\nmixer = 混合桶\nemboss = 压花轮</string>\n'
          '</map>')
    open("/tmp/wtrans_sp_clean.xml", "w").write(sp_restore)
    sh("push", "/tmp/wtrans_sp_clean.xml", "/data/local/tmp/wtrans_sp_clean.xml")
    sh("shell", f"cp /data/local/tmp/wtrans_sp_clean.xml /data/data/{PKG_APP}/shared_prefs/wtrans.xml && chown $(stat -c '%U:%G' /data/data/{PKG_APP}) /data/data/{PKG_APP}/shared_prefs/wtrans.xml && chmod 660 /data/data/{PKG_APP}/shared_prefs/wtrans.xml")
    sh("shell", "kill $(pidof " + PKG_APP + ") 2>/dev/null; true")
    time.sleep(2)
    print("E27 「译」球长按拖拽+持久化+重锚免疫+固定位可点 通过")


    subprocess.run([ADB, "-e", "exec-out", "screencap", "-p"], stdout=open("/tmp/wa_e2e_pass.png", "wb"))
    print("\nALL E2E-A11Y TESTS PASSED (v0.3.6 模拟器)")

if __name__ == "__main__":
    try:
        main()
    except AssertionError as e:
        print("\nE2E-A11Y FAIL:", e)
        subprocess.run([ADB, "-e", "exec-out", "screencap", "-p"], stdout=open("/tmp/wa_e2e_fail.png", "wb"))
        print("截图: /tmp/wa_e2e_fail.png")
        sys.exit(1)
