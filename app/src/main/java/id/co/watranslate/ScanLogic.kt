package id.co.watranslate

/*
 * ScanLogic.kt — 纯逻辑扫描层（零 Android 依赖，JVM 可单测）
 * 输入：无障碍节点树的简化模型 UiNode（含子节点）；输出：候选译文列表（文本+锚点矩形+方向）。
 * 过滤规则对齐扩展 v1.1.21（日期/加密提示/删除占位/引用头不译），但 Android 无障碍树里
 * 昵称与正文本就是独立节点（contact_name vs message_text_view），扩展里的 DOM 横幅战争不存在。
 */

data class UiNode(
    val text: String,
    val idSuffix: String,          // viewIdResourceName 去掉 "包名:id/" 前缀；无名则空串
    val className: String,
    val left: Int, val top: Int, val right: Int, val bottom: Int,
    val scrollable: Boolean,
    val clickable: Boolean = false,
    val children: List<UiNode> = emptyList(),
    val focused: Boolean = false,
    val raw: Any? = null,               // v0.1.9：真 AccessibilityNodeInfo 引用（仅 Service 侧 toUi 填；写输入框用）
) {
    val w get() = right - left
    val h get() = bottom - top

    fun findIds(pred: (String) -> Boolean): List<UiNode> {
        val out = ArrayList<UiNode>()
        fun walk(n: UiNode) {
            if (pred(n.idSuffix)) out.add(n)
            n.children.forEach(::walk)
        }
        walk(this)
        return out
    }

    fun allText(pred: (UiNode) -> Boolean): List<UiNode> {
        val out = ArrayList<UiNode>()
        fun walk(n: UiNode) {
            if (n.text.isNotBlank() && pred(n)) out.add(n)
            n.children.forEach(::walk)
        }
        walk(this)
        return out
    }

    /** v0.2.2 监控：节点矩形与给定区域是否有交集（tap miss 归因用） */
    fun overlapsRect(l: Int, t: Int, r: Int, b: Int): Boolean =
        minOf(right, r) - maxOf(left, l) > 0 && minOf(bottom, b) - maxOf(top, t) > 0
}

data class Candidate(val text: String, val anchor: UiNode, val own: Boolean) {
    // 气泡矩形：锚点节点文本可能只是气泡一部分，气泡容器另找
    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int)
}

object ScanLogic {

    /** v0.2.2 监控 sink：Service 注入 hook 后 pick 的过滤动作才有日志可见性；
     *  hook=null（单测/未注入）时 trace 是单次空判断 = 零开销，且绝不改候选结果。 */
    object WatchSink {
        @Volatile var hook: ((kind: String, node: UiNode, raw: String) -> Unit)? = null
        fun trace(kind: String, node: UiNode, raw: String) {
            try { hook?.invoke(kind, node, raw) } catch (_: Exception) {}   // 监控永不影响主流程
        }
    }

    // 气泡文本节点 id（按新版→旧版排列，命中即止；防 WhatsApp 改 id 有兜底启发式）
    val BUBBLE_TEXT_IDS = listOf("message_text_view", "message_text", "message_body_text")
    val NAME_IDS = listOf("contact_name", "group_chat_contact_name", "message_header_name")
    val DATE_IDS = listOf("date", "day", "message_date", "full_date")
    val QUOTE_IDS = listOf("reply_preview_text_view", "quoted_message_text_view", "reply_preview_text")
    // v0.2.7（真机树取证 2026-09-21 08:59 导出，118 节点全部 id=-）：新版 WhatsApp 对无障碍
    // 完全隐藏 view-id → QUOTE_IDS/SENDER_IDS 整层在真机失效，引用块文字漏成翻译候选，
    // 点回复消息译出的却是被引用的原问题。改用结构特征识别：引用块左侧的彩色细竖条。
    // 真机实证尺寸 11×220px（另两份样本 11×93）；正文文本左缘比条右缘仅偏 10~33px。
    fun isQuoteBar(n: UiNode): Boolean =
        n.className.substringAfterLast('.') == "View" && n.w in 2..24 && n.h >= 40

    /** 竖条是否"管辖"该文本节点（真机树实证尺寸）：条 b=135,1876,146,2096，引用文 b=168,1940
     *  → 左偏 22px/垂直覆盖 100% 命中；同消息正文 b=130,2096 → 与条零重叠 0% 不命中；
     *  消息4 caption b=124,1596 → 其邻近条 b=135,1043,627,1175(w=492 不算条) → 无条不命中。 */
    fun barCovers(t: UiNode, b: UiNode): Boolean {
        val dx = t.left - b.right
        if (dx < -4 || dx > 40) return false
        val ov = minOf(b.bottom, t.bottom) - maxOf(b.top, t.top)
        return ov * 5 >= t.h * 4
    }

    /** v0.2.7 hint 通道（真机部分节点 event.source 无 rect，只剩文字线索）：
     *  hint 能匹配到"全部是引用块内容"的树节点（引用文字与独立候选原文相同时=歧义，拒绝
     *  归位，宁按 v0.2.6 旧行为不译错），取引用条的最内层含候选祖先=整条回复消息，
     *  其子树内唯一候选（pick 已剔除引用文字，候选只剩正文）→ 归位。失败返回 null。 */
    fun quoteHintTarget(
        root: UiNode, bars: List<UiNode>, cands: List<Candidate>, hint: String,
    ): Candidate? {
        if (hint.isBlank() || cands.isEmpty()) return null
        val h = clean(hint)
        if (h.isEmpty()) return null
        // 精确匹配优先：event.text=被点节点文本原样（引用块文本被 WhatsApp 截 80 字，与
        // 屏上原句全文不同长度）→ 精确相等可消歧"点的是引用块"还是"点的是原文气泡"。
        val exact = ArrayList<UiNode>(); val pre = ArrayList<UiNode>()
        fun walk(n: UiNode) {
            val t = if (n.text.isNotBlank()) clean(n.text) else ""
            if (t.isNotEmpty()) {
                if (t == h) exact.add(n)
                else if (t.startsWith(h.take(60)) || h.startsWith(t.take(60))) pre.add(n)
            }
            n.children.forEach(::walk)
        }
        walk(root)
        val matched = if (exact.isNotEmpty()) exact else pre
        if (matched.isEmpty()) return null
        if (matched.any { m -> bars.none { barCovers(m, it) } }) return null  // 有一条匹配=非引用=可能点的是原文气泡，歧义放弃
        val theBar = bars.firstOrNull { barCovers(matched[0], it) } ?: return null
        if (matched.any { m -> !bars.any { barCovers(m, it) && it === theBar } }) return null  // 跨两处引用=含糊
        val anc = ArrayList<UiNode>()
        fun collect(n: UiNode, isRoot: Boolean) {
            if (!isRoot && n.left <= theBar.left && n.top <= theBar.top && n.right >= theBar.right && n.bottom >= theBar.bottom) anc.add(n)
            n.children.forEach { collect(it, false) }
        }
        collect(root, true)
        anc.sortBy { it.w.toLong() * it.h }
        for (a in anc) {
            val inner = cands.filter {
                it.anchor.left >= a.left && it.anchor.top >= a.top &&
                    it.anchor.right <= a.right && it.anchor.bottom <= a.bottom
            }
            if (inner.isEmpty()) continue
            return if (inner.size == 1) inner[0] else null
        }
        return null
    }

    /** v0.2.7：全树收集引用竖条（pick 排除层与 Service hint 归位通道共用） */
    fun collectQuoteBars(root: UiNode): List<UiNode> {
        val out = ArrayList<UiNode>()
        fun walk(n: UiNode) {
            if (isQuoteBar(n)) out.add(n)
            n.children.forEach(::walk)
        }
        walk(root)
        return out
    }
    val SYSTEM_IDS = listOf("system_message_text", "system_message", "message_text_system")

    // 文本形态黑名单（同扩展 cleanCandidateText 的核心集合）
    // 会话列表行 id——聊天列表页（conversations_row_*）的预览文本不是聊天气泡，整条排除。
    val ROW_LEAK_IDS = listOf("conversations_row", "row_title", "textview_row", "list_item") // 聊天列表行=预览非气泡
    // 引用头/发送者标签类 id（真机实证 2026-09-07：名片头「~ Erfra Irawan」泄漏被翻 → ⚠ 行）
    val SENDER_IDS = listOf("reply", "quoted", "participant", "sender", "author", "contact")
    val PHONE_RES = Regex("^\\+?\\d[\\d\\s().-]{6,}$")                 // 纯电话号（vCard/引用头号码）
    // WhatsApp 群内引用头昵称形态「~ Erfra Irawan」「@张三」：~/@ 开头+首字符非数字（"~500片"不误伤）
    val NICK_RES = Regex("^[~@]\\s*[^\\d].{0,38}$")
    // v0.1.5 真机聊天页树实证（2026-09-07 第4份诊断）：「已编辑」标签与时间并排
    val EDITED_RES = Regex("^(已编辑|diubah suntingan|edited|editado)$", RegexOption.IGNORE_CASE)
    /** v0.3.3 ②（真机 2026-09-23）：「已转发」角标整行无任何过滤 → 混进 mergeFragments
     *  被"忠实翻译"成 Sudah dikirimkan = 用户看到的"凭空加词"之一。中文/英文/印尼文
     *  三形态 + 常见带数字计数形态（「已转发2次」WhatsApp 部分版本）。 */
    val FORWARDED_RES = Regex("^(?:已转发|转发消息|forwarded message|forwarded|diteruskan|pesan diteruskan)(?:\\s*\\d+)?$", RegexOption.IGNORE_CASE)
    /** 剪掉不可见格式字符（LRM/RLM/双向嵌入层/ZWSP/BOM/软字号）并把 U+202F 换成普通空格 */
    fun clean(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) when (c) {
            '\u200E', '\u200F', '\u202A', '\u202B', '\u202C', '\u202D', '\u202E',
            '\u2066', '\u2067', '\u2068', '\u2069', '\u200B', '\u200C', '\u200D',
            '\uFEFF', '\u00AD' -> {}
            '\u202F' -> sb.append(' ')
            else -> sb.append(c)
        }
        return sb.toString().trim()
    }
    val PUKUL_RES = Regex("^(?:pukul|jam)\\s*\\d{1,2}[.:]\\d{2}(?:\\s*(?:pagi|siang|sore|malam))?$", RegexOption.IGNORE_CASE) // "Pukul 08:22" / "pukul 6.21 pagi"
    // 气泡内嵌时间戳（真机实证 2026-09-07：中文"上午8:22"被反译成"Pukul 08:22"贴出去）
    // v0.1.3 真机取证（聊天列表树 2026-09-07）：「昨天」白胶囊被翻成 kemarin 贴出 →
    // 分隔符名单补齐中文/印尼文全形态；CLOCK_RES 原来缺「晚上」前缀形态（截图 晚上8:20）
    // v0.3.2 真机取证（2026-09-23 日志 836b8104 + 截图 76e66be7）：「早上5:49/早上6:21」不在
    // 前缀名单 → 时间戳碎片被 mergeFragments 拼进正文 → 含汉字使 detectTarget 判成"中文→印尼文"
    // → 模型把印尼文正文"润色"一遍还顺手翻译尾巴时间（绿字结尾多出 Pukul x:xx pagi）。
    // 补全中文时段词；印尼语小数点时间形态（05.49）与裸时段后缀一并覆盖。
    val CLOCK_RES = Regex("^(?:上午|中午|下午|晚上|夜里|半夜|半夜里|凌晨|清晨|早上|傍晚|AM|PM)?\\s*\\d{1,2}[:.]\\d{2}(?:\\s*(?:AM|PM|am|pm|pagi|siang|sore|malam))?$", RegexOption.IGNORE_CASE)
    val DIVIDER_RES = Regex("^(?:昨天|今天|前天|星期[一二三四五六日天]|礼拜[一二三四五六日天]|周[一二三四五六日天]|\\d{4}年\\d{1,2}月\\d{1,2}日|\\d{1,2}月\\d{1,2}日| ayer|hoy|antes de ayer|ayer por la tarde|lunes|martes|miércoles|hueves|viernes|sábado|domingo|kemarin|hari ini|senin|selasa|rabu|kamis|jumat|sabtu|minggu)(?:\\s+(?:上午|中午|下午|晚上|夜里|半夜|半夜里|凌晨|清晨|早上|傍晚)?\\s*\\d{1,2}[.:]\\d{2})?$", RegexOption.IGNORE_CASE)
    val MEDIA_META_RES = Regex("^\\d{1,2}[:.]\\d{2}$")                 // 视频/语音时长角标 0:16 / 印尼语小数点 05.49
    // v0.1.8 真机日志实证（09-08 06:41:43 ✘）：图片消息 "585 kB\n5张照片" 被当正文送翻
    private val FILE_SIZE_RES = Regex("^\\d+(?:[.,]\\d+)?\\s*(?:kB|KB|MB|GB|B)$", RegexOption.IGNORE_CASE)
    private val PHOTO_COUNT_RES = Regex("^(?:\\d+\\s*)?(?:张照片|张图|photos?|fotos?|foto(?:\\.)?s?)$", RegexOption.IGNORE_CASE)
    // v0.3.6（真机 9/24 日志 218-220 行取证：「tap 首译: 2个视频」→ "2 video"）：
    // 媒体消息气泡的计数行——扫描路径被 drop 挡住，点击路径（hint/事件文本）没挡。
    val MEDIA_COUNT_RES = Regex("^(?:\\d+\\s*)?(?:个视频|条视频|个GIF|个动图|个贴纸|个文档|个文件|条语音|个音频|videos?|gifs?|stickers?|documents?|files?|audios?|voice messages?)$", RegexOption.IGNORE_CASE)
    val TIME_IDS = listOf("message_time", "primary_text", "secondary_text", "video_duration", "media_duration")
    val DELETED_RES = Regex(
        "^(?:🚫\\s*)?(?:这条消息已删除|此消息已删除|消息已删除|This message was deleted|You deleted this message|Pesan ini telah dihapus|Message ini dipadam)$"
    )
    val DATE_RES = Regex(
        "^(?:Hoy|Ayer|Anterior|Today|Yesterday|\\d{4}[./-]\\d{1,2}[./-]\\d{1,2}|\\d{1,2}/\\d{1,2}/\\d{2,4}|\\d{1,2}[:.]\\d{2}|\\d{1,2} \\w+ \\d{4}|(?:(?:LUNES|MARTES|MIÉRCOLES|JUEVES|VIERNES|SÁBADO|DOMINGO) )?\\d{1,2} DE \\w+ DE \\d{4}|\\d{1,2} \\w+ \\d{4})(?: \\d{1,2}[:.]\\d{2})?$",
        RegexOption.IGNORE_CASE
    )
    val ENCRYPTION_RES = Regex("(end-to-end|extremo a extremo|ujung ke ujung|端到端|加密|enkripsi)", RegexOption.IGNORE_CASE)
    val UNREAD_RES = Regex("^(?:\\d+ )?(unread messages|mensajes no leídos|pesan belum dibaca|未读消息|NEW MESSAGES|新消息)$", RegexOption.IGNORE_CASE)
    val TIME_ONLY = Regex("^\\d{1,2}[:.]\\d{2}(?:\\s*[AP]M)?$", RegexOption.IGNORE_CASE)

    /** 从整窗树挑出翻译候选。screenW = 屏幕宽。 */
    fun pick(root: UiNode, screenW: Int): List<Candidate> {
        // 消息列表容器：id 命中优先；退而求其次"屏幕中部的高容器"（兜底大改版）
        val byId = root.findIds { it.contains("messages_list") || it.contains("conversation_list") }
            .filter { it.scrollable || it.h > screenW }
            .maxByOrNull { it.h }
        val scroll = byId ?: root.findIds { false }.let { _ ->
            root.allText { false }.let { _ ->
                // 全树找非滚动大容器：h>70% 屏且不含顶栏
                var best: UiNode? = null
                fun walk(n: UiNode, depth: Int) {
                    if (n.h > screenW * 7 / 10 && depth >= 2 && (best == null || n.h < best!!.h)) best = n
                    if (depth < 8) n.children.forEach { walk(it, depth + 1) }
                }
                walk(root, 0)
                best ?: root
            }
        }
        val frags = ArrayList<Triple<UiNode, String, UiNode>>() // (文本节点, 原文, 气泡)

        // v0.2.7：引用块彩色竖条全集（结构识别，见 isQuoteBar 注释）。每轮扫描一次，
        // 典型聊天页 0~3 根，成本=节点遍历顺带判断，可忽略。
        val quoteBars = ArrayList<UiNode>()
        run {
            fun walkBars(n: UiNode) {
                if (isQuoteBar(n)) quoteBars.add(n)
                n.children.forEach(::walkBars)
            }
            walkBars(scroll)
        }

        val texts = scroll.allText { n -> BUBBLE_TEXT_IDS.any { n.idSuffix.contains(it) } }
        val useHeuristic = texts.isEmpty()
        val pool = if (useHeuristic)
            scroll.allText { it.text.length >= 2 } // 兜底：任何有文本的节点（再靠下面过滤）
        else texts

        for (t in pool) {
            // v0.1.5 真机聊天页树实证：WhatsApp 在「2条未读消息/5人在线」前塞 U+200E(LRM)，
            // Kotlin trim() 不剪方向标记 → ^..$ 锚定过滤器失配泄漏。统一先剪隐形字符再判。
            val raw = clean(t.text)
            if (raw.isEmpty()) continue
            // ↓↓ 以下每个 continue 前加 WatchSink.trace（v0.2.2 监控，仅 hook 非空时执行，判断逻辑零改动）
            if (ROW_LEAK_IDS.any { t.idSuffix.contains(it) }) { WatchSink.trace("row-leak", t, raw); continue } // 聊天列表页预览行
            // 几何裁剪：候选必须在列表容器内（title/输入框/按钮全在外）
            if (t.top < scroll.top || t.bottom > scroll.bottom + 40) { WatchSink.trace("outside-list", t, raw); continue }
            if (isNoise(t, raw)) { WatchSink.trace(dropReason(t, raw) ?: "noise", t, raw); continue }
            if (QUOTE_IDS.any { t.idSuffix.contains(it) }) { WatchSink.trace("quote-preview", t, raw); continue }
            // v0.2.7 真机（id 全隐藏）：文本被某根引用竖条"贴左+垂直覆盖 ≥80%"→ 引用块内容
            // （昵称行/正文行一并命中）。真机树实证见 barCovers 注释。
            if (quoteBars.any { barCovers(t, it) }) { WatchSink.trace("quote-bar", t, raw); continue }
            if (SYSTEM_IDS.any { t.idSuffix.contains(it) }) { WatchSink.trace("system-msg", t, raw); continue }
            if (NAME_IDS.any { t.idSuffix.contains(it) }) { WatchSink.trace("name-id", t, raw); continue }
            if (useHeuristic) {
                if (looksLikeStructureNoise(raw, t, scroll)) { WatchSink.trace("struct-noise", t, raw); continue }
                if (!t.className.contains("TextView")) { WatchSink.trace("not-textview", t, raw); continue } // 排除 Button/CheckBox/EditText
            }
            val bubble = bubbleOf(t, scroll, screenW)
            // own 双通道（真机截图 2026-09-07 实证）：短 own 消息文本节点距右缘 ~70px，
            // 旧版只判容器 + 56px 阈值会漏判（自己的短消息被当对方贴行）。任一命中即 own。
            val own = isOwnBubble(bubble, screenW) || (screenW - t.right < 96 && t.left > 96)
            if (own) { WatchSink.trace("own", t, raw); continue } // v1：只自动翻对方消息
            frags.add(Triple(t, raw, bubble))
        }
        // v0.1.3 气泡聚合（真机树实证：WhatsApp 把消息按行拆成并列 TextView）：
        // 同气泡/同文本行/垂直邻近的碎片拼成完整一条 → 长汇报整条译，不再只译一片。
        // v0.1.4：跨度上限 = 列表容器高的 60% —— 产品侧后备防线：布局中间态（SEED 瞬间边界
        // 不精确）才会把相邻消息连成巨块（模拟器实证 1060px；该场景已由 reseed 配方+anchorKey
        // 去重双保险解决，这里兜底）。v0.1.5 真机聊天页树复核：单条长汇报气泡 952px < 60%×1999
        // = 1199，不误拆。
        val out = mergeFragments(frags, scroll.h * 3 / 5)
        return out
    }

    /** v0.3.7 F3（真机 Q2 取证：「Buang angin D007」翻成「别担心，D007不会造成任何问题」）：
     *  mergeFragments 会把独立元数据碎片节点（小字昵称、时间戳、「1条视频」角标、电话号）
     *  与正文拼成多行 merged 送进模型；旧版只剔首行发送者名+电话/角标等正则命中行。
     *  模型对着脏文本自由发挥 → 凭空安抚句。本函数=行级元数据清洗（含单行：
     *  bodyOf("2个视频")="" 旧语义保持）；**调用方负责兜底**——mergeFragments 全删光
     *  时保留原文（纯角标消息宁脏勿丢），bodyOf 全删光返回 ""（tap-copy 触发判据用）。 */
    fun bodyLines(text: String): List<String> {
        val lines = text.split('\n').map { clean(it) }.filter { it.isNotEmpty() }
        return lines.filterNot { isMetaLine(it) }
    }
    /** 行是否 WhatsApp 元数据碎片（昵称/电话/时间/角标/转发/已编辑…）。
     *  注意与「正文含数字」的区别：只删**整行恰好是**元数据形态的行。 */
    fun isMetaLine(line: String): Boolean =
        CLOCK_RES.matches(line) || PUKUL_RES.matches(line) ||
            PHONE_RES.matches(line) || NICK_RES.matches(line) ||
            EDITED_RES.matches(line) || FORWARDED_RES.matches(line) ||
            MEDIA_META_RES.matches(line) || FILE_SIZE_RES.matches(line) ||
            PHOTO_COUNT_RES.matches(line) || MEDIA_COUNT_RES.matches(line) ||
            DATE_RES.matches(line) || TIME_ONLY.matches(line) ||
            DIVIDER_RES.matches(line) || DELETED_RES.matches(line) ||
            UNREAD_RES.matches(line) ||
            ENCRYPTION_RES.containsMatchIn(line)

    /** v0.3.6：点击文本 → 正文行提取（tap-copy 触发判据用）。
     *  真机取证（9/24 日志）：点击事件的合并文本 = 昵称+电话+「已转发」+正文+时间戳，
     *  旧版拿合并长度 ≥76 判截断 → 15 字消息误触发长按骚扰（真机 15 次探测 0 成功）。
     *  逐行套 dropReason 同款纯文本判据（本函数无节点，id 类规则不适用），
     *  剩余行按原文顺序拼接。全被滤掉返回 ""（媒体气泡/纯角标）。 */
    fun bodyOf(text: String): String {
        // v0.3.7：判据抽进 bodyLines（含拉丁昵称首行剔除），行为超集；
        // 先走 senderNameLine 保持 v0.3.6 语义（群聊气泡首行中文发送者名，
        // WhatsApp 复制**不含**这一行，留着会击穿 tap-copy 的全文前缀判定）。
        val noName = senderNameLine(text) ?: text
        return bodyLines(noName).joinToString("\n").trim()
    }

    /** v0.3.3 ②b（真机 09-23 组聊「凭空加词」另一半）：群聊对方气泡上方的小字发送者名
     *  是独立 TextView（联系人显示名「陈副厂长」：无 ~/@ 前缀 → NICK_RES 拦不住；真机
     *  WhatsApp 118/118 节点 id 全隐藏 → SENDER_IDS 失效），mergeFragments 按阅读序把它
     *  与正文合法拼成一条 → src 首行是人名 → 模型"忠实翻译"出 Wakil Direktur Chen 这种
     *  凭空多出来的行（demo 手工粘贴无此碎片，输出正常）。
     *  判据（三条件同时成立才动刀，宁保守不误杀正文）：
     *   ① 只有首行可能被删（WhatsApp 名角标恒在正文之上，阅读序第一）；
     *   ② 首行=纯汉字 1~8 字（无任何拉丁/数字/标点混排，"陈副厂长"✓、"A006停机"✗）；
     *   ③ 其余行拉丁字母合计 ≥12（确认正文主体是印尼文，非中文消息夹术语）。 */
    fun senderNameLine(text: String): String? {
        val lines = text.split("\n")
        if (lines.size < 2) return null
        val first = lines[0].trim()
        if (first.isEmpty()) return null
        val cjk = Lang.cjkCount(first)
        if (cjk !in 1..8 || first.length != cjk) return null
        val bodyLatin = Lang.latinCount(lines.subList(1, lines.size).joinToString("\n"))
        if (bodyLatin < 12) return null
        val rest = lines.subList(1, lines.size).joinToString("\n").trim()
        return rest.takeIf { it.isNotEmpty() }
    }

    /** 碎片 → 候选：锚点=最外层共同容器；文本=按阅读序（top,left）去重拼接。
     *  聚合信号（任一）：① 一个碎片的文本框包含另一个（同气泡拆行）；
     *  ② 文本框垂直重叠且水平间距 <24px（引用头昵称+正文并列）；
     *  ③ 锚点矩形完全相同。 */
    fun mergeFragments(frags: List<Triple<UiNode, String, UiNode>>, maxSpanPx: Int = Int.MAX_VALUE): List<Candidate> {
        val n = frags.size
        if (n == 0) return emptyList()
        val parent = IntArray(n) { it }
        fun find(a: Int): Int { var x = a; while (parent[x] != x) x = parent[x]; var y = a; while (parent[y] != x) { val t = parent[y]; parent[y] = x; y = t }; return x }
        fun union(a: Int, b: Int) { val ra = find(a); val rb = find(b); if (ra != rb) parent[rb] = ra }
        for (i in 0 until n) for (j in i + 1 until n) {
            val (ni, _, bi) = frags[i]; val (nj, _, bj) = frags[j]
            val sameAnchor = bi.left == bj.left && bi.top == bj.top && bi.right == bj.right && bi.bottom == bj.bottom
            // v0.1.4：碎片气泡并集纵向跨度超过上限（默认无限）→ 不合。真机一条消息远小于半屏，
            // 布局中间态（SEED/滚动瞬间）边界不精确才会把三条消息零间隙连成巨块（模拟器实证 1060px）
            val span = maxOf(bi.bottom, bj.bottom) - minOf(bi.top, bj.top)
            if (span > maxSpanPx) continue
            if (sameAnchor || containsBox(ni, nj) || containsBox(nj, ni) || nearStack(ni, nj) || stackedLines(ni, nj)) union(i, j)
        }
        val groups = HashMap<Int, ArrayList<Triple<UiNode, String, UiNode>>>()
        for (i in 0 until n) groups.getOrPut(find(i)) { ArrayList() }.add(frags[i])
        val out = ArrayList<Candidate>()
        val seen = HashSet<String>()
        groups.values.map { g -> g.sortedWith(compareBy({ it.first.top }, { it.first.left })) }
            .forEach { g ->
                val parts = ArrayList<String>()
                g.forEach { f ->
                    // 被已有碎片前缀覆盖（同文本被两路都抓到）→ 只延长它
                    val hit = parts.indexOfFirst { it.startsWith(f.second) }
                    if (hit >= 0) { if (f.second.length > parts[hit].length) parts[hit] = f.second }
                    else parts.add(f.second)
                }
                val merged0 = parts.joinToString("\n")
                // v0.3.3 ②b：首行若是被 merge 进来的小字发送者名（纯汉字短行+正文为印尼文），
                // 剔除后作为候选文本与锚点键（同源 → 点击通道/缓存键同步变干净）。
                // v0.3.7 F3：再套 bodyLines——多行 merged 里任何位置的整行元数据
                // （时间戳/角标/电话/「已转发」…）与首行拉丁昵称碎片全部剔除，
                // 真机 Q2「Buang angin D007→别担心…」= 昵称+元数据混进正文送模型自由发挥。
                val preName = senderNameLine(merged0) ?: merged0
                val bl = bodyLines(preName)
                val merged = if (bl.isEmpty() && preName.isNotBlank()) preName.trim()
                             else bl.joinToString("\n").trim()
                if (merged !== merged0 && merged.isNotEmpty()) WatchSink.trace("sender-name-line", g.first().first, merged0.take(40))
                // 锚点=所有碎片气泡矩形的并集包围盒：同气泡时=该容器；
                // 启发式路径下碎片自成锚时也完整覆盖整条消息（点击命中/行定位都需要全矩形）
                val anchor = UiNode("", "", "merge",
                    g.minOf { it.third.left }, g.minOf { it.third.top },
                    g.maxOf { it.third.right }, g.maxOf { it.third.bottom },
                    false, false)
                val key = merged + "@" + anchor.left + "," + anchor.top
                if (seen.add(key)) out.add(Candidate(merged, anchor, false))
            }
        return out.sortedWith(compareBy({ it.anchor.top }, { it.anchor.left }))
    }

    private fun containsBox(a: UiNode, b: UiNode): Boolean {
        val areaB = (b.w.toLong() * b.h).coerceAtLeast(1)
        val ix = (minOf(a.right, b.right) - maxOf(a.left, b.left)).coerceAtLeast(0).toLong()
        val iy = (minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)).coerceAtLeast(0).toLong()
        return ix * iy * 10 >= areaB * 9 // b 面积 ≥90% 在 a 内
    }

    /** 同行并列碎片（昵称+正文那类）：垂直重叠 + 水平间隙极小 */
    private fun nearStack(a: UiNode, b: UiNode): Boolean {
        val vOv = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        if (vOv <= 0) return false
        val gap = maxOf(a.left - b.right, b.left - a.right)
        return gap < 24
    }

    /** 上下邻行碎片（长消息按行拆开的 TextView）：垂直间隙 ≤6px 且水平重叠 ≥70%。
     *  不同气泡之间 WhatsApp 至少留 ~12px 行距，不会误合。 */
    private fun stackedLines(a: UiNode, b: UiNode): Boolean {
        val (u, v) = if (a.top <= b.top) a to b else b to a
        if (v.top - u.bottom > 6) return false
        val ov = (minOf(u.right, v.right) - maxOf(u.left, v.left)).coerceAtLeast(0)
        val minW = minOf(u.w, v.w).coerceAtLeast(1)
        return ov * 10 >= minW * 7
    }

    /** v0.2.2 监控：节点为何被过滤掉（null=不被过滤）。只用于 drop 日志定位
     *  「图片 caption / 引用气泡 / 个别气泡点了没反应」是哪条规则拦的——判断内容与旧
     *  isNoise 完全等价（拆分自旧函数，无新增条件）。 */
    fun dropReason(n: UiNode, raw: String): String? = when {
        DATE_IDS.any { n.idSuffix.contains(it) } -> "date-id"
        TIME_IDS.any { n.idSuffix.contains(it) } -> "time-id"
        SENDER_IDS.any { n.idSuffix.contains(it) } -> "sender/reply-id"   // 引用头/发送者标签
        CLOCK_RES.matches(raw) || PUKUL_RES.matches(raw) -> "clock"
        PHONE_RES.matches(raw) -> "phone"
        NICK_RES.matches(raw) -> "nick"
        EDITED_RES.matches(raw) -> "edited"
        FORWARDED_RES.matches(raw) -> "forwarded"              // v0.3.3 ②「已转发」角标（真机泄漏实证）
        MEDIA_META_RES.matches(raw) || FILE_SIZE_RES.matches(raw) -> "media-meta"  // 0:16 / 585 kB
        PHOTO_COUNT_RES.matches(raw) -> "photo-count"                    // 5张照片
        MEDIA_COUNT_RES.matches(raw) -> "media-count"                    // v0.3.6「2个视频」（真机取证）
        raw.lines().all { it.isBlank() || FILE_SIZE_RES.matches(it.trim()) || PHOTO_COUNT_RES.matches(it.trim()) || MEDIA_COUNT_RES.matches(it.trim()) } -> "badge-only"
        DATE_RES.matches(raw) || TIME_ONLY.matches(raw) || DIVIDER_RES.matches(raw) -> "divider"
        DELETED_RES.matches(raw) -> "deleted"
        ENCRYPTION_RES.containsMatchIn(raw) -> "e2e-notice"
        UNREAD_RES.matches(raw) -> "unread"
        Regex("^(?:\\d+\\s*)?(?:条未读消息|pesan belum dibaca|unread)", RegexOption.IGNORE_CASE).containsMatchIn(raw) -> "unread-partial"
        QUOTE_IDS.any { n.idSuffix.contains(it) } -> "quote-preview"     // 旧 pick 里独立判断，并入供日志归因
        SYSTEM_IDS.any { n.idSuffix.contains(it) } -> "system-msg"
        NAME_IDS.any { n.idSuffix.contains(it) } -> "name-id"
        ROW_LEAK_IDS.any { n.idSuffix.contains(it) } -> "row-leak"       // 聊天列表预览行
        else -> null
    }

    fun isNoise(n: UiNode, raw: String): Boolean =
        DATE_IDS.any { n.idSuffix.contains(it) } ||
            TIME_IDS.any { n.idSuffix.contains(it) } ||   // message_time/primary_text=气泡内时间戳
            SENDER_IDS.any { n.idSuffix.contains(it) } || // 引用头/发送者标签（reply_preview 等，正文在 message_text_view 另一节点，不误伤）
            CLOCK_RES.matches(raw) || PUKUL_RES.matches(raw) || // "上午8:22"/"Pukul 08:22"
            PHONE_RES.matches(raw) ||                     // 引用头/名片里的纯电话号
            NICK_RES.matches(raw) ||                      // "~ Erfra Irawan" 群引用头昵称（真机实证）
            EDITED_RES.matches(raw) ||                    // 「已编辑」标签（真机聊天页树实证）
            FORWARDED_RES.matches(raw) ||                 // v0.3.3 ②「已转发」角标（真机 09-23 泄漏实证）
            MEDIA_META_RES.matches(raw) ||
            FILE_SIZE_RES.matches(raw) || PHOTO_COUNT_RES.matches(raw) || MEDIA_COUNT_RES.matches(raw) ||   // v0.1.8 图片角标（585 kB / 5张照片）/ v0.3.6「2个视频」
            raw.lines().all { it.isBlank() || FILE_SIZE_RES.matches(it.trim()) || PHOTO_COUNT_RES.matches(it.trim()) || MEDIA_COUNT_RES.matches(it.trim()) } ||  // 聚合后的纯角标气泡
            DATE_RES.matches(raw) || TIME_ONLY.matches(raw) || DIVIDER_RES.matches(raw) ||
            DELETED_RES.matches(raw) || ENCRYPTION_RES.containsMatchIn(raw) ||
            UNREAD_RES.matches(raw) ||
            Regex("^(?:\\d+\\s*)?(?:条未读消息|pesan belum dibaca|unread)", RegexOption.IGNORE_CASE).containsMatchIn(raw)

    /** 兜底启发式专用结构噪声：时间戳/未读数/状态条等 */
    private fun looksLikeStructureNoise(raw: String, n: UiNode, scroll: UiNode): Boolean {
        if (raw.length <= 1) return true
        if (Regex("^[\\d\\s:+/APap.]*$").matches(raw)) return true // 纯数字/时间
        // 顶栏会话标题/副标题（不在滚动列表内的文本）
        if (n.top < 0 || n.bottom > scroll.bottom + 400) return true
        return false
    }

    /** v0.2.7（真机树取证：点回复消息的引用区/昵称区 → 应归位到该条正文）：
     *  点击矩形不命中任何候选时，从最内层向外找"恰好包含一个候选"的公共容器，
     *   该候选即目标。结构性安全：
     *  - 引用块/昵称行的父容器=整条消息 → 子树里唯一候选=正文 ✓
     *  - 纯图片消息无候选 → 一路上爬到含多候选的祖先 → null（不瞎猜）
     *  - own 消息子树 0 候选 → 同上 null（点自己消息仍无反应，v1 语义不变）
     *  副产品（③图片文字部分自愈）：caption 图片消息上点图片 → 容器唯一候选=caption → 译 caption。 */
    fun tapContainerTarget(root: UiNode, cands: List<Candidate>, l: Int, t: Int, r: Int, b: Int): Candidate? {
        if (cands.isEmpty()) return null
        val containers = ArrayList<UiNode>()
        fun walk(n: UiNode) {
            if (n.left <= l && n.top <= t && n.right >= r && n.bottom >= b) containers.add(n)
            n.children.forEach(::walk)
        }
        walk(root)
        containers.sortBy { it.w.toLong() * it.h }
        for (c in containers) {
            val inSub = cands.filter {
                it.anchor.left >= c.left && it.anchor.top >= c.top &&
                    it.anchor.right <= c.right && it.anchor.bottom <= c.bottom
            }
            if (inSub.size == 1) return inSub[0]
            if (inSub.size > 1) return null   // 祖先只会更多，含多条=区域含糊，放弃
        }
        return null
    }

    /** 文本节点 → 气泡容器：所有严格更大的祖先中最小者。
     *  v0.1.1 修复（真机截图实证）：旧版允许节点自身入选——短消息 text 节点远窄于气泡，
     *  自身"最小" → rightGap 大 → own 判定失效（自己发的短消息被贴行，长的反而正常）。
     *  现要求候选比文本节点宽/高都更大（真气泡有 padding），否则继续向上。 */
    fun bubbleOf(textNode: UiNode, root: UiNode, screenW: Int): UiNode {
        val contain = ArrayList<UiNode>()
        fun collect(n: UiNode, isRoot: Boolean) {
            if (!isRoot && n.left <= textNode.left && n.top <= textNode.top &&
                n.right >= textNode.right && n.bottom >= textNode.bottom) contain.add(n)
            n.children.forEach { collect(it, false) }
        }
        collect(root, true)
        val byArea = contain.sortedBy { it.w.toLong() * it.h.toLong() }
        return byArea.firstOrNull {
            it !== textNode && (it.w > textNode.w + 8 || it.h > textNode.h + 8) && it.w <= screenW
        } ?: textNode
    }

    /** v0.2.1（用户定案）：「译」按钮与对方头像列对齐 → x 基准=消息列表容器左缘。
     *  真机树实证：头像 ImageView b=27,...，列表容器 b=0,...；取候选气泡最小 left
     *  （对方气泡左缘=头像右侧文本起点会偏大），所以优先列表容器 left+内边距兜底。 */
    fun avatarColumnX(root: UiNode, screenW: Int): Int {
        val lists = root.findIds { it.contains("messages_list") || it.contains("conversation_list") }
        val listLeft = lists.maxByOrNull { it.h }?.left ?: 0
        // 头像在列表容器左缘 + 系统内边距（真机树实证：容器 b=0 → 头像 ImageView b=27）
        return listLeft + 27
    }

    /** v0.1.9：聊天输入框节点（对齐扩展 getComposer：存在且取底部那个）。
     *  id 命中 message_edit_text/input/composer 优先；退而求其次 className=EditText 且在屏幕下半部。
     *  返回 null = 不在聊天页（会话列表/设置没有输入框）。 */
    fun findComposer(root: UiNode, screenH: Int): UiNode? {
        // id 命中是强信号（WhatsApp message_edit_text 只出现在聊天页输入框）→ 位置只做弱校验：
        // 键盘弹起会把输入框顶到屏幕中部，硬卡 1/2 会漏（v0.1.9 e2e 实证）
        val byId = root.findIds { n ->
            n.contains("message_edit_text") || n.contains("input") || n.contains("compose")
        }.filter { it.top > screenH / 5 && it.w > 100 }
        if (byId.isNotEmpty()) return byId.maxByOrNull { it.bottom }
        val edits = ArrayList<UiNode>()
        fun walk(n: UiNode) {
            if (n.className.contains("EditText") && n.top > screenH / 3 && n.w > 100) edits.add(n)
            n.children.forEach(::walk)
        }
        walk(root)
        return edits.maxByOrNull { it.bottom }
    }

    /** 点击坐标 → 命中的对方气泡候选（v0.1.2 点击翻译：pick 已滤 own/噪声，点自己消息=无命中） */
    fun bubbleAt(cands: List<Candidate>, x: Int, y: Int): Candidate? =
        cands.firstOrNull { it.anchor.left <= x && x < it.anchor.right && it.anchor.top <= y && y < it.anchor.bottom }

    /** TYPE_VIEW_CLICKED 的 source bounds → 命中气泡（点文本节点/气泡/整行都能归位）：
     *  1. 谁包含点击矩形 → 面积最小者；2. 点击中心在谁里面；3. 最大重叠面积（>0 才算）。
     *  整行点击 + 窄气泡（"Ya"）靠 3 兜底；own 行与对方气泡纵向零重叠 → miss 正确。 */
    fun bubbleInRect(cands: List<Candidate>, l: Int, t: Int, r: Int, b: Int): Candidate? {
        val inside = cands.filter { it.anchor.left <= l && it.anchor.top <= t && it.anchor.right >= r && it.anchor.bottom >= b }
        if (inside.isNotEmpty()) return inside.minByOrNull { (it.anchor.w.toLong() * it.anchor.h) }
        bubbleAt(cands, (l + r) / 2, (t + b) / 2)?.let { return it }
        var best: Candidate? = null; var bestA = 0L
        for (c in cands) {
            val a = maxOf(0, minOf(r, c.anchor.right) - maxOf(l, c.anchor.left)).toLong() *
                    maxOf(0, minOf(b, c.anchor.bottom) - maxOf(t, c.anchor.top))
            if (a > bestA) { bestA = a; best = c }
        }
        return best
    }

    /** v0.1.4 智能上下位：默认画气泡下方；下方放不下（被 WhatsApp 输入栏/屏底吃掉）
     *  且上方放得下 → 翻到气泡上方。纯函数便于 JVM 单测。 */
    fun rowY(bubbleTop: Int, bubbleBottom: Int, rowH: Int, screenH: Int, bottomReserve: Int): Int {
        val below = bubbleBottom + 2
        if (below + rowH <= screenH - bottomReserve) return below
        val above = bubbleTop - rowH - 2
        if (above >= 0) return above
        return below   // 两头都放不下（超长消息）→ 保持下方，行随滚动能露出来
    }

    /** v0.2.4（真机日志取证：29 次点击落在输入框文字上「泵浦电流太低…」，全在徒劳想翻译）：
     *  点击矩形（TYPE_VIEW_CLICKED source bounds）与 composer 矩形重叠 ≥40%（点击矩形占比）
     *  → 判定"点了输入框"。点 source 常就是 EditText 本身（重叠 100%）；部分文字节点/滞后旧
     *  快照留 60% 余量。触发=复用「译」链路（翻译并写回输入框），无破坏性。纯函数 JVM 可测。 */
    fun composerHit(cl: Int, ct: Int, cr: Int, cb: Int, ml: Int, mt: Int, mr: Int, mb: Int): Boolean {
        val w = (cr - cl).toLong(); val h = (cb - ct).toLong()
        val mw = (mr - ml).toLong(); val mh = (mb - mt).toLong()
        if (w <= 0 || h <= 0 || mw <= 0 || mh <= 0) return false
        val ix = (minOf(cr, mr) - maxOf(cl, ml)).coerceAtLeast(0).toLong()
        val iy = (minOf(cb, mb) - maxOf(ct, mt)).coerceAtLeast(0).toLong()
        return ix * iy * 5 >= w * h * 2
    }

    /** v0.2.3 问题①：rowY 之后再钳一道——行太高上下位都放不下时贴输入栏上方
     *  （用户截图实证 15 行译文顶出屏底看不见后半段）。limit 太小说明行比屏还高，
     *  不钳（保持 rowY 结果，随滚动露出）。纯函数便于 JVM 单测。 */
    fun clampRowY(y: Int, rowH: Int, screenH: Int, bottomReserve: Int, minTop: Int): Int {
        val limit = screenH - bottomReserve - rowH
        return if (limit > minTop && y > limit) limit else y
    }

    /**
     * 自己发的判定（右锚定）：own 气泡贴右缘。歧义（全宽长文）按对方处理（宁可多翻不可漏翻——
     * 漏翻工厂长报告是真事故，错翻自己的中文只是噪音）。
     */
    fun isOwnBubble(bubble: UiNode, screenW: Int): Boolean {
        val rightGap = screenW - bubble.right
        val leftGap = bubble.left
        return if (rightGap < 56 && leftGap > 56) true
        else if (leftGap < 56) false
        else false // 居中/全宽 → 视为对方
    }
}
