from http.server import BaseHTTPRequestHandler, HTTPServer
import json
import time

FAIL = {'on': False}
BADLEN = {'on': False}
DELAY = {'sec': 0}
REJECT400 = {'on': False}
ECHO = {'on': False}
ECHO_BATCH_ONLY = {'on': False}
LEAK = {'on': False}  # v0.2.5 E20: MiniMax 思考泄漏复刻（真机 07:23 形状）
HITS = {'n': 0}
LEAK_PREFIX = ('The user wants me to translate this WhatsApp message. I need to keep the tone casual. '
               '用户要求我把这条消息翻译成印尼语，我先理解语境再翻。\n\n')
DICT_ID2ZH = {
    'LAPORAN KERUSAKAN\nPetugas : Agung K,rg.Ary P,P,Rully\nMesin : A06\nBagian kerusakan : root blower\nWaktu kerusakan : 08.30-10.00 ( perbaikan bersamaan ganti embos )\nKerusakan : root blower suara kasar\nCara perbaikan : ganti root blower': '损坏报告\n人员：Agung K、Ary P、P、Rully\n机台：A06\n损坏部位：root blower（根式鼓风机）\n故障时间：08.30-10.00（维修同时更换压花辊）\n损坏情况：root blower 声音粗糙\n维修方式：更换 root blower',
    'Selamat pagi, berapa harga 10 kilogram hari ini?': '早上好，今天10公斤多少钱？',
    'Terima kasih': '谢谢',
    # v0.1.9 e2e E6：doRefresh 走 translateSingle（非批量），mock 需有词条否则返回 ZH[原文] 兜底
    'Saya akan lembur malam ini bersama tim': '今晚我和团队一起加班',
    # v0.2.0 E12：SETINPUT 全文用例词条（否则 ZH[原文] 兜底被回显防护拦下）
    'Selamat pagi Pak Chen hari ini baik': '陈总早上好，今天天气好',
    'Baiklah': '好的',
    'Besok kirim': '明天发货',
    'Stock PVC 4/9': 'PVC 库存 4/9',
    'Laporan bahan baku yg masuk hari ini..': '今天入库的原材料报告..',
    'Saya cek dimaterial tidak ada Mr.Chen': '我在物料里查了，没有 Chen 先生',
    'Selamat pagi': '早上好',
    'utamakan sholat': '优先礼拜',
    'sinae turu bae (Fauzan & farlin)': '睡觉就好 (Fauzan & farlin)',
    'Nggak ada di kamus stub': 'ZH[Nggak ada di kamus stub]',
    'Selamat pagi Pak, laporan shift malam sudah saya kirim': '早上好先生，夜班报告我已经发了',
    'Baiklah, nanti saya cek bagian mixing machine dulu': '好的，回头我先检查轧轮机部分',
    'Pak Chen, bahan baku CCD scanner datang jam 10 pagi': '陈总，CCD 瑕疵检出机原材料10点到',
    # v0.2.2 e2e E16：12 行长译文（> maxLines=10 → measure 截断必须触发）
    # v0.2.4 e2e E18：点击输入框文字 → 「译」写回用例（印尼文原文→中文，必须在 ID2ZH）
    'Pak tolong cek stok gudang B': '请帮我查一下B仓库存',
    # v0.2.7 e2e E21：仿 WhatsApp 引用树正文（无词条 → ZH[原文] 兜底会被回显链拦成 keep，
    # 断言失真——必须给真译文）
    'Baik terimakasih informasinya Bu Marni': '好的，谢谢您分享的信息，Marni女士',
    # v0.2.8 e2e E22：输入框哨兵死锁用例（仿真机 9/21「A009修理完成了吗？」，必须真词条）
    'Apakah mesin A009 sudah selesai diperbaiki': 'A009机器修理完成了吗',
    'LAPORAN KERUSAKAN Mesin A006 stop total 6 item detail gearbox': '故障报告\n机台：A006 停机\n项目共 6 项\n明细一：齿轮箱异响\n明细二：轴承磨损\n明细三：密封圈老化\n明细四：联轴器松动\n明细五：底座螺栓缺失\n明细六：润滑油不足\n处理：等待备件\n预计：四小时\n班组：维修班',
    # v0.3.2 e2e E24：时间戳污染树正文（必须真译文——若漏「早上6:21」进 src，
    # 键名不同 → ZH[...回显] 兜底被闸门拦 → row keep/fail 断言必炸）
    'Laporan kerusakan pisau A006 sudah saya kirim': 'A006刀具故障报告我已经发送了',
    # v0.3.3 e2e E25：组名+已转发污染树正文（真机 09-23 原句。若名行/角标混进 src，
    # 键名不同 → ZH[回显] 兜底被拦 → 断言必炸；干净 src 才命中此词条出中文）
    'Mau lihat perbandingan A4-nya, letakkan di atas meja saya': '我想看看A4的对比件，请放在我桌上',
    # v0.3.5 e2e E26：>80字长消息全文（tap-copy 链路唯一命中键——截断 80 字形态【故意不配】
    # 词条：谁拿截断文本去翻就 ZH[回显] 兜底被闸拦=行失败，只有拿到剪贴板全文才能出 ✓ 译文）
    'To Semua Mesin Calender Form tambah lipat yang baru tolong diperhatikan cara pengisiannya sesuai format contoh dari kantor pusat jangan lupa kolom alasan diisi lengkap': '致全部压延机：新的加折表单请注意填写方式，按总部示例格式，原因栏别忘了填完整',
}
DICT_ZH2ID = {
    '你好': 'Halo',
    '你好，这批货明天发货': 'Halo, pengiriman besok',
    '你好，这批货明天发货，请确认': 'Halo, pengiriman besok, tolong dikonfirmasi',
    '这批货质量有问题': 'Ada masalah dengan kualitas barang ini',
    '你好，明天安排人清理料仓': 'Halo, besok atur orang untuk bersih-bersih gudang',
    # v0.2.5 e2e E20：泄漏→恢复链路的正常译文词条（不加则 ID[原文] 兜底被回显防护拦下）
    '库存已确认，明天可以装柜': 'Stok sudah dikonfirmasi, muat besok',
    # v0.2.3 e2e E17：真机行为复刻——谭效平失败那次 HTTP200 返回【空内容】（434B）→
    # isEcho(src, "")=true（短文本豁免只管非空回显，空回复必判回显）→"显示的是原文"→学习。
    # 注意：短文本(<6字)回显被 Echo 长度闸门豁免（Terima kasih→谢谢 需要），不能写'名字:名字'。
    '王小明': '',
}

def is_cjk(t):
    return any('\u4e00' <= c <= '\u9fff' for c in t)

def tr(text):
    text = text.replace('\\n', '\n')  # 客户端换行经 HTML 表单转义后还原
    if is_cjk(text):
        return DICT_ZH2ID.get(text, 'ID[' + text + ']')
    return DICT_ID2ZH.get(text, 'ZH[' + text + ']')

class H(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        with open('/tmp/wt-test/mock_req.log', 'a') as f:
            f.write('%s %s -> %s\n' % (self.command, self.path, args[0] if args else ''))

    def do_POST(self):
        n = int(self.headers.get('Content-Length', 0))
        body = json.loads(self.rfile.read(n))
        HITS['n'] += 1
        try:
            with open('/tmp/wt-test/mock_bodies.log', 'a') as bf:
                bf.write(json.dumps(body, ensure_ascii=False) + '\n')
        except Exception:
            pass
        # v0.2.4 E19: a model name containing 'ghost' -> 404 (test button must test form input;
        # the ghost request must still land in mock_bodies.log -> check placed after the log write)
        if 'ghost' in str(body.get('model', '')).lower():
            self.send_response(404)
            self.end_headers()
            self.wfile.write(b'404 page not found')
            return
        if DELAY['sec']:
            time.sleep(DELAY['sec'])
        if REJECT400['on'] and 'enable_thinking' in body:
            self.send_response(400)
            self.end_headers()
            self.wfile.write(b'{"error":"unknown parameter enable_thinking"}')
            return
        sys_c = body['messages'][0]['content']
        user_c = body['messages'][1]['content']
        is_batch = '不要输出任何其他文字' in sys_c
        if FAIL['on'] and is_batch:
            self.send_response(500)
            self.end_headers()
            self.wfile.write(b'{"error":"forced batch failure"}')
            return
        if is_batch:
            texts = json.loads(user_c)
            strong = '【重译】' in sys_c
            if LEAK['on']:
                # v0.2.5 E20：真机 07:23 形状——批量契约下思考文吞掉 JSON（无数组可寻）
                content = LEAK_PREFIX + '（批量请求，模型只给散文）'
            elif ECHO['on'] and ECHO_BATCH_ONLY['on'] and strong:
                # v0.2.4 E5: mock returns normally on the strong retry round -> proves echo -> retry -> success
                arr = [tr(x) for x in texts]
            elif ECHO['on']:
                arr = list(texts)  # v1.1.19 测试：模型回显 = 原样吐回输入
            else:
                arr = [tr(t) for t in texts]
            if BADLEN['on']:
                arr = arr[:-1]  # 故意少回一条 → 触发批量解析失败→逐条降级
            content = json.dumps(arr, ensure_ascii=False)
        else:
            if LEAK['on']:
                # v0.2.5 E20：逐条降级/single 路径同样只给思考散文（真机 single 也 200+essay）
                content = LEAK_PREFIX + '（单条请求，模型只给散文）'
            elif ECHO['on'] and not (ECHO_BATCH_ONLY['on'] and '【重译】' in sys_c):
                # v0.2.6：N=1 直发 single 后，回显复刻必须覆盖 single 路径（E5/E5b 改测共享单条链）
                content = user_c
            else:
                content = tr(user_c)
        resp = json.dumps({'choices': [{'message': {'role': 'assistant', 'content': content}}]})
        b = resp.encode()
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def do_GET(self):
        if self.path.startswith('/setechoonly'):
            ECHO_BATCH_ONLY['on'] = '1' in self.path
            self.send_response(200); self.end_headers(); self.wfile.write(b'ok'); return
        if self.path.startswith('/setecho'):
            ECHO['on'] = '1' in self.path
            self.send_response(200); self.end_headers(); self.wfile.write(b'ok'); return
        if self.path.startswith('/setleak'):
            LEAK['on'] = '1' in self.path
            self.send_response(200); self.end_headers(); self.wfile.write(b'ok'); return
        if self.path.startswith('/setreject400'):
            REJECT400['on'] = '1' in self.path
            self.send_response(200); self.end_headers(); self.wfile.write(b'ok'); return
        if self.path.startswith('/setfail'):
            FAIL['on'] = '1' in self.path
            out = json.dumps(FAIL).encode()
        elif self.path.startswith('/setbadlen'):
            BADLEN['on'] = '1' in self.path
            out = json.dumps(BADLEN).encode()
        elif self.path.startswith('/hits'):
            if 'reset' in self.path:
                HITS['n'] = 0
            out = json.dumps(HITS).encode()
        else:
            self.send_response(404)
            self.end_headers()
            return
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(out)))
        self.end_headers()
        self.wfile.write(out)

HTTPServer(('0.0.0.0', 8787), H).serve_forever()

