#!/usr/bin/env python3
"""三轮电池：官方 Context(背景槽)/Delimiters 模板 × MT1.5/MT2。
问题：自动调度可行性 —— 背景槽是否兑现、是否复读、是否误伤简单句。"""
import json, urllib.request, concurrent.futures

MT15 = "http://127.0.0.1:8089/v1/chat/completions"
MT2 = "http://127.0.0.1:8090/v1/chat/completions"
SAMP = {"temperature": 0.7, "top_p": 0.6, "top_k": 20, "repetition_penalty": 1.05}

def ask(url, prompt):
    p = {"messages": [{"role": "user", "content": prompt}], "max_tokens": 100}
    p.update(SAMP)
    req = urllib.request.Request(url, data=json.dumps(p).encode(),
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=120) as r:
        return json.load(r)["choices"][0]["message"]["content"].strip()

# ---- 句子集 ----
Q2_SRC = "Buang angin D007"          # 真机答非所问案例
Q2_PREV = "Tolong review video maintenance mesin D007 pagi ini"
WORD = [
    "A06 pelumasan oli gearbox calender",
    "A01 level oli gearbox calender",
    "gearbox mixing bocor",
    "suhu mixing A naik",
    "ganti seal oli gearbox calender hari ini",
    "filter udara mesin emboss kotor",
]
NAMES = [
    "Pak Budi shift 2 sudah menyerahkan laporan",
    "Ketua Tan Xiaoping meminta PT Sumatra mengirim barang besok",
]
DELIM = [
    "Rapat *jam 3* di ruang _meeting_ lantai 2",
    "Penting: ~check list~ mesin A06 *hari ini*",
]

# ---- 通道（全部官方模板原文格式） ----
def base(s):
    return ("Terjemahkan kalimat bahasa Indonesia berikut ke bahasa Mandarin. "
            "Hanya berikan terjemahannya saja. \n\n" + s)

def bg_rule(s):
    # 官方 Structured Data 2 背景槽，塞抽象语法说明
    return ("【背景信息】\n印尼语工厂聊天。印尼语修饰语在名词后面，中文必须前置。\n\n"
            "请结合背景信息将以下文本翻译为中文。\n\n【待翻译文本】\n" + s)

def bg_domain(s):
    # 官方背景槽，塞具体设备词汇表（一整块，不是逐短语枚举）
    return ("【背景信息】\n印南厂设备维护群。设备词汇：gearbox=齿轮箱, mixing=轧轮机, "
            "calender=压延机, emboss=压花轮, oli=机油, seal=油封, buang angin=排气。\n\n"
            "请结合背景信息将以下文本翻译为中文。\n\n【待翻译文本】\n" + s)

def bg_ctx(s, prev):
    # 官方背景槽 = 真实上文（自动调度设计的原型）
    return ("【背景信息】\n上一条消息：" + prev + "\n\n"
            "请结合背景信息将以下文本翻译为中文。\n\n【待翻译文本】\n" + s)

def delim(s):
    # 官方 Delimiters 模板
    return ("请将以下文本准确翻译为中文。\n你必须在译文中保留等量的分隔符，"
            "绝对不可遗漏、转义或翻译该符号，并注意分隔符的位置。\n\n" + s)

def echo_check(o):
    return any(m in o for m in ["背景信息", "待翻译文本", "修饰语在名词", "设备词汇", "上一条消息"])

def pass_w(s, o):
    if "gearbox mixing" in s: return "轧轮机齿轮箱" in o or ("齿轮箱" in o and "轧轮" in o)
    if "oli gearbox" in s:
        if "齿轮箱" not in o: return False
        i = o.index("齿轮箱")
        m = [j for j in [o.find(x) for x in ["机油", "油位", "油封", "润滑"]] if j >= 0]
        return bool(m) and i <= min(m)
    if "suhu mixing" in s: return "温度" in o and "轧轮" in o
    if "emboss" in s: return ("压花" in o or "emboss" in o) and "滤" in o
    if "seal oli gearbox" in s: return "齿轮箱" in o and o.index("齿轮箱") <= o.find("油封")
    return False

def main():
    for tag, url in (("MT1.5", MT15), ("MT2", MT2)):
        print(f"\n######## {tag} ########", flush=True)
        # 1) Q2 答非所问：背景槽能否救
        for name, prompt in (
            ("BASE", base(Q2_SRC)),
            ("BG-CTX", bg_ctx(Q2_SRC, Q2_PREV)),
            ("BG-DOMAIN", bg_domain(Q2_SRC)),
        ):
            o = ask(url, prompt)
            good = ("排气" in o or "放气" in o) and "别担心" not in o
            print(f"  [{name:9s}] Q2 {'OK' if good else 'X '} echo={echo_check(o)} → {o[:70]}", flush=True)
        # 2) 词序句 + 背景槽是否误伤
        for name, fn in (("BG-RULE", bg_rule), ("BG-DOMAIN", bg_domain)):
            hits = echos = 0
            for s in WORD:
                o = ask(url, fn(s))
                k = pass_w(s, o); hits += k; echos += echo_check(o)
                print(f"  [{name:9s}] {'OK' if k else 'X '} {s[:32]:32s} → {o[:56]}", flush=True)
            print(f"  -- {tag}/{name}: 词序 {hits}/6 复读 {echos}", flush=True)
        # 3) 人名 + 背景槽
        for name, fn in (("BG-DOMAIN", bg_domain),):
            for s in NAMES:
                o = ask(url, fn(s))
                keep = "Budi" if "Budi" in s else "Tan Xiaoping"
                print(f"  [{name:9s}] NAME {'OK' if keep in o else 'X '} {s[:30]:30s} → {o[:56]}", flush=True)
        # 4) Delimiters
        for s in DELIM:
            o = ask(url, delim(s))
            stars = s.count("*") == o.count("*")
            unders = s.count("_") == o.count("_")
            tildes = s.count("~") == o.count("~")
            print(f"  [DELIM    ] {'OK' if stars and unders else 'X '} {s[:34]:34s} → {o[:56]}", flush=True)

if __name__ == "__main__":
    main()
