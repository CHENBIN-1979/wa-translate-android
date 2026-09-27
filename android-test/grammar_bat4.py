#!/usr/bin/env python3
"""v0.3.7 四轮电池 —— 按 chenbin 9/25 指令：
 1) 全部通道改用官方标准模板（中文，README 表格原文）+ 官方推荐采样(temp0.7 top_p0.6 top_k20 rep1.05)
 2) 词典全量注入 vs 现产按需提取：效果 + 速度（timings 取 prompt_n/predicted_n/耗时）
    附带复核提取匹配器本身有没有漏（提取结果 vs 句子实际包含的词条）
 3) Style 槽按用户原话写：注意翻译的风格要严格符合【印尼语语法】（另加一条展开变体对照）
 5) Delimiters 复查：官方模板原文逐字 + 简单/多类型两组用例 + 无指令基线对照
 4/6/7 不采用 → 不测。
两模型：8089 MT1.5-2bit / 8090 MT2-1.25bit，串行跑（并行会污染速度数据）。
"""
import json, re, statistics, time, urllib.request

URLS = {"MT1.5": "http://127.0.0.1:8089/v1/chat/completions",
        "MT2":   "http://127.0.0.1:8090/v1/chat/completions"}
SAMP = {"temperature": 0.7, "top_p": 0.6, "top_k": 20, "repetition_penalty": 1.05}

# ---------- 官方模板（README 表格原文，target 全称） ----------
def off_default(src):
    return "将以下文本翻译为中文，注意只需要输出翻译后的结果，不要额外解释：\n\n" + src
def off_terminology(src, pairs):
    refs = "\n".join(f"{k} 翻译成 {v}" for k, v in pairs)
    return ("参考下面的翻译：\n" + refs + "\n"
            + off_default(src))
FEWSHOT4 = ('Contoh: "Minggu depan tim maintenance akan merapat" -> "下周维修班组将到场"\n'
            'Contoh: "Suhu roller A003 normal" -> "A003辊温正常"\n'
            'Contoh: "Bak limbah penuh" -> "废料池满了"\n'
            'Contoh: "Laporan shift malam sudah dikumpulkan" -> "夜班报告已经交上去了"')
def off_fs_dict(src, pairs):          # 现产布局（示例在前，词典块居中，指令紧邻 src）
    refs = "\n".join(f"{k} 翻译成 {v}" for k, v in pairs)
    return (FEWSHOT4 + "\n\n参考下面的翻译：\n" + refs + "\n\n"
            + "将以下文本翻译为中文，注意只需要输出翻译后的结果，不要额外解释：\n\n" + src)
def style_lit(src):                    # 用户原话版
    return ("请将以下文本翻译为中文。\n注意翻译的风格要严格符合【印尼语语法】\n\n" + src)
def style_exp(src):                    # 展开变体（把语法点说明白）
    return ("请将以下文本翻译为中文。\n注意翻译的风格要严格符合"
            "【印尼语语法——修饰语后置于名词，翻译时按中文语序重排】\n\n" + src)
def delim(src):                        # 官方 Delimiters 模板逐字（仅去 markdown 强调星号）
    return ("请将以下文本准确翻译为中文。\n你必须在译文中保留等量的分隔符，"
            "绝对不可遗漏、转义或翻译该符号，并注意分隔符的位置。\n\n" + src)

# ---------- 词典（按会话史重建的 26 条，≈手机实机集） ----------
DICT = [
    ("mixing", "轧轮机"), ("mixer", "混合桶"), ("emboss", "压花辊"),
    ("calender", "压延机"), ("bahan baku", "原材料"), ("guide roll", "预热轮"),
    ("heater tank", "加热器"), ("CCD", "瑕疵检出机"), ("forklift", "叉车"),
    ("banbury", "万马力"), ("winder", "卷取机"), ("oli", "润滑油"),
    ("pelumasan", "润滑"), ("viskosity", "粘度"), ("kerlip", "亮斑"),
    ("ngeblink", "反光斑"), ("ketombean", "屑状白点"), ("conveyor", "输送机"),
    ("macet", "卡停"), ("bikin", "造成"), ("merapat", "到场"),
    ("epc mati", "EPC停机"), ("surat resmi", "正式函"), ("sparepart", "备件"),
    ("dialirkan", "输送"), ("scanner", "测厚仪"),
]

# ---------- 提取匹配器：复刻 Kotlin matchedDict/containsTerm ----------
def is_word_c(c): return c.isalnum() and c.isascii()
def contains_term(hay, term):
    hay = hay.lower(); term = term.lower()
    if not term: return False
    if not any(is_word_c(c) for c in term):
        return len(term) >= 2 and term in hay
    i = hay.find(term)
    while i >= 0:
        ok_b = i == 0 or not is_word_c(hay[i-1])
        j = i + len(term)
        ok_a = j >= len(hay) or not is_word_c(hay[j])
        if ok_b and ok_a: return True
        i = hay.find(term, i+1)
    if len(term) >= 5:
        for L in range(len(term)-1, max(3, len(term)-2)-1, -1):
            stem = term[:L]
            i = hay.find(stem)
            while i >= 0:
                ok_b = i == 0 or not is_word_c(hay[i-1])
                j = i + len(stem)
                ok_a = j >= len(hay) or not is_word_c(hay[j])
                if ok_b and ok_a: return True
                i = hay.find(stem, i+1)
    return False
def extract(src):
    return [(k, v) for k, v in DICT if contains_term(src, k)]

# ---------- 题集 ----------
WORD = [
    ("A06 pelumasan oli gearbox calender", lambda o: "齿轮箱" in o and "压延" in o and "润滑油" in o),
    ("A01 level oli gearbox calender",     lambda o: "压延" in o and ("油位" in o or "液位" in o)),
    ("gearbox mixing bocor",               lambda o: "轧轮机" in o),
    ("suhu mixing A naik",                 lambda o: "轧轮机" in o and ("温度" in o or "温" in o)),
    ("ganti seal oli calender hari ini",   lambda o: "压延" in o and "润滑油" in o),
    ("filter udara mesin emboss kotor",    lambda o: "压花辊" in o),
]
NAMES = [
    ("Pak Budi shift 2 sudah menyerahkan laporan", lambda o: "Budi" in o),
    ("Tolong sampaikan ke Sri Wahyuni bahwa spare part sudah tiba", lambda o: "Wahyuni" in o),
    ("Ketua Tan Xiaoping meminta PT Sumatra mengirim barang besok", lambda o: "Tan Xiaoping" in o or "PT Sumatra" in o),
    ("Agung Salim izin hari ini, gantikan di line 3", lambda o: "Agung" in o),
]
TERM = [  # 术语强制（词典值必须原样出现）
    ("Forklif parkir di dekat banbury",        ["叉车", "万马力"], "提取器截尾变体+Forklif"),
    ("CCD alarm saat ganti bahan baku",        ["瑕疵检出机", "原材料"], "CCD+bahan baku 多词键"),
    ("epc mati di line winder 2",              ["EPC停机", "卷取机"], "多词键+短语"),
    ("mesin emboss kerlip dan ngeblink",       ["压花辊", "亮斑", "反光斑"], "三词条一句"),
    ("conveyor macet, sparepart belum datang", ["输送机", "卡停", "备件"], "三词条一句"),
    ("surat resmi dari supplier sudah diterima", ["正式函"], "书面词"),
]
DELIMS = [
    ("Mesin %@ harus dimatikan sekarang",       ["%@"]),                      # 简单单符号
    ("Rapat *jam 3* di ruang _meeting_ lantai 2", ["*", "_"]),                # WA 加粗+斜体
    ("Penting: ~check list~ mesin A06 *hari ini*", ["~", "*"]),               # 删除线+加粗
]

def ask(url, prompt):
    p = {"messages": [{"role": "user", "content": prompt}], "max_tokens": 100}
    p.update(SAMP)
    req = urllib.request.Request(url, data=json.dumps(p).encode(),
                                 headers={"Content-Type": "application/json"})
    t0 = time.time()
    with urllib.request.urlopen(req, timeout=180) as r:
        d = json.load(r)
    el = time.time() - t0
    m = d["choices"][0]["message"]["content"].strip()
    tm = d.get("timings", {})
    return m, el, tm.get("prompt_n", 0), tm.get("predicted_n", 0)

ECHO_KEYS = ["翻译为中文", "参考下面的翻译", "印尼语语法", "修饰语后置", "分隔符",
             "保留等量", "严格符合", "翻译任务", "待翻译文本", "不需要额外解释",
             "只需要输出", "Contoh", "废料池满了", "辊温正常", "夜班报告"]
def echo(o): return any(k in o for k in ECHO_KEYS)

def run_model(tag, url):
    print(f"\n######## {tag} 官方采样 ########", flush=True)
    for name, fn_pairs in [
        ("OFFICIAL", lambda s, src=None: off_default(s)),
        ("FS+DICT-CUR", None),   # handled specially
        ("FS+DICT-FULL", lambda s, src=None: off_fs_dict(s, DICT)),
        ("TERM-FULL", lambda s, src=None: off_terminology(s, DICT)),
        ("STYLE-LIT", lambda s, src=None: style_lit(s)),
        ("STYLE-EXP", lambda s, src=None: style_exp(s)),
    ]:
        if fn_pairs is None:
            f = lambda s, src=None: off_fs_dict(s, extract(src))
        else:
            f = fn_pairs
        ws = ns = ts = lk = 0
        tms, ptok, ctok = [], [], []
        for s, chk in WORD:
            o, el, pn, cn = ask(url, f(s, src=s)); ws += chk(o); lk += echo(o)
            tms.append(el); ptok.append(pn); ctok.append(cn)
            print(f"  [{name:12s}] W {'OK' if chk(o) else 'X '} {el:5.1f}s p{pn:4d} {s[:30]:30s}→ {o[:52]}", flush=True)
        for s, chk in NAMES:
            o, el, pn, cn = ask(url, f(s, src=s)); ns += chk(o); lk += echo(o)
            tms.append(el); ptok.append(pn); ctok.append(cn)
            print(f"  [{name:12s}] N {'OK' if chk(o) else 'X '} {el:5.1f}s p{pn:4d} {s[:30]:30s}→ {o[:52]}", flush=True)
        for s, vals, note in TERM:
            o2, el, pn, cn = ask(url, f(s, src=s))
            hit = all(v in o2 for v in vals)
            ts += hit; lk += echo(o2)
            tms.append(el); ptok.append(pn); ctok.append(cn)
            print(f"  [{name:12s}] T {'OK' if hit else 'X '} {el:5.1f}s p{pn:4d} {s[:30]:30s}→ {o2[:52]}", flush=True)
        med = statistics.median(tms) if tms else 0
        print(f"  == {tag}/{name}: 词序{ws}/6 人名{ns}/4 术语{ts}/6 复读{lk} 中位{med:.1f}s 预填充{int(statistics.median(ptok))}tok", flush=True)
    # DELIM 三通道对照（OFFICIAL vs DELIM 指令）
    for s, syms in DELIMS:
        for aname, prompt in (("OFF", off_default(s)), ("DELIM", delim(s))):
            o, el, pn, cn = ask(url, prompt)
            ok = all(o.count(c) == s.count(c) for c in syms) if aname == "OFF" else \
                 (all(o.count(c) == s.count(c) for c in syms) and not echo(o))
            print(f"  [{aname:5s}] D {'OK' if ok else 'X '} echo={echo(o)} {s[:34]:34s}→ {o[:56]}", flush=True)

def main():
    # 提取器复核（不请求模型，纯逻辑）
    print("### 提取器复核：句子→应命中 vs matchedDict 结果")
    for pool in (WORD, NAMES):
        for s, _ in pool:
            print(f"  {s[:38]:38s} → {extract(s) if extract(s) else '∅'}")
    for s, _, note in TERM:
        got = extract(s)
        exp = [k for k, v in DICT if any(v in note.split("、") for _ in [1]) and contains_term(s, k)]
        print(f"  {s[:38]:38s} → {got}  ({note})")
    for tag, url in URLS.items():
        run_model(tag, url)

if __name__ == "__main__":
    main()
