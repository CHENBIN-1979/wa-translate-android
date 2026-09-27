#!/usr/bin/env python3
"""e2e_local_E23.py — M3 本地引擎 e2e（debug APK x86_64 + 真实 hy125.gguf）。

前提：模拟器 + adb root；mock :8787 在跑（仅用于断言「零云端流量」，不依赖其返回）。
链路：push hy125.gguf → app 私有 models/ + SP(engineMode=local, localModel=hy125, model_ok) →
     install debug（x86_64 native）+ testhost 默认种子（2 条对方印尼语 auto 翻译）→
     断言：①「本地命中」≥2（走 LocalEngine 非云）② mock_bodies.log 零增长
           ③ 译文行 inject ≥2 ④ 无「本地引擎未就绪」。
"""
import subprocess, sys, time, re, os

ADB = "/home/chenbin/Android/platform-tools/adb"
PKG_APP = "id.co.watranslate"
SVC = PKG_APP + "/.WaTranslateService"
HOST_PKG = "id.co.watranslate.testhost"
SHA125 = "cc497fe8f033b52b3b8b00a7669e9661435432f9d4cd43f7ed24400c01507a93"   # v0.3.6: MT2-1.25Bit 正品件
MODEL_FILE = "Hy-MT2-1.8B-1.25Bit.gguf"

_here = os.path.dirname(os.path.abspath(__file__))
DEBUG_APK = os.path.abspath(os.path.join(_here, "../app/build/outputs/apk/debug/app-debug.apk"))
HOST_APK = os.path.abspath(os.path.join(_here, "../testhost/build/outputs/apk/debug/testhost-debug.apk"))
SRC_MODEL = "/home/chenbin/.hermes/workspace/hy-mt-m0/hymt2_125.gguf"   # v0.3.6: MT2 正品件（设备侧 tmp 名沿用 hy125，cp 后即 MODEL_FILE）


def sh(*a):
    return subprocess.run([ADB, "-e"] + list(a), capture_output=True, text=True).stdout


def logs():
    return sh("logcat", "-d", "-s", "wTranslate")


def wait_pid():
    t0 = time.time()
    while time.time() - t0 < 25:
        if sh("shell", "pidof", PKG_APP).strip():
            return True
        time.sleep(2)
    return False


def count_local_hits():
    return len(re.findall(r"本地命中:", logs()))


def wait_for(pred, timeout=60):
    t0 = time.time()
    while time.time() - t0 < timeout:
        try:
            if pred():
                return True
        except Exception:
            pass
        time.sleep(2)
    return False


def reseed():
    subprocess.run([ADB, "-e", "shell", f"kill $(pidof {PKG_APP}) 2>/dev/null; true"], capture_output=True)
    time.sleep(2)
    subprocess.run([ADB, "-e", "shell", f"rm -f /data/data/{PKG_APP}/shared_prefs/wtrans_cache_v5.xml /data/data/{PKG_APP}/shared_prefs/wtrans_cache_v4.xml /data/data/id.co.watranslate/shared_prefs/wtrans_cache_v3.xml /data/data/id.co.watranslate/shared_prefs/wtrans_cache_v2.xml /data/data/{PKG_APP}/shared_prefs/wtrans_cache.xml"], capture_output=True)
    subprocess.run([ADB, "-e", "shell", "am", "force-stop", HOST_PKG], capture_output=True)
    sh("logcat", "-c")
    subprocess.run([ADB, "-e", "install", "-r", DEBUG_APK], capture_output=True)
    time.sleep(2)
    subprocess.run([ADB, "-e", "shell", "am", "start", "-n", HOST_PKG + "/.TestHostActivity"], capture_output=True)
    time.sleep(6)
    subprocess.run([ADB, "-e", "shell", "am", "broadcast", "-a", "id.co.watranslate.SEED"], capture_output=True)
    time.sleep(2)
    sh("logcat", "-c")


def main():
    for p in (DEBUG_APK, HOST_APK, SRC_MODEL):
        assert os.path.exists(p), "缺少文件: " + p

    # ---- mock 流量基线（先于一切）----
    body_log = "/tmp/wt-test/mock_bodies.log"
    base_len = os.path.getsize(body_log) if os.path.exists(body_log) else 0

    # ---- 安装 + 重绑（首条本地翻译 = 模型 mmap 加载秒级 + x86_64 软件 CPU 推理分钟级，给足 5min）----
    def install_fresh(path):
        ir = subprocess.run([ADB, "-e", "install", "-r", path], capture_output=True, text=True)
        if "Success" in ir.stdout + ir.stderr:
            return
        # 签名不匹配（模拟器残留 release 包）→ uninstall 后 fresh（测试模拟器专属套路；
        # uninstall 会清 app 数据 → 模型/SP 注入必须放在安装【之后】）
        subprocess.run([ADB, "-e", "uninstall", PKG_APP], capture_output=True)
        ir2 = subprocess.run([ADB, "-e", "install", "-g", path], capture_output=True, text=True)
        assert "Success" in ir2.stdout + ir2.stderr, "debug install 失败: " + (ir2.stdout + ir2.stderr).strip()[:300]

    install_fresh(DEBUG_APK)
    irth = subprocess.run([ADB, "-e", "install", "-r", HOST_APK], capture_output=True, text=True)
    assert "Success" in irth.stdout + irth.stderr, "testhost install 失败: " + (irth.stdout + irth.stderr).strip()[:300]

    # ---- 模型入 app 私有 models/（root cp + chown；在 install 之后，防 uninstall 抹数据）----
    files_models = "/data/data/" + PKG_APP + "/files/models"
    # 缓存判断查【字节数】而非仅存在性——9/24 实证：一次 push 截断（少 96B）后，
    # 残缺 tmp 被每轮无声 cp 回 app 目录，isReady() 尺寸+sha 双判永不过 →
    # E23 三轮 hits=0，报错文案只说「未下载或未校验」，绕一圈才定位。
    expect = os.path.getsize(SRC_MODEL)
    got = subprocess.run([ADB, "-e", "shell", "stat -c '%s' /data/local/tmp/hy125.gguf 2>/dev/null"],
                         capture_output=True, text=True).stdout.strip()
    if got != str(expect):
        subprocess.run([ADB, "-e", "shell", "rm -f /data/local/tmp/hy125.gguf"], capture_output=True)
        subprocess.run([ADB, "-e", "push", SRC_MODEL, "/data/local/tmp/hy125.gguf"], capture_output=True)
    subprocess.run([ADB, "-e", "shell",
                    f"mkdir -p {files_models} && cp /data/local/tmp/hy125.gguf {files_models}/{MODEL_FILE}"
                    f" && chown $(stat -c '%U:%G' /data/data/{PKG_APP}) {files_models}/{MODEL_FILE}"],
                   capture_output=True)

    # ---- SP 注入：local 模式 + 就绪标记 + mock api（兜底用，断言它没被访问）----
    sp = ('<?xml version=\'1\' encoding=\'utf-8\' standalone=\'yes\' ?>\n<map>\n'
          '<string name="apiBase">http://10.0.2.2:8787/v1</string>\n'
          '<string name="apiKey">test-key</string>\n'
          '<string name="model">mock-model</string>\n'
          '<string name="temperature">0.2</string>\n'
          '<boolean name="enabled" value="true" />\n'
          '<boolean name="auto" value="true" />\n'
          '<string name="dict">mixing = 轧轮机</string>\n'
          '<string name="engineMode">local</string>\n'
          '<string name="localModel">hy125</string>\n'
          f'<string name="model_ok_hy125">{SHA125}</string>\n'
          '</map>')
    open("/tmp/wtrans_sp_e23.xml", "w").write(sp)
    subprocess.run([ADB, "-e", "push", "/tmp/wtrans_sp_e23.xml", "/data/local/tmp/wtrans_sp.xml"], capture_output=True)
    subprocess.run([ADB, "-e", "shell",
                    f"mkdir -p /data/data/{PKG_APP}/shared_prefs && cp /data/local/tmp/wtrans_sp.xml "
                    f"/data/data/{PKG_APP}/shared_prefs/wtrans.xml && chown $(stat -c '%U:%G' /data/data/{PKG_APP}) "
                    f"/data/data/{PKG_APP}/shared_prefs/wtrans.xml && chmod 660 /data/data/{PKG_APP}/shared_prefs/wtrans.xml"],
                   capture_output=True)
    subprocess.run([ADB, "-e", "shell", f"rm -f /data/data/{PKG_APP}/shared_prefs/wtrans_cache_v5.xml /data/data/{PKG_APP}/shared_prefs/wtrans_cache_v4.xml /data/data/id.co.watranslate/shared_prefs/wtrans_cache_v3.xml /data/data/id.co.watranslate/shared_prefs/wtrans_cache_v2.xml /data/data/{PKG_APP}/shared_prefs/wtrans_cache.xml"], capture_output=True)
    subprocess.run([ADB, "-e", "shell", "rm -f /data/local/tmp/wt-local-smoke /data/local/tmp/wt-model-smoke"], capture_output=True)

    for attempt in range(3):
        subprocess.run([ADB, "-e", "shell", "settings", "put", "secure", "enabled_accessibility_services", SVC], capture_output=True)
        subprocess.run([ADB, "-e", "shell", "settings", "put", "secure", "accessibility_enabled", "1"], capture_output=True)
        time.sleep(3)
        assert wait_pid(), "服务进程未启动"
        reseed()
        # v0.3.3 修：等全部 3 条种子译完再断言（run1 竞态——hits=2 即退出时 mixing/轧轮
        # 那条还没出 result，③b 误 FAIL；完成顺序不固定，实测 Selamat→PakChen→Baiklah）
        if wait_for(lambda: count_local_hits() >= 3, timeout=400):
            break
        print("DBG attempt", attempt + 1, "hits=", count_local_hits(),
              "last=", logs().strip().splitlines()[-1][:100] if logs().strip() else "-")

    lg = logs()
    print("E23 本地命中行数:", count_local_hits())
    for x in re.findall(r"row result: (.*?) -> (.*)", lg):
        print("   row:", x[0][:30], "→", x[1][:40])

    # ① 走了本地引擎（非云端）
    assert count_local_hits() >= 2, f"E23 FAIL: 本地命中 <2 → {lg[-800:]}"
    # ② 零云端流量（local 模式不该碰 mock）
    now_len = os.path.getsize(body_log) if os.path.exists(body_log) else 0
    assert now_len == base_len, f"E23 FAIL: mock 流量增长 {now_len - base_len}B（本地模式不该走云）"
    # ③ 译文真实回写（row result = 译文行内容；比 inject row 更直接——慢推理下 inject
    #    落在 reseed 清 logcat 之前，时序不可靠，故以 row result 为准）
    rr = re.findall(r"row result: (.*?) -> (.*)", lg)
    assert len(rr) >= 2, f"E23 FAIL: row result <2 → {lg[-800:]}"
    # ③b 词典术语介入生效：mixing machine 那条必须译出词典值「轧轮」（prompt 官方介入模板方向
    #     修正后的实锤判据；run-3 方向反了时模型译为「混合机」即 FAIL）
    assert any("轧轮" in rr_[1] for rr_ in rr), f"E23 FAIL: 词典术语未介入 → {rr}"
    # ④ 引擎就绪（未走失败分支）
    assert "本地引擎未就绪" not in lg, "E23 FAIL: 引擎未就绪 → " + lg[-500:]
    print("\nE23 本地引擎 e2e（x86_64 native + hy125 真实 GGUF）通过")


if __name__ == "__main__":
    try:
        main()
    except AssertionError as e:
        print("\nE23 FAIL:", e)
        subprocess.run([ADB, "-e", "exec-out", "screencap", "-p"], stdout=open("/tmp/wa_e23_fail.png", "wb"))
        print("logcat 尾部:\n" + logs()[-1500:])
        sys.exit(1)
