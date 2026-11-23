package dev.jev.wechatmood.hook

import android.app.Activity
import android.app.Dialog
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import dev.jev.wechatmood.core.RelationFactBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「附加」入口弹出的功能面板。
 *
 * 第一层只留三件事：帮我回、统计、关系。档案、待办、温度、总览、时间线收进「关系」二级：
 * 一个弹窗里既有当前聊天又有全部会话，行数一多名字就互相打架，也看不出哪些是全局的。
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
    private var relationBadge: TextView? = null

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
    private fun refreshBadge(layer: CoroutineScope, onCounts: (dev.jev.wechatmood.reply.ChatBadge) -> Unit = {}) {
        val current = talker ?: return
        layer.launch {
            val badge = withContext(Dispatchers.IO) {
                runCatching { RelationFactBridge.badge(activity, current) }.getOrNull()
            } ?: return@launch
            if (activity.isFinishing || activity.isDestroyed) return@launch
            RelationBadge.update(activity, current, badge.due, badge.open, badge.timed)
            onCounts(badge)
        }
    }

    fun show() {
        val dialog = Dialog(activity)
        val layer = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val body = body()
        body.addView(kit.heading("言外"))
        body.addView(kit.caption(
            when {
                talker == null -> "当前聊天尚未识别，部分功能不可用"
                talker.endsWith("@chatroom") -> "群聊 · 功能只在微信里生效"
                else -> "私聊 · 功能只在微信里生效"
            }
        ).apply { setPadding(0, theme.dp(4), 0, theme.dp(12)) })

        val card = card()
        var first = true
        fun row(title: String, subtitle: String, action: () -> Unit, badge: TextView? = null) {
            if (!first) card.addView(kit.divider(), LinearLayout.LayoutParams(-1, theme.dp(1).coerceAtLeast(1)))
            card.addView(kit.listRow(title, subtitle, badgeView = badge) {
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
            row(if (talker.endsWith("@chatroom")) "群聊统计" else "聊天统计",
                "消息量、活跃成员与时段", onStats)
        }
        if (factsAvailable) {
            val badge = badgeView()
            relationBadge = badge
            row("关系", "档案、待办、温度、总览与时间线", { showRelationSheet() }, badge)
        }
        body.addView(card, LinearLayout.LayoutParams(-1, -2))

        body.addView(theme.action("关闭", primary = true) { dialog.dismiss() },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = theme.dp(12) })
        present(dialog, body, layer)
        if (factsAvailable) {
            refreshBadge(layer) { setBadge(relationBadge, it.timed) }
        }
    }

    /**
     * 关系的二级页：上面只放这个聊天，下面单独标出「全部会话」。
     * 第一层不再出现「总览」「时间线」这些全局入口，名字和当前聊天混在一起看不清。
     */
    private fun showRelationSheet() {
        val current = talker ?: return
        val group = current.endsWith("@chatroom")
        val dialog = Dialog(activity)
        val layer = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val body = body()
        body.addView(kit.heading("关系"))
        body.addView(kit.caption("先在「这个聊天」里找，跨会话的放在下面").apply {
            setPadding(0, theme.dp(4), 0, theme.dp(10))
        })

        val card = card()
        var first = true
        fun row(title: String, subtitle: String, action: () -> Unit, badge: TextView? = null) {
            if (!first) card.addView(kit.divider(), LinearLayout.LayoutParams(-1, theme.dp(1).coerceAtLeast(1)))
            card.addView(kit.listRow(title, subtitle, badgeView = badge) {
                dialog.dismiss()
                action()
            }, LinearLayout.LayoutParams(-1, -2))
            first = false
        }

        body.addView(kit.sectionLabel("这个聊天"))
        val factsBadge = badgeView()
        val todosBadge = badgeView()
        row(if (group) "群聊纪要" else "关系档案", "整理过的事实，可移出或补一条", onFacts, factsBadge)
        row(if (group) "群待办" else "待办", "带时间的约定与重要日期", onTodos, todosBadge)
        row("关系温度", "久未联系、悬着的约定与本周要处理", onTemperature)
        row("历史纪要", "存下来的 AI 转述，可回看或转成待办", onDigests)
        row("关系时间线", "这个聊天的档案、纪要与情绪按时间排", { RelationTimelineUi(activity, current).show() })
        body.addView(card, LinearLayout.LayoutParams(-1, -2))

        body.addView(kit.sectionLabel("全部会话"))
        val global = card()
        var firstGlobal = true
        fun globalRow(title: String, subtitle: String, action: () -> Unit) {
            if (!firstGlobal) global.addView(kit.divider(), LinearLayout.LayoutParams(-1, theme.dp(1).coerceAtLeast(1)))
            global.addView(kit.listRow(title, subtitle) {
                dialog.dismiss()
                action()
            }, LinearLayout.LayoutParams(-1, -2))
            firstGlobal = false
        }
        globalRow("关系总览", "所有会话的待办按到期时间排", { RelationOverviewUi(activity).show() })
        body.addView(global, LinearLayout.LayoutParams(-1, -2))

        body.addView(theme.action("关闭", primary = true) { dialog.dismiss() },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = theme.dp(12) })
        present(dialog, body, layer)
        refreshBadge(layer) { badge ->
            setBadge(factsBadge, badge.open)
            setBadge(todosBadge, badge.timed)
        }
    }

    private fun body(): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(theme.dp(20), theme.dp(16), theme.dp(20), theme.dp(20))
        background = theme.shape(theme.surface, ReplyMetrics.RADIUS_PANEL, theme.border)
    }

    private fun card(): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        background = theme.shape(theme.card, ReplyMetrics.RADIUS_CARD, theme.border)
    }

    private fun present(dialog: Dialog, body: LinearLayout, layer: CoroutineScope) {
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
        dialog.setOnDismissListener { layer.cancel() }
        dialog.show()
    }
}
