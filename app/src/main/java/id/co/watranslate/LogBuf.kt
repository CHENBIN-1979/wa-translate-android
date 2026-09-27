package id.co.watranslate

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * LogBuf.kt — 运行日志环形缓冲（v0.1.6）：
 *   内存 ArrayDeque 2000 条，平时零磁盘 IO；点「导出运行日志」才落文件到 Download/。
 *   log() 可在任意线程调用（synchronized），单条成本 ~µs。
 *   与「导出诊断（节点树）」分工：树=翻错/翻不该翻；日志=慢/超时/失败。
 */
object LogBuf {

    private const val MAX = 2000
    private val q = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    val startedAt = System.currentTimeMillis()

    // v0.2.2 监控计数：dump 头部「小结」一眼定位四类问题（只计数，零行为影响）
    private val counters = LinkedHashMap<String, Int>()
    private fun bump(k: String) { counters[k] = (counters[k] ?: 0) + 1 }

    @Synchronized
    fun log(msg: String) {
        q.addLast(fmt.format(Date()) + "  " + msg)
        if (q.size > MAX) q.removeFirst()
        // 按消息前缀归类（每类计数唯一来源，防双计）
        when {
            msg.startsWith("tap 首译") -> bump("点击出译")
            msg.startsWith("tap 关闭") -> bump("点击关闭")
            msg.startsWith("自动首译") -> bump("自动出译")
            msg.startsWith("tap miss") -> bump("点击无候选")
            msg.startsWith("行结果") -> when {
                msg.contains("显示的是原文") -> bump("回显失败")
                msg.contains("✘") -> bump("其它失败")
                else -> bump("翻译成功")
            }
            msg.startsWith("drop ") -> bump("节点被过滤")
            msg.startsWith("cache hit") -> bump("缓存命中")
            msg.startsWith("人名保留(学习)") -> bump("人名保留")
            msg.startsWith("回显保留") -> bump("回显保留")
            msg.startsWith("输入框点击译") -> bump("输入框点击译")
            // v0.3.1 ④：本地闸门+采样重试进小结计数（真机取证重试率）
            msg.startsWith("本地闸门拒→采样重试") -> bump("本地闸门重试")
            msg.startsWith("采样重试成功") -> bump("本地重试成功")
            // v0.2.5：输入框写回全链路 + 思考泄漏，进小结计数（真机 07:41 取证：链路断点无日志）
            msg.startsWith("输入框写回✓") -> bump("输入框写回成功")
            msg.startsWith("输入框写回✘") -> bump("输入框写回失败")
            msg.startsWith("输入框拒写") -> bump("输入框拒写")
            msg.startsWith("输入框重译(清哨兵)") -> bump("输入框哨兵重译")
            msg.startsWith("输入框还原") -> bump("输入框还原")
            msg.startsWith("思考泄漏") || msg.startsWith("重试仍思考泄漏") -> bump("思考泄漏拦截")
            msg.startsWith("measure:") -> bump("行量化" + if (msg.contains("截断") || msg.contains("溢出")) "(异常)" else "")
        }
    }

    @Synchronized
    fun dump(context: Context, version: String): String {
        val sb = StringBuilder()
        sb.append("=== WA 翻译悬浮助手 运行日志 v").append(version).append(" ===\n")
        sb.append("导出时间: ").append(fmt.format(Date(System.currentTimeMillis()))).append(".000\n")
        sb.append("会话开始: ").append(fmt.format(Date(startedAt))).append(".000\n")
        sb.append("App: v").append(version).append('\n')
        sb.append("系统: Android ").append(Build.VERSION.RELEASE)
            .append(" (API ").append(Build.VERSION.SDK_INT).append(") ")
            .append(Build.MODEL).append('\n')
        sb.append("语言: ").append(java.util.Locale.getDefault().toString()).append('\n')
        sb.append("网络: ").append(netType(context)).append('\n')
        val sp = context.getSharedPreferences("wtrans", Context.MODE_PRIVATE)
        // v0.1.7：profiles 模式下旧扁平字段是空的（模型=?/apiBase=? bug），改走 resolve 取活动配置
        val cfg = Engine(context).loadConfig()
        sb.append("配置: 模型=").append(cfg.model)
            .append(" 极速=").append(cfg.fast)
            .append(" 自动=").append(sp.getBoolean("auto", false))
            .append(" 字号=").append(sp.getFloat("font_sp", 16f))
            .append(" 词典=").append(cfg.dictText.lines().count { it.contains('=') })
            .append("条 apiBase=").append(cfg.apiBase)
            .append(" profiles=").append(cfg.profiles.size)
            // v0.3.2 取证补盲：日志头看不到 engineMode，云端回显/本地出译两条链无法归因（2026-09-23 三份日志教训）
            .append(" 引擎模式=").append(sp.getString("engineMode", "cloud"))
            .append(" 本地模型=").append(sp.getString("localModel", "hy2b"))
            // v0.3.7 取证补盲：词典注入模式自报（全量/提取两路译文质量差异的归因字段）
            .append(" 词典注入=").append(if (sp.getBoolean("dictFull", true)) "全量" else "提取")
            // v0.3.6 取证补盲：人名条款变体自报（真机日志直接能看出跑的是哪版 prompt）
            .append(" 人名条款=").append(if (LocalPrompt.NAME_CLAUSE_VARIANT.isEmpty()) "无" else LocalPrompt.NAME_CLAUSE_VARIANT)
            .append('\n')
        sb.append("超时: 单条45s 批量50s 逐条降级60s | 连接10s\n")
        // v0.2.2：小结行——四类问题（点击无候选/节点被过滤/回显失败/行截断）+ 缓存命中，一眼定位
        if (counters.isNotEmpty()) {
            sb.append("小结: ").append(counters.entries.joinToString(" / ") { "${it.key}=${it.value}" }).append('\n')
        }
        sb.append("--- 日志 (").append(q.size).append("/").append(MAX).append(" 环形) ---\n")
        q.forEach { sb.append(it).append('\n') }
        return sb.toString()
    }

    private fun netType(context: Context): String = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val n = cm.activeNetwork ?: return "无活动网络"
        val caps = cm.getNetworkCapabilities(n) ?: return "已连接(类型未知)"
        when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "移动数据"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "有线"
            else -> "其他"
        }
    }.getOrElse { "读取失败" }
}
