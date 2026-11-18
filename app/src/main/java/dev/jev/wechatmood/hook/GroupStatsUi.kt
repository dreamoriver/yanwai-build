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

/** 群聊统计窗口：数字 + 活跃成员 + 可选的一次 AI 解读，结果可复制。 */
internal class GroupStatsUi(private val activity: Activity, private val context: ReplyContext) {
    private val theme = ReplyTheme(activity)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val stats: GroupStats

    init {
        val records = context.messages.map {
            StatRecord(it.time, if (it.speaker == "我") 1 else 0, 1, it.text, it.speaker)
        }
        stats = GroupStatsCalculator.compute(records, sampled = context.trimmed)
    }

    fun show() {
        val dialog = Dialog(activity)
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(theme.dp(20), theme.dp(18), theme.dp(20), theme.dp(18))
            background = theme.shape(theme.surface, 22, theme.border)
        }
        body.addView(title("群聊统计"))
        body.addView(caption("最近 ${context.messages.size} 条消息 · 只在本机计算"))

        val numbers = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, theme.dp(14), 0, theme.dp(6))
        }
        numbers.addView(number("${stats.total}", "条消息"), weight())
        numbers.addView(number("${stats.senders}", "人发言"), weight())
        numbers.addView(number("${stats.mine}", "我发的"), weight())
        numbers.addView(number("${stats.media + stats.voice}", "图片语音"), weight())
        body.addView(numbers)

        if (stats.topSenders.isNotEmpty()) {
            body.addView(section("最活跃"))
            stats.topSenders.forEach { (name, count) ->
                body.addView(row("${name}", "${count} 条"))
            }
        }
        if (stats.activeHours.isNotEmpty()) {
            body.addView(section("活跃时段"))
            body.addView(caption(stats.activeHours.joinToString("、") { "${it} 点" }))
        }
        if (stats.links > 0) {
            body.addView(section("链接"))
            body.addView(caption("${stats.links} 条"))
        }

        val analysis = TextView(activity).apply {
            textSize = 13f
            setTextColor(theme.ink)
            setPadding(theme.dp(12), theme.dp(10), theme.dp(12), theme.dp(10))
            background = theme.shape(theme.card, 14, theme.border)
            visibility = View.GONE
            setLineSpacing(0f, 1.2f)
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
                    "分析失败：" + (error.message ?: "请重试")
                }
                analysis.text = text
                aiButton.text = "重新分析"
                aiButton.isEnabled = true
            }
        }
        body.addView(aiButton, LinearLayout.LayoutParams(-1, theme.dp(44)).apply { topMargin = theme.dp(12) })

        val actions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, theme.dp(10), 0, 0)
        }
        actions.addView(theme.action("复制统计", quiet = true) {
            copy(exportText(analysis.text?.toString().orEmpty()))
        }, LinearLayout.LayoutParams(0, theme.dp(44), 1f))
        actions.addView(theme.action("关闭", quiet = true) { dialog.dismiss() },
            LinearLayout.LayoutParams(0, theme.dp(44), 1f))
        body.addView(actions)

        dialog.setContentView(body)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setGravity(Gravity.BOTTOM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.32f)
        }
        dialog.setOnDismissListener { scope.cancel() }
        dialog.show()
    }

    private fun exportText(analysis: String): String = buildString {
        appendLine("群聊统计 · 最近 ${context.messages.size} 条")
        appendLine("共 ${stats.total} 条 · ${stats.senders} 人发言 · 我发了 ${stats.mine} 条")
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
        val sample = context.messages.takeLast(12).joinToString("\n") { "${it.speaker}: ${it.text.take(60)}" }
        val payload = JSONObject().put("model", settings.model).put("stream", false)
            .put("max_tokens", 300).put("temperature", 0.4)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content",
                    "你在读一个微信群。根据给出的统计和最近几条消息，用三句话概括这个群在聊什么、气氛如何、" +
                        "有什么值得注意的。直接说话，不要 Markdown，不要星号，不要编号，不超过 120 字。"))
                .put(JSONObject().put("role", "user").put("content",
                    "统计：${stats.lines().joinToString("；")}\n最近消息：\n$sample")))
        val client = ReplyHttpClient()
        return withContext(Dispatchers.IO) {
            client.request(settings, payload, parse = { body ->
                JSONObject(body).getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content").trim()
            })
        }.let(TextClean::plain).take(200)
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
        setPadding(0, theme.dp(12), 0, theme.dp(4))
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
        setPadding(0, theme.dp(4), 0, theme.dp(4))
        addView(TextView(activity).apply {
            text = name
            textSize = 13f
            setTextColor(theme.ink)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(TextView(activity).apply {
            text = value
            textSize = 13f
            setTextColor(theme.muted)
        })
    }
}
