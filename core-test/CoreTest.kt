package id.co.watranslate

/* CoreTest.kt — JVM 单测（无 Android 依赖）：词典/回显/JSON/批量分组/扫描过滤/profiles 解析
 * 断言集复刻扩展 test_dict / test_linealign / test_fuzzy / test_bg 的等价场景。 */

fun check(name: String, cond: Boolean) {
    if (cond) println("  通过 $name") else { println("  FAIL $name"); throw AssertionError(name) }
}

fun check(name: String, cond: Boolean, detail: String) {
    if (cond) println("  通过 $name") else { println("  FAIL $name :: $detail"); throw AssertionError(name + " :: " + detail) }
}

fun main() {
    println("== J1 Json ==")
    check("字符串数组解析",
        Json.parseStringArray("[\"a\",\"b\"]") == listOf("a", "b"))
    check("转义解析",
        Json.parseStringArray("[\"line1\\nline2\",\"\\\"引\\\"\"]") == listOf("line1\nline2", "\"引\""))
    check("extractArray 剥围栏+取首末括号",
        Json.extractArray("好的：```json\n[\"x\",\"y\"]\n```完了") == listOf("x", "y"))
    check("extractArray 内嵌方括号字符串",
        Json.extractArray("[\"a[b]c\",\"d\"]") == listOf("a[b]c", "d"))
    check("extractContent OpenAI 响应",
        Json.extractContent("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"你好\\n世界\"}}]}") == "你好\n世界")
    check("escape 往返",
        Json.parseStringArray("[" + '"' + Json.escape("他说\"好\"\n行二") + '"' + "]").first() == "他说\"好\"\n行二")

    println("== J2 词典 ==")
    val dict = Dict.parse("CCD = 瑕疵检出机\nmixing=轧轮机\nmixer = 混合桶\nemboss = 压花轮\nbanbury:万马力\n：坏行无视\nscanner：测厚仪")
    check("解析 6 条（=/:/：混用，坏行跳过）", dict.size == 6)
    check("键小写", dict.any { it.key == "ccd" && it.val_ == "瑕疵检出机" })
    check("整词匹配 mixing vs mixer",
        Dict.hasTerm("mixing", "bagian mixing machine") && !Dict.hasTerm("mixing", "mixer bowl") &&
            Dict.hasTerm("mixer", "mixer bowl"))
    check("词形不误伤（bambury≠banbury 整词）",
        Dict.hasTerm("banbury", "mesin banbury") && !Dict.hasTerm("banbury", "mesin bambury"))
    // 强制纠正：模型把 mixing 译成 mixer 的译文 → 改回
    val fixed = Dict.enforce("混合桶停机维修", dict, "mixing stop", false)
    check("行级纠正 混合桶→轧轮机", fixed == "轧轮机停机维修")
    check("两术语共存不纠正",
        Dict.enforce("混合桶和轧轮机都开", dict, "mixer dan mixing jalan", false) == "混合桶和轧轮机都开")
    check("模糊一词之差 压花辊→压花轮",
        Dict.enforce("更换压花辊", dict, "ganti emboss roll", false) == "更换压花轮")
    check("源中文反向不纠正", Dict.enforce("混合桶停了", dict, "轧轮机停了", true) == "混合桶停了")
    check("行对齐：A行 mixing B行 mixer",
        Dict.enforce("混合桶坏了\n混合桶也坏", dict, "mixing rusak\nmixer juga rusak", false) == "轧轮机坏了\n混合桶也坏")

    println("== J3 回显 ==")
    check("原样吐回=echo", Echo.isEcho("Selamat pagi Pak, laporan sudah saya kirim", "Selamat pagi Pak, laporan sudah saya kirim"))
    check("空格差异仍 echo", Echo.isEcho("a b c d e f g", "Abcdefg"))
    check("正常译文不误判", !Echo.isEcho("Selamat pagi teman", "早上好朋友们"))
    check("极短<6字符放行", !Echo.isEcho("OK", "OK"))
    check("空译文判失败", Echo.isEcho("abc def", null) && Echo.isEcho("abc def", ""))
    check("纯型号翻译不误判", !Echo.isEcho("model XYZ-100", "型号XYZ-100"))

    println("== J4 批量分组 ==")
    val g = Batcher.group(List(30) { "x" })
    check("30条→3组 12/12/6", g.map { it.size } == listOf(12, 12, 6))
    val big = Batcher.group(listOf("a".repeat(4200), "b"))
    check("超限截断进组", big.size == 2)
    check("预算 ≤8192", Batcher.budgetFor(List(12) { "y".repeat(400) }) == 8192)
    check("预算下限 200+ceil(2*2.5)", Batcher.budgetFor(listOf("hi")) == 205)

    println("== J5 profiles ==")
    val pj = "[{\"id\":\"a\",\"name\":\"scnet\",\"apiBase\":\"https://s/v1\",\"apiKey\":\"k1\",\"model\":\"M1\",\"temperature\":0.2},{\"id\":\"b\",\"name\":\"  \",\"apiBase\":\"x\",\"apiKey\":\"\",\"model\":\"\",\"temperature\":5}]"
    val list = ConfigResolve.parseProfiles(pj)
    check("无名条目丢弃", list.size == 1 && list[0].name == "scnet")
    val cfg1 = ConfigResolve.resolve(pj, "b-missing", null, null, null, null, true, true, "")
    check("activeProfile 失效→回退第一条", cfg1.apiKey == "k1" && cfg1.model == "M1")
    val cfg2 = ConfigResolve.resolve(null, null, "https://flat/v1", "flatk", "FlatM", 0.5, true, true, "")
    check("无 profiles→扁平回退", cfg2.apiKey == "flatk" && cfg2.model == "FlatM" && cfg2.temperature == 0.5)
    val cfg3 = ConfigResolve.resolve("不是json", null, null, null, null, null, true, true, "")
    check("坏 JSON→默认值", cfg3.model == "Qwen3.8-Flash" && cfg3.apiBase == "https://api.scnet.cn/api/llm/v1")

    println("== J6 提示词 ==")
    check("system 逐行一致", Prompt.SYSTEM.contains("必须逐行翻译") && Prompt.SYSTEM.contains("JSON 字符串数组"))
    check("带词典 system 含正反表", Prompt.withDict(dict).contains("mixing=轧轮机") && Prompt.withDict(dict).contains("轧轮机=mixing"))
    check("user payload JSON", Prompt.userPayload(listOf("a\"b", "c\nd")) == "[\"a\\\"b\",\"c\\nd\"]")
    check("single 方向印尼语", Prompt.singleSystem("id", dict).contains("印尼语"))

    println("== J7 扫描过滤（UiNode 树模拟） ==")
    fun node(t: String, id: String = "", l: Int = 0, tp: Int = 0, r: Int = 300, b: Int = 100, kids: List<UiNode> = emptyList()) =
        UiNode(t, id, "android.widget.TextView", l, tp, r, b, false, false, kids)
    // 屏宽 1080；对方气泡贴左 left<56；自己贴右
    val screenW = 1080
    fun tree(vararg kids: UiNode) = UiNode("", "messages_list", "android.widget.ScrollView", 0, 120, screenW, 2200, true, false, kids.toList())
    val t1 = tree(
        node("3/9/2026", "date"),                                  // 噪声：日期 id
        node("Los mensajes están cifrados de extremo a extremo.", "message_text_view"), // 加密提示
        node("Selamat pagi Pak", "message_text_view", 60, 300, 560, 400),  // 对方 → 译
        node("你好明天开会", "message_text", 640, 500, 1030, 600),         // 自己（右锚定）→ 不译
        node("这条消息已删除", "message_text_view", 60, 700, 360, 800),    // 删除占位
        node("19:33", "message_text_view", 60, 900, 120, 940),            // 纯时间
        node("Pagi Bu", "contact_name", 60, 1000, 200, 1050),             // 昵称节点
    )
    val cands = ScanLogic.pick(t1, screenW)
    check("只选中 1 条对方正文", cands.size == 1 && cands[0].text == "Selamat pagi Pak")
    // 兜底启发式（id 全没中）
    val t2 = UiNode("", "something_else", "", 0, 120, screenW, 2200, true, false, listOf(
        node("Selamat sore semua", "", 60, 200, 500, 280),
        node("今天车间休班", "", 620, 300, 1030, 380),
    ))
    val c2 = ScanLogic.pick(t2, screenW)
    check("无 id 兜底命中左气泡", c2.size == 1 && c2[0].text == "Selamat sore semua")
    // 右锚定歧义（全宽长文）→ 按对方处理
    val t3 = UiNode("", "", "", 0, 0, screenW, 2200, false, false, listOf(
        node("panjar panjang laporan produksi hari ini yang melintasi separuh layar", "", 10, 300, 1070, 400),
    ))
    check("全宽长文按对方翻（宁多不漏）", ScanLogic.pick(t3, screenW).size == 1)
    check("isOwnBubble 左=对方", !ScanLogic.isOwnBubble(node("", "", 60, 0, 560, 100), screenW))
    check("isOwnBubble 右=自己", ScanLogic.isOwnBubble(node("", "", 640, 0, 1030, 100), screenW))

    println("== J8 真机形态嵌套树（2026-09-07 用户截图取证回归） ==")
    fun box(id: String, l: Int, tp: Int, r: Int, b: Int, kids: List<UiNode>) =
        UiNode("", id, "android.widget.FrameLayout", l, tp, r, b, false, false, kids)
    // 对方气泡：容器(贴左) + 正文 + 气泡内嵌中文时间戳
    val peer = box("message_text_view_container", 27, 1012, 780, 1150, listOf(
        node("Buangan angin", "message_text_view", 60, 1030, 300, 1090),
        node("上午8:22", "message_time", 640, 1100, 750, 1140)))
    // 自己短气泡：贴右容器（v0.1.0 bug=文本节点自身当气泡 → rightGap 大 → own 漏判）
    val ownB = box("message_text_container", 620, 1300, 1030, 1450, listOf(
        node("D007 diaktifkan terlebih dahulu.", "message_text", 660, 1320, 960, 1380),
        node("上午8:23", "message_time", 970, 1400, 1020, 1440)))
    // 引用头：昵称 + 电话号
    val quote = box("reply_preview", 60, 1600, 800, 1750, listOf(
        node("~ Erfra Irawan", "contact_name", 150, 1610, 500, 1660),
        node("+62 821-3646-0329", "quoted_contact", 150, 1670, 600, 1720)))
    // 视频时长角标
    val dur = node("0:16", "video_duration", 60, 900, 140, 940)
    val t8 = UiNode("", "messages_list", "android.widget.ScrollView", 0, 120, screenW, 2200, true, false,
        listOf(dur, peer, ownB, quote))
    val c8 = ScanLogic.pick(t8, screenW)
    check("J8 只剩对方正文", c8.size == 1 && c8[0].text == "Buangan angin",
        "cands=" + c8.map { it.text })
    check("J8 锚点=气泡容器非文本节点", c8[0].anchor.top == 1012)
    // 时间戳形态防御（哪怕 WhatsApp 换 id）
    check("J8 上午8:22 形态=噪声", ScanLogic.isNoise(node("上午8:22", "weird_new_id"), "上午8:22"))
    check("J8 电话号形态=噪声", ScanLogic.isNoise(node("+62 821-3646-0329", "whatever"), "+62 821-3646-0329"))
    check("J8 Pukul 08:22 形态=噪声", ScanLogic.isNoise(node("Pukul 08:22", ""), "Pukul 08:22"))

    println()

    println("== J9 点击翻译（v0.1.2） ==")
    // 真机截图形态：左对方气泡 x=60..700, y=800..950；右own气泡 x=500..1020, y=1000..1120
    val tapPeer = UiNode("Pompa blm hidup", "message_text_view", "android.widget.TextView", 60, 800, 700, 950, false, false, emptyList())
    val tapPeerBox = UiNode("", "", "android.widget.FrameLayout", 40, 780, 720, 980, false, true, listOf(tapPeer))
    val tapOwn = UiNode("D007 SUHU", "message_text_view", "android.widget.TextView", 500, 1000, 1020, 1120, false, false, emptyList())
    val tapOwnBox = UiNode("", "", "android.widget.FrameLayout", 480, 980, 1040, 1150, false, true, listOf(tapOwn))
    val t9 = UiNode("", "messages_list", "android.widget.ScrollView", 0, 120, 1080, 2200, true, false, listOf(tapPeerBox, tapOwnBox))
    val c9 = ScanLogic.pick(t9, 1080)
    check("J9 pick 只含对方正文", c9.size == 1 && c9[0].text == "Pompa blm hidup")
    check("J9 坐标命中对方气泡", ScanLogic.bubbleAt(c9, 300, 850)?.text == "Pompa blm hidup")
    check("J9 坐标点 own 区域无候选（pick 已滤）", ScanLogic.bubbleAt(c9, 900, 1050) == null)
    check("J9 source=文本节点 bounds → 命中", ScanLogic.bubbleInRect(c9, 60, 800, 700, 950)?.text == "Pompa blm hidup")
    check("J9 source=整行(比气泡大) → 中心点兜底命中", ScanLogic.bubbleInRect(c9, 0, 770, 1080, 990)?.text == "Pompa blm hidup")
    check("J9 source=own 行 → miss", ScanLogic.bubbleInRect(c9, 480, 980, 1040, 1150) == null)
    // 窄气泡「Ya」：点整行（source=全宽容器），中心点落在 own 空档，靠重叠兜底命中
    val ya = UiNode("Ya", "message_text_view", "android.widget.TextView", 60, 1200, 260, 1300, false, false, emptyList())
    val yaBox = UiNode("", "", "android.widget.FrameLayout", 40, 1180, 280, 1330, false, true, listOf(ya))
    val t9b = UiNode("", "messages_list", "android.widget.ScrollView", 0, 120, 1080, 2200, true, false, listOf(yaBox))
    val c9b = ScanLogic.pick(t9b, 1080)
    check("J9 全宽行点击窄气泡 → 重叠兜底命中", ScanLogic.bubbleInRect(c9b, 0, 1170, 1080, 1340)?.text == "Ya")
    // 昵称/引用头过滤（真机 ⚠ 行根因）
    check("J9 「~ Erwin Suryantoro」=噪声", ScanLogic.isNoise(UiNode("~ Erwin Suryantoro", "conv_constraint", "TextView", 0,0,0,0,false), "~ Erwin Suryantoro"))
    check("J9 reply 类 id=噪声", ScanLogic.isNoise(UiNode("Ya", "reply_preview_text_custom", "TextView", 0,0,0,0,false), "Ya"))
    check("J9 正文 ~500片 不误伤(数字开头)", !ScanLogic.isNoise(UiNode("~500片材料已到", "message_text_view", "TextView", 0,0,0,0,false), "~500片材料已到"))
    check("J9 正常印尼正文不误伤", !ScanLogic.isNoise(UiNode("Pompa blm hidup", "message_text_view", "TextView", 0,0,0,0,false), "Pompa blm hidup"))

    println("== J10 长消息按行拆分聚合（v0.1.3 真机树取证） ==")
    fun box2(id: String, l: Int, tp: Int, r: Int, b: Int, kids: List<UiNode>) =
        UiNode("", id, "android.widget.FrameLayout", l, tp, r, b, false, true, kids)
    // 形态①：WhatsApp 把一条长消息拆成多个并列 TextView（不同 y、同气泡容器）
    val f1 = node("Laporan kerja", "message_text_view", 60, 400, 500, 448)
    val f2 = node("1. Pasang kabel transformer", "message_text_view", 60, 452, 620, 500)
    val f3 = node("a. Pasang kabel input 3x240mm x 15m sudah Di tarik", "message_text_view", 60, 504, 700, 552)
    val bubbleA = box2("message_text_view_container", 40, 380, 780, 580, listOf(f1, f2, f3))
    // 「昨天」日期胶囊（kemarin 泄漏根因）：白胶囊 TextView、居中、无业务 id → 兜底路径也会碰到
    val capA = node("昨天", "", 480, 300, 600, 350)
    // 形态②：引用头 昵称+正文 并列两个 TextView（真机树 49/52 行原样坐标比例）
    val qN = node("~ P M ( Supomo ): ", "message_text_view", 198, 321, 541, 383)
    val qB = node("Update suhu D005", "message_text_view", 599, 321, 954, 383)
    val bubbleB = box2("message_text_view_container", 190, 315, 960, 390, listOf(qN, qB))
    val t10 = UiNode("", "messages_list", "android.widget.ScrollView", 0, 120, 1080, 2200, true, false,
        listOf(capA, bubbleA, bubbleB))
    val c10 = ScanLogic.pick(t10, 1080)
    check("J10 昨天胶囊=噪声（不译不被点）", ScanLogic.isNoise(capA, "昨天"))
    check("J10 昨天+时间 形态=噪声", ScanLogic.isNoise(node("昨天 20:15", ""), "昨天 20:15"))
    check("J10 Kemarin形态自身也滤(大小写)", ScanLogic.isNoise(node("Kemarin", ""), "Kemarin"))
    check("J10 长消息聚合成一条", c10.any { it.text == "Laporan kerja\n1. Pasang kabel transformer\na. Pasang kabel input 3x240mm x 15m sudah Di tarik" },
        "cands=" + c10.map { it.text.take(24) })
    check("J10 聚合锚=外层气泡容器", c10.first { it.text.startsWith("Laporan") }.anchor.top == 380)
    check("J10 引用头正文独立成条", c10.any { it.text == "Update suhu D005" }, "cands=" + c10.map { it.text.take(24) })
    check("J10 引用头昵称 NICK_RES 过滤不入候选", c10.none { it.text.contains("Supomo") })
    check("J10 两气泡不误合（垂直间隙>6px）", c10.size == 2, "cands=" + c10.map { it.text.take(16) })
    check("J10 聚合后点击坐标命中整条", ScanLogic.bubbleInRect(c10, 40, 380, 780, 580)?.text?.startsWith("Laporan") == true)
    // stackedLines 边界：不同气泡间隙 12px → 不合
    val g1 = node("Pesan satu", "message_text_view", 60, 700, 500, 760)
    val g2 = node("Pesan dua", "message_text_view", 60, 772, 500, 832)
    val c10b = ScanLogic.mergeFragments(listOf(
        Triple(g1, "Pesan satu", g1), Triple(g2, "Pesan dua", g2)))
    check("J10 12px 气泡间隙不误合", c10b.size == 2)
    val h1 = node("baris satu", "message_text_view", 60, 700, 500, 765)
    val h2 = node("baris dua", "message_text_view", 60, 768, 460, 830)
    val c10c = ScanLogic.mergeFragments(listOf(
        Triple(h1, "baris satu", h1), Triple(h2, "baris dua", h2)))
    check("J10 3px 行间隙聚合成一条", c10c.size == 1 && c10c[0].text == "baris satu\nbaris dua")

    println("== J11 译文行智能上下位（v0.1.4 用户定案：底部放不下→翻上方） ==")
    // 屏幕 2306，输入栏预留 276px（≈92dp），行高 60
    // 普通气泡（底 1200）→ 下方 1202
    check("J11 常规=下方", ScanLogic.rowY(1040, 1200, 60, 2306, 276) == 1202)
    // 贴底气泡（底 2080，1202 场景会伸进输入栏）→ 翻上方
    check("J11 贴底→上方", ScanLogic.rowY(1900, 2080, 60, 2306, 276) == 1838)
    // 边界：下方刚好放得下（bubbleBottom+2+60 == 2306-276=2030 → bottom=1968）→ 仍下方
    check("J11 边界=下方优先", ScanLogic.rowY(1900, 1968, 60, 2306, 276) == 1970)
    // 上方也放不下（气泡顶 30 贴屏顶）→ 保持下方（随滚动能露出）
    check("J11 两头放不下→保持下方", ScanLogic.rowY(20, 2080, 60, 2306, 276) == 2082)
    // 翻上方后行贴气泡顶 -2px
    check("J11 上方=气泡顶-行高-2", ScanLogic.rowY(2100, 2260, 60, 2306, 276) == 2038)
    // span 上限（模拟器 E1 竞态实证 2026-09-07：布局中间态三条消息零间隙被连成 1060px 巨块）
    val sA = node("satu", "message_text_view", 60, 100, 500, 240)
    val sB = node("dua", "message_text_view", 60, 246, 500, 386)   // 间隙 6px，无上限会合并
    val mergedNoCap = ScanLogic.mergeFragments(listOf(Triple(sA, "satu", sA), Triple(sB, "dua", sB)))
    val mergedCap = ScanLogic.mergeFragments(listOf(Triple(sA, "satu", sA), Triple(sB, "dua", sB)), 200)
    check("J11 span 上限拦截巨合并", mergedNoCap.size == 1 && mergedCap.size == 2,
        "nocap=${mergedNoCap.size} cap=${mergedCap.size}")

    println("== J12 真机聊天页树回归（2026-09-07 第4份诊断实证） ==")
    // WhatsApp 隐形字符：LRM(U+200E) 前缀 + 昵称分隔 U+202F —— trim() 不剪，过滤器曾失配
    check("J12 clean 剪 LRM/RLM/ZWSP", ScanLogic.clean("\u200e2条未读消息\u200b") == "2条未读消息")
    check("J12 clean 窄空格→普通空格", ScanLogic.clean("~\u202fRB Trysno") == "~ RB Trysno")
    check("J12 「已编辑」=噪声", ScanLogic.isNoise(node("已编辑", ""), "已编辑"))
    check("J12 「edited」=噪声", ScanLogic.isNoise(node("edited", ""), "edited"))
    check("J12 「2条未读消息」=噪声（clean 前缀路径）",
        ScanLogic.isNoise(node("x", ""), ScanLogic.clean("\u200e2条未读消息")))
    check("J12 「5人在线」顶栏不误判成正文（clean 后几何在列表外）",
        !ScanLogic.isNoise(node("5人在线", ""), "5人在线"))  // 文本层不拦（防误杀），靠几何裁剪拦——下面全树验证
    check("J12 引用头昵称（U+202F 形态）clean 后仍被 NICK 滤",
        ScanLogic.isNoise(node("x", ""), ScanLogic.clean("~\u202fRB Trysno")))
    // 真机原样坐标：长汇报正文 124,1839→826,2175（336px）单节点完整（聊天页不按行拆，与列表页不同）
    // 文件消息体在 Button 节点（135,876→815,1828 高952px）→ 启发式排 Button 不产文本，正文=其下 TextView
    val realList = UiNode("", "messages_list", "android.widget.ListView", 0, 248, 1080, 2235, true, false, listOf(
        node("\u200e2条未读消息", "", 390, 677, 689, 768),
        node("~\u202fRB Trysno", "", 152, 812, 368, 865),
        node("+62 858-6643-2499", "", 473, 815, 798, 861),
        node("A005...DI BAHAN ada FLEK2 HITAM..Diduga dari callender baru no.3.. mohon IJIN ST", "",
            124, 1839, 826, 2175),
        node("已编辑", "", 554, 2175, 667, 2224),
        node("晚上7:03", "", 667, 2175, 798, 2224)))
    val c12 = ScanLogic.pick(realList, 1080)
    check("J12 真机树只剩 1 条正文（昵称/号码/已编辑/时间/未读全滤）",
        c12.size == 1 && c12[0].text.startsWith("A005...DI BAHAN"), "cands=" + c12.map { it.text.take(18) })

    // ---- J13（v0.1.8）：图片消息角标泄漏（真机日志 09-08 06:41:43 ✘ "585 kB\n5张照片"）----
    check("J13 「585 kB」单行=噪声", ScanLogic.isNoise(node("585 kB", ""), "585 kB"))
    check("J13 「5张照片」单行=噪声", ScanLogic.isNoise(node("5张照片", ""), "5张照片"))
    check("J13 聚合「585 kB\\n5张照片」整块=噪声", ScanLogic.isNoise(node("x", ""), "585 kB\n5张照片"))
    check("J13 英文形态 2.3 MB / 3 photos=噪声",
        ScanLogic.isNoise(node("x", ""), "2.3 MB") && ScanLogic.isNoise(node("x", ""), "3 photos"))
    check("J13 印尼形态 5 foto=噪声", ScanLogic.isNoise(node("x", ""), "5 foto."))
    check("J13 正文含数字不误伤（585片材料已到）", !ScanLogic.isNoise(node("585片材料已到", "message_text_view"), "585片材料已到"))
    check("J13 正常印尼正文不误伤（带 kB 字样句子）",
        !ScanLogic.isNoise(node("File laporan cuma 585 kB kok", "message_text_view"), "File laporan cuma 585 kB kok"))
    val imgNode = UiNode("", "messages_list", "android.widget.ListView", 0, 248, 1080, 2235, true, false, listOf(
        node("585 kB", "", 400, 900, 560, 940),
        node("5张照片", "", 400, 940, 560, 980)))
    val c13 = ScanLogic.pick(imgNode, 1080)
    check("J13 纯图片气泡不产候选", c13.isEmpty(), "cands=" + c13.map { it.text.take(18) })

    // ---- J14（v0.1.9）：聊天输入框识别 findComposer ----
    val chatWin = UiNode("", "messages_list", "android.widget.ListView", 0, 248, 1080, 2100, true, false, listOf(
        node("Selamat pagi", "message_text_view", 27, 300, 600, 360),
        UiNode("", "", "android.widget.FrameLayout", 0, 2100, 1080, 2306, false, false, listOf(
            node("", "message_edit_text", 150, 2150, 900, 2260)))))
    val comp = ScanLogic.findComposer(chatWin, 2306)
    check("J14 message_edit_text=输入框", comp != null && comp.idSuffix == "message_edit_text",
        "comp=" + comp?.idSuffix)
    val listWin = UiNode("", "conv_list", "android.widget.ListView", 0, 248, 1080, 2306, true, false, listOf(
        node("~ Daniel Fu", "contact_name", 198, 94, 599, 200)))
    check("J14 会话列表页无输入框", ScanLogic.findComposer(listWin, 2306) == null)
    val clsWin = UiNode("", "", "FrameLayout", 0, 0, 1080, 2306, false, false, listOf(
        UiNode("halo", "", "android.widget.EditText", 150, 2150, 900, 2260, false, true)))
    val comp2 = ScanLogic.findComposer(clsWin, 2306)
    check("J14 id缺失退EditText类名兜底", comp2 != null && comp2.className.contains("EditText"))
    val topEdit = UiNode("", "", "FrameLayout", 0, 0, 1080, 2306, false, false, listOf(
        UiNode("搜索", "", "android.widget.EditText", 100, 100, 980, 180, false, true)))
    check("J14 顶部搜索框不算输入框", ScanLogic.findComposer(topEdit, 2306) == null)

    // ---- J15（v0.1.9）：包裹形态回显（e2e E5 实证 ZH[原文] 被当正常译文缓存）----
    check("J15 ZH[原文] 包裹=回显", Echo.isEcho("Saya akan lembur malam ini bersama tim", "ZH[Saya akan lembur malam ini bersama tim]"))
    check("J15 [原文] 裸括号=回显", Echo.isEcho("Selamat pagi teman semua", "[Selamat pagi teman semua]"))
    check("J15 正常中文译文不误判", !Echo.isEcho("Saya akan lembur malam ini bersama tim", "今晚我和B班技术组一起加班"))
    check("J15 译文含原文片段但确有翻译→放行", !Echo.isEcho("Pompa blm hidup pak", "泵还没开起来，先生（Pompa blm hidup）"))

    // ---- J16（v0.2.2 监控）：Watch 启发 + dropReason 归因 + trace 零行为影响 ----
    println("== J16 监控埋点 ==")
    check("J16 人名=专名启发", Watch.looksLikeProperName("谭效平") && Watch.looksLikeProperName("陈副厂长"))
    check("J16 印尼人名(无空格)命中", Watch.looksLikeProperName("siswanto"))
    // v0.2.3：含空格拒判（TEKNIK INNAN=文件名；Saya akan lembur...=句子，字母比0.82会误判）
    check("J16 含空格不是专名", !Watch.looksLikeProperName("TEKNIK INNAN") &&
        !Watch.looksLikeProperName("HOU, Wen Sung"))
    check("J16 带数字编号不是专名", !Watch.looksLikeProperName("A006 stop"))
    check("J16 文件后缀不是专名", !Watch.looksLikeProperName("Chang.pdf") && !Watch.looksLikeProperName("code.csv"))
    check("J16 长句不是专名", !Watch.looksLikeProperName("Saya akan lembur malam ini bersama tim"))
    check("J16 纯标点不是专名", !Watch.looksLikeProperName("..."))
    check("J16 长文预警", Watch.longSource("LAPORAN KERUSAKAN\nMesin A006 stop\nPerbaikan\nGanti\nEstimasi\nTeam") && !Watch.longSource("Pompa blm hidup pak"))
    check("J16 归因-昵称", ScanLogic.dropReason(node("~ Erfra Irawan", "message_text_view"), "~ Erfra Irawan") == "nick")
    check("J16 归因-引用头", ScanLogic.dropReason(node("Pompa blm hidup", "reply_preview_text_view"), "Pompa blm hidup") == "sender/reply-id")
    check("J16 归因-角标", ScanLogic.dropReason(node("585 kB", "message_text_view"), "585 kB") == "media-meta")
    check("J16 归因-正常文本=无", ScanLogic.dropReason(node("Pompa blm hidup pak", "message_text_view"), "Pompa blm hidup pak") == null)
    check("J16 归因与isNoise一致", ScanLogic.dropReason(node("5张照片", "message_text_view"), "5张照片") != null)
    // trace 不改变候选：挂 hook 前后 pick 结果必须逐字节一致
    val j16Tree = tree(
        node("~ Erfra Irawan", "message_text_view", 60, 200, 400, 280),         // nick 拦
        node("585 kB", "message_text_view", 60, 300, 260, 380),                 // media-meta 拦
        node("Pompa blm hidup pak", "message_text_view", 60, 800, 700, 950))    // 放行
    val seenDrops = ArrayList<String>()
    ScanLogic.WatchSink.hook = { kind, _, raw -> seenDrops.add(kind + "|" + raw) }
    val withHook = ScanLogic.pick(j16Tree, screenW).map { it.text }
    ScanLogic.WatchSink.hook = null
    val withoutHook = ScanLogic.pick(j16Tree, screenW).map { it.text }
    check("J16 hook不改变候选", withHook == withoutHook && withHook == listOf("Pompa blm hidup pak"), "with=$withHook wo=$withoutHook")
    check("J16 trace抓到归因", seenDrops.any { it.startsWith("nick|") } && seenDrops.any { it.startsWith("media-meta|") }, seenDrops.joinToString())

    // ---- J17（v0.2.3 问题①）：clampRowY 纯函数 ----
    println("== J17 长行钳位 ==")
    // 常规（行放得下）：不动
    check("J17 无需钳=原样", ScanLogic.clampRowY(1202, 60, 2306, 276, 96) == 1202)
    // 15 行高行（≈900px）贴底气泡 rowY 结果 2082 → 钳到 2306-276-900=1130
    check("J17 超高行钳位贴输入栏", ScanLogic.clampRowY(2082, 900, 2306, 276, 96) == 1130)
    // 边界：刚好等于 limit → 不动
    check("J17 边界=不动", ScanLogic.clampRowY(1130, 900, 2306, 276, 96) == 1130)
    // limit < minTop（行比可用区还高）→ 不钳，保持 rowY（随滚动露出）
    check("J17 行比屏高=不钳", ScanLogic.clampRowY(2082, 2000, 2306, 276, 96) == 2082)
    // 上方位置(y 小)不受影响
    check("J17 上方位=不动", ScanLogic.clampRowY(1838, 60, 2306, 276, 96) == 1838)
    // 学习判定：3-12 汉字/字母无空格无数字 → 真；2 字常用语 → 假（不学）
    check("J17 二字常用语不是人名", !Watch.looksLikeProperName("收到") && !Watch.looksLikeProperName("好的"))
    check("J17 三字名仍真", Watch.looksLikeProperName("谭效平") && Watch.looksLikeProperName("王小明"))

    // ---- J18（v0.2.4）：回显强化重试资格 + composerHit ----
    println("== J18 echo-retry / composer-hit ==")
    // 真机取证：谭效平+Oke silakan 混合消息(54字)有资格；纯人名无资格（留给 v0.2.3 学习分支）
    val b18 = listOf("谭效平", "谭效平\nOke, silakan dicek pak", "TPM FORKLIFT", "Terima kasih")
    check("J18 混合有资格/纯名无资格",
        Watch.echoRetryQualify(b18, 4, listOf(0, 1, 2, 3)) == listOf(1, 2, 3))
    check("J18 全空子集=null", Watch.echoRetryQualify(b18, 4, listOf<Int>()) == null)
    // 条数不对齐（arrN!=batchN）→ null（重试无从映射回原下标）
    check("J18 条数不对齐=null", Watch.echoRetryQualify(b18, 3, listOf(1)) == null)
    // keepAsIs：3字以下无保留价值，>200 字不灰显整行
    check("J18 keepAsIs 边界", !Watch.keepAsIs("收到吗") && Watch.keepAsIs("收到吗好的") && !Watch.keepAsIs("啊".repeat(201)))
    // composerHit：完全命中=真；半重叠(50%)=真；20%重叠=假；零面积防御
    check("J18 全命中", ScanLogic.composerHit(100, 100, 300, 140, 100, 100, 300, 140))
    // 点击 200x40=8000，重叠 200x20=4000 → 4000*5=20000 >= 8000*2=16000 真
    check("J18 半重叠=真", ScanLogic.composerHit(100, 100, 300, 140, 100, 120, 300, 200))
    // 点击 200x40=8000，重叠 40x20=800 → 800*5=4000 < 16000 假
    check("J18 少重叠=假", !ScanLogic.composerHit(100, 100, 300, 140, 360, 120, 400, 200))
    check("J18 零面积=假", !ScanLogic.composerHit(100, 100, 100, 140, 100, 100, 300, 140))
    check("J18 KEEP 哨兵≠PN", Watch.KEEP_MARK != Watch.PN_MARK)

    // ---- J19（v0.2.5）：思考泄漏三闸门 —— 数据形状全部来自真机 07:23 日志 L480-503 实测 ----
    println("== J19 思考泄漏闸门 ==")
    // 1) 成对 <thinking>…</thinking> 后接正常数组 → strip 剥离，extractExpectedArray 救回
    val paired = "<thinking>The user wants me to translate this. Let me think.</thinking>\n[\"Minta tolong dibantu, Pak\"]"
    check("J19 成对think+数组救回", Json.extractExpectedArray(paired, 1) == listOf("Minta tolong dibantu, Pak"))
    // 2) 真机未闭合形状："\n\n<think>\nThe user wants m…"（无  只有 1 项 → throw
    val leakEssay = "\n\n<think>\nThe user wants me to translate this WhatsApp message from Chinese to Indonesian. " +
        "I should keep the tone casual.\n\n用户要求我把这条消息翻译成印尼语，我需要先理解语境。\n"
    val stripped = Reasoning.strip(leakEssay)
    check("J19 未闭合think剥标签", !stripped.contains("<think>") && stripped.startsWith("The user wants"))
    check("J19 英文essay闸门命中", Reasoning.looksLikeReasoning(stripped))
    check("J19 中文essay闸门命中", Reasoning.looksLikeReasoning("用户要求我把这条消息翻译成印尼语，我先分析语气。"))
    var threw = ""
    try { Json.extractExpectedArray(leakEssay, 1) } catch (e: Exception) { threw = e.message ?: "" }
    check("J19 纯essay→throw含期望数", threw.contains("含 1 项"), threw)
    // 3) think + 双数组（真机「条数不匹配 期望1 得到2」形状）→ 批量 2 项可救回，1 项必 throw
    val twoArr = "The user wants…translate…\n[\"Satu\",\"Dua\"]"
    check("J19 think+2项数组救回", Json.extractExpectedArray(twoArr, 2) == listOf("Satu", "Dua"))
    var threw2 = ""
    try { Json.extractExpectedArray(twoArr, 1) } catch (e: Exception) { threw2 = e.message ?: "" }
    check("J19 期望1得2→throw", threw2.contains("含 1 项"), threw2)
    // 4) 反误杀：正常译文/印尼文正文绝不命中闸门
    check("J19 正常译文不误杀",
        !Reasoning.looksLikeReasoning("Minta tolong dibantu, Pak") &&
        !Reasoning.looksLikeReasoning("Ya, bisa.") &&
        !Reasoning.looksLikeReasoning("Saya akan rapat pukul 7. TPF") &&   // saya akan + 无 menterjemah
        !Reasoning.looksLikeReasoning("LAPORAN PERBAIKAN FORKLIFT 23 Juli"))
    // 5) 围栏+think 混合（模型爱输出 ```json 围栏再带思考）
    val fenced = "```json\n[\"Oke, siap\"]\n```"
    check("J19 围栏数组救回", Json.extractExpectedArray(fenced, 1) == listOf("Oke, siap"))
    // 6) strip 幂等：干净译文 strip 后不变
    check("J19 strip幂等", Reasoning.strip("Selamat pagi, Pak.") == "Selamat pagi, Pak.")


    // ---------- J20（v0.2.6 P1+P2）单条链路回显强化重试资格 ----------
    // 真机取证 9/20「电力还没有好吗？」/9/21「先关闭…D007的阀门。」：single 回显直接拒写；
    // v0.2.6 起 N=1 直发 single + 降级 single 都进共享重试链——资格 = keepAsIs 且非纯专名
    // （纯专名留给 v0.2.3 人名学习分支，语义不变）。
    fun qual(s: String) = Watch.keepAsIs(s) && !Watch.looksLikeProperName(s)
    check("J20 中文问句有重试资格", qual("电力还没有好吗？"))
    check("J20 长阀门口令有重试资格", qual("先关闭旧厂蒸汽分配槽到的D007的阀门。"))
    check("J20 混合消息有重试资格", qual("谭效平\nOke silakan lanjut"))
    check("J20 纯人名不给重试资格(留给PN学习)", !qual("王小明") && !qual("谭效平"))
    check("J20 超短回声不给资格", !qual("Oke") && !qual("Ya"))
    check("J20 超长文本不给资格", !qual("x".repeat(201)))

    // ---- J21（v0.2.7）：引用块结构识别 —— 几何 1:1 复刻真机树（09-21 08:59 导出 119 行）----
    println("== J21 引用竖条识别（真机树复刻）==")
    fun idNode(t: String, id: String = "", cls: String = "android.widget.TextView", l: Int = 0, tp: Int = 0, r: Int = 300, b: Int = 100, kids: List<UiNode> = emptyList()) =
        UiNode(t, id, cls, l, tp, r, b, false, false, kids)
    fun view(l: Int, tp: Int, r: Int, b: Int) = UiNode("", "", "android.view.View", l, tp, r, b, false, false)
    fun grp(l: Int, tp: Int, r: Int, b: Int, kids: List<UiNode>) = UiNode("", "", "android.widget.LinearLayout", l, tp, r, b, false, false, kids)
    // 真机消息5：回复（昵称行+引用竖条+引用文+正文），外层消息容器 b=124,1804,937,2233
    val nickLine5 = grp(124, 1804, 937, 1865, listOf(          // 昵称行（正则本会拦，双保险）
        idNode("~\u202fmohamad sodiq.f", l = 152, tp = 1812, r = 506, b = 1865),
        idNode("+62 819-0122-2218", l = 595, tp = 1815, r = 909, b = 1861)))
    val quoteText5 = UiNode("\u200cArea komposisi tolong di tutup lagi krn retak kmrn mk", "", "android.widget.TextView",
        168, 1940, 761, 2085, false, false)                     // 引用文字（截断形态）
    val quoteInner5 = grp(146, 1876, 783, 2096, listOf(
        idNode("~\u202fSri Sumarn\u2026", l = 168, tp = 1887, r = 427, b = 1940),
        quoteText5))
    val quoteGroup5 = UiNode("", "", "android.widget.FrameLayout", 124, 1865, 937, 2096, false, true, listOf(
        view(135, 1876, 146, 2096),                             // ← 引用彩色竖条（真机 11×220）
        quoteInner5))
    val bodyGroup5 = UiNode("", "", "android.widget.FrameLayout", 124, 2096, 937, 2233, false, false, listOf(
        idNode("Baik terimakasih informasinya Bu Marni", l = 130, tp = 2096, r = 931, b = 2227)))  // 正文
    val msg5 = grp(124, 1804, 937, 2233, listOf(nickLine5, quoteGroup5, bodyGroup5))
    // 真机消息4：同一句话的独立 caption（b=124,1596 无邻近细条 → 绝不能被误伤）
    val msg4 = grp(124, 561, 826, 1776, listOf(
        UiNode("", "", "android.widget.FrameLayout", 124, 622, 826, 1596, false, false, listOf(
            view(323, 1043, 627, 1175))),                     // 播放键大 View（w=304 不算条）
        idNode("Area komposisi tolong di tutup lagi krn pada retak kmrn mksh", l = 124, tp = 1596, r = 826, b = 1706)))
    // 真机树消息容器位于深度≥9（pick 容器启发式 walk 只到 depth 8 → 消息不会被误选为
    // scroll 容器——fixture 必须复刻这一深度，否则 h=1215 的 msg4 会顶掉列表容器）
    fun chain(levels: Int, leafKids: List<UiNode>): UiNode =
        if (levels == 0) UiNode("", "", "android.widget.ListView", 0, 248, 1080, 2247, true, false, leafKids)
        else UiNode("", "", "android.widget.FrameLayout", 0, 248, 1080, 2247, false, false, listOf(chain(levels - 1, leafKids)))
    val j21tree = UiNode("", "", "android.widget.FrameLayout", 0, 0, 1080, 2400, false, false,
        listOf(chain(8, listOf(msg4, msg5))))
    // 真机事件 text=被点节点文本原样：引用节点=截断形态；独立气泡=全文形态
    val fullOrig = "Area komposisi tolong di tutup lagi krn pada retak kmrn mksh"
    val quoteForm = "\u200cArea komposisi tolong di tutup lagi krn retak kmrn mk"   // WhatsApp 80字截断+改尾（真机实证 L93 尾部 "mk"）
    val quoteClean = ScanLogic.clean(quoteForm)   // 候选文本经 pick clean 后不含 U+200C
    val bars = ScanLogic.collectQuoteBars(j21tree)
    check("J21 只认出真竖条(排除播放键/0宽)", bars.size == 1 && bars[0].w == 11 && bars[0].h == 220)
    // pick 级：引用文字必须出局，正文+caption 存活
    val cands21 = ScanLogic.pick(j21tree, 1080)
    val texts21 = cands21.map { it.text }
    check("J21 引用文字被剔除", texts21.none { it.contains("Sumarn") || it == quoteClean })
    check("J21 正文+caption 双候选存活", texts21.any { it.startsWith("Baik terimakasih") } && texts21.any { it == fullOrig })
    // hint 通道（真机无 rect 场景）：hint=引用截断文字（树里唯一精确匹配=引用节点）→ 归位正文
    val bodyCand = cands21.first { it.text.startsWith("Baik terimakasih") }
    val hitQuote = ScanLogic.quoteHintTarget(j21tree, bars, cands21, quoteForm)
    check("J21 hint=引用文字→归位正文", hitQuote != null && hitQuote.text == bodyCand.text)
    // hint=昵称 → 同条正文
    val hitNick = ScanLogic.quoteHintTarget(j21tree, bars, cands21, "~\u202fSri Sumarn\u2026")
    check("J21 hint=引用昵称→归位正文", hitNick != null && hitNick.text == bodyCand.text)
    // 歧义防线：hint=独立 caption 全文（非引用）→ 拒绝归位
    check("J21 hint=独立原文→拒绝归位", ScanLogic.quoteHintTarget(j21tree, bars, cands21, fullOrig) == null)
    // rect 通道：点击矩形=引用组（124,1865,937,2096）→ 归位正文（其子树唯一候选）
    val rectHit = ScanLogic.tapContainerTarget(j21tree, cands21, 124, 1865, 937, 2096)
    check("J21 rect=引用区→归位正文", rectHit != null && rectHit.text == bodyCand.text)
    // 防劫持：点击矩形含整页（多候选）→ null
    check("J21 多候选大矩形→放弃", ScanLogic.tapContainerTarget(j21tree, cands21, 0, 248, 1080, 2247) == null)
    // 真机第二例（L45 同构消息）验证泛化：另一条回复的条 11×93
    check("J21 短竖条 11×93 同规则", ScanLogic.isQuoteBar(view(135, 248, 146, 341)))

    // ================= J22（v0.2.8 问题⑤：输入框哨兵死锁）=================
    // 真机取证 9/21「A009修理完成了吗？」：首次翻译模型回显→KEEP 落缓存→此后输入框点「译」
    // 每次 cache hit 毫秒返回哨兵→永远拒写（消息行有「点行重译」出口，输入框没有）。
    // 修法=命中哨兵先 forget 再真重译。纯判断函数 sentinelBypass 在 Core（Engine 接线靠 e2e E22）。
    check("J22 KEEP 哨兵需绕过", Watch.sentinelBypass(Watch.KEEP_MARK))
    check("J22 PN 哨兵需绕过", Watch.sentinelBypass(Watch.PN_MARK))
    check("J22 正常译文不绕过", !Watch.sentinelBypass("好的，A009的修理完成了吗"))
    check("J22 缓存未命中(null)不绕过", !Watch.sentinelBypass(null))
    check("J22 空串不绕过", !Watch.sentinelBypass(""))
    // ③ 语义边界：空内容(模型暂时性故障)不应 KEEP 化 = Echo.isEcho(src, "") 恒真但重试判 isBlank
    check("J22 空回复仍判回显(闸门基线)", Echo.isEcho("A009修理完成了吗", ""))

    // ================= J23（v0.3.1 ②：词典按需注入）=================
    val dFork = Term("forklift", "叉车"); val dRoll = Term("roller", "轧轮")
    val dMix  = Term("mixing", "轧轮机"); val dCn = Term("sambungan", "接头")
    val dict23 = listOf(dFork, dRoll, dMix, dCn)
    // id→zh：比对 key（印尼文侧）。真机实证句 "Forklif no.18 sparepart" —— 词典 forklift，
    // 消息写 Forklif（借词截尾）→ 必须命中，否则修复等于没修。
    check("J23 forklift→Forklif 截尾命中",
        LocalPrompt.matchedDict("Forklif no.18 sparepart sudah datang", "zh-CN", dict23).contains(dFork))
    check("J23 fork 整词防误撞(Ibrahim 里的 ram 不算)",
        !LocalPrompt.containsTerm("pak ibrahim datang", "ram"))
    check("J23 mixing≠mixer 不互撞",
        !LocalPrompt.containsTerm("ganti mixer baru", "mixing"))
    check("J23 roller 整词命中", LocalPrompt.matchedDict("cuci roller besok", "zh-CN", dict23).contains(dRoll))
    check("J23 无关键词→空注入(纯 base 提示)",
        LocalPrompt.matchedDict("hari ini hujan deras sekali", "zh-CN", dict23).isEmpty())
    // zh→id：比对 val_（中文侧）
    check("J23 zh→id 中文术语子串命中",
        LocalPrompt.matchedDict("18号叉车备件已到", "id", dict23).contains(dFork))
    check("J23 zh→id 无关消息→零注入",
        LocalPrompt.matchedDict("今天开会吗", "id", dict23).isEmpty())
    // 命中术语后提示词只含该条
    val p23 = LocalPrompt.withDict("Forklif no.18 sparepart", "zh-CN", listOf(dFork))
    check("J23 注入块只含命中术语", p23.contains("forklift 翻译成 叉车") && !p23.contains("mixing"))
    check("J23 空词典=纯官方模板", !LocalPrompt.withDict("abc def ghi", "zh-CN", emptyList()).contains("参考下面的翻译"))

    // ================= J24（v0.3.1 ④：语言闸门 LangGate）=================
    // 真机 EKO 回显原样（部分回显=逃过 Echo 全等）：id→zh 输出仍全印尼文
    val srcEko = "Ya Mr.Chen coba nanti akan saya cari lagi konektor persegi yang large"
    val echoEko = "Ya Mr.Chen coba nanti akan saya cari lagi konektor persegi yang besar"
    check("J24 部分回显逃过 Echo(前提成立)", !Echo.isEcho(srcEko, echoEko))
    check("J24 部分回显被 LangGate 拦", !LangGate.ok(srcEko, echoEko, "zh-CN"))
    check("J24 正确中文译文放行", LangGate.ok(srcEko, "陈先生，我稍后再去找那个大型方形接头。", "zh-CN"))
    check("J24 中文夹术语(拉丁)仍放行", LangGate.ok(srcEko, "稍后再找那个 large 方形接头。", "zh-CN"))
    // zh→id 对称：输出还是中文 = 回显
    val srcZh = "再找找有没有旧的联轴器可以使用，今天要全部找到"
    check("J24 zh→id 中文输出被拦", !LangGate.ok(srcZh, "再找找有没有旧的联轴器可以使用", "id"))
    check("J24 zh→id 印尼输出放行", LangGate.ok(srcZh, "Cari lagi apakah ada kopling lama yang bisa dipakai", "id"))
    // 纯数字/超短豁免
    check("J24 超短消息豁免", LangGate.ok("OK", "OK", "zh-CN"))
    check("J24 空输出恒拦", !LangGate.ok(srcEko, "   ", "zh-CN"))

    println("== J25（v0.3.2：气泡内时间戳/日期分隔过滤补全）==")
    // 真机取证 2026-09-23：「早上5:49」「早上6:21」漏网 → mergeFragments 拼进正文 →
    // 含汉字使 detectTarget 判 id → 印尼文原文"回显"被合法放行（LAPORAN KERUSAKAN 不译）。
    // isNoise 只吃文本正则路径（id 在真机被隐藏），所以断言全部用空 id 走文本形态。
    check("J25 「早上5:49」=噪声", ScanLogic.isNoise(node("早上5:49", ""), "早上5:49"))
    check("J25 「早上 6:21」空格形态=噪声", ScanLogic.isNoise(node("早上 6:21", ""), "早上 6:21"))
    check("J25 「中午12:00」=噪声", ScanLogic.isNoise(node("中午12:00", ""), "中午12:00"))
    check("J25 「清晨5:30」=噪声", ScanLogic.isNoise(node("清晨5:30", ""), "清晨5:30"))
    check("J25 「傍晚6:45」=噪声", ScanLogic.isNoise(node("傍晚6:45", ""), "傍晚6:45"))
    check("J25 「夜里11:20」=噪声", ScanLogic.isNoise(node("夜里11:20", ""), "夜里11:20"))
    check("J25 「5:49 pagi」裸时段后缀=噪声", ScanLogic.isNoise(node("5:49 pagi", ""), "5:49 pagi"))
    check("J25 「pukul 6.21 pagi」小数点时间=噪声", ScanLogic.isNoise(node("pukul 6.21 pagi", ""), "pukul 6.21 pagi"))
    check("J25 「05.49」印尼数字角标=噪声", ScanLogic.isNoise(node("05.49", ""), "05.49"))
    check("J25 「2026年5月25日」完整年月日=噪声", ScanLogic.isNoise(node("2026年5月25日", ""), "2026年5月25日"))
    check("J25 「2026-9-23」横杠日期=噪声", ScanLogic.isNoise(node("2026-9-23", ""), "2026-9-23"))
    check("J25 「2026.9.23」点号日期=噪声", ScanLogic.isNoise(node("2026.9.23", ""), "2026.9.23"))
    check("J25 「昨天 早上6:21」复合=噪声", ScanLogic.isNoise(node("昨天 早上6:21", ""), "昨天 早上6:21"))
    // 不得误杀：真正含时间/数字的正文消息
    check("J25 正文「早上5:49我先去仓库」不滤", !ScanLogic.isNoise(node("早上5:49我先去仓库", "message_text_view"), "早上5:49我先去仓库"))
    check("J25 正文「Meeting jam 10 pagi di ruang IT」不滤", !ScanLogic.isNoise(node("Meeting jam 10 pagi di ruang IT", "message_text_view"), "Meeting jam 10 pagi di ruang IT"))
    check("J25 正文「Pisau mesin cacah A009 masih aus」不滤", !ScanLogic.isNoise(node("Pisau mesin cacah A009 masih aus", "message_text_view"), "Pisau mesin cacah A009 masih aus"))
    check("J25 正文「截止2026年9月30日完成」不滤", !ScanLogic.isNoise(node("截止2026年9月30日完成", "message_text_view"), "截止2026年9月30日完成"))
    check("J25 正文「温度5.49度偏低」不滤", !ScanLogic.isNoise(node("温度5.49度偏低", "message_text_view"), "温度5.49度偏低"))

    println("== J26（v0.3.3①：方向多数表决——发送者名混入不翻转方向）==")
    // 真机 2026-09-23「凭空加词」案：群聊对方气泡 merge 进 4 字人名后，旧规则（见汉字即判中→印）
    // 整体翻转 → 模型把印尼文正文润色回显+顺手译人名（demo 纯文本粘贴无此问题）。
    val dirty1 = "陈副厂长\nMau lihat perbandingan A4-nya, letakkan di atas meja saya"
    check("J26 真机污染行1→判印尼文正文(翻中文)", Lang.detectTarget(dirty1) == "zh-CN")
    val dirty2 = "陈副厂长\n已转发\nmasalah nya parker banbury oli masih bocor tolong dicek pak"
    check("J26 真机污染行2→判印尼文正文(翻中文)", Lang.detectTarget(dirty2) == "zh-CN")
    // 原语义不回归：纯印尼→中文、纯中文→印尼、中文夹英文术语（拉丁<汉字）→印尼
    check("J26 纯印尼文→翻中文", Lang.detectTarget("Selamat pagi Pak, laporan shift malam sudah saya kirim") == "zh-CN")
    check("J26 纯中文→翻印尼", Lang.detectTarget("你好，明天上午开会") == "id")
    check("J26 中文夹英文术语不误判", Lang.detectTarget("请检查 mixing machine 的联轴器温度") == "id")
    check("J26 中文消息夹长URL（拉丁>汉字）罕见形态", Lang.detectTarget("https://example.com/report/a006 请看") == "zh-CN")
    check("J26 空文本→翻中文（同旧行为）", Lang.detectTarget("") == "zh-CN")

    println("== J27（v0.3.3②：已转发角标过滤 + 发送者名行剔除）==")
    check("J27 「已转发」=噪声", ScanLogic.isNoise(node("已转发", ""), "已转发"))
    check("J27 「Diteruskan」=噪声", ScanLogic.isNoise(node("Diteruskan", ""), "Diteruskan"))
    check("J27 「Forwarded」=噪声", ScanLogic.isNoise(node("Forwarded", ""), "Forwarded"))
    check("J27 「转发消息」=噪声", ScanLogic.isNoise(node("转发消息", ""), "转发消息"))
    check("J27 归因=forwarded", ScanLogic.dropReason(node("已转发", "message_text_view"), "已转发") == "forwarded")
    check("J27 正文含「转发」字样不误伤", !ScanLogic.isNoise(node("tolong转发给客户", "message_text_view"), "tolong转发给客户"))
    // senderNameLine：真机两例 + 保守边界
    check("J27 名行剔除(真机例1)", ScanLogic.senderNameLine(dirty1) == "Mau lihat perbandingan A4-nya, letakkan di atas meja saya")
    check("J27 名行剔除(真机例2·已转发已由前置过滤器出局)",
        ScanLogic.senderNameLine("陈副厂长\nmasalahnya parker banbury oli masih bocor") == "masalahnya parker banbury oli masih bocor")
    check("J27 纯印尼文不削", ScanLogic.senderNameLine("Pompa blm hidup pak") == null)
    check("J27 中文正文多行不削（首行后拉丁<12）", ScanLogic.senderNameLine("今天开会\n明天上午复检 ok ok ok") == null)
    check("J27 名行含数字不削（保守：宁留污染不误杀）", ScanLogic.senderNameLine("陈副厂长2\nmasalahnya oli masih bocor parah sekali") == null)
    check("J27 首行非纯中文不削（正文首行英文抬头）", ScanLogic.senderNameLine("LAPORAN KERUSAKAN\nMesin A06 bagian root blower suara kasar hari ini") == null)
    check("J27 单行不削", ScanLogic.senderNameLine("陈副厂长") == null)
    check("J27 9字名超限不削（保守）", ScanLogic.senderNameLine("陈副厂长张三李四王\nmasalahnya oli masih bocor") == null)
    // mergeFragments 端到端：名+正文两碎片 → 候选=纯正文
    val mgA = node("陈副厂长", "", 60, 300, 300, 340)
    val mgB = node("masalahnya oli masih bocor parah sekali pak", "", 60, 342, 700, 390)
    val mgC = ScanLogic.mergeFragments(listOf(
        Triple(mgA, "陈副厂长", mgA), Triple(mgB, "masalahnya oli masih bocor parah sekali pak", mgB)))
    check("J27 merge 候选=净正文", mgC.size == 1 && mgC[0].text == "masalahnya oli masih bocor parah sekali pak",
        "got=" + mgC.map { it.text })

    println("== J28（v0.3.4 few-shot；v0.3.7 A2 官方中文指令行 + A3 示例 7 条）==")
    // id→zh 无词典：示例在前、官方中文指令紧邻原文（v0.3.7：README 原文，替换自编英文行）
    val p28a = LocalPrompt.withDict("Bak limbah A002 aman", "zh-CN", emptyList())
    check("J28 id→zh 无词典=示例前置于指令", p28a.startsWith("Contoh:") &&
        p28a.indexOf("将以下文本翻译为中文") > p28a.indexOf("Contoh") &&
        p28a.endsWith("Bak limbah A002 aman"), "got=" + p28a.take(80))
    check("J28 id→zh 指令文本=官方中文原文", p28a.contains(
        "将以下文本翻译为中文，注意只需要输出翻译后的结果，不要额外解释：\n\nBak limbah A002 aman"))
    check("J28 v0.3.7 示例对=7条", LocalPrompt.FEWSHOT_ID2ZH.split("Contoh:").size - 1 == 7)
    // id→zh 有词典：示例 → 参考 → 指令 → 原文
    val t28 = listOf(Term("mixing", "轧轮机"))
    val p28b = LocalPrompt.withDict("Strainer, mixing A002 mengalir", "zh-CN", t28)
    check("J28 id→zh 词典布局", p28b.indexOf("Contoh") < p28b.indexOf("参考下面的翻译") &&
        p28b.indexOf("参考下面的翻译") < p28b.indexOf("将以下文本翻译为中文") &&
        p28b.contains("mixing 翻译成 轧轮机") && p28b.endsWith("Strainer, mixing A002 mengalir"),
        "got=" + p28b.take(120))
    // zh→id：完全不加示例（保持 v0.3.3 行为）
    val p28c = LocalPrompt.withDict("你好，明天上午开会", "id", emptyList())
    check("J28 zh→id 无示例", p28c == "将以下文本翻译为印度尼西亚语，注意只需要输出翻译后的结果，不要额外解释：\n\n你好，明天上午开会")
    val p28d = LocalPrompt.withDict("检查轧轮机温度", "id", t28)
    check("J28 zh→id 词典布局不变", !p28d.contains("Contoh") &&
        p28d == "参考下面的翻译：\n轧轮机 翻译成 mixing\n\n将以下文本翻译为印度尼西亚语，注意只需要输出翻译后的结果，不要额外解释：\n\n检查轧轮机温度")
    // parrotExamples：真机 PC 实测复读形态全部命中
    check("J28 复读·Contoh原样", LocalPrompt.parrotExamples(
        "Contoh: \"Oli mesin masih bersih\" -> \"机油还是干净的\"\nContoh: \"Bak limbah penuh\" -> \"废料池满了\"\n\n正文"))
    check("J28 复读·中文引号译文", LocalPrompt.parrotExamples(
        "“机油仍然干净” -> “机油仍然处于清洁状态”\n“A003辊的温度正常” -> “A003辊温度正常”\n\nA001型卷绕机的生产状况仍然良好。"))
    check("J28 复读·双中文短语", LocalPrompt.parrotExamples("示例：废料池满了 -> 废料池里装满了废料（夜班报告已经交上去了）"))
    check("J28 正常译文不误伤", !LocalPrompt.parrotExamples("A002号废料池是安全的，夜班情况正常。"))
    check("J28 单短语+无箭头不误伤", !LocalPrompt.parrotExamples("卷取机A001生产状况良好"))
    check("J28 回显(原文照抄)不误伤判据", !LocalPrompt.parrotExamples("Bak limbah A002 aman"))
    // v0.3.5 收紧：单行「箭头+引号」复读变体必须拦截（真机 9/23 截图实证漏网形态）
    check("J28v35 单行例如+箭头+引号", LocalPrompt.parrotExamples(
        "例如：\"下周维修班组将到场\" -> \"下周维修班组会到场\""))
    check("J28v35 单行英文引号箭头", LocalPrompt.parrotExamples(
        "\"Oli masih bersih\" -> \"机油还是干净的\"，请参考上文。"))
    check("J28v35 译文夹行复读也被拦", LocalPrompt.parrotExamples(
        "A002废料池安全。\nContoh: \"x\" -> \"y\""))
    check("J28v35 合法箭头无引号不误伤", !LocalPrompt.parrotExamples(
        "夜班→白班交接正常"))
    check("J28v35 引号无箭头不误伤", !LocalPrompt.parrotExamples(
        "他说\"下周到场\"，A003正常"))

    println("== J29（v0.3.5 ③：全文映射 FullText）==")
    // 真机 9/22 实证形态：节点文本恰 80 字被 WhatsApp 截断，剪贴板全文更长
    val full29 = "To : Semua Mesin Calender\nForm tambah lipat yang baru, tolong diperhatikan cara pengisiannya sesuai format contoh dari kantor pusat"
    val trunc29 = "To : Semua Mesin Calender\nForm tambah lipat yang baru, tolong diperhatikan cara"   // 80 字
    check("J29 截断形态≈80字基准", trunc29.length in 75..85, "got=" + trunc29.length)
    FullText.record(trunc29, full29)
    check("J29 resolve 截断→全文", FullText.resolve(trunc29) == full29,
        "got=" + FullText.resolve(trunc29)?.take(40))
    check("J29 resolve 更短前缀也命中", FullText.resolve(trunc29.take(60)) == full29)
    check("J29 resolve 全文幂等", FullText.resolve(full29) == full29,
        "got=" + FullText.resolve(full29)?.take(20))
    check("J29 resolve 无关文本不误伤", FullText.resolve("Selamat pagi Pak hari ini baik baik saja") == null)
    check("J29 resolve 短于触发长度不查表", FullText.resolve(trunc29.take(40)) == null)
    // 持久化往返：serialize→load 恢复等价 resolve
    val ser29 = FullText.serialize()
    FullText.clear()
    check("J29 clear 后不命中", FullText.resolve(trunc29) == null)
    FullText.load(ser29)
    check("J29 load 往返等价", FullText.resolve(trunc29) == full29, "got=" + FullText.resolve(trunc29)?.take(30))
    // 换行符转义往返（多行消息）。注意：resolve 以归一化长度(去空白)对表 MIN_LEN=50，
    // 用例文本必须保证去空白后截断形态仍 ≥50 归一化字符。
    val multi29 = "第一行内容测试换行持久化往返第二行\n第三行 with spaces and stuff here padding tail more words"
    FullText.record(multi29.take(60), multi29)
    check("J29 多行原文 resolve", FullText.resolve(multi29.take(60)) == multi29)
    val serM = FullText.serialize(); FullText.clear(); FullText.load(serM)
    check("J29 多行 load 往返", FullText.resolve(multi29.take(60)) == multi29)
    // record 门槛：全文不比截断长 / 短全文 不登记
    FullText.record("abc def ghi jkl mno pqr stu vwx yz0 1234567890 ab", "abc def ghi jkl mno pqr stu vwx yz0 1234567890 ab")
    FullText.record("x".repeat(60), "y".repeat(10))
    check("J29 无增益/短全文不登记", FullText.resolve("x".repeat(60)) == null)
    FullText.clear()

    println("== J30（v0.3.6：bodyOf 正文剥离 + FullText 别名键）==")
    // 真机 9/24 日志实证的合并点击文本形态：昵称+电话 / 「已转发」/ 正文 / 时间戳
    val merged30 = "~ Hery purnomo\n+62 895-1234-5678\n 已转发 \nTerimakasih pak, saya sudah cek ke lapangan barusan\n上午10:35"
    val body30 = ScanLogic.bodyOf(merged30)
    check("J30 合并文本→只剩正文", body30 == "Terimakasih pak, saya sudah cek ke lapangan barusan",
        "got=" + body30)
    check("J30 正文<76 不误触发", body30.length < 76 && merged30.length >= 76,
        "body=" + body30.length + " merged=" + merged30.length)
    // 「2个视频」媒体计数行（真机日志 218-220 行实证）
    check("J30 媒体计数行剥离", ScanLogic.bodyOf("2个视频") == "" && ScanLogic.bodyOf("2 videos") == "",
        "got=[" + ScanLogic.bodyOf("2个视频") + "/" + ScanLogic.bodyOf("2 videos") + "]")
    // 中文发送者名首行剔除（E25 形态：WhatsApp 复制不含名行）
    val group30 = "陈副厂长\n已转发\nMau lihat perbandingan A4-nya, letakkan di atas meja saya sekarang juga ya pak"
    check("J30 中文名行剔除", ScanLogic.bodyOf(group30).startsWith("Mau lihat"),
        "got=" + ScanLogic.bodyOf(group30).take(20))
    // 正常长正文不被误剥（触发判定必须放行）
    val longBody30 = "To : Semua Mesin Calender\nForm tambah lipat yang baru tolong diperhatikan cara pengisiannya sesuai format contoh"
    check("J30 多行正文保留", ScanLogic.bodyOf(longBody30) == longBody30.trim())
    check("J30 长正文过触发线", ScanLogic.bodyOf(longBody30).length >= 76)
    // FullText 别名键：真机合并形态 resolve（v0.3.5 前缀键做不到）
    val fullAlias30 = "Terimakasih pak, saya sudah cek ke lapangan barusan dan akan kami jadwalkan ulang untuk maintenance minggu depan ya"
    FullText.record(merged30, fullAlias30)
    check("J30 合并形态别名命中", FullText.resolve(merged30) == fullAlias30,
        "got=" + FullText.resolve(merged30)?.take(30))
    check("J30 别名 load 往返", run {
        val ser = FullText.serialize(); FullText.clear(); FullText.load(ser)
        FullText.resolve(merged30) == fullAlias30
    })
    FullText.clear()

    println("== J31（v0.3.7：InstructionEcho 回显闸门 + isMetaLine 抽行清洗）==")
    // F6：电池实锤事故形态（MT1.5/STYLE-LIT 9/16 复读）——整条输出=一句提示词
    check("J31 指令回显拦截", InstructionEcho.hit(
        "注意，翻译内容严格遵循要求。翻译成中文，注意只需要输出翻译后的结果，不要额外解释：") != null)
    check("J31 官方指令行回显拦截", InstructionEcho.hit(
        "将以下文本翻译为中文，注意只需要输出翻译后的结果，不要额外解释：") != null)
    check("J31 参考词典块回显拦截", InstructionEcho.hit("参考下面的翻译：\\noli 翻译成 润滑油") != null ||
        InstructionEcho.hit("参考下面的翻译：\noli 翻译成 润滑油") != null)
    check("J31 正常译文不误伤", InstructionEcho.hit("A003辊温正常，润滑油已更换") == null)
    check("J31 「严格符合」措辞正常句不误伤", InstructionEcho.hit(
        "报告格式必须严格符合总部要求，请各班组注意") == null)
    check("J31 超120字长译文不判（复读事故形态=短指令整句）", InstructionEcho.hit(
        "今天".repeat(70) + "将以下文本翻译") == null)
    // F3：isMetaLine 行级判据（整行恰合形态才删；正文含关键词不误删）
    check("J31 isMetaLine 时间/角标/电话", ScanLogic.isMetaLine("上午10:35") &&
        ScanLogic.isMetaLine("2个视频") && ScanLogic.isMetaLine("+62 895-1234-5678") &&
        ScanLogic.isMetaLine("已转发") && ScanLogic.isMetaLine("~ Hery purnomo"))
    check("J31 isMetaLine 正常正文放行", !ScanLogic.isMetaLine("Buang angin D007") &&
        !ScanLogic.isMetaLine("oli masih bersih 2 videos per hari") &&
        !ScanLogic.isMetaLine("Mesin A003 sudah dimatikan jam 5"))
    check("J31 bodyLines 多行剔元数据保正文", ScanLogic.bodyLines(
        "Suparman\nBuang angin D007\n上午10:35") == listOf("Suparman", "Buang angin D007"))
    // A5：布局键——示例块 → 词典块 → 官方中文指令 → 原文（复读率=0 的实锤顺序）
    run {
        val p = LocalPrompt.withDict("mesin oli bocor", "zh-CN", listOf(Term("oli", "润滑油")))
        check("J31 A5 id→zh 全布局顺序",
            p.indexOf("Contoh") < p.indexOf("参考下面的翻译") &&
            p.indexOf("参考下面的翻译") < p.indexOf("将以下文本翻译为中文") &&
            p.endsWith("\n\nmesin oli bocor"), "got=" + p.take(60))
    }

    println("ALL CORE TESTS PASSED (JVM core)")
}
