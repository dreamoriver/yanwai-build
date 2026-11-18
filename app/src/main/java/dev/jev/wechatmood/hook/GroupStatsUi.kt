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
import dev.jev.wechatmood.core.ModulePrefs
import dev.jev.wechatmood.core.TextClean
import dev.jev.wechatmood.reply.GroupStats
import dev.jev.wechatmood.reply.GroupStatsCalculator
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

/** 分析范围：进程内记住上次选择，重开窗口沿用。 */
object StatsFilter {
    enum class Range(val label: String, val millis: Long) {
        HOUR("最近 1 小时", 60L * 60 * 1000),
        HOUR3("最近 3 小时", 3 * 60L * 60 * 1000),
        TODAY("今天", -1),
        ALL("全部加载", 0),
    }
    var range: Range = Range.ALL
    var limit: Int = 0  // 0 表示不限制
    val limitLabel: String get() = if (limit == 0) "全部" else "最近 $limit 条"
}

/** 群聊统计窗口：数字 + 活跃成员 + 一次 AI 解读，结果可复制。 */
internal class GroupStatsUi(private val activity: Activity, private val context: ReplyContext) {
    private val theme = ReplyTheme(activity)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val stats: GroupStats
    private val topContainer = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private var currentNames: Map<String, String> = emptyMap()
    private var analysisText: String = ""
    private var dialog: Dialog? = null

    /** 按选择的时段与条数裁剪消息。 */
    private fun selectedMessages(): List<dev.jev.wechatmood.reply.ReplyMessage> {
        val now = System.currentTimeMillis()
        val since = when (StatsFilter.range) {
            StatsFilter.Range.HOUR -> now - StatsFilter.Range.HOUR.millis
            StatsFilter.Range.HOUR3 -> now - StatsFilter.Range.HOUR3.millis
            StatsFilter.Range.TODAY -> {
                val calendar = java.util.Calendar.getInstance()
                calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
                calendar.set(java.util.Calendar.MINUTE, 0)
                calendar.set(java.util.Calendar.SECOND, 0)
                calendar.timeInMillis
            }
            StatsFilter.Range.ALL -> 0
        }
        val byTime = context.messages.filter { it.time >= since }
        return if (StatsFilter.limit > 0) byTime.takeLast(StatsFilter.limit) else byTime
    }

    init {
        val records = selectedMessages().map {
            StatRecord(it.time, if (it.speaker == "我") 1 else 0, 1, it.text, it.speaker)
        }
        val base = GroupStatsCalculator.compute(records, sampled = context.trimmed)
        // 媒体不在回复上下文里，用漏掉的条数补上，否则永远是 0。
        val voices = context.messages.count { it.voiceState == dev.jev.wechatmood.voice.VoiceState.READY }
        stats = base.copy(media = context.omittedMedia, voice = voices)
    }

    fun show() {
        lateinit var scroll: android.widget.ScrollView
        val dialog = Dialog(activity).also { this.dialog = it }
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(theme.dp(20), theme.dp(18), theme.dp(20), theme.dp(18))
            background = theme.shape(theme.surface, 22, theme.border)
        }
        body.addView(title(if (context.talker.endsWith("@chatroom")) "群聊统计" else "聊天统计"))
        val first = context.messages.firstOrNull { it.time > 0 }?.time
        val last = context.messages.lastOrNull { it.time > 0 }?.time
        val span = if (first != null && last != null) {
            val format = java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA)
            " · " + format.format(java.util.Date(first)) + "–" + format.format(java.util.Date(last))
        } else ""
        val filters = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, theme.dp(10), 0, 0)
        }
        filters.addView(filterPill("时段 · ${StatsFilter.range.label}") { pickRange() },
            LinearLayout.LayoutParams(0, theme.dp(34), 1f).apply { rightMargin = theme.dp(8) })
        filters.addView(filterPill("条数 · ${StatsFilter.limitLabel}") { pickLimit() },
            LinearLayout.LayoutParams(0, theme.dp(34), 1f))
        body.addView(filters)
        body.addView(caption("当前 ${stats.total} 条消息$span · 人均 ${stats.perCapita} 条 · 只在本机计算"))

        val numbers = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, theme.dp(14), 0, theme.dp(6))
        }
        numbers.addView(number("${stats.total}", "条消息"), weight())
        numbers.addView(number("${stats.senders}", "人发言"), weight())
        numbers.addView(number("${stats.media + stats.voice}", "图片语音"), weight())
        numbers.addView(number("${stats.activeHours.size}", "个时段"), weight())
        body.addView(numbers, LinearLayout.LayoutParams(-1, theme.dp(72)))

        body.addView(section("最活跃"), LinearLayout.LayoutParams(-1, -2).apply { topMargin = theme.dp(6) })
        body.addView(topContainer, LinearLayout.LayoutParams(-1, -2))
        renderTop(emptyMap())

        if (stats.activeHours.isNotEmpty()) {
            body.addView(section("活跃时段"))
            body.addView(caption(stats.activeHours.joinToString("、") { "${it} 点" }))
        }

        val analysisBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        body.addView(analysisBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = theme.dp(12) })
        analysisText = ""

        fun showAnalysis(text: String) {
            analysisText = text
            analysisBox.removeAllViews()
            analysisBox.visibility = View.VISIBLE
            val parsed = splitSections(text)
            // 模型没按标签写时，本地按句子切成几块，至少不是一整段。
            val sections = parsed.ifEmpty {
                val sentences = text.split("。", "！", "？").map { it.trim() }.filter { it.isNotEmpty() }
                sentences.chunked(2).mapIndexed { index, chunk ->
                    "要点 ${index + 1}" to (chunk.joinToString("。") + "。")
                }
            }
            if (sections.isEmpty()) {
                analysisBox.addView(panel("分析", text))
                return
            }
            sections.forEachIndexed { index, (label, content) ->
                // 默认只展开前两块，其余折起来，避免一屏全是字。
                val card = panel(label, content, collapsed = index >= 2)
                analysisBox.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = theme.dp(8) })
            }
        }

        lateinit var aiButton: TextView
        aiButton = theme.action("让 AI 说说这群在聊什么", primary = true) {
            aiButton.isEnabled = false
            analysisBox.visibility = View.VISIBLE
            analysisBox.removeAllViews()
            val bodies = LinkedHashMap<String, TextView>()
            sections.forEach { (label, _) ->
                val body = TextView(activity).apply {
                    text = "…"
                    textSize = 13f
                    setTextColor(theme.ink)
                    setPadding(0, theme.dp(4), 0, 0)
                    setLineSpacing(0f, 1.25f)
                }
                analysisBox.addView(panelWith(label, body),
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = theme.dp(8) })
                bodies[label] = body
            }
            scope.launch {
                val finished = java.util.concurrent.atomic.AtomicInteger(0)
                val gate = kotlinx.coroutines.sync.Semaphore(3)
                val jobs = sections.map { (label, instruction) ->
                    launch {
                        gate.withPermit {
                            val text = runCatching { analyzeSection(context, label, instruction) }.getOrElse { error ->
                                dev.jev.wechatmood.core.MoodLog.w("STATS_AI_FAILED $label " + error.message)
                                "（失败：" + (error.message ?: "未知原因") + "）"
                            }
                            withContext(Dispatchers.Main) {
                                bodies[label]?.text = text.ifBlank { "（模型返回空内容）" }
                                aiButton.text = "正在分析 ${finished.incrementAndGet()}/${sections.size}"
                            }
                        }
                    }
                }
                jobs.joinAll()
                analysisText = bodies.entries.joinToString("\n") { "${it.key}：${it.value.text}" }
                aiButton.text = "重新分析"
                aiButton.isEnabled = true
                scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
            }
        }

        val actions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, theme.dp(10), 0, 0)
        }
        actions.addView(theme.action("复制", quiet = true) {
            copy(exportText(analysisText))
        }, LinearLayout.LayoutParams(0, theme.dp(44), 1f))
        actions.addView(theme.action("生成图片", quiet = true) {
            val summary = analysisText.takeIf {
                it.isNotBlank() && !it.startsWith("分析失败")
            }
            val bitmap = StatsImage.render(stats, currentNames, summary, "群聊统计")
            val uri = StatsImage.save(activity, bitmap)
            Toast.makeText(activity,
                if (uri != null) "已保存到相册，可在相册里发送" else "保存失败，检查相册权限",
                Toast.LENGTH_LONG).show()
        }, LinearLayout.LayoutParams(0, theme.dp(44), 1f))
        actions.addView(theme.action("关闭", quiet = true) { dialog.dismiss() },
            LinearLayout.LayoutParams(0, theme.dp(44), 1f))
        actions.setPadding(theme.dp(20), theme.dp(8), theme.dp(20), theme.dp(14))
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = theme.shape(theme.surface, 22, theme.border)
        }
        body.background = null

        scroll = android.widget.ScrollView(activity).apply {
            isFillViewport = false
            addView(body)
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(aiButton, LinearLayout.LayoutParams(-1, theme.dp(44)).apply {
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
            setDimAmount(0.32f)
        }
        dialog.setOnDismissListener { scope.cancel() }
        dialog.show()

        // 昵称在数据库里，异步查一次再替换 wxid；顺便记一次表名，方便确认字段位置。
        scope.launch {
            runCatching { ReplyDatabaseHistory.tableNames() }
            val names = runCatching {
                ReplyDatabaseHistory.displayNames(stats.topSenders.map { it.first })
            }.getOrDefault(emptyMap())
            if (names.isNotEmpty()) renderTop(names)
        }
    }

    private fun renderTop(names: Map<String, String>) {
        currentNames = names
        topContainer.removeAllViews()
        if (stats.members.isEmpty()) {
            topContainer.addView(caption("没有可统计的发言"))
            return
        }
        val merged = LinkedHashMap<String, Int>()
        var anonymous = 0
        var unknown = 0
        for (member in stats.members) {
            val resolved = resolveName(member.name, names)
            val candidate = when {
                resolved != null -> resolved
                member.name.trim().let { it.startsWith("wxid_") || it.matches(Regex("[A-Za-z0-9_-]{16,}")) } ->
                    "未获取昵称#${++unknown}"
                else -> "匿名成员#${++anonymous}"
            }
            // 兜底：显示名不可见或没有实际字符时，也不要留空行。
            val shown = candidate.takeIf { it.any { ch -> ch.isLetterOrDigit() } }
                ?: "匿名成员#${++anonymous}"
            merged[shown] = (merged[shown] ?: 0) + member.count
        }
        topContainer.addView(headerRow(), LinearLayout.LayoutParams(-1, theme.dp(24)))
        val ordered = merged.entries.sortedByDescending { it.value }
        val percents = dev.jev.wechatmood.reply.GroupStatsCalculator.percents(ordered.map { it.value })
        ordered.take(12).forEachIndexed { index, entry ->
            topContainer.addView(tableRow(entry.key, "${entry.value}", "${percents[index]}%"),
                LinearLayout.LayoutParams(-1, theme.dp(26)))
        }
        if (ordered.size > 12) topContainer.addView(caption("另有 ${ordered.size - 12} 位成员未列出"))
    }

    private fun resolveName(raw: String, names: Map<String, String>): String? {
        names[raw]?.let { dev.jev.wechatmood.core.TextClean.plain(it).trim() }
            ?.takeIf { it.isNotEmpty() && it.any { ch -> ch.isLetterOrDigit() } }
            ?.let { return shorten(it) }
        val clean = raw.replace(Regex("[\\u200B-\\u200F\\uFEFF]"), "").trim()
        if (clean.isEmpty() || clean.none { it.isLetterOrDigit() }) return null
        if (clean.startsWith("wxid_") || clean.matches(Regex("[A-Za-z0-9_-]{16,}"))) return null
        return shorten(clean)
    }

    private fun headerRow() = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(activity).apply {
            text = "成员"
            textSize = 11f
            setTextColor(theme.muted)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(TextView(activity).apply {
            text = "条数"
            textSize = 11f
            setTextColor(theme.muted)
            gravity = Gravity.END
        }, LinearLayout.LayoutParams(theme.dp(56), -2))
        addView(TextView(activity).apply {
            text = "占比"
            textSize = 11f
            setTextColor(theme.muted)
            gravity = Gravity.END
        }, LinearLayout.LayoutParams(theme.dp(52), -2))
    }

    private fun tableRow(name: String, count: String, percent: String) = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(activity).apply {
            text = name
            textSize = 13f
            setTextColor(theme.ink)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(TextView(activity).apply {
            text = count
            textSize = 13f
            setTextColor(theme.muted)
            gravity = Gravity.END
        }, LinearLayout.LayoutParams(theme.dp(56), -2))
        addView(TextView(activity).apply {
            text = percent
            textSize = 13f
            setTextColor(theme.muted)
            gravity = Gravity.END
        }, LinearLayout.LayoutParams(theme.dp(52), -2))
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
        android.app.AlertDialog.Builder(activity)
            .setTitle("$shownName · ${member.count} 条")
            .setMessage("活跃时段：${member.hours.joinToString("、") { "$it 点" }.ifBlank { "未知" }}\n" +
                "发言范围：${span.ifBlank { "未知" }}\n\n$lines")
            .setPositiveButton("关闭", null)
            .show()
    }

    /**
     * 按标签把分析拆成板块。模型经常不换行，所以按关键词出现的位置切，
     * 而不是按行切；没出现任何标签就返回空，交给句子兜底。
     */
    private fun splitSections(text: String): List<Pair<String, String>> {
        val labels = listOf("氛围" to "群氛围", "话题" to "核心话题", "角色" to "成员角色",
            "互动" to "互动关系", "风险" to "风险提示", "建议" to "沟通建议")
        val hits = mutableListOf<Triple<Int, Int, Pair<String, String>>>()
        for ((key, label) in labels) {
            val regex = Regex("[【\\[]?$key[】\\]]?")
            var match = regex.find(text)
            while (match != null) {
                hits += Triple(match.range.first, match.range.last + 1, key to label)
                match = regex.find(text, match.range.last + 1)
            }
        }
        if (hits.isEmpty()) return emptyList()
        val sorted = hits.sortedBy { it.first }
        val result = mutableListOf<Pair<String, String>>()
        for ((index, hit) in sorted.withIndex()) {
            val start = hit.second
            val end = sorted.getOrNull(index + 1)?.first ?: text.length
            if (end <= start) continue
            val body = text.substring(start, end).trim().trimStart('：', ':', '，', ' ')
            if (body.isNotBlank()) result += hit.third.second to body
        }
        return result
    }

    /** 分析板块卡片：点标题折叠展开。 */
    private fun panelWith(label: String, body: TextView): LinearLayout {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(theme.dp(12), theme.dp(10), theme.dp(12), theme.dp(10))
            background = theme.shape(theme.card, 14, theme.border)
        }
        val title = TextView(activity).apply {
            text = label
            textSize = 14f
            setTextColor(theme.ink)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        card.addView(title)
        card.addView(body)
        title.setOnClickListener {
            body.visibility = if (body.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        return card
    }

    private fun panel(label: String, content: String, collapsed: Boolean = false): LinearLayout {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(theme.dp(12), theme.dp(10), theme.dp(12), theme.dp(10))
            background = theme.shape(theme.card, 14, theme.border)
        }
        val title = TextView(activity).apply {
            text = label
            textSize = 14f
            setTextColor(theme.ink)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val body = TextView(activity).apply {
            this.text = content
            textSize = 13f
            setTextColor(theme.ink)
            setPadding(0, theme.dp(4), 0, 0)
            setLineSpacing(0f, 1.25f)
        }
        card.addView(title)
        card.addView(body)
        if (collapsed) body.visibility = View.GONE
        title.setOnClickListener {
            body.visibility = if (body.visibility == View.VISIBLE) View.GONE else View.VISIBLE
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
        appendLine("群聊统计 · 最近 ${context.messages.size} 条")
        appendLine("共 ${stats.total} 条 · ${stats.senders} 人发言")
        if (stats.topSenders.isNotEmpty()) {
            appendLine("最活跃：" + stats.topSenders.joinToString("、") { "${it.first} ${it.second}" })
        }
        if (stats.activeHours.isNotEmpty()) {
            appendLine("活跃时段：" + stats.activeHours.joinToString("、") { "${it} 点" })
        }
        if (analysis.isNotBlank() && !analysis.startsWith("正在") && !analysis.startsWith("分析失败")) {
            appendLine()
            appendLine(analysis)
        }
    }.trim()

    /** 六个板块分开问，避免模型把内容揉成一段。 */
    private val sections = listOf(
        "群氛围" to "用两句话描述这个微信群的聊天风格和情绪走向：是互损玩梗、轻松闲聊还是公事公办。不要复述统计数字。",
        "核心话题" to "用两句话说这群最近在聊什么，归到日常闲聊、工作业务、生活琐事、八卦吐槽、事务通知其中一类。",
        "成员角色" to "用两句话说谁在发起话题、谁在接话、谁基本潜水。只依据给出的消息，不编造。",
        "互动关系" to "用两句话说成员之间的互动特点，有没有明显的小圈子。",
        "风险提示" to "用一句话说有没有争议、敏感表述或负面情绪，没有就直接说没有。",
        "沟通建议" to "用一句话说结合这个群的调性，接下来适合聊什么。",
    )

    private suspend fun analyzeSection(context: ReplyContext, label: String, instruction: String): String {
        var settings = ModulePrefs.replySettings()
        if (!settings.isConfigured) {
            // 微信进程可能还没连上设置服务，先强制刷新一次再试。
            ModulePrefs.reload(force = true)
            kotlinx.coroutines.delay(1200)
            settings = ModulePrefs.replySettings()
        }
        check(settings.isConfigured) {
            if (!ModulePrefs.bridgeAvailable) "设置服务未连接：先打开一次言外再回来分析" else "先在言外「回复」里配置模型"
        }
        val sample = context.messages.takeLast(24).joinToString("\n") { "${it.speaker}: ${it.text.take(60)}" }
        val payload = JSONObject().put("model", settings.model).put("stream", false)
            .put("max_tokens", 1200).put("temperature", 0.6)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content",
                    "$instruction 要求：口语化、有细节，不复述数字，不写 Markdown，不用星号，不超过 60 字。"))
                .put(JSONObject().put("role", "user").put("content",
                    "统计：${stats.lines().joinToString("；")}\n最近消息：\n$sample")))
        val client = ReplyHttpClient()
        val raw = withContext(Dispatchers.IO) {
            client.request(settings, payload, parse = { body ->
                val choice = JSONObject(body).getJSONArray("choices").getJSONObject(0)
                val message = choice.getJSONObject("message")
                val content = message.optString("content").trim()
                val reasoning = message.optString("reasoning_content").trim()
                val finish = choice.optString("finish_reason")
                dev.jev.wechatmood.core.MoodLog.i(
                    "STATS_RAW $label content=${content.length} reasoning=${reasoning.length} finish=$finish")
                // 只取正文：reasoning_content 是模型的思考过程，不能当答案。
                content
            })
        }
        if (raw.isBlank()) {
            // 推理型模型可能把预算全花在思考上，放大上限再试一次。
            val retry = JSONObject(payload.toString()).put("max_tokens", 2000)
            val second = withContext(Dispatchers.IO) {
                client.request(settings, retry, parse = { body ->
                    JSONObject(body).getJSONArray("choices").getJSONObject(0)
                        .getJSONObject("message").optString("content").trim()
                })
            }
            dev.jev.wechatmood.core.MoodLog.i("STATS_RETRY $label chars=${second.length}")
            if (second.isNotBlank()) return TextClean.plain(second).take(220)
        }
        check(raw.isNotBlank()) { "模型只返回了思考过程，没给正文；换一个非推理模型试试" }
        val clean = TextClean.plain(raw).take(220)
        dev.jev.wechatmood.core.MoodLog.i("STATS_SECTION $label chars=${clean.length}")
        return clean
    }

    private fun copy(text: String) {
        (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("言外统计", text))
        Toast.makeText(activity, "已复制", Toast.LENGTH_SHORT).show()
    }

    private fun filterPill(label: String, action: () -> Unit): TextView =
        theme.action(label, quiet = true) { action() }.apply {
            textSize = 12f
            minHeight = theme.dp(34)
            setPadding(theme.dp(8), 0, theme.dp(8), 0)
        }

    private fun pickRange() {
        val values = StatsFilter.Range.values()
        android.app.AlertDialog.Builder(activity).setTitle("分析时段")
            .setItems(values.map { it.label }.toTypedArray()) { _, which ->
                StatsFilter.range = values[which]
                dialog?.dismiss()
                GroupStatsUi(activity, context).show()
            }.setNegativeButton("取消", null).show()
    }

    private fun pickLimit() {
        val options = listOf(0, 10, 20, 50)
        val labels = options.map { if (it == 0) "全部加载" else "最近 $it 条" }.toTypedArray()
        android.app.AlertDialog.Builder(activity).setTitle("消息条数")
            .setItems(labels) { _, which ->
                StatsFilter.limit = options[which]
                dialog?.dismiss()
                GroupStatsUi(activity, context).show()
            }.setNegativeButton("取消", null).show()
    }

    private fun weight() = LinearLayout.LayoutParams(0, -2, 1f)
    private fun title(text: String) = TextView(activity).apply {
        this.text = text
        textSize = 18f
        setTextColor(theme.ink)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    }
    private fun section(text: String) = TextView(activity).apply {
        this.text = text
        textSize = 12f
        setTextColor(theme.muted)
        setPadding(0, theme.dp(14), 0, theme.dp(4))
    }
    private fun caption(text: String) = TextView(activity).apply {
        this.text = text
        textSize = 12f
        setTextColor(theme.muted)
        setPadding(0, theme.dp(4), 0, 0)
    }
    private fun number(value: String, label: String) = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(TextView(activity).apply {
            text = value
            textSize = 20f
            setTextColor(theme.ink)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        addView(TextView(activity).apply {
            text = label
            textSize = 11f
            setTextColor(theme.muted)
        })
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
