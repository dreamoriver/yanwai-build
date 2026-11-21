package dev.jev.wechatmood.hook

import android.app.Activity
import android.app.Dialog
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.Toast

/**
 * 「附加」入口弹出的功能面板：帮回、统计、档案、待办，最后是自动分析开关。
 *
 * 「找话题」不单列一行：它本来就在「帮我回」抽屉里，同一个抽屉开两次反而让人以为有两套逻辑。
 *
 * 一块卡片装功能行、行间用细线分隔，不再每项各占一个圆角卡片：
 * 那样项一多就碎，和微信自己的列表也不像。
 */
internal class FeaturePanel(
    private val activity: Activity,
    private val talker: String?,
    private val replyAvailable: Boolean,
    private val factsAvailable: Boolean,
    private val analysisEnabled: Boolean,
    private val onReply: () -> Unit,
    private val onStats: () -> Unit,
    private val onFacts: () -> Unit,
    private val onTodos: () -> Unit,
    /** 返回是否真的保存成功；false 时开关会拨回原位。 */
    private val onAnalysisChanged: (Boolean) -> Boolean,
) {
    private val kit = PanelKit(activity)
    private val theme = kit.theme

    fun show() {
        val dialog = Dialog(activity)
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(theme.dp(20), theme.dp(16), theme.dp(20), theme.dp(20))
            background = theme.shape(theme.surface, ReplyMetrics.RADIUS_PANEL, theme.border)
        }

        body.addView(kit.heading("言外"))
        body.addView(kit.caption(
            when {
                talker == null -> "当前聊天尚未识别，部分功能不可用"
                talker.endsWith("@chatroom") -> "群聊 · 功能只在微信里生效"
                else -> "私聊 · 功能只在微信里生效"
            }
        ).apply { setPadding(0, theme.dp(4), 0, theme.dp(12)) })

        // 功能行放在同一张卡里，行间一条 1dp 分隔线；卡片本身不加内边距，交给每一行。
        val group = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = theme.shape(theme.card, ReplyMetrics.RADIUS_CARD, theme.border)
        }
        var first = true
        fun row(title: String, subtitle: String, action: () -> Unit) {
            if (!first) group.addView(kit.divider(), LinearLayout.LayoutParams(-1, theme.dp(1).coerceAtLeast(1)))
            group.addView(kit.listRow(title, subtitle) {
                dialog.dismiss()
                action()
            }, LinearLayout.LayoutParams(-1, -2))
            first = false
        }
        if (replyAvailable) {
            // 找话题不另开一行：抽屉里本来就有「找找话题」按钮，两个入口进同一个地方只会让人犯嘀咕。
            row("帮我回", "生成 3–5 条候选，找话题也在里面", onReply)
        }
        if (talker != null) {
            row(if (talker?.endsWith("@chatroom") == true) "群聊统计" else "聊天统计",
                "消息量、活跃成员与时段", onStats)
        }
        if (factsAvailable) {
            row("关系档案", "整理过的事实，可移出或补一条", onFacts)
            row("待办", "带时间的约定与重要日期", onTodos)
        }
        body.addView(group, LinearLayout.LayoutParams(-1, -2))

        body.addView(kit.divider(), LinearLayout.LayoutParams(-1, theme.dp(1).coerceAtLeast(1)).apply {
            topMargin = theme.dp(14)
        })
        var syncing = false
        val switch = Switch(activity).apply {
            isChecked = analysisEnabled
            text = "自动分析"
            textSize = ReplyMetrics.TEXT_BODY
            setTextColor(theme.ink)
            // 没识别到聊天时没有「这个会话」可存，开关不该能拨。
            isEnabled = talker != null
            minimumHeight = theme.dp(ReplyMetrics.TAP)
            setPadding(0, theme.dp(10), 0, theme.dp(4))
            setOnCheckedChangeListener { button, value ->
                if (syncing) return@setOnCheckedChangeListener
                if (!onAnalysisChanged(value)) {
                    // 保存失败就把开关拨回去，别让人以为已经关了。
                    syncing = true
                    button.isChecked = !value
                    syncing = false
                    Toast.makeText(activity, "开关没保存，重新进一次聊天再试", Toast.LENGTH_SHORT).show()
                }
            }
        }
        body.addView(switch, LinearLayout.LayoutParams(-1, -2))
        body.addView(kit.caption(
            if (talker == null) "没识别到当前聊天，开关先不可用"
            else "开启后，收到的文字与语音会在气泡下方显示情绪与解读"
        ))

        dialog.setContentView(body)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setGravity(Gravity.BOTTOM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(ReplyMetrics.DIM)
        }
        dialog.setOnShowListener {
            dialog.window?.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
    }
}
