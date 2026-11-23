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
import dev.jev.wechatmood.core.RelationFactBridge
import dev.jev.wechatmood.core.TextClean
import dev.jev.wechatmood.core.UsageKind
import dev.jev.wechatmood.reply.GroupStats
import dev.jev.wechatmood.reply.DigestArchive
import dev.jev.wechatmood.reply.GroupStatsCalculator
import dev.jev.wechatmood.reply.GroupTranscript
import dev.jev.wechatmood.reply.ReplyContactKey
import dev.jev.wechatmood.reply.ReplyContext
import dev.jev.wechatmood.reply.ReplyHttpClient
import dev.jev.wechatmood.reply.StatRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    private val accountScope: String? = null) {
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
    /** 数据来源说明：账号没确认、或库里这个时段没消息时写在界面上，别让人以为筛选没生效。 */
    private var sourceNote: String = ""
    /** 上一周期的条数；null 表示这次没有可比的上一周期（账号没确认、刚过零点、库没查到）。 */
    private var previousTotal: Int? = null
    /** 「与上一时段对比」那一行；没有结论时整行收起，不占位。 */
    private var compareLine: TextView? = null
    /** 进行中的分析请求；切筛选或重新点一次时取消，别让旧范围白花钱。 */
    private var analysisJob: Job? = null
    /** 请求代次：回来时对不上就说明筛选换过了，结果直接丢掉。 */
    private var analysisEpoch = 0
    /** wxid → 微信页面上显示的昵称，来自消息对象自带的显示名，比库里的准。 */
    private var pageNames: Map<String, String> = emptyMap()
    /** wxid → 引用消息里带出来的昵称，补上库里没有的非好友。 */
    private var quotedNames: Map<String, String> = emptyMap()
    /** wxid → @ 提及里带出来的昵称。 */
    private var atNames: Map<String, String> = emptyMap()
    /** wxid → 手动补的昵称，优先级最高，点未获取昵称那一行就能填。 */
    private var manualNames: Map<String, String> = emptyMap()
    /** 一次分析的结果，附带用了多少素材，界面上要看得出覆盖范围。 */
    private data class Analysis(val text: String, val material: Int, val total: Int, val note: String = "")
    /** 群聊转述和私聊转述的口吻、长度要求不一样，提示词各用一套。 */
    private val isGroup = context.talker.endsWith("@chatroom")

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
        dev.jev.wechatmood.core.MoodLog.i("STATS_POOL source=${source.size} byTime=${byTime.size} " +
            "type34=${byTime.count { it.type == 34 }} voice=${byTime.count { it.voice != null }} " +
            "fromDb=${source === dbMessages}")
        return if (StatsFilter.limit > 0) byTime.takeLast(StatsFilter.limit) else byTime
    }

    init { stats = computeStats() }

    /** 上一次记录的「没有发送者」类型分布，只在变化时写日志。 */
    private var blankSignature: String? = null

    /** 按当前筛选重算，只读内存里的消息，不碰数据库。 */
    private fun computeStats(): GroupStats {
        // 群里有些消息 content 不带发送者前缀（系统提示、部分媒体），单独记类型分布，
        // 否则它们会全被并成一个「未获取昵称」，看着像某个人没说名字。
        val blanks = selectedMessages().filter { it.senderId.isBlank() }
        val signature = if (blanks.isEmpty()) "" else blanks.groupingBy { it.type }.eachCount().entries
            .sortedByDescending { it.value }.joinToString(",") { it.key.toString() + ":" + it.value }
        if (blanks.isNotEmpty() && signature != blankSignature) {
            dev.jev.wechatmood.core.MoodLog.w("STATS_NO_SENDER n=" + blanks.size + " types=" + signature)
        }
        blankSignature = signature
        val records = selectedMessages().map {
            StatRecord(normalizedMillis(it.time), if (it.speaker == "我") 1 else 0, it.type, it.text, it.speaker)
        }
        val base = GroupStatsCalculator.compute(records, sampled = context.trimmed)
        // 库里的消息带真实 type，图片语音按自己这一份算。
        // 页面消息拿不到类型，只能按「没进上下文的条数」补，否则永远是 0。
        if (dbMessages.isNotEmpty()) return base
        val voices = context.messages.count { it.voiceState == dev.jev.wechatmood.voice.VoiceState.READY }
        return base.copy(media = context.omittedMedia, voice = voices)
    }

    /** 微信不同版本的时间有秒有毫秒，统计前统一成毫秒，否则活跃时段会算到 1970 年。 */
    private fun normalizedMillis(time: Long): Long = when {
        time <= 0 -> 0L
        time > 1_000_000_000_000L -> time
        else -> time * 1000
    }

    /** 说明行：跟着筛选走，切时段时只换文字。 */
    /** 时段的人话版本；界面和提示词共用，免得两处说法对不上。 */
    private fun windowLabel(range: StatsFilter.Range = StatsFilter.range): String = when (range) {
        StatsFilter.Range.TODAY -> "今天 00:00 到现在"
        else -> range.label
    }

    /** 上面一行放数量类信息（范围、条数、人数、人均），大字那排只放消息类型。 */
    private fun spanLineText(): String =
        "数据范围 ${spanText()} · ${stats.total} 条 · ${stats.senders} 人发言 · 人均 ${stats.perCapita} 条 · 只在本机统计"

    private fun analysisTitle() = if (isGroup) "群里在聊什么" else "你们在聊什么"

    private fun aiButtonLabel() = if (isGroup) "让 AI 说说这群在聊什么" else "让 AI 说说你们在聊什么"

    private fun spanText(messages: List<dev.jev.wechatmood.reply.ReplyMessage> = selectedMessages()): String {
        // 用筛选后的消息算范围：以前拿页面消息，会出现「1011 条却只有 18 分钟」这种对不上的显示。
        val stamps = messages.map { normalizedMillis(it.time) }.filter { it > 0 }
        if (stamps.isEmpty()) return "暂无数据"
        val format = java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA)
        return format.format(java.util.Date(stamps.min())) + "–" + format.format(java.util.Date(stamps.max()))
    }

    /** 换筛选后按新条件重新查库。以前只重算内存里的旧数据，条数调大不会生效。 */
    private suspend fun reloadDb() {
        if (accountScope == null) {
            // 账号没确认时不查库（会混进另一个账号同群的消息），界面要说清楚只剩页面数据。
            sourceNote = "没确认当前登录账号，只统计页面上可见的 " + context.messages.size + " 条"
            dev.jev.wechatmood.core.MoodLog.i("STATS_RELOAD_NO_SCOPE page=" + context.messages.size)
            // 没查库也就没有上一周期，对比行跟着收起。
            previousTotal = null
            updateCompareLine()
            return
        }
        val limit = if (StatsFilter.limit == 0) 5000 else StatsFilter.limit
        val rows = runCatching {
            ReplyDatabaseHistory.statMessages(context.talker, StatsFilter.sinceMillis(), limit, accountScope)
        }.getOrDefault(emptyList())
        dev.jev.wechatmood.core.MoodLog.i("STATS_RELOAD rows=" + rows.size + " limit=" + limit + " range=" + StatsFilter.range)
        if (rows.isEmpty()) {
            sourceNote = "库里没有这个时段的消息，页面上的 " + context.messages.size + " 条也不在范围内"
            dev.jev.wechatmood.core.MoodLog.i("STATS_RELOAD_EMPTY range=" + StatsFilter.range)
            // 当前窗口都空了，上一周期的数也不能留：否则对比行会拿旧结论配新空白。
            previousTotal = null
            updateCompareLine()
            return
        }
        sourceNote = ""
        val buildStarted = android.os.SystemClock.elapsedRealtime()
        dbMessages = rows.map {
            dev.jev.wechatmood.reply.ReplyMessage(it.messageId, it.speaker(), it.createdAt,
                // 合并转发先摊平成「谁说了什么」，卡片取标题/描述，实在没有才留占位。
                it.analysisText() ?: it.evidenceBody() ?: "[媒体]", voice = it.voiceSource(),
                senderId = it.senderId(), type = it.type)
        }
        // 诊断：库里查出来的语音引用，到 ReplyMessage 里还剩多少。
        dev.jev.wechatmood.core.MoodLog.i("STATS_DB_VOICE rows=${rows.size} " +
            "type34=${rows.count { it.type == 34 }} src=${rows.count { it.voiceSource() != null }} " +
            "built=${dbMessages.size} voice=${dbMessages.count { it.voice != null }} " +
            "waiting=${dbMessages.count { it.text == dev.jev.wechatmood.voice.VoiceText.WAITING }} " +
            "took=${android.os.SystemClock.elapsedRealtime() - buildStarted}ms")
        stats = computeStats()
        logBlankSamples()
        // 对比走同一条按时间查库的路：跟着这次刷新一起数一遍上一周期，不另开请求路径。
        refreshCompare()
    }

    /**
     * 诊断：把「没有发送者」的几条消息在库里的原样打出来。
     * 优先看表情和图片：文本类没有前缀的都是自己发的，不需要看。
     */
    private suspend fun logBlankSamples() {
        val samples = dbMessages.filter { it.senderId.isBlank() && (it.type == 47 || it.type == 3) }
            .take(4)
        for (sample in samples) {
            val row = runCatching {
                ReplyDatabaseHistory.messageRow(context.talker, sample.id, accountScope)
            }.getOrNull().orEmpty()
            dev.jev.wechatmood.core.MoodLog.w("STATS_BLANK_ROW type=" + sample.type +
                " mine=" + (sample.speaker == "我") + " id=" + sample.id + " " +
                row.entries.joinToString(" ") { it.key + "=" + it.value })
        }
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
        // 「共 N 条 / M 人发言」已经是大字里的数字，这里只留数据范围和人均，不重复。
        val spanLine = kit.caption(spanLineText())
        val sourceLine = kit.caption(sourceNote)
        // 与上一周期对比：一行结论，不画图表；没有可比的上一周期就整行不占位。
        val compare = kit.caption("")
        compareLine = compare
        captionLine(compare, "")
        body.addView(spanLine)
        body.addView(sourceLine)
        body.addView(compare)
        captionLine(sourceLine, sourceNote)

        val numbers = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, theme.dp(14), 0, theme.dp(6))
        }
        // 上面就是全部的统计，下面只留成员表：条数和人数写在数据范围那一行，这里按类型分格。
        val cells = (dev.jev.wechatmood.reply.GroupStatsCalculator.KIND_LABELS.map { number(it.second) } +
            number("其它"))
        cells.forEach { numbers.addView(it.first, weight()) }
        fillNumbers(cells)
        body.addView(numbers, LinearLayout.LayoutParams(-1, -2))
        body.addView(LinearLayout(activity), LinearLayout.LayoutParams(-1, theme.dp(6)))
        body.addView(topContainer, LinearLayout.LayoutParams(-1, -2))
        pageNames = pageSpeakerNames()
        manualNames = GroupNameStore.load(activity, context.talker)
        if (dbMessages.isEmpty()) {
            // 只有页面消息时，条数只含文字：媒体在回复上下文里被剔掉，单独按本页计数。
            sourceNote = "只有页面上的文字消息；图片语音按本页单独计数"
            captionLine(sourceLine, sourceNote)
        }
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
        /** 把一段结果摆进面板：手动分析、切筛选命中缓存都走这里，显示口径才会一致。 */
        fun renderAnalysis(analysis: Analysis, scrollToBottom: Boolean = true) {
            analysisText = analysis.text
            val failed = analysis.text.startsWith("分析失败") || analysis.text.isBlank()
            val shown = analysis.text.ifBlank { "（模型返回空内容）" }
            analysisBox.removeAllViews()
            analysisBox.visibility = View.VISIBLE
            val content = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                if (analysis.material > 0) {
                    addView(kit.caption("素材 ${analysis.material}/${analysis.total} 条 · 按整段抽样"))
                }
                if (analysis.note.isNotBlank()) addView(kit.caption(analysis.note))
                addView(kit.body(shown).apply { if (failed) setTextColor(theme.error) })
            }
            analysisBox.addView(panelWith(analysisTitle(), content),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = theme.dp(8) })
            aiButton.text = "重新分析"
            aiButton.isEnabled = true
            if (scrollToBottom) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
        aiButton = theme.action(aiButtonLabel(), primary = true) {
            // 素材、范围、名字、统计都在点击这一刻拍照：请求期间界面怎么变，这一轮说的还是同一份数据。
            val epoch = ++analysisEpoch
            val requestPool = selectedMessages()
            val requestRange = StatsFilter.range
            val requestLimit = StatsFilter.limit
            val requestNames = displayedNames.toMap()
            val requestStats = stats
            analysisJob?.cancel()
            aiButton.isEnabled = false
            aiButton.text = "正在分析…"
            analysisBox.visibility = View.VISIBLE
            analysisBox.removeAllViews()
            val loading = kit.loading().apply { showLoading("正在分析最近聊天…") }
            analysisBox.addView(panelWith(analysisTitle(), loading),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = theme.dp(8) })
            analysisJob = scope.launch {
                val analysis = try {
                    analyzeAll(context, requestPool, requestRange, requestLimit, requestNames, requestStats) { text ->
                        if (epoch == analysisEpoch) loading.showLoading(text)
                    }
                } catch (cancel: kotlinx.coroutines.CancellationException) {
                    throw cancel
                } catch (error: Throwable) {
                    dev.jev.wechatmood.core.MoodLog.w("STATS_AI_FAILED " + error.message)
                    Analysis("分析失败：" + (error.message ?: "请重试"), 0, 0)
                }
                if (epoch != analysisEpoch) {
                    // 筛选已经换过或又点了一次，这份结果对应的是旧数据，别摆上来。
                    dev.jev.wechatmood.core.MoodLog.i("STATS_AI_DISCARD epoch=" + epoch)
                    return@launch
                }
                // 先落盘再摆上：结论只存本机，翻历史纪要时不用再花一次模型钱。
                archive(analysis, requestPool, requestRange)
                renderAnalysis(analysis)
            }
        }
        // 切换筛选：只改数字、表格和时段，不关窗口、不重建面板，滚动位置也保留。
        onFilterChanged = {
            // 作废进行中的分析：取消请求，旧范围的结果也不再往新范围上摆。
            analysisEpoch++
            analysisJob?.cancel()
            analysisJob = null
            stats = computeStats()
            rangePill.text = "时段 · ${StatsFilter.range.label}"
            limitPill.text = "条数 · ${StatsFilter.limitLabel}"
            spanLine.text = spanLineText()
            captionLine(sourceLine, sourceNote)
            // 上一周期的数还没查：先收起对比行，等 reloadDb 里数完再显示，别拿旧窗口的结论配新数字。
            previousTotal = null
            updateCompareLine()
            fillNumbers(cells)
            renderTop(currentNames)
            renderActiveHours(activeBox)
            // 换了范围，旧的分析对不上新数据，先清掉；同一范围再切回来会从缓存里补回。
            analysisBox.removeAllViews()
            analysisBox.visibility = View.GONE
            analysisText = ""
            aiButton.text = aiButtonLabel()
            aiButton.isEnabled = true
            scope.launch {
                // 新筛选可能要看更多消息：先重查，再刷新数字与表格。
                reloadDb()
                spanLine.text = spanLineText()
                captionLine(sourceLine, sourceNote)
                fillNumbers(cells)
                renderTop(currentNames)
                renderActiveHours(activeBox)
                renderTop(currentNames + namesFor(stats.members.map { it.name }))
                cachedAnalysis()?.let { renderAnalysis(it) }
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

        // 昵称在数据库里，异步查一次再替换 wxid；同范围的分析结果还在的话直接摆出来。
        scope.launch {
            logBlankSamples()
            renderTop(namesFor(stats.members.map { it.name }))
            // 打开窗口时也算一次对比：不然这一行要等用户切一次筛选才出现。
            refreshCompare()
            // 打开窗口时命中的缓存不抢滚动位置：先让人看到数字，再自己往下翻。
            cachedAnalysis()?.let { renderAnalysis(it, scrollToBottom = false) }
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
            if (resolved == null) {
                // 查不到就用日志说清：库里到底有没有这个名字、长什么样。
                dev.jev.wechatmood.core.MoodLog.w("STATS_NAME_UNRESOLVED raw=" + member.name.take(16) +
                    " db=" + (names[member.name]?.take(16) ?: "none") + " count=" + member.count)
            }
            // 没有发送者的那堆消息（系统提示、部分媒体）不是某个人，不要冒充「未获取昵称#N」。
            val unattributed = member.name.startsWith("对方（群成员未知")
            val shown = resolved ?: if (unattributed) "未署名消息" else "未获取昵称#${++unknown}"
            displayedNames[member.name] = shown
            merged[shown] = (merged[shown] ?: 0) + member.count
            rawByShown.putIfAbsent(shown, member.name)
        }
        topContainer.addView(headerRow(), LinearLayout.LayoutParams(-1, -2))
        val ordered = merged.entries.sortedByDescending { it.value }
        val percents = dev.jev.wechatmood.reply.GroupStatsCalculator.percents(ordered.map { it.value })
        ordered.forEachIndexed { index, entry ->
            val row = tableRow(entry.key, "${entry.value}", "%.1f%%".format(percents[index]))
            val raw = rawByShown[entry.key]
            if (entry.key.startsWith("未获取昵称") && raw != null) {
                row.setOnClickListener { promptName(raw, entry.key) }
            }
            topContainer.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        if (unknown > 0) {
            // 只说人话：分层命中数留在 STATS_NAMES 日志里，不往界面上堆。
            topContainer.addView(kit.caption("其中 $unknown 人没查到昵称，点一下可以手动补").apply {
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
        val at = runCatching {
            ReplyDatabaseHistory.atNames(context.talker, StatsFilter.sinceMillis(), accountScope)
        }.getOrDefault(emptyMap())
        atNames = at
        // 手动补的名字放最后，覆盖前面所有的自动结果。
        return db + quoted + at + pageNames + manualNames
    }

    private fun resolveName(raw: String, names: Map<String, String>): String? {
        // 数据库里有名字优先用；没有就用原始名字。只要不是空白就显示，截断即可。
        val fromDb = names[raw]?.let { dev.jev.wechatmood.core.TextClean.name(it) }?.takeIf { it.isNotEmpty() }
        val clean = (fromDb ?: raw).trim()
        // 数据库里缺失的字段会写 null / uinull，那不是名字。
        // 只有真的空白才算没名字：「null」这个名字是用户自己取的，微信会照原样显示。
        if (clean.isEmpty()) return null
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
            // 补过名字以后这个群旧的分析文本里还是「未获取昵称」，只作废本群的缓存。
            val prefix = context.talker + "|"
            summaryCache.keys.filter { it.startsWith(prefix) }.toList().forEach { summaryCache.remove(it) }
            dev.jev.wechatmood.core.MoodLog.i("STATS_NAME_MANUAL count=" + manualNames.size)
            renderTop(currentNames + mapOf(raw to name))
        }
    }

    /** 成员表只有两列：名字（条数）和占比，条数并进名字里，表格不再被挤成三块。 */
    private fun headerRow() = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = theme.dp(24)
        addView(theme.label("成员（条数）", ReplyMetrics.TEXT_CAPTION, theme.muted),
            LinearLayout.LayoutParams(0, -2, 1f))
        addView(theme.label("占比", ReplyMetrics.TEXT_CAPTION, theme.muted).apply { gravity = Gravity.END },
            LinearLayout.LayoutParams(theme.dp(52), -2))
    }

    private fun tableRow(name: String, count: String, percent: String) = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = theme.dp(28)
        addView(theme.label("$name（$count 条）", ReplyMetrics.TEXT_BODY, theme.ink).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
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

    /** 复制出去的文本：一行一件事，成员写成「名字（N 条）」，别让人自己对着两列数字对齐。 */
    private fun exportText(analysis: String): String = buildString {
        appendLine("群聊统计 · ${StatsFilter.range.label} · ${StatsFilter.limitLabel}")
        appendLine("共 ${stats.total} 条 · ${stats.senders} 人发言 · 人均 ${stats.perCapita} 条")
        appendLine("数据范围 ${spanText()}")
        if (stats.topSenders.isNotEmpty()) {
            // 名字用表格里那套解析结果，复制出去别带 wxid。
            appendLine("说得最多：" + stats.topSenders.joinToString("、") {
                "${displayedNames[it.first] ?: it.first}（${it.second} 条）"
            })
        }
        if (stats.kindSummary().isNotBlank()) {
            appendLine("消息构成：" + stats.kindSummary())
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
     * 群聊转述的提示词：三十秒说清主线，只挑最大的两三个话题，不逐人报价。
     * 早先那版要求「能说清的事都要点到、每件事都点出是谁说的、能说全就说全」，
     * 模型照做就成了照抄聊天记录的流水账——要求写全，等于要求复述。
     * 素材里的 speaker 已经换成昵称，模型不该再看到 wxid。
     */
    private val groupSummaryPrompt = """
        你在一个微信群里待了一整天，现在用三十秒跟没进群的人讲清楚这群人在折腾什么。

        好笑、具体、有画面感，但只准用素材里真有的东西：人、事、数字、原话、分歧。
        热闹不是靠形容词堆出来的，是靠把某件荒唐的小事说清楚。

        只写三段，全文 250 到 420 字。超过 420 字这版就算废了。

        第一段，一句：这群人这阵子最较劲的是哪件事。要用最具体的那一版说法（谁、为了什么、卡在哪），
        别写成「最近大家在讨论……」这种开场。

        第二段，两到三句：挑最大的两到三个话题，每个写清「怎么起的、谁在坚持、最后怎样」。
        允许用一句群里的原话当包袱，但别把聊天记录复述一遍。

        第三段，一句收尾：吐槽、反差，或者给这帮人贴个小标签。别喊口号，别上价值。

        想写得有意思，可以挑一两个用，别全上：
        - 抓荒诞点：为很小的事认真争论半天，或者前后说法对不上。
        - 用群里的原话当包袱，不要自己造梗。
        - 把分歧原样摆出来，别和稀泥，也别替他们下结论。

        硬性要求：
        - 统计行只用来判断热度，不要写进正文，也别提「多少条」「多少人」。
        - 合并。十个人围着同一件事说十句，那是同一件事。
        - 不逐人报价。禁止「甲说……乙说……丙又接……」；一个话题最多点两个昵称，
          只有这个人真的带着观点时才点名。
        - 引用克制。全文最多三句原话，不引也能说清的地方就不要引。
        - 小事不写。一两个人搭话、没结论的插科打诨，半句带过或者干脆不写。
        - 宁可漏，不要全。漏掉边角话题没关系，主线错了才叫失败。
        - 素材少就写短：不到 40 条消息，120 到 250 字就算完；就是普通闲聊，就老实说他们在闲扯什么，
          别硬编冲突，也别总结出什么群体规律。
        - 【图片：…】是对图片的一句话描述，可以当内容用；【图片，未识别】和 [媒体] 才是读不到内容的占位，不要猜。
          「未获取昵称#N」是没查到昵称的人，别猜是谁。
        - 语音已转写的就按普通发言读；「【语音，未转写】」「【语音未能转写，内容未知】」是读不到内容的占位，
          不要猜它说了什么，也不要提「转写」「占位」「系统状态」，不要因为素材少就吐槽或编内容。
        - 不评价这个群好不好，不用「首先/其次」「总而言之」「值得一提的是」，不加小标题，不编号。

        语气像边想边说的口头转述，短句，可以刻薄一点。

        示范（只学密度和口吻，内容别照抄，人名价格都是编的）：
        这两天主要在折腾搬家，一边比价一边骂中介，顺带问了句周末谁有空搭把手。
        主力是两个人，一个坚持自己搬能省一半，一个说上次闪了腰不值当，最后折中找了半日搬家公司，还被「半天也算一天」这句话堵了回去。搬完顺带聊到楼下停车位，有人报 300 一个月，贵得没人接话。
        倒是「谁顺手带个早饭」这点小事吵了半天，几个人排队点单，剩下的都在笑话他们。

    """.trimIndent()

    /** 私聊用的是「转述这段聊天」的提示词：同一套写全的要求用在两个人身上更像流水账。 */
    private val privateSummaryPrompt = """
        你和一个人聊了一整天，现在用三十秒跟朋友转述这段聊天在聊什么。

        只写两段，全文 200 到 350 字。超过 350 字这版就算废了。

        第一段，一两句，说清这段聊天主要在聊什么、聊到哪一步了（约好了什么、还在等什么答复）。
        第二段，挑两三个具体的事，一个一两句，写清楚对方的态度或者你们的分歧。

        硬性要求：
        - 只写对方和我，不要出现第三个人，也不要把自己说过的话逐句复述一遍。
        - 合并。同一件事来回说了十句，那是同一件事，不是十件。
        - 引用克制，全文最多两句原话；不引也能说清的地方就不要引。
        - 小事不写。只有一两句寒暄、没结论的，半句带过或者干脆不写。
        - 宁可漏，不要全。漏掉边角话题没关系，主线错了才叫失败。
        - 素材少就写短，别硬凑：不到 40 条消息，写到 100 到 200 字就算完；
          如果只是日常寒暄没什么事，就照实说，别硬编出情绪和矛盾。
        - 【图片：…】是对图片的一句话描述，可以当内容用；【图片，未识别】和 [媒体] 才是读不到内容的占位，不要猜。
        - 语音已转写的就按普通发言读；「【语音，未转写】」「【语音未能转写，内容未知】」是读不到内容的占位，
          不要猜它说了什么，也不要提「转写」「占位」「系统状态」，不要因为素材少就吐槽或编内容。
        - 不写统计数字，不评价这段关系，不用「首先/其次」「总而言之」「值得一提的是」，
          不加小标题，不编号。

        语气像边想边说的口头转述，短句。

        示范（只学密度和口吻，内容别照抄）：
        这两天主要在说周末见面的事，从去哪吃饭聊到几点结束，最后定在周六下午，具体地方还没定。
        一个想找离家近的，另一个嫌那边没好吃的，来回推了三四个店，谁也没拍板。中间还夹了句「你上次说的那家关了」，于是又绕回起点。

        只输出正文，不要写「以下是总结」这类开场，也不要解释你是怎么写的。
    """.trimIndent()

    /** 一次请求拿到一段转述，顺带回报用了多少素材。 */
    /**
     * 把这次的转述留下来。
     *
     * 只存模型写出的结论，不存聊天素材；同一时段重复分析由 [DigestArchive] 的派生 id 去重。
     * 落盘失败不影响这次分析照常显示，只记日志。
     */
    private suspend fun archive(analysis: Analysis, pool: List<dev.jev.wechatmood.reply.ReplyMessage>,
        range: StatsFilter.Range) {
        val key = ReplyContactKey.of(accountScope, context.talker, allowGroup = true) ?: run {
            dev.jev.wechatmood.core.MoodLog.i("DIGEST_ARCHIVE_SKIP key")
            return
        }
        // 库里是毫秒、页面消息常是秒：不归一化会让同一结论派生两个 id，去重失效。
        val digest = DigestArchive.build(windowLabel(range), normalizedMillis(pool.firstOrNull()?.time ?: 0),
            normalizedMillis(pool.lastOrNull()?.time ?: 0), analysis.material, analysis.total, analysis.text) ?: run {
            dev.jev.wechatmood.core.MoodLog.i("DIGEST_ARCHIVE_SKIP empty")
            return
        }
        withContext(Dispatchers.IO) { RelationFactBridge.saveDigest(activity, key, digest) }
        dev.jev.wechatmood.core.MoodLog.i("DIGEST_ARCHIVE_SAVED id=" + digest.id +
            " label=" + digest.rangeLabel + " material=" + digest.material + " total=" + digest.total)
    }

    private suspend fun analyzeAll(context: ReplyContext,
        pool: List<dev.jev.wechatmood.reply.ReplyMessage>, range: StatsFilter.Range, limit: Int,
        shownNames: Map<String, String>, statsSnapshot: GroupStats,
        onStage: (String) -> Unit = {}): Analysis {
        val settings = ModulePrefs.replySettings()
        if (!settings.isConfigured) {
            ModulePrefs.reload(force = true)
            kotlinx.coroutines.delay(1200)
        }
        val current = ModulePrefs.replySettings()
        check(current.isConfigured) {
            if (!ModulePrefs.bridgeAvailable) "设置服务未连接：先打开一次言外再回来分析" else "先在言外「回复」里配置模型"
        }
        // 素材由点击那一刻快照传进来：请求期间切了筛选，也不会拿新范围的数据配旧提示词。
        // 筛选后为空也不再偷偷退回整页消息——那会让「最近 1 小时」的分析里出现昨天的话。
        check(pool.isNotEmpty()) { "这个时段没有消息，把范围放宽一点再试" }
        // 诊断：素材里到底有多少条语音/图片，以及 voice 引用是否带上了。
        dev.jev.wechatmood.core.MoodLog.i("STATS_MEDIA_SCAN total=${pool.size} " +
            "type34=${pool.count { it.type == 34 }} voices=${pool.count { it.voice != null }} " +
            "waiting=${pool.count { it.text == dev.jev.wechatmood.voice.VoiceText.WAITING }} " +
            "images=${pool.count { it.type == 3 }} media=${pool.count { it.text == "[媒体]" }}")
        // 语音在库里只有「【语音，等待转写】」占位：先按上限逐条转写，转出来的当正文喂模型。
        // 没转的留明确占位（未转写 / 未能转写），提示词里说清那是读不到内容，
        // 别让模型去评论占位符本身，也别让它以为「都在发语音」就是聊了什么。
        val waitingVoices = pool.count { it.text == dev.jev.wechatmood.voice.VoiceText.WAITING }
        // 先按条数抽样，再只处理抽中的语音和图片：反过来会把最后没进素材的消息也转一遍
        // （106 条只喂 56 行时尤其明显，转了等于白等）。
        var picked = GroupTranscript.sample(pool.size)
        val pickedRows = picked.mapNotNull { pool.getOrNull(it) }
        // 最近优先：群总结最该看到刚说完的话，和抽样「最后一窗锚定最新消息」保持一致。
        val voiceRows = pickedRows.filter { it.voice != null && it.text == dev.jev.wechatmood.voice.VoiceText.WAITING }
            .asReversed()
        // 预算只算真正要调原生转写的条数：命中缓存的直接拿来，不再占额度，
        // 这样语音多于 10 条的群，第二次分析会接着转下一批，不会永远卡在前 10 条。
        val uncached = voiceRows.count { synchronized(voiceTextCache) { voiceTextCache[it.id] } == null }
        val transcriptBudget = minOf(uncached, MAX_STATS_TRANSCRIPTS)
        val transcripts = HashMap<Long, String>()
        val tried = HashSet<Long>()
        var transcribed = 0
        for (row in voiceRows) {
            val source = row.voice ?: continue
            val cached = synchronized(voiceTextCache) { voiceTextCache[row.id] }
            if (cached != null) { transcripts[row.id] = cached; continue }
            if (transcribed >= transcriptBudget) continue
            transcribed++
            tried += row.id
            onStage("正在转写语音 ${transcribed}/${transcriptBudget}…")
            val text = try { NativeVoiceBridge.transcribe(source) { true }.trim() }
                catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
                catch (_: Exception) { null }
            if (!text.isNullOrBlank()) {
                transcripts[row.id] = text
                synchronized(voiceTextCache) { voiceTextCache[row.id] = text }
            }
        }
        if (transcriptBudget > 0) {
            onStage("正在分析最近聊天…")
            dev.jev.wechatmood.core.MoodLog.i("STATS_VOICE_TRANSCRIBE voices=${voiceRows.size} " +
                "budget=$transcriptBudget ok=${transcripts.size}")
        }
        // 图片同样最近优先：历史图片在微信加密的 .dat 里（不做解密），只有页面还挂着的
        // 能取到；识别过就写 ImageDigest 缓存，同一张图不重复调视觉模型。
        val imageRows = pickedRows.filter { it.type == 3 && it.text == "[媒体]" }.asReversed()
        val images = HashMap<Long, String>()
        val imageBudget = minOf(imageRows.size, MAX_STATS_IMAGES)
        var imageIndex = 0
        // 取不到图的历史图片会 continue，限制一次最多摸这么多条，别把整段图片都遍历一遍。
        for (row in imageRows.take(MAX_STATS_IMAGES * 4)) {
            if (imageIndex >= imageBudget) break
            val cached = dev.jev.wechatmood.reply.ImageDigest.cached(activity, context.talker, row.id)
            if (cached != null) { images[row.id] = cached; continue }
            val dataUri = runCatching { MessageSniffer.captureMediaImage(row.id) }.getOrNull() ?: continue
            imageIndex++
            onStage("正在识别图片 ${imageIndex}/${imageBudget}…")
            val text = try {
                dev.jev.wechatmood.reply.ImageDigest.describe(activity, current, context.talker, row.id, dataUri)
            } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (_: Exception) { null }
            if (!text.isNullOrBlank()) images[row.id] = text
        }
        if (imageBudget > 0) {
            onStage("正在分析最近聊天…")
            dev.jev.wechatmood.core.MoodLog.i("STATS_IMAGE_DIGEST images=${imageRows.size} " +
                "budget=$imageBudget ok=${images.size}")
        }
        fun isPlaceholder(text: String) = text == dev.jev.wechatmood.voice.VoiceText.WAITING ||
            text == dev.jev.wechatmood.voice.VoiceText.SKIPPED ||
            text == dev.jev.wechatmood.voice.VoiceText.FAILED || text == "[媒体]" || text == "[图片，未识别]"
        val readable = pool.map { row ->
            when {
                row.text != dev.jev.wechatmood.voice.VoiceText.WAITING -> row
                transcripts[row.id] != null -> row.copy(text = transcripts.getValue(row.id),
                    voiceState = dev.jev.wechatmood.voice.VoiceState.READY)
                row.id in tried -> row.copy(text = dev.jev.wechatmood.voice.VoiceText.FAILED,
                    voiceState = dev.jev.wechatmood.voice.VoiceState.FAILED)
                else -> row.copy(text = dev.jev.wechatmood.voice.VoiceText.SKIPPED,
                    voiceState = dev.jev.wechatmood.voice.VoiceState.SKIPPED)
            }
        }.map { row ->
            val digest = images[row.id]
            when {
                digest != null -> row.copy(text = "【图片：$digest】")
                row.type == 3 && row.text == "[媒体]" -> row.copy(text = "[图片，未识别]")
                else -> row
            }
        }
        check(readable.any { !isPlaceholder(it.text) }) {
            "这个时段 ${pool.size} 条消息里，语音和图片都没能读出内容；可以先播放一次语音、把图片点开再试，或者把范围放宽"
        }
        // 表格里已经算好的显示名一并并进来，总结里的「未获取昵称#N」才能和表格对上号。
        val resolved = namesFor(readable.map { it.speaker }.distinct()) + shownNames
        // 按整段抽样：单条均匀抽会把对话抽成碎片，模型看不出谁在接谁。
        // 条数由字符预算反算，抽完还超就再收一次，条数多到撑爆上下文会直接 400。
        var transcript = GroupTranscript.lines(picked, readable, resolved, timestamps = true)
        while (transcript.first.joinToString("\n").length > GroupTranscript.BUDGET_CHARS && picked.size > 24) {
            picked = GroupTranscript.pick(readable.size, limit = picked.size / 2)
            transcript = GroupTranscript.lines(picked, readable, resolved, timestamps = true)
        }
        var body = transcript.first
        var labels = transcript.second
        // 统计行也换成昵称：以前直接把 wxid 喂给模型，转述里就冒出 wxid。
        val statsLine = buildString {
            append("共 ${statsSnapshot.total} 条 · ${statsSnapshot.senders} 人参与 · 人均 ${statsSnapshot.perCapita} 条")
            val top = statsSnapshot.members.filter { it.name != "我" }.take(6).map { member ->
                // 以前查不到名字就退回 it.name，等于把 wxid 直接写进提示词。
                val name = shownNames[member.name]
                    ?: GroupTranscript.label(member.name, resolved[member.name])
                    ?: "群成员"
                "$name ${member.count} 条"
            }
            if (top.isNotEmpty()) append("；发言最多：").append(top.joinToString("、"))
            if (waitingVoices > 0) append("；其中 $waitingVoices 条是语音，${transcripts.size} 条已转写成正文，其余未转写")
            val imageCount = pool.count { it.type == 3 && it.text == "[媒体]" }
            if (imageCount > 0) append("；$imageCount 张图片，${images.size} 张已识别成文字")
        }
        // 长回答走非流式更稳：非流式只受读超时约束，这里单独放宽到 3 分钟。
        val client = ReplyHttpClient(HttpClients.shared.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS).callTimeout(240, TimeUnit.SECONDS).build(),
            kind = UsageKind.DIGEST)
        // 推理模型会先把 max_tokens 花在 reasoning 上：日志里 1200 全被思考吃掉、正文 0 字，
        // 等于每次总结都白跑一次再重试。起始给 4000，够一次写完又不会让模型放飞。
        var maxTokens = 4000
        var partial = ""
        var lastFinish = ""
        val prompt = if (isGroup) groupSummaryPrompt else privateSummaryPrompt
        repeat(2) { attempt ->
            val payload = JSONObject().put("model", current.model).put("stream", false)
                .put("max_tokens", maxTokens).put("temperature", 0.35)
                .put("messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", prompt))
                    .put(JSONObject().put("role", "user").put("content",
                        "时段：${windowLabel(range)}（实际数据 ${spanText(pool)}）\n统计：$statsLine\n" +
                            "该时段可读消息（${readable.size} 条，按时间顺序抽了 ${body.size} 条）：\n" +
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
                    picked = GroupTranscript.pick(readable.size, limit = body.size / 2)
                    transcript = GroupTranscript.lines(picked, readable, resolved, timestamps = true)
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
                val unnamed = labels.values.count { it.startsWith("群友") || it.startsWith("未获取昵称") }
                dev.jev.wechatmood.core.MoodLog.i(
                    "STATS_AI_POOL pool=${pool.size} lines=${body.size} speakers=${labels.size} unnamed=$unnamed")
                return Analysis(trimToLength(clean, if (isGroup) 420 else 370), body.size, pool.size)
                    .also { remember(selectedKey(pool, range, limit), it) }
            }
            if (clean.length > partial.length) partial = clean
            maxTokens = 8000
        }
        if (partial.isNotBlank()) {
            dev.jev.wechatmood.core.MoodLog.w("STATS_AI_TRUNCATED chars=${partial.length}")
            // 截断也按同一套字数收口：以前这里直接 take(3000)，提示词要的 400 字等于白写。
            return Analysis(trimToLength(partial, if (isGroup) 420 else 370), body.size, pool.size,
                "输出被长度上限截断，可以再点一次重试")
        }
        throw IllegalStateException("模型没返回正文（$lastFinish，素材 ${body.size} 条）")
    }

    /**
     * 提示词只是软约束，模型想写长还是会写长。超了就在句号处收口，
     * 宁可少一个收尾，也不要留半句话。
     */
    private fun trimToLength(text: String, limit: Int = 420): String {
        if (text.length <= limit) return text
        val head = text.take(limit + 80)
        val cut = head.indexOfLast { it in "。！？…\n" }
        return if (cut >= limit / 2) head.take(cut + 1).trim() else head.take(limit).trim()
    }

    /** 按类型填数字：文字 / 图片 / 语音 / 视频 / 表情 / 其它，一次填完。 */
    private fun fillNumbers(cells: List<Pair<LinearLayout, TextView>>) {
        val labels = dev.jev.wechatmood.reply.GroupStatsCalculator.KIND_LABELS
        labels.forEachIndexed { index, (type, _) ->
            cells.getOrNull(index)?.second?.text = (stats.types[type] ?: 0).toString()
        }
        val known = labels.sumOf { stats.types[it.first] ?: 0 }
        cells.getOrNull(labels.size)?.second?.text = (stats.types.values.sum() - known).coerceAtLeast(0).toString()
    }

    /** 空的状态行直接收起，界面上不留没有内容的一行。 */
    private fun captionLine(line: TextView, value: String) {
        line.text = value
        line.visibility = if (value.isBlank()) View.GONE else View.VISIBLE
    }

    /** 对比行文字：没有可比的上一周期就是空串，行随 [captionLine] 整行收起。 */
    private fun updateCompareLine() {
        val line = compareLine ?: return
        val previous = previousTotal ?: run {
            captionLine(line, "")
            return
        }
        captionLine(line, GroupStatsCalculator.compare(stats.total, previous).orEmpty())
    }

    /**
     * 数一遍上一周期的条数：复用按时间查库的 statMessages，只看列表大小，不重建整张表。
     * 账号没确认、窗口算不出来、查库报错都当作「没有上一周期」，界面不显示这一行。
     */
    private suspend fun refreshCompare() {
        val now = System.currentTimeMillis()
        val since = StatsFilter.sinceMillis(now)
        // 「今天」比昨天同一时段；「最近 N 小时」比紧邻的前 N 小时。
        val window = if (StatsFilter.range == StatsFilter.Range.TODAY) {
            GroupStatsCalculator.previousDayWindow(since, now)
        } else {
            GroupStatsCalculator.previousWindow(since, now)
        }
        // 当前窗口本身就是页面数据时（库里这个时段没消息）不比：两边来源不同，比出来没意义。
        if (accountScope == null || window == null || dbMessages.isEmpty()) {
            previousTotal = null
            dev.jev.wechatmood.core.MoodLog.i("STATS_COMPARE skip scope=" + (accountScope != null) +
                " window=" + (window != null) + " rows=" + dbMessages.size + " label=" + windowLabel())
            updateCompareLine()
            return
        }
        // 上一周期必须和当前用同一个条数上限：当前被截断到 N 条，上期却拿全窗口，比出来必然「少 90%」。
        val compareLimit = if (StatsFilter.limit == 0) COMPARE_LIMIT else StatsFilter.limit
        val rows = runCatching {
            ReplyDatabaseHistory.statMessages(context.talker, window.start, compareLimit, accountScope,
                untilMillis = window.end)
        }.getOrNull()
        if (rows == null) {
            previousTotal = null
            dev.jev.wechatmood.core.MoodLog.w("STATS_COMPARE_FAILED label=" + windowLabel())
            updateCompareLine()
            return
        }
        // 达到上限说明可能被截断：两边都可能是「前 N 条」，百分比不可信，宁可不给结论。
        if (rows.size >= compareLimit) {
            previousTotal = null
            dev.jev.wechatmood.core.MoodLog.i("STATS_COMPARE capped limit=" + compareLimit + " label=" + windowLabel())
            updateCompareLine()
            return
        }
        previousTotal = rows.size
        dev.jev.wechatmood.core.MoodLog.i("STATS_COMPARE current=" + stats.total + " previous=" + rows.size +
            " limit=" + compareLimit + " label=" + windowLabel())
        updateCompareLine()
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

    /**
     * 分析结果的缓存口径：聊天对象 + 时段 + 条数 + 素材首尾 id。
     * 数据变了（新消息进来）key 就变，不会把旧的观察当现在。
     */
    private fun selectedKey(pool: List<dev.jev.wechatmood.reply.ReplyMessage>,
        range: StatsFilter.Range = StatsFilter.range, limit: Int = StatsFilter.limit): String =
        context.talker + "|" + (accountScope ?: "-") + "|" + range + "|" + limit + "|" + pool.size + "|" +
            (pool.firstOrNull()?.id ?: 0) + "-" + (pool.lastOrNull()?.id ?: 0)

    /** 同一范围的分析结果还在的话直接复用，切筛选来回、关窗再打开都不必重新花钱。 */
    private fun cachedAnalysis(): Analysis? {
        val pool = selectedMessages()
        if (pool.isEmpty()) return null
        val key = selectedKey(pool)
        val entry = summaryCache[key] ?: return null
        if (System.currentTimeMillis() - entry.first > CACHE_TTL_MS) {
            summaryCache.remove(key)
            return null
        }
        return entry.second
    }

    private fun remember(key: String, analysis: Analysis) {
        if (analysis.text.isBlank() || analysis.text.startsWith("分析失败")) return
        // 先删再放：LinkedHashMap 按插入序淘汰，重复写同一个范围要让它排到最新。
        summaryCache.remove(key)
        summaryCache[key] = System.currentTimeMillis() to analysis
        while (summaryCache.size > 3) summaryCache.remove(summaryCache.keys.first())
        dev.jev.wechatmood.core.MoodLog.i("STATS_AI_CACHE size=" + summaryCache.size)
    }

    private companion object {
        /** 分析结果的有效期：半小时前的观察别再当现在用。 */
        const val CACHE_TTL_MS = 30 * 60 * 1000L
        /** 上一周期只数条数：给个够用的上限，别为了对比把整段历史都拉出来。 */
        const val COMPARE_LIMIT = 5000
        /** 进程内最多留三份不同范围的结果，够来回切筛选就行。 */
        val summaryCache = LinkedHashMap<String, Pair<Long, Analysis>>()
        /** 一次分析最多转写这么多条语音：原生转写每条约 2 秒，再多就成等转写了。 */
        const val MAX_STATS_TRANSCRIPTS = 10
        /** 一次分析最多识别这么多张图片：每张一次视觉模型调用，最近优先。 */
        const val MAX_STATS_IMAGES = 5
        /** 进程内转写缓存：同一条语音（msgId 全局唯一）不带重复跑原生转写。 */
        val voiceTextCache = HashMap<Long, String>()
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
