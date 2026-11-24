package dev.jev.wechatmood.hook

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import dev.jev.wechatmood.core.MoodLog
import dev.jev.wechatmood.reply.ReplyContext
import dev.jev.wechatmood.reply.StyleAnalyzer
import dev.jev.wechatmood.reply.StyleProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 说话习惯。
 *
 * 这段口吻画像原本只喂给模型：回复不像自己时，用户既看不到依据也提不出异议。
 * 这里把同一份统计（StyleAnalyzer 的产出）摊开——一段摘要、几个数字、语气词、几句原话，
 * 说明只用字面和标点算，让「为什么回得像我/不像我」有地方核对。
 *
 * 数据只在本窗口里过一遍：不写盘、不新增权限，也不缓存聊天原文；
 * 「复制摘要」是用户自己按下去才把纯文本放进剪贴板。
 */
internal class StyleProfileUi(private val activity: Activity, private val talker: String) {

    /** 窗口状态。读不到库和「确实没数据」必须分开：两者都说成 0 条就是在骗人。 */
    private enum class Phase { LOADING, READY, TOO_FEW, DATABASE_UNAVAILABLE, NO_TALKER }

    private val kit = PanelKit(activity)
    private val theme = kit.theme
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private var copyAction: TextView? = null
    private var profile: StyleProfile? = null

    fun show() {
        val dialog = Dialog(activity)
        val root = kit.root()
        val column = kit.content(root)
        column.addView(kit.heading("说话习惯"))
        column.addView(kit.caption(
            "从你在这个聊天最近最多 200 条自己发的文字统计，只算字面和标点，不存聊天原文")
            .apply { setPadding(0, theme.dp(4), 0, theme.dp(8)) })
        // 内容多时按上限滚动，内容少时只占实际高度，和关系温度、纪要窗口一致。
        val scroll = PanelMaxScroll(activity,
            (activity.resources.displayMetrics.heightPixels * 0.62f).toInt()).apply { addView(body) }
        column.addView(scroll, LinearLayout.LayoutParams(-1, -2).apply { topMargin = theme.dp(6) })

        // 复制按钮在统计出来之前不可点：禁用态从 theme 里取灰色，不靠 toast 解释为什么按不动。
        val copy = theme.action("复制摘要", primary = true) { copySummary() }.apply { isEnabled = false }
        copyAction = copy
        val actions = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(copy, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = theme.dp(8) })
        actions.addView(theme.action("关闭", quiet = true) { dialog.dismiss() },
            LinearLayout.LayoutParams(0, -2, 1f))
        column.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = theme.dp(10) })

        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setGravity(Gravity.BOTTOM)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(ReplyMetrics.DIM)
        }
        // show 之前就定好宽度：放到 onShow 里设置会先按内容宽度露半屏，再跳成全宽。
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.show()
        dialog.setOnDismissListener { scope.cancel() }

        if (talker.isBlank()) {
            render(Phase.NO_TALKER, null)
        } else {
            render(Phase.LOADING, null)
            scope.launch { load() }
        }
    }

    /** 读库全部放在 IO；读库失败与「没数据」按不同状态收场，不合并成一个空态。 */
    private suspend fun load() {
        // 页面锚点只能在主线程读（要从聊天列表的 adapter 里取消息），它只用来核对账号；
        // 聊天窗口被面板盖住时拿不到也不致命，库里只剩一个账号时仍能读。
        val page: ReplyContext? = runCatching { MessageSniffer.replyContext(requireBottom = false) }.getOrNull()
        val texts: List<String>? = withContext(Dispatchers.IO) {
            runCatching { ReplyDatabaseHistory.ownStyleTexts(talker, page) }.getOrNull()
        }
        // 图片、表情、语音、合并转发的占位和引用占位由 analyzeTexts 内部剔除，
        // 这里只需要把「我」的文字原样传进去，不在界面层再写一套过滤规则。
        val analyzed = texts?.let { StyleAnalyzer.analyzeTexts(it) }
        MoodLog.i("STYLE_UI_LOAD texts=" + (texts?.size ?: -1) + " kept=" + (analyzed?.messageCount ?: -1) +
            " anchor=" + (page != null) + " talker=" + talker.hashCode())
        when {
            texts == null -> render(Phase.DATABASE_UNAVAILABLE, null)
            analyzed == null -> render(Phase.TOO_FEW, null)
            else -> render(Phase.READY, analyzed)
        }
    }

    private fun render(phase: Phase, value: StyleProfile?) {
        profile = value
        // 只有真正算出来才允许复制，避免把「读不到」的说明当成画像贴出去。
        copyAction?.isEnabled = value != null
        body.removeAllViews()
        when (phase) {
            Phase.LOADING -> body.addView(kit.body("正在统计…"))
            Phase.NO_TALKER -> {
                body.addView(kit.body("暂时识别不到当前聊天"))
                body.addView(kit.caption("回到聊天里重新打开这个窗口").apply { setPadding(0, theme.dp(4), 0, 0) })
            }
            Phase.DATABASE_UNAVAILABLE -> {
                body.addView(kit.body("本机聊天库暂时读不到"))
                body.addView(kit.caption(
                    "先打开一次这个聊天再回来。这里不是「没有数据」，是这次没读到。")
                    .apply { setPadding(0, theme.dp(4), 0, 0) })
            }
            Phase.TOO_FEW -> {
                body.addView(kit.body("这个聊天里你自己发的文字还不到 8 条，统计不出来"))
                body.addView(kit.caption("再聊几句回来就有了；只算文字，图片和表情不算。")
                    .apply { setPadding(0, theme.dp(4), 0, 0) })
            }
            Phase.READY -> value?.let { renderProfile(it) }
        }
    }

    private fun renderProfile(p: StyleProfile) {
        // 一段摘要拆成句子逐条列：原文是给模型的一整段，挤成一坨没人愿意读。
        body.addView(kit.sectionLabel("模型看到的画像"))
        val card = kit.card()
        summaryLines(p.summary()).forEach { line ->
            card.addView(theme.label("· " + line, ReplyMetrics.TEXT_BODY, theme.ink).apply {
                setLineSpacing(theme.dp(3).toFloat(), 1f)
            })
        }
        body.addView(card, LinearLayout.LayoutParams(-1, -2))

        body.addView(kit.sectionLabel("数字"))
        val table = kit.card()
        table.addView(metricRow("统计条数", p.messageCount.toString() + " 条"))
        table.addView(metricRow("常见句长", "中位 " + p.medianLength + " 字 · 90 分位 " +
            p.p90Length + " 字"))
        table.addView(metricRow("问句率", percent(p.questionRate)))
        table.addView(metricRow("感叹率", percent(p.exclaimRate)))
        table.addView(metricRow("句号率", percent(p.periodRate)))
        table.addView(metricRow("表情率", percent(p.emojiRate)))
        body.addView(table, LinearLayout.LayoutParams(-1, -2))
        // 口径写清楚，用户才能判断这些数字和自己感受的差异是不是定义问题。
        body.addView(kit.caption("问句率、感叹率按含 ? / ？、! / ！ 的消息算；句号率按句末是 。、. 或 … 算；" +
            "表情率按含 emoji 的消息算。").apply { setPadding(0, theme.dp(4), 0, 0) })

        // 语气词和开场字是「反复出现」才会出现的，没有就明说没有，不放空标题。
        body.addView(kit.sectionLabel("常用语气词"))
        if (p.particles.isEmpty()) {
            body.addView(kit.caption("没有反复出现的语气词"))
        } else {
            body.addView(chips(p.particles))
        }
        body.addView(kit.sectionLabel("常用开场字"))
        if (p.openers.isEmpty()) {
            body.addView(kit.caption("开场没有明显的习惯字"))
        } else {
            body.addView(chips(p.openers))
        }

        body.addView(kit.sectionLabel("你的原话示例"))
        if (p.samples.isEmpty()) {
            body.addView(kit.caption("没有合适的短句可以举例"))
        } else {
            p.samples.take(3).forEach { sample ->
                body.addView(theme.label("· " + sample, ReplyMetrics.TEXT_BODY, theme.muted).apply {
                    setPadding(0, theme.dp(2), 0, 0)
                    // 极窄窗口里再折一次，末尾必须有省略号，不能半句消失。
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
            }
        }
        body.addView(kit.caption("示例是超过 24 字的句子截短来的；只在这个窗口里显示，不写盘，也不进日志。")
            .apply { setPadding(0, theme.dp(8), 0, 0) })
    }

    private fun metricRow(name: String, value: String): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, theme.dp(4), 0, theme.dp(4))
        addView(theme.label(name, ReplyMetrics.TEXT_BODY, theme.muted), LinearLayout.LayoutParams(0, -2, 1f))
        addView(theme.label(value, ReplyMetrics.TEXT_BODY, theme.ink, bold = true))
    }

    /** 语气词、开场字用浅底胶囊：比一串逗号分隔的正文更容易一眼扫完。 */
    private fun chips(words: List<String>): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        words.forEach { word ->
            addView(theme.label(word, ReplyMetrics.TEXT_ITEM, theme.accent, bold = true).apply {
                setPadding(theme.dp(10), theme.dp(4), theme.dp(10), theme.dp(4))
                background = theme.shape(theme.soft, ReplyMetrics.RADIUS_BUTTON, theme.border)
            }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = theme.dp(6) })
        }
    }

    /** summary() 是给模型的一整段，按句号拆成条目；末尾拆出的空串不要显示成空行。 */
    private fun summaryLines(summary: String): List<String> =
        summary.split("。").map { it.trim() }.filter { it.isNotEmpty() }

    private fun percent(value: Double): String = "%.1f%%".format(java.util.Locale.CHINA, value * 100)

    /** 复制的是纯文本摘要 + 数字，和窗口里显示的内容一致，不含聊天原文以外的任何东西。 */
    private fun copySummary() {
        val data = profile ?: return
        val text = buildString {
            appendLine("言外 · 说话习惯（这个聊天，最近最多 200 条我发的文字）")
            appendLine(data.summary())
            appendLine("统计条数：" + data.messageCount + " 条")
            appendLine("常见句长：中位 " + data.medianLength + " 字 · 90 分位 " + data.p90Length + " 字")
            appendLine("问句率：" + percent(data.questionRate))
            appendLine("感叹率：" + percent(data.exclaimRate))
            appendLine("句号率：" + percent(data.periodRate))
            appendLine("表情率：" + percent(data.emojiRate))
            if (data.particles.isNotEmpty()) appendLine("常用语气词：" + data.particles.joinToString("、"))
            if (data.openers.isNotEmpty()) appendLine("常用开场字：" + data.openers.joinToString("、"))
            if (data.samples.isNotEmpty()) {
                appendLine("原话示例：")
                data.samples.take(3).forEach { appendLine("· " + it) }
            }
        }.trim()
        (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("言外说话习惯", text))
        Toast.makeText(activity, "已复制", Toast.LENGTH_SHORT).show()
    }
}
