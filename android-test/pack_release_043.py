#!/usr/bin/env python3
"""v0.4.3 出包：release apk -> watranslate-v0.4.3.apk + 源码 zip（排除构建产物/历史包）"""
import os, shutil, zipfile, hashlib

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "release-v0.4.3")
os.makedirs(OUT, exist_ok=True)

EXCLUDE_DIRS = {"build", "__pycache__", ".gradle", ".git", ".cxx",
                "release-v0.3.0", "release-v0.3.1", "release-v0.3.2", "release-v0.3.3",
                "release-v0.3.4", "release-v0.3.5", "release-v0.3.6", "release-v0.3.7",
                "release-v0.3.8", "release-v0.3.9", "release-v0.4.0", "release-v0.4.1",
                "release-v0.4.2",
                "release-v0.4.3"}  # ⚠ 输出目录必须在排除名单里，否则 os.walk 边写 zip 边吞自己（9/24 实证：5.9GB 自吞噬；0.4.1 漏排 0.4.0 已在本版补全）
EXCLUDE_FILES = {"smoke32mb.bin"}  # mock 测试垃圾文件（32MB），不进源码包

apk_src = os.path.join(ROOT, "app/build/outputs/apk/release/app-release.apk")
apk_dst = os.path.join(OUT, "watranslate-v0.4.3.apk")
shutil.copy2(apk_src, apk_dst)
md5_apk = hashlib.md5(open(apk_dst, "rb").read()).hexdigest()
print(f"APK: {apk_dst}  size={os.path.getsize(apk_dst)}  md5={md5_apk}")

zip_dst = os.path.join(OUT, "watranslate-v0.4.3-src.zip")
count = 0
with zipfile.ZipFile(zip_dst, "w", zipfile.ZIP_DEFLATED) as zf:
    for dirpath, dirnames, filenames in os.walk(ROOT):
        dirnames[:] = [d for d in dirnames if d not in EXCLUDE_DIRS]
        for fn in filenames:
            if fn in EXCLUDE_FILES:
                continue
            p = os.path.join(dirpath, fn)
            rel = os.path.relpath(p, ROOT)
            if rel == os.path.relpath(zip_dst, ROOT):
                continue
            zf.write(p, os.path.join("watranslate-v0.4.3", rel))
            count += 1
md5_zip = hashlib.md5(open(zip_dst, "rb").read()).hexdigest()
print(f"SRC: {zip_dst}  size={os.path.getsize(zip_dst)}  md5={md5_zip}  entries={count}")
