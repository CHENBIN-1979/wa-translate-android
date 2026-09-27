# WA 翻译悬浮助手（Android）v0.4.4

思路与交互对齐 Chrome 扩展版 whatsapp-translate-extension（v1.1.21）的逻辑层——
词典、批量、缓存、回显防护、多模型配置列表全部移植。

## 版本更新与各版下载

完整版本更新记录（v0.1.1 → v0.4.4）与**各版本安装包 / 源码归档**已迁至 [Releases 页面](https://github.com/CHENBIN-1979/wa-translate-android/releases)。

各版本的**根因取证、机制说明与踩坑经验**（"为什么这么改"）详录见 [CHANGELOG.md](CHANGELOG.md)；Releases 页面只保留简短的更新/修复条目。

## 功能
- 进入 WhatsApp 聊天 → 点对方外语消息（印尼语）→ 气泡旁浮出深绿中文译文行（智能上下位）
- 译文行结构：白色圆角底行，`译 v0.2.4` 小水印（10sp）+ 译文（默认 16sp 加粗，深绿 #0E8C46，滑杆可调 12–24sp）
- **点击模式一次只显示一条**：点新气泡旧行自动消失；再点同一气泡 = 关闭（v0.1.8）
- **不翻译**：日期分割线、端到端加密提示、「这条消息已删除」占位、发件人昵称、自己发的消息
- 回显防护：模型把原文吐回来 → 显示 ⚠ 而不是假装翻译成功
- 强制重译清该条缓存重发（持续失败的自动进 15–60s 冷却，不重复计费）
- 专属词典：`印尼文 = 中文` 每行一条，双向强制，整词精确匹配（与扩展同格式，可直接粘贴）
- 多模型配置列表：多套 API 随时切换，选中即用（切换绝不污染其他配置——扩展 v1.1.21 的教训内置）
- 极速模式：关思考链（enable_thinking:false），实测约快一倍；网关不支持该字段自动拉黑重试
- 点击穿透：译文行仅文字热区收点击（v0.1.8 点行=重译），padding 空白与其余区域照常操作 WhatsApp

## 安装（真机）
**装 `wa-translate-app-release.apk`（release 自签名版，推荐）**；debug 版功能完全相同，仅签名不同。
1. 安装 APK（允许"安装未知应用"）。若 Google Play 保护机制弹「已屏蔽应用」：
   Play 商店 → 头像 → Play 保护机制 → ⚙ 关闭「扫描应用」→ 重新安装（装好后可再打开）
2. 打开 APP → 填模型配置（默认已填 scnet：`https://api.scnet.cn/api/llm/v1` + 你的 Key + `Qwen3.8-Flash`）→ **保存配置** → 测试连接 ✔
3. 点「开启无障碍权限」→ 已下载的服务 → **WA翻译**。若弹「受限制的设置，目前不可用」：
   桌面长按 WA翻译图标 → 应用信息ⓘ → 右上角 ⋮ → **「允许受限制的设置」** → 再回来打开开关
   （备用：adb 连接后执行
   `adb shell settings put secure enabled_accessibility_services id.co.watranslate/.WaTranslateService`）
4. 打开 WhatsApp 任意聊天（默认点击翻译：点气泡才出译文行；再点=重译；点别条=旧行消失），
   气泡旁出现深绿 `译 v0.2.4` 行即生效。设置页可拖「翻译字号」滑杆（12–24sp 实时生效）
- 注意：release 与 debug 签名不同，**不能覆盖安装**——换版先卸载旧的（配置会丢，词典记得先备份）

## 技术要点
- Manifest：无障碍服务监听 WhatsApp/WhatsApp Business（测试壳包名也在白名单）
- 译文行 = `TYPE_ACCESSIBILITY_OVERLAY` 悬浮窗，**无需悬浮窗权限**，只需无障碍
- 双窗口触摸设计：译文主行窗口 `FLAG_NOT_TOUCHABLE`（整行点击穿透回 WhatsApp），
  ⟳ 是独立的 44dp 小窗（唯一可触摸区域）；lp 坐标必须配 `FLAG_LAYOUT_IN_SCREEN`
  否则整行被状态栏下推 136px（v0.1.0 模拟器实证修复）
- 扫描：450ms 防抖；节点 id 匹配 `message_text_view`/`message_text`，id 全失效时几何启发式兜底
- 翻译：350ms 攒批（≤12 条/4000 字符一批）+ 本地缓存 + 批量失败并行逐条降级
- 词典强制纠正（行对齐、一词之差模糊、单一入侵者）与扩展 enforceDictOut 同算法
- 零第三方依赖（kotlin-stdlib 除外），HTTP 用 HttpURLConnection

## 测试（开发机）
- `bash compile_core.sh` —— JVM core 单测 72 断言（+真机聊天页树回归 J12/clean隐形字符/已编辑/span上限），当前 **ALL PASS**
- 假 WhatsApp 测试壳（`testhost/`，模拟器专用）：节点 id 仿真机，含日期/加密提示/删除占位噪声行与左右锚定气泡
- e2e：`android-test/e2e_a11y.py`（纯 adb + logcat 真值断言 + 广播通道操作测试壳——UiAutomation
  会话会干扰无障碍事件分发，故运行期零 uiautomator 依赖）在模拟器上跑 14 断言：自动模式挂载/数量对齐/
  噪声零泄漏/词典强制（轧轮机）/回显⚠/再点重译/滚动不堆积 + 点击模式不点=零行/点对方出唯一行/点自己无反应/
  再点强制重译/点新气泡旧行消失（single-row 日志断言）/Download 诊断，**ALL PASS**
- 无障碍重绑配方：`settings put` 出来的绑定可能是僵尸（connected 但 0 事件），唯一可靠解药 =
  `install -r`（package-replaced 强制 rebind）；delete→put 之间必须 sleep 2s（异步竞态）

## 限制与已知边界（v0.1.4）
- 只自动翻译**对方**消息；你打中文→印尼语目前走 Chrome 扩展的输入框「译」按钮（输入框注入在无障碍层无合法通道，列入 v0.2 悬浮输入方案）
- 图片 caption：Android 无障碍树不含图注文本时不译（能取到即译）
- 语音/状态提示/系统消息一律跳过
- 翻译走你配置的云端 API，消息文本会离开手机（扩展同模式；不接受请停用）

## 目录
```
app/           翻译服务 APK（release 签名凭据在 keystore.properties，不入库；
               新环境先 cp keystore.properties.example keystore.properties 再构建）
keystore/      wa-translate-release.jks —— 签名钥匙（口令在本地 keystore.properties 里，
               绝不入库）。以后所有升级版必须用这把钥匙签名，否则手机要卸载重装
testhost/      假 WhatsApp 测试壳（仅模拟器调试用，不用装到手机）
android-test/  模拟器 e2e（纯 adb + logcat 真值断言）
core-test/     JVM 纯逻辑单测
build.sh       一键构建三个 APK（debug + release + 测试壳）
compile_core.sh 一键跑 core 单测
```
