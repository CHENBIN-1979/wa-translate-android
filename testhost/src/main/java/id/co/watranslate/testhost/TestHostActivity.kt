package id.co.watranslate.testhost

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*

/*
 * 假 WhatsApp 测试壳（模拟器验证翻译服务用，不含任何 WhatsApp 代码）。
 * 节点 id 仿新版 WhatsApp：message_text_view / contact_name / date；
 * 对方气泡贴左、自己气泡贴右（isOwnBubble 判定的测试面）；
 * 含噪声行：日期/加密提示/已删除占位（必须不出译文）。
 */
class TestHostActivity : Activity() {

    private val sp by lazy { getSharedPreferences("testhost", Context.MODE_PRIVATE) }

    data class Msg(val text: String, val own: Boolean, val noise: Boolean = false)

    // v0.3.5 E26 tap-copy 测试面：>80 字印尼文正文（WhatsApp 真机会把 a11y 节点截到 80，
    // testhost 不截——所以本用例专测「剪贴板全文 → 换键重译」链路，而非节点截断本身）。
    companion object {
        const val LONG_FULL_TEXT = "To Semua Mesin Calender Form tambah lipat yang baru tolong diperhatikan cara pengisiannya sesuai format contoh dari kantor pusat jangan lupa kolom alasan diisi lengkap"
    }

    private val msgs = ArrayList<Msg>()

    private var rcv: BroadcastReceiver? = null
    private var inputRef: android.widget.EditText? = null   // v0.2.0 E12
    private var lastScroll = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "WA测试壳"
        loadMsgs()
        build()
        // e2e 专用控制通道：am broadcast 触发重播种/追加消息（替代 uiautomator 点击，
        // 消除 UiAutomation 会话对无障碍事件分发的干扰，2026-09-07 实证）
        val f = IntentFilter()
        f.addAction("id.co.watranslate.SEED")
        f.addAction("id.co.watranslate.APPEND")
        f.addAction("id.co.watranslate.SETINPUT")   // v0.2.0 E12：直接改输入框内容（测「译」点击时现读）
        f.addAction("id.co.watranslate.QUOTE")      // v0.2.7 E21：注入仿 WhatsApp 引用回复树
        f.addAction("id.co.watranslate.CLOCKROW")   // v0.3.2 E24：注入仿真机「气泡内时间戳+日期胶囊」树
        f.addAction("id.co.watranslate.GROUPROW")   // v0.3.3 E25：注入仿真机「组名+已转发+印尼正文」树
        f.addAction("id.co.watranslate.LONGMSG")    // v0.3.5 E26：注入>80字长消息（tap-copy 测试面）
        rcv = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                if (i?.action?.endsWith("LONGMSG") == true) {
                    // v0.3.5 E26：>80字长消息（tap-copy 测试面），哨兵 @LONGMSG 持久化
                    msgs.clear(); msgs.add(Msg("@LONGMSG", false))
                    saveMsgs(); build(); lastScroll = true
                    return
                }
                if (i?.action?.endsWith("SETINPUT") == true && i != null) {
                    // 绕过 build()：只动 EditText.text —— 复现"内容变了但 overlay 缓存快照未刷新"
                    inputRef?.setText(i.getStringExtra("text") ?: "")
                    return
                }
                if (i?.action?.endsWith("GROUPROW") == true) {
                    // v0.3.3 E25（真机 2026-09-23 复刻）：群聊对方气泡 = 小字发送者名「陈副厂长」
                    // +「已转发」角标 + 印尼文正文，全部无 view-id；持久化哨兵 @GROUPROW
                    msgs.clear(); msgs.add(Msg("@GROUPROW", false))
                    saveMsgs(); build(); lastScroll = true
                    return
                }
                if (i?.action?.endsWith("CLOCKROW") == true) {
                    // v0.3.2 E24（真机 2026-09-23 复刻）：列表整体换成「日期胶囊 + 印尼文正文
                    // + 右下角中文时间戳」无 id 树，持久化哨兵 @CLOCKROW
                    msgs.clear(); msgs.add(Msg("@CLOCKROW", false))
                    saveMsgs(); build(); lastScroll = true
                    return
                }
                if (i?.action?.endsWith("QUOTE") == true) {
                    // v0.2.7 E21（真机树 09-21 08:59 复刻）：列表整体换成一条「引用+正文」回复，
                    // 持久化用哨兵消息（@QUOTE）——进程重建后 renderQuote 仍可复现
                    msgs.clear(); msgs.add(Msg("@QUOTE", false))
                    saveMsgs(); build(); lastScroll = true
                    return
                }
                val appending = i?.action?.endsWith("APPEND") == true
                if (appending && i != null) {
                    val t = i.getStringExtra("text") ?: return
                    if (t.isNotEmpty()) msgs.add(Msg(t, i.getBooleanExtra("own", false)))
                } else {
                    msgs.clear()
                    msgs.addAll(defaultMsgs())
                }
                saveMsgs()
                build()
                if (appending) lastScroll = true   // 追加的新消息可能不在视口内（无障碍树=可见节点）→ 滚到底
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(rcv, f, Context.RECEIVER_EXPORTED)
        else registerReceiver(rcv, f)
    }

    // 消息列表持久化（Activity 重建/旋转/重启不丢，且 seed 只播一次）
    private fun loadMsgs() {
        msgs.clear()
        val raw = sp.getString("msgs", "") ?: ""
        if (raw.isNotEmpty()) for (line in raw.split("\u0001")) {
            val p = line.split("\u0002")
            if (p.size == 3) msgs.add(Msg(p[0].replace("\\n", "\n"), p[1] == "1", p[2] == "1"))
        }
    }
    private fun saveMsgs() {
        sp.edit().putString("msgs", msgs.joinToString("\u0001") { it.text.replace("\n", "\\n") + "\u0002" + (if (it.own) 1 else 0) + "\u0002" + (if (it.noise) 1 else 0) }).apply()
    }

    private fun defaultMsgs(): List<Msg> = listOf(
        Msg("3/9/2026", false, true),                                  // 日期 → 不译
        Msg("Los mensajes y las llamadas están protegidos con cifrado de extremo a extremo de WhatsApp.", false, true), // 加密提示 → 不译
        Msg("Selamat pagi Pak, laporan shift malam sudah saya kirim", false), // 对方印尼语 → 译
        Msg("Baiklah, nanti saya cek bagian mixing machine dulu", false),      // 词典 mixing → 轧轮机
        Msg("你好，明天上午开会", true),                                          // 自己中文 → 不自动译
        Msg("这条消息已删除", false, true),                                    // 占位 → 不译
        Msg("Pak Chen, bahan baku CCD scanner datang jam 10 pagi", false)
    )

    private fun build() {
        val dm = resources.displayMetrics
        fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), dm).toInt()

        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(10), dp(10), dp(10)) }
        // 操作区
        val op = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val inp = EditText(this).apply {
            // v0.1.9：viewId 对齐真机 WhatsApp 聊天输入框（findComposer id 路径 e2e 可测）
            id = R.id.message_edit_text
            // v0.2.4 E18：真机 WhatsApp 输入框点击会产生 TYPE_VIEW_CLICKED（tap miss 日志
            // cls=EditText+rect 实证）；testhost 默认 EditText 不发 → 对齐点击语义
            isClickable = true
            setOnTouchListener { v, e ->
                if (e.action == android.view.MotionEvent.ACTION_UP) v.performClick()
                false   // 不消费，正常输入行为不受影响
            }
            hint = "输入消息文本"; setText("Selamat pagi Pak, laporan shift malam sudah saya kirim")
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        inputRef = inp
        val own = CheckBox(this).apply { text = "我发的" }
        op.addView(inp); op.addView(own)
        op.addView(Button(this).apply { text = "追加"; isAllCaps = false; setOnClickListener {
            msgs.add(Msg(inp.text.toString(), own.isChecked))
            saveMsgs(); inp.setText(""); build()
        } })
        op.addView(Button(this).apply { text = "清空"; isAllCaps = false; setOnClickListener {
            msgs.clear(); saveMsgs(); build()
        } })
        outer.addView(op)

        val scroll = ScrollView(this).apply {
            id = R.id.messages_list
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(col)
        // v0.1.9：消息列表吃掉剩余高度，操作区（输入框）沉底——对齐真机 WhatsApp
        // （聊天页输入框永远在屏幕底部；findComposer 的"下半屏"判定必须按真实布局测）
        outer.removeAllViews()
        outer.addView(scroll)
        outer.addView(op)
        setContentView(outer)

        if (msgs.isEmpty()) { msgs.addAll(defaultMsgs()); saveMsgs() }

        fun bubble(m: Msg) {
            if (m.text == "@QUOTE") { renderQuote(col); return }   // v0.2.7 E21 仿 WhatsApp 引用树
            if (m.text == "@CLOCKROW") { renderClockRow(col); return }  // v0.3.2 E24 时间戳污染树
            if (m.text == "@GROUPROW") { renderGroupRow(col); return }  // v0.3.3 E25 组名+已转发污染树
            if (m.text == "@LONGMSG") { renderLongMsg(col); return }    // v0.3.5 E26 长消息 tap-copy 树
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = if (m.own) Gravity.END else Gravity.START
                setPadding(0, dp(4), 0, dp(4))
            }
            val tv = TextView(this).apply {
                text = m.text
                // v0.1.2：气泡可点击 → TYPE_VIEW_CLICKED 走无障碍点击通道（e2e E8 点击翻译验证）
                isClickable = true
                id = when {
                    m.own -> 0x7f0a0001
                    else -> 0x7f0a0002
                }
                // 无障碍 viewId：系统按资源 id 输出 "<pkg>:id/<entry>" —— 没有正式 ids.xml 时用 tag 兜底不行，
                // 这里用 getIdentifier 映射仿 WhatsApp 的 id 名（resource 在宿主里不存在时返回 -1，
                // 故预定义资源名，见 res/values/ids.xml）
                setBackgroundColor(if (m.own) 0xFFDCF8C6.toInt() else 0xFFFFFFFF.toInt())
                setTextColor(0xFF111111.toInt())
                setPadding(dp(10), dp(7), dp(10), dp(7))
                maxWidth = dm.widthPixels * 3 / 4
                textSize = 15f
            }
            // 关键：view id 用预定义资源名，让 viewIdResourceName 输出 message_text_view / message_text
            tv.id = if (m.own) R.id.message_text else R.id.message_text_view
            // 噪声标记：date/system 用专门 id 名
            if (m.noise) {
                tv.id = if (m.text.matches(Regex("^\\d+/\\d+/\\d+$"))) R.id.date else R.id.system_message_text
            }
            row.addView(tv)
            col.addView(row)
        }
        msgs.forEach(::bubble)
        if (lastScroll) { lastScroll = false; scroll.post { scroll.fullScroll(android.view.View.FOCUS_DOWN) } }
    }

    /*
     * v0.2.7 E21 —— 仿 WhatsApp 引用回复（真机 09-21 08:59 dump 复刻）：
     * 全部节点【无 view-id】（真机 Redmi 上 WhatsApp 118 节点 id 全隐藏 → id 排除层失效），
     * 结构=昵称行+灰条引用块(11px 竖条+截断引用文)+正文兄弟容器。
     * 这是 quote-bar 剔除 / hint 归位 / 容器归位 三条新链路的唯一 e2e 测试面。
     */
    private fun renderQuote(col: LinearLayout) {
        val dm = resources.displayMetrics
        fun tv(text: String, color: Int, sizeSp: Float, clickable: Boolean = false) = TextView(this).apply {
            this.text = text
            setTextColor(color)
            textSize = sizeSp
            isClickable = clickable
            maxWidth = 813
            setPadding(6, 14, 6, 14)
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
            setPadding(10, 12, 10, 12)
        }
        val bubbleBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFFFFFFF.toInt())
            setPadding(8, 8, 8, 8)
        }
        // 昵称行（发送者+电话号，真机靠 NICK/PHONE 正则拦——保留验证双保险不回归）
        val nickRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        nickRow.addView(tv("~\u202fmohamad sodiq.f", 0xFF111111.toInt(), 15f))
        nickRow.addView(tv("+62 819-0122-2218", 0xFF667788.toInt(), 12f))
        bubbleBox.addView(nickRow)
        // 引用块 = [11px 灰条 | 左侧 22px 内缩的昵称+引用文]（dx=22/覆盖100% 真机实证值）
        val quoteBlock = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        quoteBlock.addView(android.view.View(this).apply { setBackgroundColor(0xFF94A3B8.toInt()) },
            LinearLayout.LayoutParams(11, LinearLayout.LayoutParams.MATCH_PARENT))
        val quoteInner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        quoteInner.addView(tv("~\u202fSri Sumarni \uD83D\uDC9E", 0xFF667788.toInt(), 12f))
        quoteInner.addView(tv("Area komposisi tolong di tutup lagi krn retak kmrn mk", 0xFF667788.toInt(), 13f, clickable = true))
        quoteBlock.addView(quoteInner)
        bubbleBox.addView(quoteBlock)
        // 正文兄弟容器（真机结构：条管辖范围之外的独立 FrameLayout）
        val bodyFrame = android.widget.FrameLayout(this)
        bodyFrame.addView(tv("Baik terimakasih informasinya Bu Marni", 0xFF111111.toInt(), 15f, clickable = true))
        bubbleBox.addView(bodyFrame)
        row.addView(bubbleBox)
        col.addView(row)
    }

    /*
     * v0.3.2 E24 —— 仿真机 2026-09-23「部份可译大部份不行」案发现场（Redmi 中文 WhatsApp）：
     * 白胶囊日期「2026年9月23日」+ 对方气泡（印尼文正文 + 右下角中文时间戳「早上6:21」），
     * 全部节点【无 view-id】（真机 id 全隐藏 → id 排除层失效，只能靠文本正则拦）。
     * 旧 CLOCK_RES 缺「早上」前缀 → 时间戳漏进 mergeFragments → 候选含汉字 →
     * detectTarget 判 id → 印尼文"回显"合法过闸门。修复后：两个碎片各自被 clock/divider 剔除，
     * 候选=干净印尼文正文。renderQuote 同款无 id 结构复用 tv()。
     */
    private fun renderClockRow(col: LinearLayout) {
        val dm = resources.displayMetrics
        fun tv(text: String, color: Int, sizeSp: Float, clickable: Boolean = false) = TextView(this).apply {
            this.text = text
            setTextColor(color)
            textSize = sizeSp
            isClickable = clickable
            maxWidth = dm.widthPixels * 3 / 4
            setPadding(6, 14, 6, 14)
        }
        // 日期胶囊（居中灰字，无 id —— 真机「2026年9月23日」形态）
        col.addView(tv("2026年9月23日", 0xFF8899AA.toInt(), 12f).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })
        // 对方气泡：正文+右下角内嵌时间戳（FrameLayout 叠放=真机 WhatsApp 布局近似）
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
            setPadding(10, 12, 10, 12)
        }
        val bubbleBox = android.widget.FrameLayout(this).apply {
            setBackgroundColor(0xFFFFFFFF.toInt())
            setPadding(8, 8, 8, 8)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        bubbleBox.addView(tv("Laporan kerusakan pisau A006 sudah saya kirim", 0xFF111111.toInt(), 15f, clickable = true))
        bubbleBox.addView(tv("早上6:21", 0xFF8899AA.toInt(), 11f).apply {
            gravity = Gravity.BOTTOM or Gravity.END
        }, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.END))
        row.addView(bubbleBox)
        col.addView(row)
    }

    /*
     * v0.3.5 E26 —— >80 字长消息 tap-copy 测试面【仿真机节点截断】。
     * 真机 WhatsApp：气泡 a11y 节点 text 在 ~80 字符硬截断（可见文字是全文，但
     * a11y 树里只有 80 字——这一层无法在 testhost 复刻成"显示全文+树里截断"：
     * run-4 实证 IMPORTANT_FOR_ACCESSIBILITY_NO 并不能把底层全文节点从服务读到的
     * 树里剔除，双层设计会让候选直接变成全文、架空被测链路）。
     * 因此 a11y 保真优先：树里唯一节点=80 字截断文本（可见即截断，牺牲视觉仿真）。
     * 服务链路：点击(80字)→注入长按→本 view 弹「复制」菜单→服务点复制→
     * 剪贴板全文(167字)→tap-copy 登记映射→换键全文重译。
     */
    private fun renderLongMsg(col: LinearLayout) {
        val dm = resources.displayMetrics
        fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), dm).toInt()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
            setPadding(0, dp(4), 0, dp(4))
        }
        val box = android.widget.FrameLayout(this).apply {
            setBackgroundColor(0xFFFFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(
                dm.widthPixels * 3 / 4, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        box.addView(TextView(this).apply {
            text = LONG_FULL_TEXT.substring(0, 80)   // a11y 树里唯一节点=截断形态
            setTextColor(0xFF111111.toInt())
            setPadding(dp(10), dp(7), dp(10), dp(7))
            textSize = 15f
            isClickable = true
            isLongClickable = true
            setOnLongClickListener { v -> showCopyMenu(v, LONG_FULL_TEXT); true }
            id = R.id.message_text_view
        }, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(box)
        col.addView(row)
    }

    /** 「复制」菜单（PopupMenu=独立 a11y 窗，仿 WhatsApp context menu；锚=真实气泡 view）。 */
    private var copyMenuRef: android.widget.PopupMenu? = null
    private fun showCopyMenu(anchor: android.view.View, text: String) {
        val pm = android.widget.PopupMenu(this, anchor, Gravity.CENTER)
        pm.menu.add(0, 1, 0, "复制")
        pm.setOnMenuItemClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("testhost", text))
            copyMenuRef = null
            true
        }
        pm.show()
        copyMenuRef = pm
    }

    /*
     * v0.3.3 E25 —— 真机 2026-09-23「凭空加词」案发现场复刻（红米中文 WhatsApp 群聊）：
     * 对方气泡三碎片 = 小字发送者名「陈副厂长」（4 纯汉字，NICK_RES 不拦、真机 id 全隐藏）
     * +「已转发」角标 + 印尼文正文，纵向紧邻。旧行为：名+角标 mergeFragments 拼进正文
     * → src 含汉字 detectTarget 翻转 → 译文多出 Wakil Direktur Chen / Sudah dikirimkan
     * 这种"凭空加出来的行"（demo 纯文本粘贴无此现象）。修复后：「已转发」被 FORWARDED_RES
     * 单节点过滤（drop forwarded）、名行被 senderNameLine 在 merge 层整行剔除（trace
     * sender-name-line）→ 候选=净正文 → 翻中文。正文用 mock 既有词条 A4 句。
     */
    private fun renderGroupRow(col: LinearLayout) {
        val dm = resources.displayMetrics
        fun tv(text: String, color: Int, sizeSp: Float, clickable: Boolean = false) = TextView(this).apply {
            this.text = text
            setTextColor(color)
            textSize = sizeSp
            isClickable = clickable
            maxWidth = dm.widthPixels * 3 / 4
            setPadding(6, 6, 6, 6)
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
            setPadding(10, 12, 10, 12)
        }
        val bubbleBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFFFFFFF.toInt())
            setPadding(8, 8, 8, 8)
        }
        bubbleBox.addView(tv("陈副厂长", 0xFF667788.toInt(), 12f))                    // 名行（小字灰）
        bubbleBox.addView(tv("已转发", 0xFF8899AA.toInt(), 11f))                      // 角标行
        bubbleBox.addView(tv("Mau lihat perbandingan A4-nya, letakkan di atas meja saya", 0xFF111111.toInt(), 15f, clickable = true))
        row.addView(bubbleBox)
        col.addView(row)
    }
}
