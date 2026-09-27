#!/usr/bin/env python3
"""v0.3.3 出包：release apk -> watranslate-v0.3.3.apk + 源码 zip（排除构建产物）"""
import os, zipfile, hashlib, shutil

ROOT = "/home/chenbin/.hermes/workspace/wa-translate-android"
OUT = os.path.join(ROOT, "release-v0.3.3")
os.makedirs(OUT, exist_ok=True)

apk_src = os.path.join(ROOT, "app/build/outputs/apk/release/app-release.apk")
apk_dst = os.path.join(OUT, "watranslate-v0.3.3.apk")
shutil.copy2(apk_src, apk_dst)

zip_dst = os.path.join(OUT, "watranslate-v0.3.3-src.zip")
EXCLUDE_DIRS = {"build", ".gradle", ".git", "__pycache__", ".cxx",
                "release-v0.3.0", "release-v0.3.1", "release-v0.3.2", "release-v0.3.3"}
EXCLUDE_FILES = {"smoke32mb.bin"}  # mock 测试垃圾文件（32MB），不进源码包
n = 0
with zipfile.ZipFile(zip_dst, "w", zipfile.ZIP_DEFLATED) as zf:
    for dirpath, dirnames, filenames in os.walk(ROOT):
        dirnames[:] = [d for d in dirnames if d not in EXCLUDE_DIRS]
        for f in filenames:
            if f in EXCLUDE_FILES:
                continue
            p = os.path.join(dirpath, f)
            rel = os.path.relpath(p, ROOT)
            zf.write(p, os.path.join("watranslate-v0.3.3", rel))
            n += 1

def md5(p):
    h = hashlib.md5()
    with open(p, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()

for p in (apk_dst, zip_dst):
    print(f"{p}\n  size={os.path.getsize(p):,}B  md5={md5(p)}")
print(f"zip entries: {n}")
