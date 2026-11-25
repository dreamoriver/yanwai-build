package dev.jev.wechatmood.hook

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.view.View

import android.widget.Switch
import android.widget.Toast
import kotlinx.coroutines.launch

import dev.jev.wechatmood.analysis.SignalAnalyzer
import dev.jev.wechatmood.core.ModulePrefs
import dev.jev.wechatmood.core.AnalysisInput
import dev.jev.wechatmood.core.Diagnostics

/** Owns chat actions; the analysis switch is mounted only in the expanded plus row. */
class HostUi(private val activity: Activity) {
    private val replyUi = ReplyHostUi(activity, { showFeaturePanel() })
    private var status = ""
    private var messages = emptyList<AnalysisInput>()
    private var talker: String? = null
    private var dialog: AlertDialog? = null
    fun showStatus(value: String, current: List<AnalysisInput>, currentTalker: String? = null) {
        if (talker != currentTalker) dialog?.dismiss()
        talker = currentTalker
        status = value
        messages = current
        replyUi.update(currentTalker)
        RelationChatBadge.update(activity, currentTalker)
    }

    private fun showActions() {
        if (dialog?.isShowing == true) return
        val selectedTalker = talker
        val selectedMessages = messages
        dialog = AlertDialog.Builder(activity).setTitle("言外 · $status")
            .setItems(arrayOf("帮我回 / 上次建议", "分析本屏", "助手设置", "导出运行日志")) { _, which ->
                when (which) {
                    0 -> replyUi.open()
                    1 -> {
                        selectedMessages.forEach { SignalAnalyzer.retryFailure(it.key) }
                        MessageSniffer.refresh()
                    }
                    2 -> openSettings()
                    3 -> Diagnostics.show(activity)
                }
            }
            .setNegativeButton("关闭", null).create().also { it.show() }
    }

    fun showSettings() {
        replyUi.hide()
        talker = null
        messages = emptyList()
        dialog?.dismiss()
    }

    private fun openSettings() = HostSettingsEntry.open(activity)
    fun suggestReply(focusMessageId: Long? = null, imageDataUri: String? = null) = replyUi.open(focusMessageId, imageDataUri)

    /** ＋面板里「附加」弹出的功能面板。 */
    private fun showFeaturePanel() {
        val current = talker
        // 私聊是关系档案，群聊是群聊纪要；两边都按会话存，共用同一套待办与提醒。
        FeaturePanel(activity, current, replyAvailable = current != null, factsAvailable = current != null,
            onReply = {
                // 抽屉要求还停在这个聊天上；切走了就说清楚，别让按钮看起来没反应。
                if (!replyUi.open()) {
                    Toast.makeText(activity, "先回到这个聊天再点", Toast.LENGTH_SHORT).show()
                }
            },
            onStats = { showStats() },
            onFacts = { RelationArchiveUi(activity, current).show() },
            onTodos = { RelationTodoUi(activity, current).show() },
            onDigests = { DigestHistoryUi(activity, current).show() },
            onTemperature = { RelationTemperatureUi(activity, current).show() }).show()
    }
    /**
     * 协程体里直接读历史库、建统计界面，异常不能交给线程默认处理器：那会把微信进程带崩。
     * 挂一个 handler 记一笔就收住。另外建弹层前要确认页面还在（见 showStats）。
     */
    private val statsScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate +
            kotlinx.coroutines.CoroutineExceptionHandler { _, error ->
                dev.jev.wechatmood.core.MoodLog.w(
                    "STATS_FAILED " + error.javaClass.simpleName + " " + error.message)
            })

    /** 群聊统计：按选择的时段从数据库取，不再只看已加载的几十条。 */
    private fun showStats() {
        val context = runCatching { MessageSniffer.replyContext(requireBottom = false) }.getOrNull()
        dev.jev.wechatmood.core.MoodLog.i(
            "STATS_OPEN ok=${context != null} msgs=${context?.messages?.size ?: -1} talker=${context?.talker?.hashCode()}")
        // 面板开着时可能已经切了聊天：入口指的是旧会话，别把统计开到新会话上。
        val current = MessageSniffer.currentReplyTalker()
        if (talker != null && current != null && current != talker) {
            Toast.makeText(activity, "已经切到别的聊天了，重新打开统计", Toast.LENGTH_SHORT).show()
            return
        }
        if (context == null) {
            Toast.makeText(activity, "先在聊天页等一会儿，再打开统计", Toast.LENGTH_SHORT).show()
            return
        }
        statsScope.launch {
            val since = StatsFilter.sinceMillis()
            // 先确认当前登录账号，只统计这个账号的聊天记录。
            val account = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { ReplyDatabaseHistory.replyAccount(context) }.getOrNull()
            }
            val mapped: List<dev.jev.wechatmood.reply.ReplyMessage> = if (account == null) {
                // 账号确认不了时不查历史库：可能混进另一个账号同群的消息，只用当前可见页面。
                dev.jev.wechatmood.core.MoodLog.i("STATS_SCOPE unknown fallback=page msgs=${context.messages.size}")
                context.messages
            } else {
                val rows = runCatching {
                    ReplyDatabaseHistory.statMessages(context.talker, since,
                        if (StatsFilter.limit == 0) 5000 else StatsFilter.limit, account)
                }.getOrDefault(emptyList())
                rows.map {
                    // 语音要带上引用，统计里的「群里在聊什么」才转写得了；漏了这一步，
                    // 28 条语音会全部变成占位，模型只能说「都在发语音」。
                    dev.jev.wechatmood.reply.ReplyMessage(it.messageId, it.speaker(), it.createdAt,
                        // 合并转发先摊平成「谁说了什么」，卡片取标题/描述，实在没有才留占位。
                        it.materialText(), voice = it.voiceSource(),
                        senderId = it.senderId(), type = it.type, imagePath = it.imagePath)
                }
            }
            // 取数这段时间用户可能已经退出这个聊天页：对已销毁的 Activity 建弹层会崩。
            if (activity.isFinishing || activity.isDestroyed) {
                dev.jev.wechatmood.core.MoodLog.w("STATS_SKIP activity_finished")
                return@launch
            }
            GroupStatsUi(activity, context, mapped, account).show()
        }
    }

    fun hide() { replyUi.hide(); dialog?.dismiss(); messages = emptyList(); talker = null }
    fun dispose() { hide(); replyUi.dispose() }
    private fun dp(n: Int) = (n * activity.resources.displayMetrics.density).toInt()
}
