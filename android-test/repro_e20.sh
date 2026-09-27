#!/usr/bin/env bash
# E20 假死锁复现：完整不清 logcat 走一遍 A(泄漏拒写)->B(冷却)->C(恢复写回)->D(还原)->E(再点=cache hit 写回)
# 每一步前后 dump 输入框真实文本 + 服务 compose 状态日志。仅诊断用，不改任何代码。
ADB=/home/chenbin/Android/platform-tools/adb
MOCK=http://127.0.0.1:8787
PKG=id.co.watranslate
HOST=$PKG.testhost

edtext() {   # uiautomator dump 抓 EditText 当前文本（长度+前20字）
  $ADB -e shell uiautomator dump /sdcard/rr.xml >/dev/null 2>&1
  $ADB -e shell cat /sdcard/rr.xml | python3 -c "
import sys, re
xml = sys.stdin.read()
for m in re.finditer(r'<node[^>]*>', xml):
    t = m.group(0)
    cls = re.search(r'class=\"([^\"]*)\"', t)
    if cls and 'EditText' in cls.group(1):
        txt = re.search(r'text=\"([^\"]*)\"', t)
        v = txt.group(1) if txt else ''
        print(f'  EditText: len={len(v)} head=\"{v[:20]}\"')
"
}
btnframe() {   # 「译」按钮方块窗口中心（<200px 见 e2e compose_frame20）
  $ADB -e shell dumpsys window windows | python3 -c "
import sys, re
for g in re.finditer(r'id\.co\.watranslate, frame=\[(\d+),(\d+),(\d+),(\d+)\]', sys.stdin.read()):
    l, t, r, b = map(int, g.groups())
    if r - l < 200 and b - t < 200:
        print((l + r) // 2, (t + b) // 2); break
"
}
tapbtn() { local xy=$(btnframe); [ -z "$xy" ] && { echo "  !! 按钮不见了"; return; }; echo "  tap 译球 @$xy"; $ADB -e shell input tap $xy; }
mark() { echo; echo "===== $1 ====="; }

curl -s "$MOCK/setecho?on=0" >/dev/null; curl -s "$MOCK/setleak?on=0" >/dev/null
$ADB -e shell am force-stop $HOST
$ADB -e shell am start -n $HOST/.TestHostActivity >/dev/null; sleep 2
$ADB -e shell input keyevent 111; sleep 1   # 收可能的遗留键盘
$ADB -e shell am broadcast -a $PKG.SETINPUT --es text "'库存已确认，明天可以装柜'" >/dev/null
$ADB -e shell am start -n $HOST/.TestHostActivity >/dev/null; sleep 2
$ADB -e logcat -c

mark "SETINPUT 后（期望=中文12字）"; edtext

# C: 正常态译 → 写回✓
tapbtn; sleep 4
mark "C 译一次后（期望=印尼文35字）"; edtext
$ADB -e logcat -d -s wTranslate | grep -E "compose start|写回|还原|拒写|compose btn|removed|detach" | sed 's/^/  /'

# D: 再点 → 还原↩
tapbtn; sleep 4
mark "D 再点后（期望=中文12字）"; edtext
$ADB -e logcat -d -s wTranslate | grep -E "compose start|写回|还原|拒写|compose btn|removed|detach" | sed 's/^/  /'

# E: 第三次点 → 期望 cache hit 中文→印尼 写回✓
tapbtn; sleep 5
mark "E 第三点后（期望=印尼文35字）"; edtext
$ADB -e logcat -d -s wTranslate | grep -E "compose start|写回|还原|拒写|冷却|compose btn|removed|detach|直发|single" | sed 's/^/  /'
echo; echo "完整 compose 相关日志（无过滤尾 40 行）:"
$ADB -e logcat -d -s wTranslate | grep -vE "event pkg|scan cands|drop |measure" | tail -40 | sed 's/^/  /'
