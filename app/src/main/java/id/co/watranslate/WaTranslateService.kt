package id.co.watranslate

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.Color
import android.util.TypedValue
import android.util.Log
import kotlin.concurrent.thread

/*
 * WaTranslateService.kt — 悬浮翻译核心服务（对标 content.js + background.js 的运行时合体）
 * 点击模式一次只保留一条译文（v0.1.4 用户定案）：点新气泡→旧行自动消失。
 * 点未译气泡=译；点已译气泡=强制重译（词典改完点一下生效）。无 ⟳ 图标、无光晕阴影。
 * 位置智能：气泡下方空间不足（贴近 WhatsApp 输入栏）→ 译文行翻到气泡上方（v0.1.4）。
 */
class WaTranslateService : AccessibilityService() {


    companion object {
        // v0.3.7 F2（真机取证：v0.3.6 代码的日志水印写 v0.3.5）：旧版硬编码 const，
        // 每次出包漏改即全日志失真。改读 BuildConfig.APP_VERSION（app/build.gradle
        // buildConfigField，唯一真源=versionName，出包脚本不再需要第二处同步）。
        val VERSION: String = BuildConfig.APP_VERSION
        const val TAG = "wTranslate"
        const val PRIMARY_PKG = "com.whatsapp"
        const val BIZ_PKG = "com.whatsapp.w4b"
        const val TEST_PKG = "id.co.watranslate.testhost" // 假 WhatsApp 测试壳
        val TARGET_PKGS = setOf(PRIMARY_PKG, BIZ_PKG, TEST_PKG)
        // v0.1.4 无光晕（用户定案）：浅色会被白气泡吞掉，全部换深色系
        val COLOR_TEXT = Color.parseColor("#0E8C46")      // 深 WhatsApp 绿
        val COLOR_PENDING = Color.parseColor("#16A34A")
        val COLOR_FAIL = Color.parseColor("#B45309")
        val COLOR_NAME = Color.parseColor("#94A3B8")     // v0.2.3 人名·保留原文（中性灰，不标红）
        @Volatile var running: WaTranslateService? = null
        fun isRunning(): Boolean = running != null
        @Volatile var lastSnapshot: String = ""
        @Volatile var lastSnapAt: Long = 0
    }

    private lateinit var engine: Engine
    private val wm: android.view.WindowManager by lazy { getSystemService(WINDOW_SERVICE) as android.view.WindowManager }
    private val main = Handler(Looper.getMainLooper())
    private var lastPkg = ""

    private class Row(
        val win: View,                 // overlay 根 view（纯穿透，不可点；v0.1.3 起它是唯一窗口）
        val tag: TextView,
        val body: TextView,
        var src: String,
        var box: Rect,                 // 气泡屏幕矩形（锚点）
        var anchorKey: String,         // 去重身份：文本+包+top|left|right（无障碍无稳定 data-id，用几何+文本）
        var pending: Boolean = false,
        var failed: Boolean = false,
        var staleTicks: Int = 0,
        var waitSecs: Int = 0,          // v0.1.7：等待秒数跳动（排队型网关体感）
        var tickRunnable: Runnable? = null,
        var nameOnly: Boolean = false,        // v0.2.3 人名灰显行（重译不 forget，防漂移）
    )

    private val rows = ArrayList<Row>()
    // v0.1.8（用户定案三态）：再点气泡=关闭 → key 进黑名单，防 stale 撤行/自动模式复活；
    // 同条重新点开时先出名单。切聊天窗（detachAll）清空——回到该聊天允许再译。
    private val closedKeys = HashSet<String>()
    // v0.3.8 P2（真机取证 9/25 问题②：新消息推入→列表滚动→已译气泡滚出视口→stale 撤行，
    // 滚回来时点击模式绝不自动重投(:363 continue)→用户必须重新点击）。tappedKeys=本轮会话
    // 里用户点译过的 key；气泡滚回视口即重建行（缓存秒命中，零 API）。手动关闭/被新行替换
    // 时移出，detachAll 清空——与 closedKeys 生命周期一致。
    private val tappedKeys = HashSet<String>()

    // ---------------- v0.1.2 点击翻译 ----------------
    // 自动模式关闭时（默认关）：消息不自动出译文行；点中对方气泡才译那一条，再点一次收起。
    // 点击判定通道：TYPE_VIEW_CLICKED 无障碍事件 —— event.source 带被点节点的屏幕 bounds（主判据）
    // 与 text（辅助）。adb input tap 注入真实 MotionEvent 同样触发该事件（E6 实证），
    // 故 testhost 气泡设 clickable 即可全链路回归。真机 WhatsApp 消息行是否 clickable 由诊断快照
    // 的 clickable= 字段取证（本次快照已加该字段）。
    // 注意：不用 FLAG_REQUEST_TOUCH_EXPLORATION_MODE —— 它会把全系统点按改成两段式（TalkBack 语义），
    // 直接破坏 WhatsApp 正常操作（设计阶段自查否决）。
    @Volatile var autoTranslate = false
    // v0.1.4：正文字号 sp 可经设置页滑杆调（12–24，默认 16），拖动即时重排现有行
    @Volatile var bodySizeSp = 16f
    @Volatile private var clickedHint: String? = null     // 点击事件的文字线索
    @Volatile private var pendingClickRect: Rect? = null  // 点击事件的 source bounds

    // ---------------- v0.3.5 ②：点按气泡→注入长按→点「复制」→剪贴板全文 ----------------
    // 真机 WhatsApp 正文节点 ~80 字符硬截断（9/22 日志实证恰 80）。点击链路照旧工作；
    // 仅当被点文本 ≥TRUNC_TRIGGER 才值得探测全文（短消息永不截断，零打扰）。
    // 任何一环失败（手势被 OEM 拦截/菜单文案不认识/剪贴板禁读）→ 静默降级 = v0.3.4 行为。
    @Volatile private var tapCopyBusy = false
    private var tapCopyPending: Runnable? = null
    private var tapCopyRect: Rect? = null   // v0.3.5：正在探测的气泡矩形（噪声判定用）
    private var tapCopyPkg: String? = null  // v0.3.6 E26 修复：探测起点留存 pkg（行丢失时全文行直接注入用）
    private var lastClickAt = 0L
    // v0.3.6 真机取证（9/24 日志）：点「复制」→ 读到的是【用户此前按「导出诊断」写入的
    // 旧剪贴板】（pkg=com.whatsapp… 那份节点树快照）→「剪贴板非本条」放弃。
    // 注：Android 10+ 非焦点态 primaryClip 读不到（正是要焦点探针的原因），「基线快照」
    // 方案不成立；正确修法=把前缀判定的比对物从【合并点击文本】换成【正文】
    // （ScanLogic.bodyOf），旧导出快照/无关内容必然过不了前缀关，多轮询几次即可。

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "service connected")
        running = this
        // v0.4.3 恢复保活（v0.3.7 F1 原样）：真机取证 myBCA 命中面=canPerformGestures，
        // 非 FGS（v0.4.2 删手势声明后弹窗消失；P2 搜狗/小红书/抖音常驻 FGS 不弹）。
        // 收益：MIUI 杀进程后无障碍不再失效，免重开开关。
        startKeepAlive()
        engine = Engine(applicationContext)
        LocalSmoke.maybeRun(applicationContext)  // M1d：debug+flag-file 才触发，release 空操作
        ModelSmoke.maybeRun(applicationContext)  // M2：debug+flag-file 才触发，release 空操作
        // v0.3.0 M3：本地引擎闲置 ~5min 自动卸载（native 内存回收）；60s 巡检一次。
        startIdleWatcher()
        // v0.2.2 监控：pick 过滤节点进导出日志（单测环境不连接服务 → hook 恒 null 零影响）
        ScanLogic.WatchSink.hook = ::onWatchDrop
        autoTranslate = getSharedPreferences("wtrans", MODE_PRIVATE).getBoolean("auto", false)
        bodySizeSp = getSharedPreferences("wtrans", MODE_PRIVATE).getFloat("font_sp", 16f)
        loadComposeDragPos()   // v0.3.5 ④：恢复用户拖拽位
        // v0.3.5 ③：全文映射载入 + 落盘 hook（SP 单键，写盘走后台线程，同扩展思路）
        runCatching {
            val fsp = getSharedPreferences("wtrans_fulltext", MODE_PRIVATE)
            FullText.load(fsp.getString("map", null))
            FullText.persist = { s ->
                thread(name = "wtrans-ft-persist") { runCatching { fsp.edit().putString("map", s).apply() } }
            }
        }
        serviceInfo = (serviceInfo ?: AccessibilityServiceInfo()).apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_VIEW_SCROLLED or
                AccessibilityEvent.TYPE_VIEW_CLICKED   // v0.1.2 点击翻译：被点节点 source bounds
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 200
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg == packageName) return // 自己 overlay 引发的窗口事件，忽略（否则加了行→事件→撤行→死循环）
        if (pkg in TARGET_PKGS) {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) lastPkg = pkg
            // 点击翻译通道：被点节点 bounds（主）+ 文本（辅）。重复点击由 scan 里 toggle 吸收。
            if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED &&
                System.currentTimeMillis() - lastClickAt > 400) {
                lastClickAt = System.currentTimeMillis()
                val srcTxt = event.text?.joinToString("").orEmpty().trim()
                val node = runCatching { event.source }.getOrNull()
                val b = node?.let { Rect().also { r -> it.getBoundsInScreen(r) } }
                Log.i(TAG, "click evt txt=${srcTxt.take(20)} b=${b?.toShortString()}")
                // v0.3.5 ②：tap-copy 进行中——这一下【可能】是我们注入的长按/菜单点击的副产物
                // （WhatsApp 对 context menu 项点击同样发 TYPE_VIEW_CLICKED）。
                // run-3 E10 取证：旧版一刀切全吞，把用户点【另一条气泡】的正常点击也吞了
                // （E10 点第二条气泡 2.5s 无反应）。改为只吞三类噪声：
                //   a) 无文字点击（注入长按的 DOWN 副产物，source 常无 text）
                //   b) 复制菜单项文字（「复制/Copy/Salin…」——点菜单不该触发气泡翻译）
                //   c) 落点在被探测气泡矩形内（长按同一条气泡收尾时 WhatsApp 补发的 CLICK）
                // 其余（点了另一条气泡）→ 正常走点击翻译通道。
                if (tapCopyBusy) {
                    val s = srcTxt.lowercase()
                    val menuNoise = s.isBlank() ||
                        listOf("复制", "copy", "salin", "複製", "تكرار").any { s.contains(it) }
                    val onProbeBubble = b != null && tapCopyRect?.let { it.contains(b.centerX(), b.centerY()) } == true
                    if (menuNoise || onProbeBubble) {
                        Log.d(TAG, "click during tapCopy suppressed: ${srcTxt.take(12)}")
                        node?.let { runCatching { it.recycle() } }
                        scheduleScan()
                        return
                    }
                    // 点了别的气泡：用户意图明确——立即放弃本轮探测（撤轮询、还原 busy），
                    // 绝不让后台轮询稍后弹出菜单打断用户正在做的事。
                    tapCopyPending?.let { main.removeCallbacks(it) }; tapCopyPending = null
                    tapCopyBusy = false
                    logBoth("tap-copy 中止: 用户点击其他气泡")
                }
                // 哨兵：source 无文字时也记录点击（hint=""），scan 里 rect 优先、hint 兜底
                main.post { clickedHint = srcTxt.take(200); pendingClickRect = b }
                // v0.3.6：tap-copy 判定改为【事件线程内同步】消费 node（v0.3.5 是
                // postDelayed 120ms 后用 node 坐标——但节点在下面就被 recycle 了，
                // 新版要 performAction(LONG_CLICK) 必须在 recycle 之前完成判定+派发）。
                if (pkg in TARGET_PKGS && node != null && b != null && b.width() > 0 && b.height() > 0) {
                    tapCandidate(node, srcTxt, b, pkg)
                }
                node?.let { runCatching { it.recycle() } }
            }
            Log.d(TAG, "event pkg=$pkg type=${event.eventType}")
            scheduleScan()
        } else if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            (rows.isNotEmpty() || composeWin != null)) {
            // v0.2.0（用户实证 bug）：旧条件只看 rows——只用了输入框「译」没点过气泡时
            // rows 空 → 退桌面「译」按钮永久残留。composeWin 一并纳入判定。
            main.post(::detachAll)
        }
    }

    override fun onInterrupt() {}

    // ---------------- v0.4.3 恢复（v0.3.7 F1 原样）：常驻前台通知保活 ----------------
    // 真机取证：myBCA 命中面 = canPerformGestures（v0.4.2 删后消失），非 FGS。
    // 无障碍服务实例不能 startService/bindService，但 AccessibilityService 本身就是
    // 系统绑定的 Service → 直接对它 startForeground() 合法（平台组件已在白名单），
    // 进程升 FOREGROUND_SERVICE 优先级 → LMK/MIUI 清理不再优先杀，权限不被清。
    // 通知 LOW 优先级=无声音无横幅、常驻下拉一条，点击直达无障碍设置。
    private fun startKeepAlive() {
        try {
            val nm = getSystemService(android.app.NotificationManager::class.java)
            nm.createNotificationChannel(android.app.NotificationChannel(
                "wa_alive", "WA翻译守护",
                android.app.NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
            val pi = android.app.PendingIntent.getActivity(this, 0,
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
                android.app.PendingIntent.FLAG_IMMUTABLE)
            val ni = android.app.Notification.Builder(this, "wa_alive")
                .setContentTitle("WA翻译运行中")
                .setContentText("在 WhatsApp 聊天里点气泡即译；点按打开无障碍设置")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true)
                .setContentIntent(pi)
                .build()
            if (Build.VERSION.SDK_INT >= 34)
                startForeground(42, ni, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else
                startForeground(42, ni)
            logBoth("keep-alive 前台通知已挂")
        } catch (t: Throwable) {
            // MIUI 部分版本对无障碍服务 startForeground 抛 FGS 策略异常 → 降级继续跑
            logBoth("startKeepAlive 失败（降级后台运行）: ${t.javaClass.simpleName}: ${t.message}")
        }
    }


    override fun onUnbind(intent: Intent?): Boolean { stopIdleWatcher(); detachAll(); running = null; return super.onUnbind(intent) }
    override fun onDestroy() { stopIdleWatcher(); detachAll(); running = null; LocalEngine.unload(); super.onDestroy() }

    // ---- v0.3.0 M3：本地引擎闲置卸载巡检；v0.3.1 ③：5min→15min（真机首句重载 ~2min 代价太高，
    // 工作间隙通常 <15min；常驻内存 = mmap 页缓存，本就按需换入，代价可接受） ----
    private val idleWatcher = object : Runnable {
        override fun run() {
            try { LocalEngine.maybeIdleUnload(15 * 60_000L) } catch (_: Throwable) {}
            main.postDelayed(this, 60_000L)
        }
    }
    private fun startIdleWatcher() { main.removeCallbacks(idleWatcher); main.postDelayed(idleWatcher, 60_000L) }
    private fun stopIdleWatcher() { main.removeCallbacks(idleWatcher) }

    private var scanScheduled = false
    private val scanExec = java.util.concurrent.Executors.newSingleThreadExecutor()
    private fun scheduleScan() {
        if (scanScheduled) return
        scanScheduled = true
        // rootInActiveWindow 与整棵 toUi 拷贝放工作线程：主线程读窗会撞上 overlay addView 的
        // 布局窗口期 → 拿到不完整窗 → 气泡"消失" → 刚点的行被 stale 撤掉（E9 回归实证 2026-09-07）
        main.postDelayed({
            scanScheduled = false
            if (!engine.loadConfig().enabled) { if (rows.isNotEmpty()) detachAll(); return@postDelayed }
            scanExec.execute { scanBg() }
        }, 450)
    }

    private fun scanBg() {
        val root = rootInActiveWindow ?: run { Log.w(TAG, "no active window"); return }
        val pkg = root.packageName?.toString() ?: lastPkg
        if (pkg !in TARGET_PKGS) { runCatching { root.recycle() }; return }
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        val ui = root.toUi()
        runCatching { root.recycle() }
        main.post { scan(ui, pkg, screenW, screenH) }
    }

    private fun scan(ui: UiNode, pkg: String, screenW: Int, screenH: Int) {
        // 诊断快照：WhatsApp 窗口只在此刻可读（回 APP 后 rootInActiveWindow 就不是它了），
        // 所以每次扫描顺手留存（3s 限流），APP「导出诊断」按钮复制最近一份
        if (pkg in TARGET_PKGS && System.currentTimeMillis() - lastSnapAt > 3000) {
            lastSnapAt = System.currentTimeMillis(); lastSnapshot = buildSnapshot(ui, pkg)
        }
        val candsRaw = ScanLogic.pick(ui, screenW)
        // v0.3.5 ③：全文映射——候选文本命中已登记截断前缀 → 换成全文再进后续所有链路
        // （点击消费/键名/缓存/翻译）。全文键优先：同一条消息不会再以截断形态出译文。
        val cands = candsRaw.map { c ->
            FullText.resolve(c.text)?.let { Candidate(it, c.anchor, c.own) } ?: c
        }
        val dbg = cands.joinToString(" ; ") { it.text.take(16) + "<" + it.anchor.idSuffix + "|" + it.anchor.className.substringAfterLast('.') + ">" + it.anchor.left + "," + it.anchor.top + "," + it.anchor.right + "," + it.anchor.bottom }
        Log.d(TAG, "scan cands=${cands.size}: $dbg")

        // 点击消费（本轮气泡集齐后判定，source bounds 主判据）
        val crect = pendingClickRect
        val chint = clickedHint
        if (crect != null || chint != null) {
            pendingClickRect = null; clickedHint = null
            // v0.2.7：hint 归位需要全树竖条（真机部分点击 event.source 无 rect，只剩文字线索）
            val bars = ScanLogic.collectQuoteBars(ui)
            var hit = crect?.let { ScanLogic.bubbleInRect(cands, it.left, it.top, it.right, it.bottom) }
            // v0.2.7（真机取证：点回复消息的引用区，hint 文本与被引用原句相同，会撞上别条
            // 独立候选的错误原文）：hint 通道在文本线索匹配之前先走引用归位——
            // hint 只匹配引用块内容时 → 归位到该条正文。歧义（同时匹配独立原文）则拒绝，旧行为。
            if (hit == null && !chint.isNullOrBlank()) {
                val qt = ScanLogic.quoteHintTarget(ui, bars, cands, chint)
                if (qt != null) {
                    Log.i(TAG, "tap 归位引用: ${qt.text.take(24)}")
                    LogBuf.log("tap 归位引用: ${qt.text.take(24)}")
                    hit = qt
                }
            }
            hit = hit ?: chint?.takeIf { it.isNotBlank() }?.let { h -> cands.firstOrNull { norm(it.text).startsWith(h.take(60)) || h.startsWith(norm(it.text).take(60)) } }
            // v0.2.4 问题④：命中输入框且有字 → 走「译」链路（v0.2.7：顺序保持在归位之前——
            // composer 判定是精确重叠，归位是兜底猜测，绝不能劫持输入框点击）
            var composerConsumed = false
            if (hit == null && tryComposerTap(crect, ui, screenH)) composerConsumed = true
            // v0.2.7 rect 通道：以上全落空 → 点击矩形所在最小"唯一候选"容器归位（点引用区=点该条）
            if (hit == null && !composerConsumed && crect != null) {
                val target = ScanLogic.tapContainerTarget(ui, cands, crect.left, crect.top, crect.right, crect.bottom)
                if (target != null) {
                    Log.i(TAG, "tap 归位容器: ${target.text.take(24)}")
                    LogBuf.log("tap 归位容器: ${target.text.take(24)}")
                    hit = target
                }
            }
            if (hit != null) {
                val key = hit.text.take(200) + "|" + pkg
                val exist = rows.firstOrNull { it.anchorKey == key }
                // v0.1.3 用户定案：⟳ 图标删除，再点已译气泡 = 强制重译（词典改完点一下即生效）
                if (exist != null) {
                    // v0.1.8 用户定案：再点气泡=关闭译文行（重译入口移到点行文字）
                    if (exist.pending) { Log.i(TAG, "close ignored (pending): ${hit.text.take(24)}") }
                    else {
                        Log.i(TAG, "close row: ${hit.text.take(24)}")
                        LogBuf.log("tap 关闭: ${hit.text.take(24)}")
                        closedKeys.add(key); tappedKeys.remove(key); removeRow(exist); rows.remove(exist)  // v0.3.8 P2：手动关=不再自动重投
                    }
                }
                else { closedKeys.remove(key); tappedKeys.add(key); Log.i(TAG, "tap 首译: ${hit.text.take(24)}"); LogBuf.log("tap 首译: ${hit.text.take(24)}"); injectRow(hit, key) }  // v0.3.8 P2：点译过的 key 进记忆表
                clampScreen(screenH)
            } else if (!composerConsumed) {
                // v0.2.2 监控：点了没反应的真机取证通道（旧版只进 logcat，导出日志里看不见）
                Log.i(TAG, "click miss (own/noise/offscreen) rect=${crect?.toShortString()} hint=${chint?.take(20)}")
                logTapMiss(ui, crect, chint, screenW)
            }
        }

        // 1) 标记本轮未出现的行为 stale；连续 2 轮未见才撤（防滚动瞬间抖动）
        val liveKeys = HashSet<String>()
        for (c in cands) {
            val key = c.text.take(200) + "|" + pkg // 身份=文本+包（几何随滚动变，不能进 key）
            liveKeys.add(key)
            val exist = rows.firstOrNull { it.anchorKey == key }
            if (exist != null) {
                exist.box = Rect(c.anchor.left, c.anchor.top, c.anchor.right, c.anchor.bottom)
                exist.staleTicks = 0
                position(exist)
                continue
            }
            if (c.own) continue
            if (closedKeys.contains(key)) continue  // v0.1.8：手动关闭过的不再自动弹回
            if (!autoTranslate && !tappedKeys.contains(key)) continue  // v0.1.2：未点过的绝不自动出
            if (rows.any { it.anchorKey == key }) continue  // v0.1.4 修复：重复消息只显示一行（瞬时错位行锚点会被上面归位）
            if (!autoTranslate) {
                // v0.3.8 P2：已点译的气泡滚出视口（stale 撤行）后滚回来 → 重建行（缓存秒命中）。
                // 修订：屏上已有行（用户刚点的新气泡=当前意图）时不重投、也不移出 tapped——
                // 否则来回滚动会让译文行反复易主刷屏。key 出 tapped 只发生在手动关闭/single-row 替换。
                if (rows.isNotEmpty()) continue
                Log.i(TAG, "滚回重投: ${c.text.take(24)}")   // 双通道（e2e E28 断言走 logcat）
                LogBuf.log("滚回重投: ${c.text.take(24)}")
            } else {
                LogBuf.log("自动首译: ${c.text.take(24)}")
            }
            injectRow(c, key)
        }
        updateComposeBtn(ui, screenH)   // v0.1.9：聊天输入框「译」按钮（对齐扩展 onComposeTranslate）
        // 本轮一个候选都没抓到 = 大概率读窗撞上布局窗口期，不据此惩罚现有行（防误撤）
        if (cands.isEmpty() && rows.isNotEmpty()) { Log.d(TAG, "scan empty cands w/ rows — skip stale"); return }
        val it = rows.iterator()
        while (it.hasNext()) {
            val r = it.next()
            if (!liveKeys.contains(r.anchorKey)) {
                if (closedKeys.contains(r.anchorKey)) { removeRow(r); it.remove(); continue }  // v0.1.8：已关闭的直接走
                r.staleTicks++
                // 自动模式 2 轮未见才撤（防抖动）；点击模式气泡滚出视口 1 轮即撤
                if (r.staleTicks >= (if (autoTranslate) 2 else 1)) { removeRow(r); it.remove() }
            }
        }
        // 2) 超屏清理（列表滚动后旧 key 自然消失，无需额外处理）
        clampScreen(screenH)
    }

    // ---------------- v0.1.9 输入框反向翻译「译」按钮（对齐扩展 content.js onComposeTranslate） ----------------
    private var composeWin: View? = null
    private var composeTv: TextView? = null
    @Volatile private var composeNodeRaw: AccessibilityNodeInfo? = null
    private var composeOriginal: String? = null      // 未点=原文；点过=暂存的原文，再点还原
    private var composeTranslatedSnapshot: String? = null
    private var composeBusy = false
    private var composeTick: Runnable? = null   // v0.3.8 P4：「译」按钮等待秒表（…Ns）
    // v0.3.5 ④：拖拽定位——用户拖动过（长按 400ms 后移动>10dp）→ 按钮改走「绝对坐标+持久化」，
    // 不再随输入框重锚定；删除 SP 两个键即回到自动位。
    private var composeDragged = false
    private var composeDragX = 0
    private var composeDragY = 0

    private fun loadComposeDragPos() {
        val sp = getSharedPreferences("wtrans", MODE_PRIVATE)
        composeDragged = sp.getBoolean("cbtn_drag", false)
        composeDragX = sp.getInt("cbtn_x", 0)
        composeDragY = sp.getInt("cbtn_y", 0)
    }

    private fun updateComposeBtn(ui: UiNode, screenH: Int) {
        val comp = ScanLogic.findComposer(ui, screenH)
        // v0.2.1（用户定案）：x 与对方头像列对齐（输入框左缘 154 仍偏右压消息区视觉）
        val btnX = ScanLogic.avatarColumnX(ui, resources.displayMetrics.widthPixels)
        if (comp == null && !composeDragged) {
            if (composeWin != null) Log.d(TAG, "compose btn removed (no composer)")
            removeComposeBtn(); return
        }
        if (composeWin == null) Log.d(TAG, "composer found: ${comp?.idSuffix}|${comp?.className?.substringAfterLast('.')} b=${comp?.left},${comp?.top},${comp?.right},${comp?.bottom}")
        composeNodeRaw = (comp?.raw) as? AccessibilityNodeInfo
        val tv = composeTv
        if (tv == null || composeWin == null) {
            val btn = TextView(this).apply {
                text = "译"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.parseColor("#25d366")); cornerRadius = dp(20).toFloat()
                }
                setOnClickListener { onComposeTranslate() }
                // v0.3.5 ④：长按 400ms 起拖动（普通点按零影响：tap 不满足时长阈值）
                var downX = 0f; var downY = 0f; var origX = 0; var origY = 0
                var dragging = false; var longPressFired = false
                val lpDrag = Runnable {
                    longPressFired = true
                    dragging = true
                    isHapticFeedbackEnabled = true
                    performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                }
                setOnTouchListener { v, e ->
                    val lp = v.layoutParams as android.view.WindowManager.LayoutParams
                    when (e.actionMasked) {
                        android.view.MotionEvent.ACTION_DOWN -> {
                            downX = e.rawX; downY = e.rawY; origX = lp.x; origY = lp.y
                            longPressFired = false
                            main.postDelayed(lpDrag, 400)
                            true
                        }
                        android.view.MotionEvent.ACTION_MOVE -> {
                            if (!longPressFired) {
                                // 400ms 内先动了 = 用户只是（快速）点按/滚动，取消长按判定
                                if (kotlin.math.abs(e.rawX - downX) > dp(10) || kotlin.math.abs(e.rawY - downY) > dp(10)) {
                                    main.removeCallbacks(lpDrag)
                                }
                                true
                            } else {
                                lp.x = (origX + e.rawX - downX).toInt()
                                lp.y = (origY + e.rawY - downY).toInt()
                                runCatching { wm.updateViewLayout(v, lp) }   // 拖动期只挪视觉
                                true
                            }
                        }
                        else -> {
                            main.removeCallbacks(lpDrag)
                            val moved = dragging && (kotlin.math.abs(e.rawX - downX) > dp(10) ||
                                kotlin.math.abs(e.rawY - downY) > dp(10))
                            dragging = false
                            if (moved) {
                                composeDragged = true; composeDragX = lp.x; composeDragY = lp.y
                                getSharedPreferences("wtrans", MODE_PRIVATE).edit()
                                    .putBoolean("cbtn_drag", true).putInt("cbtn_x", lp.x).putInt("cbtn_y", lp.y).apply()
                                logBoth("译球已固定: ${lp.x},${lp.y}")   // logcat+LogBuf 双通道（e2e E27 断言）
                                // touchableRegion 不跟 updateViewLayout（v0.1.9 实证）→
                                // remove+re-add 重建窗口让热区落到新位
                                runCatching { wm.removeView(v) }
                                composeWin = null
                                updateComposeBtn(ui, screenH)
                                true
                            } else if (longPressFired) true
                            else { v.performClick(); true }   // 普通点按→手动触发一次并消费 UP
                            // （返回 false 会让 View.onTouchEvent 再 performClick 一次=点「译」双重触发）
                        }
                    }
                }
            }
            val params = android.view.WindowManager.LayoutParams(
                dp(40), dp(40),
                android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                if (composeDragged) {           // v0.3.5 ④：用户位优先
                    x = composeDragX; y = composeDragY
                } else {
                    // v0.2.1（用户定案）：与接收方头像列齐平
                    x = btnX
                    y = maxOf((comp?.top ?: screenH) - dp(48), dp(96))
                }
                flags = flags or android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            }
            runCatching { wm.addView(btn, params) }.onFailure {
                Log.e(TAG, "compose addView failed: ${it.message}")
                composeWin = null; composeTv = null
                return
            }
            composeWin = btn; composeTv = btn
        } else if (!composeDragged) {
            // v0.1.9 e2e 双次实证：TYPE_ACCESSIBILITY_OVERLAY 窗口 updateViewLayout 挪位后
            // touchableRegion 不跟（frame=[221,1842] 而 region 停 [221,1762]，点新位全 miss；
            // 换新构造 LayoutParams 同样无效）→ 位置变化只能 remove+re-add 重建窗口。
            val nx = btnX                            // v0.2.1：头像列对齐（与新建分支一致）
            val ny = maxOf((comp?.top ?: screenH) - dp(48), dp(96))
            val lp = tv.layoutParams as android.view.WindowManager.LayoutParams
            if (lp.x != nx || lp.y != ny) {
                Log.d(TAG, "compose btn reposition ${lp.x},${lp.y} -> ${nx},${ny}")
                runCatching { wm.removeView(tv) }
                composeWin = null   // 走上面的重建分支（listener 随新 TextView 重挂）
                updateComposeBtn(ui, screenH)
            }
        }
    }

    /** 双通道日志：logcat（e2e 断言）+ LogBuf（真机导出取证）。 */
    private fun logBoth(msg: String) { Log.i(TAG, msg); LogBuf.log(msg) }

    /** v0.3.5 run-3 E10 取证：阈值 45 把普通 54 字消息也拖进 2.5s 探测窗口（见
     *  WaTranslateService.onAccessibilityEvent 抑制链路），探测期间真机截断点=恰好
     *  80 字符（d9e7 日志实证），取 76——只有真正疑似被截断的消息才注入长按。
     *  v0.3.6：判据改为【正文】≥76——真机点击事件的合并文本含昵称/电话/角标
     *  （15 字正文合并后 107 字），旧版直接量合并长度 → 真机 15 连发误触发。 */
    private val truncTrigger = 76

    /** 点击判定（事件线程内同步调用，node 尚未 recycle）：正文够长（有截断嫌疑）
     *  → 节点动作复刻「长按→弹菜单→点复制」。映射已知 → 绝不注入长按（防骚扰）。 */
    private fun tapCandidate(node: AccessibilityNodeInfo, clickedText: String, bubbleRect: Rect?, pkg: String) {
        if (ScanLogic.bodyOf(clickedText).length < truncTrigger) return
        if (tapCopyBusy) { Log.d(TAG, "tapCopy busy, skip"); return }
        val full = FullText.resolve(clickedText)
        // 全文幂等=点击文本本身就是全文（scan 已映射过，候选是全文形态）→
        // 截断形态必然也已在映射里，绝不再注入长按（防骚扰）。
        if (full != null && full != clickedText) { main.post { applyFullText(clickedText, full, "map", bubbleRect, pkg) }; return }
        if (full == clickedText) return
        startTapCopy(node, clickedText, bubbleRect, pkg)
    }

    /** v0.3.6 节点动作复刻（用户提出「找 WhatsApp 复制原理复刻」，取证=diag14 树）：
     *  旧版 = dispatchGesture 600ms 触摸式长按——MIUI 对注入手势的长按判定节流/吞没
     *  （9/24 真机日志 4×「菜单未弹出」），且路过表情条/多选模式（用户截图骚扰源头）。
     *  新版 = performAction(ACTION_LONG_CLICK) 直调气泡容器的长按处理器：不经屏幕、
     *  与用户手指长按走同一条 View 代码路径，WhatsApp 弹的就是原版上下文菜单
     *  （diag14 实证「复制」= 文字节点 + clickable 父，现有 findCopyMenuItem/doCopyTap
     *  双通道零改动兼容）。anchor 在文本节点上溯 6 层找 longClickable 容器。 */
    private fun startTapCopy(node: AccessibilityNodeInfo, clickedText: String, bubbleRect: Rect?, pkg: String) {
        if (android.os.Build.VERSION.SDK_INT < 24) return
        // v0.3.9 R1（真机取证 9/25：13:08:42/13:09:32 两轮长按弹出的是【文字选择】
        // 工具条 diag17 type=2099「全部选中/复制/请求文本选择」，不是消息菜单）：
        // 正文 TextView 自身的长按处理器=文字选择，点它会把对方气泡长按进多选 →
        // 表情条残留+气泡变灰（用户截图）。改为【跳过 TextView 本体】，从 parent
        // 上溯找【气泡级】longClickable（rect 覆盖且大于点击条目的容器=消息按钮层，
        // diag18 b=122,436,1040,556 那层）——它的长按=原版消息上下文菜单（含真「复制」）。
        var anchor: AccessibilityNodeInfo? = runCatching { node.parent }.getOrNull()
        var hops = 0
        var longClicked = false
        val tb = Rect().also { node.getBoundsInScreen(it) }
        while (anchor != null && hops < 6) {
            val a = anchor   // 不可变局部：闭包捕获可变 var 会挡 smart-cast
            if (a.isLongClickable && bubbleLevel(a, tb)) {
                longClicked = runCatching {
                    a.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
                }.getOrDefault(false)
                if (longClicked) break
            }
            anchor = runCatching { a.parent }.getOrNull(); hops++
        }
        if (!longClicked && node.isLongClickable) {
            // 兜底：找不到气泡层（OEM 结构怪）→ 退回旧行为按正文长按（可能弹文字
            // 选择菜单，R2 的窗口过滤仍会拦住假「复制」，不会误点进多选）。
            longClicked = runCatching {
                node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
            }.getOrDefault(false)
        }
        if (!longClicked) {
            logBoth("tap-copy 放弃: 长按动作不可用(${clickedText.length}字)")
            return
        }
        tapCopyBusy = true
        tapCopyRect = bubbleRect
        tapCopyPkg = pkg
        logBoth("tap-copy 启动: ${clickedText.take(18)}…(${clickedText.length}字)")
        val poll = object : Runnable {
            var ticks = 0
            override fun run() {
                ticks++
                val item = findCopyMenuItem(pkg)
                if (item != null) { doCopyTap(item, clickedText); return }
                if (ticks >= 8) {   // 8×250ms=2s 无菜单：长按处理器没接/菜单文案不认识
                    // 残局清理：WhatsApp 长按菜单失败的路径=直接进多选模式（用户 9/24 截图
                    // 实证：顶部 1 ▸ ⭐ 🗑 ⋮ 工具栏 + 表情条）。BACK 恰好退一层（多选/表情条
                    // 收起，聊天页保留）；菜单其实没弹出时 BACK 同样只退弹层，无双退风险。
                    tapCopyBusy = false
                    endTapCopyCleanup()
                    logBoth("tap-copy 放弃: 未见复制菜单(降级为截断文本)")
                    dumpWindowDiag()   // v0.3.5 取证：菜单到底没弹/在哪个窗/层级太深
                    return
                }
                main.postDelayed(this, 250)
            }
        }
        tapCopyPending = poll
        main.postDelayed(poll, 400)   // 菜单出现至少要点时间，先等 400ms 再轮询
    }
    
    /** v0.3.9 R2（真机取证 9/25 两条假「复制」失败链）：正牌 context menu 的硬标记
     *  =「复制」节点祖先链有 MenuItem 类（framework/appcompat ListMenuItemView，E26
     *  testhost 同源结构）。文字选择工具条（type=2099「全部选中/复制/请求文本选择」）
     *  与聊天正文里的「复制」二字都无此标记 → 绝不点（点了=误入多选/把手乱飞）。
     *  另外跳过【自家 overlay 窗】：译文行若恰好显示 copy/salin（用户消息翻译而来），
     *  旧版会自点自的行。a11y 窗口类型区分不了 PopupMenu（也是 APPLICATION），
     *  弃用窗口分区，只认结构标记。 */
    // v0.4.0 F1：本轮「复制」命中是否来自多选顶栏（=点完 selection 态仍在，需退层）。
    private var copyHitTopBar = false

    private fun findCopyMenuItem(pkg: String): AccessibilityNodeInfo? {
        copyHitTopBar = false
        val roots = ArrayList<AccessibilityNodeInfo>()
        runCatching {
            windows.forEach { w ->
                val r = w.root ?: return@forEach
                val rp = r.packageName?.toString() ?: ""
                if (rp != packageName && rp == pkg) roots.add(r)   // 只认被长按 app 的窗
                else runCatching { r.recycle() }
            }
        }
        if (roots.isEmpty()) runCatching {
            rootInActiveWindow?.let { roots.add(it) }
        }
        return copyMenuItemIn(roots)
    }

    /** v0.3.6：从给定根扫「复制」菜单项（全窗 roots 与长按后重取的 anchor 窗都走这里）。
     *  v0.3.9 R2：命中「复制」二字还不够——必须 menuIsh（祖先 MenuItem）才算正牌菜单项。 */
    private fun copyMenuItemIn(roots: List<AccessibilityNodeInfo>): AccessibilityNodeInfo? {
        var hit: AccessibilityNodeInfo? = null
        var fakeSeen = false
        var fakeLog = 0
        // v0.4.0 F1：本轮全窗是否处于多选态（一次判定，walk 内复用；判据=
        // 裸整数计数+顶栏动作簇双条件，正常聊天页恒 false）。
        val multiSelectState = detectMultiSelect()
        fun copyText(n: AccessibilityNodeInfo): String {
            val cands = listOf(n.text?.toString() ?: "", n.contentDescription?.toString() ?: "")
            return cands.map { it.trim().lowercase() }.firstOrNull { it.isNotEmpty() } ?: ""
        }
        fun isCopyLabel(s: String): Boolean =
            s == "复制" || s == "copy" || s == "salin" || s.startsWith("copy to") ||
                s == "复制到" || s == "salin ke" || s.startsWith("复制") || s.startsWith("salin")
        /** 节点文字（自身 text/contentDescription + 直接子节点一层，覆盖 PopupMenu
         *  「clickable 父 + 文字子 TextView」与「MenuItem 自带 text」两种结构）。 */
        fun labelOf(n: AccessibilityNodeInfo): String {
            val own = copyText(n)
            if (own.isNotEmpty()) return own
            for (i in 0 until minOf(n.childCount, 8)) {
                val c = n.getChild(i) ?: continue
                val s = copyText(c)
                runCatching { c.recycle() }
                if (s.isNotEmpty()) return s
            }
            return ""
        }
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (hit != null || depth > 24) return
            if (n.isVisibleToUser) {
                val label = copyText(n)
                if (isCopyLabel(label) || (n.isClickable && isCopyLabel(labelOf(n)))) {
                    if (hasMenuItemAncestor(n)) { hit = n; return }
                    // v0.4.0 F1（真机取证 9/25：新版 WhatsApp 长按直接进多选态，顶栏
                    // 「1 ▸ ⇉ 📋 ⋮」里就有真「复制」——不是 PopupMenu，R2 骨架判据
                    // 把它当假「复制」拦掉 → 2s 轮询耗尽 → 表情条残留）。放行条件三重
                    // 与：多选态成立（计数+动作簇双条件）+ 节点在顶栏带（90≤top，
                    // bottom≤250，同 P1 判据①）+ 「复制」标签成立。正常聊天页/选词
                    // 工具条（选词条无计数节点、且是 type=2099 横排非顶栏带）进不来。
                    val nb = Rect().also { n.getBoundsInScreen(it) }
                    if (multiSelectState && nb.top >= 90 && nb.bottom <= 250 &&
                        (labelOf(n).isNotEmpty() || label.isNotEmpty())) {
                        copyHitTopBar = true
                        hit = n
                        logBoth("tap-copy F1: 多选顶栏「复制」放行 (y=${nb.top}..${nb.bottom})")
                        return
                    }
                    fakeSeen = true   // 假「复制」（选词工具条/正文文字）——只记日志继续找
                    // v0.3.9 取证：打印祖先链类名——E26 首跑实证 PopupMenu 真菜单
                    // 也会被误拦（15:48:04 全过滤），必须看清真机/测试宿主的类名形态。
                    if (fakeLog < 2) {
                        fakeLog++
                        val chain = StringBuilder()
                        var aa: AccessibilityNodeInfo? = n
                        var hh = 0
                        while (aa != null && hh < 8) {
                            chain.append("/").append(aa.className?.toString() ?: "?")
                            val curAa = aa
                            val nx = runCatching { curAa.parent }.getOrNull()
                            aa = if (nx !== curAa) nx else null; hh++
                        }
                        logBoth("tap-copy 假复制祖先链: " + chain.toString().take(300))
                    }
                }
            }
            for (i in 0 until minOf(n.childCount, 120)) n.getChild(i)?.let { walk(it, depth + 1) }
        }
        for (r in roots) { walk(r, 0); runCatching { r.recycle() }; if (hit != null) break }
        if (fakeSeen && hit == null) logBoth("tap-copy 过滤: 见到假「复制」(非菜单项)，未点")
        return hit
    }

    /** v0.3.9 R2：节点祖先链里是否为菜单骨架（取证=E26 祖先链日志 15:48：
     *  framework PopupMenu 的 a11y 类名【不含 MenuItem】，真实形态=
     *  TextView←RelativeLayout←LinearLayout←LinearLayout←android.widget.ListView）。
     *  三类假「复制」的排除依据：
     *    ① 文字选择工具条（type=2099 横排按钮条）——无 ListView/MenuItem 祖先；
     *    ② 聊天正文里的「复制」二字——消息容器 RecyclerView 类名不是
     *       android.widget.ListView（13:09:32 事故线）；
     *  真菜单两种骨架都认（framework ListView / appcompat MenuPopup ListView 系）。 */
    private fun hasMenuItemAncestor(n: AccessibilityNodeInfo): Boolean {
        var a: AccessibilityNodeInfo? = n
        var hops = 0
        while (a != null && hops < 8) {
            val cur = a   // 不可变局部：runCatching lambda 捕获 var 挡 smart-cast
            val cls = cur.className?.toString() ?: ""
            if (cls == "android.widget.ListView" || cls.contains("MenuItem") ||
                cls.contains("PopupListItem")) return true
            a = runCatching { cur.parent }.getOrNull(); hops++
        }
        return false
    }

    /** v0.3.9 R1：容器是否为【气泡层】（长按它弹消息菜单，而不是文字选择菜单）。
     *  判据=矩形【严格大于】正文文本矩形（每边至少 4px；同矩形或只宽 1px=还是文字
     *  层/view 内部结构）。testhost E26 面：可长按 TextView 外层是 MATCH_PARENT 的
     *  FrameLayout box → 天然满足，链路不变。 */
    private fun bubbleLevel(a: AccessibilityNodeInfo, textB: Rect): Boolean {
        val ab = Rect().also { a.getBoundsInScreen(it) }
        val bigger = ab.left <= textB.left - 4 && ab.right >= textB.right + 4 &&
            ab.top <= textB.top - 4 && ab.bottom >= textB.bottom + 4
        if (!bigger) return false
        // 面积防呆：整屏/整列表层的长按弹的是别的东西（空白长按=页面级菜单），跳过。
        val screen = resources.displayMetrics
        return ab.width().toLong() * ab.height() <=
            screen.widthPixels.toLong() * screen.heightPixels * 3 / 4
    }

    private fun doCopyTap(item: AccessibilityNodeInfo, clickedText: String) {
        tapCopyPending?.let { main.removeCallbacks(it) }; tapCopyPending = null
        // v0.3.5 run-8 取证：坐标手势会被【我们自己的悬浮译文行】挡住——行窗口贴在气泡
        // 下方、菜单项也弹在那一带，TYPE_ACCESSIBILITY_OVERLAY z-order 高于 PopupMenu，
        // dispatchGesture 与 adb input tap 双双落空（日志实证 row click ignored +
        // 手工 tap 同坐标菜单不关）。→ 改走 ACTION_CLICK 节点通道：从文字节点上溯
        // clickable 祖先直接 performAction——不经触摸屏幕，遮挡无关。失败才退手势。
        var clicked = false
        var target: AccessibilityNodeInfo? = item
        var hops = 0
        while (target != null && hops < 6 && !clicked) {
            val t = target!!
            if (t.isClickable) {
                clicked = runCatching { t.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
                    .getOrDefault(false)
            }
            if (!clicked) { target = runCatching { t.parent }.getOrNull(); hops++ }
        }
        if (!clicked) {   // 兜底：整链无可点击祖先（OEM 菜单怪结构）→ 旧坐标手势
            val b = Rect().also { item.getBoundsInScreen(it) }
            val path = android.graphics.Path().apply { moveTo(b.exactCenterX(), b.exactCenterY()) }
            val stroke = GestureDescription.StrokeDescription(path, 0, 80)
            clicked = runCatching { dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null) }
                .getOrDefault(false)
            if (!clicked) { tapCopyBusy = false; logBoth("tap-copy 放弃: 菜单点击派发失败"); return }
        }
        // 等剪贴板落定后走焦点探针读取（readClipboardViaFocus，AOSP 权限实锤）。
        // v0.3.6 新鲜度轮询（9/24 真机取证：点过「复制」后读到的还是用户按「导出诊断」
        // 写入的旧文本 →「剪贴板非本条」拒绝）：每 300ms 探一次共 5 次，判据收紧为
        // 【clean 全文以本条正文开头 + 明显更长】（旧诊断快照/无关剪贴板必然出局）；
        // 5 次没有 → 静默降级（绝不用旧内容）。
        logBoth("tap-copy 点复制: 已派发")
        main.postDelayed({
            val body = ScanLogic.bodyOf(clickedText)
            var tries = 0
            val poll = object : Runnable {
                override fun run() {
                    tries++
                    readClipboardViaFocus { clip ->
                        val c = ScanLogic.clean(clip ?: "")
                        if (c.length > body.length + 8 && startsWithLoose(c, body)) {
                            tapCopyBusy = false
                            applyFullText(clickedText, c, "clip", tapCopyRect, tapCopyPkg ?: "com.whatsapp")
                            // v0.4.0 F1：复制来源=多选顶栏按钮时，点完 WhatsApp 仍停在
                            // selection 态（计数栏+表情条不收）→ 走逐层退链收掉。
                            // PopupMenu 通道（copyHitTopBar=false）维持旧行为：不 BACK
                            // （点过菜单项再 BACK 会把聊天页踹出去，v0.3.5 写码自查）。
                            if (copyHitTopBar) exitMultiSelectStaged()
                            return@readClipboardViaFocus
                        }
                        if (tries >= 5) {
                            tapCopyBusy = false
                            endTapCopyCleanup()
                            val why = when {
                                c.isBlank() -> "剪贴板不可读/为空"
                                c.length <= body.length + 8 -> "无增益(${c.length}字)"
                                else -> "非本条(${c.take(12)}…)"
                            }
                            logBoth("tap-copy 放弃: $why")
                            return@readClipboardViaFocus
                        }
                        main.postDelayed(this, 300)
                    }
                }
            }
            main.post(poll)
            // 不按返回键收菜单：点「复制」后 WhatsApp 自己关掉 context menu，
            // 此时 GLOBAL_ACTION_BACK 会把聊天页踹出去（写码自查否决）。
        }, 700)
    }

    /** v0.3.6：探测放弃后的残局清理。真机取证（9/24 用户截图）：长按若被误判成多选，
     *  WhatsApp 进【多选模式】（顶部「1 ▸ ⭐ 🗑 ⋮」计数工具栏 + 气泡表情条）。
     *  判据必须避开【正常聊天页顶栏】的误伤：聊天页头部恒有 ⋮「更多选项」按钮，若按
     *  「options/关闭/更多选项」判存在会每轮误命中 → 无脑 BACK 把用户踹出聊天（严重回归）。
     *  改用多选的**独有信号**：顶栏里一个「裸整数」文本节点（选择计数 "1"/"2"），正常
     *  聊天页顶栏是标题文字（"Group …"）绝不含裸数字胶囊。命中才 BACK 一次退层；
     *  不命中则什么都不做（宁可留着菜单让用户手动关，也绝不误退）。 */
    private fun endTapCopyCleanup() {
        // v0.3.7 F4（真机 Q3 取证：TPM 报告群翻译完成即"自动退群"）：旧版数顶栏
        // 「裸整数」判多选——TPM 消息编号气泡「1/2/3」恰在顶部含数字文本 → 误判 →
        // BACK 把用户踹出聊天。改**零启发式**：BACK 唯一前提=有独立 context menu
        // 窗口（窗口根类名 PopupMenu/MenuPopup 系=我们长按弹出、没被消费的那个）。
        // 多选模式没有这种窗口 → 什么都不做（宁可留菜单让用户手动关，
        // 绝不再"猜"屏幕状态按返回键）。
        val menuOpen = runCatching {
            windows.asSequence().mapNotNull { w -> w.root }.any { r ->
                val cls = r.className?.toString() ?: ""
                val hit = cls.contains("PopupMenu") || cls.contains("MenuPopup") ||
                    cls.contains("DropDownMenu") || cls.contains("ActionMenuPopup")
                runCatching { r.recycle() }
                hit
            }
        }.getOrDefault(false)
        if (menuOpen) {
            runCatching { performGlobalAction(GLOBAL_ACTION_BACK) }
            logBoth("tap-copy 残局: context menu 窗口在位 → BACK 收菜单")
            return
        }
        // v0.3.8 P1（真机取证 9/25 问题①：注入的「复制」点击落偏 → 误入多选模式，
        // 顶部「1 ▸ ⭐ 🗑 ⇉ ⋮」计数工具栏 + 表情条残留 + 选中气泡变色，用户两张截图）。
        // diag17 vs diag18 结构差实证的双条件 AND 判据（仅 tap-copy 放弃后 2s 内调用一次，
        // 不是每轮扫描——9/24 TPM「自动退群」事故的根因是每轮启发式，此处保留该教训）：
        //   ① 顶栏带（90≤y_top 且 y_bottom≤250）内有裸整数 TextView（选择计数 "1"/"12"）；
        //   ② 同带内 LinearLayoutCompat 直接子节点里 clickable ≥4（回复/星标/删除/转发/更多
        //      共 5 个）；正常聊天顶栏固定 3 个动作（搜索/会话菜单/头像⋮）。
        // TPM 报告的编号气泡「1/2/3」在消息 ListView（y≥248 起）里，两条件都不满足。
        val multiSelect = detectMultiSelect()
        if (multiSelect) exitMultiSelectStaged()
    }

    /** v0.4.0 F3/F1：多选态逐层退链（从 endTapCopyCleanup 抽出；顶栏「复制」点成功后
     *  WhatsApp 仍停留 selection 态——表情条+计数栏不自动收，两处都要走这条链）。
     *  v0.4.0 F3（真机取证 9/25：表情条残留=单次 BACK 只退一层；13:08:42/13:09:03
     *  两轮实证）。逐层退：BACK → 400ms 后用【新树】复检，仍在多选再 BACK，
     *  封顶 3 层（计数栏/表情条/选区），退出多选或封顶即停。
     *  护栏不变：只在 tap-copy 放弃/顶栏复制流程内调用一次链，绝不被每轮扫描驱动
     *  （9/24 TPM「自动退群」教训）；每步都用双条件判据，非启发式猜窗。 */
    private fun exitMultiSelectStaged() {
        var layer = 0
        val step = object : Runnable {
            override fun run() {
                if (layer >= 3) { logBoth("tap-copy 残局: 退层封顶(3) 停手"); return }
                layer++
                runCatching { performGlobalAction(GLOBAL_ACTION_BACK) }
                logBoth("tap-copy 残局: 多选态 → BACK ${layer}层")
                main.postDelayed({
                    if (tapCopyBusy) return@postDelayed   // 新一轮探测已启动，别抢
                    if (detectMultiSelect()) main.postDelayed(this, 400)
                    // 已退出多选：停手（正常聊天顶栏过不了双条件，不会再退）
                }, 400)
            }
        }
        step.run()
    }

    /** v0.3.9 R3：多选模式双条件判据（P1 原样拆出，供首判+复检两处调用）。 */
    private fun detectMultiSelect(): Boolean = runCatching {
        windows.asSequence().mapNotNull { w -> w.root }.any { r ->
            val hit = headerBareCounter(r) && headerActionCluster(r)
            runCatching { r.recycle() }
            hit
        }
    }.getOrDefault(false)

    /** v0.3.8 P1 判据①：顶栏带内「裸整数」文本节点（多选计数）。 */
    private fun headerBareCounter(root: AccessibilityNodeInfo): Boolean {
        var hit = false
        fun walk(n: AccessibilityNodeInfo?, d: Int) {
            if (n == null || hit || d > 10) return
            val b = Rect().also { n.getBoundsInScreen(it) }
            val cls = n.className?.toString() ?: ""
            val t = n.text?.toString()?.trim() ?: ""
            if (b.top >= 90 && b.bottom <= 250 && cls.endsWith("TextView") &&
                t.isNotEmpty() && t.length <= 3 && t.all { it.isDigit() }) { hit = true; return }
            for (i in 0 until minOf(n.childCount, 120)) n.getChild(i)?.let { walk(it, d + 1) }
        }
        walk(root, 0)
        return hit
    }

    /** v0.3.8 P1 判据②：顶栏带内 clickable 直接子节点 ≥3 的 LinearLayoutCompat。
     *  v0.4.0 F2（真机取证 9/25：部分机型/版本多选顶栏只有 3 个动作（回复/删除/更多），
     *  ≥4 恒不成立 → P1/F 链全灭，表情条残留）：阈值 4→3。误伤防线仍在：AND 判据①
     *  裸整数计数节点（正常顶栏是标题文字）+ 本判据限顶栏带 90≤y_top/bottom≤250 +
     *  LinearLayoutCompat 类名三重约束；TPM 编号气泡在 y≥248 的消息 ListView 里不命中。 */
    private fun headerActionCluster(root: AccessibilityNodeInfo): Boolean {
        var hit = false
        fun walk(n: AccessibilityNodeInfo?, d: Int) {
            if (n == null || hit || d > 10) return
            val b = Rect().also { n.getBoundsInScreen(it) }
            val cls = n.className?.toString() ?: ""
            if (b.top >= 90 && b.bottom <= 250 && cls.contains("LinearLayoutCompat")) {
                var clickable = 0
                for (i in 0 until minOf(n.childCount, 12)) {
                    val kid = n.getChild(i) ?: continue
                    if (kid.isClickable) clickable++
                }
                if (clickable >= 3) { hit = true; return }
            }
            for (i in 0 until minOf(n.childCount, 120)) n.getChild(i)?.let { walk(it, d + 1) }
        }
        walk(root, 0)
        return hit
    }

    /** v0.3.5：剪贴板读取的焦点探针通道。
     *  AOSP ClipboardService.clipboardAccessAllowed 实锤（main 分支 L1214+）：
     *  OP_READ_CLIPBOARD 只放行【持窗口焦点的 uid】/默认IME/ContentCapture/Autofill——
     *  普通 AccessibilityService 读 primaryClip 在 Android 10+ 一律 null。
     *  解法=读的瞬间加一个 1×1 屏外可聚焦 overlay 窗（IME 同款技巧），拿到窗口焦点后
     *  uid focused → 读合法 → 立刻移除窗口归还焦点。任何一步失败 → null（静默降级）。
     *  cb 主线程恰好回调一次（焦点事件 / 200ms 兜底 / 加窗失败 三选一）。 */
    private fun readClipboardViaFocus(cb: (String?) -> Unit) {
        fun readClip(): String? = runCatching {
            getSystemService(ClipboardManager::class.java)?.primaryClip?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)?.text?.toString()
        }.getOrNull()
        var done = false
        val probe = View(this).apply {
            isFocusable = true
            isFocusableInTouchMode = true
        }
        fun finish(clip: String?) {
            if (done) return; done = true
            probe.onFocusChangeListener = null
            runCatching { wm.removeView(probe) }
            cb(clip)
        }
        probe.onFocusChangeListener = View.OnFocusChangeListener { _, has ->
            if (has) finish(readClip())
        }
        val lp = android.view.WindowManager.LayoutParams(
            1, 1,
            android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START; x = -4; y = -4 }
        val added = runCatching { wm.addView(probe, lp) }.isSuccess
        if (!added) { finish(null); return }
        runCatching { probe.requestFocus() }
        probe.postDelayed({ finish(readClip()) }, 350)
    }

    /** v0.3.5 E26 取证：放弃探测时把全部 a11y 窗口里【任何】带「复制」字样的节点打出来
     *  （不限 clickable——菜单可能弹了但结构不认识；也可能根本没弹——长按被吞）。 */
    private fun dumpWindowDiag() {
        main.post {
            logBoth("tap-copy 诊断: locale=${java.util.Locale.getDefault()}")
            val roots = ArrayList<Pair<String, AccessibilityNodeInfo?>>()
            runCatching { windows.forEach { w -> val r = w.root; roots.add((r?.packageName?.toString() ?: "?") to r) } }
            val sb = StringBuilder()
            var total = 0
            for ((pkg, r) in roots) {
                if (r == null) continue
                total++
                fun walk(n: AccessibilityNodeInfo, d: Int) {
                    if (d > 26) return
                    val t = n.text?.toString() ?: ""
                    val cd = n.contentDescription?.toString() ?: ""
                    if (t.contains("复制") || cd.contains("复制") || t.lowercase().contains("copy")) {
                        val b = Rect().also { n.getBoundsInScreen(it) }
                        sb.append(" [$pkg|clk=${n.isClickable} vis=${n.isVisibleToUser} d=$d b=${b.toShortString()}] '${t.take(8)}|$cd'")
                    }
                    for (i in 0 until minOf(n.childCount, 120)) n.getChild(i)?.let { walk(it, d + 1) }
                }
                walk(r, 0)
                runCatching { r.recycle() }
            }
            logBoth("tap-copy 诊断: 窗口数=$total 复制节点=${if (sb.isEmpty()) "无(菜单未弹出)" else sb.toString().take(500)}")
        }
    }

    /** 宽松前缀判定：归一化（去空白/小写）后截断文本应为全文前缀（容 5% 尾部差异）。 */
    private fun startsWithLoose(full: String, truncated: String): Boolean {
        val f = Echo.norm(full); val t = Echo.norm(truncated)
        if (!f.startsWith(t.take(t.length - t.length / 20))) return false
        return true
    }

    private fun dismissContextMenu() {
        // 保留但当前不调用：点「复制」后 WhatsApp 自己收起 context menu，
        // 我们若 GLOBAL_ACTION_BACK 会把聊天页踹出去。仅留作 OEM 菜单不自动关时的应急口。
        runCatching { performGlobalAction(GLOBAL_ACTION_BACK) }
    }

    /** 全文到手（映射命中 或 tap-copy 剪贴板）→ 行重建为全文键并重译。
     *  v0.3.6 E26 修复：长按探测吞掉了触发点击（菜单弹出时本轮 scan 找不到候选 →
     *  截断形态译文行从未注入）→ 旧版 `rows.firstOrNull ?: return` 静默断链。
     *  行找不到时不再 return：用点击时留存的气泡矩形造 anchor 直接注入全文行。 */
    private fun applyFullText(truncated: String, full: String, via: String, bubbleRect: Rect?, pkg: String) {
        FullText.record(truncated, full)
        logBoth("tap-copy ✓[$via]: ${truncated.length}→${full.length}字 ${full.take(14)}")
        main.post {
            val suffix = "|$pkg"
            val key = full.take(200) + suffix
            if (rows.any { it.anchorKey == key }) return@post   // 全文行已在屏上（scan 映射通道建过）→ 幂等
            val r = rows.firstOrNull { it.src == truncated }
            if (r != null) {
                logBoth("全文重译: ${full.take(18)}")
                engine.forget(truncated)         // 旧截断译文作废（不进缓存复活通道）
                closedKeys.remove(r.anchorKey)
                tappedKeys.remove(r.anchorKey); tappedKeys.add(key)  // v0.3.8 P2：截断键→全文键迁移（scan 候选已 resolve 成全文形态）
                rows.remove(r); removeRow(r)     // 行重建：src/键都换全文
                val c = Candidate(full, r_anchorStub(r), false)
                injectRow(c, key)
            } else if (bubbleRect != null && !closedKeys.contains(key)) {
                // v0.3.6：探测链路里没有旧行（长按吞点击）→ 以气泡矩形为锚直接建全文行
                logBoth("全文建行: ${full.take(18)}")
                closedKeys.remove(key)
                tappedKeys.add(key)              // v0.3.8 P2：建行即视为已点译
                val anchor = UiNode(full, "", "", bubbleRect.left, bubbleRect.top, bubbleRect.right, bubbleRect.bottom, false)
                injectRow(Candidate(full, anchor, false), key)
            }
        }
    }

    // 换键重建行只需要 anchor 的几何（injectRow 读 c.anchor.left/top/bottom）；
    // 用旧行的 box 造一个脱树 UiNode（raw=null，不参与任何动作）。
    private fun r_anchorStub(r: Row): UiNode =
        UiNode(r.src, "", "", r.box.left, r.box.top, r.box.right, r.box.bottom, false)

    private fun removeComposeBtn() {
        composeWin?.let { runCatching { wm.removeView(it) } }
        composeWin = null; composeTv = null
        composeNodeRaw = null
        // v0.3.5 修正（E20 run-4 过 / run-5,6,7 挂的回归根因）：这里绝不能清
        // composeOriginal/composeTranslatedSnapshot。按钮因一次扫描瞬时丢不到 composer
        // 就重建（run-7 日志：composer found + reposition 1111→1147），把「待还原」状态
        // 一起抹掉 → D 步再点读到译文 → 反向请求印尼→中文 KEEP → 拒写假死锁。
        // 还原对只在「新译周期覆盖写」和 detachAll（离开聊天会话）时作废，二者都会
        // 重新赋值或显式清理。
        composeBusy = false
    }

    /** 写回输入框：ACTION_SET_TEXT（Android 无障碍标准通道，WhatsApp Lexical 编辑器认它）。
     *  失败兜底：焦点节点逐字符走剪贴板粘贴不可靠 → 直接提示用户长按粘贴。 */
    private fun setComposerText(node: AccessibilityNodeInfo?, text: String): Boolean {
        if (node == null) return false
        val args = android.os.Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        var ok = runCatching { node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) }.getOrDefault(false)
        if (!ok) {   // 节点可能已失效（输入框重建）→ 先聚焦再试一次
            runCatching { node.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }
            ok = runCatching { node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) }.getOrDefault(false)
        }
        return ok
    }

    private fun composerCurrentText(): String =
        ScanLogic.clean(composeNodeRaw?.text?.toString() ?: "")

    /** v0.2.0（真机日志取证）：scan 缓存的节点 text 会滞后于用户最新输入
     *  （你打「早上好」→ 无新事件 → 旧快照还是「早上」→ 只翻出 pagi）。
     *  点「译」的瞬间必须重新抓一次输入框节点，EXTRA_DATA 通道优先于滞后的 text。 */
    private fun freshComposerNode(): AccessibilityNodeInfo? {
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return null
        try {
            val screenH = resources.displayMetrics.heightPixels
            val ui = root.toUi()   // toUi 把 raw 引用带进树，findComposer 命中即拿回活节点
            val comp = ScanLogic.findComposer(ui, screenH) ?: return null
            return comp.raw as? AccessibilityNodeInfo
        } finally {
            runCatching { root.recycle() }
        }
    }

    private fun readComposerText(node: AccessibilityNodeInfo?): String {
        if (node == null) return ""
        // v0.3.9 C1（真机取证 9/25：用户输入长文 → 点「译」只读到「今天」两字，
        // 日志 13:10:20 实证 text 通道严重滞后）。旧版只读 node.text 一个通道；
        // WhatsApp 输入框是自定义编辑器，无障碍 text 属性常不回写最新内容，
        // 真内容躺在 contentDescription（「文本编辑框, <内容>」形态）或子节点里。
        // 三通道全读、取最长者；顺带把各通道长度打进日志（下次一查便知哪层滞后）。
        val direct = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        var deepest = ""
        for (i in 0 until minOf(node.childCount, 8)) {
            val c = node.getChild(i) ?: continue
            val s = (c.text?.toString() ?: "").ifBlank { c.contentDescription?.toString() ?: "" }
            if (s.length > deepest.length) deepest = s
            runCatching { c.recycle() }
        }
        composerChannelDiag = "text=${direct.length} desc=${desc.length} kid=${deepest.length}"
        // desc 只在【带逗号前缀】形态（「文本编辑框, 内容」）时取内容段；无前缀逗号
        // 的 desc 常是纯标签（「文本编辑框」5 字）——直接当内容会把占位符翻译出去。
        // 例外兜底：三通道全空且 desc>8 字 → 可能是「desc 装全文」形态，才用它。
        val cut = desc.indexOfFirst { it == ',' || it == '，' }
        val descBody = if (cut in 1..40) desc.substring(cut + 1).trim() else ""
        val best = listOf(direct, descBody, deepest)
            .filter { it.isNotBlank() }
            .maxByOrNull { ScanLogic.clean(it).length }
            ?: if (desc.length > 8) desc else ""
        return ScanLogic.clean(best)
    }

    /** 三通道长度诊断（compose start 日志用），本周期一次性。 */
    private var composerChannelDiag: String = ""

    private fun onComposeTranslate() {
        val cached = composeNodeRaw ?: run { Log.i(TAG, "compose abort: no node"); return }
        val tv = composeTv ?: run { Log.i(TAG, "compose abort: no tv"); return }
        if (composeBusy) { Log.i(TAG, "compose abort: busy"); return }
        // 现读：成功则替换缓存引用（写回也用活节点）
        val node = freshComposerNode() ?: cached
        composeNodeRaw = node
        val current = readComposerText(node)
        Log.i(TAG, "compose start: len=${current.length} [$composerChannelDiag] \"" + current.take(20) + "\"")
        // 再点一次 = 还原原文（同扩展：当前内容==上次译文 → 写回 original）
        if (composeOriginal != null && current.isNotEmpty() && norm(current) == norm(composeTranslatedSnapshot ?: "")) {
            if (setComposerText(node, composeOriginal!!)) {
                // 本地镜像立即更新：node.text 停留在抓树时刻（performAction 不回写快照），
                // 用户「还原后秒再点译」会读到滞后译文→拿译文反向请求→假死锁（E20 ①型断点）。
                runCatching { node.text = composeOriginal }
                composeNodeRaw = node
                Log.i(TAG, "compose restore: " + composeOriginal!!.take(30))
                Log.i(TAG, "输入框还原↩: " + composeOriginal!!.take(24))   // v0.2.5 双通道（e2e 断言）
                LogBuf.log("输入框还原↩: " + composeOriginal!!.take(24))
                composeOriginal = null; composeTranslatedSnapshot = null
                main.post { tv.text = "译"; tv.setBackgroundColor(Color.TRANSPARENT) }
            }
            return
        }
        if (current.isEmpty()) { Log.i(TAG, "compose translate: empty input, ignore"); return }
        // v0.2.8 ①（真机 9/21「A009修理完成了吗？」5 连点全拒写死锁）：缓存值是学习哨兵
        // （KEEP/PN）时消息行还能点行重译，输入框没有出口 → 先清哨兵再真重译。
        // 9/21 死锁形态=第一次翻译模型回显→KEEP 落缓存→此后每次 cache hit 毫秒返回哨兵→永远拒写。
        if (engine.cachedSentinel(current)) {
            engine.forget(current)
            Log.i(TAG, "输入框重译(清哨兵): " + current.take(24))
            LogBuf.log("输入框重译(清哨兵): " + current.take(24))
        }
        composeBusy = true
        // v0.3.8 P4（真机需求 9/25 问题③）：点「译」后按钮显示等待秒数「…Ns」——
        // 气泡行早已有 v0.1.7「翻译中… Ns」跳动，输入框旧版只闪一个「…」，
        // 用户不知道在等服务器还是卡死。composeBusy 落回时停表（见下方回调）。
        main.post {
            tv.text = "…0s"
            composeTick = object : Runnable {
                var secs = 0
                override fun run() {
                    if (!composeBusy) return
                    secs++
                    tv.text = "…" + secs + "s"
                    main.postDelayed(this, 1000)
                }
            }
            main.postDelayed(composeTick!!, 1000)
        }
        LogBuf.log("输入框译: ${current.take(24)}")
        engine.translate(current, bypassCooling = true) { t, err ->  // v0.3.8 P3：用户主动点「译」无视 15s 冷却
            main.post {
                composeBusy = false
                composeTick?.let { main.removeCallbacks(it) }; composeTick = null  // v0.3.8 P4 停表
                if (t != null && t != Watch.PN_MARK && t != Watch.KEEP_MARK && !Echo.isEcho(current, t)) {
                    // 写回前再抓一次活节点（等待期间输入框可能被 WhatsApp 重建 → 旧引用失效）
                    val live = freshComposerNode() ?: node
                    if (setComposerText(live, t)) {
                        // 本地镜像同步（v0.3.5 同上）：写回后立刻再点（还原路径）读到的
                        // 必须是译文，而不是抓树时刻的原文快照。
                        runCatching { live.text = t }
                        composeNodeRaw = live
                        composeOriginal = current; composeTranslatedSnapshot = t
                        tv.text = "译"
                        Log.i(TAG, "compose result: " + current.take(20) + " -> " + t.take(30))
                        Log.i(TAG, "输入框写回✓: " + current.take(12) + " → " + t.replace('\n', '⎵').take(24))
                        LogBuf.log("输入框写回✓: " + current.take(12) + " → " + t.replace('\n', '⎵').take(24))
                    } else {
                        tv.text = "译"
                        Log.i(TAG, "compose set-text failed: " + current.take(20))
                        Log.i(TAG, "输入框写回✘ 节点失效: " + current.take(24))
                        LogBuf.log("输入框写回✘ 节点失效: " + current.take(24))
                        toastShort("已翻译，但写入输入框失败——长按译文选择复制粘贴")
                    }
                } else {
                    tv.text = "译"
                    Log.i(TAG, "compose fail: " + (err ?: t ?: "echo"))
                    Log.i(TAG, "输入框拒写: " + current.take(12) + " 原因=" +
                        (if (t == Watch.KEEP_MARK || t == Watch.PN_MARK) "保留原文"
                         else "失败[" + (err ?: "无结果").take(24) + "]"))
                    LogBuf.log("输入框拒写: " + current.take(12) + " 原因=" +
                        (if (t == Watch.KEEP_MARK || t == Watch.PN_MARK) "保留原文"
                         else "失败[" + (err ?: "无结果").take(24) + "]"))
                    toastShort(if (t == Watch.KEEP_MARK || t == Watch.PN_MARK) "模型对这条保留原文，未写入译文"
                        else "翻译失败：" + (err ?: "无结果").take(30))
                }
            }
        }
    }

    private fun toastShort(s: String) {
        main.post { runCatching { android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show() } }
    }

    private fun norm(s: String) = s.replace("\n", " ").trim()

    /** v0.3.1（真机取证 9/19：幽灵点击循环）：点输入框文字**不再触发翻译**，只消费点击。
     *  旧 v0.2.4 通道"命中输入框+有字→直接译"被 WhatsApp/IME 编辑期的自发 VIEW_CLICKED
     *  （移动光标改字、候选词上屏、自动更正）误触发：长中文消息一校对就自动翻译写回，
     *  中↔印来回翻转（日志 8 连击实锤）。输入框翻译入口只认「译」按钮（用户定案，无开关）。
     *  保留本函数：①消费落在输入框上的点击，避免退化成 tap miss 噪音污染归因；
     *  ②顺带完成 dump 重绑后「译」按钮的补装配（E18 取证 "compose abort: no node" 教训）。 */
    private fun tryComposerTap(crect: Rect?, ui: UiNode, screenH: Int): Boolean {
        if (crect == null || composeBusy) return false
        // 点击消费发生在 scan 前段、updateComposeBtn 之后段——本轮（或 dump 重绑后首轮）
        // composeNodeRaw/composeTv 可能还没装配 → 先补装配
        updateComposeBtn(ui, screenH)
        if (composeWin == null || composeTv == null) return false
        val comp = ScanLogic.findComposer(ui, screenH) ?: return false
        if (!ScanLogic.composerHit(crect.left, crect.top, crect.right, crect.bottom,
                comp.left, comp.top, comp.right, comp.bottom)) return false
        Log.d(TAG, "composer tap: consumed (v0.3.1 button-only)")
        return true
    }

    // ---------------- v0.2.2 监控埋点（v0.2.3 调整：drop 按内容去重） ----------------
    private var lastDropKey = ""            // drop 抽样 2s 节流键（同文本同原因不刷屏）
    private var lastDropAt = 0L
    // v0.2.3 M3c：真机取证 1268 条 drop 里 nick+phone 刷屏 746 条，挤爆 2000 环形——
    // 同一 kind+文本**整个会话只记一条**（取证要的是"有没有/是什么"，不是次数）。
    private val dropSeen = object : LinkedHashMap<String, Boolean>(256, 0.75f, false) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Boolean>?) = size > 512
    }
    private val measuredRows = HashSet<String>()   // 每行只量一次截断

    /** 点击没命中候选 → 归因日志：被点矩形里有什么节点、被什么规则拦、还是纯 rect 错位 */
    private fun logTapMiss(ui: UiNode, crect: Rect?, chint: String?, screenW: Int) {
        runCatching {
            val hintS = chint?.trim()?.take(20) ?: ""
            if (crect == null) { LogBuf.log("tap miss: hint=$hintS 原因=无rect(仅文本线索)"); return }
            val l = crect.left; val t = crect.top; val r = crect.right; val b = crect.bottom
            val miss: (String) -> Unit = { line -> Log.i(TAG, line); LogBuf.log(line) }   // e2e(logcat)+真机(导出) 双通道
            val near = ArrayList<UiNode>()
            fun walk(n: UiNode) {
                if (n.text.isNotBlank() && n.overlapsRect(l, t, r, b)) near.add(n)
                n.children.forEach(::walk)
            }
            walk(ui)
            val inRect = near.filter {
                it.left >= l - 40 && it.top >= t - 40 && it.right <= r + 120 && it.bottom <= b + 120
            }.sortedBy { it.w.toLong() * it.h }
            val node = inRect.firstOrNull { ScanLogic.clean(it.text).length >= 2 }
                ?: near.maxByOrNull { minOf(it.right, r) - maxOf(it.left, l) }
            if (node == null) {
                miss("tap miss: rect=$l,$t,$r,$b 原因=rect内无文本节点(点击事件未附文本? id未知)")
                return
            }
            val raw = ScanLogic.clean(node.text)
            val reason = when {
                ScanLogic.isOwnBubble(ScanLogic.bubbleOf(node, ui, screenW), screenW) ||
                    (screenW - node.right < 96 && node.left > 96) -> "own"
                else -> ScanLogic.dropReason(node, raw) ?: "inrect-mismatch"
            }
            miss("tap miss: \"" + raw.take(16) + "\" id=" + node.idSuffix.ifEmpty { "-" } +
                " cls=" + node.className.substringAfterLast('.') + " 原因=" + reason)
        }
    }

    /** WatchSink hook（onServiceConnected 注入）：pick 过滤节点 → 2s 节流抽样进日志。
     *  高频且无取证价值的 kind 排除（own/时间戳/分隔符每轮扫描都触发，会淹掉 2000 环）；
     *  「点了没反应」的 own 场景由 tap miss 归因通道覆盖，不靠 drop。 */
    private val dropSkip = setOf("own", "time-id", "clock", "divider")
    private fun onWatchDrop(kind: String, node: UiNode, raw: String) {
        if (kind in dropSkip) return
        val key = kind + "|" + raw.take(24)
        if (dropSeen.containsKey(key)) return          // v0.2.3：内容去重，一会话一条
        val now = System.currentTimeMillis()
        if (key == lastDropKey && now - lastDropAt < 2000) return
        lastDropKey = key; lastDropAt = now
        dropSeen[key] = true
        val line = "drop " + kind + ": \"" + raw.take(20) + "<" + node.idSuffix.ifEmpty { "-" } +
            "|" + node.className.substringAfterLast('.') + ">\""
        Log.d(TAG, line)                 // e2e 断言通道（logcat）
        LogBuf.log(line)                 // 真机导出通道
    }

    /** 译文行几何量化（用户问题①，每行量一次）。模拟器实证：maxLines=10 对 12 行译文
     *  未裁剪（lineCount=12）——"显示不全"的另一半真相是行窗口底部伸出屏幕被切。
     *  恒记录一行 measure:（不只在截断时），真机日志直接分辨三种病因：
     *  maxLines 截断（显示<译文行）/ 屏内溢出（行底>屏高）/ 两者都无（那就是滚动看不见）。 */
    private fun measureRow(r: Row, t: String, attempt: Int = 0) {
        runCatching {
            // v0.2.3 M3b：显示行数改用**实际绘制行高**推定——真机取证 maxLines=10 时
            // lineCount 报 15（layout 不裁、绘制才裁），lineCount 不可信（v0.2.2 教训）。
            val lh = kotlin.math.ceil(r.body.lineHeight.toDouble()).toInt().coerceAtLeast(1)
            val visible: Int = if (r.body.height > 0) (r.body.height / lh).coerceAtLeast(1) else r.body.lineCount
            if (visible <= 0) {
                if (attempt < 3) main.postDelayed({ if (rows.contains(r)) measureRow(r, t, attempt + 1) }, 500)
                return
            }
            if (!measuredRows.add(r.anchorKey)) return
            val avail = r.body.maxWidth.toFloat().coerceAtLeast(1f)
            val paint = r.body.paint
            val est = t.split('\n').sumOf { ln ->
                maxOf(1, kotlin.math.ceil(paint.measureText(ln) / avail).toInt())
            }
            val lp = r.win.layoutParams as? android.view.WindowManager.LayoutParams
            val winBottom = (lp?.y ?: 0) + r.win.height
            val screenH = resources.displayMetrics.heightPixels
            val cut = if (visible < est) " maxLines截断" + est + "→" + visible + "行" else ""
            val over = if (winBottom > screenH) " 溢出屏" + (winBottom - screenH) + "px" else ""
            val line = "measure: src=\"" + r.src.replace('\n', '⎵').take(14) + "\" 译文" + est +
                "行 显示" + visible + "行 行底" + winBottom + "/屏" + screenH + cut + over
            Log.i(TAG, line)
            LogBuf.log(line)
        }
    }

    private fun clampScreen(screenH: Int) {
        // 气泡被滚到屏幕外 → 译文行窗口移到屏外（visibility=INVISIBLE 省资源）
        for (r in rows) {
            if (r.box.bottom < 0 || r.box.top > screenH) r.win.visibility = View.INVISIBLE
            else r.win.visibility = View.VISIBLE
        }
    }

    private fun injectRow(c: Candidate, key: String) {
        // v0.1.4（用户定案）：点击模式一次只译一条——点新气泡，旧行立刻消失。
        // 自动模式不在此清（它按屏内气泡铺行，由 stale 撤）。同条再点走 doRefresh 不经过这里。
        if (!autoTranslate && rows.isNotEmpty()) {
            Log.i(TAG, "single-row: drop " + rows.size + " old (" + rows.first().src.take(20) + ")")
            rows.forEach { tappedKeys.remove(it.anchorKey); removeRow(it) }  // v0.3.8 P2：被新点行替换的旧行移出记忆表（防滚回时反复重投）
            rows.clear()
        }
        // v0.1.5（用户定案）：白色圆角底 + 无光晕（v0.1.4 透明底在深浅气泡上可读性不稳）；行宽限屏内
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(8).toFloat()
            }
            setPadding(dp(8), dp(3), dp(8), dp(3))
        }
        val tag = TextView(this).apply {
            text = "译 v" + VERSION
            setTextColor(COLOR_TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)   // 水印固定不随字号滑杆缩放（用户定：主字放大，水印克制）
            includeFontPadding = false
            setPadding(0, dp(2), dp(4), 0)
        }
        val body = TextView(this).apply {
            text = "翻译中…"
            setTextColor(COLOR_PENDING)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, bodySizeSp)  // v0.1.4 默认 16sp，可滑杆调
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            // v0.2.3 问题①（用户截图实证）：真机上 maxLines=10 绘制时裁掉第 11 行起
            // （模拟器不裁=API 差异，v0.2.2 曾被假阴性骗过）；10→40 配合 position() 钳位。
            maxLines = 40
            setHorizontallyScrolling(false)
            includeFontPadding = false
        }
        // 行从气泡左缘起画，最宽到屏右缘-16dp；超出自动折行（透明底溢出=被系统裁字）
        body.maxWidth = maxOf(dp(160), resources.displayMetrics.widthPixels - c.anchor.left - dp(16))
        row.addView(tag); row.addView(body)
        row.isClickable = false   // v0.1.8：只有文字热区收点击，行 padding 空白穿透
        // v0.1.8（用户定案）：点译文行文字=强制重译 → 行不再是全穿透窗口。
        // 根 LinearLayout 保持不可点（padding 区穿透），仅 body/tag 收点击；
        // 行的覆盖矩形本来就只有一小条，WhatsApp 其余区域手势不受影响。
        val params = android.view.WindowManager.LayoutParams(
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = c.anchor.left
            // v0.1.4 智能上下位：底部空间被 WhatsApp 输入栏吃掉 → 翻到气泡上方
            y = ScanLogic.rowY(c.anchor.top, c.anchor.bottom, dp(28),
                resources.displayMetrics.heightPixels, dp(92))
            // IN_SCREEN：让 lp.x/y 以整屏（含状态栏）为原点，与无障碍 bounds 对齐；
            // 没有它 lp.y 会被额外下推一个状态栏高度（SurfaceFlinger 实测 +136px，2026-09-07）
            flags = flags or android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        }
        try {
            wm.addView(row, params)
        } catch (e: Exception) {
            Log.e(TAG, "addView failed: ${e.message}")
            runCatching { wm.removeView(row) }
            return
        }
        val r = Row(row, tag, body, c.text, Rect(c.anchor.left, c.anchor.top, c.anchor.right, c.anchor.bottom), key)
        // v0.1.8：点行文字=重译（forget 缓存→重新请求 API）；pending 期间忽略防打断
        val onRowClick = View.OnClickListener {
            if (!rows.contains(r)) return@OnClickListener
            if (r.pending) { Log.i(TAG, "row click ignored (pending)") ; return@OnClickListener }
            Log.i(TAG, "row click -> refresh: ${r.src.take(24)}")
            LogBuf.log("点行重译: ${r.src.take(24)}")
            doRefresh(r.src)
        }
        body.isClickable = true; body.setOnClickListener(onRowClick)
        tag.isClickable = true; tag.setOnClickListener(onRowClick)
        rows.add(r)
        Log.i(TAG, "inject row @${c.anchor.left},${c.anchor.bottom}: ${c.text.take(30)}")
        requestTranslate(r)
    }

    private fun position(r: Row) {
        val lp = r.win.layoutParams as android.view.WindowManager.LayoutParams
        lp.x = r.box.left
        // 行高取实测值（折行后变高→上下位判定跟着变）；未布局过时估一行
        val h = if (r.win.height > 0) r.win.height else dp(28)
        val screenH = resources.displayMetrics.heightPixels
        val reserve = dp(92)
        lp.y = ScanLogic.clampRowY(ScanLogic.rowY(r.box.top, r.box.bottom, h, screenH, reserve),
            h, screenH, reserve, dp(96))
        // v0.2.3 问题①（真机截图实证 15 行译文顶出屏底）：钳位贴输入栏上方——宁可盖住
        // 原文气泡下缘（热区只在译文文字上，点气泡关闭仍可用），也不能看不见译文后半段。
        main.post { runCatching { wm.updateViewLayout(r.win, lp) } }
    }

    /** 设置页滑杆调用：字号即时套到屏上所有行（不用重译） */
    fun applyFontSizes() {
        for (r in rows) {
            r.body.setTextSize(TypedValue.COMPLEX_UNIT_SP, bodySizeSp)
            position(r)   // 行高变了 → 上/下位可能要翻
        }
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun requestTranslate(r: Row) {
        if (r.pending) return
        r.pending = true
        r.waitSecs = 0
        r.body.text = "翻译中…"
        r.body.setTextColor(COLOR_PENDING)
        // v0.1.7：每秒把"翻译中…"变"翻译中… Ns"——让用户看出是在等服务器排队，不是卡死
        stopTick(r)
        val tick = object : Runnable {
            override fun run() {
                if (!r.pending || !rows.contains(r)) return
                r.waitSecs++
                r.body.text = "翻译中… " + r.waitSecs + "s"
                main.postDelayed(this, 1000)
            }
        }
        r.tickRunnable = tick
        main.postDelayed(tick, 1000)
        val t0 = System.currentTimeMillis()
        engine.translate(r.src) { t, err ->
            val ms = System.currentTimeMillis() - t0
            LogBuf.log("行结果 ${ms}ms ${if (t != null) "✔ " + t.take(24) else "✘ " + (err ?: "").take(60)}")
            main.post {
                stopTick(r)
                r.pending = false
                when {
                    t != null && t == Watch.PN_MARK -> {
                        // v0.2.3：已学到的人名——缓存秒命中哨兵，灰显不发 API
                        r.nameOnly = true; r.failed = false
                        Log.i(TAG, "row name-only (cached): " + r.src.take(24))
                        r.body.text = Watch.PN_MARK
                        r.body.setTextColor(COLOR_NAME)
                        main.postDelayed({ if (rows.contains(r)) position(r) }, 350)
                    }
                    t != null && t == Watch.KEEP_MARK -> {
                        // v0.2.4 问题①：混合消息回显→Engine 强化重试仍回显→KEEP 哨兵（本次结果
                        // 或下次 cache hit 都走这里）。灰显零 API，不再红⚠。
                        // nameOnly **不置**：点行重译保持 forget 语义=再给模型一次机会
                        // （纯人名 PN 哨兵才跳过 API 防漂移，两者语义不同）。
                        r.failed = false
                        Log.i(TAG, "row keep-as-is (cached): " + r.src.take(24))
                        r.body.text = Watch.KEEP_MARK
                        r.body.setTextColor(COLOR_NAME)
                        main.postDelayed({ if (rows.contains(r)) position(r) }, 350)
                    }
                    t != null && !Echo.isEcho(r.src, t) -> {
                        Log.i(TAG, "row result: " + r.src.take(24) + " -> " + t.take(40))
                        r.body.text = t; r.failed = false
                        r.body.setTextColor(COLOR_TEXT)
                        // 译文可能折行变高 → 350ms 后用实测行高重算上/下位
                        main.postDelayed({
                            if (rows.contains(r)) { position(r); measureRow(r, t) }   // v0.2.2 截断实测
                        }, 350)
                    }
                    else -> {
                        Log.i(TAG, "row fail: " + r.src.take(24) + " echo-or-err=" + (err ?: "echo"))
                        r.failed = true
                        // v0.2.3 问题②（回显学习）：模型对纯人名方向歧义（谭效平 2208ms
                        // 返回空→"显示的是原文"，同日另一刻返回 Tan Xiaoping）。回显 +
                        // looksLikeProperName → 本次直接灰显（不红⚠）+ 落哨兵，以后秒命中零 API。
                        // 回显检测必须看 err 内容（Engine 已把回显包成 err，t=null——v0.2.2
                        // 的 err==null&&isEcho(t=null) 恒假，就是 回显明细=0 的根因）。
                        // 词典加了该词条并保存 → purgeNameCache 解除，词条优先。网络错误不学。
                        val echoFail = (err != null && err.contains("显示的是原文")) ||
                            (t != null && Echo.isEcho(r.src, t))
                        if (echoFail &&
                            Watch.looksLikeProperName(r.src) && !r.src.contains('\n')) {
                            r.nameOnly = true; r.failed = false
                            engine.persistProperName(r.src)
                            Log.i(TAG, "name learned (echo->grey, cached): " + r.src.take(24))
                            LogBuf.log("人名保留(学习): " + r.src.take(14))
                            r.body.text = Watch.PN_MARK
                            r.body.setTextColor(COLOR_NAME)
                            main.postDelayed({ if (rows.contains(r)) position(r) }, 350)
                            return@post
                        }
                        r.body.text = "⚠ " + (err ?: "翻译失败") + "（再点一次重试）"
                        r.body.setTextColor(COLOR_FAIL)
                    }
                }
            }
        }
    }

    private fun stopTick(r: Row) {
        r.tickRunnable?.let { main.removeCallbacks(it) }
        r.tickRunnable = null
    }

    /** 供设置页（v0.3.5）：清空一切缓存——译文缓存 + 全文映射
     *  （映射是缓存的一种：词典/模型换掉后旧截断→全文映射也应可整体作废重来）。 */
    fun clearAllCaches() { engine.clearAllCache(); FullText.clear() }

    /** 供设置页（v0.2.3）：保存词典后清人名哨兵——词典词条优先于回显学习结果 */
    fun purgeNameCache() { runCatching { engine.purgeProperNameCache() } }

    // ---------------- 诊断导出（v0.1.1：真机 WhatsApp 节点树取证通道） ----------------
    // 每次 scan 顺手把节点树序列化成缩进文本（限 900 节点/文本 80 字），
    // APP 点「导出诊断」时复制进剪贴板。扫描来自真实 WhatsApp 窗口 = 真实结构。
    // companion=进程级存活：uiautomator dump 等引起的服务重绑不会清掉快照（E9b 实证需要）。
    private fun buildSnapshot(root: UiNode, pkg: String): String {
        val sb = StringBuilder("pkg=" + pkg + " screen=" + resources.displayMetrics.widthPixels + "x" + resources.displayMetrics.heightPixels + "\n")
        var count = 0
        fun walk(n: UiNode, depth: Int) {
            if (count++ > 900) return
            sb.append("  ".repeat(depth)).append(n.className.substringAfterLast('.'))
              .append(" id=").append(n.idSuffix.ifEmpty { "-" })
              .append(" b=").append(n.left).append(",").append(n.top).append(",").append(n.right).append(",").append(n.bottom)
            if (n.scrollable) sb.append(" SCROLL")
            if (n.clickable) sb.append(" CLICK")
            if (n.text.isNotBlank()) sb.append(" txt=\"").append(n.text.replace("\n", "\\n").take(80)).append("\"")
            sb.append("\n")
            n.children.forEach { walk(it, depth + 1) }
        }
        walk(root, 0)
        return sb.toString()
    }

    /** 再点已译气泡：forget 缓存 → 强制重译（v0.1.3 起 ⟳ 图标删除，这是唯一刷新入口） */
    private fun doRefresh(text: String) {
        Log.i(TAG, "doRefresh: " + text.take(24))
        // v0.2.3：人名灰显行不 forget（清了哨兵就会走 API → 模型歧义红⚠ 复活），只重落灰显
        val nameRows = rows.filter { it.src == text && it.nameOnly }
        if (nameRows.isNotEmpty() && nameRows.size == rows.count { it.src == text }) {
            LogBuf.log("点行重译(人名·跳过API): " + text.take(24))
            nameRows.forEach { requestTranslate(it) }
            return
        }
        LogBuf.log("forget→重译(缓存清除): " + text.take(24))
        engine.forget(text)
        rows.filter { it.src == text }.forEach { r -> r.failed = false; r.pending = false; requestTranslate(r) }
    }

    private fun removeRow(r: Row) {
        stopTick(r)
        runCatching { wm.removeView(r.win) }
    }
    private fun detachAll() {
        main.post {
            rows.forEach { removeRow(it) }
            rows.clear()
            closedKeys.clear()   // v0.1.8：离开聊天窗=会话结束，回来允许再译
            tappedKeys.clear()   // v0.3.8 P2：记忆表随会话清空
            removeComposeBtn()   // v0.1.9：输入框「译」按钮随窗口走
            measuredRows.clear() // v0.2.2 监控：截断记录随行清（回聊天重译可重量）
        }
    }

    // ---------------- 节点树转换（真 AccessibilityNodeInfo → UiNode，限深+限节点数防爆栈） ----------------
    private fun AccessibilityNodeInfo.toUi(depth: Int = 0): UiNode {
        val b = android.graphics.Rect().also { getBoundsInScreen(it) }
        val kids = ArrayList<UiNode>()
        if (depth < 24) {
            for (i in 0 until minOf(childCount, 120)) {
                getChild(i)?.let { kids.add(it.toUi(depth + 1)) }
            }
        }
        val vid = viewIdResourceName?.substringAfterLast('/') ?: ""
        return UiNode(text?.toString() ?: "", vid, className?.toString() ?: "",
            b.left, b.top, b.right, b.bottom, isScrollable, isClickable, kids,
            isFocused, this)   // v0.1.9：focused+raw 供输入框「译」按钮使用
    }
}
