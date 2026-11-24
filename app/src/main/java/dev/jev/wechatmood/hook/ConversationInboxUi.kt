package dev.jev.wechatmood.hook

import android.app.Activity
import android.app.Dialog
import android.view.ViewGroup
import android.widget.LinearLayout
import dev.jev.wechatmood.core.MoodLog
import dev.jev.wechatmood.reply.PendingReply
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 待回消息：对方最后一条超过阈值还没接的会话。
 *
 * 为什么不做成「未读」：未读清零不代表回了，很多时候是点开看了一眼又退出；
 * 反过来，把红点清了但没接话的会话才最容易漏。这里判的是「最后一条是对方发的」，
 * 不碰已读状态。
 *
 * 只读本机聊天库，不发模型请求，也不改任何数据；点一行就是让微信自己打开那个会话。
 */
internal class ConversationInboxUi(
    private val activity: Activity,
    private val accountScope: String?,
    private val minHours: Int = DEFAULT_HOURS,
) {
    private val kit = PanelKit(activity)
    private val theme = kit.theme
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val listContainer = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private var dialog: Dialog? = null
    private var status: android.widget.TextView? = null

    fun show() {
        val dialog = kit.sheet()
        this.dialog = dialog
        val root = kit.root()
        val column = kit.content(root)
        column.addView(kit.heading("待回消息"))
        column.addView(kit.caption("对方最后一条超过 $minHours 小时还没接的会话；只读本机聊天库，不发请求").apply {
            setPadding(0, theme.dp(4), 0, theme.dp(8))
        })
        val scroll = PanelMaxScroll(activity,
            (activity.resources.displayMetrics.heightPixels * 0.62f).toInt()).apply {
            addView(listContainer)
        }
        column.addView(scroll, LinearLayout.LayoutParams(-1, -2).apply { topMargin = theme.dp(4) })
        val line = kit.status("正在读本机聊天库…")
        status = line
        column.addView(line)
        column.addView(theme.action("关闭", primary = true) { dialog.dismiss() },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = theme.dp(10) })

        dialog.setContentView(root)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.setOnDismissListener { scope.cancel() }
        dialog.show()
        load()
    }

    private fun load() {
        val label = status ?: return
        label.text = "正在读本机聊天库…"
        label.setTextColor(theme.muted)
        scope.launch {
            val rows = runCatching { ReplyDatabaseHistory.pendingReplies(minHours, 40, accountScope) }
                .onFailure { MoodLog.w("REPLY_INBOX_FAIL " + it.javaClass.simpleName) }
                .getOrNull()
            if (activity.isFinishing || activity.isDestroyed) return@launch
            if (rows == null) {
                // null 是「库没读通」，不是「没有待回」；这两句话不能混着说。
                label.text = "本机聊天库暂时读不到。先打开一次微信聊天再回来，这里不是「没有待回消息」。"
                label.setTextColor(theme.error)
                return@launch
            }
            val names = withContext(Dispatchers.IO) { namesOf(rows.map { it.talker }) }
            render(rows, names)
            label.text = if (rows.isEmpty()) "" else "共 " + rows.size + " 个会话还没接；按最久没回的排前面"
            label.setTextColor(theme.muted)
        }
    }

    private suspend fun namesOf(talkers: List<String>): Map<String, String> {
        if (talkers.isEmpty()) return emptyMap()
        val direct = runCatching { ReplyDatabaseHistory.contactNames(talkers) }.getOrDefault(emptyMap())
        val groups = runCatching { ReplyDatabaseHistory.groupNames(talkers) }.getOrDefault(emptyMap())
        return direct + groups
    }

    private fun render(rows: List<PendingReply>, names: Map<String, String>) {
        listContainer.removeAllViews()
        if (rows.isEmpty()) {
            listContainer.addView(kit.status("都回过了，眼下没有压着的会话。"))
            return
        }
        val now = System.currentTimeMillis()
        rows.forEachIndexed { index, row ->
            val name = names[row.talker]?.takeIf(String::isNotBlank)
                ?: row.name.takeIf(String::isNotBlank)
                ?: "未知会话"
            val subtitle = buildString {
                append(since(row.lastAt, now))
                append("没回")
                if (row.isGroup) append(" · 群聊")
                if (row.unread > 0) append(" · ").append(row.unread).append(" 条未读")
                append(" · ").append(row.preview)
            }
            listContainer.addView(kit.itemRow(name, subtitle) {
                dialog?.dismiss()
                val opened = RelationTodoActions.openTalker(activity, row.talker)
                MoodLog.i("REPLY_INBOX_OPEN opened=" + opened + " talker=" + row.talker.hashCode())
            }, LinearLayout.LayoutParams(-1, -2).apply {
                if (index > 0) topMargin = theme.dp(6)
            })
        }
    }

    /** 粗略时长：清单只需要「多久没接」，不显示到分钟级。 */
    private fun since(at: Long, now: Long): String {
        val minutes = ((now - at) / 60_000L).coerceAtLeast(1)
        return when {
            minutes < 60 -> minutes.toString() + " 分钟"
            minutes < 24 * 60 -> (minutes / 60).toString() + " 小时"
            minutes < 30 * 24 * 60 -> (minutes / (24 * 60)).toString() + " 天"
            else -> (minutes / (30 * 24 * 60)).toString() + " 个月"
        }
    }

    private companion object {
        /** 6 小时：短于这个数的等待还算正常聊天节奏，催了反而是打扰。 */
        const val DEFAULT_HOURS = 6
    }
}
