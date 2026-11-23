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
import dev.jev.wechatmood.reply.FactTimes
import dev.jev.wechatmood.reply.RelationFact
import dev.jev.wechatmood.reply.ReplyContactKey
import dev.jev.wechatmood.reply.StoredDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 关系时间线：把档案、历史纪要、情绪日聚合按时间合成一条。
 *
 * 原来这四样分在四个窗口，回看一个人要来回切；这里只排序和展示，不请求模型。
 * 数据全部来自本机库，读不到就说读不到，不当成「这个人没有历史」。
 */
internal class RelationTimelineUi(private val activity: Activity, private val talker: String?) {
    private val kit = PanelKit(activity)
    private val theme = kit.theme
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val listContainer = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

    private data class Entry(val at: Long, val label: String, val text: String)

    fun show() {
        val dialog = Dialog(activity)
        val root = kit.root()
        val column = kit.content(root)
        column.addView(kit.heading("关系时间线"))
        column.addView(kit.caption("档案、纪要和情绪按时间排在一起，只读本机记录").apply {
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
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.show()

        listContainer.addView(kit.body("正在读取…"))
        scope.launch { load() }
        dialog.setOnDismissListener { scope.cancel() }
    }

    private suspend fun load() {
        // 账号只能从当前聊天页反查：库里的 key 是按账号哈希隔离的，拿不到账号就不猜。
        val live = runCatching { MessageSniffer.replyContext(requireBottom = false) }.getOrNull()
        // 面板是按打开时的聊天记的 talker；切走聊天后页面上的 live 已经是别人，别跟着换。
        val selected = talker?.takeIf { it.isNotBlank() } ?: live?.talker
        if (live == null || selected == null) {
            render(listOf(), "先回到聊天页再打开时间线")
            return
        }
        val account = withContext(Dispatchers.IO) { runCatching { ReplyDatabaseHistory.replyAccount(live) }.getOrNull() }
        val key = ReplyContactKey.of(account, selected, allowGroup = true)
        if (key == null) {
            render(listOf(), "没能确认是哪个账号的聊天，先回到聊天页等一会儿")
            return
        }
        // 读库没兜底会把异常抛到主线程：微信进程直接崩，窗口还停在「正在读取」。
        val facts = runCatching {
            withContext(Dispatchers.IO) { RelationFactBridge.loadAll(activity, key) }
        }.getOrElse { error ->
            MoodLog.w("RELATION_TIMELINE_LOAD_FAILED " + error.javaClass.simpleName)
            render(listOf(), "读取档案失败，稍后重试")
            return
        }
        val digests = withContext(Dispatchers.IO) { RelationFactBridge.digests(activity, key) }
        val session = dev.jev.wechatmood.reply.EmotionTrend.sessionOf(selected)
        val emotion = if (session == null) emptyList() else withContext(Dispatchers.IO) {
            RelationFactBridge.emotionDays(activity, session, 90)
        }
        val entries = merge(facts, digests, emotion)
        MoodLog.i("RELATION_TIMELINE_LOAD facts=" + facts.size + " digests=" + (digests?.size ?: -1) +
            " emotionDays=" + emotion.size + " entries=" + entries.size)
        if (activity.isFinishing || activity.isDestroyed) return
        // 纪要读失败（null）不能和「一条都没有」混成空态。
        render(entries, if (entries.isEmpty() && digests == null) "读取纪要失败，稍后重试" else null)
    }

    private fun merge(facts: List<RelationFact>, digests: List<StoredDigest>?, emotion: List<EmotionDay>): List<Entry> {
        val entries = ArrayList<Entry>()
        facts.forEach { fact ->
            val at = when {
                fact.occurredAt > 0 -> fact.occurredAt
                fact.evidenceTime > 0 -> fact.evidenceTime
                else -> fact.updatedAt
            }
            if (at <= 0) return@forEach
            val status = when (fact.status) {
                RelationFact.STATUS_DONE -> " · 已完成"
                RelationFact.STATUS_CANCELLED -> " · 已移出"
                RelationFact.STATUS_SUPERSEDED -> " · 已作废"
                else -> ""
            }
            entries += Entry(at, fact.kind + status, fact.text)
        }
        digests.orEmpty().forEach { digest ->
            if (digest.createdAt <= 0) return@forEach
            entries += Entry(digest.createdAt, "纪要", oneLine(digest.text))
        }
        // 情绪按天合并成一条：同一天可能有多个标签和多次判定。
        emotion.filter { it.speaker != "我" }.groupBy { it.dayKey }.forEach { (day, rows) ->
            val at = FactTimes.parseLocal(day)
            if (at <= 0) return@forEach
            val summary = rows.groupBy { it.label }.map { (label, list) -> label + " " + list.sumOf { it.count } + " 次" }
            entries += Entry(at, "情绪", summary.joinToString(" · "))
        }
        return entries.sortedByDescending { it.at }.take(MAX_ENTRIES)
    }

    private fun render(entries: List<Entry>, emptyHint: String?) {
        listContainer.removeAllViews()
        if (entries.isEmpty()) {
            listContainer.addView(kit.body(emptyHint ?: "还没有可回看的内容"))
            if (emptyHint == null) {
                listContainer.addView(kit.caption("整理一次档案、转述一次聊天或产生情绪判定后，这里就会按时间排起来").apply {
                    setPadding(0, theme.dp(4), 0, 0)
                })
            }
            return
        }
        val now = System.currentTimeMillis()
        var lastDay = ""
        entries.forEach { entry ->
            val day = dayLabel(entry.at, now)
            if (day != lastDay) {
                if (lastDay.isNotEmpty()) {
                    listContainer.addView(kit.divider(), LinearLayout.LayoutParams(-1, theme.dp(1).coerceAtLeast(1)).apply {
                        topMargin = theme.dp(6)
                    })
                }
                listContainer.addView(kit.sectionLabel(day))
                lastDay = day
            }
            val time = if (FactTimes.hasClock(entry.at)) FactTimes.formatLocal(entry.at, "HH:mm") else "全天"
            val row = kit.listRow(entry.text, entry.label + " · " + time, arrow = false) { }
            listContainer.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = theme.dp(2) })
        }
        listContainer.addView(kit.caption("共 " + entries.size + " 条，最近 " + MAX_ENTRIES + " 条以内；只读本机记录").apply {
            setPadding(0, theme.dp(10), 0, 0)
        })
    }

    private fun dayLabel(at: Long, now: Long): String = when {
        FactTimes.sameDay(at, now) -> "今天"
        FactTimes.startOfDay(at) == FactTimes.startOfDay(now) - 86_400_000L -> "昨天"
        else -> FactTimes.formatLocal(at, "MM-dd")
    }

    private fun oneLine(text: String): String =
        text.replace('\n', ' ').replace(Regex("\\s+"), " ").trim().take(120)

    private companion object {
        const val MAX_ENTRIES = 120
    }
}
