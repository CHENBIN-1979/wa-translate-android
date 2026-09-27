#!/usr/bin/env python3
"""v0.3.7 二轮语法电池 —— 按 Hy-MT2 官方指令模板（HF README Table + arXiv 2605.22064）。
一轮教训：自由文本条款（自己发明的句式）0/6。官方给了两个正牌槽位：
  Style:           请将以下文本翻译为{lang}。注意翻译的风格要严格符合【{style}】
  Personalization: 【待翻译文本】…【翻译任务】1、偏好 2、偏好 …4、翻译为{lang}（多约束=MT2 主打）
通道：
  BASE   现产（few-shot 4 例 + 英文 INSTR，temp0）
  ZH     仅把 INSTR 换成官方中文（官方推荐中文指令，零条款）→ 中文指令本身就带出中文语序？
  STYLE  官方 Style 槽 + 风格词=工厂维修报告中文习惯
  PERS   官方 Personalization 槽，约束1=后置修饰语前置（通用规则，非词组枚举），约束2=人名/机号原样
  PERS_S 同 PERS 但用官方推荐采样（temp0.7 top_p0.6 top_k20 rep1.05）
题集：6 词序 + 4 人名/机号 = 10 句 × 5 通道 × 2 模型 = 100 请求。"""
import json, sys, time, urllib.request

MT15 = "http://127.0.0.1:8089/v1/chat/completions"
MT2  = "http://127.0.0.1:8090/v1/chat/completions"

FEWSHOT4 = ('Contoh: "Minggu depan tim maintenance akan merapat" -> "下周维修班组将到场"\n'
            'Contoh: "Suhu roller A003 normal" -> "A003辊温正常"\n'
            'Contoh: "Bak limbah penuh" -> "废料池满了"\n'
            'Contoh: "Laporan shift malam sudah dikumpulkan" -> "夜班报告已经交上去了"')
INSTR_EN = "Translate the following segment into Chinese, without additional explanation."
STYLE = "请将以下文本翻译为中文。注意翻译的风格要严格符合【工厂维修报告的中文表达习惯，修饰语在前、中心词在后】"
PREF_ORDER = "印尼语修饰语在名词后面，中文译文必须调整为修饰语在名词前面（如 oli gearbox 译作齿轮箱机油）"
PREF_NAME  = "人名、机器编号与公司名保留原文书写，不音译、不意译（如 Pak Budi、A006、PT Sumatra）"
def pers(src):
    return ("【待翻译文本】\n" + src + "\n\n【翻译任务】\n1、" + PREF_ORDER +
            "\n2、" + PREF_NAME + "\n3、输出保持单行，不加引号\n4、将【待翻译文本】翻译为中文。")

WORD = [  # (src, 判词序正确的函数名 w)
    "A06 pelumasan oli gearbox calender",
    "A01 level oli gearbox calender",
    "A06 level oli gearbox strainer",
    "gearbox mixing bocor",
    "suhu mixing A naik",
    "ganti seal oli gearbox calender hari ini",
]
NAMES = [
    "Pak Budi shift 2 sudah menyerahkan laporan",
    "Tolong sampaikan ke Sri Wahyuni bahwa spare part sudah tiba",
    "Ketua Tan Xiaoping meminta PT Sumatra mengirim barang besok",
    "Agung Salim izin hari ini, gantikan di line 3",
]

def pass_w(src, out):
    if "gearbox mixing" in src:
        return "轧轮机齿轮箱" in out or ("轧轮" in out and "齿轮箱" in out and out.index("轧轮") < out.index("齿轮箱"))
    if "oli gearbox" in src:
        if "齿轮箱" not in out: return False
        i = out.index("齿轮箱")
        m = [j for j in [out.find("机油"), out.find("油位"), out.find("油封"), out.find("油滤"), out.find("润滑")] if j >= 0]
        return bool(m) and i <= min(m)
    if "suhu mixing" in src:
        return "温度" in out and "轧轮" in out and out.index("轧轮") < out.index("温度") + 3
    return "油" in out

def pass_n(src, out):
    keep = {0: ["Budi"], 1: ["Sri Wahyuni", "Wahyuni"], 2: ["Tan Xiaoping", "PT Sumatra"],
            3: ["Agung Salim", "Agung"]}[NAMES.index(src)]
    return any(k in out for k in keep)

def build(cond, src):
    if cond == "BASE":  return f"{FEWSHOT4}\n\n{INSTR_EN}\n\n{src}"
    if cond == "ZH":    return f"{FEWSHOT4}\n\n将以下文本翻译为中文，注意只需要输出翻译后的结果，不要额外解释：\n\n{src}"
    if cond == "STYLE": return f"{FEWSHOT4}\n\n{STYLE}\n\n{src}"
    if cond == "PERS":  return pers(src)
    raise ValueError(cond)

def ask(url, prompt, samp=False):
    payload = {"messages": [{"role": "user", "content": prompt}], "max_tokens": 120}
    if samp:
        payload.update({"temperature": 0.7, "top_p": 0.6, "top_k": 20, "repetition_penalty": 1.05})
    else:
        payload["temperature"] = 0
    req = urllib.request.Request(url, data=json.dumps(payload).encode(),
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.load(r)["choices"][0]["message"]["content"].strip()

def leak(out):  # 槽位文本被复读出来
    return any(m in out for m in ["修饰语", "保留原文书写", "工厂维修报告的风格", "翻译任务", "待翻译文本"])

def main():
    for name, url in [("MT1.5", MT15), ("MT2", MT2)]:
        for cond in ["BASE", "ZH", "STYLE", "PERS"]:
            ws = ns = lk = 0
            lines = []
            for s in WORD:
                o = ask(url, build(cond, s), samp=(cond == "PERS" and name == "MT2"))  # PERS_S 用采样
                p = pass_w(s, o); ws += p; lk += leak(o)
                lines.append(f"   {'✓' if p else '✗'} {s[:26]:26s}→ {o[:64]}")
            for s in NAMES:
                o = ask(url, build(cond, s), samp=(cond == "PERS" and name == "MT2"))
                p = pass_n(s, o); ns += p; lk += leak(o)
                lines.append(f"   {'✓' if p else '✗'} {s[:26]:26s}→ {o[:64]}")
            print(f"== {name}-{cond}: 词序 {ws}/6 人名 {ns}/4 复读 {lk}")
            print("\n".join(lines)); sys.stdout.flush(); time.sleep(0.3)

if __name__ == "__main__":
    main()
