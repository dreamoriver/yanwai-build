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
        body.addView(numbers, LinearLayout.LayoutParams(-1, theme.dp(84)))

        body.addView(section("最活跃"))
        body.addView(topContainer, LinearLayout.LayoutParams(-1, -2))
        renderTop(emptyMap())

        if (stats.activeHours.isNotEmpty()) {
            body.addView(section("活跃时段"))
            body.addView(caption(stats.activeHours.joinToString("、") { "${it} 点" }))
        }

        val analysis = TextView(activity).apply {
            textSize = 13f
            setTextColor(theme.ink)
            setPadding(theme.dp(12), theme.dp(10), theme.dp(12), theme.dp(10))
            background = theme.shape(theme.card, 14, theme.border)
            visibility = View.GONE
            minHeight = theme.dp(72)
            setLineSpacing(0f, 1.25f)
        }
        body.addView(analysis, LinearLayout.LayoutParams(-1, -2).apply { topMargin = theme.dp(12) })

        lateinit var aiButton: TextView
        aiButton = theme.action("让 AI 说说这群在聊什么", primary = true) {
            aiButton.isEnabled = false
            aiButton.text = "正在分析…"
            analysis.visibility = View.VISIBLE
            analysis.text = "正在分析…"
            scope.launch {
                val text = runCatching { analyze(context) }.getOrElse { error ->
                    dev.jev.wechatmood.core.MoodLog.w("STATS_AI_FAILED " + error.javaClass.simpleName + " " + error.message)
                    "分析失败：" + (error.message ?: "请重试")
                }
                analysis.text = text.ifBlank { "模型没有返回内容，可以再点一次试试" }
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
            copy(exportText(analysis.text?.toString().orEmpty()))
        }, LinearLayout.LayoutParams(0, theme.dp(44), 1f))
        actions.addView(theme.action("生成图片", quiet = true) {
            val summary = analysis.text?.toString()?.takeIf {
                it.isNotBlank() && !it.startsWith("正在") && !it.startsWith("分析失败")
            }
            val bitmap = StatsImage.render(stats, currentNames, summary, "群聊统计")
            val uri = StatsImage.save(activity, bitmap)
            Toast.makeText(activity,
                if (uri != null) "已保存到相册，可在相册里发送" else "保存失败，检查相册权限",
                Toast.LENGTH_LONG).show()
        }, LinearLayout.LayoutParams(0, theme.dp(44), 1f))
        actions.addView(theme.action("关闭", quiet = true) { dialog.dismiss() },
            LinearLayout.LayoutParams(0, theme.dp(44), 1f))
        body.addView(actions)

        val scroll = android.widget.ScrollView(activity).apply {
            isFillViewport = false
            addView(body)
        }
        dialog.setContentView(scroll)
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
        val shown = stats.members.take(10)
        val percents = dev.jev.wechatmood.reply.GroupStatsCalculator.percents(shown.map { it.count })
        shown.forEachIndexed { index, member ->
            val name = displayName(names[member.name] ?: member.name)
            val media = member.media + member.voice
            val row = tableRow(name, "${member.count} 条", "${percents[index]}%", if (media > 0) "$media" else "-")
            row.setOnClickListener { showMemberDetail(member, name) }
            topContainer.addView(row, LinearLayout.LayoutParams(-1, theme.dp(34)))
        }
        if (stats.members.size > shown.size) {
            topContainer.addView(caption("另有 ${stats.members.size - shown.size} 位成员未列出"))
        }
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

    /** 空名字、纯符号的昵称统一显示成「群成员」。 */
    private fun displayName(raw: String): String {
        val clean = raw.trim()
        if (clean.isEmpty() || clean.none { it.isLetterOrDigit() }) return "群成员"
        return if (clean.length > 12) clean.take(12) + "…" else clean
    }

    private fun tableRow(name: String, count: String, percent: String, media: String) = LinearLayout(activity).apply {
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
        }, LinearLayout.LayoutParams(theme.dp(64), -2))
        addView(TextView(activity).apply {
            text = percent
            textSize = 13f
            setTextColor(theme.muted)
            gravity = Gravity.END
        }, LinearLayout.LayoutParams(theme.dp(52), -2))
        addView(TextView(activity).apply {
            text = media
            textSize = 13f
            setTextColor(theme.muted)
            gravity = Gravity.END
        }, LinearLayout.LayoutParams(theme.dp(40), -2))
    }

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
            .put("max_tokens", 900).put("temperature", 0.5)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content",
                    "你在读一个微信群。根据统计和最近消息，按六段写观察，每段不超过 60 字，" +
                        "分别以「氛围」「话题」「角色」「互动」「风险」「建议」开头：\n" +
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
        throw IllegalStateException("模型两次都返回了空内容，可能是服务端过滤或模型不支持该任务")
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
