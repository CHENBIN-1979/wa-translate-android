package id.co.watranslate

import android.content.Context
import android.net.TrafficStats
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/*
 * ModelManager.kt — M2 应用内模型下载器（零第三方依赖，纯 HttpURLConnection）。
 *
 * 注册表（2026-09-24 MT2 换代锁定；勿改文件名——仓库各有同名不同量化陷阱文件）：
 *   2bit  = Hy-MT2-1.8B-2Bit.gguf   600,534,880B  sha dcc33bba...1cd7（app 默认）
 *   1.25b = Hy-MT2-1.8B-1.25Bit.gguf 461,860,800B  sha cc497fe8...7a93
 * HF / ModelScope 两源同一 sha 已经 Range 抽样字节比对 = 同一次量化产物。
 *
 * 断点续传：下到 .part；重连带 Range: bytes=N-；回 206 续传，回 200 整文件重来。
 * 校验：完成后流式 sha256，通过才改名转正 + sp 记「已验证」（ready 检查免重算）。
 * 容错：任务内自动重试 5 次（退避 2/4/8/16/30s）；网络抖动无需用户干预。
 */
object ModelManager {

    private const val TAG = "wTranslate"
    private const val MAX_ATTEMPTS = 5

    enum class Source { HUGGINGFACE, MODELSCOPE }
    enum class State { IDLE, RUNNING, VERIFYING, READY, FAILED }

    class ModelSpec(
        val id: String,            // "hy2b" | "hy125" | smoke 自定义
        val title: String,         // UI 名
        val fileName: String,      // 服务端与本地统一文件名
        val sizeBytes: Long,
        val sha256: String,
        val urls: Map<Source, String>,
    )

    class Progress(
        val specId: String, val state: State,
        val done: Long, val total: Long,
        val bytesPerSec: Long, val note: String,
    )

    // ---- 注册表 ----
    // v0.3.6：Hy-MT1.5 → Hy-MT2 直接换代（用户拍板，旧模型不进注册表）。
    // ⚠ 同名不同量化陷阱（2026-09-24 HF API 实测）：2Bit 仓库并存 2Bit/2bit/2bit-v2
    //   三份、1.25Bit 仓库并存 1.25Bit/1.25bit-v2 两份，字节数彼此只差几十到一百字节。
    //   锁死「源×文件名×sha」三元组；双源 Range 抽样（头 4MB+中段 4MB）比对一致：
    //   2Bit=c1edfe24dfc2… 1.25Bit=312024054d5f…（HF 与 ModelScope 同产物）。
    //   1.25Bit 本地件全量 sha 已验=cc497fe8f033…（9/23 下载件为正品）。
    // 兼容性（9/23 GGUF 头部实测）：architecture=hunyuan-dense、chat_template 与
    //   MT1.5 逐字节相同、BOS/EOS=120000/120020 同 → native/loader 零改动。
    // ready 判定=model_ok_<id> 记 sha：换表后旧值≠新 sha 自动失效，重新校验/下载。

    val HY2B = ModelSpec(
        id = "hy2b", title = "2bit（推荐 · 572MB）",
        fileName = "Hy-MT2-1.8B-2Bit.gguf", sizeBytes = 600_534_880L,
        sha256 = "dcc33bbae9b28d923c8c76a64f6157840841d26f8774f3dfd770d5fabeeb1cd7",
        urls = mapOf(
            Source.HUGGINGFACE to "https://huggingface.co/AngelSlim/Hy-MT2-1.8B-2Bit-GGUF/resolve/main/Hy-MT2-1.8B-2Bit.gguf",
            Source.MODELSCOPE to "https://modelscope.cn/models/AngelSlim/Hy-MT2-1.8B-2Bit-GGUF/resolve/master/Hy-MT2-1.8B-2Bit.gguf",
        ),
    )

    val HY125 = ModelSpec(
        id = "hy125", title = "1.25bit（省内存 · 440MB）",
        fileName = "Hy-MT2-1.8B-1.25Bit.gguf", sizeBytes = 461_860_800L,
        sha256 = "cc497fe8f033b52b3b8b00a7669e9661435432f9d4cd43f7ed24400c01507a93",
        urls = mapOf(
            Source.HUGGINGFACE to "https://huggingface.co/AngelSlim/Hy-MT2-1.8B-1.25Bit-GGUF/resolve/main/Hy-MT2-1.8B-1.25Bit.gguf",
            Source.MODELSCOPE to "https://modelscope.cn/models/AngelSlim/Hy-MT2-1.8B-1.25Bit-GGUF/resolve/master/Hy-MT2-1.8B-1.25Bit.gguf",
        ),
    )

    // v0.4.0：Q4_K_M 注册（模拟器同件实测 tg 23.9 t/s = 2Bit 的 3~8 倍、质量与
    // 2Bit 打平 18/20，APK 引擎双源 Range 抽样+全量 sha 核验）。
    // ⚠ 源=官方 tencent/Hy-MT2-1.8B-GGUF 仓；ModelScope AngelSlim 的
    //   Hy-MT2-1.8B-Q4.gguf（1,133,080,736B）是【另一量化产物】，sha 不同，
    //   绝不能当镜像挂上（同名不同量化陷阱再犯）。无 ms 源时下载调度
    //   自动回落 HUGGINGFACE（runDownload: urls[sourceOf] ?: urls[HF]）。
    val HYQ4 = ModelSpec(
        id = "hyq4", title = "Q4_K_M（最快 · 1080MB）",
        fileName = "Hy-MT2-1.8B-Q4_K_M.gguf", sizeBytes = 1_133_080_448L,
        sha256 = "dc5f44fcf1fa496ee7ad725982c0c8c553a4de00259b53af84c4b89fb0c06699",
        urls = mapOf(
            Source.HUGGINGFACE to "https://huggingface.co/tencent/Hy-MT2-1.8B-GGUF/resolve/main/Hy-MT2-1.8B-Q4_K_M.gguf",
        ),
    )

    val SPECS: List<ModelSpec> = listOf(HY2B, HY125, HYQ4)

    fun spec(id: String): ModelSpec? = SPECS.firstOrNull { it.id == id }

    // ---- 文件布局：app 私有目录（卸载即净；cover-install 升级保留）----

    fun modelsDir(context: Context): File =
        File(context.filesDir, "models").apply { mkdirs() }

    fun modelFile(context: Context, s: ModelSpec): File = File(modelsDir(context), s.fileName)
    private fun partFile(context: Context, s: ModelSpec): File = File(modelsDir(context), s.fileName + ".part")

    /** 就绪 = 正式文件在 + 大小对 + 曾通过 sha256。 */
    fun isReady(context: Context, s: ModelSpec): Boolean {
        val f = modelFile(context, s)
        if (!f.isFile || f.length() != s.sizeBytes) return false
        val sp = context.getSharedPreferences("wtrans", Context.MODE_PRIVATE)
        return sp.getString("model_ok_" + s.id, null) == s.sha256.lowercase()
    }

    /** 部分下载字节（.part 存在且在合理区间）。 */
    fun partBytes(context: Context, s: ModelSpec): Long {
        val p = partFile(context, s)
        return if (p.isFile && p.length() in 1 until s.sizeBytes) p.length() else 0L
    }

    fun sourceOf(context: Context): Source =
        if (context.getSharedPreferences("wtrans", Context.MODE_PRIVATE).getString("model_src", "hf") == "ms")
            Source.MODELSCOPE else Source.HUGGINGFACE

    fun setSource(context: Context, src: Source) {
        context.getSharedPreferences("wtrans", Context.MODE_PRIVATE).edit()
            .putString("model_src", if (src == Source.MODELSCOPE) "ms" else "hf").apply()
    }

    // ---- 下载调度（单任务后台线程；UI 经 listener 收主线程进度回调）----

    private val exec = Executors.newSingleThreadExecutor()
    private val mainH = Handler(Looper.getMainLooper())
    @Volatile private var activeId: String? = null
    @Volatile private var cancelReq = AtomicBoolean(false)
    @Volatile private var listener: ((Progress) -> Unit)? = null

    fun setListener(l: ((Progress) -> Unit)?) { listener = l }
    fun busy(): Boolean = activeId != null
    fun activeModelId(): String? = activeId

    fun delete(context: Context, s: ModelSpec) {
        if (activeId == s.id) cancelReq.set(true)
        runCatching { modelFile(context, s).delete() }
        runCatching { partFile(context, s).delete() }
        context.getSharedPreferences("wtrans", Context.MODE_PRIVATE).edit().remove("model_ok_" + s.id).apply()
        Log.i(TAG, "M2 模型删除 ${s.id}")
        LogBuf.log("M2 模型删除 ${s.id}")
    }

    /**
     * 启动下载。幂等：已有任务在跑则拒绝并提示。
     * @param forceUrl 非空则无视源表直连（debug smoke 走 mock 服务器）。
     */
    fun download(context: Context, s: ModelSpec, forceUrl: String? = null): Boolean {
        if (activeId != null) {
            emit(Progress(s.id, State.FAILED, 0, s.sizeBytes, 0, "已有下载任务在跑（${activeId}），先等它结束"))
            return false
        }
        val app = context.applicationContext
        runCatching {
            val need = s.sizeBytes + 50_000_000L
            val st = StatFs(app.filesDir.path)
            if (st.availableBytes < need) {
                emit(Progress(s.id, State.FAILED, 0, s.sizeBytes, 0,
                    "存储空间不足（需约 ${"%.0f".format(need / 1048576.0)} MB）"))
                return false
            }
        }
        val url = forceUrl ?: s.urls[sourceOf(app)] ?: s.urls[Source.HUGGINGFACE]
        if (url == null) {
            emit(Progress(s.id, State.FAILED, 0, s.sizeBytes, 0, "该模型无可用源地址"))
            return false
        }
        activeId = s.id; cancelReq = AtomicBoolean(false)
        exec.execute { runDownload(app, s, url) }
        return true
    }

    fun cancel() { cancelReq.set(true) }

    private fun runDownload(app: Context, s: ModelSpec, urlStr: String) {
        TrafficStats.setThreadStatsTag(0x57540001)  // 非零 tag（联网统计归组，便于抓包定位）
        val part = partFile(app, s)
        var attempt = 0
        var lastErr = "未知错误"
        try {
            while (attempt < MAX_ATTEMPTS && !cancelReq.get()) {
                attempt++
                var conn: HttpURLConnection? = null
                try {
                    val from = if (part.isFile) part.length().coerceAtMost(s.sizeBytes - 1) else 0L
                    conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 15_000; readTimeout = 30_000
                        instanceFollowRedirects = true
                        setRequestProperty("User-Agent", "wa-translate/0.3")
                        if (from > 0) setRequestProperty("Range", "bytes=$from-")
                    }
                    conn.connect()
                    val code = conn.responseCode
                    var startFrom = 0L
                    when {
                        code == 206 && from > 0 -> startFrom = from                 // 续传
                        code == 206 -> { /* 罕见：0- range，等价全新 */ }
                        code == 200 -> { part.delete(); startFrom = 0L }            // 不支持 Range：重来
                        else -> error("HTTP $code")
                    }
                    emit(Progress(s.id, State.RUNNING, startFrom, s.sizeBytes, 0,
                        if (startFrom > 0) "从 ${mb(startFrom)} 处续传…" else "连接成功，下载中…"))
                    val written = streamTo(part, conn, s, startFrom)
                    if (cancelReq.get()) break  // 暂停：保住 .part，交给循环外的暂停分支
                    if (written != s.sizeBytes) { part.delete(); error("大小不符 $written/${s.sizeBytes}（已清，重下）") }
                    emit(Progress(s.id, State.VERIFYING, s.sizeBytes, s.sizeBytes, 0, "校验 sha256 中…（约十几秒）"))
                    val actual = sha256OfFile(part)
                    if (!actual.equals(s.sha256, true)) {
                        part.delete()
                        error("sha256 不匹配（文件已丢弃，重新下载）")
                    }
                    val dst = modelFile(app, s)
                    runCatching { dst.delete() }
                    if (!part.renameTo(dst)) error("改名失败")
                    app.getSharedPreferences("wtrans", Context.MODE_PRIVATE).edit()
                        .putString("model_ok_" + s.id, s.sha256.lowercase()).apply()
                    Log.i(TAG, "M2 模型就绪 ${s.id} ${dst.length()}B")
                    LogBuf.log("M2 模型就绪 ${s.id}")
                    emit(Progress(s.id, State.READY, s.sizeBytes, s.sizeBytes, 0, "✔ 下载并校验完成"))
                    return
                } catch (t: Throwable) {
                    if (cancelReq.get()) break
                    lastErr = t.message ?: t.javaClass.simpleName
                    Log.i(TAG, "M2 下载异常(第 $attempt 次) ${s.id}: $lastErr")
                    LogBuf.log("M2 下载异常(第$attempt 次) ${s.id}: $lastErr")
                    if (attempt < MAX_ATTEMPTS) {
                        emit(Progress(s.id, State.RUNNING, part.length(), s.sizeBytes, 0,
                            "$lastErr → 自动重试（$attempt/$MAX_ATTEMPTS）"))
                        Thread.sleep(longArrayOf(2_000, 4_000, 8_000, 16_000, 30_000)[(attempt - 1).coerceAtMost(4)])
                    }
                } finally {
                    runCatching { conn?.disconnect() }
                }
            }
            if (cancelReq.get()) {
                Log.i(TAG, "M2 已暂停 ${s.id} @${part.length()}")
                emit(Progress(s.id, State.IDLE, part.length(), s.sizeBytes, 0, "已暂停（进度保留，可续传）"))
            } else {
                Log.i(TAG, "M2 最终失败 ${s.id}: $lastErr")
                LogBuf.log("M2 最终失败 ${s.id}: $lastErr")
                emit(Progress(s.id, State.FAILED, part.length(), s.sizeBytes, 0, "✘ $lastErr（再点下载即续传）"))
            }
        } finally {
            activeId = null
        }
    }

    /** 流式写 .part；返回当前总字节。每 300ms 汇报一次带速度。 */
    private fun streamTo(part: File, conn: HttpURLConnection, s: ModelSpec, startFrom: Long): Long {
        FileOutputStream(part, startFrom > 0).use { fo ->
            val input = conn.inputStream
            val buf = ByteArray(128 * 1024)
            var done = startFrom
            var lastReport = System.currentTimeMillis()
            var lastBytes = done
            var speed = 0L
            while (true) {
                if (cancelReq.get()) { fo.flush(); return done }
                val n = input.read(buf)
                if (n < 0) break
                fo.write(buf, 0, n); done += n
                val now = System.currentTimeMillis()
                if (now - lastReport >= 300) {
                    val dt = now - lastReport
                    if (dt > 0) speed = (done - lastBytes) * 1000 / dt
                    lastReport = now; lastBytes = done
                    emit(Progress(s.id, State.RUNNING, done, s.sizeBytes, speed, ""))
                }
            }
            runCatching { input.close() }
            return done
        }
    }

    private fun sha256OfFile(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = ins.read(buf); if (n < 0) break; md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun mb(b: Long) = String.format(java.util.Locale.US, "%.1f MB", b / 1048576.0)

    private fun emit(p: Progress) {
        val l = listener ?: return
        if (Looper.myLooper() == Looper.getMainLooper()) l(p) else mainH.post { l(p) }
    }
}

/**
 * M2 冒烟（debug 包 + /data/local/tmp/wt-model-smoke 触发，release 永不跑）：
 * 前置：PC 侧 android-test/static_range_server.py（:8788，支持 /sha /control 限速/掐线）
 *      + `adb reverse tcp:8788 tcp:8788`。
 * 链路：限速下载 → 3MB 处人工暂停（.part 保留）→ 续传（Range 206）→ 续传中途服务器
 *      掐线 → 自动重试再续传 → sha256 → 改名 → isReady → 删除 → 复查干净。
 */
object ModelSmoke {
    private const val TAG = "wTranslate"
    private const val FLAG = "/data/local/tmp/wt-model-smoke"
    private const val BASE = "http://127.0.0.1:8788"

    private fun control(qs: String) {
        try {
            (URL("$BASE/control?$qs").openConnection() as HttpURLConnection)
                .inputStream.use { it.readBytes() }
        } catch (_: Throwable) {}
    }

    fun maybeRun(context: Context) {
        val debuggable = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!debuggable || !File(FLAG).exists()) return
        Thread {
            try {
                val app = context.applicationContext
                val sha = (URL("$BASE/sha?f=smoke32mb.bin").openConnection() as HttpURLConnection)
                    .inputStream.bufferedReader().readText().trim()
                if (sha.length != 64) { Log.i(TAG, "SMOKE-M2 FAIL bad /sha"); return@Thread }
                val spec = ModelManager.ModelSpec(
                    id = "smoke32mb", title = "smoke", fileName = "smoke32mb.gguf",
                    sizeBytes = 32_000_000L, sha256 = sha, urls = emptyMap(),
                )
                ModelManager.delete(app, spec)
                Log.i(TAG, "SMOKE-M2 start（32MB @200KB/s，3MB 处暂停）")
                val latch = java.util.concurrent.CountDownLatch(1)
                var pausedAt = -1L
                val cancelFired = java.util.concurrent.atomic.AtomicBoolean(false)
                ModelManager.setListener { p ->
                    if (p.specId != spec.id) return@setListener
                    if (p.state == ModelManager.State.RUNNING && p.done >= 3_000_000 &&
                        !cancelFired.getAndSet(true)) ModelManager.cancel()
                    if (p.state == ModelManager.State.READY) latch.countDown()
                    if (p.state == ModelManager.State.IDLE && p.note.contains("暂停")) pausedAt = p.done
                }
                // ---- 阶段 1：限速下载，3MB 处人工暂停 ----
                control("rate=200000&fail_after=0")
                ModelManager.download(app, spec, "$BASE/smoke32mb.bin")
                val t0 = System.currentTimeMillis()
                while (ModelManager.busy() && System.currentTimeMillis() - t0 < 60_000) Thread.sleep(200)
                val part1 = ModelManager.partBytes(app, spec)
                if (pausedAt < 1_000_000 || part1 < 1_000_000) {
                    Log.i(TAG, "SMOKE-M2 FAIL 暂停未生效 pausedAt=$pausedAt part=$part1"); return@Thread
                }
                Log.i(TAG, "SMOKE-M2 人工暂停 OK @${part1}（.part 保留）")
                // ---- 阶段 2：续传 + 续传中途服务器掐线 -> 自动重试再续传 ----
                control("rate=0&fail_after=${part1 + 500_000}&fail_times=1")
                ModelManager.download(app, spec, "$BASE/smoke32mb.bin")
                if (!latch.await(150, java.util.concurrent.TimeUnit.SECONDS)) {
                    Log.i(TAG, "SMOKE-M2 FAIL 续传超时"); return@Thread
                }
                val ready = ModelManager.isReady(app, spec)
                // ---- 阶段 3：删除 ----
                control("rate=0&fail_after=0")
                ModelManager.delete(app, spec)
                Thread.sleep(300)
                val gone = !ModelManager.isReady(app, spec) &&
                    !File(ModelManager.modelsDir(app), spec.fileName).exists() &&
                    ModelManager.partBytes(app, spec) == 0L
                Log.i(TAG, "SMOKE-M2 result ready=$ready cleaned=$gone")
                Log.i(TAG, if (ready && gone) "SMOKE-M2 PASS" else "SMOKE-M2 FAIL")
                ModelManager.setListener(null)
            } catch (t: Throwable) {
                Log.i(TAG, "SMOKE-M2 crash: $t")
            }
        }.start()
    }
}
