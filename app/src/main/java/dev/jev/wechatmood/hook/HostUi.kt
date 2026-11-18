package dev.jev.wechatmood.hook

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.view.View

import android.widget.Switch
import android.widget.Toast

import dev.jev.wechatmood.analysis.SignalAnalyzer
import dev.jev.wechatmood.core.ModulePrefs
import dev.jev.wechatmood.core.AnalysisInput
import dev.jev.wechatmood.core.Diagnostics

/** Owns chat actions; the analysis switch is mounted only in the expanded plus row. */
class HostUi(private val activity: Activity) {
    private val replyUi = ReplyHostUi(activity, { createAnalysisControl() }, { showFeaturePanel() })
    private var control: Switch? = null
    private var syncing = false
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
        syncControl()
    }

    private fun syncControl() {
        syncing = true
        control?.apply {
            visibility = View.VISIBLE
            isChecked = ModulePrefs.isChatEnabled(talker)
            isEnabled = talker != null
            contentDescription = "当前聊天分析开关，本地记住选择；$status；长按打开菜单与设置"
        }
        syncing = false
    }

    private fun createAnalysisControl(): View {
        removeControl()
        val theme = ReplyTheme(activity)
        val toggle = Switch(activity).apply {
            text = "分析"
            textSize = 14f
            setTextColor(theme.ink)
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = ColorStateList(states, intArrayOf(theme.accent, theme.muted))
            trackTintList = ColorStateList(states, intArrayOf(theme.soft, theme.border))
            switchPadding = dp(3)
            minHeight = dp(48)
            setPadding(dp(4), 0, dp(4), 0)
            setOnCheckedChangeListener { _, checked ->
                if (!syncing) {
                    if (!MessageSniffer.setChatEnabled(talker, checked)) {
                        syncing = true
                        isChecked = ModulePrefs.isChatEnabled(talker)
                        syncing = false
                        Diagnostics.showFailure(activity, "开关未保存", "当前聊天已变化或本地保存失败，请重新进入聊天后重试。")
                    } else if (!checked) BubbleDecorator.clearAll()
                    MessageSniffer.refresh()
                }
            }
            setOnLongClickListener { showActions(); true }
        }
        control = toggle
        syncControl()
        toggle.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                control = toggle
                syncControl()
            }
            override fun onViewDetachedFromWindow(view: View) {
                // The host can temporarily detach and reuse the same panel and switch.
                if (control === view) control = null
            }
        })
        return toggle
    }

    private fun showActions() {
        if (dialog?.isShowing == true) return
        val selectedTalker = talker
        val selectedMessages = messages
        dialog = AlertDialog.Builder(activity).setTitle("言外 · $status")
            .setItems(arrayOf("帮我回 / 上次建议", "分析本屏", "助手设置", "导出运行日志")) { _, which ->
                when (which) {
                    0 -> replyUi.open()
                    1 -> if (MessageSniffer.setChatEnabled(selectedTalker, true)) {
                        selectedMessages.forEach { SignalAnalyzer.retryFailure(it.key) }
                        MessageSniffer.refresh()
                    } else Diagnostics.showFailure(activity, "分析开关未保存", "当前聊天未识别、已变化或本地保存失败，请重新进入聊天后重试。")
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
        removeControl()
    }

    private fun openSettings() = HostSettingsEntry.open(activity)
    fun suggestReply(focusMessageId: Long? = null, imageDataUri: String? = null) = replyUi.open(focusMessageId, imageDataUri)

    /** ＋面板里「附加」弹出的功能面板。 */
    private fun showFeaturePanel() {
        val current = talker
        FeaturePanel(activity, current, topicsAvailable = current != null,
            analysisEnabled = ModulePrefs.isChatEnabled(current),
            onReply = { replyUi.open() },
            onTopics = { replyUi.open(autoTopics = true) },
            onStats = { showStats() },
            onFacts = { Toast.makeText(activity, "在「帮我回」抽屉的「更新关系档案」旁查看", Toast.LENGTH_SHORT).show() },
            onAnalysisChanged = { value ->
                if (current != null) {
                    val saved = ModulePrefs.setChatEnabled(current, value)
                    if (saved && !value) SignalAnalyzer.cancelConversation(current)
                }
            }).show()
    }

    /** 群聊统计：只聚合本地最近读取到的消息，不联网。 */
    private fun showStats() {
        val context = runCatching { MessageSniffer.replyContext(requireBottom = false) }.getOrNull()
        if (context == null) {
            Toast.makeText(activity, "先在聊天页打开一次「附加」再试", Toast.LENGTH_SHORT).show()
            return
        }
        val records = context.messages.map {
            dev.jev.wechatmood.reply.StatRecord(it.time, if (it.speaker == "我") 1 else 0, 1, it.text)
        }
        val stats = dev.jev.wechatmood.reply.GroupStatsCalculator.compute(records)
        val text = stats.lines().joinToString("\n")
        AlertDialog.Builder(activity)
            .setTitle("最近 ${context.messages.size} 条消息")
            .setMessage(text)
            .setPositiveButton("复制") { _, _ ->
                val clipboard = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("言外统计", text))
                Toast.makeText(activity, "已复制", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("关闭", null)
            .show()
    }
    fun hide() { replyUi.hide(); removeControl(); dialog?.dismiss(); messages = emptyList(); talker = null }
    fun dispose() { hide(); replyUi.dispose() }
    private fun removeControl() {
        control?.setOnCheckedChangeListener(null)
        control?.setOnLongClickListener(null)
        control = null
    }
    private fun dp(n: Int) = (n * activity.resources.displayMetrics.density).toInt()
}
