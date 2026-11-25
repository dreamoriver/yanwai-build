package dev.jev.wechatmood.hook

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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
import java.io.File
import java.io.IOException
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
    /** 统计结果；首轮在后台算，算完之前是空对象（面板先出骨架，数字随后填）。 */
    private var stats: GroupStats = GroupStats()
    /** 统计代次：切筛选、换数据源就加一，后台旧结果回来对不上直接丢。 */
    private var statsEpoch = 0
    /** 首轮统计是否已经算出来过：没算出来前界面写「正在统计…」。 */
    private var statsApplied = false
    /** 首轮统计的任务：打开窗口后数昵称、比上一周期都要等它算完。 */
    private var statsJob: Job? = null
    /** 数字建好之后挂上来：后台算完在主线程刷数字，窗口没打开时是 null。 */
    private var refreshNumbers: (() -> Unit)? = null
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
    /** 正在画长图：几十 MB 的渲染不能连点排队，完成前再来点击直接忽略。 */
    private var renderingImage = false

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

    /** 上一次记录的「没有发送者」类型分布，只在变化时写日志。 */
    private var blankSignature: String? = null

    /** 统计的输入快照：消息池、媒体与语音计数都在主线程这一刻定住。 */
    private class StatsInput(
        val pool: List<dev.jev.wechatmood.reply.ReplyMessage>,
        val trimmed: Boolean,
        val fromDb: Boolean,
        val omittedMedia: Int,
        val voice: Int,
    )

    /** 后台算出来的一轮统计：数字 + 顺带要写的诊断信息（日志回主线程写）。 */
    private class StatsRun(val stats: GroupStats, val noSender: Int, val signature: String)

    /**
     * 主线程取快照：只读字段，不做聚合。
     *
     * 以前统计直接读这些可变字段，还在同一个方法里对同一个池子 filter 了两次；
     * 现在后台线程只认这一份快照，用户中途切筛选也不会算出半新半旧的数字。
     */
    private fun statsInput(): StatsInput {
        // 池子只筛一次：旧写法连着筛两次，几千条消息白白多跑一轮。
        val pool = selectedMessages()
        return StatsInput(pool, context.trimmed, dbMessages.isNotEmpty(), context.omittedMedia,
            context.messages.count { it.voiceState == dev.jev.wechatmood.voice.VoiceState.READY })
    }

    /** 纯计算：只读快照，可以在后台线程跑。 */
    private fun computeStats(input: StatsInput): StatsRun {
        // 群里有些消息 content 不带发送者前缀（系统提示、部分媒体），单独记类型分布，
        // 否则它们会全被并成一个「未获取昵称」，看着像某个人没说名字。
        val blanks = input.pool.filter { it.senderId.isBlank() }
        val signature = if (blanks.isEmpty()) "" else blanks.groupingBy { it.type }.eachCount().entries
            .sortedByDescending { it.value }.joinToString(",") { it.key.toString() + ":" + it.value }
        val records = input.pool.map {
            StatRecord(normalizedMillis(it.time), if (it.speaker == "我") 1 else 0, it.type, it.text, it.speaker)
        }
        val base = GroupStatsCalculator.compute(records, sampled = input.trimmed)
        // 库里的消息带真实 type，图片语音按自己这一份算。
        // 页面消息拿不到类型，只能按「没进上下文的条数」补，否则永远是 0。
        val merged = if (input.fromDb) base else base.copy(media = input.omittedMedia, voice = input.voice)
        return StatsRun(merged, blanks.size, signature)
    }

    /** 主线程收口：写诊断日志、换数字，必要时刷界面。 */
    private fun applyStats(run: StatsRun, refresh: Boolean = true) {
        if (run.noSender > 0 && run.signature != blankSignature) {
            dev.jev.wechatmood.core.MoodLog.w("STATS_NO_SENDER n=" + run.noSender + " types=" + run.signature)
        }
        blankSignature = run.signature
        stats = run.stats
        statsApplied = true
        if (refresh) refreshNumbers?.invoke()
    }

    /**
     * 后台重算统计，算完回主线程刷数字。
     *
     * 几千条的池子上在主线程 filter + 聚合，切「时段 / 条数」时会掉帧；这里先留着旧数字
     * （面板不闪空白），算完再换。代次对不上说明筛选又换过了：晚到的旧结果直接丢，
     * 不让旧范围盖掉新范围。
     */
    private fun recomputeStats() {
        val epoch = ++statsEpoch
        val input = statsInput()
        statsJob = scope.launch {
            val run = withContext(Dispatchers.Default) { runCatching { computeStats(input) }.getOrNull() }
            if (epoch != statsEpoch || run == null) return@launch
            applyStats(run)
        }
    }

    /**
     * 换数据源后重算（[reloadDb] 里用）：算完把代次也往前进，正在进行中的旧一轮结果就作废了。
     * 是否顺带刷界面交给调用方（它后面本来就要整屏刷一遍），默认不重复画。
     */
    private suspend fun reloadStats(refresh: Boolean = false) {
        val epoch = ++statsEpoch
        val input = statsInput()
        val run = withContext(Dispatchers.Default) { runCatching { computeStats(input) }.getOrNull() } ?: return
        if (epoch != statsEpoch) return
        applyStats(run, refresh)
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
        }.getOrNull()
        dev.jev.wechatmood.core.MoodLog.i("STATS_RELOAD rows=" + (rows?.size ?: -1) + " limit=" + limit + " range=" + StatsFilter.range)
        if (rows == null) {
            // 读库失败和「这个时段没消息」不是一回事：旧数据必须先清掉，否则上一轮的数字会配这次失败的说明。
            sourceNote = "读取消息库失败，只统计页面上可见的 " + context.messages.size + " 条"
            dev.jev.wechatmood.core.MoodLog.w("STATS_RELOAD_FAILED range=" + StatsFilter.range)
            dbMessages = emptyList()
            reloadStats()
            previousTotal = null
            updateCompareLine()
            return
        }
        if (rows.isEmpty()) {
            sourceNote = "库里没有这个时段的消息，只统计页面上可见的 " + context.messages.size + " 条"
            dev.jev.wechatmood.core.MoodLog.i("STATS_RELOAD_EMPTY range=" + StatsFilter.range)
            // 窗口空了，内存里上一轮的行和统计一起清：否则数字还是旧范围的。
            dbMessages = emptyList()
            reloadStats()
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
                it.materialText(), voice = it.voiceSource(),
                senderId = it.senderId(), type = it.type, imagePath = it.imagePath)
        }
        // 诊断：库里查出来的语音引用，到 ReplyMessage 里还剩多少。
        dev.jev.wechatmood.core.MoodLog.i("STATS_DB_VOICE rows=${rows.size} " +
            "type34=${rows.count { it.type == 34 }} src=${rows.count { it.voiceSource() != null }} " +
            "built=${dbMessages.size} voice=${dbMessages.count { it.voice != null }} " +
            "waiting=${dbMessages.count { it.text == dev.jev.wechatmood.voice.VoiceText.WAITING }} " +
            "took=${android.os.SystemClock.elapsedRealtime() - buildStarted}ms")
        // 统计放后台算：库里几千条时这一步在主线程要几十毫秒，够掉几帧。
        reloadStats()
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
        // 首轮统计在后台算：还没结果时先写「正在统计…」，算完由 refreshNumbers 换成真数字。
        val spanLine = kit.caption(if (statsApplied) spanLineText() else "正在统计…")
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
        // 统计没算出来前先留空：显示一排 0 会被当成「这个时段真没消息」。
        if (statsApplied) fillNumbers(cells)
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
        // 同理，先不画成员表，否则会先闪一句「没有可统计的发言」再换成真表格。
        currentNames = pageNames + manualNames
        if (statsApplied) renderTop(currentNames)

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
            // 后台重算：切筛选时先留着旧数字（面板不闪空白），算完由 refreshNumbers 换掉。
            recomputeStats()
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
            if (renderingImage) return@action
            renderingImage = true
            Toast.makeText(activity, "正在生成图片…", Toast.LENGTH_SHORT).show()
            // 和 CSV 一样按点击这一刻拍照：画图要几秒，期间界面怎么变，出来的都是同一份数据。
            val summary = analysisText.takeIf {
                it.isNotBlank() && !it.startsWith("分析失败")
            }
            val snapshot = stats
            val names = displayedNames.toMap()
            scope.launch {
                // 1080×上万像素的 ARGB_8888 加 PNG 压缩是几十 MB 的活，放主线程会整屏假死。
                val uri = withContext(Dispatchers.Default) {
                    var bitmap: android.graphics.Bitmap? = null
                    try {
                        val image = StatsImage.render(snapshot, names, summary, "群聊统计")
                        bitmap = image
                        StatsImage.save(activity, image)
                    } catch (error: Throwable) {
                        // 取消不是失败：面板关掉后别再弹提示，交给取消机制收尾。
                        if (error is kotlinx.coroutines.CancellationException) throw error
                        dev.jev.wechatmood.core.MoodLog.w("STATS_IMAGE_FAILED " + error.javaClass.simpleName)
                        null
                    } finally {
                        // 一张就几十 MB，存完立刻还给系统，别等 GC。
                        bitmap?.recycle()
                    }
                }
                Toast.makeText(activity,
                    if (uri != null) "已保存到相册，可在相册里发送" else "保存失败，检查相册权限",
                    Toast.LENGTH_LONG).show()
                renderingImage = false
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(theme.action("关闭", quiet = true) { dialog.dismiss() },
            LinearLayout.LayoutParams(0, -2, 1f))
        // 导出 CSV：单独一行放在底栏上方，底栏那三个按钮不会被挤窄；状态行紧贴按钮下面。
        val exportStatus = kit.status("").apply { visibility = View.GONE }
        val exportRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        exportRow.addView(theme.action("导出 CSV", quiet = true) {
            // 和 AI 分析一样按点击这一刻拍照：导出期间界面怎么变，写出来的都是同一份数据。
            exportCsv(stats, displayedNames.toMap(), exportStatus)
        }, LinearLayout.LayoutParams(-1, -2))
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
        root.addView(exportRow, LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(theme.dp(20), theme.dp(4), theme.dp(20), 0)
        })
        root.addView(exportStatus, LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(theme.dp(20), 0, theme.dp(20), 0)
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
        // 后台算完回到主线程只换文字，不重建窗口；表格与活跃时段跟着新数字重画一遍。
        refreshNumbers = {
            spanLine.text = spanLineText()
            captionLine(sourceLine, sourceNote)
            updateCompareLine()
            fillNumbers(cells)
            renderTop(currentNames)
            renderActiveHours(activeBox)
        }
        recomputeStats()
        dialog.show()

        // 昵称在数据库里，异步查一次再替换 wxid；同范围的分析结果还在的话直接摆出来。
        scope.launch {
            logBlankSamples()
            // 先等首轮统计算完：昵称和「上一周期」都按这一次的数字算，别拿空统计去比。
            statsJob?.join()
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
     * 群聊和私聊共用的硬性约束。
     * 以前两边各抄一份，改一边漏一边（群聊就漏了输出收口），统一从这里取。
     */
    private val summarySharedRules = """
        - 合并。同一件事来回说十句，那是同一件事，不是十件。
        - 不逐人报价。禁止「甲说……乙说……丙又接……」；一个话题最多点两个名字，只有这个人真的带着观点时才点名。
        - 引用克制，最多引两句原话；能说清的地方就不引。引号里必须是素材原文照抄，不能改写。
        - 小事不写。只有一两句寒暄、没结论的插科打诨，半句带过或者干脆不写。
        - 宁可漏，不要全。漏掉边角话题没关系，主线错了才叫失败。
        - 素材少就写短，别硬凑：不到 40 条消息就老实写短；只是日常闲聊，就照实说在聊什么，别硬编冲突。
        - 没交代结果的事，写成还悬着、没下文，不要替他们补结局。
        - 【图片：…】是识别出来的描述，可以当内容用，但可能认错；金额、人名这类硬事实，素材正文没确认就别断言。
          【图片，未识别】和 [媒体] 是读不到内容的占位，不要猜。
        - 链接、公众号文章、文件和文档（Word、PPT、Excel、PDF、压缩包等）在素材里只有标题或文件名，正文和文件内容都没读过。
          可以说「有人转了篇讲 X 的文章」「有人发了个叫「预算.xlsx」的表」，但不要总结文章内容，
          也不要补标题、文件名里没有的数字、时间或结论。
        - 语音已转写的按普通发言读；「【语音，未转写】」「【语音未能转写，内容未知】」读不到内容，
          不要猜它说了什么，也不要提「转写」「占位」「系统状态」，不要因为素材少就吐槽或编内容。
        - 【视频：…】【小视频：…】是抽关键帧认出来的画面内容，可以当内容用：说画面里真有的场景、
          人物和动作。但只看到几个瞬间，看不到完整过程，不要替它补前后的动作、结局或台词。
          素材里有几条带描述的视频，正文就要点到几条：每条各用一句话说清它是什么画面、跟群里
          在聊的哪件事有关，不要合并成「还发了几条视频」一句带过。这是本提示词里唯一要求点全的
          东西，其余话题照旧按上面「宁可漏，不要全」取舍。
          【视频】【小视频】【表情】【系统消息】只写着类型，说明这次没读到内容：可以提「有人发了个视频」，
          不要猜拍了什么、说了什么，也不要把「媒体」「占位」这些词写进正文。
        - 转账的金额和备注是消息里真有的，可以写；红包只有祝福语、没有金额，不要猜金额，
          也不要写「金额未知」这种话。
        - 转账素材里的「转出」「转入」是方向，照原样写，不要写反；金额照抄——0.01 就是 0.01 元，
          不要换算成「一毛」「一块」这类说法。
        - 【红包·已被领取】【转账·已收款】是状态消息，可以写「对方领了/收了」；同一笔转账不要
          写成两个人互相转，只按带方向的转账条目描述一次。
        - 【转账·待领取】是还没被收，【转账·已收款】是已经收了；可以照实写，不要反过来说。
          红包没有【已被领取】就当还没被领。
        - 素材里的「群友N」「未获取昵称#N」「群成员」都只是代号，不是真名，别猜是谁，也不要把不同代号当成同一个人。
        - 每行开头的 HH:mm 是当天时间；跨零点的消息可能属于前一天，别把时间说反。
        - 素材是按时间轴抽样的片段，不代表消息多少，别拿它推断热度。
        - 不用「首先/其次」「总而言之」「值得一提的」这类套路词，不加小标题，不编号。
        - 只输出正文：不要写「以下是总结」这类开场，也不要解释你是怎么写的。
    """.trimIndent()

    /**
     * 群聊转述的提示词：三十秒说清主线，只挑最大的两三个话题，不逐人报价。
     * 早先那版要求「能说清的事都要点到、每件事都点出是谁说的、能说全就说全」，
     * 模型照做就成了照抄聊天记录的流水账——要求写全，等于要求复述。
     * 素材里的 speaker 已经换成昵称，模型不该再看到 wxid。
     * 口吻按用户要求偏主观：要的是「看过之后有自己的态度」，不是中立纪要，
     * 所以允许站边、吐槽、下判断，只把评价钉在素材里真有的言行上。
     */
    private val groupSummaryPrompt = """
        你在一个微信群里潜水看了一天，现在跟没进群的朋友讲这群人在折腾什么。
        素材里的「我」是用户本人，你不是素材里的任何一个人。
        你不是中立的纪要机器：你是看过这些对话的人，有自己的好恶和判断。
        用「我」可以，但那是你看完之后的看法，不是素材里那个用户。

        好笑、具体、有画面感，但只准用素材里真有的东西：人、事、数字、原话、分歧。
        热闹不是靠形容词堆出来的，是靠把某件荒唐的小事说清楚。

        只写三段，180 到 400 字，宁短勿长。超过 400 字这版就算废了。
        第一段一句，第二段两到三句，第三段一句；某段没东西可写就压成半句，不要为凑字数展开。

        第一段，一句：这群人这阵子最较劲的是哪件事，顺带把你的态度带出来（觉得离谱、好笑、可怜、佩服都行）。
        要用最具体的那一版说法（谁、为了什么、卡在哪），别写成「最近大家在讨论……」这种开场。

        第二段，两到三句：挑最大的两到三个话题，每个写清「怎么起的、谁在坚持、最后怎样」。
        讲的时候可以站边：谁说得有道理、谁在胡搅、谁其实挺委屈，直接讲出来，别两边各夸一句就算完。
        允许用一句群里的原话当包袱，但别把聊天记录复述一遍。

        第三段，一句收尾：你的判断、吐槽、反差，或者给这帮人贴个小标签。
        带点私人评价，别喊口号，别上价值，别说教。

        想写得有意思，可以挑一两个用，别全上：
        - 抓荒诞点：为很小的事认真争论半天，或者前后说法对不上。
        - 用群里的原话当包袱，不要自己造梗。
        - 把分歧摆出来，并说清你更认哪一边。
        - 顺着这件事说半句自己的感受（烦、乐、无语、羡慕），别写成情绪表演。

        硬性要求：
        - 统计行只用来判断热度：可以说谁说得最多，但不要报条数、人数这些数字。
        - 态度要落在具体的人和事上，别空泛地夸「这群人真有意思」。
        __SHARED_RULES__

        语气像边想边说的口头转述，短句，可以带明显的偏好和情绪。
        可以损事、损说法、损做法：笑他们干的蠢事，不碰外貌、家境、身体、性别。
        不编素材里没有的态度和情节，也不替谁补结局。

        示范（只学密度和口吻，内容别照抄）：
        说实话这两天的重点就是搬家，一边比价一边骂中介，最后折中找了半日搬家公司，还被「半天也算一天」堵了回去——
        中介这话确实不地道，但他们几个也没谁真去砍价。倒是「谁顺手带个早饭」这点破事吵了半天，一群人排队点单，
        剩下几个在看笑话，我到现在还挺想知道那顿早饭最后谁付的钱。

    """.trimIndent().replace("__SHARED_RULES__", summarySharedRules)

    /** 私聊用的是「转述这段聊天」的提示词：同一套写全的要求用在两个人身上更像流水账。 */
    private val privateSummaryPrompt = """
        你和一个人聊了一整天，现在跟朋友转述这段聊天在聊什么。
        素材里的「我」是用户本人，「对方」就是和你聊天的这个人。
        你说话可以带自己的看法，不用当复读机。

        只写两段，180 到 330 字，宁短勿长。超过 330 字这版就算废了。

        第一段，一两句，说清这段聊天主要在聊什么、聊到哪一步了（约好了什么、还在等什么答复），
        顺口带一句你对这事的感受或者判断。

        第二段，挑两三个具体的事，一个一两句，写清楚对方的态度、你们的分歧，以及你更认哪一头、当时什么感觉。

        硬性要求：
        - 主要写对方和我之间的事，不要把自己说过的话逐句复述一遍；素材里提到的家人、同事这些第三方可以出现，
          但只写素材里真有的态度，不要替他们编。
        - 不写统计数字；可以表达自己的感受和看法，但别给这段关系下定义，也别给对面这个人贴标签。
        __SHARED_RULES__

        语气像边想边说的口头转述，短句，可以有点情绪。

        示范（只学密度和口吻，内容别照抄）：
        这两天主要在说周末见面的事，从去哪吃饭聊到几点结束，最后定在周六下午，具体地方还没定。
        一个想找离家近的，另一个嫌那边没好吃的，来回推了三四个店，谁也没拍板。中间还夹了句「你上次说的那家关了」，
        于是又绕回起点——这事我看八成还得再拖两天。

    """.trimIndent().replace("__SHARED_RULES__", summarySharedRules)

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
            "type34=${pool.count { it.type == 34 }} calls=${pool.count { it.type == 50 }} " +
            "voices=${pool.count { it.voice != null }} " +
            "waiting=${pool.count { it.text == dev.jev.wechatmood.voice.VoiceText.WAITING }} " +
            "images=${pool.count { it.type == 3 }} media=${pool.count { it.text == "[媒体]" }} " +
            "mediaTypes=${pool.filter { it.text == "[媒体]" }.groupingBy { it.type }.eachCount()}")
        // 语音在库里只有「【语音，等待转写】」占位：先按上限逐条转写，转出来的当正文喂模型。
        // 没转的留明确占位（未转写 / 未能转写），提示词里说清那是读不到内容，
        // 别让模型去评论占位符本身，也别让它以为「都在发语音」就是聊了什么。
        val waitingVoices = pool.count { it.text == dev.jev.wechatmood.voice.VoiceText.WAITING }
        // 先按条数抽样，再只处理抽中的语音和图片：反过来会把最后没进素材的消息也转一遍
        // （106 条只喂 56 行时尤其明显，转了等于白等）。
        var picked = GroupTranscript.sample(pool.size)
        // 图片和合并转发的信息量集中在少数几条，按时间段抽样很容易整段漏掉；
        // 抽完再把它们补回来，并从非保留项里挤掉同样条数，总行数不变。
        val keep = buildList {
            val images = pool.withIndex().filter { it.value.type == 3 }.map { it.index }.takeLast(MAX_STATS_IMAGES)
            val forwards = pool.withIndex()
                .filter { (it.value.type and 0xFFFF) == 49 && it.value.text.startsWith("聊天记录：") }
                .map { it.index }.takeLast(MAX_STATS_FORWARDS)
            // 视频的信息量也集中在少数几条：抽完样再补回来，否则一整段可能一条都没抽到。
            val videos = pool.withIndex()
                .filter { it.value.type == 43 || it.value.type == 62 }
                .map { it.index }.takeLast(MAX_STATS_VIDEOS)
            addAll(images); addAll(forwards); addAll(videos)
        }.distinct()
        if (keep.isNotEmpty()) {
            val merged = (picked + keep).distinct().sorted()
            picked = if (merged.size <= picked.size) merged else {
                val extra = merged.size - picked.size
                val drop = merged.filter { it !in keep }.take(extra).toSet()
                merged.filter { it !in drop }
            }
        }
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
        // 视频同样最近优先：本体是明文 mp4（MicroMsg/<账号 hash>/video 下与封面同名），
        // 抽几帧就能得到一句画面描述。没点开过、本地没有 mp4 的直接跳过，不写成「读到了」。
        val videoRows = pickedRows.filter { it.type == 43 || it.type == 62 }.asReversed()
        val videos = HashMap<Long, String>()
        // cached / locate 是同步磁盘读，抽帧是同步解码：这个函数跑在 Main.immediate 的协程里，
        // 压在微信主线程上一段几分钟的视频就能把界面冻住，所以每一步都显式切到 IO。
        val tasks = withContext(Dispatchers.IO) {
            videoRows.take(MAX_STATS_VIDEOS * 4).mapNotNull { row ->
                // 关键帧缓存（v:）是最完整的，有就直接用，不再解码。
                val frames = dev.jev.wechatmood.reply.VideoDigest.cached(activity, context.talker, row.id)
                if (frames != null) { videos[row.id] = frames; return@mapNotNull null }
                // 一次遍历同时拿本体和封面。
                val media = dev.jev.wechatmood.reply.VideoDigest.locateMedia(activity, row.imagePath)
                val file = media.first
                val cover = media.second
                // 本地已经有本体就抽关键帧：封面只是第一帧。否则点开过的视频还停在封面级描述，
                // 用户点了半天「打开视频」，总结里看到的还是那张海报。
                if (file != null) return@mapNotNull Triple(row, file, cover)
                val digest = dev.jev.wechatmood.reply.ImageDigest.cached(activity, context.talker, row.id)
                if (digest != null) { videos[row.id] = digest; return@mapNotNull null }
                // 没有本体也没有缓存：还有封面就读封面，两样都没有就跳过。
                cover?.let { Triple(row, null, it) }
            }
        }
        // 分母用「真正要读的条数」：缓存命中的不算，进度才不会停在 1/3 不动。
        val videoBudget = minOf(tasks.size, MAX_STATS_VIDEOS)
        var videoIndex = 0
        var byKeyframe = 0
        var byCover = 0
        for (task in tasks) {
            if (videoIndex >= videoBudget) break
            val row = task.first
            val file = task.second
            val cover = task.third
            videoIndex++
            onStage("正在看视频 " + videoIndex + "/" + videoBudget + "…")
            val text = try {
                withContext(Dispatchers.IO) {
                    if (file != null) {
                        dev.jev.wechatmood.reply.VideoDigest.describe(activity, current, context.talker, row.id, file)
                    } else {
                        dev.jev.wechatmood.reply.VideoDigest.describeCover(activity, current, context.talker, row.id,
                            requireNotNull(cover))
                    }
                }
            } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (_: Exception) { null }
            if (!text.isNullOrBlank()) {
                videos[row.id] = text
                if (file != null) byKeyframe++ else byCover++
            }
        }
        if (videoBudget > 0) onStage("正在分析最近聊天…")
        // 无条件打：budget=0 时必须能区分「窗口里根本没有视频」和「全部命中缓存」，
        // 否则从日志上看起来就像视频功能没生效。types 是这批素材的真实类型分布。
        dev.jev.wechatmood.core.MoodLog.i("STATS_VIDEO_DIGEST videos=" + videoRows.size +
            " budget=" + videoBudget + " keyframe=" + byKeyframe + " cover=" + byCover +
            " ok=" + videos.size + " picked=" + pickedRows.size +
            " types=" + pickedRows.groupingBy { it.type }.eachCount() +
            " paths=" + videoRows.take(3).map { it.imagePath.take(20) })
        // 只有类型、没有内容的行（【红包】【通话记录】【位置】这类）也不是可读素材：
        // 整段都是它们时，别让模型对着一屏方括号硬编内容。
        val bareTypes = setOf(
            "【红包】", "【转账】", "【通话记录】", "【链接】", "【文件】", "【合并转发】",
            "【引用回复】", "【链接、引用或转发】", "【名片】", "【位置】", "【视频】",
            "【小视频】", "【表情】", "【系统消息】",
        )
        fun isPlaceholder(text: String) = text == dev.jev.wechatmood.voice.VoiceText.WAITING ||
            text == dev.jev.wechatmood.voice.VoiceText.SKIPPED ||
            text == dev.jev.wechatmood.voice.VoiceText.FAILED || text == "[媒体]" ||
            text == "[图片，未识别]" || text in bareTypes
        var readable = pool.map { row ->
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
            val videoDigest = videos[row.id]
            when {
                digest != null -> row.copy(text = "【图片：$digest】")
                videoDigest != null -> row.copy(text = "【视频：$videoDigest】")
                row.type == 3 && row.text == "[媒体]" -> row.copy(text = "[图片，未识别]")
                else -> row
            }
        }
        // 转账领没领取：同一段素材里，金额相同的「已收款」状态条目只配对一次；
        // 配上的转出标成已收款，没配上的标成待领取。状态条目本身不重复保留。
        fun amountOf(text: String) = text.substringAfter("：", "").substringBefore("】", "").trim()
        val paidTransfers = readable.filter { it.text.startsWith("【转账·已收款") }
            .map { amountOf(it.text) }.filter { it.isNotEmpty() }.toMutableList()
        val settledReadable = readable.mapNotNull { row ->
            when {
                row.text.startsWith("【转账·已收款") -> null
                row.text.startsWith("【转账·转出") -> {
                    val value = amountOf(row.text)
                    if (value.isEmpty()) row else {
                        val matched = paidTransfers.remove(value)
                        row.copy(text = if (matched) "【转账·已收款：$value】" else "【转账·待领取：$value】")
                    }
                }
                else -> row
            }
        }
        readable = settledReadable
        check(readable.any { !isPlaceholder(it.text) }) {
            "这个时段 ${pool.size} 条消息里没有可读的正文（语音、图片没识别，或只有红包、通话这类提示）；" +
                "可以先播放一次语音、把图片点开，或者把范围放宽"
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
                shownNames[member.name]
                    ?: GroupTranscript.label(member.name, resolved[member.name])
                    ?: "群成员"
            }
            // 只给名字不给条数：正文允许说谁最活跃，但按提示词的要求不报数字。
            if (top.isNotEmpty()) append("；发言最多：").append(top.joinToString("、"))
            if (waitingVoices > 0) append("；其中 $waitingVoices 条是语音，${transcripts.size} 条已转写成正文，其余未转写")
            val imageCount = pool.count { it.type == 3 && it.text == "[媒体]" }
            if (imageCount > 0) append("；$imageCount 张图片，${images.size} 张已识别成文字")
            val videoCount = pool.count { it.type == 43 || it.type == 62 }
            if (videoCount > 0) append("；$videoCount 条视频，${videos.size} 条已抽关键帧识别成文字")
        }
        // 长回答走非流式更稳：非流式只受读超时约束，这里单独放宽到 3 分钟。
        val client = ReplyHttpClient(HttpClients.shared.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS).callTimeout(240, TimeUnit.SECONDS).build(),
            kind = UsageKind.DIGEST)
        // 带画面的视频条数：只有它们需要在正文里逐条交代，也是放宽字数与 token 预算的依据。
        // 只算真的读到画面的（videos 里已有的描述），只有类型的【视频】不算。
        val describedVideos = videos.size
        // 推理模型会先把 max_tokens 花在 reasoning 上：日志里 1200 全被思考吃掉、正文 0 字，
        // 等于每次总结都白跑一次再重试。起始给 4000，视频多时按条加，少一次「掐断再重试」。
        var maxTokens = 4000 + 400 * minOf(describedVideos, MAX_VIDEO_WIDEN)
        var partial = ""
        var lastFinish = ""
        var correction = ""
        // 群总结要把每条带画面的视频都点到：基底 500 字，每多一条视频再给 60 字，
        // 最多放宽 MAX_VIDEO_WIDEN 条（上限 860）。私聊不涉及逐条交代，仍 330 字。
        val textLimit = if (isGroup) 500 + 60 * minOf(describedVideos, MAX_VIDEO_WIDEN) else 330
        var lastIssue = ""
        val prompt = if (isGroup) groupSummaryPrompt else privateSummaryPrompt
        repeat(2) { attempt ->
            val payload = JSONObject().put("model", current.model).put("stream", false)
                .put("max_tokens", maxTokens).put("temperature", 0.35)
                .put("messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", prompt))
                    .put(JSONObject().put("role", "user").put("content",
                        "时段：${windowLabel(range)}（实际数据 ${spanText(pool)}）\n统计：$statsLine\n" +
                            "该时段可读消息（${readable.size} 条，按时间顺序抽了 ${body.size} 条）：\n" +
                            body.joinToString("\n") + correction)))
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
            val issue = if (clean.isNotBlank()) summaryIssue(clean, textLimit) else null
            dev.jev.wechatmood.core.MoodLog.i(
                "STATS_AI_ALL attempt=${attempt + 1} $lastFinish issue=${issue ?: "-"}")
            // finish_reason=length 说明话被掐在半路，直接重试要更多 token，不把半截文本当结果。
            if (clean.isNotBlank() && answer.second != "length" && issue == null) {
                val unnamed = labels.values.count { it.startsWith("群友") || it.startsWith("未获取昵称") }
                dev.jev.wechatmood.core.MoodLog.i(
                    "STATS_AI_POOL pool=${pool.size} lines=${body.size} speakers=${labels.size} unnamed=$unnamed")
                return Analysis(trimToLength(clean, textLimit), body.size, pool.size)
                    .also { remember(selectedKey(pool, range, limit), it) }
            }
            // 开场白、套话、逐人复述、明显超长：把问题写回请求再要一次，不让用户自己点重试。
            if (issue != null && attempt == 0) {
                lastIssue = issue
                dev.jev.wechatmood.core.MoodLog.w("STATS_AI_RETRY issue=$issue chars=${clean.length}")
                correction = "\n\n上一版的问题：$issue。重写：直接写正文，不要开场白和套话，" +
                    "不要逐句复述，控制在 $textLimit 字以内。"
                if (clean.length > partial.length) partial = clean
                // 话被长度掐断时，第二版要更多 token，不能只按形状问题处理。
                if (answer.second == "length") maxTokens = 8000
                return@repeat
            }
            if (clean.length > partial.length) partial = clean
            maxTokens = 8000
        }
        if (partial.isNotBlank()) {
            dev.jev.wechatmood.core.MoodLog.w(
                "STATS_AI_TRUNCATED chars=${partial.length} issue=${lastIssue.ifEmpty { "-" }}")
            // 截断也按同一套字数收口：以前这里直接 take(3000)，提示词要的 400 字等于白写。
            return Analysis(trimToLength(partial, textLimit), body.size, pool.size,
                if (lastIssue.isNotEmpty()) "输出" + when (lastIssue) {
                    "过长" -> "偏长"
                    "开场白" -> "带了开场白"
                    "套话" -> "带了套话"
                    "逐人复述" -> "写成了逐人复述"
                    else -> "没完全按要求来"
                } + "，可以再点一次重试"
                else "输出被长度上限截断，可以再点一次重试")
        }
        throw IllegalStateException("模型没返回正文（$lastFinish，素材 ${body.size} 条）")
    }

    /**
     * 提示词管不住的形状问题：开场白、套话、逐人复述、明显超长。
     * 返回问题名给第二次请求用，null 表示可以收。
     */
    private fun summaryIssue(text: String, limit: Int): String? {
        val head = text.take(30)
        if (head.contains("以下是") || head.startsWith("总结")) return "开场白"
        // 「首先」只在句首才算套话，正文里照抄的原话不该被误判。
        if (FIRST_SECOND.containsMatchIn(text) || text.contains("总而言之") ||
            text.contains("值得一提的是")) return "套话"
        if (text.contains("甲说") && text.contains("乙说")) return "逐人复述"
        // 多写十几二十字交给 trim 在句号处收口，不为这点溢出多跑一次完整请求。
        if (text.length > limit + 20) return "过长"
        return null
    }

    private val FIRST_SECOND = Regex("(^|[。！？\\n])首先")

    /**
     * 提示词只是软约束，模型想写长还是会写长。超了就在句号处收口，
     * 宁可少一个收尾，也不要留半句话。
     */
    private fun trimToLength(text: String, limit: Int = 400): String {
        if (text.length <= limit) return text
        // 只在 limit 以内找句号：以前多留 80 字的窗口，提示词和 CHANGELOG 写的上限都不作数。
        val head = text.take(limit)
        val cut = head.indexOfLast { it in "。！？…\n" }
        // 找不到句读时就近硬切，不留一个没有标点的尾巴在外面。
        return if (cut >= limit / 2) head.take(cut + 1).trim() else head.trim()
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

    /**
     * 一次导出用的快照：点下去那一刻就把统计、名字和消息池定住。
     * 导出期间用户切筛选、补昵称都不影响这一份，写出来的就是他按下按钮时看到的数据。
     */
    private data class ExportSnapshot(
        val stats: GroupStats,
        val names: Map<String, String>,
        val pool: List<dev.jev.wechatmood.reply.ReplyMessage>,
        val rangeLabel: String,
        val limitLabel: String,
        val windowText: String,
        val compareText: String,
        val sourceNote: String,
    )

    /**
     * 导出 CSV 到系统「下载」目录。
     *
     * 为什么走 MediaStore：模块跑在微信进程里，Android 10 起的分区存储不允许按绝对路径往
     * 公共下载目录写文件；MediaStore.Downloads 是系统给的合规入口，插一条记录再往里写流就行。
     * 这样既不用申请 WRITE_EXTERNAL_STORAGE（targetSdk 35 上这个权限本来也不起作用），
     * 也不用猜各家 ROM 把下载目录挪到了哪里。
     */
    private fun exportCsv(stats: GroupStats, names: Map<String, String>, line: TextView) {
        if (stats.total == 0) {
            showExportStatus(line, "这个时段没有消息，没有可导出的内容", failed = true)
            return
        }
        val pool = selectedMessages()
        val previous = previousTotal
        val snapshot = ExportSnapshot(
            stats = stats,
            names = names,
            pool = pool,
            rangeLabel = windowLabel(StatsFilter.range),
            limitLabel = StatsFilter.limitLabel,
            windowText = spanText(pool),
            // 环比用界面上那一行同样的算法；没有可比的上一周期就在文件里说清楚，不留空。
            compareText = previous?.let { GroupStatsCalculator.compare(stats.total, it) }.orEmpty(),
            sourceNote = sourceNote,
        )
        line.visibility = View.VISIBLE
        line.setTextColor(theme.muted)
        line.text = "正在导出…"
        scope.launch {
            // 群名在 chatroom 表里，查不到（私聊、群名还没同步）就让文件名用会话哈希。
            val title = runCatching { groupTitle() }.getOrNull()
            val outcome = withContext(Dispatchers.IO) { runCatching { writeCsv(snapshot, title) } }
            outcome.fold(
                onSuccess = { shown ->
                    dev.jev.wechatmood.core.MoodLog.i("STATS_EXPORT_OK path=" + shown + " rows=" + stats.total)
                    showExportStatus(line, "已导出到 " + shown, failed = false)
                },
                onFailure = { error ->
                    // 只记异常类名：消息文本里可能带路径甚至聊天内容，不往日志里灌。
                    dev.jev.wechatmood.core.MoodLog.w("STATS_EXPORT_FAIL " + error.javaClass.simpleName)
                    showExportStatus(line, "导出失败：" + exportReason(error), failed = true)
                },
            )
        }
    }

    /** 群名：只有 @chatroom 才查 chatroom 表，私聊直接返回 null。 */
    private suspend fun groupTitle(): String? {
        val talker = context.talker
        if (!talker.endsWith("@chatroom")) return null
        return ReplyDatabaseHistory.groupNames(listOf(talker))[talker]?.takeIf { it.isNotBlank() }
    }

    /** 状态行：成功绿、失败红；不弹 Toast，免得把面板盖住。 */
    private fun showExportStatus(line: TextView, message: String, failed: Boolean) {
        line.text = message
        line.setTextColor(if (failed) theme.error else theme.success)
        line.visibility = if (message.isBlank()) View.GONE else View.VISIBLE
    }

    /** 失败原因说人话：异常栈、URI、完整路径都不进界面。 */
    private fun exportReason(error: Throwable): String = when (error) {
        is SecurityException -> "没有写入下载目录的权限"
        is IOException -> "写文件出错，可能是存储空间不足"
        else -> "系统没有给出可写的位置"
    }

    /**
     * 拼 CSV 并写进「下载」目录，整个过程在 IO 线程。
     *
     * 为什么开头加 UTF-8 BOM：Excel 打开没有 BOM 的 UTF-8 CSV 会按本地 ANSI 猜编码，
     * 中文群名和成员名直接变乱码；BOM 只有三个字节，文本编辑器、pandas、Numbers 都认。
     *
     * 返回给人看的路径：Android 10+ 是「下载/xxx.csv」，Android 9 兜底到私有目录时是绝对路径。
     */
    private fun writeCsv(snapshot: ExportSnapshot, title: String?): String {
        val stats = snapshot.stats
        val records = snapshot.pool.map {
            StatRecord(normalizedMillis(it.time), if (it.speaker == "我") 1 else 0, it.type, it.text, it.speaker)
        }
        val kinds = GroupStatsCalculator.KIND_LABELS
        val members = memberRows(stats, snapshot.names, records)
        val memberPercents = GroupStatsCalculator.percents(members.map { it.second[0] })
        val hours = hourCounts(records)
        val kindCounts = kinds.map { stats.types[it.first] ?: 0 }
        val otherTypes = (stats.types.values.sum() - kindCounts.sum()).coerceAtLeast(0)
        val typeRows = kinds.mapIndexed { index, pair -> pair.second to kindCounts[index] } + ("其它" to otherTypes)
        val typePercents = GroupStatsCalculator.percents(typeRows.map { it.second })

        val text = buildString {
            append('\uFEFF')
            appendLine(csvRow("言外 · 聊天统计"))
            appendLine(csvRow("导出时间", stampText("yyyy-MM-dd HH:mm")))
            appendLine(csvRow("统计时段", snapshot.rangeLabel))
            appendLine(csvRow("条数范围", snapshot.limitLabel))
            appendLine(csvRow("时间窗口", snapshot.windowText))
            appendLine(csvRow("消息条数", stats.total))
            appendLine(csvRow("发言人数", stats.senders))
            appendLine(csvRow("人均条数", stats.perCapita))
            appendLine(csvRow("环比", snapshot.compareText.ifBlank { "无可比的上一周期" }))
            appendLine(csvRow("采样", if (stats.sampled) "最近 " + GroupStatsCalculator.SAMPLE_LIMIT + " 条采样" else "全量"))
            if (snapshot.sourceNote.isNotBlank()) appendLine(csvRow("数据来源", snapshot.sourceNote))
            appendLine()
            appendLine(csvRow("成员名", "消息数", "文字", "图片", "语音", "视频", "表情", "占比"))
            members.forEachIndexed { index, (shown, cells) ->
                appendLine(csvRow(shown, cells[0], cells[1], cells[2], cells[3], cells[4], cells[5],
                    percentText(memberPercents[index])))
            }
            appendLine()
            appendLine(csvRow("小时", "条数"))
            hours.forEachIndexed { hour, count -> appendLine(csvRow(hour, count)) }
            appendLine()
            appendLine(csvRow("类型", "条数", "占比"))
            typeRows.forEachIndexed { index, (label, count) ->
                appendLine(csvRow(label, count, percentText(typePercents[index])))
            }
        }

        val fileName = "yanwai-stats-" + fileLabel(title) + "-" + stampText("yyyyMMdd-HHmm") + ".csv"
        val bytes = text.toByteArray(Charsets.UTF_8)
        val resolver = activity.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+：Downloads 集合 + RELATIVE_PATH，写自己新建的文件不需要任何存储权限。
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("media store insert failed")
            try {
                resolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: throw IOException("open output stream failed")
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (error: Throwable) {
                // 没写完就留个 0 字节条目在下载列表里，比没写更烦人，撤掉。
                runCatching { resolver.delete(uri, null, null) }
                throw error
            }
            return "下载/" + fileName
        }
        // Android 9 没有 MediaStore.Downloads，只能回到老路径写公共下载目录：
        // 微信自己有存储权限时写得进去，没有就抛 SecurityException，按约定兜到应用私有外部目录。
        @Suppress("DEPRECATION")
        val publicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val publicOk = runCatching {
            if (!publicDir.exists()) publicDir.mkdirs()
            File(publicDir, fileName).writeBytes(bytes)
        }.isSuccess
        if (publicOk) return "下载/" + fileName
        // 私有外部目录不需要权限；卸载微信时文件会一起被清掉，所以路径要明写给用户。
        val own = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: throw IOException("no external files dir")
        if (!own.exists()) own.mkdirs()
        val ownFile = File(own, fileName)
        ownFile.writeBytes(bytes)
        return ownFile.absolutePath
    }

    /**
     * 成员表数据：按面板同一套显示名合并，再按条数排序。
     * 返回「显示名 → [总数, 文字, 图片, 语音, 视频, 表情]」。
     */
    private fun memberRows(stats: GroupStats, names: Map<String, String>,
        records: List<StatRecord>): List<Pair<String, IntArray>> {
        val kindsByRaw = memberKinds(records)
        val merged = LinkedHashMap<String, IntArray>()
        for (member in stats.members) {
            // 名字用面板上那一份（已解析、已编号、已截断），查不到才退回原始名。
            val shown = names[member.name]?.takeIf { it.isNotBlank() } ?: member.name
            val cell = merged.getOrPut(shown) { IntArray(6) }
            cell[0] += member.count
            kindsByRaw[member.name]?.let { counts -> for (i in 0 until 5) cell[i + 1] += counts[i] }
        }
        return merged.entries.sortedByDescending { it.value[0] }.map { it.key to it.value }
    }

    /**
     * 每个成员的类型明细。
     * 逐类型各跑一遍 GroupStatsCalculator，而不是在这儿再抄一套「谁能算成员、wxid 怎么处理」：
     * 那份规则在面板里改得勤，抄一份迟早和界面对不上。
     * 返回 原始名 → [文字, 图片, 语音, 视频, 表情, 其它]。
     */
    private fun memberKinds(records: List<StatRecord>): Map<String, IntArray> {
        val columns = mapOf(1 to 0, 3 to 1, 34 to 2, 43 to 3, 47 to 4)
        val out = LinkedHashMap<String, IntArray>()
        for ((type, column) in columns) {
            val part = GroupStatsCalculator.compute(records.filter { it.type == type })
            for (member in part.members) out.getOrPut(member.name) { IntArray(6) }[column] += member.count
        }
        // 剩下的类型（转账、系统提示、合并转发等）合起来算「其它」，成员表里不单独占列。
        val others = GroupStatsCalculator.compute(records.filter { record -> columns.keys.none { it == record.type } })
        for (member in others.members) out.getOrPut(member.name) { IntArray(6) }[5] += member.count
        return out
    }

    /** 时段分布：按消息本地时间落在几点统计；时间戳缺失（0）的跳过，否则会全堆到 0 点。 */
    private fun hourCounts(records: List<StatRecord>): IntArray {
        val counts = IntArray(24)
        val calendar = java.util.Calendar.getInstance()
        for (record in records) {
            if (record.time <= 0) continue
            calendar.timeInMillis = record.time
            counts[calendar.get(java.util.Calendar.HOUR_OF_DAY)]++
        }
        return counts
    }

    /** 百分比固定按中文区域格式化：别的系统语言会把小数点写成逗号，CSV 立刻错列。 */
    private fun percentText(value: Double): String = String.format(java.util.Locale.CHINA, "%.1f%%", value)

    private fun stampText(pattern: String): String =
        java.text.SimpleDateFormat(pattern, java.util.Locale.CHINA).format(java.util.Date())

    /**
     * 文件名中间那段：群名查得到就用群名，查不到（私聊、群名没同步）用会话哈希。
     * 不写 wxid：文件名会出现在下载列表、分享面板里，没必要把账号标识带出去。
     */
    private fun fileLabel(title: String?): String {
        val safe = title?.let { safeFilePart(it) }.orEmpty()
        if (safe.isNotEmpty()) return safe
        // hashCode 可能是 Int.MIN_VALUE，abs 会溢出成负数，单独兜一下。
        val hash = context.talker.hashCode()
        return (if (hash == Int.MIN_VALUE) 0 else kotlin.math.abs(hash)).toString()
    }

    /** 文件名里的名字：去掉 / \ : * ? " < > | 和换行等控制符，最多 20 字，避免非法字符和超长文件名。 */
    private fun safeFilePart(raw: String): String = raw
        .filterNot { it in "/\\:*?\"<>|" || it.isISOControl() }
        .trim()
        .trimStart('.')
        .take(20)
        .trimEnd()

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
        /** 一次分析最多看这么多条视频：每条抽 4–6 帧，同样是一次视觉调用，最近优先。 */
        const val MAX_STATS_VIDEOS = 3
        /** 逐条交代视频时最多放宽这么多条：每条 60 字，再多就靠模型自己压缩，不让总结无限长。 */
        const val MAX_VIDEO_WIDEN = 6
        /** 抽样后至少保留这几条合并转发：一条能带出好几段对话，被抽掉最可惜。 */
        const val MAX_STATS_FORWARDS = 3
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

/** CSV 单元格：逗号、引号、换行按 RFC 4180 包引号，内部引号写两遍。 */
private fun csvCell(value: Any?): String {
    val text = value?.toString().orEmpty()
    return if (text.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
        "\"" + text.replace("\"", "\"\"") + "\""
    } else {
        text
    }
}

/** 一行 CSV：每个单元格都过一遍 [csvCell]。 */
private fun csvRow(vararg cells: Any?): String = cells.joinToString(",") { csvCell(it) }
