package dev.jev.wechatmood.hook

import android.app.Activity
import android.app.Dialog
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import dev.jev.wechatmood.core.MoodLog
import dev.jev.wechatmood.core.RelationFactBridge
import dev.jev.wechatmood.reply.EmotionDay
import dev.jev.wechatmood.reply.EmotionTrend
import dev.jev.wechatmood.reply.FactTimes
import dev.jev.wechatmood.reply.RelationTemperature
import dev.jev.wechatmood.reply.TemperatureContact
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 关系温度。
 *
 * 默认只讲这个聊天：约定悬着几条、本周几件、近期情绪。
 * 「久未联系 / 全部会话」是跨会话的东西，收在底部折叠里——在某个聊天的弹窗里
 * 一上来就摆一个不相关的人名，会让人以为那是这个聊天的事。
 */
internal class RelationTemperatureUi(private val activity: Activity, private val talker: String?) {
    private val kit = PanelKit(activity)
    private val theme = kit.theme
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val listContainer = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private var items: List<TemperatureContact> = emptyList()
    private var emotionRows: List<EmotionDay> = emptyList()
    private var currentTalker: String? = null
    private var now: Long = 0
    private var expanded = false

    fun show() {
        val dialog = Dialog(activity)
        val root = kit.root()
        val column = kit.content(root)
        column.addView(kit.heading("关系温度"))
        column.addView(kit.caption("先看这个聊天；跨会话的概况收在底部").apply {
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
        // show 之前就定好宽度：放到 onShow 里设置会先按内容宽度露半屏，再跳成全宽。
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.show()

        listContainer.addView(kit.body("正在汇总…"))
        scope.launch { load() }
        dialog.setOnDismissListener { scope.cancel() }
    }

    private suspend fun load() {
        val now = System.currentTimeMillis()
        val rows = withContext(Dispatchers.IO) { RelationFactBridge.contacts(activity) }
        val timed = withContext(Dispatchers.IO) { RelationFactBridge.timedOpen(activity) }
        // 当前聊天取页面上的实际会话：抽屉里缓存的 talker 可能在切换聊天后落后一拍。
        val live = runCatching { MessageSniffer.replyContext(requireBottom = false) }.getOrNull()
        val current = live?.talker ?: talker
        // pendingAccountScope() 是 "pending:<uuid>" 这种临时值，不是库的账号哈希，
        // 拿它过滤数据库只会得到空表。真账号只能从当前聊天的库反查。
        val accountScope = withContext(Dispatchers.IO) {
            live?.let { page -> runCatching { ReplyDatabaseHistory.replyAccount(page) }.getOrNull() }
        }
        val last = withContext(Dispatchers.IO) {
            if (accountScope == null) emptyMap() else runCatching {
                ReplyDatabaseHistory.lastMessageTimes(rows.map { it.talker }, accountScope)
            }.getOrDefault(emptyMap())
        }
        val mapped = rows.map { row ->
            val facts = timed[row.contactKey].orEmpty()
            TemperatureContact(
                key = row.contactKey,
                talker = row.talker,
                name = row.name.ifBlank { "未命名会话" },
                lastAt = last[row.talker] ?: 0,
                openTodos = facts.size,
                weekTodos = facts.count { RelationTemperature.inWeek(it.occurredAt, now) },
                overdue = facts.count { FactTimes.overdue(it.occurredAt, now) },
            )
        }
        val session = EmotionTrend.sessionOf(current)
        val emotion = if (session == null) emptyList() else withContext(Dispatchers.IO) {
            RelationFactBridge.emotionDays(activity, session, 30)
        }
        MoodLog.i("RELATION_TEMP_LOAD contacts=" + rows.size + " current=" + (current != null) +
            " quiet=" + mapped.count { RelationTemperature.isQuiet(it.lastAt, now) } +
            " open=" + mapped.count { it.openTodos > 0 })
        this.items = mapped
        this.emotionRows = emotion.filter { it.speaker != "我" }
        this.currentTalker = current
        this.now = now
        render()
    }

    private fun render() {
        listContainer.removeAllViews()
        val current = items.firstOrNull { it.talker == currentTalker }
        if (current == null) {
            listContainer.addView(kit.body("还没有这个聊天的档案"))
            listContainer.addView(kit.caption("打开一次「帮我回」，或在下面的附加里整理一次，这里才有东西可算"))
        } else {
            listContainer.addView(kit.sectionLabel("这个聊天 · " + current.name))
            listContainer.addView(thisRow(current))
        }
        renderEmotion()
        renderGlobal()
    }

    /** 当前聊天的一行：约定、本周、最后聊过多久。 */
    private fun thisRow(item: TemperatureContact) = kit.card().apply {
        val parts = mutableListOf<String>()
        parts += if (item.openTodos > 0) item.openTodos.toString() + " 条约定没落地" else "没有悬着的约定"
        if (item.overdue > 0) parts += item.overdue.toString() + " 条已过期"
        if (item.weekTodos > 0) parts += "本周 " + item.weekTodos + " 条"
        addView(theme.label(parts.joinToString(" · "), ReplyMetrics.TEXT_BODY, theme.ink))
        addView(theme.label(RelationTemperature.quietText(item.lastAt, now), ReplyMetrics.TEXT_CAPTION, theme.muted)
            .apply { setPadding(0, theme.dp(3), 0, 0) })
    }

    /** 当前聊天的近期情绪；30 天里没有判定就整块不显示，不放空标题。 */
    private fun renderEmotion() {
        if (EmotionTrend.sessionOf(currentTalker) == null) return
        val summary = EmotionTrend.summarize(emotionRows).take(4)
        if (summary.isEmpty()) return
        listContainer.addView(kit.sectionLabel("近期情绪（30 天）"))
        listContainer.addView(kit.body(summary.joinToString(" · ") { it.label + " " + it.count + " 次" }))
        listContainer.addView(kit.caption("共 " + emotionRows.sumOf { it.count } + " 次判定；只记标签和次数，不存聊天原文"))
    }

    /** 跨会话概况：默认只给一行数字，点开才列名字。 */
    private fun renderGlobal() {
        val quiet = RelationTemperature.sorted(items.filter { RelationTemperature.isQuiet(it.lastAt, now) }, now)
        val hanging = items.filter { it.openTodos > 0 }
        val week = items.filter { it.weekTodos > 0 }
        listContainer.addView(kit.divider(), LinearLayout.LayoutParams(-1, theme.dp(1).coerceAtLeast(1)).apply {
            topMargin = theme.dp(12)
        })
        listContainer.addView(kit.caption("全部会话（" + items.size + " 个整理过档案的）：" +
            quiet.size + " 人久未联系 · " + hanging.size + " 人还有约定 · 本周 " +
            week.sumOf { it.weekTodos } + " 条").apply { setPadding(0, theme.dp(8), 0, 0) })
        if (!expanded) {
            listContainer.addView(kit.pill("展开看全部会话") { expanded = true; render() },
                LinearLayout.LayoutParams(-2, theme.dp(36)).apply { topMargin = theme.dp(6) })
            return
        }
        listContainer.addView(kit.pill("收起") { expanded = false; render() },
            LinearLayout.LayoutParams(-2, theme.dp(36)).apply { topMargin = theme.dp(6) })
        section("久未联系 " + quiet.size + " 个", quiet) { RelationTemperature.quietText(it.lastAt, now) }
        section("悬着的约定 " + hanging.size + " 人", hanging.take(LIMIT)) { item ->
            val parts = mutableListOf<String>()
            parts += item.openTodos.toString() + " 条未完成"
            if (item.overdue > 0) parts += item.overdue.toString() + " 条已过期"
            if (item.weekTodos > 0) parts += "本周 " + item.weekTodos + " 条"
            parts.joinToString(" · ")
        }
        section("本周要处理 " + week.sumOf { it.weekTodos } + " 条", week) { item ->
            item.weekTodos.toString() + " 条 · " + RelationTemperature.quietText(item.lastAt, now)
        }
        if (quiet.isEmpty() && hanging.isEmpty() && week.isEmpty()) {
            listContainer.addView(kit.caption("没有久未联系的人，也没有悬着的约定"))
        }
    }

    private fun section(title: String, list: List<TemperatureContact>,
        subtitle: (TemperatureContact) -> String) {
        if (list.isEmpty()) return
        listContainer.addView(kit.sectionLabel(title))
        list.forEach { item ->
            val row = kit.listRow(item.name, subtitle(item), arrow = false) { }
            listContainer.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = theme.dp(2) })
        }
    }

    private companion object {
        const val LIMIT = 12
    }
}
