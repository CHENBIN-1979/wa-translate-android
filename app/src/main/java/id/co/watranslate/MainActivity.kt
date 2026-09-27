package id.co.watranslate

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import java.io.BufferedReader

/*
 * MainActivity.kt — 设置页（纯平台 API，无 AndroidX）。
 * 交互规则与扩展 v1.1.21 一致：切换=纯查看不回写表单；新增=复制已保存值；脏表单警告。
 * v0.4.1：单页长滚动 → 四标签页（常规/模型/词典/云端）。用户定案的归位：
 *   常规 = 无障碍入口 + 导出 + 自动/极速/字号 + 清空缓存 + 翻译引擎三选 + 模型尺寸 + 词典全量注入
 *   模型 = 下载源 + 线程 + 三个模型下载块
 *   词典 = 词典编辑/保存/恢复
 *   云端 = 模型配置（多套 profile + 测试连接）
 * 文案：云端/本地离线删品牌名（MiniMax/scnet/Hy-MT2），Q4_K_M 删「1080MB」。
 * 顶部服务状态与底部提示栏常驻标签外——任何页操作都能看到反馈。
 */
class MainActivity : Activity() {

    private val sp by lazy { getSharedPreferences("wtrans", Context.MODE_PRIVATE) }
    private val profilesJson get() = sp.getString("profiles", "") ?: ""
    private lateinit var profileSpin: Spinner
    private var profileIds: List<String> = emptyList()
    private var activeIdx = 0
    private lateinit var edName: EditText; private lateinit var edBase: EditText
    private lateinit var edKey: EditText; private lateinit var edModel: EditText
    private lateinit var edTemp: EditText; private lateinit var edDict: EditText
    private lateinit var swEnabled: Switch; private lateinit var swFast: Switch
    private var dictShrinkArmed = false   // v0.2.1：词典条数变少时的二次确认臂
    private lateinit var fontSeek: SeekBar; private lateinit var fontVal: TextView
    private fun fontSp(): Int = 12 + fontSeek.progress
    private lateinit var tvStatus: TextView; private lateinit var tvService: TextView
    private var lastTestedId: String? = null
    private var btnTest: Button? = null   // v0.1.5：测试期间禁用防连点

    // v0.3.0 M2：模型行 UI 引用表
    private class ModelRowUi(val tv: TextView, val pb: ProgressBar, val bDown: Button, val bCancel: Button)
    private val modelViews = HashMap<String, ModelRowUi>()

    /** v0.4.1：简易流式换行容器（无 AndroidX Flexbox）——一行放不下自动降入下一行。
     *  用于「模型尺寸」三选：窄屏不再把第三项挤成竖排。 */
    private class FlowLayout(ctx: Context, private val hGap: Int) : ViewGroup(ctx) {
        private class Row { var w = 0; var h = 0; var from = 0; var to = -1 }
        private val rows = ArrayList<Row>()
        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            val maxW = MeasureSpec.getSize(widthSpec).coerceAtLeast(1)
            rows.clear()
            var cur = Row()
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                measureChild(c, widthSpec, heightSpec)
                if (cur.to == -1 && cur.w > 0 && cur.w + hGap + c.measuredWidth > maxW) {
                    cur.to = i - 1; rows.add(cur); cur = Row(); cur.from = i
                }
                if (cur.to == -1) {
                    cur.w += if (cur.w > 0) hGap + c.measuredWidth else c.measuredWidth
                    cur.h = maxOf(cur.h, c.measuredHeight)
                }
            }
            if (cur.to == -1 && childCount > 0) { cur.to = childCount - 1; rows.add(cur) }
            val totalH = rows.sumOf { it.h }
            val maxRowW = rows.maxOfOrNull { it.w } ?: 0
            setMeasuredDimension(resolveSize(maxRowW, widthSpec), resolveSize(totalH, heightSpec))
        }
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            // ⚠ 子 view.layout() 要父相对坐标，不是绝对屏幕坐标（首版写错致内容画到屏外）
            var y = 0
            for (row in rows) {
                var x = 0
                for (i in row.from..row.to) {
                    val c = getChildAt(i)
                    c.layout(x, y, x + c.measuredWidth, y + c.measuredHeight)
                    x += c.measuredWidth + hGap
                }
                y += row.h
            }
        }
    }

    /** 按磁盘真实状态刷某模型行（note 非空时附加在文案后，如「已暂停」「✘ 错误」）。 */
    private fun refreshModelRow(id: String, note: String?) {
        val mv = modelViews[id] ?: return
        val s = ModelManager.spec(id) ?: return
        val ready = ModelManager.isReady(this, s)
        val part = ModelManager.partBytes(this, s)
        val suffix = if (note.isNullOrEmpty()) "" else "\n$note"
        when {
            ready -> { mv.tv.text = "${s.title} ✔ 已就绪" + suffix; mv.pb.progress = 1000; mv.bDown.isEnabled = false; mv.bCancel.isEnabled = false }
            part > 0 -> { mv.tv.text = "${s.title} 已下 ${"%.1f".format(part / 1048576.0)}MB / ${"%.0f".format(s.sizeBytes / 1048576.0)}MB（点下载续传）" + suffix
                mv.pb.progress = (part * 1000 / s.sizeBytes).toInt(); mv.bDown.isEnabled = !ModelManager.busy(); mv.bCancel.isEnabled = false }
            else -> { mv.tv.text = "${s.title} ✖ 未下载" + suffix; mv.pb.progress = 0; mv.bDown.isEnabled = !ModelManager.busy(); mv.bCancel.isEnabled = false }
        }
    }

    /** 写公共 Download/<name>（API29 MediaStore 免权限；同名先删再插=覆盖）；返回可见路径描述 */
    private fun saveToDownloads(name: String, content: String): String {
            val resolver = contentResolver
            val base = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
            // 同名先删 = 覆盖（首次没有同名会返回 0，忽略）
            runCatching { resolver.delete(base, android.provider.MediaStore.MediaColumns.DISPLAY_NAME + "=?", arrayOf(name)) }
            val vals = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Download/")
            }
            val uri = resolver.insert(base, vals) ?: error("MediaStore insert 失败")
            resolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) } ?: error("流打开失败")
            return "Download/$name（文件管理器或「下载管理」直见）"
        }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dm = resources.displayMetrics
        fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), dm).toInt()

        // ---- v0.4.1：根布局 = 常驻服务状态 + 标签栏 + 页容器(ScrollView) + 常驻提示栏 ----
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#f1f5f9"))
        }

        fun label(s: String) = TextView(this).apply {
            text = s; setTextColor(Color.parseColor("#475569")); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(8), 0, dp(2))
        }
        fun edit(input: Int = InputType.TYPE_CLASS_TEXT): EditText = EditText(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setBackgroundResource(android.R.drawable.edit_text)
            setTextColor(Color.parseColor("#0f172a"))
            if (input != InputType.TYPE_CLASS_TEXT) inputType = input
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        fun btn(text: String, bg: String, onClick: () -> Unit): Button {
            val b = Button(this)
            b.text = text; b.isAllCaps = false
            // v0.1.5（用户反馈"点着没有按压感"）：纯色 setBackground 会吞掉系统按压态
            // → StateListDrawable：按下=同色加深；v0.1.9 用户再反馈不够深 → 25%提到45%
            val normal = Color.parseColor(bg)
            fun darker(c: Int, f: Float) = Color.rgb(
                (Color.red(c) * f).toInt(), (Color.green(c) * f).toInt(), (Color.blue(c) * f).toInt())
            b.background = android.content.res.ColorStateList.valueOf(normal).let { _ ->
                android.graphics.drawable.StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_pressed),
                        android.graphics.drawable.ColorDrawable(darker(normal, 0.55f)))
                    addState(intArrayOf(android.R.attr.state_enabled),
                        android.graphics.drawable.ColorDrawable(normal))
                    addState(intArrayOf(), android.graphics.drawable.ColorDrawable(darker(normal, 0.55f)))
                }
            }
            b.setTextColor(Color.WHITE)
            b.setOnClickListener { onClick() }
            return b
        }
        fun topMargin(v: android.view.View, m: Int) { (v.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp -> lp.topMargin = m } }

        // ---- 顶部：服务状态（常驻，不随页切换） ----
        tvService = TextView(this).apply {
            text = "翻译服务：检测中…"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(Color.parseColor("#334155"))
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setBackgroundColor(Color.parseColor("#e2e8f0"))
        }
        root.addView(tvService)

        // ---- 四个页容器（先建，后按归位填控件） ----
        fun newPage() = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(2), dp(14), dp(12))
        }
        val pageGeneral = newPage(); val pageModel = newPage()
        val pageDict = newPage(); val pageCloud = newPage()
        val pages = listOf(pageGeneral, pageModel, pageDict, pageCloud)
        val pageNames = listOf("常规", "模型", "词典", "云端")

        val contentCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(contentCol)
        }

        // ---- 标签栏 ----
        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), dp(6), dp(10), dp(2))
        }
        val tabBtns = ArrayList<Button>()
        fun styleTab(b: Button, sel: Boolean) {
            b.setBackgroundColor(if (sel) Color.parseColor("#25d366") else Color.parseColor("#e2e8f0"))
            b.setTextColor(if (sel) Color.WHITE else Color.parseColor("#334155"))
        }
        fun showPage(idx: Int) {
            contentCol.removeAllViews()
            contentCol.addView(pages[idx])
            tabBtns.forEachIndexed { i, b -> styleTab(b, i == idx) }
        }
        pageNames.forEachIndexed { i, name ->
            val b = Button(this).apply {
                text = name; isAllCaps = false
                setOnClickListener { showPage(i) }
            }
            tabBtns.add(b)
            tabBar.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = if (i > 0) dp(4) else 0 })
        }
        root.addView(tabBar)
        root.addView(scroll)
        setContentView(root)

        // =====================================================================
        // 页一：常规
        // =====================================================================
        pageGeneral.addView(btn("开启无障碍权限（必需）", "#25d366") {
            runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                .onFailure { status("打不开系统设置：${it.message}", true) }
        }.also { topMargin(it, dp(8)) })
        pageGeneral.addView(btn("导出诊断（WhatsApp 节点树）", "#0ea5e9") {
            val snap = WaTranslateService.lastSnapshot
            if (snap.isEmpty()) { status("还没有快照：先去 WhatsApp 打开出问题的聊天页（停留 2 秒让服务扫描），再回来点这个", true); return@btn }
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("wa-translate-diag", snap))
            // 同时落文件到公共 Download/（MediaStore 写，零权限；v0.1.2：旧路径藏太深）
            val saved = runCatching { saveToDownloads("wa-translate-diag.txt", snap) }
            status(saved.getOrNull()?.let { "✔ 已复制节点树（" + snap.lines().size + " 行）并存到 $it" }
                ?: if (saved.isSuccess) "✔ 已复制节点树（" + snap.lines().size + " 行）" else "✔ 已复制到剪贴板（存 Download 失败：${saved.exceptionOrNull()?.message}）", saved.isFailure)
        }.also { topMargin(it, dp(4)) })
        // v0.1.6：运行日志导出（环形缓冲落 Download/，专治「慢/超时/失败」定位）
        pageGeneral.addView(btn("导出运行日志（性能/网络）", "#8b5cf6") {
            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date())
            val name = "wtranslate-log-$stamp.txt"
            val dump = LogBuf.dump(this@MainActivity, WaTranslateService.VERSION)
            val saved = runCatching { saveToDownloads(name, dump) }
            status(saved.getOrNull()?.let { "✔ 日志已保存（${dump.lines().size} 行）到 $it，发这个文件即可" }
                ?: "存 Download 失败：${saved.exceptionOrNull()?.message}", saved.isFailure)
        }.also { topMargin(it, dp(4)) })

        swEnabled = Switch(this).apply { text = "自动翻译（关=点哪条译哪条，一次只显示一条）"; isChecked = sp.getBoolean("auto", false)
            setOnCheckedChangeListener { _, v ->
                sp.edit().putBoolean("auto", v).apply()
                WaTranslateService.running?.autoTranslate = v
                status(if (v) "已切自动：所有对方消息自动出译文" else "已切点击翻译（默认）：点对方气泡才译，不点不译", false)
            } }
        swFast = Switch(this).apply { text = "极速模式（关思考链，快约一倍）"; isChecked = sp.getBoolean("fast", true)
            setOnCheckedChangeListener { _, v -> sp.edit().putBoolean("fast", v).apply() } }
        pageGeneral.addView(swEnabled); pageGeneral.addView(swFast)

        // ---- v0.1.4 字号滑杆：12–24sp，默认 16，拖动实时套到屏上译文行 ----
        pageGeneral.addView(label("翻译字号"))
        val fontRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(2), 0, 0) }
        fontSeek = SeekBar(this).apply {
            max = 24 - 12
            progress = (sp.getFloat("font_sp", 16f) - 12f).toInt().coerceIn(0, max)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        fontVal = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); setTextColor(Color.parseColor("#0f172a"))
            setPadding(dp(8), dp(2), 0, 0); text = fontSp().toString() + "sp"
        }
        fontSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                sp.edit().putFloat("font_sp", fontSp().toFloat()).apply()
                fontVal.text = fontSp().toString() + "sp"
                WaTranslateService.running?.apply { bodySizeSp = fontSp().toFloat(); applyFontSizes() }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) { status("✔ 字号已设为 ${fontSp()}sp（屏上译文行已即时更新）", false) }
        })
        fontRow.addView(fontSeek); fontRow.addView(fontVal)
        pageGeneral.addView(fontRow)

        // ---- 缓存（v0.4.1 用户定案：留在常规页） ----
        pageGeneral.addView(label("翻译缓存：本地保存，重复消息不重复计费"))
        val cacheRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, 0) }
        cacheRow.addView(btn("清空缓存", "#dc2626") {
            runCatching { WaTranslateService.running?.clearAllCaches() }
            status("✔ 缓存已清空", false)
        })
        pageGeneral.addView(cacheRow)

        // ---- v0.3.0 M3：翻译引擎模式三选（v0.4.1 用户定案：入常规页；删品牌名 MiniMax/scnet/Hy-MT2）----
        pageGeneral.addView(label("翻译引擎"))
        val rgMode = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        fun modeRb(id: Int, txt: String): RadioButton = RadioButton(this).apply {
            text = txt; this.id = id; setTextColor(Color.parseColor("#334155"));
            setPadding(0, dp(2), 0, dp(2))
        }
        val rbCloud = modeRb(1, "云端（走 API 计费）")
        val rbLocal = modeRb(2, "本地离线（免流量免计费；需先下载模型）")
        val rbHybrid = modeRb(3, "本地优先 · 云端兜底（本地翻不动时自动走云）")
        rgMode.addView(rbCloud); rgMode.addView(rbLocal); rgMode.addView(rbHybrid)
        when (sp.getString("engineMode", "cloud")) {
            "local" -> rbLocal.isChecked = true; "hybrid" -> rbHybrid.isChecked = true; else -> rbCloud.isChecked = true
        }
        rgMode.setOnCheckedChangeListener { _, checkedId ->
            val m = when (checkedId) { rbLocal.id -> "local"; rbHybrid.id -> "hybrid"; else -> "cloud" }
            sp.edit().putString("engineMode", m).apply()
            if (m != "cloud") LocalEngine.unload()  // 切模式后强制按需重载（模型/模式可能都变了）
            status("✔ 引擎模式：${when (m) { "local" -> "本地离线"; "hybrid" -> "本地优先·云端兜底"; else -> "云端" }}", false)
        }
        pageGeneral.addView(rgMode)

        // ---- 模型尺寸（v0.4.1：FlowLayout 自动换行 + 手写互斥——RadioGroup 不支持折行；
        //      删「1080MB」；组名「本地模型尺寸」上提为 label，三项不再带「模型：」前缀）----
        pageGeneral.addView(label("本地模型尺寸"))
        val flowSize = FlowLayout(this, dp(4))
        fun sizeRb(txt: String): RadioButton = RadioButton(this).apply {
            text = txt; setTextColor(Color.parseColor("#334155")); setPadding(0, dp(2), 0, dp(2))
        }
        val rb2b = sizeRb("2bit（推荐）"); val rb125 = sizeRb("1.25bit（省内存）"); val rbq4 = sizeRb("Q4_K_M（最快）")
        flowSize.addView(rb2b); flowSize.addView(rb125); flowSize.addView(rbq4)
        val sizeRadios = listOf(rb2b to "hy2b", rb125 to "hy125", rbq4 to "hyq4")
        val nowModel = sp.getString("localModel", "hy2b")
        sizeRadios.forEach { (rb, mid) ->
            rb.isChecked = mid == nowModel
            rb.setOnClickListener {
                if (sp.getString("localModel", "hy2b") == mid) return@setOnClickListener   // 重复点当前值=无操作
                sizeRadios.forEach { it.first.isChecked = it.second == mid }
                sp.edit().putString("localModel", mid).apply()
                LocalEngine.unload()  // 换模型 → 丢弃已加载的旧权重，下次翻译按需加载新模型
                status("✔ 本地模型切换（下次翻译生效）", false)
            }
        }
        pageGeneral.addView(flowSize)

        // ---- v0.3.7 A1：词典注入模式开关（v0.4.1 用户定案：入常规页）。
        // 全量=26 条整表进提示词（四轮电池实锤 ≥ 提取且复读 0；native prompt 前缀
        // KV 缓存兜底首条成本）；提取=只拼本条命中的术语（提取器 16/16 无漏，保险丝）。
        // 两模式产出的译文不同且共享 src 键 → 切换即清缓存，旧译文不串台。
        val swDictFull = Switch(this).apply {
            text = "词典全量注入（开=整表进提示词，实测更稳；关=只注入本条命中的词，切换后旧译文自动重译）"
            isChecked = sp.getBoolean("dictFull", true)
            setTextColor(Color.parseColor("#334155"))
            setPadding(0, dp(2), 0, dp(2))
        }
        swDictFull.setOnCheckedChangeListener { _, checked ->
            sp.edit().putBoolean("dictFull", checked).apply()
            runCatching { WaTranslateService.running?.clearAllCaches() }   // 两模式译文不同，整体作废防串台
            status("✔ 词典注入：${if (checked) "全量（切换后第一条稍慢，之后走缓存）" else "按需提取"}，旧译文已清", false)
        }
        pageGeneral.addView(swDictFull)

        // =====================================================================
        // 页二：模型（下载源 + 线程 + 下载块）
        // =====================================================================
        pageModel.addView(label("离线翻译模型（下载到本应用私有目录，卸载即净）"))
        val srcRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(2), 0, dp(4)) }
        val rgSrc = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val rbHf = RadioButton(this).apply { text = "HuggingFace（海外快）"; id = 1; setTextColor(Color.parseColor("#334155")) }
        val rbMs = RadioButton(this).apply { text = "ModelScope（国内备用）"; id = 2; setTextColor(Color.parseColor("#334155")) }
        rgSrc.addView(rbHf); rgSrc.addView(rbMs)
        (if (ModelManager.sourceOf(this) == ModelManager.Source.MODELSCOPE) rbMs else rbHf).isChecked = true
        rgSrc.setOnCheckedChangeListener { _, checkedId ->
            ModelManager.setSource(this@MainActivity, if (checkedId == rbMs.id) ModelManager.Source.MODELSCOPE else ModelManager.Source.HUGGINGFACE)
            status("✔ 下载源已切换：${if (checkedId == rbMs.id) "ModelScope" else "HuggingFace"}（下次下载生效，续传保持原源进度）", false)
        }
        srcRow.addView(rgSrc)
        pageModel.addView(srcRow)

        // v0.3.1 ③：推理线程数（big.LITTLE 机器上核多≠快；0=自动。改动下次加载生效。v0.4.1 入模型页）
        pageModel.addView(label("推理线程（下次加载生效）"))
        val rgThread = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        fun thRb(id: Int, txt: String): RadioButton = RadioButton(this).apply {
            text = txt; this.id = id; setTextColor(Color.parseColor("#334155"))
        }
        val thAuto = thRb(21, "线程:自动"); val th4 = thRb(22, "4"); val th6 = thRb(23, "6"); val th8 = thRb(24, "8")
        rgThread.addView(thAuto); rgThread.addView(th4); rgThread.addView(th6); rgThread.addView(th8)
        when (sp.getInt("localThreads", 0)) { 4 -> th4.isChecked = true; 6 -> th6.isChecked = true; 8 -> th8.isChecked = true; else -> thAuto.isChecked = true }
        rgThread.setOnCheckedChangeListener { _, checkedId ->
            val n = when (checkedId) { th4.id -> 4; th6.id -> 6; th8.id -> 8; else -> 0 }
            sp.edit().putInt("localThreads", n).apply()
            LocalEngine.unload()
            status("✔ 推理线程：${if (n == 0) "自动" else "$n"}（下次翻译生效，可对比速度）", false)
        }
        pageModel.addView(rgThread)

        for (s in ModelManager.SPECS) {
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, 0) }
            val tv = TextView(this).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); setTextColor(Color.parseColor("#0f172a"))
            }
            val pb = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 1000; layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, 0) }
            lateinit var bDown: Button
            lateinit var bCancel: Button
            bDown = btn("下载", "#25d366") {
                if (ModelManager.busy()) { status("✘ 已有下载任务在跑：${ModelManager.activeModelId()}", true); return@btn }
                if (ModelManager.isReady(this@MainActivity, s)) { status("${s.title} 已就绪，无需重复下载", false); return@btn }
                if (ModelManager.download(this@MainActivity, s)) { bDown.isEnabled = false; bCancel.isEnabled = true }
            }
            bCancel = btn("暂停", "#64748b") { ModelManager.cancel() }
            val bDel = btn("删除", "#dc2626") {
                if (ModelManager.activeModelId() == s.id) { status("✘ 正在下载中，先点「暂停」再删", true); return@btn }
                ModelManager.delete(this@MainActivity, s); refreshModelRow(s.id, null)
                status("✔ 已删除 ${s.title}", false)
            }
            bCancel.isEnabled = false
            row.addView(bDown); row.addView(bCancel); row.addView(bDel)
            box.addView(tv); box.addView(pb); box.addView(row)
            pageModel.addView(box)
            modelViews[s.id] = ModelRowUi(tv, pb, bDown, bCancel)
            refreshModelRow(s.id, null)
        }
        // 进度回调：下载线程 emit -> 主线程刷 UI（ModelManager 已保证主线程派发）
        ModelManager.setListener { p ->
            val mv = modelViews[p.specId] ?: return@setListener
            val title = ModelManager.spec(p.specId)?.title ?: p.specId
            when (p.state) {
                ModelManager.State.RUNNING, ModelManager.State.VERIFYING -> {
                    mv.pb.progress = if (p.total > 0) (p.done * 1000 / p.total).toInt() else 0
                    mv.tv.text = (if (p.state == ModelManager.State.VERIFYING) "$title ✔ 下载完成，校验中…"
                        else "$title ${"%.1f".format(p.done / 1048576.0)}MB / ${"%.0f".format(p.total / 1048576.0)}MB" +
                            (if (p.bytesPerSec > 0) "  ${"%.1f".format(p.bytesPerSec / 1048576.0)}MB/s" else "")) +
                        (if (p.note.isNotEmpty()) "\n${p.note}" else "")
                    mv.bDown.isEnabled = false; mv.bCancel.isEnabled = true
                }
                ModelManager.State.READY -> refreshModelRow(p.specId, null)
                ModelManager.State.IDLE -> refreshModelRow(p.specId, p.note)     // 已暂停
                ModelManager.State.FAILED -> refreshModelRow(p.specId, p.note)
                else -> {}
            }
        }

        // =====================================================================
        // 页三：词典
        // =====================================================================
        pageDict.addView(label("专属词典（每行一个：印尼文 = 中文，双向强制；与扩展同格式可直接粘贴）"))
        edDict = edit(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        edDict.minLines = 6; edDict.gravity = Gravity.TOP
        // v0.2.3 问题⑤（用户报告：时间久了词典在界面"消失"，点恢复上一版又出来）：
        // 输入框从无初始化——app 进程回收后 onCreate 重建 → 框空白，但 sp("dict") 数据
        // 完好、翻译照常用（日志头 词典=18条 为证）。打开页面即回填真实内容。
        edDict.setText(sp.getString("dict", "") ?: "")
        pageDict.addView(edDict)
        // v0.2.1（真机取证：词典从 15 条掉到 1 条）：保存前显示条数确认 + 旧版自动备份 + 一键恢复
        val dictRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, 0) }
        dictRow.addView(btn("保存词典", "#25d366") {
            val newTxt = edDict.text.toString()
            val newN = newTxt.lines().count { it.contains('=') }
            val oldTxt = sp.getString("dict", "") ?: ""
            val oldN = oldTxt.lines().count { it.contains('=') }
            if (newN == 0 && oldN > 0) {
                status("✘ 新词典 0 条而现有 $oldN 条——疑似误清空，未保存。确要清空请删字后先备份再存。", true)
                return@btn
            }
            if (newN < oldN) {   // 条数变少 → 先备份旧版，二次点击确认才覆盖
                if (!dictShrinkArmed) {
                    dictShrinkArmed = true
                    sp.edit().putString("dict_bak", oldTxt).apply()
                    status("⚠ 新 $newN 条 < 现有 $oldN 条。旧版已备份。确认替换请再点一次「保存词典」。", true)
                    return@btn
                }
            } else {
                sp.edit().putString("dict_bak", oldTxt).apply()   // 正常保存也留一份上一版
            }
            dictShrinkArmed = false
            sp.edit().putString("dict", newTxt).apply()
            // v0.2.3：词典保存 → 清人名哨兵。加了 谭效平=xxx 词条 = 用户要求真翻译，
            // 回显学习结果让位；未加词条的名字下次真回显时会自动再学回来。
            runCatching { WaTranslateService.running?.purgeNameCache() }
            status("✔ 词典已保存（$newN 条），旧译文自动重译", false)
            dismissKeyboard()   // v0.1.9：保存即退出编辑态
        })
        dictRow.addView(btn("恢复上一版", "#64748b") {
            val bak = sp.getString("dict_bak", null)
            if (bak.isNullOrEmpty()) { status("✘ 没有可恢复的备份", true); return@btn }
            sp.edit().putString("dict", bak).apply()
            edDict.setText(bak)
            runCatching { WaTranslateService.running?.purgeNameCache() }   // v0.2.3：同保存词典
            status("✔ 已恢复上一版词典（${bak.lines().count { it.contains('=') }} 条）", false)
        })
        pageDict.addView(dictRow)

        // =====================================================================
        // 页四：云端（模型配置 = OpenAI 兼容 profile 多套 + 测试连接）
        // =====================================================================
        pageCloud.addView(label("模型配置（可存多套，下拉/点选即用）"))
        val spinRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        profileSpin = Spinner(this)
        profileSpin.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        profileSpin.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                if (position == activeIdx && lastTestedId == profileIds.getOrNull(position)) return
                val dirty = formDirty()
                activeIdx = position
                sp.edit().putString("activeProfile", profileIds.getOrNull(position) ?: "").apply()
                loadFormFromProfile(position)
                lastTestedId = profileIds.getOrNull(position)
                if (dirty) status("⚠ 切换前表单有未保存修改，已丢弃。要保留请先点「保存配置」。", true)
                else status("✔ 已切换，翻译立即使用「${profileName(position)}」", false)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        spinRow.addView(profileSpin)
        spinRow.addView(btn("＋新增", "#64748b") { addProfile() }.also { it.setPadding(dp(10), 0, dp(10), 0) })
        spinRow.addView(btn("删", "#dc2626") { delProfile() })
        pageCloud.addView(spinRow)

        pageCloud.addView(label("名称")); edName = edit(); pageCloud.addView(edName)
        pageCloud.addView(label("API Base URL（OpenAI 兼容）")); edBase = edit(); edBase.hint = "https://api.scnet.cn/api/llm/v1"; pageCloud.addView(edBase)
        pageCloud.addView(label("API Key")); edKey = edit(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD); pageCloud.addView(edKey)
        val modelRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val colModel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        colModel.addView(label("模型")); edModel = edit(); colModel.addView(edModel)
        val colTemp = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(dp(90), ViewGroup.LayoutParams.WRAP_CONTENT) }
        colTemp.addView(label("温度")); edTemp = edit(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL); colTemp.addView(edTemp)
        modelRow.addView(colModel); modelRow.addView(colTemp)
        pageCloud.addView(modelRow)

        val saveRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        saveRow.addView(btn("保存配置", "#25d366") { saveProfile() })
        saveRow.addView(btn("测试连接", "#64748b") { testConn() }.also { btnTest = it })
        pageCloud.addView(saveRow)

        // ---- 底部：提示/反馈栏（常驻，任何页的操作反馈都看得见） ----
        tvStatus = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(10), dp(8), dp(10), dp(8)); setTextColor(Color.parseColor("#334155"))
            setBackgroundColor(Color.parseColor("#e2e8f0"))
        }
        val sl = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); sl.topMargin = dp(4)
        root.addView(tvStatus, sl)
        tvStatus.text = "提示：装好后先「开启无障碍权限」→ 找到「WA翻译」→ 打开开关。默认点击翻译：在 WhatsApp 聊天里点对方气泡 → 气泡下方浮出深绿译文行（下方放不下会翻到上方）；再点同一条 = 强制重译（改完词典点一下即生效）；点别的气泡，上一条译文自动消失。"

        showPage(0)   // 默认「常规」
        loadProfiles()
    }

    override fun onResume() {
        super.onResume()
        tvService.text = if (WaTranslateService.isRunning()) "翻译服务：● 运行中（WhatsApp 内生效）"
        else "翻译服务：○ 未运行 —— 请点下方「开启无障碍权限」找到「WA翻译」并打开"
    }

    // ---------------- profiles ----------------
    private fun parsedProfiles(): List<Profile> = ConfigResolve.parseProfiles(profilesJson)

    private fun loadProfiles() {
        val list = parsedProfiles()
        if (list.isEmpty()) {
            // 迁移旧扁平字段
            val k = sp.getString("apiKey", "") ?: ""
            if (k.isNotEmpty() || (sp.getString("model", "") ?: "").isNotEmpty()) {
                val p = Profile("p0", "默认配置", sp.getString("apiBase", "") ?: ConfigResolve.DEFAULT_BASE,
                    k, sp.getString("model", "") ?: ConfigResolve.DEFAULT_MODEL,
                    (sp.getString("temperature", "") ?: "").toDoubleOrNull() ?: 0.2)
                profilesSave(listOf(p), p.id)
            } else {
                val p = Profile("p0", "scnet Qwen", ConfigResolve.DEFAULT_BASE, "", ConfigResolve.DEFAULT_MODEL, 0.2)
                profilesSave(listOf(p), p.id)
            }
        } else {
            val active = sp.getString("activeProfile", "") ?: ""
            activeIdx = list.indexOfFirst { it.id == active }.let { if (it >= 0) it else 0 }
        }
        refreshSpinner()
        loadFormFromProfile(activeIdx)
    }

    private fun profilesSave(list: List<Profile>, activeId: String) {
        val json = list.joinToString(",", "[", "]") { p ->
            "{\"id\":\"" + Json.escape(p.id) + "\",\"name\":\"" + Json.escape(p.name) +
                "\",\"apiBase\":\"" + Json.escape(p.apiBase) + "\",\"apiKey\":\"" + Json.escape(p.apiKey) +
                "\",\"model\":\"" + Json.escape(p.model) + "\",\"temperature\":" + p.temperature + "}"
        }
        sp.edit().putString("profiles", json).putString("activeProfile", activeId).apply()
    }

    private fun refreshSpinner() {
        val list = parsedProfiles()
        profileIds = list.map { it.id }
        val names = list.mapIndexed { i, p -> (if (i == activeIdx) "● " else "") + p.name }
        profileSpin.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        if (activeIdx in names.indices) profileSpin.setSelection(activeIdx, false)
    }

    private fun profileName(i: Int) = parsedProfiles().getOrNull(i)?.name ?: "未命名"
    private fun currentSaved(): Profile? = parsedProfiles().getOrNull(activeIdx)

    private fun loadFormFromProfile(i: Int) {
        val p = parsedProfiles().getOrNull(i) ?: return
        edName.setText(p.name); edBase.setText(p.apiBase)
        edKey.setText(p.apiKey); edModel.setText(p.model); edTemp.setText(p.temperature.toString())
    }

    private fun formDirty(): Boolean {
        val p = currentSaved() ?: return false
        return p.name != edName.text.toString().trim() || p.apiBase != edBase.text.toString().trim() ||
            p.apiKey != edKey.text.toString().trim() || p.model != edModel.text.toString().trim() ||
            p.temperature != (edTemp.text.toString().toDoubleOrNull() ?: 0.2)
    }

    private fun saveProfile() {
        val list = parsedProfiles().toMutableList()
        if (list.isEmpty()) { status("✘ 还没有配置", true); return }
        val old = list[activeIdx]
        val name = edName.text.toString().trim().ifEmpty { old.name }
        val np = Profile(
            id = old.id, name = name,
            apiBase = edBase.text.toString().trim().ifEmpty { ConfigResolve.DEFAULT_BASE },
            apiKey = edKey.text.toString().trim(),
            model = edModel.text.toString().trim().ifEmpty { ConfigResolve.DEFAULT_MODEL },
            temperature = (edTemp.text.toString().toDoubleOrNull() ?: 0.2).coerceIn(0.0, 2.0),
        )
        list[activeIdx] = np
        profilesSave(list, np.id)
        refreshSpinner(); lastTestedId = np.id
        status("✔ 已保存「$name」，翻译即时生效", false)
        dismissKeyboard()   // v0.1.9：保存即退出编辑态（API Key 等输入框光标不再残留闪烁）
    }

    private fun addProfile() {
        val list = parsedProfiles().toMutableList()
        val src = currentSaved()
        val base = (src?.name ?: "新配置")
        var n = 2; var name = "$base ($n)"
        while (list.any { it.name == name }) { n++; name = "$base ($n)" }
        val p = src?.copy(id = "p" + System.currentTimeMillis().toString(36), name = name)
            ?: Profile("p" + System.currentTimeMillis().toString(36), name, ConfigResolve.DEFAULT_BASE, "", ConfigResolve.DEFAULT_MODEL, 0.2)
        list.add(p)
        profilesSave(list, p.id)
        activeIdx = list.size - 1
        refreshSpinner(); loadFormFromProfile(activeIdx)
        if (formDirty()) status("⚠ 上一条有未保存修改未被带过来（新增只复制已保存值）", true)
        else status("✔ 已新增「$name」，改参数后点「保存配置」", false)
    }

    private fun delProfile() {
        val list = parsedProfiles().toMutableList()
        if (list.size <= 1) { status("✘ 至少保留一套配置", true); return }
        list.removeAt(activeIdx)
        activeIdx = 0
        profilesSave(list, list[0].id)
        refreshSpinner(); loadFormFromProfile(0)
        status("✔ 已删除，当前用「${profileName(0)}」", false)
    }

    private fun testConn() {
        // v0.1.5（用户反馈"点了没反应感觉"）：测试期间按钮禁用+改文案，杜绝连点排队
        // v0.2.4 真机取证（用户："测试可以，一保存就不行"）：旧实现走 ConfigResolve.resolve，
        // profiles 非空时【只读已保存 profile、表单当前输入被完全忽略】→ 新模型没保存前
        // 测的永远是旧配置（说谎的绿灯）。改为直接测表单现值——输入什么就测什么。
        btnTest?.apply { isEnabled = false; text = "测试中…" }
        val base = edBase.text.toString().trim().ifEmpty { ConfigResolve.DEFAULT_BASE }
        val model = edModel.text.toString().trim().ifEmpty { ConfigResolve.DEFAULT_MODEL }
        val cfg = ConfigResolve.resolve(
            "", null, base, edKey.text.toString(),
            model, edTemp.text.toString().toDoubleOrNull(), true, swFast.isChecked, "")
        status("正在测试：$model\n发往 $base/chat/completions（最长 45 秒）…", false)
        Thread {
            val dict = Dict.parse(sp.getString("dict", "") ?: "")
            val r = runCatching { engine().translateSingle(cfg, dict, "你好，这批货明天发货") }
            runOnUiThread {
                btnTest?.apply { isEnabled = true; text = "测试连接" }
                r.onSuccess { status("✔ 连接成功：「$it」" +
                        if (formDirty()) "（注意：这是表单当前输入，点「保存配置」后翻译才用它）" else "", false) }
                    .onFailure {
                        val msg = it.message ?: "未知错误"
                        status(when {
                            msg.contains("timeout", true) || msg.contains("timed out", true) ->
                                "✘ 服务器 45 秒没响应（网关高峰期常见）。等一两分钟再点一次；连续多次都超时请把日志发我"
                            msg.contains("API 404") ->
                                "✘ 404：该地址下没有这个模型。发往 $base/chat/completions 模型「$model」。\n" +
                                "接口地址用平台的 OpenAI 兼容地址（形如 https://…/v1，不要带 /anthropic 等其它协议前缀）；" +
                                "模型名去平台控制台核对确切拼写（区分大小写）。改好后重测，绿了再保存"
                            msg.contains("API 401") || msg.contains("API 403") ->
                                "✘ 401/403：API Key 不对或没有该模型权限（key 用该平台自己生成的，不是别家的）"
                            else -> "✘ 失败：$msg"
                        }, true)
                    }
            }
        }.start()
    }

    private var engineRef: Engine? = null
    private fun engine(): Engine = engineRef ?: Engine(this).also { engineRef = it }

    /** v0.1.9：保存后彻底退出编辑态——清输入焦点 + 强制收起软键盘（用户反馈光标还在闪） */
    private fun dismissKeyboard() {
        currentFocus?.clearFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        currentFocus?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
        window.decorView.post {
            runCatching { imm.hideSoftInputFromWindow(window.decorView.windowToken, 0) }
        }
    }

    private fun status(s: String, err: Boolean) {
        tvStatus.text = s
        tvStatus.setBackgroundColor(Color.parseColor(if (err) "#fee2e2" else "#dcfce7"))
    }
}
