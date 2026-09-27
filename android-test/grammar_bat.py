#!/usr/bin/env python3
"""v0.3.7 语法电池：印尼语后置修饰语→中文前置，四条通道哪个能兑现到 MT 小模型。
A 基线（现产 4 例 few-shot，无词典无条款）
B 词组词典（键=多词短语，走现产「X 翻译成 Y」注入块——今天就能用的通道）
C 抽象语法条款（一句规则描述，追加进指令行尾——人名电池的 CLAUSE 同类，预期复读）
D 词序示例对（few-shot 6 例=现产 4+2 条专门演示修饰语前置；无词典）
判分：齿轮箱/轧轮机 出现在其修饰的中心词"油"之前的句子数 + 复读检测。"""
import json, re, sys, time, urllib.request

MT15 = "http://127.0.0.1:8089/v1/chat/completions"   # hy2b (MT1.5)
MT2  = "http://127.0.0.1:8090/v1/chat/completions"   # MT2 1.25bit

FEWSHOT4 = ('Contoh: "Minggu depan tim maintenance akan merapat" -> "下周维修班组将到场"\n'
            'Contoh: "Suhu roller A003 normal" -> "A003辊温正常"\n'
            'Contoh: "Bak limbah penuh" -> "废料池满了"\n'
            'Contoh: "Laporan shift malam sudah dikumpulkan" -> "夜班报告已经交上去了"')
FEWSHOT6 = FEWSHOT4 + ('\nContoh: "suhu oli gearbox A06 turun" -> "A06齿轮箱机油温度下降"\n'
                       'Contoh: "ganti oli mixing B besok" -> "明天更换轧轮机B机油"')
INSTR = "Translate the following segment into Chinese, without additional explanation."
CLAUSE = "注意：印尼语的修饰语在名词后面，翻译成中文时必须把修饰语放到名词前面。"

SRCS = [
    "A06 pelumasan oli gearbox calender",
    "A01 level oli gearbox calender",
    "A06 level oli gearbox strainer",
    "gearbox mixing bocor",
    "suhu mixing A naik",
    "ganti seal oli gearbox calender hari ini",
]

def pass_check(src, out):
    """词序兑现判据：修饰语(齿轮箱/轧轮机)须先于其中心词；错序/丢失判负。"""
    if "gearbox mixing" in src:
        return ("轧轮机齿轮箱" in out) or ("轧轮" in out and "齿轮箱" in out and out.index("轧轮") < out.index("齿轮箱"))
    if "oli gearbox" in src:
        if "齿轮箱" not in out: return False
        i = out.index("齿轮箱")
        m = [j for j in [out.find("机油"), out.find("油位"), out.find("油封"), out.find("油滤")] if j >= 0]
        return bool(m) and i <= min(m)          # 齿轮箱 在 油X 之前（或相邻前置）
    if "suhu mixing" in src:
        return "温度" in out and ("轧轮" in out or "mixing" in out.lower())
    return "油" in out

def build(cond, src):
    if cond == "A":  return f"{FEWSHOT4}\n\n{INSTR}\n\n{src}"
    if cond == "B":
        d = ("参考下面的翻译：\n"
             "gearbox mixing 翻译成 轧轮机齿轮箱\noli gearbox 翻译成 齿轮箱机油\n"
             "level oli 翻译成 机油油位\ngearbox 翻译成 齿轮箱\nmixing 翻译成 轧轮机\n"
             f"pelumasan 翻译成 润滑\n\n{INSTR}\n\n{src}")
        return d
    if cond == "C":  return f"{FEWSHOT4}\n\n{INSTR}{CLAUSE}\n\n{src}"
    if cond == "D":  return f"{FEWSHOT6}\n\n{INSTR}\n\n{src}"
    raise ValueError(cond)

def ask(url, prompt):
    body = json.dumps({"messages": [{"role": "user", "content": prompt}],
                       "temperature": 0, "max_tokens": 120}).encode()
    req = urllib.request.Request(url, data=body, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.load(r)["choices"][0]["message"]["content"].strip()

def main():
    out = {}
    for name, url in [("MT1.5", MT15), ("MT2", MT2)]:
        for cond in "ABCD":
            res = []
            for s in SRCS:
                try:
                    o = ask(url, build(cond, s))
                except Exception as e:
                    o = f"<ERR {e}>"
                p = pass_check(s, o)
                parrot = ("修饰语" in o) or (INSTR[:20] in o)
                res.append((s[:24], p, parrot, o[:70]))
                time.sleep(0.2)
            won = sum(1 for _, p, _, _ in res if p)
            leak = sum(1 for _, _, q, _ in res if q)
            out[f"{name}-{cond}"] = (won, leak, res)
            print(f"== {name}-{cond}: 词序兑现 {won}/6  条款复读 {leak}/6")
            for s, p, q, o in res:
                print(f"   {'✓' if p else '✗'}{'[复读]' if q else ''} {s}… → {o}")
            sys.stdout.flush()
    print("\n===== 汇总 =====")
    for k, (w, l, _) in out.items():
        print(f"{k}: 兑现 {w}/6 复读 {l}/6")

if __name__ == "__main__":
    main()
