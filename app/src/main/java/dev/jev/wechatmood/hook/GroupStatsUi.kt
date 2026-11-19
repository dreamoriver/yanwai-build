package dev.jev.wechatmood.hook

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import dev.jev.wechatmood.core.HttpClients
import dev.jev.wechatmood.core.ModulePrefs
import dev.jev.wechatmood.core.TextClean
import dev.jev.wechatmood.reply.GroupStats
import dev.jev.wechatmood.reply.GroupStatsCalculator
import dev.jev.wechatmood.reply.GroupTranscript
import dev.jev.wechatmood.reply.ReplyContext
import dev.jev.wechatmood.reply.ReplyHttpClient
import dev.jev.wechatmood.reply.StatRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** 分析范围：进程内记住上次选择，重开窗口沿用。 */
object StatsFilter {
    enum class Range(val label: String, val millis: Long) {
        HOUR("最近 1 小时", 60L * 60 * 1000),
        HOUR3("最近 3 小时", 3 * 60L * 60 * 1000),
        HOUR12("最近 12 小时", 12 * 60L * 60 * 1000),
        HOUR24("最近 24 小时", 24 * 60L * 60 * 1000),
        TODAY("今天", -1),
    }
    // 时段默认「今天」；条数默认全部，之前默认 20 条，总结就只能看到十几条消息。
    var range: Range = Range.TODAY
    var limit: Int = 0
    val limitLabel: String get() = if (limit == 0) "该时段全部" else "最近 $limit 条"

    fun sinceMillis(now: Long = System.currentTimeMillis()): Long = when (range) {
        Range.HOUR -> now - Range.HOUR.millis
        Range.HOUR3 -> now - Range.HOUR3.millis
        Range.HOUR12 -> now - Range.HOUR12.millis
        Range.HOUR24 -> now - Range.HOUR24.millis
        Range.TODAY -> java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            timeInMillis
        }.timeInMillis
    }
}

/** 群聊统计窗口：数字 + 活跃成员 + 一次 AI 解读，结果可复制。 */
internal class GroupStatsUi(private val activity: Activity, private val context: ReplyContext,
    private var dbMessages: List<dev.jev.wechatmood.reply.ReplyMessage> = emptyList(),
    /** 当前登录账号；昵称查询只在这个账号的库里查，避免把另一个账号的资料算进来。 */
    private val accountScope: String? = null,
    private val reload: (() -> Unit)? = null) {
    private val kit = PanelKit(activity)
    private val theme = kit.theme
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stats: GroupStats
    /** 筛选变化时原地重算，不重建面板。 */
    private var onFilterChanged: (() -> Unit)? = null
    private val topContainer = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private var currentNames: Map<String, String> = emptyMap()
    /** 成员原始名 → 表格里显示的文本，生成图片时复用同一套规则。 */
    private val displayedNames = mutableMapOf<String, String>()
    private var analysisText: String = ""
    /** 上一次表格里没查到昵称的人数，诊断导出要看。 */
    private var unknownNames = 0
    /** 素材正文的字符预算：小模型上下文也就 8k，素材撑太满直接 400。 */
    private val MATERIAL_CHARS = 9000
    /** wxid → 微信页面上显示的昵称，来自消息对象自带的显示名，比库里的准。 */
    private var pageNames: Map<String, String> = emptyMap()
    /** wxid → 引用消息里带出来的昵称，补上库里没有的非好友。 */
    private var quotedNames: Map<String, String> = emptyMap()
    /** wxid → 手动补的昵称，优先级最高，点未获取昵称那一行就能填。 */
    private var manualNames: Map<String, String> = emptyMap()
    /** 一次分析的结果，附带用了多少素材，界面上要看得出覆盖范围。 */
    private data class Analysis(val text: String, val material: Int, val total: Int, val note: String = "")

    /** 看着有码点、渲染却是空白的字符。 */
    private val BLANK_CODES = setOf(
        0x3164, 0x2800, 0x115F, 0x1160, 0xFFA0, 0x00A0, 0x3000,
        0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006, 0x2007, 0x2008, 0x2009, 0x200A,
        0x200B, 0x200C, 0x200D, 0x2028, 0x2029, 0x202F, 0x205F, 0xFEFF, 0xFE0F,
    )
    private var dialog: Dialog? = null

    /** 按选择的时段与条数裁剪消息。 */
    private fun selectedMessages(): List<dev.jev.wechatmood.reply.ReplyMessage> {
        val since = StatsFilter.sinceMillis()
        // 优先用数据库按时间查到的消息，页面上下文只有最近几十条。
        val source = dbMessages.ifEmpty { context.messages }
        val byTime = source.filter {
            val stamp = if (it.time > 1_000_000_000_000L) it.time else it.time * 1000
            stamp >= since
        }
        return if (StatsFilter.limit > 0) byTime.takeLast(StatsFilter.limit) else byTime
    }

    init { stats = computeStats() }

    /** 按当前筛选重算，只读内存里的消息，不碰数据库。 */
    private fun computeStats(): GroupStats {
        val records = selectedMessages().map {
            StatRecord(it.time, if (it.speaker == "我") 1 else 0, 1, it.text, it.speaker)
        }
        val base = GroupStatsCalculator.compute(records, sampled = context.trimmed)
        // 媒体不在回复上下文里，用漏掉的条数补上，否则永远是 0。
        val voices = context.messages.count { it.voiceState == dev.jev.wechatmood.voice.VoiceState.READY }
        return base.copy(media = context.omittedMedia, voice = voices)
    }

    /** 说明行：跟着筛选走，切时段时只换文字。 */
    private fun summaryText(): String {
        val window = when (StatsFilter.range) {
            StatsFilter.Range.TODAY -> "今天 00:00–现在"
            else -> StatsFilter.range.label
        }
        return "$window · ${StatsFilter.limitLabel} · 共 ${stats.total} 条 · 人均 ${stats.perCapita} 条 · 只在本机计算"
    }

    private fun spanText(): String {
        // 用筛选后的消息算范围：以前拿页面消息，会出现「1011 条却只有 18 分钟」这种对不上的显示。
        val stamps = selectedMessages().map { if (it.time > 1_000_000_000_000L) it.time else it.time * 1000 }
            .filter { it > 0 }
        if (stamps.isEmpty()) return "暂无数据"
        val format = java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA)
        return format.format(java.util.Date(stamps.min())) + "–" + format.format(java.util.Date(stamps.max()))
    }

    /** 换筛选后按新条件重新查库。以前只重算内存里的旧数据，条数调大不会生效。 */
    private suspend fun reloadDb() {
        val limit = if (StatsFilter.limit == 0) 5000 else StatsFilter.limit
        val rows = runCatching {
            ReplyDatabaseHistory.statMessages(context.talker, StatsFilter.sinceMillis(), limit, accountScope)
        }.getOrDefault(emptyList())
        dev.jev.wechatmood.core.MoodLog.i("STATS_RELOAD rows=" + rows.size + " limit=" + limit + " range=" + StatsFilter.range)
        if (rows.isEmpty()) return
        dbMessages = rows.map {
            dev.jev.wechatmood.reply.ReplyMessage(it.messageId, it.speaker(), it.createdAt,
                it.analysisText() ?: "[媒体]", senderId = it.senderId())
        }
        stats = computeStats()
    }

    fun show() {
        lateinit var scroll: android.widget.ScrollView
        val dialog = Dialog(activity).also { this.dialog = it }
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(theme.dp(20), theme.dp(18), theme.dp(20), theme.dp(18))
            background = theme.shape(theme.surface, ReplyMetrics.RADIUS_PANEL, theme.border)
        }
        body.addView(kit.heading(if (context.talker.endsWith("@chatroom")) "群聊统计" else "聊天统计"))
        val filters = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, theme.dp(10), 0, 0)
        }
        val rangePill = filterPill("时段 · ${StatsFilter.range.label}") { pickRange() }
        val limitPill = filterPill("条数 · ${StatsFilter.limitLabel}") { pickLimit() }
        filters.addView(rangePill, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = theme.dp(8) })
        filters.addView(limitPill, LinearLayout.LayoutParams(0, -2, 1f))
        body.addView(filters)
        val summaryLine = kit.caption(summaryText())
        val spanLine = kit.caption("数据范围 ${spanText()}")
        body.addView(summaryLine)
        body.addView(spanLine)

        val numbers = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, theme.dp(14), 0, theme.dp(6))
        }
        val cells = listOf("条消息", "人发言", "图片语音", "个时段").map { number(it) }
        cells.forEach { numbers.addView(it.first, weight()) }
        body.addView(numbers, LinearLayout.LayoutParams(-1, -2))

        body.addView(LinearLayout(activity), LinearLayout.LayoutParams(-1, theme.dp(6)))
        body.addView(topContainer, LinearLayout.LayoutParams(-1, -2))
        pageNames = pageSpeakerNames()
        manualNames = GroupNameStore.load(activity, context.talker)
        renderTop(pageNames + manualNames)

        val activeBox = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        body.addView(activeBox, LinearLayout.LayoutParams(-1, -2))
        renderActiveHours(activeBox)

        val analysisBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        body.addView(analysisBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = theme.dp(12) })
        analysisText = ""

        lateinit var aiButton: TextView
        aiButton = theme.action("让 AI 说说这群在聊什么", primary = true) {
            aiButton.isEnabled = false
            aiButton.text = "正在分析…"
            analysisBox.visibility = View.VISIBLE
            analysisBox.removeAllViews()
            analysisBox.addView(panelWith("群里在聊什么", kit.loading().apply { showLoading("正在分析最近聊天…") }),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = theme.dp(8) })
            scope.launch {
                val analysis = runCatching { analyzeAll(context) }.getOrElse { error ->
                    dev.jev.wechatmood.core.MoodLog.w("STATS_AI_FAILED " + error.message)
                    Analysis("分析失败：" + (error.message ?: "请重试"), 0, 0)
                }
                val failed = analysis.text.startsWith("分析失败")
                val shown = analysis.text.ifBlank { "（模型返回空内容）" }
                analysisText = analysis.text
                analysisBox.removeAllViews()
                val content = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    if (analysis.material > 0) {
                        addView(kit.caption("素材 ${analysis.material}/${analysis.total} 条 · 按整段抽样"))
                    }
                    if (analysis.note.isNotBlank()) addView(kit.caption(analysis.note))
                    addView(kit.body(shown).apply { if (failed) setTextColor(theme.error) })
                }
                analysisBox.addView(panelWith("群里在聊什么", content),
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = theme.dp(8) })
                aiButton.text = "重新分析"
                aiButton.isEnabled = true
                scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
        // 切换筛选：只改数字、表格和时段，不关窗口、不重建面板，滚动位置也保留。
        onFilterChanged = {
            stats = computeStats()
            rangePill.text = "时段 · ${StatsFilter.range.label}"
            limitPill.text = "条数 · ${StatsFilter.limitLabel}"
            summaryLine.text = summaryText()
            spanLine.text = "数据范围 ${spanText()}"
            cells[0].second.text = "${stats.total}"
            cells[1].second.text = "${stats.senders}"
            cells[2].second.text = "${stats.media + stats.voice}"
            cells[3].second.text = "${stats.activeHours.size}"
            renderTop(currentNames)
            renderActiveHours(activeBox)
            // 换了范围，旧的观察对不上新数据，直接清掉，避免复制或出图带错内容。
            analysisBox.removeAllViews()
            analysisBox.visibility = View.GONE
            analysisText = ""
            aiButton.text = "让 AI 说说这群在聊什么"
            scope.launch {
                // 新筛选可能要看更多消息：先重查，再刷新数字与表格。
                reloadDb()
                summaryLine.text = summaryText()
                spanLine.text = "数据范围 " + spanText()
                cells[0].second.text = "" + stats.total
                cells[1].second.text = "" + stats.senders
                cells[2].second.text = "" + (stats.media + stats.voice)
                cells[3].second.text = "" + stats.activeHours.size
                renderTop(currentNames)
                renderActiveHours(activeBox)
                renderTop(currentNames + namesFor(stats.members.map { it.name }))
            }
        }
        val actions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, theme.dp(10), 0, 0)
        }
        actions.addView(theme.action("复制", quiet = true) {
            copy(exportText(analysisText))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(theme.action("生成图片", quiet = true) {
            val summary = analysisText.takeIf {
                it.isNotBlank() && !it.startsWith("分析失败")
            }
            val bitmap = StatsImage.render(stats, displayedNames, summary, "群聊统计")
            val uri = StatsImage.save(activity, bitmap)
            Toast.makeText(activity,
                if (uri != null) "已保存到相册，可在相册里发送" else "保存失败，检查相册权限",
                Toast.LENGTH_LONG).show()
        }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(theme.action("关闭", quiet = true) { dialog.dismiss() },
            LinearLayout.LayoutParams(0, -2, 1f))
        actions.setPadding(theme.dp(20), theme.dp(8), theme.dp(20), theme.dp(14))
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = theme.shape(theme.surface, ReplyMetrics.RADIUS_PANEL, theme.border)
        }
        body.background = null

        scroll = android.widget.ScrollView(activity).apply {
            isFillViewport = false
            addView(body)
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(aiButton, LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(theme.dp(20), theme.dp(6), theme.dp(20), 0)
        })
        root.addView(actions, LinearLayout.LayoutParams(-1, -2))
        dialog.setContentView(root)
        val screenHeight = activity.resources.displayMetrics.heightPixels
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setGravity(Gravity.BOTTOM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, (screenHeight * 0.82).toInt())
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(ReplyMetrics.DIM)
        }
        dialog.setOnDismissListener { scope.cancel() }
        dialog.show()

        // 昵称在数据库里，异步查一次再替换 wxid；顺便记一次表名，方便确认字段位置。
        scope.launch {
            runCatching { ReplyDatabaseHistory.tableNames(accountScope) }
            renderTop(namesFor(stats.members.map { it.name }))
            dumpNameDiag()
        }
    }

    /**
     * 昵称查不到时的现场诊断：写到微信自己的外部目录。
     * 用独立的 scope，且快慢两段分开落盘——以前挂在面板协程上，用户一关面板就被取消，
     * 全库搜索还没跑完文件根本没生成。
     */
    private fun dumpNameDiag() {
        if (unknownNames <= 0) return
        val raws = stats.members.map { it.name }.filter { it !in currentNames }
        val dir = activity.getExternalFilesDir(null) ?: return
        val file = java.io.File(dir, "yanwai-names-diag.txt")
        val tail = "\nunknown=" + unknownNames + " layers=" + ReplyDatabaseHistory.lastLayers +
            "\npageNames=" + pageNames.size + " quoted=" + quotedNames.size + " manual=" + manualNames.size +
            "\nmsg_fields=" + MessageMetadata.lastFields.take(80).joinToString(",") + "\n"
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val body = runCatching {
                ReplyDatabaseHistory.diagNames(context.talker, accountScope, raws)
            }.getOrNull().orEmpty()
            runCatching { file.writeText(body + tail) }
            // 类名探测放在全库搜索前面：搜索很容易超时，探测更值钱。
            // 用微信自己的联系人实体解码 lvbuff，昵称在新版可能只存在那儿。
            runCatching {
                file.appendText(ReplyDatabaseHistory.diagRcontact(accountScope, raws, activity.classLoader))
            }
            runCatching {
                ReplyDatabaseHistory.diagSearch(accountScope, raws.firstOrNull { it.startsWith("wxid") }.orEmpty())
            }.getOrNull()?.let { extra ->
                runCatching { file.appendText(extra) }
            }
            dev.jev.wechatmood.core.MoodLog.i("STATS_DIAG_WRITTEN " + file.absolutePath)
        }
    }

    private fun renderTop(names: Map<String, String>) {
        currentNames = names
        topContainer.removeAllViews()
        displayedNames.clear()
        if (stats.members.isEmpty()) {
            topContainer.addView(kit.caption("没有可统计的发言"))
            return
        }
        val merged = LinkedHashMap<String, Int>()
        val rawByShown = LinkedHashMap<String, String>()
        var unknown = 0
        for (member in stats.members) {
            val resolved = resolveName(member.name, names)
            val shown = resolved ?: "未获取昵称#${++unknown}"
            displayedNames[member.name] = shown
            merged[shown] = (merged[shown] ?: 0) + member.count
            rawByShown.putIfAbsent(shown, member.name)
        }
        topContainer.addView(headerRow(), LinearLayout.LayoutParams(-1, -2))
        val ordered = merged.entries.sortedByDescending { it.value }
        val percents = dev.jev.wechatmood.reply.GroupStatsCalculator.percents(ordered.map { it.value })
        ordered.forEachIndexed { index, entry ->
            val row = tableRow(entry.key, "${entry.value}", "${percents[index]}%")
            val raw = rawByShown[entry.key]
            if (entry.key.startsWith("未获取昵称") && raw != null) {
                row.setOnClickListener { promptName(raw, entry.key) }
            }
            topContainer.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        unknownNames = unknown
        if (unknown > 0) {
            // 查不到就说清楚查不到，顺带报每层命中多少，方便判断是库的问题还是解析的问题。
            val layers = ReplyDatabaseHistory.lastLayers
            val detail = if (layers.isEmpty()) "" else "（群名片 ${layers["memberlist"] ?: 0} · 备注 " +
                "${layers["contact"] ?: 0} · 微信号 ${layers["alias"] ?: 0} · 兜底 ${layers["scanned"] ?: 0}）"
            topContainer.addView(kit.caption("其中 $unknown 人没查到群昵称$detail，先按序号显示").apply {
                setPadding(0, theme.dp(6), 0, 0)
            })
        }
    }

    /**
     * 群聊页面上微信自己渲染的名字：消息对象带了显示名时 speaker 就是昵称，senderId 是 wxid。
     * 非好友的昵称库里可能是空白，但页面上一定显示得出来。
     */
    private fun pageSpeakerNames(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (message in context.messages) {
            val id = message.senderId
            if (id.isBlank() || message.speaker.isBlank() || message.speaker == id) continue
            if (message.speaker == "我" || message.speaker == "对方") continue
            out[id] = message.speaker
        }
        return out
    }

    /**
     * 三层名字来源合并，后面的覆盖前面的：
     * 库里查到的（好友备注/昵称）< 引用消息里带的昵称 < 微信页面上显示的昵称。
     */
    private suspend fun namesFor(raws: List<String>): Map<String, String> {
        val db = runCatching { ReplyDatabaseHistory.displayNames(raws, context.talker, accountScope) }
            .getOrDefault(emptyMap())
        val quoted = runCatching {
            ReplyDatabaseHistory.quotedNames(context.talker, StatsFilter.sinceMillis(), accountScope)
        }.getOrDefault(emptyMap())
        quotedNames = quoted
        // 手动补的名字放最后，覆盖前面所有的自动结果。
        return db + quoted + pageNames + manualNames
    }

    private fun resolveName(raw: String, names: Map<String, String>): String? {
        // 数据库里有名字优先用；没有就用原始名字。只要不是空白就显示，截断即可。
        val fromDb = names[raw]?.let { dev.jev.wechatmood.core.TextClean.name(it) }?.takeIf { it.isNotEmpty() }
        val clean = (fromDb ?: raw).trim()
        // 数据库里缺失的字段会写 null / uinull，那不是名字。
        if (clean.isEmpty() || clean.equals("null", true) || clean.equals("uinull", true)) return null
        // 空白、零宽、格式控制符都算「没有名字」，避免表格里出现空行。
        // 只把「真的能看见」的字符算名字：字母、数字、标点、符号。
        // 但 U+3164、U+2800 这类字符虽然属于「字母/符号」，渲染出来是空白，必须单独排除。
        val visible = clean.codePoints().anyMatch { cp ->
            if (cp in BLANK_CODES) {
                false
            } else {
                val type = Character.getType(cp).toInt()
                type in 1..5 || type in 9..11 || type in 20..30
            }
        }
        if (!visible || clean.startsWith("对方（群成员未知")) {
            if (clean.isNotEmpty()) {
                val codes = clean.codePoints().toArray().take(8).joinToString(",")
                dev.jev.wechatmood.core.MoodLog.w("STATS_BLANK_NAME len=${clean.length} codes=$codes")
            }
            return null
        }
        if (clean.startsWith("wxid_") || clean.matches(Regex("[A-Za-z0-9_-]{16,}"))) return null
        return shorten(clean)
    }

    /** 点击「未获取昵称#N」补一个名字，存在本机，之后统计和总结都用这个。 */
    private fun promptName(raw: String, shown: String) {
        kit.input("给 $shown 填个名字", "这个人在群里的昵称", manualNames[raw].orEmpty()) { name ->
            if (name.isBlank()) return@input
            GroupNameStore.save(activity, context.talker, raw, name)
            manualNames = GroupNameStore.load(activity, context.talker)
            dev.jev.wechatmood.core.MoodLog.i("STATS_NAME_MANUAL count=" + manualNames.size)
            renderTop(currentNames + mapOf(raw to name))
        }
    }

    private fun headerRow() = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = theme.dp(24)
        addView(theme.label("成员", ReplyMetrics.TEXT_CAPTION, theme.muted),
            LinearLayout.LayoutParams(0, -2, 1f))
        addView(theme.label("条数", ReplyMetrics.TEXT_CAPTION, theme.muted).apply { gravity = Gravity.END },
            LinearLayout.LayoutParams(theme.dp(56), -2))
        addView(theme.label("占比", ReplyMetrics.TEXT_CAPTION, theme.muted).apply { gravity = Gravity.END },
            LinearLayout.LayoutParams(theme.dp(52), -2))
    }

    private fun tableRow(name: String, count: String, percent: String) = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = theme.dp(28)
        addView(theme.label(name, ReplyMetrics.TEXT_BODY, theme.ink).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(theme.label(count, ReplyMetrics.TEXT_BODY, theme.muted).apply { gravity = Gravity.END },
            LinearLayout.LayoutParams(theme.dp(56), -2))
        addView(theme.label(percent, ReplyMetrics.TEXT_BODY, theme.muted).apply { gravity = Gravity.END },
            LinearLayout.LayoutParams(theme.dp(52), -2))
    }

    /** 点成员看他自己最近说了什么，不展示全员明细。 */
    private fun showMemberDetail(member: dev.jev.wechatmood.reply.MemberStat, shownName: String) {
        val lines = context.messages.filter { it.speaker == member.name }
            .takeLast(6)
            .joinToString("\n") { "· " + it.text.take(40) }
            .ifBlank { "没有可取的代表发言" }
        val format = java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA)
        val span = if (member.firstAt > 0) {
            format.format(java.util.Date(member.firstAt)) + "–" + format.format(java.util.Date(member.lastAt))
        } else ""
        kit.info("$shownName · ${member.count} 条",
            "活跃时段：${member.hours.joinToString("、") { "$it 点" }.ifBlank { "未知" }}\n" +
                "发言范围：${span.ifBlank { "未知" }}\n\n$lines")
    }

    /** 分析板块卡片：点标题折叠展开。 */
    private fun panelWith(label: String, content: View): LinearLayout {
        val card = kit.card()
        val header = kit.body(label).apply { setTypeface(typeface, android.graphics.Typeface.BOLD) }
        card.addView(header)
        card.addView(content, LinearLayout.LayoutParams(-1, -2).apply { topMargin = theme.dp(4) })
        header.setOnClickListener {
            content.visibility = if (content.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        return card
    }

    /**
     * 昵称显示规则：
     * 1. 数据库里查到备注/昵称就用它
     * 2. 查不到且看着像 wxid，标成「未获取昵称#N」，不裸奔原始 id
     * 3. 空白或纯符号，标成「匿名成员#N」，多个空名也能区分
     */
    private fun shorten(text: String): String = if (text.length > 10) text.take(10) + "…" else text

    private fun exportText(analysis: String): String = buildString {
        appendLine("群聊统计 · ${summaryText()}")
        appendLine("数据范围 ${spanText()}")
        appendLine("共 ${stats.total} 条 · ${stats.senders} 人发言")
        if (stats.topSenders.isNotEmpty()) {
            // 名字用表格里那套解析结果，复制出去别带 wxid。
            appendLine("最活跃：" + stats.topSenders.joinToString("、") {
                "${displayedNames[it.first] ?: it.first} ${it.second}"
            })
        }
        if (stats.activeHours.isNotEmpty()) {
            appendLine("活跃时段：" + stats.activeHours.joinToString("、") { "${it} 点" })
        }
        if (analysis.isNotBlank() && !analysis.startsWith("正在") && !analysis.startsWith("分析失败")) {
            appendLine()
            appendLine(analysis)
        }
    }.trim()

    /**
     * 群聊转述的提示词：先把主线说清，再把事一件件点到，最后收一句。
     * 素材里的 speaker 已经换成昵称，模型不该再看到 wxid。
     */
    private val summaryPrompt = """
        你在一个微信群里蹲了一整天，现在要跟旁边的人讲清楚这群人最近都聊了些什么。

        素材是这个时段的消息，按时间顺序，可能只抽了一部分，也可能有上下文缺失。

        怎么讲：
        - 开头一句话说清主线：这帮人最近主要在忙什么、反复出现的是什么话题。
        - 然后一件事一段。素材里能说清的事都要点到，通常三到六件，有几件写几件；
          不要只挑两件好笑的，也不要把两件不同的事并成一件。
        - 每件事都点出是谁说的、说了什么，能用原话就用原话，原话必须真的在素材里出现过。
        - 顺序按有画面感或者好笑的来排，不要按时间顺着讲，那是记流水账。
        - 结尾一句，可以吐槽，也可以给这帮人一句定性，别喊口号。
        - 素材里叫「群友N」的是没查到昵称的人，别猜他们是谁；[图片] [语音] 只当占位，不要猜内容。

        怎么说话：
        - 短句为主，可以有半截话，像边想边说。
        - 不要「首先、其次、最后」「虽然、但是」「不仅、而且」这类连接词，
          也不要「综上所述」「总而言之」「值得一提的是」「值得注意的是」。
        - 不分点、不编号、不加小标题、不用 Markdown、不用 emoji 当标题，
          不写「群氛围」「成员角色」「沟通建议」这种分析标题。
        - 不复述统计数字，不评价这个群好还是不好。
        - 如果确实只是闲聊没什么事，就照实说在聊什么，别硬编冲突，也别总结出什么群体规律。

        长度 500 字上下，最多八段。宁可把事说全，也不要为了短而漏掉明显的话题。

        只输出正文，不要写「以下是总结」这类开场。
    """.trimIndent()

    /** 一次请求拿到一段转述，顺带回报用了多少素材。 */
    private suspend fun analyzeAll(context: ReplyContext): Analysis {
        val settings = ModulePrefs.replySettings()
        if (!settings.isConfigured) {
            ModulePrefs.reload(force = true)
            kotlinx.coroutines.delay(1200)
        }
        val current = ModulePrefs.replySettings()
        check(current.isConfigured) {
            if (!ModulePrefs.bridgeAvailable) "设置服务未连接：先打开一次言外再回来分析" else "先在言外「回复」里配置模型"
        }
        // 用筛选后的消息做素材：以前只取页面里最近几条，统计一千多条却只总结十几条。
        val pool = selectedMessages().ifEmpty { context.messages }
        val resolved = namesFor(pool.map { it.speaker }.distinct())
        // 按整段抽样：单条均匀抽会把对话抽成碎片，模型看不出谁在接谁。
        // 抽完再按字符预算收缩，条数多到撑爆上下文会直接 400。
        var picked = GroupTranscript.pick(pool.size)
        var transcript = GroupTranscript.lines(picked, pool, resolved)
        while (transcript.first.joinToString("\n").length > MATERIAL_CHARS && picked.size > 24) {
            picked = GroupTranscript.pick(pool.size, limit = picked.size / 2)
            transcript = GroupTranscript.lines(picked, pool, resolved)
        }
        var body = transcript.first
        var labels = transcript.second
        // 统计行也换成昵称：以前直接把 wxid 喂给模型，转述里就冒出 wxid。
        val statsLine = buildString {
            append("共 ${stats.total} 条 · ${stats.senders} 人参与 · 人均 ${stats.perCapita} 条")
            val top = stats.members.filter { it.name != "我" }.take(6)
                .map { "${GroupTranscript.label(it.name, resolved[it.name]) ?: it.name} ${it.count} 条" }
            if (top.isNotEmpty()) append("；发言最多：").append(top.joinToString("、"))
        }
        // 长回答走非流式更稳：非流式只受读超时约束，这里单独放宽到 3 分钟。
        val client = ReplyHttpClient(HttpClients.shared.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS).callTimeout(240, TimeUnit.SECONDS).build())
        var maxTokens = 4000
        var partial = ""
        var lastFinish = ""
        repeat(2) { attempt ->
            val payload = JSONObject().put("model", current.model).put("stream", false)
                .put("max_tokens", maxTokens).put("temperature", 0.35)
                .put("messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", summaryPrompt))
                    .put(JSONObject().put("role", "user").put("content",
                        "统计：$statsLine\n该时段消息（原始 ${pool.size} 条，按时间顺序抽了 ${body.size} 条）：\n" +
                            body.joinToString("\n"))))
            val answer = try {
                withContext(Dispatchers.IO) {
                    client.request(current, payload, parse = { raw ->
                        val choice = JSONObject(raw).getJSONArray("choices").getJSONObject(0)
                        val message = choice.getJSONObject("message")
                        val content = message.optString("content").trim()
                        val reasoning = message.optString("reasoning_content").trim()
                        val finish = choice.optString("finish_reason")
                        dev.jev.wechatmood.core.MoodLog.i(
                            "STATS_AI_RAW attempt=${attempt + 1} content=${content.length} " +
                                "reasoning=${reasoning.length} finish=$finish")
                        Triple(content, finish, reasoning.length)
                    })
                }
            } catch (error: IllegalStateException) {
                // 上下文超长：把素材砍一半再来一次，比让用户回去改筛选省事。
                if (error.message?.contains("上下文超出") == true && attempt == 0 && body.size > 30) {
                    picked = GroupTranscript.pick(pool.size, limit = body.size / 2)
                    transcript = GroupTranscript.lines(picked, pool, resolved)
                    body = transcript.first
                    labels = transcript.second
                    dev.jev.wechatmood.core.MoodLog.w("STATS_AI_SHRINK lines=${body.size}")
                    return@repeat
                }
                throw error
            }
            val clean = TextClean.plain(answer.first).trim()
            lastFinish = "finish=${answer.second} reasoning=${answer.third} chars=${clean.length} tokens=$maxTokens"
            dev.jev.wechatmood.core.MoodLog.i("STATS_AI_ALL attempt=${attempt + 1} $lastFinish")
            // finish_reason=length 说明话被掐在半路，直接重试要更多 token，不把半截文本当结果。
            if (clean.isNotBlank() && answer.second != "length") {
                val unnamed = labels.values.count { it.startsWith("群友") }
                dev.jev.wechatmood.core.MoodLog.i(
                    "STATS_AI_POOL pool=${pool.size} lines=${body.size} speakers=${labels.size} unnamed=$unnamed")
                return Analysis(clean.take(3000), body.size, pool.size)
            }
            if (clean.length > partial.length) partial = clean
            maxTokens = 8000
        }
        if (partial.isNotBlank()) {
            dev.jev.wechatmood.core.MoodLog.w("STATS_AI_TRUNCATED chars=${partial.length}")
            return Analysis(partial.take(3000), body.size, pool.size, "输出被长度上限截断，可以再点一次重试")
        }
        throw IllegalStateException("模型没返回正文（$lastFinish，素材 ${body.size} 条）")
    }

    private fun copy(text: String) {
        (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("言外统计", text))
        Toast.makeText(activity, "已复制", Toast.LENGTH_SHORT).show()
    }

    private fun filterPill(label: String, action: () -> Unit): TextView = kit.pill(label, action)

    private fun pickRange() {
        val values = StatsFilter.Range.values()
        kit.picker("分析时段", values.map { it.label }, values.indexOf(StatsFilter.range)) { which ->
            StatsFilter.range = values[which]
            dev.jev.wechatmood.core.MoodLog.i("STATS_FILTER range=${StatsFilter.range}")
            onFilterChanged?.invoke()
        }
    }

    private fun pickLimit() {
        val options = listOf(10, 30, 50, 100, 0)
        kit.picker("消息条数", options.map { if (it == 0) "该时段全部" else "最近 $it 条" },
            options.indexOf(StatsFilter.limit)) { which ->
            StatsFilter.limit = options[which]
            dev.jev.wechatmood.core.MoodLog.i("STATS_FILTER limit=${StatsFilter.limit}")
            onFilterChanged?.invoke()
        }
    }

    private fun weight() = LinearLayout.LayoutParams(0, -2, 1f)
    /** 数字块：整块排版固定，只把数字视图交出去，换筛选时改文字不重排。 */
    private fun number(label: String): Pair<LinearLayout, TextView> {
        val value = TextView(activity).apply {
            textSize = 20f
            setTextColor(theme.ink)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            minimumHeight = theme.dp(ReplyMetrics.TAP)
            setPadding(0, theme.dp(6), 0, theme.dp(6))
            addView(value)
            addView(TextView(activity).apply {
                text = label
                textSize = ReplyMetrics.TEXT_CAPTION
                setTextColor(theme.muted)
            })
        }
        return box to value
    }

    private fun renderActiveHours(box: LinearLayout) {
        box.removeAllViews()
        if (stats.activeHours.isEmpty()) return
        box.addView(kit.sectionLabel("活跃时段"))
        box.addView(kit.caption(stats.activeHours.joinToString("、") { "${it} 点" }))
    }
    private fun row(name: String, value: String) = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = theme.dp(40)
        setPadding(0, theme.dp(2), 0, theme.dp(2))
        addView(TextView(activity).apply {
            text = name
            textSize = 13f
            setTextColor(theme.ink)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(TextView(activity).apply {
            text = value
            textSize = 13f
            setTextColor(theme.muted)
        }, LinearLayout.LayoutParams(-2, -2))
    }
}
