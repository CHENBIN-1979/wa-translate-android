package id.co.watranslate

import android.content.Context
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/*
 * Engine.kt — 翻译引擎（对标扩展 background.js）：
 *   批量队列(300ms 窗口) + LRU 缓存 + 逐条降级 + 自愈（回显/失败不缓存）
 *   + per-text forget（⟳ 强制重译）。网络用 HttpURLConnection（零依赖）。
 */

class Engine(private val context: Context) {

    companion object { private const val CACHE_MAX = 5000 }   // 同扩展上限量级

    data class Request(val texts: List<String>, val cb: (List<String?>, List<String?>) -> Unit)

    private val cache = ConcurrentHashMap<String, String>()      // src → 译文（v0.1.7 起持久化到磁盘）
    private val failedCache = ConcurrentHashMap<String, Long>()  // src → 失败时间戳（60s 冷却，防打爆 API；内存不落盘）
    private val executor = Executors.newCachedThreadPool()

    val cacheSize: Int get() = cache.size

    // ---- v0.1.7：缓存落盘 SharedPreferences，杀进程/重启不丢，重复句 0ms ----
    // v0.3.3 ③：文件名 v1→v2 换代=一次性整体作废。真机 09-23 日志实证：发送者名/「已转发」
    // 污染的 src 键（"陈副厂长\n已转发\nmasalah…"）的回声译文已落盘，之后每次点击 cache hit
    // 0ms 秒回垃圾（修了抓取也命中旧毒键）。旧文件保留但永不读取（~几十KB，卸载即净）。
    // v0.3.4：v2→v3 同理——v2 里存着「Bak limbah A002 aman→请查看…确保它是安全的」这类
    // 句级幻觉译文（few-shot 修复前的贪婪输出），不作废则重点击 0ms 命中旧幻觉=修复无效假象。
    // v0.3.6：v3→v4 换代——MT1.5→MT2 引擎换代，v3 里全是旧模型的译文（含术语漂移
    // calender→日历、winder 飘、祈使加戏句）。不作废 → cache hit 永久钉死旧译文，
    // 新引擎质量永远出不来。旧文件保留但永不读取（卸载即净）。
    // v0.3.7：v4→v5 换代——提示词布局换代（官方中文指令行 id→zh / few-shot 4→7 /
    // 词典默认全量注入 / 官方采样）。旧键下存的译文出自旧布局，不作废则 cache hit
    // 0ms 钉死旧版输出=修复无效假象（v0.3.3③/v0.3.4 同款教训）。
    private val cacheSp by lazy { context.getSharedPreferences("wtrans_cache_v5", Context.MODE_PRIVATE) }
    private var diskLoaded = false
    private var lastPersist = 0L
    private val persistLock = Any()

    private fun ensureCacheLoaded() {
        if (diskLoaded) return
        synchronized(persistLock) {
            if (diskLoaded) return
            runCatching {
                @Suppress("UNCHECKED_CAST")
                val m = cacheSp.all as? Map<String, String> ?: emptyMap()
                cache.putAll(m)
            }
            diskLoaded = true
            if (cache.isNotEmpty()) LogBuf.log("缓存载入: " + cache.size + " 条(磁盘)")
        }
    }

    /** 新译文写盘，≥2s 或 ≥50 条增量才 apply 一次（平时零 IO，同扩展 flushCache 思路） */
    private fun persistCache(newItems: List<Pair<String, String>>) {
        if (newItems.isEmpty()) return
        val pending = persistPending
        pending.addAll(newItems)
        val now = System.currentTimeMillis()
        if (now - lastPersist >= 2_000 || pending.size >= 50) {
            lastPersist = now
            val batch = ArrayList(pending); pending.clear()
            thread(name = "wtrans-cache-persist") {
                runCatching {
                    val e = cacheSp.edit()
                    batch.forEach { (k, v) -> e.putString(k, v) }
                    e.apply()
                }
            }
        }
    }
    private val persistPending = java.util.Collections.synchronizedList(ArrayList<Pair<String, String>>())

    /** 淘汰超限：内存+磁盘一起删（FIFO，同扩展 CACHE_MAX 思想） */
    private fun evictCacheIfNeeded() {
        if (cache.size <= CACHE_MAX) return
        runCatching {
            val doomed = Collections.list(cache.keys()).take(cache.size - CACHE_MAX)
            doomed.forEach { cache.remove(it) }
            thread(name = "wtrans-cache-evict") {
                runCatching { val e = cacheSp.edit(); doomed.forEach { e.remove(it) }; e.apply() }
            }
        }
    }

    /** 清空全部翻译缓存（设置页「清空缓存」按钮）——含磁盘 */
    fun clearAllCache() {
        cache.clear(); failedCache.clear()
        persistPending.clear()
        runCatching { cacheSp.edit().clear().apply() }
    }

    // ---------------- 配置 ----------------
    fun loadConfig(): Config {
        val sp = context.getSharedPreferences("wtrans", Context.MODE_PRIVATE)
        return ConfigResolve.resolve(
            profilesJson = sp.getString("profiles", null),
            activeId = sp.getString("activeProfile", ""),
            apiBase = sp.getString("apiBase", null),
            apiKey = sp.getString("apiKey", null),
            model = sp.getString("model", null),
            temperature = (sp.getString("temperature", null) ?: "").toDoubleOrNull(),
            enabled = sp.getBoolean("enabled", true),
            fast = sp.getBoolean("fast", true),
            dictText = sp.getString("dict", "") ?: "",
        )
    }

    fun saveConfig(cfg: Config) {
        context.getSharedPreferences("wtrans", Context.MODE_PRIVATE).edit()
            .putString("profiles", cfg.profiles.joinToString(",", "[", "]") { p ->
                "{\"id\":\"" + Json.escape(p.id) + "\",\"name\":\"" + Json.escape(p.name) +
                    "\",\"apiBase\":\"" + Json.escape(p.apiBase) + "\",\"apiKey\":\"" + Json.escape(p.apiKey) +
                    "\",\"model\":\"" + Json.escape(p.model) + "\",\"temperature\":" + p.temperature + "}"
            })
            .putString("activeProfile", cfg.activeId)
            .apply()
    }

    /** ⟳ 刷新：清该条缓存+失败冷却 → 下一次请求必走 API（同 wtranslate-forget） */
    fun forget(text: String) {
        val k = text.trim()
        cache.remove(k)
        failedCache.remove(k)
        thread(name = "wtrans-cache-forget") { runCatching { cacheSp.edit().remove(k).apply() } }
    }

    /** v0.2.3（用户定案②，实现修订=回显学习）：该文本已学到"模型翻不动、保留原文"？
     *  只查缓存哨兵（Service 在模型真回显+像人名时落哨兵）；**不做主动正则**——
     *  "收到/好的"同为 2-4 汉字会被误杀，而"陈副厂长"实测模型翻得对（Wakil Direktur Chen），
     *  拦截反伤。词典加了该词条并保存（清缓存）即自动解除，回正常翻译路径。 */
    fun isProperName(text: String): Boolean = cache[text.trim()] == Watch.PN_MARK

    /** v0.2.8 问题⑤（真机 9/21「A009修理完成了吗？」5 连点全拒写死锁）：缓存值是否哨兵。
     *  消息行有「点行重译」出口，输入框没有 → 输入框链路命中哨兵必须先 forget 再真重译。 */
    fun cachedSentinel(text: String): Boolean {
        ensureCacheLoaded()
        return Watch.sentinelBypass(cache[text.trim()])
    }

    /** 人名哨兵进缓存+写盘：此后同文本秒判灰显、零 API（省 tokens + 消灭时红时绿） */
    fun persistProperName(text: String) {
        val k = text.trim()
        cache[k] = Watch.PN_MARK
        thread(name = "wtrans-pn-cache") { runCatching { cacheSp.edit().putString(k, Watch.PN_MARK).apply() } }
    }

    /** 保存词典时调用：清掉所有学习哨兵——词典加了这个词条 = 用户要求真翻译，
     *  学习结果让位（兑现「改完词典点一下即生效」语义；真译文回来后重新覆盖哨兵）。
     *  v0.2.8 ②：KEEP（保留原文）哨兵一并清——9/21 真机取证 gearbox/oli 进词典后
     *  旧灰显仍被哨兵卡住零 API，词典意图无法生效（用户感知=「再翻译就无法翻译了」。 */
    fun purgeProperNameCache() {
        val doomed = cache.filterValues { it == Watch.PN_MARK || it == Watch.KEEP_MARK }.keys.toList()
        if (doomed.isEmpty()) return
        doomed.forEach { cache.remove(it) }
        thread(name = "wtrans-pn-purge") {
            runCatching { val ed = cacheSp.edit(); doomed.forEach { ed.remove(it) }; ed.apply() }
        }
        LogBuf.log("学习哨兵清除: " + doomed.size + " 条（词典保存→词条优先）")
    }

    // ---------------- 攒批队列（350ms 窗口合并多请求；结果按各请求长度拆回） ----------------
    private class Slot(val texts: List<String>, val cb: (List<String?>, List<String?>) -> Unit)
    private val slots = ArrayList<Slot>()

    fun translate(text: String, bypassCooling: Boolean = false, cb: (String?, String?) -> Unit) =
        translateBatch(listOf(text), bypassCooling) { ts, es -> cb(ts.getOrNull(0), es.getOrNull(0)) }

    fun translateBatch(texts: List<String>, cb: (List<String?>, List<String?>) -> Unit) =
        translateBatch(texts, false, cb)

    /** v0.3.8 P3：bypassCooling=用户主动触发的翻译（输入框「译」按钮）无视 15s 失败冷却
     *  直接重试。真机取证 9/25 10:23：「找生产的人员对他们进行教」首翻回显→冷却→两次点击
     *  全吃「冷却中」→第三次才成功。后台自动链路（冷却的原设计对象=防打爆 API）保持 false。 */
    fun translateBatch(texts: List<String>, bypassCooling: Boolean, cb: (List<String?>, List<String?>) -> Unit) {
        ensureCacheLoaded()
        val cfg = loadConfig()
        if (!cfg.enabled) { cb(List(texts.size) { null }, List(texts.size) { "翻译已停用" }); return }
        // v0.3.0 M3：引擎模式（SP engineMode，默认 cloud=零行为变化）
        val mode = context.getSharedPreferences("wtrans", Context.MODE_PRIVATE)
            .getString("engineMode", "cloud") ?: "cloud"
        if (mode == "cloud" && cfg.apiKey.isEmpty() && !cfg.apiBase.contains("localhost") && !cfg.apiBase.contains("127.0.0.1")) {
            cb(List(texts.size) { null }, List(texts.size) { "未配置 API Key（打开悬浮翻译助手 APP 设置）" })
            return
        }
        val now = System.currentTimeMillis()
        val outsNull = arrayOfNulls<String>(texts.size)
        val outsErr = arrayOfNulls<String>(texts.size)
        val need = ArrayList<Pair<Int, String>>()   // 原下标 → 待翻文本
        texts.forEachIndexed { i, t ->
            val k = t.trim()
            when {
                k.isEmpty() -> outsNull[i] = ""
                cache.containsKey(k) -> {
                    // v0.2.5（真机 07:41 日志取证：输入框三次 cache hit 但写回结果不可见，缓存值
                    // 也不可见 → 无法裁决缓存内容是否坏；命中行带值，一份日志自证）
                    val cv = cache[k] ?: ""
                    // v0.2.5 双通道：e2e E20c 断言 cache-hit 带值行（LogBuf 只有导出才可见）
                    Log.i("wTranslate", "cache hit: ${k.take(24)} → ${cv.replace('\n', '⎵').take(16)}")
                    LogBuf.log("cache hit: ${k.take(24)} → ${cv.replace('\n', '⎵').take(16)}")
                    outsNull[i] = cache[k]
                }
                (failedCache[k] ?: 0L) > now && !bypassCooling -> { Log.i("wTranslate", "冷却中(<15s): ${k.take(24)}"); LogBuf.log("冷却中(<15s): ${k.take(24)}"); outsErr[i] = "冷却中，稍后自动重试" }
                (failedCache[k] ?: 0L) > now && bypassCooling -> { Log.i("wTranslate", "冷却旁路(用户点译): ${k.take(24)}"); LogBuf.log("冷却旁路(用户点译): ${k.take(24)}"); need.add(Pair(i, k)) }  // v0.3.8 P3
                else -> need.add(Pair(i, k))
            }
        }
        if (need.isEmpty()) { deliver(outsNull, outsErr, cb); return }
        val localCb: (List<String?>, List<String?>) -> Unit = { ts, es ->
            need.forEachIndexed { j, (origIdx, src) ->
                outsNull[origIdx] = ts.getOrNull(j)
                outsErr[origIdx] = es.getOrNull(j)
                if (outsNull[origIdx] != null) failedCache.remove(src)
            }
            deliver(outsNull, outsErr, cb)
        }
        val needTexts = need.map { it.second }
        // ---- M3：本地引擎分支（cloud 模式完全走原路，零回归）----
        if (mode == "local" || mode == "hybrid") {
            thread(name = "wtrans-local-flush") {
                try { runLocalNeed(needTexts, localCb, mode == "hybrid") }
                catch (t: Throwable) {
                    Log.e("wTranslate", "local flush died: ${t.javaClass.simpleName}: ${t.message}", t)
                }
            }
            return
        }
        synchronized(slots) { slots.add(Slot(needTexts, localCb)) }
        scheduleFlush()
    }

    // ---------------- M3 本地引擎链路（Hy-MT2；串行推理 + 按需加载 + 闲置卸载由 Service 定时驱动） ----------------

    /** engineMode 是否要求本地模型在位（设置页提示用）。 */
    fun localEngineRequired(): Boolean {
        val m = context.getSharedPreferences("wtrans", Context.MODE_PRIVATE)
            .getString("engineMode", "cloud") ?: "cloud"
        return m == "local" || m == "hybrid"
    }

    /** 本地链路：逐条 LocalEngine.translate（native 内串行）；hybrid 时单条失败/回显 → 云端 single 兜底。 */
    private fun runLocalNeed(texts: List<String>, cb: (List<String?>, List<String?>) -> Unit, hybrid: Boolean) {
        val cfg = loadConfig()
        val dict = Dict.parse(cfg.dictText)
        // 按需加载模型（首条触发；未下载/加载失败 → 逐条按模式处置）
        var localOk = false
        if (localEngineReady()) localOk = true else {
            Log.i("wTranslate", "本地引擎未就绪：${localEngineUnreadyReason()}")
            LogBuf.log("本地引擎未就绪: ${localEngineUnreadyReason()}")
        }
        val ts = ArrayList<String?>(texts.size)
        val es = ArrayList<String?>(texts.size)
        for (src in texts) {
            var r: String? = null; var e: String? = null
            if (localOk) {
                val t = translateLocalOne(src, dict)
                if (t != null) {
                    r = t
                    cache[src] = t
                    persistCache(listOf(Pair(src, t))); evictCacheIfNeeded()
                    Log.i("wTranslate", "本地命中: ${src.take(24)}")
                    LogBuf.log("本地命中: ${src.take(24)}")
                } else {
                    // 本地失败（回显/空）：hybrid → 云端 single 兜底（走既有回显重试语义）
                    if (hybrid) {
                        val (cr, ce) = singleWithEchoRetry(cfg, dict, src)
                        r = cr; e = ce
                        Log.i("wTranslate", "本地回显→云兜底: ${src.take(24)}")
                        LogBuf.log("本地回显→云兜底: ${src.take(24)}")
                    } else {
                        failedCache[src] = System.currentTimeMillis() + 15_000
                        e = "本地翻译未成功（显示原文），稍后重试"
                    }
                }
            } else if (hybrid) {
                val (cr, ce) = singleWithEchoRetry(cfg, dict, src)
                r = cr; e = ce
            } else {
                e = localEngineUnreadyReason()
            }
            ts.add(r); es.add(e)
        }
        cb(ts, es)
    }

    /** 单条本地翻译（含词典术语介入 + 方向判定）。返回 null=回显/空（失败），否则为译文。
     *  v0.3.1：② 词典按需注入（只拼本条消息实际出现的术语，省 ~150-200 tok 预填充）；
     *  ④ 语言闸门——贪婪输出不过闸 → 自动采样重试一次（低比特模型对个别句子
     *  确定性回显，贪婪重跑一百次同样结果；采样打破确定性），仍不过 → null（不写缓存）。
     *  v0.3.7 A1：词典注入模式开关（SP "dictFull"，默认 **true=全量注入**）——
     *  四轮电池实锤（grammar_bat4.log）：全量 ≥ 提取且略优（MT2 术语5/6、人名2/4、
     *  复读0 vs 提取 5/6、1/4、复读1），提取器本身复核 16/16 无漏、开关只是保险丝。
     *  速度：全量首条 ~40s prefill 由 native prompt 前缀 KV 缓存兜底（local_engine.cpp
     *  v0.3.7 A1），后续每条只算增量；真机首条仍不可接受 → 设置页切回"按需提取"。
     *  v0.3.7 A4：重试采样从 temp=0.5 升级为官方链（0.7/20/0.6/1.05）。 */
    private fun translateLocalOne(src: String, dict: List<id.co.watranslate.Term>): String? {
        val target = Lang.detectTarget(src)   // "id" | "zh-CN"
        val dictFull = context.getSharedPreferences("wtrans", Context.MODE_PRIVATE)
            .getBoolean("dictFull", true)
        val useDict = if (dictFull) dict else LocalPrompt.matchedDict(src, target, dict)
        val prompt = LocalPrompt.withDict(src, target, useDict)
        if (dict.isNotEmpty() && useDict.size != dict.size)
            Log.i("wTranslate", "词典按需: ${dict.size}→${useDict.size}条 ${src.take(16)}")
        var out = LocalEngine.translatePrompt(prompt)
        var t = gateLocal(src, out, target, dict)
        if (t == null && out.isNotBlank()) {
            // 闸门拒绝 → 官方采样重试一次（重试日志走 LogBuf 导出通道，真机可取证）
            Log.i("wTranslate", "本地闸门拒→采样重试: ${src.take(24)}")
            LogBuf.log("本地闸门拒→采样重试: ${src.take(24)}")
            out = LocalEngine.translatePrompt(prompt, LocalEngine.OFFICIAL_TEMP, 0L)
            t = gateLocal(src, out, target, dict)
            if (t != null) LogBuf.log("采样重试成功: ${src.take(24)}")
        }
        return t
    }

    /** 闸门=空/思考泄漏外的语言合格性：非空 ∧ 无示例复读 ∧ 无提示词回显 ∧
     *  Echo 非回显 ∧ LangGate 目标语言字符达标。
     *  v0.3.7 F6：+InstructionEcho——STYLE-LIT 电池实锤模型会把「注意翻译的风格要严格
     *  符合【印尼语语法】」这类指令行整句当译文吐出来，逃过 Echo（与原文不等）与
     *  LangGate（含汉字）直接上屏。命中 → 拒绝 + LogBuf 记 ECHO_REJECT（导出可见）。 */
    private fun gateLocal(src: String, out: String, target: String,
                          dict: List<id.co.watranslate.Term>): String? {
        if (out.isBlank()) return null
        if (target != "id" && LocalPrompt.parrotExamples(out)) {
            LogBuf.log("ECHO_REJECT(示例复读): ${out.take(40)}")
            return null
        }
        InstructionEcho.hit(out)?.let { ph ->
            LogBuf.log("ECHO_REJECT(指令回显「$ph」): ${out.take(40)}")
            return null
        }
        val t = Dict.enforce(out, dict, src, target == "id")
        if (t.isBlank() || Echo.isEcho(src, t) || !LangGate.ok(src, t, target)) return null
        return t
    }

    /** 本地模型是否就绪（选定模型已下载+校验，引擎可用）。 */
    private fun localEngineReady(): Boolean {
        val spec = selectedLocalSpec() ?: return false
        if (!ModelManager.isReady(context, spec)) return false
        return LocalEngine.load(context, ModelManager.modelFile(context, spec).path)
    }

    private fun localEngineUnreadyReason(): String {
        val sp = context.getSharedPreferences("wtrans", Context.MODE_PRIVATE)
        val mid = sp.getString("localModel", "hy2b") ?: "hy2b"
        val spec = ModelManager.spec(mid)
        return when {
            spec == null -> "本地模型未配置（$mid）"
            !ModelManager.isReady(context, spec) -> "本地模型未下载或未校验完成（设置→离线翻译模型 点「下载」）"
            else -> "本地引擎加载失败（重启 APP 再试）"
        }
    }

    private fun selectedLocalSpec(): ModelManager.ModelSpec? {
        val mid = context.getSharedPreferences("wtrans", Context.MODE_PRIVATE)
            .getString("localModel", "hy2b") ?: "hy2b"
        return ModelManager.spec(mid)
    }

    private fun deliver(o: Array<String?>, e: Array<String?>, cb: (List<String?>, List<String?>) -> Unit) =
        cb(o.toList(), e.toList())

    @Volatile private var flushScheduled = false
    private fun scheduleFlush() {
        synchronized(slots) { if (flushScheduled) return; flushScheduled = true }
        thread(name = "wtrans-flush") {
            try {
            Thread.sleep(350)
            val taken: List<Slot>
            synchronized(slots) { taken = ArrayList(slots); slots.clear(); flushScheduled = false }
            // 新请求可能在清标志前加入 → 有存货才补排一轮（无标志竞态：scheduleFlush 自带 flag 守卫）
            synchronized(slots) { if (slots.isNotEmpty()) scheduleFlush() }
            if (taken.isEmpty()) return@thread
            // 窗口合并 → 分组跑 → 按 slot 长度拆回
            val all = taken.flatMap { it.texts }
            LogBuf.log("flush: ${taken.size}请求合并 ${all.size}条文本")
            val results = arrayOfNulls<String?>(all.size)
            val errors = arrayOfNulls<String?>(all.size)
            Batcher.group(all).forEach { g -> runBatch(all, g, results, errors) }
            var off = 0
            for (slot in taken) {
                val n = slot.texts.size
                slot.cb(results.copyOfRange(off, off + n).toList(), errors.copyOfRange(off, off + n).toList())
                off += n
            }
            } catch (e: Throwable) {
                // v0.2.4 诊断：flush 线程死亡 = 所有翻译静默无回调（E18 取证中…）必须可见
                flushScheduled = false
                Log.e("wTranslate", "flush thread died: ${e.javaClass.simpleName}: ${e.message}", e)
            }
        }
    }

    /** v0.2.6 共享单条链路（P1 直发 + 逐条降级复用）：
     *  single → Reasoning.strip + looksLikeReasoning 泄漏 → 泄漏/空/回显/网络异常 →
     *  【重译】强化重试一次 → 仍泄漏 → 错误（不缓存）；仍回显 → KEEP 哨兵落缓存（v0.2.4 语义）；
     *  重试网络异常 → 保留首轮错误。return Pair(result, error)，两者至多一个非 null。 */
    private fun singleWithEchoRetry(cfg: Config, dict: List<Term>, src: String): Pair<String?, String?> {
        var firstErr: String? = null
        try {
            val t = translateSingle(cfg, dict, src)
            if (Echo.isEcho(src, t)) {
                logEchoDetail(src, "回显明细")
                firstErr = "显示的是原文，未翻译成功"
            } else {
                cache[src] = t
                persistCache(listOf(Pair(src, t))); evictCacheIfNeeded()
                return Pair(t, null)
            }
        } catch (e: Exception) {
            firstErr = if (e.message?.isNotBlank() == true) e.message!! else "翻译失败"
        }
        // v0.2.3 人名学习兼容：纯短专名/超短文本不进 KEEP 重试（留给 Service 红⚠→学习分支）
        val qual = Watch.keepAsIs(src) && !Watch.looksLikeProperName(src)
        if (!qual) {
            failedCache[src] = System.currentTimeMillis() + 15_000
            return Pair(null, firstErr)
        }
        Log.i("wTranslate", "回显强化重试(1条): " + src.replace('\n', ' ').take(10))
        LogBuf.log("回显强化重试(1条): " + src.replace('\n', ' ').take(10))
        try {
            var t = translateSingle(cfg, dict, src, Prompt.ECHO_RETRY_NOTE_SINGLE)
            if (t.isNotBlank()) t = Dict.enforce(t, dict, src, Lang.detectTarget(src) == "id")
            val leak = Reasoning.looksLikeReasoning(t)
            if (leak) {
                Log.i("wTranslate", "重试仍思考泄漏→保留(不缓存): " + src.replace('\n', '⎵').take(14))
                LogBuf.log("重试仍思考泄漏→保留(不缓存): " + src.replace('\n', '⎵').take(14))
                failedCache[src] = System.currentTimeMillis() + 15_000
                return Pair(null, "模型输出思考文本，未翻译成功")
            }
            // v0.2.8 ③（真机 9/21 取证）：重试仍【空内容】= 模型暂时性故障 → 判失败不缓存
            // +15s 冷却（空回复落 KEEP 缓存 = 输入框死锁源头；真回显才值得保留哨兵）。
            if (t.isBlank()) {
                Log.i("wTranslate", "重试空内容(判失败·不缓存): " + src.replace('\n', '⎵').take(14))
                LogBuf.log("重试空内容(判失败·不缓存): " + src.replace('\n', '⎵').take(14))
                failedCache[src] = System.currentTimeMillis() + 15_000
                return Pair(null, firstErr)
            }
            val outcome = if (Echo.isEcho(src, t)) Watch.KEEP_MARK else t
            cache[src] = outcome
            persistCache(listOf(Pair(src, outcome))); evictCacheIfNeeded()
            failedCache.remove(src)
            if (outcome == Watch.KEEP_MARK) {
                Log.i("wTranslate", "回显保留(重试仍回显): " + src.replace('\n', '⎵').take(14))
                LogBuf.log("回显保留(重试仍回显): " + src.replace('\n', '⎵').take(14))
            }
            return Pair(outcome, null)
        } catch (e2: Exception) {
            // 重试轮泄漏由 translateSingle 自记闸门行；此处按 v0.2.4 语义保留首轮错误
            LogBuf.log("回显重试失败: ${(e2.message ?: "").take(60)}")
            failedCache[src] = System.currentTimeMillis() + 15_000
            return Pair(null, firstErr)
        }
    }

    private fun logEchoDetail(src: String, tag: String) {
        LogBuf.log(tag + ": 源" + src.length + "字" +
            (if (Watch.looksLikeProperName(src)) " [专名?]" else "") + " \"" + src.replace('\n', '⎵').take(14) + "\"")
    }

    private fun runBatch(textsAll: List<String>, idxs: List<Int>, results: Array<String?>, errors: Array<String?>) {
        val cfg = loadConfig()
        val dict = Dict.parse(cfg.dictText)
        val batch = idxs.map { textsAll[it].trim().take(Batcher.MAX_TEXT_LEN)}
        // v0.2.6 P1（真机 9/18-9/21 日志取证：134/134 批量请求全=1 条，其中 39 次被 MiniMax
        // 无视数组契约「输出中未找到含 1 项的 JSON 数组」→白烧 4-5s 才降级 single）。
        // 点击翻译天然单条 → 1 条组直接走 single 路径，省一次注定浪费的请求。
        if (batch.size == 1) {
            val src = batch[0]; val wi = idxs[0]
            Log.i("wTranslate", "直发single(1条): " + src.replace('\n', '⎵').take(14))
            LogBuf.log("直发single(1条): " + src.replace('\n', '⎵').take(14))
            val (r, e) = singleWithEchoRetry(cfg, dict, src)
            results[wi] = r; errors[wi] = e
            return
        }
        try {
            val content = chatCompletion(cfg, listOf(
                Pair("system", Prompt.withDict(dict)),
                Pair("user", Prompt.userPayload(batch)),
            ), 50_000, Batcher.budgetFor(batch))
            val arr = Json.extractExpectedArray(content, batch.size)
            val fresh = ArrayList<Pair<String, String>>()
            val echoJs = ArrayList<Int>()   // batch 局部下标，v0.2.4 强化重试候选
            arr.forEachIndexed { j, raw ->
                val src = batch[j]
                var t = raw
                // v0.2.5（真机 2026-09-18 MiniMax-M2.7 取证：思考文混进 content → 剥离后才是译文；
                // 未闭合思考块无法定位正文起点 → 判失败进重试，**绝不写缓存**（防毒缓存））
                t = Reasoning.strip(t)
                val thinkLeak = t.isNotBlank() && Reasoning.looksLikeReasoning(t)
                if (t.isNotBlank() && !thinkLeak) t = Dict.enforce(t, dict, src, Lang.detectTarget(src) == "id")
                if (thinkLeak) {
                    results[idxs[j]] = null
                    errors[idxs[j]] = "模型输出思考文本，未翻译成功"
                    failedCache[src] = System.currentTimeMillis() + 15_000
                    echoJs.add(j)
                    Log.i("wTranslate", "思考泄漏(判失败): " + src.replace('\n', '⎵').take(14))
                    LogBuf.log("思考泄漏(判失败): " + src.replace('\n', '⎵').take(14))
                } else if (t.isBlank() || Echo.isEcho(src, t)) {
                    // 回显 = 未翻译成功：按失败处理，不缓存（同 v1.1.19）；v0.2.4 先进强化重试候选
                    results[idxs[j]] = null
                    // isEcho(src, "")=true（空内容也是回显——真机谭效平 434B 空回复即此路径；
                    // 不可加 isNotBlank 守卫，否则空回复变"翻译失败"，人名学习漏触发）
                    val echo = Echo.isEcho(src, t)
                    errors[idxs[j]] = if (echo) "显示的是原文，未翻译成功" else "翻译失败"
                    failedCache[src] = System.currentTimeMillis() + 15_000
                    if (echo) echoJs.add(j)
                    // v0.2.3 M3a：回显明细挪到唯一判定点（v0.2.2 放 Service 的 err==null 分支，
                    // Engine 已把回显包成 err → 恒 0，真机日志取证）。Engine→LogBuf 直写，无循环。
                    if (echo) LogBuf.log("回显明细: 源" + src.length + "字" +
                        (if (Watch.looksLikeProperName(src)) " [专名?]" else "") + " \"" + src.replace('\n', '⎵').take(14) + "\"")
                } else {
                    results[idxs[j]] = t
                    cache[src] = t
                    fresh.add(Pair(src, t))
                }
            }
            // v0.2.4 问题①：回显且具备资格（混合消息，非纯专名）→ 强化重试一次；
            // 仍回显 → 结果=KEEP_MARK 灰显哨兵 + 落缓存（下次点击 cache hit 零 API，不再红⚠）。
            // 网络/解析失败：保留首轮"显示的是原文"错误语义（Service 红⚠/人名学习不变）。
            val qual = Watch.echoRetryQualify(batch, arr.size, echoJs)
            if (qual != null) {
                val retryTexts = qual.map { batch[it] }
                // e2e 断言走 logcat（LogBuf 环只有导出才可见）；Engine 的监控行双通道（同 Service drop/miss）
                Log.i("wTranslate", "回显强化重试(${retryTexts.size}条): " + retryTexts.joinToString("⎵") { it.replace('\n', ' ').take(10) })
                LogBuf.log("回显强化重试(${retryTexts.size}条): " + retryTexts.joinToString("⎵") { it.replace('\n', ' ').take(10) })
                // v0.2.5（真机 2026-09-18 取证：重试走批量契约 → MiniMax 思考文混进 content →
                // extractArray「输出中未找到 JSON 数组」→ 整批重试作废）。改单条契约逐条重试：
                // 思考剥离在 translateSingle 内统一处理，无条数映射问题。
                retryTexts.forEachIndexed { k, src ->
                    val j = qual[k]
                    val wi = idxs[j]
                    try {
                        var t = translateSingle(cfg, dict, src, Prompt.ECHO_RETRY_NOTE_SINGLE)
                        if (t.isNotBlank()) t = Dict.enforce(t, dict, src, Lang.detectTarget(src) == "id")
                        // 思考泄漏/空 → 判失败不缓存（毒缓存防线）；真回显 → 保留原文哨兵（v0.2.4 语义）。
                        // v0.2.8 ③：空内容同样不落缓存——9/21 真机死锁源头=空回复被 KEEP 化永久缓存。
                        val leak = Reasoning.looksLikeReasoning(t)
                        if (leak || t.isBlank()) {
                            Log.i("wTranslate", "重试仍" + (if (leak) "思考泄漏" else "空内容") + "→失败(不缓存): " + src.replace('\n', '⎵').take(14))
                            LogBuf.log("重试仍" + (if (leak) "思考泄漏" else "空内容") + "→失败(不缓存): " + src.replace('\n', '⎵').take(14))
                            failedCache[src] = System.currentTimeMillis() + 15_000
                            results[wi] = null
                            errors[wi] = if (leak) "模型输出思考文本，未翻译成功" else "显示的是原文，未翻译成功"
                        } else {
                            val outcome = if (Echo.isEcho(src, t)) Watch.KEEP_MARK else t
                            results[wi] = outcome; errors[wi] = null
                            failedCache.remove(src)
                            cache[src] = outcome
                            persistCache(listOf(Pair(src, outcome)))
                            if (outcome == Watch.KEEP_MARK) {
                                Log.i("wTranslate", "回显保留(重试仍回显): " + src.replace('\n', '⎵').take(14))
                                LogBuf.log("回显保留(重试仍回显): " + src.replace('\n', '⎵').take(14))
                            }
                        }
                    } catch (e2: Exception) {
                        LogBuf.log("回显重试失败: ${(e2.message ?: "").take(60)}")
                    }
                }
            }
            persistCache(fresh); evictCacheIfNeeded()
        } catch (e: Exception) {
            // 逐条降级（同扩展：批量失败并行逐条）
            LogBuf.log("批量失败→逐条降级(${batch.size}条): ${(e.message ?: "").take(100)}")
            batch.forEachIndexed { j, src ->
                executor.execute {
                    // v0.2.6 P2：降级单条同样接【重译】强化重试（复用共享链路，仍回显→KEEP 灰显
                    // 落缓存；真机取证 9/20「电力还没有好吗？」/9/21「先关闭…阀门。」走旧路径直接拒写）
                    val (r, err) = singleWithEchoRetry(cfg, dict, src)
                    results[idxs[j]] = r; errors[idxs[j]] = err
                }
            }
            // 等同批次降级完成（简单轮询 ≤60s）
            val t0 = System.currentTimeMillis()
            while (batch.indices.any { results[idxs[it]] == null && errors[idxs[it]] == null }
                && System.currentTimeMillis() - t0 < 60_000) Thread.sleep(100)
        }
    }

    fun translateSingle(cfg: Config, dict: List<Term>, text: String, extraNote: String = ""): String {
        val target = Lang.detectTarget(text)
        val content = chatCompletion(cfg, listOf(
            Pair("system", Prompt.singleSystem(target, dict) + extraNote),
            Pair("user", text),
        // v0.1.5：真机 timeout（用户截图 2026-09-07，scnet 高峰排队>25s）→ 25s 改 45s
        ), 45_000, minOf(4096, 100 + (text.length * 3 + 0.999).toInt()))
        // v0.2.5（真机 2026-09-18 MiniMax 取证：行结果 ✔ 后跟 30 行思考文——single 路径零剥离
        // 直接缓存+画绿行）。先剥成对/未闭合标签，再过元叙述闸门；命中=抛错=调用方按失败处理，
        // 不缓存（think-dump 进缓存 = 毒缓存，重译 forget 后还会从磁盘回来）。
        var out = Reasoning.strip(content)
        if (Reasoning.looksLikeReasoning(out)) {
            Log.i("wTranslate", "single 思考泄漏(判失败): " + out.replace('\n', '⎵').take(20))
            LogBuf.log("思考泄漏(判失败): " + text.replace('\n', '⎵').take(14) + " [single]")
            throw IllegalStateException("模型输出思考文本，未翻译成功")
        }
        if (out.startsWith("\"") && out.endsWith("\"") && out.length >= 2) {
            out = try { Json.parseString(out, 0).first } catch (_: Exception) { out }
        }
        if (target == "zh-CN" && out.isNotBlank()) out = Dict.enforce(out, dict, text, false)
        return out
    }

    /** 日志用：payload 类型+条数（批量=user 消息是 JSON 数组） */
    private fun countReq(messages: List<Pair<String, String>>): String {
        val u = messages.lastOrNull()?.second ?: return "?"
        return if (u.trimStart().startsWith("[")) "batch " + (u.count { c -> c == '"' } / 2) + "条" else "single 1条"
    }

    // ---------------- HTTP（同扩展：enable_thinking 不支持则拉黑字段重试） ----------------
    private val unsupported = ConcurrentHashMap<String, Boolean>()

    fun chatCompletion(cfg: Config, messages: List<Pair<String, String>>, timeoutMs: Int, maxTokens: Int): String {
        val body = StringBuilder("{")
        body.append("\"model\":\"").append(Json.escape(cfg.model)).append("\",")
        body.append("\"temperature\":").append(cfg.temperature).append(",")
        body.append("\"messages\":[")
        body.append(messages.joinToString(",") { (r, c) ->
            "{\"role\":\"" + r + "\",\"content\":\"" + Json.escape(c) + "\"}"
        })
        body.append("]")
        if (maxTokens > 0 && !unsupported.containsKey("max_tokens")) {
            body.append(",\"max_tokens\":").append(maxTokens)
        }
        if (cfg.fast && !unsupported.containsKey("enable_thinking")) {
            body.append(",\"enable_thinking\":false")
        }
        body.append("}")
        val url = URL(cfg.apiBase.trimEnd('/') + "/chat/completions")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 10_000
        conn.readTimeout = timeoutMs
        conn.setRequestProperty("Content-Type", "application/json")
        if (cfg.apiKey.isNotEmpty()) conn.setRequestProperty("Authorization", "Bearer " + cfg.apiKey)
        conn.doOutput = true
        val t0 = System.currentTimeMillis()
        // v0.2.4 项3（真机取证：用户添加第二平台 404 无法判定打到哪）：每个请求记录目标 host+path（不含 key）
        // v0.2.6 E5b 取证：e2e 的 logs() 读 logcat，LogBuf-only 行不可见 → 双通道规则补齐
        Log.i("wTranslate", "api→ ${cfg.model} ${countReq(messages)} 请求体${body.length}字 超时=${timeoutMs}ms 发往=${url.host}${url.path}")
        LogBuf.log("api→ ${cfg.model} ${countReq(messages)} 请求体${body.length}字 超时=${timeoutMs}ms 发往=${url.host}${url.path}")
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        var code = -1
        try {
            code = conn.responseCode
        } catch (e: Exception) {
            conn.disconnect()
            LogBuf.log("api✘ 连接/读响应失败 ${e.javaClass.simpleName}: ${e.message} 已等${System.currentTimeMillis() - t0}ms")
            throw IllegalStateException("网络请求失败: ${e.message}")
        }
        val stream = if (code in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)
        val resp = stream.bufferedReader().readText()
        conn.disconnect()
        LogBuf.log("api← HTTP ${code} ${resp.length}B ${System.currentTimeMillis() - t0}ms")
        if (code !in 200..299) {
            // 字段不支持（400）→ 拉黑该字段，下一次不再带（同扩展）
            if (code == 400) {
                if (resp.contains("enable_thinking")) unsupported["enable_thinking"] = true
                if (resp.contains("max_tokens")) unsupported["max_tokens"] = true
            }
            val msg = try { Json.extractContent(resp) } catch (_: Exception) { "" }
            throw IllegalStateException("API $code ${msg.take(120)}")
        }
        return Json.extractContent(resp)
    }
}
