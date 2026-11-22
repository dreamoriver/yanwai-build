package dev.jev.wechatmood.hook

import android.app.Activity
import android.app.Dialog
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import dev.jev.wechatmood.core.RelationFactBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private val onReply: () -> Unit,
    private val onStats: () -> Unit,
    private val onFacts: () -> Unit,
    private val onTodos: () -> Unit,
    private val onDigests: () -> Unit,
    private val onTemperature: () -> Unit,
) {
    private val kit = PanelKit(activity)
    private val theme = kit.theme
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var factsBadge: TextView? = null
    private var todosBadge: TextView? = null

    private fun badgeView(): TextView = theme.label("", ReplyMetrics.TEXT_CAPTION, theme.accent, bold = true).apply {
        setPadding(theme.dp(7), theme.dp(2), theme.dp(7), theme.dp(2))
        background = theme.shape(theme.surface, ReplyMetrics.RADIUS_BUTTON, theme.accent)
        visibility = android.view.View.GONE
    }

    private fun setBadge(view: TextView?, value: Int) {
        if (view == null) return
        if (value <= 0) {
            view.visibility = android.view.View.GONE
        } else {
            view.text = value.toString()
            view.visibility = android.view.View.VISIBLE
        }
    }

    /** 缓存是上一次进聊天写的；打开面板时再读一次本地库，避免要开关几次才出数字。 */
    private fun refreshCounts() {
        val current = talker ?: return
        scope.launch {
            val badge = withContext(Dispatchers.IO) {
                runCatching { RelationFactBridge.badge(activity, current) }.getOrNull()
            } ?: return@launch
            if (activity.isFinishing || activity.isDestroyed) return@launch
            RelationBadge.update(activity, current, badge.due, badge.open, badge.timed)
            setBadge(factsBadge, badge.open)
            setBadge(todosBadge, badge.timed)
        }
    }

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
        // 面板一打开就告诉用户「为什么会有提醒」：档案里总共几条待办、今天要处理几条。
        val counts = RelationBadge.counts(activity, talker)
        fun row(title: String, subtitle: String, action: () -> Unit, badgeView: TextView? = null) {
            if (!first) group.addView(kit.divider(), LinearLayout.LayoutParams(-1, theme.dp(1).coerceAtLeast(1)))
            group.addView(kit.listRow(title, subtitle, badgeView = badgeView) {
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
            factsBadge = badgeView()
            todosBadge = badgeView()
            setBadge(factsBadge, counts.open)
            setBadge(todosBadge, counts.timed)
            if (talker?.endsWith("@chatroom") == true) {
                row("群聊纪要", "群里的约定、时间地点和提到我的事", onFacts, factsBadge)
                row("群待办", "群里带时间的约定与重要日期", onTodos, todosBadge)
            } else {
                row("关系档案", "整理过的事实，可移出或补一条", onFacts, factsBadge)
                row("待办", "带时间的约定与重要日期", onTodos, todosBadge)
            }
            row("关系温度", "久未联系、悬着的约定与本周要处理", onTemperature)
            row("历史纪要", "存下来的 AI 转述，可回看或转成待办", onDigests)
        }
        body.addView(group, LinearLayout.LayoutParams(-1, -2))

        body.addView(kit.divider(), LinearLayout.LayoutParams(-1, theme.dp(1).coerceAtLeast(1)).apply {
            topMargin = theme.dp(14)
        })
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
        dialog.setOnDismissListener { scope.cancel() }
        dialog.show()
        refreshCounts()
    }
}
