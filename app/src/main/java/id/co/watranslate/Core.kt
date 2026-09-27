package id.co.watranslate

/*
 * Core.kt — 纯逻辑层（零 Android 依赖，可在 JVM 直接单测）
 * 逐函数移植自浏览器扩展 background.js（v1.1.21），逻辑保持一致：
 *   词典归一/整词匹配/双向表行/模糊一词之差/按行对齐强制纠正/回显防护/
 *   JSON 数组解析/配置 profiles 解析/批量分组/提示词构建
 */

// ---------------- JSON（极简，仅覆盖本场景：字符串数组、对象取键） ----------------
object Json {
    /** 解析 JSON 字符串数组（元素可为字符串；其他类型转空串）。失败抛 IllegalArgumentException */
    fun parseStringArray(src: String): List<String> {
        val s = src.trim()
        if (s.isEmpty() || s[0] != '[') throw IllegalArgumentException("输出不是数组")
        val out = ArrayList<String>()
        var i = 1
        while (true) {
            i = skipWs(s, i)
            if (i >= s.length) throw IllegalArgumentException("JSON 数组未闭合")
            if (s[i] == ']') return out
            if (s[i] == ',') { i++; continue }
            if (s[i] == '"') {
                val r = parseString(s, i)
                out.add(r.first)
                i = r.second
            } else {
                // 非字符串元素（null/number/object）：跳到 , 或 ]
                val r = skipValue(s, i)
                out.add("")
                i = r
            }
        }
    }

    fun skipWs(s: String, from: Int): Int { var i = from; while (i < s.length && s[i].isWhitespace()) i++; return i }

    /** 从 s[i]=='"' 开始解析字符串，返回 (值, 后继下标) */
    fun parseString(s: String, i0: Int): Pair<String, Int> {
        val sb = StringBuilder()
        var i = i0 + 1
        while (i < s.length) {
            val c = s[i]
            if (c == '"') return Pair(sb.toString(), i + 1)
            if (c == '\\') {
                i++
                if (i >= s.length) break
                when (s[i]) {
                    '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                    'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                    'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t')
                    'u' -> {
                        if (i + 4 < s.length) {
                            sb.append(Integer.parseInt(s.substring(i + 1, i + 5), 16).toChar())
                            i += 4
                        }
                    }
                    else -> sb.append(s[i])
                }
                i++
            } else { sb.append(c); i++ }
        }
        throw IllegalArgumentException("JSON 字符串未闭合")
    }

    private fun skipValue(s: String, i0: Int): Int {
        var i = i0
        when (s[i]) {
            '{' -> {
                var depth = 0
                while (i < s.length) {
                    val c = s[i]
                    if (c == '{') depth++
                    if (c == '}') { depth--; if (depth == 0) return i + 1 }
                    if (c == '"') { i = parseString(s, i).second - 1 }
                    i++
                }
                return i
            }
            '[' -> {
                var depth = 0
                while (i < s.length) {
                    val c = s[i]
                    if (c == '[') depth++
                    if (c == ']') { depth--; if (depth == 0) return i + 1 }
                    if (c == '"') { i = parseString(s, i).second - 1 }
                    i++
                }
                return i
            }
            '"' -> return parseString(s, i).second
            else -> { while (i < s.length && s[i] != ',' && s[i] != ']' && s[i] != '}') i++; return i }
        }
    }

    /** 从模型输出中提取 JSON 数组：剥 ```json 围栏，取首个 '[' 到末个 ']'（同 parseJsonArray） */
    fun extractArray(raw: String): List<String> {
        val cleaned = raw.replace(Regex("```(?:json)?", RegexOption.IGNORE_CASE), "")
        val s = cleaned.indexOf('[')
        val e = cleaned.lastIndexOf(']')
        if (s == -1 || e == -1 || e <= s) throw IllegalArgumentException("输出中未找到 JSON 数组")
        return parseStringArray(cleaned.substring(s, e + 1))
    }

    /** v0.2.5（真机 2026-09-18 MiniMax-M2.7 取证：思考文本混在数组前后 →
     *  首个'['到末个']'把思考里的括号也框进来 → 解析炸/条数不符 → 逐条降级 → 思考文被当译文）。
     *  从每个 '[' 起做括号平衡扫描，返回第一个恰含 expected 个字符串元素的数组。找不到抛原语义异常。 */
    fun extractExpectedArray(raw: String, expected: Int): List<String> {
        val cleaned = Reasoning.strip(raw).replace(Regex("```(?:json)?", RegexOption.IGNORE_CASE), "")
        var s = cleaned.indexOf('[')
        while (s != -1) {
            var depth = 0
            var inStr = false
            var esc = false
            var e = s
            while (e < cleaned.length) {
                val ch = cleaned[e]
                if (inStr) {
                    when {
                        esc -> esc = false
                        ch == '\\' -> esc = true
                        ch == '"' -> inStr = false
                    }
                } else when (ch) {
                    '"' -> inStr = true
                    '[' -> depth++
                    ']' -> {
                        depth--
                        if (depth == 0) break
                    }
                }
                e++
            }
            if (e < cleaned.length && depth == 0) {
                val arr = try { parseStringArray(cleaned.substring(s, e + 1)) } catch (_: Exception) { null }
                if (arr != null && arr.size == expected) return arr
            }
            s = cleaned.indexOf('[', s + 1)
        }
        throw IllegalArgumentException("输出中未找到含 $expected 项的 JSON 数组")
    }


    /** OpenAI 响应取 choices[0].message.content（手写提取，避免整包解析） */
    fun extractContent(resp: String): String {
        val i = resp.indexOf("\"content\"")
        if (i == -1) return ""
        var j = resp.indexOf(':', i + 9)
        if (j == -1) return ""
        j = skipWs(resp, j + 1)
        if (j >= resp.length || resp[j] != '"') return ""
        return parseString(resp, j).first
    }

    fun escape(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (c in s) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\b' -> sb.append("\\b")
            '\u000C' -> sb.append("\\f")
            else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
        return sb.toString()
    }
}

// ---------------- 词典（同 background.js normDict/dictLines/…） ----------------
data class Term(val key: String, val val_: String)

object Dict {
    /** "印尼文 = 中文" 每行一个（=/:/：均可）→ 归一表（键小写），保序 */
    fun parse(text: String): List<Term> {
        val seen = LinkedHashMap<String, String>()
        for (line in text.split('\n')) {
            val i = line.indexOfFirst { it == '=' || it == ':' || it == '：' }
            if (i < 0) continue
            val k = line.substring(0, i).trim()
            val v = line.substring(i + 1).trimStart('=', ':', '：', ' ').trim()
            if (k.isNotEmpty() && v.isNotEmpty()) seen[k.lowercase()] = v
        }
        return seen.map { Term(it.key, it.value) }
    }

    fun lines(dict: List<Term>): String =
        dict.sortedByDescending { it.key.length }.joinToString("；") { "${it.key}=${it.val_}" }

    fun linesRev(dict: List<Term>): String =
        dict.sortedByDescending { it.val_.length }.joinToString("；") { "${it.val_}=${it.key}" }

    /** 整词匹配（ASCII 词带边界，中文术语直接子串）——同 hasTerm */
    fun hasTerm(term: String, text: String): Boolean {
        val t = term.lowercase()
        return if (Regex("[a-z0-9]").containsMatchIn(term)) {
            Regex("(?<![a-z0-9])" + Regex.escape(t) + "(?![a-z0-9])", RegexOption.IGNORE_CASE).containsMatchIn(text)
        } else {
            text.contains(term)
        }
    }

    /** 等长滑窗恰 1 处不同且唯一 → 返回该窗口（同 fuzzyTermVal） */
    fun fuzzyWindow(out: String, value: String): String? {
        if (value.length < 2) return null
        var hit: String? = null
        var count = 0
        for (i in 0..out.length - value.length) {
            val win = out.substring(i, i + value.length)
            if (win == value) continue
            var diff = 0
            for (j in value.indices) if (win[j] != value[j]) diff++
            if (diff == 1) { hit = win; count++ }
        }
        return if (count == 1) hit else null
    }

    /** 输出强制纠正（同 enforceDictOut v1.1.12 行对齐版）；srcZh=true 表示源为中文（反向不纠正） */
    fun enforce(out: String, dict: List<Term>, sourceText: String, srcZh: Boolean): String {
        if (srcZh || out.isEmpty() || dict.isEmpty() || sourceText.isEmpty()) return out
        fun enforceOne(t: String, source: String): String {
            var cur = t
            val srcTerms = dict.filter { hasTerm(it.key, source) }
            val outTerms = srcTerms.map { it.val_ }.filter { cur.contains(it) }.toMutableSet()
            for ((term, value) in srcTerms) {
                if (outTerms.contains(value)) continue
                val intruders = dict.filter {
                    it.key != term && !hasTerm(it.key, source) && it.val_ != value &&
                        !outTerms.contains(it.val_) && cur.contains(it.val_)
                }
                if (intruders.size == 1) {
                    cur = cur.replace(intruders[0].val_, value)
                    outTerms.add(value)
                } else if (intruders.isEmpty()) {
                    val near = fuzzyWindow(cur, value)
                    if (near != null && dict.none { it.val_ == near }) {
                        cur = cur.replace(near, value)
                        outTerms.add(value)
                    }
                }
            }
            return cur
        }
        val srcLines = sourceText.split('\n')
        val outLines = out.split('\n')
        return if (srcLines.size == outLines.size && srcLines.size > 1)
            outLines.mapIndexed { i, ln -> enforceOne(ln, srcLines[i]) }.joinToString("\n")
        else enforceOne(out, sourceText)
    }
}

// ---------------- v0.2.2 监控启发（纯函数，JVM 可单测；只记日志，零行为影响） ----------------
object Watch {
    val NAME_CHAR = Regex("[\\u4e00-\\u9fa9A-Za-zÀ-ÿ]")
    val NAME_PUNCT = Regex("[.,:;!?。，！？、~@#%&*()（）\\[\\]{}\"'\\s\\-–—_/\\\\+=<>|`^]")
    /** 专名启发（日志标签 + v0.2.3 回显学习判定）：≤12 字、字母/汉字占比≥80%、
     *  无数字、无换行、**无空格**、非 URL 文件后缀。真机回显失败榜前列全是人名（谭效平×11、
     *  陈副厂长×4）。空格必须拒：e2e E5 实证 "Saya akan lembur malam ini bersama tim"
     *  字母比 0.82 会误判人名，学习分支会把回显灰显掉 → E5 假失败。TEKNIK INNAN（含空格）
     *  是文件名不是人名气泡，拒掉无害。 */
    fun looksLikeProperName(s: String): Boolean {
        val t = s.trim()
        // v0.2.6（真机 9/20「电力还没有好吗？」取证）：句读标点=句子特征，短人名绝无——
        // 此前 8 字中文问句因汉字占比 0.875 被误判专名，挡掉了 KEEP 强化重试资格。
        if (t.any { it in "。！？、；：?!" }) return false
        if (t.length < 3 || t.length > 12 || t.contains('\n') || t.contains(' ') ||
            t.contains(Regex("\\d"))) return false
        if (t.contains(Regex("\\.(pdf|jpe?g|png|gif|csv|xlsx?|docx?|pptx?)$", RegexOption.IGNORE_CASE))) return false
        val letters = t.count { NAME_CHAR.matches(it.toString()) }
        val puncts = t.count { NAME_PUNCT.matches(it.toString()) }
        return letters >= 2 && letters + puncts == t.length && letters.toDouble() / t.length >= 0.8
    }
    /** v0.2.3（用户定案②）人名放行：纯汉字名段（2-4 字，最多两段、空格分隔）。
     *  v0.2.3 评审自我否决**主动**使用（"收到/好的"同为 2-4 汉字会被误杀）——
     *  现行机制 = 回显学习：模型真回显 + looksLikeProperName → 灰显并落 PN_MARK 哨兵，
     *  此后 isProperName 秒判不再发 API。本函数保留给日志标签/未来数据佐证。 */
    fun isProperNamePassthrough(s: String): Boolean {
        val t = s.trim()
        return Regex("^[\\u4e00-\\u9fff]{2,4}( [\\u4e00-\\u9fff]{2,4})?$").matches(t)
    }
    /** 人名气泡的灰色替换文案（同时写进缓存作哨兵，防 forget→API 漂移） */
    const val PN_MARK = "〔人名·保留原文〕"
    /** v0.2.4 问题①（真机取证 5 条混合消息回显：谭效平+Oke silakan / 0895392493636+siswanto /
     *  TEKNIK INNAN+darminto / TPM FORKLIFT 车牌）：强化重试后仍整体回显 → 灰显「保留原文」
     *  并落缓存哨兵（下次点击 cache hit 零 API，不再红⚠）。与 PN_MARK 区别：那是纯专名，
     *  这整条混合消息。点行重译 forget 清哨兵=再给模型一次机会；v0.2.8 起词典保存也清（②）。 */
    const val KEEP_MARK = "〔保留原文〕"
    /** v0.2.8 问题⑤（真机取证 9/21「A009修理完成了吗？」5 连点全拒写死锁）：
     *  输入框链路的缓存命中值若是哨兵 → 清缓存重译。消息行有「点行重译」出口，输入框没有
     *  （拒写时译文从未写入 → 还原分支永不相等）→ 哨兵=永久拒写。仅纯判断，可单测。 */
    fun sentinelBypass(cv: String?): Boolean = cv == KEEP_MARK || cv == PN_MARK
    /** 保留原文灰显资格：≥4 字且 ≤200 字。纯短专名（<4 字）故意排除——那走 v0.2.3
     *  人名学习分支（PN 哨兵），语义不同；空/超短回声也无学习价值。 */
    fun keepAsIs(s: String): Boolean {
        val t = s.trim()
        return t.length in 4..200
    }
    /** v0.2.4 问题①（真机取证）：批量轮回显子集的强化重试资格——模型对"人名/电话+正文"混合
     *  消息整条不敢动（谭效平⏎Oke silakan… ×2、0895392493636…、TEKNIK INNAN×、TPM FORKLIFT）。
     *  echoJs=batch 局部下标；返回具备重试资格的下标子集（保持升序）。资格：keepAsIs（≥4 字，
     *  回声有保留价值）且非纯专名——纯人名（王小明/谭效平 单独气泡）必须留给 v0.2.3 学习分支
     *  （红⚠→PN 哨兵），重试抢了会破坏 E17 语义。1 条批次（点击模式常态）也要重试。
     *  条数不对齐（arrN!=batchN，重试无从映射回原下标）→ null。 */
    fun echoRetryQualify(batch: List<String>, arrN: Int, echoJs: List<Int>): List<Int>? {
        if (echoJs.isEmpty() || arrN != batch.size) return null
        val qual = echoJs.filter { keepAsIs(batch[it]) && !looksLikeProperName(batch[it]) }
        return qual.ifEmpty { null }
    }

    /** 长文截断预警（只用于日志）：源行数 ≥6 或 ≥240 字 → 译文大概率超 maxLines=10 */
    fun longSource(s: String): Boolean = s.count { it == '\n' } + 1 >= 6 || s.length >= 240
}

// ---------------- v0.2.5 思考泄漏剥离（真机取证 2026-09-18：MiniMax-M2.7/highspeed 强制思考，
//  enable_thinking:false 被无视，思考内容直接混进 content 字段，且无闭合标签，如
//  "\n\n<think>\n用户要求我把这条消息翻译成印尼语\n..." 整段被当译文画上绿行/写进缓存）----------------
object Reasoning {
    /** 剥离模型输出里的思考/推理前导文，只留真正译文。
     *  1) 成对 <think>...</think> / <thinking>...</thinking> → 删除标签内所有内容（全局）。
     *  2) 未闭合的前导思考块（有 <think> 无 </think>）→ 从 <think> 起截断。
     *  3) MiniMax 实测连 <think> 标签都可能被吞，直接以"用户要求我…/用户想让我…/I need to…/
     *     Let me…/The user wants…"这类元叙述开头 → 若整段命中"元叙述句式"且不含明显译文正文，
     *     返回空串（交给上层 looksLikeReasoning 判定丢弃，而非当译文）。 */
    fun strip(raw: String): String {
        var s = raw
        s = Regex("<(?:think|thinking|reasoning)>.*?</(?:think|thinking|reasoning)>",
            RegexOption.DOT_MATCHES_ALL).replace(s, "")
        val open = Regex("<(?:think|thinking|reasoning)>").find(s)
        if (open != null && !Regex("</(?:think|thinking|reasoning)>").containsMatchIn(s)) {
            s = s.substring(0, open.range.first) + s.substring(open.range.last + 1)
            // 未闭合：标签之后到"看起来像正文"之间也是思考——但无法可靠定位正文起点，
            // 保守做法：删掉 <think> 标签本身，剩下交给 looksLikeReasoning 闸门处理。
        }
        return s.trim()
    }

    /** 元叙述句式（思考文开头特征，多语言）。命中任一前缀且整段偏"讲解"而非"译文"。 */
    private val LEAD_PATTERNS = listOf(
        "用户要求", "用户希望", "用户想要", "用户给我", "我需要", "我要把", "这条消息", "这段话",
        "首先", "任务是用", "任务是", "让我", "这是", "我应", "我会", "根据用户", "用户的",
        "the user wants", "the user asked", "the user is asking", "i need to", "i want to",
        "i should", "let me", "let's", "the task is", "i'll ", "i will ", "first,", "this is a translation",
        "saya perlu", "pengguna meminta", "aku harus", "saya akan menterjemah", "saya akan menerjemah",
    )
    fun looksLikeReasoning(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        val low = t.lowercase()
        val startsLead = LEAD_PATTERNS.any { low.startsWith(it) }
        if (!startsLead) return false
        // 元叙述句式常带"翻译成/translate to/menterjemah"这类自指动词 → 判定为思考而非译文
        val selfRef = low.contains("翻译成") || low.contains("translate") || low.contains("翻译为") ||
            low.contains("印尼语") || low.contains("中文") || low.contains("english") || low.contains("bahasa") ||
            low.contains("menterjemah") || low.contains("menerjemah")
        return selfRef
    }
}

// ---------------- v0.3.5 ③：全文映射（WhatsApp 无障碍节点 80 字截断根治） ----------------
// 真机实证（9/22 日志 d9e7ec45：Sri Sumarni 消息 txt 恰 80 字）：WhatsApp 给 a11y 树的
// 正文节点在 ~80 字符处硬截断。点按复制链路（tap-copy）拿到剪贴板全文后，在此登记
// 「截断前缀 → 全文」映射；之后所有扫描/点击/自动模式遇到以该前缀开头的候选文本，
// 一律还原成全文再进翻译与缓存——**全文键优先，截断键永不覆盖它**（用户痛点
// 「新译文覆盖旧译文」的机制性根治）。纯 JVM 逻辑，持久化经 persist 注入（Service 落 SP）。
object FullText {
    // norm(全文) → 全文（按登记序，FIFO 截断防膨胀）
    private val entries = java.util.concurrent.ConcurrentHashMap<String, String>()
    private var order: List<String> = emptyList()
    @Volatile var persist: ((String) -> Unit)? = null

    fun serialize(): String = order.joinToString("\u0001") { k ->
        k + "\u0002" + (entries[k] ?: "").replace("\\", "\\\\").replace("\n", "\\n")
    }

    fun load(raw: String?) {
        if (raw.isNullOrEmpty()) return
        for (line in raw.split("\u0001")) {
            val p = line.split("\u0002", limit = 2)
            if (p.size != 2 || p[0].isEmpty()) continue
            val full = p[1].replace("\\n", "\n").replace("\\\\", "\\")
            // v0.3.6：序列化里混有全文键与截断别名键——按原键恢复，别名才不会在重启后失效
            if (Echo.norm(full) == p[0]) register(full) else addKey(p[0], full)
        }
    }

    /** 只有比截断形态更长才值得登记（无增益的重复登记=白占空间）。
     *  v0.3.6：除全文前缀键外，**同时登记 truncated 原形态作别名键**（value=全文）。
     *  真机气泡的 scan 候选常是合并形态（昵称+电话+「已转发」+正文，diag12/15 实证），
     *  它不是任何全文的前缀 → 旧版 resolve(合并文本) 永不命中 → 映射白做、下一轮
     *  点击重新注入长按。别名键让「点过的这条」以后直接走映射，不再二次探测。 */
    fun record(truncated: String, full: String) {
        if (full.length < MIN_LEN) return
        if (full.length <= truncated.length) return
        register(full)
        addKey(Echo.norm(truncated), full)
    }

    private fun register(full: String) {
        addKey(Echo.norm(full), full)
    }

    private fun addKey(k: String, full: String) {
        if (k.isEmpty() || entries.containsKey(k)) return
        entries[k] = full
        synchronized(this) { order = order + k }
        while (order.size > MAX) {
            val doomed = order.first()
            order = order.drop(1); entries.remove(doomed)
        }
        persist?.invoke(serialize())
    }

    /** src 若命中某登记全文的前缀（且 src 达到触发长度）→ 返回全文，否则 null。
     *  v0.3.5 修正：**幂等**——src 本身就是登记全文时也返回它。
     *  （scan 消费点击 hint 时拿全文去 resolve，截断键的行二次点击 toggle 依赖这一步匹配。） */
    fun resolve(src: String): String? {
        val n = Echo.norm(src)
        if (n.length < MIN_LEN) return null
        entries[n]?.let { return it }
        var best: String? = null
        for ((k, v) in entries) if (k.length > n.length && k.startsWith(n)) {
            if (best == null || k.length < Echo.norm(best!!).length) best = v   // 多条命中取最短全文（最小惊讶）
        }
        return best
    }

    fun clear() { entries.clear(); synchronized(this) { order = emptyList() }; persist?.invoke("") }

    private const val MIN_LEN = 50   // 真机截断点=80；50 留余量，防短句前缀误碰撞
    private const val MAX = 400
}

// ---------------- 回显防护（v1.1.19）：归一化（去全部空白+小写）全等才算回显 ----------------
object Echo {
    fun norm(s: String): String = s.replace(Regex("\\s+"), "").lowercase()
    fun isEcho(src: String?, translated: String?): Boolean {
        if (translated.isNullOrEmpty()) return true
        if (src == null) return false
        if (src.length < 6) return false
        if (norm(src) == norm(translated)) return true
        // v0.1.9（e2e E5 取证）：模型把原文包在标记里返回（"ZH[原文]"/"[原文]"样式）= 没翻译。
        // 取最后一对括号内的内容（丢掉 ZH/ID 类语言前缀），归一化与原文全等 → 判回显。
        val inner = Regex("\\[(.*)]", RegexOption.DOT_MATCHES_ALL).find(translated)?.groupValues?.get(1)
        if (inner != null && inner.isNotBlank() && norm(inner) == norm(src)) return true
        return false
    }
}

// ---------------- 语言方向 ----------------
object Lang {
    /** v0.3.3 ①（真机 2026-09-23「凭空加词」案）：旧规则"含任何一个汉字→判中文"太脆——
     *  群聊对方气泡混进 4 字发送者名「陈副厂长」就整体翻转成"中→印尼"，模型把印尼文
     *  正文润色回显+顺手译名字（demo 手工粘贴无此碎片故正常）。改多数表决：
     *  汉字为 0→翻中文（原语义不变）；有汉字时，仅当拉丁字母严格多于汉字才判"主体是
     *  印尼文"→翻中文，否则翻印尼（中文消息夹英文术语=拉丁<汉字，不受影响）。
     *  真机取证：`陈副厂长\nMau lihat perbandingan A4-nya, letakkan di atas meja saya`
     *  拉丁≈45 / 汉字=4 → 判印尼文正文→翻中文（修复前 汉字>0 直接判中文→方向翻转回声）。 */
    fun detectTarget(text: String): String {
        val cjk = cjkCount(text)
        if (cjk == 0) return "zh-CN"
        val latin = latinCount(text)
        // 双阈值：既要比例悬殊（≥4×，排除中文夹术语）又要有绝对量差（≥12，排除短语噪声）。
        return if (latin >= cjk * 4 && latin - cjk >= 12) "zh-CN" else "id"
    }
    fun cjkCount(s: String): Int = s.count { it in '\u4e00'..'\u9fff' }
    fun latinCount(s: String): Int = s.count { it in 'a'..'z' || it in 'A'..'Z' }
}

// ---------------- 配置解析（v1.1.20/21：profiles 列表，activeProfile 优先；无列表回退扁平） ----------------
data class Profile(
    val id: String, val name: String, val apiBase: String,
    val apiKey: String, val model: String, val temperature: Double,
)
data class Config(
    val profiles: List<Profile>, val activeId: String,
    val apiBase: String, val apiKey: String, val model: String,
    val temperature: Double, val enabled: Boolean, val fast: Boolean, val dictText: String,
)

object ConfigResolve {
    const val DEFAULT_BASE = "https://api.scnet.cn/api/llm/v1"
    const val DEFAULT_MODEL = "Qwen3.8-Flash"

    /** profilesJson 存储格式：[{"id","name","apiBase","apiKey","model","temperature"},…] */
    fun parseProfiles(json: String?): List<Profile> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = Json.parseNestedArrayObjects(json)
            arr.mapNotNull { o ->
                val name = o["name"]?.trim() ?: return@mapNotNull null
                if (name.isEmpty()) return@mapNotNull null
                Profile(
                    id = o["id"]?.ifBlank { null } ?: ("p" + name.hashCode().toString(36)),
                    name = name.take(30),
                    apiBase = (o["apiBase"]?.ifBlank { null } ?: DEFAULT_BASE).trim(),
                    apiKey = (o["apiKey"] ?: "").trim(),
                    model = (o["model"]?.ifBlank { null } ?: DEFAULT_MODEL).trim(),
                    temperature = (o["temperature"]?.toDoubleOrNull() ?: 0.2).coerceIn(0.0, 2.0),
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    fun resolve(profilesJson: String?, activeId: String?, apiBase: String?, apiKey: String?,
                model: String?, temperature: Double?, enabled: Boolean, fast: Boolean, dictText: String): Config {
        val list = parseProfiles(profilesJson)
        val (b, k, m, t) = if (list.isNotEmpty()) {
            val p = list.firstOrNull { it.id == activeId } ?: list[0]
            Quad(p.apiBase, p.apiKey, p.model, p.temperature)
        } else {
            Quad(apiBase?.ifBlank { null } ?: DEFAULT_BASE, apiKey ?: "",
                 model?.ifBlank { null } ?: DEFAULT_MODEL, temperature ?: 0.2)
        }
        return Config(list, activeId ?: "", b, k, m, t, enabled, fast, dictText)
    }

    private data class Quad(val a: String, val b: String, val c: String, val d: Double)
}

/** Json 补充：解析对象数组（仅字符串/数字/布尔值键），给 profiles 用 */
fun Json.parseNestedArrayObjects(src: String): List<Map<String, String?>> {
    val s = src.trim()
    if (s.isEmpty() || s[0] != '[') throw IllegalArgumentException("需要数组")
    val out = ArrayList<Map<String, String?>>()
    var i = 1
    while (true) {
        i = skipWs(s, i)
        if (i >= s.length) throw IllegalArgumentException("未闭合")
        if (s[i] == ']') return out
        if (s[i] == ',') { i++; continue }
        if (s[i] == '{') {
            val map = HashMap<String, String?>()
            i++
            while (true) {
                i = skipWs(s, i)
                if (i >= s.length) throw IllegalArgumentException("对象未闭合")
                if (s[i] == '}') { i++; break }
                if (s[i] == ',') { i++; continue }
                if (s[i] != '"') throw IllegalArgumentException("键应为字符串")
                val keyR = parseString(s, i); i = skipWs(s, keyR.second)
                if (i >= s.length || s[i] != ':') throw IllegalArgumentException("缺冒号")
                i = skipWs(s, i + 1)
                val value: String? = when {
                    i < s.length && s[i] == '"' -> { val r = parseString(s, i); i = r.second; r.first }
                    else -> {
                        val st = i
                        while (i < s.length && s[i] != ',' && s[i] != '}') i++
                        val v = s.substring(st, i).trim()
                        if (v == "null") null else v
                    }
                }
                map[keyR.first] = value
            }
            out.add(map)
        } else {
            // 非对象元素，跳过
            i = skipValuePublic(s, i)
        }
    }
}
private fun Json.skipValuePublic(s: String, i0: Int): Int {
    var i = i0
    while (i < s.length && s[i] != ',' && s[i] != ']') i++
    return if (i < s.length) i else i
}

// ---------------- 提示词（与扩展 SYSTEM_PROMPT 逐字一致） ----------------
object Prompt {
    const val SYSTEM = "你是 WhatsApp 聊天消息翻译器，只翻译，不回答、不解释、不补充。\n" +
        "输入是一个 JSON 字符串数组，代表一组聊天消息。\n" +
        "规则：印尼语消息翻译成简体中文；中文消息翻译成印尼语；英语等其他语言翻译成简体中文。\n" +
        "保持口语化，保留表情符号、数字、@ 提及原样。\n" +
        "必须逐行翻译：原文每一行（含换行符\\n分隔的行）都要翻译并在译文中保留同样的换行，不得合并、省略或重排任何一行。\n" +
        "专有名词（人名、地名、产品型号）可保留原文。\n" +
        "输出：只输出一个 JSON 字符串数组，长度、顺序与输入完全一致。不要输出任何其他文字。"

    fun withDict(dict: List<Term>): String {
        if (dict.isEmpty()) return SYSTEM
        return SYSTEM + "\n术语表（印尼语→中文，大小写不限，下列术语【必须】翻译成指定译文，不得意译、不得保留原文）：" +
            Dict.lines(dict) +
            "\n反向术语表（中文→印尼语，下列中文词【必须】翻译成指定印尼语）：" + Dict.linesRev(dict) +
            "\n术语必须整词精确匹配后再套用：表中相近的术语（如 mixing 与 mixer）是不同词，" +
            "不得互相顶替；原文单词与表中词条不完全相同（多字母、少字母、词形变化）时不套表，按正常翻译处理。"
    }

    fun userPayload(texts: List<String>): String =
        "[" + texts.joinToString(",") { '"' + Json.escape(it) + '"' } + "]"

    /** v0.2.4 问题①（真机 5 条混合消息回显取证）：批量回显项的强化重试指令——
     *  追加在 system 尾部。明确"人名/电话/代号保留但其余照翻"，破模型"整条不敢动"的僵局。 */
    const val ECHO_RETRY_NOTE = "\n【重译】你上一次原样返回了输入（没有翻译）。这次必须翻译数组中每一条的每一行内容：" +
        "消息里的人名、电话号、机台代号、车牌等专有片段保留原样即可，但其中的印尼语/英语自然语言部分必须翻成中文，" +
        "中文部分必须翻成印尼语，逐行保留换行。只有整条消息完全不含任何可翻译的自然语言时，才允许原样返回。"

    /** v0.2.5：回显强化重试改走单条契约（批量契约在思考模型下 extractArray 必炸，真机 9/18 取证），
     *  ECHO_RETRY_NOTE 的"数组中每一条"字样不适用 → 单条版同语义。 */
    const val ECHO_RETRY_NOTE_SINGLE = "\n【重译】你上一次原样返回了输入（没有翻译）。这次必须翻译这条消息的每一行内容：" +
        "消息里的人名、电话号、机台代号、车牌等专有片段保留原样即可，但其中的印尼语/英语自然语言部分必须翻成中文，" +
        "中文部分必须翻成印尼语，逐行保留换行。只输出译文本身，禁止任何解释、思考或前言。" +
        "只有整条消息完全不含任何可翻译的自然语言时，才允许原样返回。"

    /** v0.2.4：判定一个批量 system prompt 是否已处于强化重试轮（mock/e2e 同判据） */
    fun isStrongBatchPrompt(systemContent: String): Boolean =
        systemContent.contains("不要输出任何其他文字") && systemContent.contains("【重译】")

    /** 单条系统提示（批量降级/输入框/测试连接用）——v0.2.4 内置强化措辞 */
    fun singleSystem(target: String, dict: List<Term>): String {
        val targetName = if (target == "id") "印尼语" else "简体中文"
        val d = Dict.lines(dict)
        val dr = Dict.linesRev(dict)
        val dictPart = if (dict.isNotEmpty())
            "术语表：" + (if (target == "id") dr else d) + "（下列术语必须按指定译文翻译，不得意译或保留原文）"
        else ""
        return "你是聊天消息翻译器。把用户给你的消息翻译成" + targetName + "，口语化，只输出译文本身，不要任何解释、引号或前缀。保留表情符号和换行。" +
            "若消息含人名、电话号、机台代号等专有片段，这些片段保留原样，其余自然语言部分必须逐行翻译，不得原样返回整条消息。" + dictPart
    }
}

/*
 * v0.3.0 M3：本地引擎（Hy-MT1.5→v0.3.6 起 Hy-MT2，官方模板逐字节兼容）提示词 + 术语介入。
 * 官方模板（tencent/Hy-MT README，M0 电池已验证）：
 *   ZH→XX: "将以下文本翻译为{target_language}，注意只需要输出翻译后的结果，不要额外解释：\n\n{source_text}"
 *   XX→ZH: "Translate the following segment into Chinese, without additional explanation.\n\n{source_text}"
 * 术语介入（官方 terminology-intervention 模板）：
 *   "参考下面的翻译：\n{source_term} 翻译成 {target_term}\n\n<基础模板>"
 * 词典 Term(key=源语言词, val_=目标语言词)；方向 id→zh 时两侧对调。
 * JNI 侧（local_engine.cpp）对该文本手工渲染 hy_User 结构，本函数只产「hy_User 内文」。
 */
/*
 * v0.3.7 F5 证伪结论（四轮电池 9/25，MT1.5+MT2 × 官方采样，日志 /tmp/grammar_bat4.log）
 * ——以下三条路线**实测无效，勿再尝试**：
 *  ① 抽象语法条款（STYLE-LIT/STYLE-EXP：「注意翻译的风格要严格符合【印尼语语法】」
 *    及其展开说明版）：两模型词序 0/6、术语 0/6，MT1.5 复读 9/16——模型把条款本身
 *    背出来当译文。词序问题的真解=FEWSHOT_ID2ZH 示例对（模仿行为，0/6→4-5/6）。
 *  ② Delimiters 官方模板（「必须保留等量的分隔符…」）：12/12 全把指令句翻译成中文
 *    吐出；且不加指令时 WhatsApp 的 * ~ 符号本来就保得住（Default 通道实测）→ 纯负收益。
 *  ③ Personalization 模板（人名/语气定制清单）：复读 7-9/10（第二轮电池实锤），
 *    人名保留靠 few-shot+词典通道。
 *  另：人名保留条款 NAME_CLAUSE_VARIANT 维持默认关（v0.3.6 四格电池判词=无净赢）。
 */
object LocalPrompt {
    /** v0.3.6 人名保留条款（PC 四格电池判词=**无净赢，默认关闭**，9/24 MT2-1.25bit 双方向实测）：
     *  - id→zh（核心场景）：BASE 全音译（Budi→布迪、WIndarta→温达塔、calender→日历，
     *    机台代号 A012/B007 存活）；HEAD 条款 → **模型把条款本身译出来**（P12 复现）；
     *    few-shot 人名例对（Suparman/Endah 两条）→ **零迁移**（16/16 照旧音译，示例
     *    没被模仿）；TAIL（条款并入指令行尾）→ 16 句仅 2 句保住名字（Budi 存活，
     *    Wahyuni/WIndarta/Winder 照旧音译）。
     *  - zh→id：BASE 的拉丁名本就保留（Budi/WIndarta 原样），真受害是中文职衔名
     *    （陈副厂长→Wakil Direktur Chen，v0.3.3 起该行已被 senderNameLine 剔除不进模型）；
     *    HEAD 条款 6/8 保住但两句把条款泄漏进译文。
     *  结论：任何变体都达不到「净赢」（保名收益 < 泄漏/无效成本）→ 默认 ""。
     *  开关保留原因：真机装上 MT2 后若用户仍反馈音译问题，"tail" 是唯一实测有
     *  部分效果的变体（且 LogBuf 日志头自报「人名条款=」便于取证）。 */
    const val NAME_CLAUSE_VARIANT = ""    // "" | "tail"

    /** 条款文本（"tail" 变体：追加到指令行尾，紧邻原文——电池实测唯一有部分效果的形态） */
    private fun clauseTail(target: String): String = when (NAME_CLAUSE_VARIANT) {
        "tail" -> if (target == "id")
            " Semua nama orang/perusahaan dan kode mesin (mis. Budi, WIndarta, A012) tulis apa adanya dalam alfabet Latin, jangan ditransliterasi."
        else
            " Keep person and company names in their original Latin spelling (e.g. Budi, Wahyuni); never transliterate them into Chinese characters."
        else -> ""
    }

    /** v0.3.4：id→zh 方向风格示例（few-shot）。1.25B 低比特模型不遵守抽象规则
     *  （PC hy2b 实测：加"不要加戏"约束句 → 模型把约束句本身翻译出来），但模仿
     *  示范格式稳定 → 陈述句被译成祈使句+加戏的句级幻觉用示例对压制。
     *  v0.3.7 A3（四轮电池 9/25 实锤，grammar_bat4.log）：示例对=词序问题的**真正解药**——
     *  同一模型同一句集，裸官方指令 词序 0/6，带示例对 4-5/6。模型模仿例句句式，
     *  不读抽象语法条款。4 条 → 7 条：新增 3 条专门示范「印尼语修饰语后置 → 中文前置」
     *  的三种不同句式（来源定语 dari…、颜色后置 warna…、时间后置 minggu ini），
     *  用词全部避开词典内术语（oli/winder/banbury/conveyor/mixing/emboss/calender…），
     *  防模型死记例句单词、语义渗漏到真实消息。
     *  PC 3 轮 A/B 定型（E4v3 集 × 9 真机句 × 两遍全净）：例对=陈述句短信息。 */
    val FEWSHOT_ID2ZH: String =
        "Contoh: \"Minggu depan tim maintenance akan merapat\" -> \"下周维修班组将到场\"\n" +
        "Contoh: \"Suhu roller A003 normal\" -> \"A003辊温正常\"\n" +
        "Contoh: \"Bak limbah penuh\" -> \"废料池满了\"\n" +
        "Contoh: \"Laporan shift malam sudah dikumpulkan\" -> \"夜班报告已经交上去了\"\n" +
        "Contoh: \"Suara bising dari ruang genset mengganggu\" -> \"发电机房传来的噪音很扰人\"\n" +
        "Contoh: \"Cat tembok warna biru sudah dibeli\" -> \"蓝色墙面漆已经买好了\"\n" +
        "Contoh: \"Jadwal lembur minggu ini sudah keluar\" -> \"本周加班安排已经公布了\""

    /** v0.3.7 A2 官方指令行（id→zh）：此前用自编英文 "Translate the following segment…"，
     *  非官方中文原文。四轮电池全部通道以 grammar_bat4.py 的 off_fs_dict() 为准 → 生产对齐，
     *  否则 PC 结论不能直接迁移到手机。 */
    private const val INSTR_ID2ZH = "将以下文本翻译为中文，注意只需要输出翻译后的结果，不要额外解释："

    /** id→zh 布局实锤（PC hy2b 两遍验证）：示例在前+指令紧邻原文。
     *  反例：指令在前示例在【后】且无词典 → 基本净但偶发复读；有词典 → 100% 复读示例块。 */
    fun withDict(src: String, target: String, dict: List<Term>): String {
        // v0.3.6 人名条款开关（默认 NAME_CLAUSE_VARIANT="" → clauseTail="" 零影响）
        val tail = clauseTail(target)
        if (target == "id") {
            val base = "将以下文本翻译为印度尼西亚语，注意只需要输出翻译后的结果，不要额外解释：" + tail + "\n\n" + src
            if (dict.isEmpty()) return base
            val refLines = dict.map { "${it.val_} 翻译成 ${it.key}" }
            return "参考下面的翻译：\n" + refLines.joinToString("\n") + "\n\n" + base
        }
        val instr = INSTR_ID2ZH + tail
        if (dict.isEmpty()) return FEWSHOT_ID2ZH + "\n\n" + instr + "\n\n" + src
        val refLines = dict.map { "${it.key} 翻译成 ${it.val_}" }
        // 词典块放示例与指令之间：实锤布局，复读率=0（指令后置紧邻 src）
        return FEWSHOT_ID2ZH + "\n\n参考下面的翻译：\n" + refLines.joinToString("\n") + "\n\n" + instr + "\n\n" + src
    }

    /** 无词典基础模板（与 LocalEngine.buildPrompt 同文本，保留供对照/测试） */
    fun base(src: String, target: String): String = withDict(src, target, emptyList())

    /** v0.3.4：示例复读检测。few-shot 偶发副作用=模型把示例块原样/半样复述出来
     *  （PC hy2b 实测某些句式复读率 100%）。判据（两路任一命中）：
     *  ① 任意一行同时含「箭头 + 引号/示例字样」——v0.3.5 收紧：真机 9/23 截图实证
     *    单行变体（`例如："…" -> "…"`，两引号间还做词替换）漏网，阈值 2 行→1 行。
     *    合法译文同时含箭头+引号的概率极低（印尼工务消息几乎不用箭头），误伤代价
     *    =采样重试一次→仍误杀则原文不显示，可接受。
     *  ② ≥2 条示例中文侧整串出现（真翻译几乎不可能连撞两条固定短语）
     *  单条示例词（如「废料池」）单独出现不算——那是合法译文。 */
    private val EXAMPLE_ZH = listOf(
        "下周维修班组将到场", "A003辊温正常", "废料池满了", "夜班报告已经交上去了",
        "发电机房传来的噪音很扰人", "蓝色墙面漆已经买好了", "本周加班安排已经公布了")
    fun parrotExamples(out: String): Boolean {
        val echoLine = out.lineSequence().any {
            (it.contains("->") || it.contains("→")) &&
                (it.contains("\"") || it.contains("“") || it.contains("「") ||
                 it.contains("Contoh") || it.contains("示例") || it.contains("例如"))
        }
        if (echoLine) return true
        return EXAMPLE_ZH.count { out.contains(it) } >= 2
    }

    // ---------------- v0.3.1 ②：词典按需注入 ----------------
    // 旧行为=19 条全量拼进提示词（≈150-200 token 预填充，长句白付 0.5-1.5s），
    // 且多数术语与当条消息无关，反而稀释指令。改为：只注入【本条消息里实际出现】的术语。
    // 匹配（对"源语言侧词条"，大小写不限）：
    //   含 ASCII 词干的术语 → 整词匹配（前后非字母数字）；另容忍「截尾变体」：
    //     词干≥5 的术语，消息里出现它去尾 1-2 字符的前缀且是完整单词 → 算命中
    //     （真机实证：词典 forklift，消息写 Forklif——印尼语借词常截尾）。
    //   纯中文术语 → 子串匹配（≥2 字；单字误匹配率高，跳过）。

    /** target="id" → 源语言=中文，比对 Term.val_；target="zh-CN" → 源语言=印尼文，比对 Term.key。 */
    fun matchedDict(src: String, target: String, dict: List<Term>): List<Term> {
        if (dict.isEmpty() || src.isEmpty()) return emptyList()
        val lower = src.lowercase()
        return dict.filter { containsTerm(lower, srcTermOf(it, target).lowercase()) }
    }

    private fun srcTermOf(t: Term, target: String): String = if (target == "id") t.val_ else t.key

    private fun isAsciiWord(c: Char) = c in 'a'..'z' || c in '0'..'9'

    /** hayLower 已小写；termLower 已小写。 */
    fun containsTerm(hayLower: String, termLower: String): Boolean {
        if (termLower.isEmpty()) return false
        val hasAscii = termLower.any { isAsciiWord(it) }
        if (!hasAscii) return termLower.length >= 2 && hayLower.contains(termLower)
        var from = 0
        while (true) {
            val i = hayLower.indexOf(termLower, from)
            if (i < 0) break
            val beforeOk = i == 0 || !isAsciiWord(hayLower[i - 1])
            val j = i + termLower.length
            val afterOk = j >= hayLower.length || !isAsciiWord(hayLower[j])
            if (beforeOk && afterOk) return true
            from = i + 1
        }
        // 截尾变体：term="forklift"，消息词="forklif"（term 的前 6-8 字符且成词）
        if (termLower.length >= 5) {
            for (len in (termLower.length - 1) downTo maxOf(4, termLower.length - 2)) {
                if (len < 4) continue
                val stem = termLower.substring(0, len)
                from = 0
                while (true) {
                    val i = hayLower.indexOf(stem, from)
                    if (i < 0) break
                    val beforeOk = i == 0 || !isAsciiWord(hayLower[i - 1])
                    val j = i + stem.length
                    val afterOk = j >= hayLower.length || !isAsciiWord(hayLower[j])
                    if (beforeOk && afterOk) return true
                    from = i + 1
                }
            }
        }
        return false
    }
}

// ---------------- v0.3.7 F6：提示词回显检测（四轮电池 STYLE-LIT 取证：低比特模型把
// 指令行/任务清单本身当译文吐出来——「注意，翻译内容严格遵循【印尼语语法】要求。」「你必须在
// 译文中保留等量的分隔符…」整句复读，且这类输出逃过 Echo.isEcho（与原文不等）与
// LangGate（含汉字）→ 会直接画上屏幕。此处用提示词**固定短语集**（不含原文相关词）
// 做子串判据，命中即拒绝该译文。短语集刻意保守：全部是"翻译指令措辞"，
// 正常运行消息（产线报告/人名/日程）不含这些字样，误伤概率极低；
// 代价 = 该句走一次采样重试，仍命中则判失败（KEEP/云兜底），绝不覆写气泡。
object InstructionEcho {
    // 特征词全部取自**生产提示词真实存在的句子**（LocalPrompt 布局 + ECHO_RETRY_NOTE）——
    // 模型只能复读自己见过的文本；电池版 STYLE/DELM 措辞（「严格符合」「印尼语语法」
    // 「保留等量的分隔符」…）不在生产提示词里，若收录反而误伤正常中文
    // （「结果必须严格符合标准」是合法译文）→ 刻意不收。
    val PHRASES = listOf(
        "将以下文本翻译", "注意只需要输出", "不要额外解释", "参考下面的翻译",
        "翻译成印度尼西亚语",
        "Translate the following", "without additional explanation",
        "聊天消息翻译器", "只输出一个 JSON", "不要输出任何其他文字",
        "上一次原样返回", "逐行保留换行",
    )
    /** 命中提示词固定短语 → 返回该短语（日志用），否则 null。
     *  只判 ≤120 字的输出：真译文超长的句子即便内嵌"翻译成中文"之类字样也是合法翻译，
     *  复读事故形态=整条输出就是一句指令（电池实测全部 ≤60 字）。 */
    fun hit(out: String): String? {
        val t = out.trim()
        if (t.isEmpty() || t.length > 120) return null
        return PHRASES.firstOrNull { t.contains(it) }
    }
}

// ---------------- v0.3.1 ④：本地输出语言闸门 ----------------
// 低比特模型对个别句子会稳定回显（真机实证：id→zh 输出整句仍是印尼文，
// 只改 1 个词 → 逃过 Echo.isEcho 全等判定 → 冒充译文进缓存）。闸门按【方向】要求
// 输出必须含目标语言字符：id→zh 须有汉字；zh→id 须有拉丁字母。不过 → 采样重试 → 仍不过 →
// 判失败（hybrid 云兜底 / 纯本地 KEEP），绝不写缓存。纯函数便于 JVM 单测。
object LangGate {
    fun cjkCount(s: String): Int = s.count { it.code in 0x4E00..0x9FFF }
    fun latinCount(s: String): Int = s.count { it in 'a'..'z' || it in 'A'..'Z' }

    /** 本地译文是否"像目标语言"。false=疑似回显/未翻译。 */
    fun ok(src: String, out: String, target: String): Boolean {
        if (out.isBlank()) return false
        val s = src.trim()
        if (s.length < 6) return true          // 与 Echo 同豁免：超短消息不误杀（"OK"/"5000"）
        // 原文本来就短/纯符号：只要求输出非空即放行交由 Echo 判全等
        return if (target == "zh-CN") cjkCount(out) >= 1 else latinCount(out) >= 2
    }
}

// ---------------- 批量分组（同 MAX_TEXT_LEN=4000 / MAX_BATCH_ITEMS=12 / MAX_BATCH_CHARS=4000） ----------------
object Batcher {
    const val MAX_TEXT_LEN = 4000
    const val MAX_BATCH_ITEMS = 12
    const val MAX_BATCH_CHARS = 4000

    /** texts → 分组下标列表（每组 ≤12 条且 ≤4000 字符；单条先截断） */
    fun group(texts: List<String>): List<List<Int>> {
        val groups = ArrayList<List<Int>>()
        var cur = ArrayList<Int>()
        var curLen = 0
        for ((idx, raw) in texts.withIndex()) {
            val t = raw.take(MAX_TEXT_LEN)
            if (cur.isNotEmpty() && (cur.size >= MAX_BATCH_ITEMS || curLen + t.length > MAX_BATCH_CHARS)) {
                groups.add(cur); cur = ArrayList(); curLen = 0
            }
            cur.add(idx); curLen += t.length
        }
        if (cur.isNotEmpty()) groups.add(cur)
        return groups
    }

    fun budgetFor(texts: List<String>): Int {
        val chars = texts.sumOf { it.take(MAX_TEXT_LEN).length }
        return minOf(8192, 200 + ((chars * 2.5 + 0.999).toInt()))
    }
}
