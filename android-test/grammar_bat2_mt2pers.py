#!/usr/bin/env python3
"""补跑 MT2-PERS（官方 Personalization 槽 × MT2，temp0 与采样各一遍防偶然）。"""
import json, sys, urllib.request
sys.path.insert(0, ".")
from grammar_bat2 import build, pass_w, pass_n, leak, WORD, NAMES, pers

MT2 = "http://127.0.0.1:8090/v1/chat/completions"

def ask(prompt, samp):
    payload = {"messages": [{"role": "user", "content": prompt}], "max_tokens": 80}
    if samp:
        payload.update({"temperature": 0.7, "top_p": 0.6, "top_k": 20, "repetition_penalty": 1.05})
    else:
        payload["temperature"] = 0
    req = urllib.request.Request(MT2, data=json.dumps(payload).encode(),
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=90) as r:
        return json.load(r)["choices"][0]["message"]["content"].strip()

for samp in (False, True):
    ws = ns = lk = 0
    print(f"== MT2-PERS temp{'0.7采样' if samp else '=0'}")
    for s in WORD:
        o = ask(pers(s), samp); p = pass_w(s, o); ws += p; lk += leak(o)
        print(f"   {'✓' if p else '✗'}{'[复读]' if leak(o) else ''} {s[:26]:26s}→ {o[:60]}")
    for s in NAMES:
        o = ask(pers(s), samp); p = pass_n(s, o); ns += p; lk += leak(o)
        print(f"   {'✓' if p else '✗'}{'[复读]' if leak(o) else ''} {s[:26]:26s}→ {o[:60]}")
    print(f"== MT2-PERS samp={samp}: 词序 {ws}/6 人名 {ns}/4 复读 {lk}")
    sys.stdout.flush()
