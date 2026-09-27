// local_engine.cpp — Hy-MT1.5-1.8B 本地翻译引擎 JNI 层
//
// 链接 libllama.so / libggml.so / libggml-base.so（预编译，随 APK jniLibs 打包）。
// 后端调度：ggml_backend_load_all_from_path(nativeLibraryDir) —— 与腾讯官方 demo 同一
// 上游架构（GGML_BACKEND_DL + GGML_CPU_ALL_VARIANTS），dlopen 挑选本机指令集最优的
// libggml-cpu-android_armvX_Y.so 变体。
//
// 推理策略（M0 已验证）：temp=0 贪婪解码（argmax），提示词由 Kotlin 层用 Hy-MT 官方
// 模板拼好传入；add_special=false；遇 EOG 或预算上限停止。
//
// API 核实记录（hymt-unified tree, llama.h）：
//   - llama_batch_get_one() 的 pos/logits 为 nullptr（logits 缺省只在最后? 不——
//     get_one 全 0 = 只有隐式行为不可靠），故用 llama_batch_init 手工组装并显式
//     设 logits[最后]=1，保证 llama_get_logits_ith 可取。
//   - 装载用 llama_model_load_from_file / llama_init_from_model（旧名已 DEPRECATED）。
//   - 词表大小用 llama_vocab_n_tokens；ctx 大小用 llama_n_ctx(ctx)。

#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <chrono>
#include <cmath>
#include <mutex>
#include <random>
#include <string>
#include <vector>

#include "ggml.h"
#include "ggml-backend.h"
#include "llama.h"

#define TAG "LocalEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

constexpr int      BATCH_CAP  = 512;
constexpr size_t   MAX_BYTES  = 8192;

struct EngineState {
    std::mutex          mu;
    llama_model       * model = nullptr;
    llama_context     * ctx   = nullptr;
    const llama_vocab * vocab = nullptr;
    std::string         chatTmpl;
    bool                backendsLoaded = false;
    // v0.3.7 A1 速度支撑：prompt 前缀 KV 缓存。词典全量注入后 245 tok 前缀每轮重复，
    // 手机上全量 prefill 首条 ~40s（PC 实测），不缓存则**每条**都付这份钱。判据：
    // 本轮 tokens 与上轮 g.lastPrompt 的逐 token 最长公共前缀 c → seq_rm 只删
    // [c, inf) 尾巴，[0,c) 的 KV 原地复用（token 级比对，任何差异位都重灌，无脏 KV）。
    // 提取词典模式下 prompt 每轮不同 → 公共前缀小，行为≈旧版，零回归。
    std::vector<llama_token> lastPrompt;   // 上轮完整 prompt token
    bool                   kvValid = false; // 上轮成功跑完（false → 下次全清）

    void unloadLocked() {
        if (ctx)   { llama_free(ctx);         ctx   = nullptr; }
        if (model) { llama_model_free(model); model = nullptr; }
        vocab = nullptr;
        lastPrompt.clear();
        kvValid = false;
    }
};

EngineState g;

// JNI GetStringChars（UTF-16）-> 标准 UTF-8；不经过 Modified UTF-8，代理对合成 4 字节
static std::string jstrToStdUtf8(JNIEnv * env, jstring s) {
    const jsize n = env->GetStringLength(s);
    const jchar * u = env->GetStringChars(s, nullptr);
    std::string out;
    auto emit = [&](unsigned cp) {
        if (cp < 0x80) out += (char) cp;
        else if (cp < 0x800) { out += (char) (0xC0 | (cp >> 6)); out += (char) (0x80 | (cp & 0x3F)); }
        else if (cp < 0x10000) { out += (char) (0xE0 | (cp >> 12)); out += (char) (0x80 | ((cp >> 6) & 0x3F)); out += (char) (0x80 | (cp & 0x3F)); }
        else { out += (char) (0xF0 | (cp >> 18)); out += (char) (0x80 | ((cp >> 12) & 0x3F)); out += (char) (0x80 | ((cp >> 6) & 0x3F)); out += (char) (0x80 | (cp & 0x3F)); }
    };
    for (jsize i = 0; i < n; i++) {
        unsigned c = u[i];
        if (c >= 0xD800 && c <= 0xDBFF && i + 1 < n && u[i + 1] >= 0xDC00 && u[i + 1] <= 0xDFFF) {
            emit(0x10000 + ((c - 0xD800) << 10) + (u[++i] - 0xDC00));
        } else if (c >= 0xD800 && c <= 0xDFFF) {
            emit(0xFFFD);
        } else emit(c);
    }
    env->ReleaseStringChars(s, u);
    return out;
}

static bool encode_locked(const std::string & text, bool add_special,
                          std::vector<llama_token> & out) {
    // llama_tokenize 负返回值 = 需要的 token 数（非错误）。BPE 每 token ≥1 字节，
    // 故 cap = 字节数+8 必够，单次调用即可。
    const int32_t cap = (int32_t) text.size() + 8;
    std::vector<llama_token> toks(cap);
    const int32_t n = llama_tokenize(g.vocab, text.data(), (int32_t) text.size(),
                                     toks.data(), cap, add_special, /*parse_special=*/ true);
    if (n < 0) { LOGE("tokenize rc=%d cap=%d", n, cap); return false; }
    toks.resize(n);
    out.insert(out.end(), toks.begin(), toks.end());
    return true;
}

// Single-sequence decode with explicit KV positions.
// Feed prompt in <=BATCH_CAP chunks (logits only for the chunk's last token),
// then one token per step. `pending` = last sampled token not yet fed.
// skip = 已在 KV 里的前缀长度（v0.3.7 prompt 前缀缓存），从 prompt[skip] 起喂。
// temp<=0 → greedy argmax; temp>0 → v0.3.7 A4 官方采样链：repetition-penalty →
//   temperature(0.7) → top-k(20) → top-p(0.6)（腾讯 Hy-MT README 推荐参数，
//   四轮电池 grammar_bat4 全通道以此跑通；此前为 temp+top_p(0.9)，无 top-k 无 rep）。
// 采样只作用于 decode 步（discardSample 的 prefill 采样本就丢弃，不影响 RNG 一致性）。
static bool generate_locked(const std::vector<llama_token> & prompt, size_t skip,
                            int n_predict,
                            float temp, int top_k, float top_p, float rep_pen,
                            uint64_t seed, std::string & result) {
    const bool sampling = temp > 0.0f;
    std::mt19937 rng(seed);
    std::uniform_real_distribution<float> uni(0.0f, 1.0f);
    llama_batch batch = llama_batch_init(BATCH_CAP, 0, 1);
    if (batch.token == nullptr) { LOGE("batch_init failed"); return false; }

    std::vector<char> piece(64);
    const int32_t nv = llama_vocab_n_tokens(g.vocab);
    // 采样期复用的概率缓冲（128k 词表 ≈1MB，避免每步重分配）
    std::vector<std::pair<float, int32_t>> probs;
    if (sampling) probs.resize((size_t) nv);
    std::vector<llama_token> generated_toks;   // rep-penalty 历史（译文侧，通常 <300）
    size_t i        = skip;                   // prompt tokens consumed == next position
    bool   has_pend = false;
    llama_token pend = 0;
    int    generated = 0;
    bool   ok = true;

    while (ok) {
        // --- build next batch ---
        bool discardSample = false;
        if (i < prompt.size()) {
            const int32_t take =
                (int32_t) std::min<size_t>(prompt.size() - i, (size_t) BATCH_CAP);
            for (int32_t j = 0; j < take; j++) {
                batch.token[j]     = prompt[i + j];
                batch.pos[j]       = (llama_pos) (i + j);
                batch.n_seq_id[j]  = 1;
                batch.seq_id[j][0] = 0;
                batch.logits[j]    = (j == take - 1) ? 1 : 0;
            }
            batch.n_tokens = take;
            i += take;
            // mid-prefill samples are garbage (next chunk is real input) — discard
            discardSample = (i < prompt.size());
        } else if (has_pend && generated < n_predict) {
            batch.token[0]    = pend;
            batch.pos[0]      = (llama_pos) i;
            batch.n_seq_id[0] = 1;
            batch.seq_id[0][0] = 0;
            batch.logits[0]   = 1;
            batch.n_tokens    = 1;
            i += 1;
            has_pend = false;
        } else {
            break; // budget exhausted or nothing pending -> done
        }

        // --- decode + greedy sample ---
        if (llama_decode(g.ctx, batch) != 0) { LOGE("decode failed"); ok = false; break; }
        const float * logits = llama_get_logits_ith(g.ctx, batch.n_tokens - 1);
        if (!logits) { LOGE("no logits"); ok = false; break; }

        llama_token best = 0; float bv = logits[0];
        if (sampling) {
            // v0.3.7 A4 官方链：repetition-penalty → temperature → top-k → top-p。
            // 词表 128k 每步 O(n log n) 排序，只在采样步付出（约 +30-60ms/步，
            // 短句 <20 token，可接受——电池 MT2 全程此配置，中位 3.6-3.7s/句）。
            // 1) rep penalty（llama.cpp 同款公式：logit>0 除、≤0 乘）
            const float * lg = logits;
            std::vector<float> adj;
            if (rep_pen > 1.0f && !generated_toks.empty()) {
                adj.assign(logits, logits + nv);
                for (llama_token tk : generated_toks)
                    if (tk >= 0 && tk < nv) {
                        float & x = adj[(size_t) tk];
                        x = x > 0.0f ? x / rep_pen : x * rep_pen;
                    }
                lg = adj.data();
            }
            // 2) temperature softmax
            float maxv = lg[0];
            for (int32_t t = 1; t < nv; t++) if (lg[t] > maxv) maxv = lg[t];
            float sum = 0.0f;
            for (int32_t t = 0; t < nv; t++) {
                const float p = std::exp((lg[t] - maxv) / temp);
                probs[(size_t) t] = { p, t }; sum += p;
            }
            std::sort(probs.begin(), probs.end(),
                      [](const std::pair<float, int32_t> & a, const std::pair<float, int32_t> & b) {
                          return a.first > b.first;
                      });
            // 3) top-k 截尾（k<=0 = 不限）
            const size_t kLim = (top_k > 0) ? std::min<size_t>((size_t) top_k, probs.size())
                                             : probs.size();
            // top-p 截尾阈值在 renorm 后的概率上算（与 llama-server 同语义）
            float kSum = 0.0f;
            for (size_t t = 0; t < kLim; t++) kSum += probs[t].first;
            const float cut = kSum * top_p;
            const float target = uni(rng) * kSum;
            float cum = 0.0f;
            int32_t pick = probs.front().second;
            for (size_t t = 0; t < kLim; t++) {
                cum += probs[t].first;
                pick = probs[t].second;
                if (cum >= target || cum >= cut) break;
            }
            best = pick;
        } else {
            for (int32_t t = 1; t < nv; t++) if (logits[t] > bv) { bv = logits[t]; best = t; }
        }

        if (discardSample) continue;
        if (llama_vocab_is_eog(g.vocab, best)) break;

        const int nch = llama_token_to_piece(g.vocab, best, piece.data(),
                                             (int32_t) piece.size(), 0, /*special=*/ true);
        if (nch < 0) { LOGE("piece too big rc=%d", nch); ok = false; break; }
        result.append(piece.data(), nch);
        if (result.size() > MAX_BYTES) { result.resize(MAX_BYTES); break; }

        pend     = best;
        has_pend = true;
        generated++;
        if (sampling) generated_toks.push_back(best);   // rep-penalty 历史
    }

    llama_batch_free(batch);
    return ok;
}

} // namespace

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *, void *) {
    llama_backend_init();
    return JNI_VERSION_1_6;
}

// init(libDir): dlopen best CPU backend variant from the APK native lib dir
JNIEXPORT jboolean JNICALL
Java_id_co_watranslate_LocalEngine_nativeInit(JNIEnv * env, jclass, jstring libDir) {
    std::lock_guard<std::mutex> lk(g.mu);
    if (g.backendsLoaded) return JNI_TRUE;
    const char * dir = env->GetStringUTFChars(libDir, nullptr);
    ggml_backend_load_all_from_path(dir);
    env->ReleaseStringUTFChars(libDir, dir);
    g.backendsLoaded = ggml_backend_dev_count() > 0;
    for (size_t i = 0; i < ggml_backend_dev_count(); i++) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        LOGI("backend[%zu]: name=%s type=%d", i, ggml_backend_dev_name(dev),
             (int) ggml_backend_dev_type(dev));
    }
    LOGI("backends: dev_count=%zu ok=%d", ggml_backend_dev_count(), (int) g.backendsLoaded);
    return g.backendsLoaded ? JNI_TRUE : JNI_FALSE;
}

// load(modelPath, ctxSize, nThreads)
JNIEXPORT jboolean JNICALL
Java_id_co_watranslate_LocalEngine_nativeLoad(JNIEnv * env, jclass, jstring modelPath,
                                              jint ctxSize, jint nThreads) {
    std::lock_guard<std::mutex> lk(g.mu);
    if (g.model) return JNI_TRUE;
    const char * path = env->GetStringUTFChars(modelPath, nullptr);

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0; // CPU only
    g.model = llama_model_load_from_file(path, mp);
    env->ReleaseStringUTFChars(modelPath, path);
    if (!g.model) { LOGE("model load FAILED"); return JNI_FALSE; }

    g.vocab = llama_model_get_vocab(g.model);
    const char * tmpl = llama_model_chat_template(g.model, nullptr);
    g.chatTmpl = tmpl ? tmpl : "";

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx           = (uint32_t) ctxSize;
    cp.n_batch         = BATCH_CAP;
    cp.n_ubatch        = BATCH_CAP;
    cp.n_threads       = nThreads > 0 ? nThreads : 4;
    cp.n_threads_batch = cp.n_threads;
    g.ctx = llama_init_from_model(g.model, cp);
    if (!g.ctx) { LOGE("ctx FAILED"); g.unloadLocked(); return JNI_FALSE; }

    LOGI("loaded: tmpl_len=%zu n_ctx=%u nvocab=%d threads=%d",
         g.chatTmpl.size(), llama_n_ctx(g.ctx), llama_vocab_n_tokens(g.vocab),
         cp.n_threads);
    return JNI_TRUE;
}

// translate(prompt, maxTokens, temp, topK, topP, repPen, seed) -> UTF-8 bytes; empty = failure
// temp<=0 = 贪婪（原语义）；temp>0 = v0.3.7 A4 官方采样链 temp→top-k→top-p + rep（Kotlin
// 默认传 0.7/20/0.6/1.05 = Hy-MT README 推荐）。seed 由 Kotlin 传入。
// （返回 byte[] 而非 String：模型输出可能含 emoji 等补充平面字符，NewStringUTF
//   要求 Modified UTF-8，标准 4 字节序列会被 CheckJNI abort——模拟器实测崩溃）
JNIEXPORT jbyteArray JNICALL
Java_id_co_watranslate_LocalEngine_nativeTranslate(JNIEnv * env, jclass, jstring prompt,
                                                   jint maxTokens, jfloat temp,
                                                   jint topK, jfloat topP, jfloat repPen,
                                                   jlong seed) {
    auto fail = [&]() -> jbyteArray {
        jbyteArray e = env->NewByteArray(0); return e;
    };
    std::lock_guard<std::mutex> lk(g.mu);
    if (!g.ctx) { LOGE("translate before load"); return fail(); }

    // v0.3.7 A1：旧版此处全清 KV；改到 tokens 出来后按公共前缀条件化清理（见下）。

    // seed=0 → 时间熵（Kotlin 调用方一般不传；重试链路每句随机化）
    uint64_t useSeed = (uint64_t) seed;
    if (useSeed == 0)
        useSeed = (uint64_t) std::chrono::steady_clock::now().time_since_epoch().count();

    std::string pstr = jstrToStdUtf8(env, prompt);

    // wrap into the model's chat template. NOTE: llama_chat_apply_template's
    // builtin detector matches HUNYUAN_VL before HUNYUAN_DENSE (both markers are
    // present in the dense jinja) and renders a WRONG structure -> deterministic
    // mojibake (emulator forensics 2026-09-22: tokens [BOS,content…,120006]).
    // Render the dense jinja structure manually: BOS + <｜hy_User｜>p<｜hy_Assistant｜>
    // — byte-identical to llama-server's 28-token chat path.
    std::string formatted;
    static const char kHyBegin[] = "<｜hy_begin▁of▁sentence｜>";
    static const char kHyUser[]  = "<｜hy_User｜>";
    static const char kHyAsst[]  = "<｜hy_Assistant｜>";
    if (g.chatTmpl.find("hy_User") != std::string::npos &&
        g.chatTmpl.find("hy_place") != std::string::npos) {
        formatted = std::string(kHyBegin) + kHyUser + pstr + kHyAsst;
        pstr = formatted;
    } else if (!g.chatTmpl.empty()) {
        std::vector<char> content(pstr.begin(), pstr.end());
        content.push_back('\0');
        llama_chat_message chat[1];
        chat[0].role    = "user";
        chat[0].content = content.data();
        std::vector<char> buf(2 * pstr.size() + 256);
        const int32_t need = llama_chat_apply_template(
            g.chatTmpl.c_str(), chat, 1, /*add_ass=*/ true, buf.data(), (int32_t) buf.size());
        if (need < 0) { LOGE("apply_template rc=%d", need); return fail(); }
        if ((size_t) need >= buf.size()) {
            buf.resize(need + 1);
            const int32_t need2 = llama_chat_apply_template(
                g.chatTmpl.c_str(), chat, 1, true, buf.data(), (int32_t) buf.size());
            if (need2 < 0 || need2 >= (int) buf.size()) {
                LOGE("apply_template retry rc=%d", need2); return fail();
            }
        }
        formatted.assign(buf.data());
        pstr = formatted;
    }

    std::vector<llama_token> tokens;
    // dense path already carries the BOS marker text; the vocab's add_bos is false
    // (verified via /tokenize probe), so add_special has no effect either way.
    if (!encode_locked(pstr, /*add_special=*/ false, tokens) || tokens.empty())
        return fail();
    LOGI("shim-v3 prefill n=%zu first_tok=%d last_tok=%d",
         tokens.size(), (int) tokens.front(), (int) tokens.back());
    if ((int32_t) tokens.size() + maxTokens + 8 > (int32_t) llama_n_ctx(g.ctx)) {
        LOGE("prompt too long: %zu tokens", tokens.size());
        return fail();
    }

    // v0.3.7 A1：prompt 前缀 KV 复用（因果注意力 → 公共 token 前缀的 KV 与全量
    // prefill 数学等价）。上一轮的 prompt token 序列留档 g.lastPrompt；本轮取
    // 逐 token 最长公共前缀 c（留 1 个未缓存 token 保底，防"同一条 prompt 重复翻译"
    // 时 0 输入），seq_rm [c, inf) 清掉上一轮尾巴（旧 prompt 剩余 + 生成段），
    // 从 c 起喂。KV 里 [0,c) 保持原位不动。词典全量注入时 c≈245 → 每条省 ~40s；
    // 提取词典模式 prompt 每轮不同 → c 小（≈11），行为≈旧版全量重算，零回归。
    size_t c = 0;
    const size_t lim = std::min(tokens.size(), g.lastPrompt.size());
    while (c < lim && tokens[c] == g.lastPrompt[c]) c++;
    size_t skip = (c > 0 && c + 1 < tokens.size()) ? c : 0;  // v0.4.0 去 const：seq_rm 兜底需置 0
    auto * mem = llama_get_memory(g.ctx);
    bool reused = true;
    if (!g.kvValid) {
        llama_memory_clear(mem, true);            // 首轮/上轮失败/重载后：全清（旧语义）
        reused = false;
    } else {
        // v0.4.0 seq_rm 兜底（apk-engine-host 同款缺陷已实证修法：LFM 重跑 19/20 句
        // 触发）：seq_rm 返回 false=状态不支持该区间移除，此时 KV 里仍留着上轮尾巴，
        // 从 skip 起续喂 → llama_decode "inconsistent sequence positions" → 整句 0 输出。
        reused = llama_memory_seq_rm(mem, 0, (llama_pos) skip, -1);
        if (!reused) { llama_memory_clear(mem, true); skip = 0; }  // 全清重灌（旧语义）
    }
    LOGI("kv-reuse skip=%zu/%zu c=%zu valid=%d reused=%d",
         skip, tokens.size(), c, (int) g.kvValid, (int) reused);

    std::string out;
    const bool genOk = generate_locked(tokens, skip, maxTokens,
                                       (float) temp, topK, topP, repPen, useSeed, out);
    // 留档本轮 prompt 全长供下轮前缀比对；成功才置 kvValid（失败下轮全清重灌）。
    g.lastPrompt = tokens;
    g.kvValid = genOk;
    if (!genOk) return fail();

    const size_t a = out.find_first_not_of(" \t\r\n");
    if (a == std::string::npos) out.clear();
    else { const size_t b = out.find_last_not_of(" \t\r\n"); out = out.substr(a, b - a + 1); }

    jbyteArray arr = env->NewByteArray((jsize) out.size());
    if (arr) env->SetByteArrayRegion(arr, 0, (jsize) out.size(),
                                     (const jbyte *) out.data());
    return arr;
}

JNIEXPORT void JNICALL
Java_id_co_watranslate_LocalEngine_nativeUnload(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lk(g.mu);
    g.unloadLocked();
    LOGI("unloaded");
}

JNIEXPORT jboolean JNICALL
Java_id_co_watranslate_LocalEngine_nativeIsLoaded(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lk(g.mu);
    return g.ctx ? JNI_TRUE : JNI_FALSE;
}

} // extern C
