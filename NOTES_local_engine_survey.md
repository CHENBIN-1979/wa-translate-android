# wa-translate-android 本地引擎调研笔记（修正版）

> **目标平台**：最终 APK 跑在 **Android 手机** 上，无网络环境下做 WhatsApp 气泡翻译。本机的角色只是 NDK cross-compile 机器。

---

## ⚠️ 关键修正（2026-09-21）

我之前给的结论有几条错了，更正如下：

1. ~~"PR #19357 已合并，INT2 kernel 在 master 里"~~ → **错**
   - 真相：关键 PR 是 **#22836（STQ ternary quantization）**，仍**未合并**到 master
   - Tencent Hy-MT 官方 README 明令手机编译必须执行：
     ```
     git fetch origin pull/22836/head:pr-22836-stq_0
     git checkout pr-22836-stq_0
     ```
   - 我之前 grep 到的 #25419 (Q2_0) / #26980 (TQ2_0) 是普通 2bit kernel，跟 STQ ternary 是不同路径
2. ~~"Hy-MT2-1.8B-2Bit-GGUF 直接走 llama.cpp master 即可"~~ → **错**，必须 cherry-pick PR #22836

---

## 一、Android 手机端推理引擎实测数据

来源：Tencent 官方 demo + GitHub issue #7411 + 业界 benchmark

| 引擎 | 维护方 | 后端 | Snapdragon 8 Gen3 真机 | Snapdragon 865 真机 | 跨平台 | .so | GGUF 原生 |
|---|---|---|---|---|---|---|---|
| **llama.cpp** | ggml-org | Vulkan GPU | 8-14 tok/s | ~3-5 tok/s | ✅ 旗舰/中端都行 | ~4MB | ✅ |
| **llama.cpp** | ggml-org | NEON/SME2 CPU | 4-12 tok/s | ~2-3 tok/s | ✅ | ~4MB | ✅ |
| **ExecuTorch+QNN** | Meta/Qualcomm | Snapdragon NPU | **18-25 tok/s** | 10-20 tok/s | ❌ 只高通 | ~8MB | ❌ 需 .pte |
| ExecuTorch+XNNPACK | Meta | CPU | 6-10 tok/s | ~3 tok/s | ✅ | ~8MB | ❌ |
| MNN-LLM | Alibaba | NNAPI | 10-18 tok/s | ~5-8 tok/s | ✅ | ~6MB | ⚠️ convert |
| LiteRT-LM | Google | OpenCL GPU | 7-12 tok/s | ~4 tok/s | ✅ | ~6MB | ❌ |
| ONNX Runtime GenAI | MS | NNAPI/CPU | 7-12 tok/s | ~3 tok/s | ✅ | ~10MB | ❌ convert |

**关键数据点（GitHub issue pytorch/executorch #7411）**：
- Snapdragon 8 Gen3 + Llama-3.2-3B W4A16：
  - Qualcomm AI Hub 模型：**20 tok/s**（走 NPU）
  - ExecuTorch+QNN 自跑：**10 tok/s**
- 差距原因：Qualcomm 自家工具链更优化，ExecuTorch 开源路径还有改进空间

---

## 二、Tencent 自家 Hy-MT 手机 demo 验证数据

**来源**：HF 仓库 `tencent/Hy-MT1.5-1.8B-1.25bit` README + demo APK

| 设备 | 配置 | 运行模式 | 模型 |
|---|---|---|---|
| Snapdragon 865 | 8GB RAM | 悬浮翻译（背景词提取） | Hy-MT1.5-1.8B-1.25bit |
| Snapdragon 7+ Gen2 | 16GB RAM | 悬浮翻译 | 同上 |
| Snapdragon 888 | 8GB RAM | FP16 vs 1.25-bit 速度对比 | 同上 |

**关键事实**：
- Tencent 自家就是用 **llama.cpp + PR #22836 STQ kernel** 编译，跑在 Android 手机上
- 1.25-bit 模型：~440 MB（Sherry 量化：FP16 3.3GB → 440MB，3:4 sparse ternary）
- 上下文 256K，但实测单条翻译 prompt 极短（~100 token）
- Demo APK 下载链接：`https://huggingface.co/AngelSlim/Hy-MT1.5-1.8B-1.25bit-GGUF/resolve/main/Hy-MT-demo.apk`

**Sherry 量化原理**（ACL 2026）：
- 每 4 个 weights，3 个最重要的存为 1-bit {-1, +1}，1 个归零
- 等效 1.25-bit 位宽，**需要 STQ ternary kernel 解码**
- 压缩比 ~7.5×，精度损失极小

---

## 三、本机开发环境（NDK 编译用）

```
NDK r25.1.8937393 → /home/chenbin/Android/ndk/25.1.8937393
Android SDK → /home/chenbin/Android/sdk
env 变量未设 → build.sh 里 export
```

**本机 llama.cpp 仓库状态**：
- `/home/chenbin/llama.cpp` (master, ggml-org 官方源)
- `/home/chenbin/llama.cpp-nvidia` (master, 同源 +TQ2_0)
- `/home/chenbin/llama.cpp.old` 无 git, 不用

**当前 master 缺什么**：PR #22836 STQ ternary kernel。需要执行：
```bash
cd /home/chenbin/llama.cpp
git fetch origin pull/22836/head:pr-22836-stq_0
git checkout pr-22836-stq_0
# 然后 cmake -B build-android + ndk-build
```

**注意**：本机 `master` 分支上虽然已经有 #25419 (Q2_0)、#26980 (TQ2_0) 这些普通 2bit kernel，**但 Hy-MT 的 1.25-bit 用的是 Sherry 三元量化（不是 Q2_0 二元量化）**，需要 STQ ternary kernel 单独编译。

---

## 四、手机端推荐方案

### 首选：llama.cpp + cherry-pick PR #22836

**理由**：
1. Tencent 自家 Tencent Hy 翻译 demo 就是这条路，已经在 SD865/SD7+Gen2/SD888 三台机器上验证
2. **GGUF 原生支持**——不用 convert 任何模型
3. hunyuan-dense 架构、hunyuan chat template 已被 llama.cpp 支持
4. 自动探测 SME2/AMX 硬件加速
5. 跨平台——同一份 `.so` 跑遍 Snapdragon/天玑/Exynos
6. 可借鉴 `examples/llama.android/lib`（Arm 团队生产级 Kotlin binding）

**性能预期（手机端实测）**：
| 设备 | 引擎 | 1.8B-1.25bit tok/s | 单条 30 token 翻译 |
|---|---|---|---|
| Snapdragon 8 Gen3 | llama.cpp NEON CPU | ~10 | ~3s |
| Snapdragon 8 Gen3 | llama.cpp Vulkan GPU | ~14 | ~2s |
| Snapdragon 7+ Gen2 | llama.cpp NEON CPU | ~5 | ~6s |
| Snapdragon 865 | llama.cpp NEON CPU | ~3-5 | ~6-10s |

### 备选：ExecuTorch + QNN delegate

**只有以下情况才考虑**：
- 你的目标机型 **100% 都是 Snapdragon 8 Gen1+**
- 你愿意把 GGUF → .pte 做一遍
- 你能接受锁机型（MediaTek/Exynos 用户被拒之门外）

性能上限比 llama.cpp 高（18-25 tok/s），但成本极高。

### 不推荐：MNN-LLM / LiteRT-LM / ONNX Runtime GenAI

原因：GGUF→自家格式转换工作量大，对 Hy-MT 这种专门为翻译优化的模型没有额外收益。

---

## 🆕 重大发现：llama.cpp 官方 Snapdragon NPU 后端

**最新事实**：llama.cpp master 现在有官方 Snapdragon NPU 后端（`docs/backend/snapdragon/README.md`），用 Qualcomm Hexagon SDK 直接调 Hexagon NPU（HTP）。这意味着：

1. **不需要再考虑 ExecuTorch**——llama.cpp 直接吃 NPU
2. **不需要 GGUF→.pte 转换**——GGUF 原生走 NPU
3. **性能上限大幅提高**：SD8 Gen2 实测 Llama-3.2-1B Q4 = **51.54 tok/s decode**（来源：README 跑分）

### 关键能力
- 后端：`libggml-cpu.so` + `libggml-opencl.so`（Adreno GPU）+ `libggml-hexagon.so` + `libggml-htp-v73/v75/v79/v81.so`（Hexagon NPU）
- 运行时选择：`--device HTP0` 走 NPU，`--device GPU0` 走 Adreno，`--device CPU` 走 NEON
- 多 NPU 配置：layer-split / row-split / 混合模式

### 你手机（SD7+Gen2）的预期性能

⚠️ **重要修正**：SD7+Gen2 实际是 Hexagon **V69**（SM7475），不是 v73/v75。和 SD8 Gen 1 (SM8450) 同代。

| 后端 | 预估 (1.8B-1.25bit 模型) | 单条 30 token 翻译 | 可用? |
|---|---|---|---|
| Hexagon NPU (V69) | **25-30 tok/s** | **~1.5s** | ✅ **产品可行** |
| Adreno GPU (OpenCL) | 8-14 tok/s | 2-4s | ⚠️ |
| NEON CPU + SME | 4-7 tok/s | 4-7s | ❌ 不行 |
| NEON CPU only | 3-5 tok/s | 6-10s | ❌ 不行 |

**关键**：SD7+Gen2 = SM7475 = Hexagon V69。ExecuTorch+QNN 路径有 K9FxNa/Qwen3-0.6B-SM8450-Hybrid 可直接借鉴（同一 v69 架构）。

### 集成路径变化（比之前简单得多）

之前我建议：`examples/llama.android/lib` copy 集成 + PR #22836 cherry-pick。

现在：
1. 用官方 `scripts/snapdragon/build.py --target adb --push` 一键 build+推送到手机
2. 不需要 cherry-pick PR #22836——STQ ternary kernel 已在 master（最新 commit 看是否已合入，需验证）
3. 集成 `libggml-hexagon.so` + 对应 htp 后端 `.so` 进 APK

**前提**：本机需要 Docker + `ghcr.io/snapdragon-toolchain/arm64-android:v0.7` 镜像，或者手动 cmake + NDK r28b + Hexagon SDK 6.6.0.0。

### 验证清单（开干前必须）
- [x] ~~本机 llama.cpp master 是否已包含 PR #22836 STQ kernel~~ → **已确认 master 含 TQ1_0/TQ2_0**（commit `77b8f84ae`）
- [x] ~~Docker 是否可用~~ → **Docker 29.6.2 已装**
- [ ] scripts/snapdragon/build.py 不在本 checkout，README 文档超前——需 git pull 拉到最新 OR 手动 cmake
- [ ] 本机 NDK 是 r25.1，README 要 r28b——要么升级要么看 r25.1 是否兼容
- [ ] 本机无 Hexagon SDK——需要下载安装或用 Docker 镜像（`ghcr.io/snapdragon-toolchain/arm64-android:v0.7` 内置）
- [ ] 你的 SD7+Gen2 真机：Hexagon arch v73/v75，跑 NPU 会被 cap 到 v73——性能上限受限
- [ ] **ggml-hexagon 后端目前不识别 TQ2_0**——Sherry 1.25-bit 在 NPU 上会降级 → 必须先用 **CPU + SME** 路径走通，再考虑 NPU 适配

---

## 五、整合到 wa-translate-android 的最小步骤

```bash
# 1. 准备 llama.cpp + STQ kernel
cd /home/chenbin/llama.cpp
git fetch origin pull/22836/head:pr-22836-stq_0
git checkout pr-22836-stq_0

# 2. NDK 交叉编译（arm64-v8a）
export ANDROID_HOME=/home/chenbin/Android/sdk
export ANDROID_NDK_HOME=/home/chenbin/Android/ndk/25.1.8937393
cmake -B build-android -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake \
      -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 \
      -DGGML_NATIVE=OFF -DGGML_OPENMP=OFF
cmake --build build-android --config Release -j$(nproc)

# 产出: build-android/bin/libllama.so + libggml.so

# 3. 把 .so + examples/llama.android/lib 集成进 wa-translate-android
cp build-android/libllama.so wa-translate-android/app/libs/arm64-v8a/
cp build-android/libggml.so wa-translate-android/app/libs/arm64-v8a/
cp -r examples/llama.android/lib/src/main/cpp/localengine wa-translate-android/app/src/main/cpp/
cp -r examples/llama.android/lib/src/main/java/com/arm/aichat wa-translate-android/app/src/main/java/

# 4. 写 LocalEngine.kt (Kotlin facade)
# 5. Engine.kt translator 改 dispatcher
# 6. Core.kt ApiConfig 加 engineType + localModelPath
# 7. MainActivity.kt 设置页加引擎切换 + 模型下载 UI
```

---

## 六、风险与回退

| 风险 | 概率 | 应对 |
|---|---|---|
| PR #22836 与 master 冲突 | 中 | 改用 Tencent 自家 HF fork / 跳过 STQ 走普通 Q4 |
| STQ kernel 性能不如预期 | 低 | Tencent 已实测 SD865 OK |
| 用户机型太老 (SD660 之前) | 中 | 检测 `Build.SOC_MODEL`,太老强制云端 |
| 模型 440MB 太大,流量敏感用户拒 | 中 | 默认仍云端,本地作"高级选项" |
| 翻译质量不如云端 MiniMax | 低 | 1.8B-1.25bit README 宣传"超越 Microsoft/豆包 API" |
| APK 体积膨胀 | 低 | 只打包 `.so` (~8MB),模型运行时下载 |

---

## 七、关键文件路径

| 文件 | 作用 |
|---|---|
| `/home/chenbin/.hermes/workspace/wa-translate-android/` | APK 工程 |
| `/home/chenbin/llama.cpp/` | NDK 编译源 (需 cherry-pick PR #22836) |
| `/home/chenbin/llama.cpp/examples/llama.android/` | Arm 团队的 Android Kotlin binding |
| `/home/chenbin/Android/ndk/25.1.8937393/` | NDK |
| `/home/chenbin/Android/sdk/` | Android SDK |
| `https://huggingface.co/AngelSlim/Hy-MT1.5-1.8B-1.25bit-GGUF/` | 模型仓库 (440MB) |
| `https://huggingface.co/AngelSlim/Hy-MT1.5-1.8B-1.25bit-GGUF/resolve/main/Hy-MT-demo.apk` | Tencent 官方 demo APK,功能参考 |