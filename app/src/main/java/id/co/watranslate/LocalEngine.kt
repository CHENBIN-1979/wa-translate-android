package id.co.watranslate

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore

/**
 * Hy-MT2-1.8B 本地引擎（llama.cpp hymt-unified，M1d 骨架；v0.3.6 起 MT2，
 * chat_template 与 MT1.5 逐字节相同——native 渲染层零改动）。
 *
 * 加载链：System.loadLibrary("local_engine") → linker 递归 NEEDED 自动带出
 * libllama/libggml/libggml-base/libomp（全部在 jniLibs）→ nativeInit 再
 * dlopen 本机指令集最优的 libggml-cpu-android_armvX_Y 后端变体。
 *
 * 与云端 Engine 相同的调用形状：buildPrompt(text, target) → translate(prompt)。
 */
object LocalEngine {

    private const val TAG = "wTranslate"
    const val CTX_SIZE   = 2048
    const val MAX_TOKENS = 256

    @Volatile private var backendsOk = false
    @Volatile private var loaded      = false
    @Volatile private var lastUseMs   = 0L
    private val lock = Semaphore(1)          // 串行化推理（native 层另有 mutex，双保险）

    private external fun nativeInit(libDir: String): Boolean
    private external fun nativeLoad(modelPath: String, ctxSize: Int, nThreads: Int): Boolean
    private external fun nativeTranslate(prompt: String, maxTokens: Int, temp: Float,
                                         topK: Int, topP: Float, repPen: Float,
                                         seed: Long): ByteArray
    private external fun nativeUnload()
    private external fun nativeIsLoaded(): Boolean

    @Volatile private var libsOk = false

    /**
     * 加载 .so 链。先试常规 loadLibrary；失败则按 nativeLibraryDir 绝对路径
     * System.load 逐层显式加载（兄弟库互解析的确定性兜底）。
     */
    private fun loadAllLibs(context: Context): Boolean {
        if (libsOk) return true
        try {
            System.loadLibrary("local_engine")
            libsOk = true; return true
        } catch (_: UnsatisfiedLinkError) {
            Log.i(TAG, "LocalEngine loadLibrary 失败，走绝对路径加载")
        }
        val dir = context.applicationInfo.nativeLibraryDir
        return try {
            System.load("$dir/libomp.so")
            System.load("$dir/libggml-base.so")
            System.load("$dir/libggml.so")
            System.load("$dir/libllama.so")
            System.load("$dir/liblocal_engine.so")
            libsOk = true
            Log.i(TAG, "LocalEngine 绝对路径加载 OK")
            true
        } catch (t: Throwable) {
            Log.i(TAG, "LocalEngine native 加载失败: $t")
            false
        }
    }

    /** dlopen CPU 后端变体；进程生命周期一次即可 */
    @Synchronized
    fun init(context: Context): Boolean {
        if (backendsOk) return true
        if (!loadAllLibs(context)) return false
        backendsOk = nativeInit(context.applicationInfo.nativeLibraryDir)
        Log.i(TAG, "LocalEngine.init backends=$backendsOk")
        return backendsOk
    }

    /** 加载 GGUF（首次约数秒； mmap，不占 Java 堆） */
    @Synchronized
    fun load(context: Context, modelPath: String): Boolean {
        if (loaded) return true
        if (!init(context)) return false
        // v0.3.1 ③：线程数 SP 可调（big.LITTLE 机器上"核数越多越快"不成立，
        // 真机可自行试 4/6/8；0/未设=旧默认 min(核数,8)≥2）
        val spThreads = context.getSharedPreferences("wtrans", Context.MODE_PRIVATE)
            .getInt("localThreads", 0)
        val threads = if (spThreads in 2..16) spThreads
            else Runtime.getRuntime().availableProcessors().coerceAtMost(8).coerceAtLeast(2)
        loaded = nativeLoad(modelPath, CTX_SIZE, threads)
        Log.i(TAG, "LocalEngine.load=$loaded path=${File(modelPath).name} threads=$threads")
        lastUseMs = System.currentTimeMillis()
        return loaded
    }

    fun isLoaded(): Boolean = loaded && nativeIsLoaded()

    /**
     * 闲置超时卸载。注意：unload 用 tryAcquire **不阻塞** —— 该函数会在主线程被调用
     * （Service 60s 巡检 / 设置页切模式），而一次本地推理在慢机器上可长达分钟级，
     * 阻塞 acquire = ANR。拿不到锁=翻译进行中 → 跳过本次卸载，下次巡检再来。
     */
    fun maybeIdleUnload(idleMs: Long) {
        if (loaded && System.currentTimeMillis() - lastUseMs > idleMs
            && lock.tryAcquire()) {
            try { nativeUnload(); loaded = false; Log.i(TAG, "LocalEngine 闲置卸载") }
            finally { lock.release() }
        }
    }

    @Synchronized
    fun unload() {
        if (!loaded) return
        if (lock.tryAcquire()) {
            try { nativeUnload(); loaded = false } finally { lock.release() }
        } else {
            Log.i(TAG, "LocalEngine.unload 跳过（推理进行中，稍后自动卸载）")
        }
    }

    /** 官方模板（v0.3.7 A2：两方向均官方中文原文，与 grammar_bat4 off_default() 逐字；
     *  target="id"|"zh-CN"。此前 id→zh 用自编英文行，非 README 原文）。 */
    fun buildPrompt(text: String, target: String): String =
        if (target == "id")
            "将以下文本翻译为印度尼西亚语，注意只需要输出翻译后的结果，不要额外解释：\n\n$text"
        else
            "将以下文本翻译为中文，注意只需要输出翻译后的结果，不要额外解释：\n\n$text"

    /** 单条翻译；"" = 失败（调用方走云端兜底逻辑在 M3 接入） */
    fun translate(text: String): String {
        if (text.isBlank()) return ""
        val target = Lang.detectTarget(text)
        return translatePrompt(buildPrompt(text, target))
    }

    /** v0.3.7 A4 官方采样默认值（腾讯 Hy-MT README 推荐，四轮电池以此跑通）：
     *  temp=0.7 / top_k=20 / top_p=0.6 / repetition_penalty=1.05。
     *  temp=0 → 贪婪（保留给测试/冒烟的确定性断言）；temp>0 → 官方采样链
     *  （native：rep → temperature → top-k → top-p）。seed=0 → native 取时间熵。 */
    const val OFFICIAL_TEMP = 0.7f
    const val OFFICIAL_TOP_K = 20
    const val OFFICIAL_TOP_P = 0.6f
    const val OFFICIAL_REP_PEN = 1.05f

    fun translatePrompt(prompt: String, temp: Float = 0f, seed: Long = 0L): String =
        translatePrompt(prompt, temp, seed, OFFICIAL_TOP_K, OFFICIAL_TOP_P, OFFICIAL_REP_PEN)

    fun translatePrompt(prompt: String, temp: Float, seed: Long,
                        topK: Int, topP: Float, repPen: Float): String {
        if (!loaded) return ""
        lastUseMs = System.currentTimeMillis()
        lock.acquire()
        return try {
            val t0 = System.currentTimeMillis()
            val bytes = nativeTranslate(prompt, MAX_TOKENS, temp, topK, topP, repPen, seed)
            val out = if (bytes.isEmpty()) "" else String(bytes, Charsets.UTF_8)
            Log.i(TAG, "LocalEngine.translate ${out.length}chars ${System.currentTimeMillis() - t0}ms" +
                    if (temp > 0f) " temp=$temp k=$topK p=$topP rep=$repPen" else "")
            out
        } finally { lock.release() }
    }

    fun translateBatch(texts: List<String>): List<String> = texts.map { translate(it) }
}

/**
 * M1d 冒烟：debug 包且 /data/local/tmp/wt-local-smoke 存在时，服务启动后台跑
 * 一条真实中→印尼翻译进 logcat。不碰任何现有翻译路径；release 永不触发。
 * 模型文件放 /data/local/tmp/hy125.gguf（adb push）。
 */
object LocalSmoke {
    private const val FLAG_FILE  = "/data/local/tmp/wt-local-smoke"
    private const val MODEL_125  = "/data/local/tmp/hy125.gguf"
    private const val MODEL_2B   = "/data/local/tmp/hy2b.gguf"
    private const val TAG = "wTranslate"

    fun maybeRun(context: Context) {
        val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!debuggable || !File(FLAG_FILE).exists()) return
        Executors.newSingleThreadExecutor().execute {
            try {
                val model = when {
                    File(MODEL_125).exists() -> MODEL_125
                    File(MODEL_2B).exists()  -> MODEL_2B
                    else -> { Log.i(TAG, "SMOKE skip: no gguf in /data/local/tmp"); return@execute }
                }
                Log.i(TAG, "SMOKE load $model")
                if (!LocalEngine.load(context, model)) { Log.i(TAG, "SMOKE FAIL load"); return@execute }
                val out = LocalEngine.translate("A009机器修理完成了吗？")
                Log.i(TAG, "SMOKE result=[$out]")
                Log.i(TAG, if (out.isNotBlank() && !Regex("[\\u4e00-\\u9fff]").containsMatchIn(out)) "SMOKE PASS" else "SMOKE FAIL")
                // v0.3.1 ④：采样解码路径冒烟（闸门重试专用；贪婪路径 M0 已验证）。
                // 强断言=①同 seed 两次输出逐字节一致（seed 真控制 RNG）②换 seed 大概率不同
                // （采样真随机）③非空不崩。长句提高差异检测灵敏度。
                val sPrompt = LocalEngine.buildPrompt(
                    "Forklif no.18 sparepart sudah datang, tapi belum dipasang. Tolong cek juga stok kopling.", "zh-CN")
                val sampA = LocalEngine.translatePrompt(sPrompt, 0.5f, 42L)
                val sampB = LocalEngine.translatePrompt(sPrompt, 0.5f, 42L)
                val sampC = LocalEngine.translatePrompt(sPrompt, 0.5f, 7L)
                Log.i(TAG, "SMOKE-SAMP a=[$sampA]")
                Log.i(TAG, "SMOKE-SAMP b=[$sampB]")
                Log.i(TAG, "SMOKE-SAMP c=[$sampC]")
                when {
                    sampA.isBlank() || sampB.isBlank() -> Log.i(TAG, "SMOKE-SAMP FAIL empty")
                    sampA != sampB -> Log.i(TAG, "SMOKE-SAMP FAIL seed-nondeterminism")
                    sampA == sampC -> Log.i(TAG, "SMOKE-SAMP WARN same-across-seeds（可能仍正常，人工判读）")
                    else -> Log.i(TAG, "SMOKE-SAMP PASS")
                }
            } catch (t: Throwable) {
                Log.i(TAG, "SMOKE crash: $t")
            }
        }
    }
}
