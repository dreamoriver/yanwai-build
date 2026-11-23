package dev.jev.wechatmood.hook

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import dev.jev.wechatmood.core.MoodLog
import dev.jev.wechatmood.core.RelationFactBridge
import dev.jev.wechatmood.reply.FactTimes
import dev.jev.wechatmood.reply.RelationFact
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 关系总览：全部会话的待办放进同一个窗口。
 *
 * 以前档案、待办都只能在对应聊天里打开，想知道「这几天要处理谁的事」得一个人一个人点。
 * 这里只读本机库、不请求模型，按到期时间从早到晚排；点一条直接进那个聊天。
 */
internal class RelationOverviewUi(private val activity: Activity) {
    private val kit = PanelKit(activity)
    private val theme = kit.theme
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val listContainer = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private var dialog: Dialog? = null

    fun show() {
        val dialog = Dialog(activity)
        this.dialog = dialog
        val root = kit.root()
        val column = kit.content(root)
        column.addView(kit.heading("关系总览"))
        column.addView(kit.caption("所有会话的待办放在一起，按到期时间排；点一条进对应的聊天").apply {
            setPadding(0, theme.dp(4), 0, theme.dp(8))
        })
        val scroll = PanelMaxScroll(activity, (activity.resources.displayMetrics.heightPixels * 0.62f).toInt()).apply {
            addView(listContainer)
        }
        column.addView(scroll, LinearLayout.LayoutParams(-1, -2).apply { topMargin = theme.dp(6) })
        column.addView(theme.action("关闭", primary = true) { dialog.dismiss() },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = theme.dp(10) })

        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setGravity(Gravity.BOTTOM)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(ReplyMetrics.DIM)
        }
        // show 之前就定好宽度：放到 onShow 里会先按内容宽度露半屏，再跳成全宽。
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.show()

        listContainer.addView(kit.body("正在汇总…"))
        scope.launch { load() }
        dialog.setOnDismissListener { scope.cancel() }
    }

    private suspend fun load() {
        val now = System.currentTimeMillis()
        // 到期条目与联系人目录都只读本地库；读不到就当空，不用异常打断整个窗口。
        val timed = withContext(Dispatchers.IO) { RelationFactBridge.timedOpen(activity) }
        val contacts = withContext(Dispatchers.IO) { RelationFactBridge.contacts(activity, 500) }
        val rows = timed.flatMap { (key, facts) -> facts.map { key to it } }
            .filter { it.second.occurredAt > 0 }
            .sortedBy { it.second.occurredAt }
        val names = contacts.associate { it.contactKey to it.name.ifBlank { "未命名会话" } }
        val talkers = contacts.associate { it.contactKey to it.talker }
        MoodLog.i("RELATION_OVERVIEW_LOAD contacts=" + contacts.size + " timed=" + rows.size)
        if (activity.isFinishing || activity.isDestroyed) return
        render(rows, names, talkers, now)
    }

    private fun render(rows: List<Pair<String, RelationFact>>, names: Map<String, String>,
        talkers: Map<String, String>, now: Long) {
        listContainer.removeAllViews()
        if (rows.isEmpty()) {
            listContainer.addView(kit.body("还没有带时间的约定"))
            listContainer.addView(kit.caption("在聊天里整理一次档案，或者手动加一条带时间的待办，这里就会汇总").apply {
                setPadding(0, theme.dp(4), 0, 0)
            })
            return
        }
        val overdue = rows.filter { FactTimes.overdue(it.second.occurredAt, now) }
        val today = rows.filter { !FactTimes.overdue(it.second.occurredAt, now) && FactTimes.sameDay(it.second.occurredAt, now) }
        val tomorrow = rows.filter { bucketOf(it.second.occurredAt, now) == 2 }
        val later = rows.filter { bucketOf(it.second.occurredAt, now) == 3 }
        val head = buildString {
            append("共 ").append(rows.size).append(" 条")
            if (overdue.isNotEmpty()) append(" · 逾期 ").append(overdue.size)
            if (today.isNotEmpty()) append(" · 今天 ").append(today.size)
        }
        listContainer.addView(kit.body(head))
        section("已过期 · " + overdue.size + " 条", overdue, names, talkers, now, 0)
        section("今天 · " + today.size + " 条", today, names, talkers, now, 1)
        section("明天 · " + tomorrow.size + " 条", tomorrow, names, talkers, now, 2)
        section("之后 · " + later.size + " 条", later, names, talkers, now, 3)
    }

    private fun section(title: String, rows: List<Pair<String, RelationFact>>, names: Map<String, String>,
        talkers: Map<String, String>, now: Long, bucket: Int) {
        if (rows.isEmpty()) return
        listContainer.addView(kit.sectionLabel(title))
        // 逾期和之后都从早到晚：逾期先看最早的，之后的按时间递增。
        val ordered = if (bucket == 0) rows.sortedBy { it.second.occurredAt } else rows
        ordered.forEach { (key, fact) ->
            val name = names[key] ?: "未命名会话"
            val whenText = if (bucket == 0) "已过 · " + FactTimes.label(fact.occurredAt, now)
                else FactTimes.label(fact.occurredAt, now)
            val row = kit.listRow(fact.text, name + " · " + whenText, arrow = true) {
                openChat(talkers[key])
            }
            listContainer.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = theme.dp(2) })
        }
    }

    /** 0 已过期、1 今天、2 明天、3 更晚。只到日的条目当天结束前不算过期。 */
    private fun bucketOf(at: Long, now: Long): Int = when {
        FactTimes.overdue(at, now) -> 0
        FactTimes.sameDay(at, now) -> 1
        FactTimes.startOfDay(at) == FactTimes.startOfDay(now) + 86_400_000L -> 2
        else -> 3
    }

    /**
     * 打开对应会话。这版微信的 LauncherUI 不认带 Chat_User 的 extra，
     * 点了只会回到首页；ChattingUI 才是真正的聊天页入口。
     */
    private fun openChat(talker: String?) {
        val target = talker?.takeIf { it.matches(TALKER) }
        if (target == null) {
            // 目录里没记下这个哈希键对应谁：点了没反应最难受，明确说一句。
            MoodLog.w("RELATION_OVERVIEW_OPEN no_talker")
            kit.info("打不开这个聊天", "档案里还没记下它对应哪个会话；重新进一次那个聊天就会补上。")
            return
        }
        // 先在对话框里点走：聊天页会在同一个 Activity 之上打开，留着面板会挡在它上面。
        dialog?.dismiss()
        val intent = Intent().apply {
            setClassName("com.tencent.mm", "com.tencent.mm.ui.chatting.ChattingUI")
            putExtra("Chat_User", target)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val opened = runCatching { activity.startActivity(intent); true }.getOrDefault(false)
        MoodLog.i("RELATION_OVERVIEW_OPEN talker=" + target.take(6) + " ok=" + opened)
    }

    private companion object {
        val TALKER = Regex("[A-Za-z0-9_@.\\-]{1,256}")
    }
}
