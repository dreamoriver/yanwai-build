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
import dev.jev.wechatmood.core.MoodLog
import dev.jev.wechatmood.core.RelationFactBridge
import dev.jev.wechatmood.reply.DigestArchive
import dev.jev.wechatmood.reply.FactTimes
import dev.jev.wechatmood.reply.ReplyContactKey
import dev.jev.wechatmood.reply.StoredDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * 历史纪要：存下来的 AI 转述。
 *
 * 只读本机档案库，不额外请求模型；每条可以复制、转成待办或删除。
 * 库里存的是模型写出的结论，聊天素材不在里面。
 *
 * 汇总（周报）也是纯本地拼装：存下来的结论已经是成品，再让模型「总结一遍」只会
 * 多花一次钱，还可能把原文里没有的因果和情绪编进去。区间汇总只做排序、去重、截断。
 */
internal class DigestHistoryUi(private val activity: Activity, private val talker: String?) {
    private val kit = PanelKit(activity)
    private val theme = kit.theme
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val listContainer = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private var items: List<StoredDigest> = emptyList()
    private var contactKey: ReplyContactKey? = null
    /** 只在读取成功后置位：空列表和「还没读到」是两件事，汇总不能把后者当成空态。 */
    private var loaded = false

    /** 读失败和「还在读」要分开：失败后点周报不该得到「等一秒再点」。 */
    private var loadFailed = false
    /** 会话名查过一次就记住：标题用的，第一次汇总时才去库里找。 */
    private var conversation: String? = null

    fun show() {
        val dialog = Dialog(activity)
        val root = kit.root()
        val column = kit.content(root)
        column.addView(kit.heading("历史纪要"))
        column.addView(kit.caption("存下来的 AI 转述，只有结论，没有聊天原文").apply {
            setPadding(0, theme.dp(4), 0, theme.dp(8))
        })
        // 汇总入口放在列表上方：最常用的「本周」一键直达，其余区间走选择器，不多占一行。
        val reportRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        reportRow.addView(theme.action("本周周报", quiet = true) { openReport(ReportRange.WEEK) },
            LinearLayout.LayoutParams(0, -2, 1f))
        reportRow.addView(theme.action("其他区间汇总…", quiet = true) { pickRange() },
            LinearLayout.LayoutParams(0, -2, 1f))
        column.addView(reportRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = theme.dp(2) })
        val scroll = PanelMaxScroll(activity, (activity.resources.displayMetrics.heightPixels * 0.6f).toInt()).apply {
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
        dialog.setOnDismissListener { scope.cancel() }
        // show 之前就定好宽度：放到 onShow 里设置会先按内容宽度露半屏，再跳成全宽。
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.show()

        val selected = talker
        MoodLog.i("DIGEST_HISTORY_OPEN talker=" + (selected != null))
        if (selected == null) {
            listContainer.addView(kit.body("返回一个聊天再打开"))
            return
        }
        listContainer.addView(kit.body("正在读取…"))
        scope.launch {
            val key = resolveKey(selected)
            if (key == null) {
                listContainer.removeAllViews()
                listContainer.addView(kit.body("没确认是哪个账号的聊天，先回到聊天页等一会儿"))
                return@launch
            }
            contactKey = key
            load(key)
        }
    }

    /** 档案按账号与联系人哈希隔离，确认不了账号就不猜一个 key。 */
    private suspend fun resolveKey(selected: String): ReplyContactKey? {
        val live = runCatching { MessageSniffer.replyContext(requireBottom = false) }.getOrNull() ?: return null
        if (live.talker != selected) return null
        val account = withContext(Dispatchers.IO) { ReplyDatabaseHistory.replyAccount(live) }
        return ReplyContactKey.of(account, selected, allowGroup = true)
    }

    private suspend fun load(key: ReplyContactKey) {
        val loadedItems = withContext(Dispatchers.IO) { RelationFactBridge.digests(activity, key) }
        MoodLog.i("DIGEST_HISTORY_LOAD total=" + (loadedItems?.size ?: -1))
        if (loadedItems == null) {
            // 读失败和「真没有」是两件事：库异常时不能说成空态。
            loadFailed = true
            listContainer.removeAllViews()
            listContainer.addView(kit.body("读取纪要失败，稍后重试"))
            return
        }
        items = loadedItems
        loaded = true
        render()
    }

    private fun render() {
        listContainer.removeAllViews()
        if (items.isEmpty()) {
            listContainer.addView(kit.body("还没有存下来的纪要"))
            listContainer.addView(kit.caption("在聊天统计里点一次 AI 分析，结论会自动存到这里"))
            return
        }
        items.forEach { digest ->
            listContainer.addView(digestRow(digest), LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = theme.dp(8)
            })
        }
    }

    private fun digestRow(digest: StoredDigest): View {
        val card = kit.card()
        card.addView(theme.label(DigestArchive.stamp(digest.createdAt) + " · " + digest.rangeLabel,
            ReplyMetrics.TEXT_CAPTION, theme.muted))
        card.addView(theme.label(digest.text, ReplyMetrics.TEXT_BODY, theme.ink).apply {
            setPadding(0, theme.dp(4), 0, 0)
        })
        card.addView(theme.label("看了 " + digest.material + " 条素材 · 该时段共 " + digest.total + " 条",
            ReplyMetrics.TEXT_CAPTION, theme.muted).apply { setPadding(0, theme.dp(4), 0, 0) })
        card.isClickable = true
        card.isFocusable = true
        card.contentDescription = "纪要，" + digest.rangeLabel
        card.setOnClickListener {
            kit.picker("这条纪要", listOf("复制结论", "转成待办", "删除")) { which ->
                when (which) {
                    0 -> copy(digest)
                    1 -> toTodo(digest)
                    2 -> delete(digest)
                }
            }
        }
        return card
    }

    private fun copy(digest: StoredDigest) {
        (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("聊天纪要", digest.text))
        Toast.makeText(activity, "已复制", Toast.LENGTH_SHORT).show()
    }

    /** 转成待办：把这条结论交给档案整理，只抽约定和日期，不再重传整个聊天。 */
    private fun toTodo(digest: StoredDigest) {
        val selected = talker ?: return
        Toast.makeText(activity, "正在整理这条里的约定…", Toast.LENGTH_SHORT).show()
        // 队列会在页面快照、模型配置、账号任一处不满足时返回 false，不能一律提示成功。
        // 用户明确要求再整理一次，不走「同一段转述半小时只处理一次」的去重。
        DigestFactQueue.offer(activity, null, selected, "历史纪要", digest.text, dedupe = false) { ok ->
            Toast.makeText(activity, if (ok) "整理完成，去待办看看" else "这次没整理成，切回聊天再试",
                Toast.LENGTH_SHORT).show()
        }
    }

    private fun delete(digest: StoredDigest) {
        val key = contactKey ?: return
        scope.launch {
            val removed = withContext(Dispatchers.IO) {
                RelationFactBridge.deleteDigest(activity, key, digest.id)
            }
            MoodLog.i("DIGEST_HISTORY_DELETED ok=" + removed)
            if (!removed) Toast.makeText(activity, "这条已经不在列表里了", Toast.LENGTH_SHORT).show()
            load(key)
        }
    }

    /** 汇总区间：周报按自然周（周一 00:00 起），其余按整天回数，全部不设下限。 */
    private enum class ReportRange(val label: String) {
        WEEK("本周"), TODAY("今天"), DAYS7("最近 7 天"), DAYS30("最近 30 天"), ALL("全部")
    }

    /** 拼好的一份汇总：标题、副标题、正文分开存，图片按前三段画，复制和弹层用整段。 */
    private data class Report(val title: String, val subtitle: String, val body: String, val text: String,
        val count: Int)

    private fun pickRange() {
        if (!ready()) return
        kit.picker("汇总区间", ReportRange.values().map { it.label }) { index ->
            openReport(ReportRange.values()[index])
        }
    }

    /** 汇总读的是已经加载好的列表，没读到就明说，不把「还在读」当成「没有」。 */
    private fun ready(): Boolean {
        if (talker == null) {
            Toast.makeText(activity, "返回一个聊天再打开", Toast.LENGTH_SHORT).show()
            return false
        }
        if (!loaded) {
            val text = if (loadFailed) "读取纪要失败了，关掉重开这个面板再试" else "还在读取纪要，等一秒再点"
            Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()
            return false
        }
        return true
    }

    private fun openReport(range: ReportRange) {
        if (!ready()) return
        val known = conversation
        if (known != null) {
            present(range, known)
            return
        }
        // 第一次汇总才去查会话名：平时只翻列表的人，不该为标题多扫一次数据库。
        val selected = talker ?: return
        scope.launch {
            val name = conversationLabel(selected)
            conversation = name
            present(range, name)
        }
    }

    private fun present(range: ReportRange, conversation: String) {
        val report = compose(range, conversation)
        if (report.count > 0) {
            // 只记区间、条数和字数：日志会被别人看到，纪要正文不落日志。
            MoodLog.i("DIGEST_REPORT_OK range=" + range.name + " count=" + report.count +
                " chars=" + report.text.length)
        } else {
            MoodLog.i("DIGEST_REPORT_EMPTY range=" + range.name)
        }
        showReport(report)
    }

    /**
     * 纯本地拼装：按覆盖时段排序、按时段去重、超长截断。
     *
     * 库里每条已经是模型写好的成品，再让它「汇总一次」等于多花一次钱，也等于给它一次
     * 把原文没有的因果和情绪编进去的机会；排序、去重、截断这些事本地做得到，就不该上模型。
     */
    private fun compose(range: ReportRange, conversation: String,
        now: Long = System.currentTimeMillis()): Report {
        val start = rangeStart(range, now)
        val picked = items
            .filter { val anchor = anchorOf(it); anchor > 0 && (range == ReportRange.ALL || anchor >= start) }
            .sortedWith(compareByDescending<StoredDigest> { anchorOf(it) }.thenByDescending { it.createdAt })
        // 库里写入时已按时段去过重，这里按同一规则再收一次：同一天同时段只留最新的一条。
        val seen = HashSet<String>()
        val unique = picked.filter { seen.add(dedupeKey(it)) }
        val title = conversation + " · " + range.label + "纪要"
        val newest = unique.firstOrNull()?.let { anchorOf(it) } ?: now
        val oldest = unique.lastOrNull()?.let { anchorOf(it) } ?: now
        val subtitle = FactTimes.formatLocal(oldest, "MM-dd") + " – " +
            FactTimes.formatLocal(newest, "MM-dd") + " · 共 " + unique.size + " 条"
        val header = title + "\n" + subtitle + "\n\n"
        val body = StringBuilder()
        var dropped = 0
        // 末尾还要写一行「更早的 N 条没显示」，先把这段预算垫掉，免得最后一行被挤掉。
        val budget = (MAX_REPORT_CHARS - header.length - 24).coerceAtLeast(240)
        for (digest in unique) {
            val line = DigestArchive.stamp(anchorOf(digest)) + " · " + digest.text.trim()
            if (body.length + line.length + 1 > budget) {
                // 超上限保住最近的：列表已按时间倒序，丢掉的都是更早的。
                dropped++
                continue
            }
            body.append(line).append('\n')
        }
        if (dropped > 0) body.append("更早的 ").append(dropped).append(" 条没显示\n")
        val trimmed = body.toString().trimEnd()
        return Report(title, subtitle, trimmed, header + trimmed, unique.size)
    }

    /** 一条纪要属于哪一天：先看它覆盖时段的末尾，老数据缺时段才退回存下来的时间。 */
    private fun anchorOf(digest: StoredDigest): Long = when {
        digest.rangeEnd > 0 -> digest.rangeEnd
        digest.createdAt > 0 -> digest.createdAt
        else -> 0L
    }

    /** 和 DigestArchive.derivedId 同一套去重口径：时段标签 + 那一天的日期。 */
    private fun dedupeKey(digest: StoredDigest): String =
        digest.rangeLabel.trim() + "\u0000" + FactTimes.dayKey(anchorOf(digest))

    private fun rangeStart(range: ReportRange, now: Long): Long = when (range) {
        ReportRange.TODAY -> FactTimes.startOfDay(now)
        ReportRange.WEEK -> startOfWeek(now)
        ReportRange.DAYS7 -> FactTimes.startOfDay(now) - 6L * DAY
        ReportRange.DAYS30 -> FactTimes.startOfDay(now) - 29L * DAY
        ReportRange.ALL -> 0L
    }

    /** 本周从周一 00:00 起：Calendar 里周日是 1，换算成距周一的天数才不会从周日切开。 */
    private fun startOfWeek(now: Long): Long {
        val day = FactTimes.startOfDay(now)
        val weekday = Calendar.getInstance().apply { timeInMillis = day }.get(Calendar.DAY_OF_WEEK)
        return day - ((weekday + 5) % 7) * DAY
    }

    /** 标题上的会话名：群查 chatroom 表的群名，私聊查 rcontact 的备注/昵称；查不到就用通用说法。 */
    private suspend fun conversationLabel(selected: String): String = runCatching {
        // 私聊不能用 displayNames：它按账号哈希过滤库，拿不到 scope 时恒为空。
        // contactNames 遍历全部句柄，和待回清单用的是同一条路径。
        val found = if (selected.endsWith("@chatroom")) {
            ReplyDatabaseHistory.groupNames(listOf(selected))[selected]
        } else {
            ReplyDatabaseHistory.contactNames(listOf(selected))[selected]
        }
        found?.trim()?.takeIf { it.isNotEmpty() && it != selected }
    }.getOrNull() ?: plainName(selected)

    /** 拿到的还是 wxid 这类内部号就不冒充名字。 */
    private fun plainName(selected: String): String {
        val raw = selected.trim()
        val looksLikeId = raw.isEmpty() || raw.startsWith("wxid_") || raw.startsWith("gh_") ||
            raw.endsWith("@chatroom") || raw.all { it.isDigit() }
        return if (looksLikeId) "当前会话" else raw
    }

    private fun showReport(report: Report) {
        val dialog = Dialog(activity)
        val root = kit.root()
        val column = kit.content(root)
        column.addView(kit.heading(report.title))
        if (report.count == 0) {
            column.addView(kit.body("这个区间还没有存下的纪要。先去「群里在聊什么」生成一次，结论会自动存进来。")
                .apply { setPadding(0, theme.dp(8), 0, theme.dp(4)) })
            column.addView(theme.action("关闭", primary = true) { dialog.dismiss() },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = theme.dp(10) })
        } else {
            column.addView(kit.caption(report.subtitle).apply { setPadding(0, theme.dp(4), 0, theme.dp(8)) })
            val body = kit.body(report.body, ReplyMetrics.TEXT_BODY).apply { setTextIsSelectable(true) }
            val scroll = PanelMaxScroll(activity,
                (activity.resources.displayMetrics.heightPixels * 0.55f).toInt()).apply { addView(body) }
            column.addView(scroll, LinearLayout.LayoutParams(-1, -2).apply { topMargin = theme.dp(6) })
            val status = kit.status("").apply { visibility = View.GONE }
            column.addView(status, LinearLayout.LayoutParams(-1, -2))
            val actions = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            actions.addView(theme.action("复制全文", quiet = true) { copyReport(report) },
                LinearLayout.LayoutParams(0, -2, 1f))
            actions.addView(theme.action("保存为图片", quiet = true) { saveReportImage(report, status) },
                LinearLayout.LayoutParams(0, -2, 1f))
            actions.addView(theme.action("关闭", quiet = true) { dialog.dismiss() },
                LinearLayout.LayoutParams(0, -2, 1f))
            column.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = theme.dp(8) })
        }
        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setGravity(Gravity.BOTTOM)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(ReplyMetrics.DIM)
        }
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.show()
    }

    private fun copyReport(report: Report) {
        (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("聊天纪要汇总", report.text))
        Toast.makeText(activity, "已复制全文", Toast.LENGTH_SHORT).show()
    }

    /**
     * 把汇总画成一张 1080 宽的长图存进相册。
     *
     * 8000 字可能铺到 9000+ px，画布加 PNG 压缩是几十 MB 级别的活，放主线程会明显假死；
     * 这里挪到 Default 线程，只把状态文字切回主线程。面板先关掉时 scope 已取消，
     * 写回会被自动丢弃，不会碰已销毁的界面。
     */
    private fun saveReportImage(report: Report, status: TextView) {
        showStatus(status, "正在画图…", true)
        scope.launch {
            val saved = withContext(Dispatchers.Default) {
                runCatching {
                    val bitmap = StatsImage.renderReport(report.title, report.subtitle, report.body)
                    val result = StatsImage.saveReport(activity, bitmap, "yanwai-digest")
                    bitmap.recycle()
                    result
                }.getOrElse { error ->
                    MoodLog.w("DIGEST_REPORT_IMAGE_FAILED " + error.javaClass.simpleName)
                    StatsImage.Saved(null, "画图失败：" + error.javaClass.simpleName + "，先用「复制全文」也行")
                }
            }
            MoodLog.i("DIGEST_REPORT_IMAGE ok=" + saved.ok)
            if (saved.ok) showStatus(status, "已存进相册 · Pictures/言外", true)
            else showStatus(status, saved.reason, false)
        }
    }

    private fun showStatus(status: TextView, message: String, ok: Boolean) {
        status.text = message
        status.setTextColor(if (ok) theme.success else theme.error)
        status.visibility = View.VISIBLE
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        /** 汇总正文上限：再长没人看完，长图也会占掉太多内存。 */
        const val MAX_REPORT_CHARS = 8000
        const val DAY = 86_400_000L
    }
}
