#!/bin/bash
# 一次性脚本：盘本机 llama.cpp 三仓库 + Android SDK
set +e
for d in /home/chenbin/llama.cpp /home/chenbin/llama.cpp-nvidia /home/chenbin/llama.cpp.old; do
  if [ -d "$d" ]; then
    echo "=== $d ==="
    cd "$d" || continue
    echo "branch: $(git rev-parse --abbrev-ref HEAD 2>/dev/null)"
    echo "remote: $(git remote -v 2>/dev/null | head -1)"
    echo "log:"
    git log --oneline -10 2>/dev/null | sed 's/^/  /'
    echo "int2/stq/q2_0c/sme2 hits: $(git log --all --oneline 2>/dev/null | grep -ciE 'int2|stq|q2_0c|sme2|kleidiai')"
    echo "has examples/llama.android: $(test -d examples/llama.android && echo YES || echo NO)"
    echo "size: $(du -sh . 2>/dev/null | cut -f1)"
  fi
done
echo '---ANDROID_HOME---'
ls /home/chenbin/Android 2>/dev/null
echo '---NDK---'
ls /home/chenbin/Android/sdk/ndk 2>/dev/null
echo '---ANDROID_SDK_ROOT---'
echo "ANDROID_HOME=$ANDROID_HOME"
echo "ANDROID_SDK_ROOT=$ANDROID_SDK_ROOT"
echo '---models---'
ls /home/chenbin/models 2>/dev/null | head -20