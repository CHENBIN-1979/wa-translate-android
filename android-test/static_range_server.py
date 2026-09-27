# M2 冒烟用静态 Range 服务器：服务 android-test/mockfiles/ 下文件，
# 支持 Range: bytes=N-（回 206），并暴露：
#   GET /sha                 -> smoke8mb.bin 的 sha256
#   GET /control?rate=B&fail_after=N&fail_times=K
#        rate=B       每 chunk 限速 B 字节/秒（0=不限）
#        fail_after=N 接下来 K 次数据请求：发出 N 字节后掐断连接（模拟网络中断）
# 仅测试工具，不进 APK。
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import hashlib
import os
import time
from urllib.parse import urlparse, parse_qs

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'mockfiles')

CTL = {'rate': 0, 'fail_after': 0, 'fail_left': 0}


def sha_of(name):
    h = hashlib.sha256()
    with open(os.path.join(ROOT, name), 'rb') as f:
        for chunk in iter(lambda: f.read(256 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()


class H(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'

    def log_message(self, *a):
        pass

    def do_GET(self):
        u = urlparse(self.path)
        if u.path == '/sha':
            q = parse_qs(u.query)
            fname = (q.get('f') or ['smoke8mb.bin'])[0]
            body = sha_of(os.path.basename(fname)).encode()
            self.send_response(200)
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        if u.path == '/control':
            q = parse_qs(u.query)
            if 'rate' in q:
                CTL['rate'] = int(q['rate'][0])
            if 'fail_after' in q:
                CTL['fail_after'] = int(q['fail_after'][0])
            if 'fail_times' in q:
                CTL['fail_left'] = int(q['fail_times'][0])
            if CTL['fail_after'] == 0:
                CTL['fail_left'] = 0
            body = str(CTL).encode()
            self.send_response(200)
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        name = u.path.lstrip('/')
        fp = os.path.join(ROOT, name)
        if not os.path.isfile(fp):
            self.send_error(404)
            return
        size = os.path.getsize(fp)
        rng = self.headers.get('Range')
        start = 0
        if rng and rng.startswith('bytes='):
            a, _, _b = rng[6:].split(',')[0].strip().partition('-')
            try:
                start = int(a)
            except ValueError:
                start = 0
        if start and start >= size:
            self.send_response(416)
            self.send_header('Content-Range', f'bytes */{size}')
            self.send_header('Content-Length', '0')
            self.end_headers()
            return
        remaining = size - start
        self.send_response(206 if start > 0 else 200)
        if start > 0:
            self.send_header('Content-Range', f'bytes {start}-{size - 1}/{size}')
        self.send_header('Accept-Ranges', 'bytes')
        self.send_header('Content-Length', str(remaining))
        self.send_header('Content-Type', 'application/octet-stream')
        self.end_headers()
        # 本请求是否armed了断线故障
        my_fail = CTL['fail_left'] > 0 and CTL['fail_after'] > 0
        if my_fail:
            CTL['fail_left'] -= 1
        sent = 0
        with open(fp, 'rb') as f:
            f.seek(start)
            while remaining > 0:
                n = min(64 * 1024, remaining)
                if CTL['rate'] > 0:
                    n = min(n, max(4096, CTL['rate'] // 16))
                chunk = f.read(n)
                if not chunk:
                    break
                try:
                    self.wfile.write(chunk)
                    self.wfile.flush()
                except (BrokenPipeError, ConnectionResetError):
                    return
                sent += len(chunk); remaining -= len(chunk)
                if CTL['rate'] > 0:
                    time.sleep(len(chunk) / float(CTL['rate']))
                if my_fail and sent >= CTL['fail_after']:
                    self.close_connection = True
                    return  # 掐断


if __name__ == '__main__':
    print('static range server on :8788 root=', ROOT, flush=True)
    ThreadingHTTPServer(('127.0.0.1', 8788), H).serve_forever()
