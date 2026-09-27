#!/bin/bash
# pack_engine.sh — 把 hymt-unified 交叉编译产物铺进 app 的 native 目录。
# 用法：bash pack_engine.sh   （幂等，可反复跑）
#
# 产物来源（hymt-m0 工作区，NDK r29 交叉编译）：
#   arm64:  build-and/bin   x86_64:  build-and-x64/bin
# 依赖闭包：libllama/libggml/libggml-base + 全部 libggml-cpu-* 变体 + libomp.so
# 本地 JNI 头文件来自 llama-hymt/include + ggml/include。
set -e
cd "$(dirname "$0")"

HYMT=/home/chenbin/.hermes/workspace/hy-mt-m0/llama-hymt
NDK=/home/chenbin/Android/ndk/29.0.14206865
CLANG_RT=$NDK/toolchains/llvm/prebuilt/linux-x86_64/lib/clang/21/lib/linux
STRIP=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip

APP=app/src/main
JNILIBS=$APP/jniLibs
PREBUILT=$APP/cpp/prebuilt

fresh_dir() { rm -rf "$1"; mkdir -p "$1"; }

# ---- 1) JNI 编译期需要的头文件（两个 ABI 共用同一套 header）----
for abi in arm64-v8a x86_64; do
  fresh_dir "$PREBUILT/$abi/include"
  cp -r "$HYMT/include/." "$PREBUILT/$abi/include/"
  cp -r "$HYMT/ggml/include/." "$PREBUILT/$abi/include/"
  fresh_dir "$PREBUILT/$abi/lib"
done

# link 时 -lllama -lggml -lggml-base 需要这三个（用未 strip 的 build-and 版，含 .so 符号）
for abi_pair in "arm64-v8a:build-and" "x86_64:build-and-x64"; do
  abi=${abi_pair%%:*}; bdir=${abi_pair##*:}
  for lib in libllama.so libggml.so libggml-base.so; do
    cp "$HYMT/$bdir/bin/$lib" "$PREBUILT/$abi/lib/$lib"
  done
done

# ---- 2) 运行期打进 APK 的 .so（strip 后拷进 jniLibs）----
fresh_dir "$JNILIBS/arm64-v8a"; fresh_dir "$JNILIBS/x86_64"

# arm64: 用已 strip 好的 /tmp/and-stripped（若无则现编 build-and/bin）
if [ -d /tmp/and-stripped ] && ls /tmp/and-stripped/*.so >/dev/null 2>&1; then
  cp /tmp/and-stripped/*.so "$JNILIBS/arm64-v8a/"
else
  for f in "$HYMT"/build-and/bin/*.so; do cp "$f" /tmp/a-stmp/ 2>/dev/null || { mkdir -p /tmp/a-stmp && cp "$f" /tmp/a-stmp/; }; done
  ( cd /tmp/a-stmp && for f in *.so; do "$STRIP" "$f"; done ); cp /tmp/a-stmp/*.so "$JNILIBS/arm64-v8a/"
fi

if [ -d /tmp/and64-stripped ] && ls /tmp/and64-stripped/*.so >/dev/null 2>&1; then
  cp /tmp/and64-stripped/*.so "$JNILIBS/x86_64/"
else
  mkdir -p /tmp/x-stmp && cp "$HYMT"/build-and-x64/bin/*.so /tmp/x-stmp/
  ( cd /tmp/x-stmp && for f in *.so; do "$STRIP" "$f"; done ); cp /tmp/x-stmp/*.so "$JNILIBS/x86_64/"
fi

# libomp.so（两 ABI 各一份，NDK clang runtime）
cp "$CLANG_RT/aarch64/libomp.so" "$JNILIBS/arm64-v8a/"
cp "$CLANG_RT/x86_64/libomp.so"  "$JNILIBS/x86_64/"

echo "=== jniLibs 清单 ==="
for abi in arm64-v8a x86_64; do
  echo "-- $abi ($(ls $JNILIBS/$abi/*.so | wc -l) 个 .so, $(du -sh $JNILIBS/$abi | cut -f1))"
  ls $JNILIBS/$abi/
done
