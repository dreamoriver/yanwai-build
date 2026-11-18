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
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** 群聊统计窗口：数字 + 活跃成员 + 一次 AI 解读，结果可复制。 */
internal class GroupStatsUi(private val activity: Activity, private val context: ReplyContext) {
    private val theme = ReplyTheme(activity)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val stats: GroupStats
    private val topContainer = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private var currentNames: Map<String, String> = emptyMap()
    private var analysisText: String = ""

    init {
        val records = context.messages.map {
            StatRecord(it.time, if (it.speaker == "我") 1 else 0, 1, it.text, it.speaker)
        }
        val base = GroupStatsCalculator.compute(records, sampled = context.trimmed)
        // 媒体不在回复上下文里，用漏掉的条数补上，否则永远是 0。
        val voices = context.messages.count { it.voiceState == dev.jev.wechatmood.voice.VoiceState.READY }
        stats = base.copy(media = context.omittedMedia, voice = voices)
    }

    fun show() {
        val dialog = Dialog(activity)
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
        body.addView(caption("最近 ${context.messages.size} 条消息$span · 人均 ${stats.perCapita} 条 · 只在本机计算"))

        val numbers = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, theme.dp(14), 0, theme.dp(6))
        }
        numbers.addView(number("${stats.total}", "条消息"), weight())
        numbers.addView(number("${stats.senders}", "人发言"), weight())
        numbers.addView(number("${stats.media + stats.voice}", "图片语音"), weight())
        numbers.addView(number("${stats.activeHours.size}", "个时段"), weight())
        body.addView(numbers, LinearLayout.LayoutParams(-1, theme.dp(72)))

        body.addView(section("最活跃"))
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
            aiButton.text = "正在分析…"
            analysisBox.visibility = View.VISIBLE
            analysisBox.removeAllViews()
            analysisBox.addView(panel("分析", "正在分析…"))
            scope.launch {
                val text = runCatching { analyze(context) }.getOrElse { error ->
                    dev.jev.wechatmood.core.MoodLog.w("STATS_AI_FAILED " + error.javaClass.simpleName + " " + error.message)
                    "分析失败：" + (error.message ?: "请重试")
                }
                showAnalysis(text.ifBlank { "模型没有返回内容，可以再点一次试试" })
                aiButton.text = "重新分析"
                aiButton.isEnabled = true
            }
        }
        body.addView(aiButton, LinearLayout.LayoutParams(-1, theme.dp(44)).apply { topMargin = theme.dp(12) })

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

        val scroll = android.widget.ScrollView(activity).apply {
            isFillViewport = false
            addView(body)
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
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

        // 昵称在数据库里，异步查一次再替换 wxid。
        scope.launch {
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
            val shown = when {
                resolved != null -> resolved
                member.name.trim().let { it.startsWith("wxid_") || it.matches(Regex("[A-Za-z0-9_-]{16,}")) } ->
                    "未获取昵称#${++unknown}"
                else -> "匿名成员#${++anonymous}"
            }
            merged[shown] = (merged[shown] ?: 0) + member.count
        }
        topContainer.addView(headerRow(), LinearLayout.LayoutParams(-1, theme.dp(28)))
        val ordered = merged.entries.sortedByDescending { it.value }
        val percents = dev.jev.wechatmood.reply.GroupStatsCalculator.percents(ordered.map { it.value })
        ordered.take(12).forEachIndexed { index, entry ->
            topContainer.addView(tableRow(entry.key, "${entry.value}", "${percents[index]}%"),
                LinearLayout.LayoutParams(-1, theme.dp(34)))
        }
        if (ordered.size > 12) topContainer.addView(caption("另有 ${ordered.size - 12} 位成员未列出"))
    }

    private fun resolveName(raw: String, names: Map<String, String>): String? {
        names[raw]?.trim()?.takeIf { it.isNotEmpty() }?.let { return shorten(it) }
        val clean = raw.trim()
        if (clean.isEmpty()) return null
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
            val regex = Regex("[【\\[]?$key[】\\]]?[：:， ]")
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

    private suspend fun analyze(context: ReplyContext): String {
        val settings = ModulePrefs.replySettings()
        check(settings.isConfigured) { "先在言外「回复」里配置模型" }
        val sample = context.messages.takeLast(30).joinToString("\n") { "${it.speaker}: ${it.text.take(80)}" }
        val payload = JSONObject().put("model", settings.model).put("stream", false)
            .put("max_tokens", 700).put("temperature", 0.4)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content",
                    "你在读一个微信群。根据统计和最近消息，按六个板块写观察，每段不超过 60 字，" +
                        "严格用【氛围】【话题】【角色】【互动】【风险】【建议】作小标题，每块单独一行：\n" +
                        "氛围：活跃度、关系亲疏、轻松还是严肃\n" +
                        "话题：主要在聊什么，可归到日常闲聊、工作业务、生活琐事、八卦吐槽、事务通知\n" +
                        "角色：谁发起话题、谁接话、谁潜水、谁起哄\n" +
                        "互动：谁和谁互动最多，有没有小圈子\n" +
                        "风险：有没有争议、敏感表述或负面情绪堆积\n" +
                        "建议：接下来适合聊什么\n" +
                        "不要 Markdown，不要星号井号，不要编号；只依据给出内容，不编造。"))
                .put(JSONObject().put("role", "user").put("content",
                    "统计：${stats.lines().joinToString("；")}\n最近消息：\n$sample")))
        val client = ReplyHttpClient()
        // 空返回重试一次：有些服务偶发返回空 content，重试通常就好了。
        repeat(2) { attempt ->
            val raw = withContext(Dispatchers.IO) {
                client.request(settings, payload, parse = { body ->
                    JSONObject(body).getJSONArray("choices").getJSONObject(0)
                        .getJSONObject("message").getString("content").trim()
                })
            }
            val clean = TextClean.plain(raw).take(900)
            dev.jev.wechatmood.core.MoodLog.i("STATS_AI_OK attempt=${attempt + 1} chars=${clean.length}")
            if (clean.isNotBlank()) return clean
        }
        // 长提示被过滤时，退成最短问法再试一次。
        val short = JSONObject().put("model", settings.model).put("stream", false)
            .put("max_tokens", 300).put("temperature", 0.4)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content",
                    "用三句话说明这个微信群里在聊什么、气氛怎么样，不要 Markdown。"))
                .put(JSONObject().put("role", "user").put("content",
                    "统计：${stats.lines().joinToString("；")}\n最近消息：\n$sample")))
        val raw = withContext(Dispatchers.IO) {
            client.request(settings, short, parse = { body ->
                JSONObject(body).getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content").trim()
            })
        }
        val clean = TextClean.plain(raw).take(400)
        dev.jev.wechatmood.core.MoodLog.i("STATS_AI_SHORT chars=${clean.length}")
        if (clean.isNotBlank()) return clean
        throw IllegalStateException("模型三次都返回了空内容，换个模型或服务再试")
    }

    private fun copy(text: String) {
        (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("言外统计", text))
        Toast.makeText(activity, "已复制", Toast.LENGTH_SHORT).show()
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
