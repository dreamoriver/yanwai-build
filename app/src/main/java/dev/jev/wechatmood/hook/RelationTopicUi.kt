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
import dev.jev.wechatmood.core.AnalysisCacheKey
import dev.jev.wechatmood.core.ModulePrefs
import dev.jev.wechatmood.core.MoodLog
import dev.jev.wechatmood.core.ReplyIdentityBridge
import dev.jev.wechatmood.reply.ContactBackground
import dev.jev.wechatmood.reply.ReplyContactKey
import dev.jev.wechatmood.reply.ReplyIdentitySetting
import dev.jev.wechatmood.reply.TopicRequest
import dev.jev.wechatmood.reply.TopicSuggestion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 关系温度 → 某个人 → 找话题。
 *
 * 复用而不是重写：读最近消息走 [ReplyDatabaseHistory.recentContext]（同一个聊天库、
 * 同一套类型白名单与上下文裁剪），请求走 [TopicRequest]（抽屉里「找找话题」用的那条
 * 链路：TopicProtocol 的提示词、ReplyHttpClient 的重试与用量统计），界面零件取自
 * [PanelKit]。这里只负责「换一个会话当输入」和结果展示，不碰提示词，也不自己发 HTTP。
 *
 * 只在微信进程内完成：除了这一次模型请求，不产生别的网络访问；日志只记哈希、条数和
 * 异常类名，不写聊天正文、不写联系人名字。
 */
internal class RelationTopicUi private constructor(
    private val activity: Activity,
    private val talker: String,
    private val contactKey: String?,
    private val name: String,
    private val quietText: String,
    private val accountScope: String?,
) {
    private val kit = PanelKit(activity)
    private val theme = kit.theme
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val results = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val loading = kit.loading()
    private var job: Job? = null
    /** 上一次拿到的一组话题：重试与「换一批」要把它当 avoid_topics 传给模型。 */
    private var topics: List<TopicSuggestion>? = null

    fun show() {
        val dialog = Dialog(activity)
        val root = kit.root()
        val column = kit.content(root)
        column.addView(kit.heading("找话题 · " + name))
        column.addView(kit.caption(quietText + "；只用这个会话最近 " + COUNT + " 条本机消息").apply {
            setPadding(0, theme.dp(4), 0, theme.dp(8))
        })
        val scroll = PanelMaxScroll(activity,
            (activity.resources.displayMetrics.heightPixels * 0.55f).toInt()).apply { addView(results) }
        column.addView(scroll, LinearLayout.LayoutParams(-1, -2).apply { topMargin = theme.dp(6) })
        column.addView(loading)
        column.addView(theme.action("关闭", primary = true) { dialog.dismiss() },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = theme.dp(10) })

        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setGravity(Gravity.BOTTOM)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(ReplyMetrics.DIM)
        }
        // 和关系温度面板一样，show 之前定宽度，否则会先按内容宽度露半屏再跳全宽。
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.show()
        dialog.setOnDismissListener { scope.cancel() }
        request()
    }

    /** [previous] 是已经看过的一组；「换一批」和重试都带着它，让模型换方向。 */
    private fun request(previous: List<TopicSuggestion> = emptyList()) {
        job?.cancel()
        results.removeAllViews()
        loading.showLoading("正在读这个会话的最近消息…")
        job = scope.launch {
            // 配置要在读库之前看：没配模型时读一遍库只是白花时间。
            val settings = ModulePrefs.replySettings()
            if (!settings.isConfigured) {
                MoodLog.i("TEMP_TOPIC_SKIP unconfigured")
                loading.showLoading(null)
                problem("先去言外设置里配好回复模型，再回来点「找话题」。", PanelTone.WARNING, retry = false)
                return@launch
            }
            val read = withContext(Dispatchers.IO) {
                runCatching { ReplyDatabaseHistory.recentContext(talker, COUNT, accountScope) }
            }
            val chat = read.getOrNull()
            val error = read.exceptionOrNull()
            if (error != null) {
                MoodLog.w("TEMP_TOPIC_FAIL " + error.javaClass.simpleName + " " + hash())
                loading.showLoading(null)
                problem("读取这个会话的聊天记录时出错了：" + (error.message ?: error.javaClass.simpleName),
                    PanelTone.ERROR, retry = true, degrade = true)
                return@launch
            }
            if (chat == null) {
                // 读不到 ≠ 没有记录：库没打开、账号核不出来都走这里，得如实说，不能报成 0 条。
                MoodLog.w("TEMP_TOPIC_FAIL NoHistory " + hash())
                loading.showLoading(null)
                problem("这个会话的聊天记录暂时读不到：微信的聊天库还没打开，或者当前账号还没核对出来。",
                    PanelTone.WARNING, retry = true, degrade = true)
                return@launch
            }
            if (chat.messages.isEmpty()) {
                MoodLog.w("TEMP_TOPIC_FAIL EmptyHistory " + hash())
                loading.showLoading(null)
                problem("这个会话本机没有可读的文字消息（可能只剩下图片、语音，或记录已经清空）。",
                    PanelTone.WARNING, retry = true, degrade = true)
                return@launch
            }
            loading.showLoading("正在找 5 个话题…")
            val (identity, background) = contactContext()
            try {
                // 提示词、参考资料选取、重试都在 TopicRequest / TopicProtocol / ReplyHttpClient 里，
                // 这里传的参数与抽屉里完全一致：最近消息 + 空草稿 + 无本次补充 + 关系 + 日期上下文。
                val found = withContext(Dispatchers.IO) {
                    TopicRequest.find(activity, settings, chat.copy(background = background), "", "",
                        identity.relationship, identity.customText, previous = previous)
                }
                topics = found
                loading.showLoading(null)
                render(found)
                MoodLog.i("TEMP_TOPIC_OK " + hash() + " count=" + found.size + " msgs=" + chat.messages.size +
                    " relationship=" + identity.relationship.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 只记异常类名：模型服务返回的正文里可能有聊天内容。
                MoodLog.w("TEMP_TOPIC_FAIL " + e.javaClass.simpleName + " " + hash())
                loading.showLoading(null)
                problem(e.message ?: "暂时无法生成话题，请重试", PanelTone.ERROR, retry = true)
            }
        }
    }

    /**
     * 这个联系人在档案里存过的关系与背景。
     *
     * 都是可选输入：读不到就按「未指定身份」问，不擅自假定亲疏。给一个短超时，
     * 主进程拉不起来时宁可少一点背景，也别让面板卡在加载态。
     */
    private suspend fun contactContext(): Pair<ReplyIdentitySetting, ContactBackground> {
        val key = contactKey?.let { runCatching { ReplyContactKey(it) }.getOrNull() }
        if (key == null) return Pair(ReplyIdentitySetting(), ContactBackground())
        val loaded = withTimeoutOrNull(IDENTITY_WAIT_MS) {
            runCatching {
                ReplyIdentityBridge.load(activity, key) to ReplyIdentityBridge.loadBackground(activity, key)
            }.getOrNull()
        }
        return loaded ?: Pair(ReplyIdentitySetting(), ContactBackground())
    }

    private fun render(list: List<TopicSuggestion>) {
        results.removeAllViews()
        results.addView(kit.caption("点一下短句就复制，粘到聊天框里自己改再发").apply {
            setPadding(0, 0, 0, theme.dp(2))
        })
        list.forEachIndexed { index, topic ->
            results.addView(card(index, topic),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = theme.dp(8) })
        }
        results.addView(theme.action("换一批", quiet = true) { request(list) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = theme.dp(10) })
        results.addView(kit.caption("换一批会把这几个题目标成「看过」，让模型换方向").apply {
            setPadding(0, theme.dp(4), 0, 0)
        })
    }

    private fun card(index: Int, topic: TopicSuggestion) = kit.card().apply {
        addView(theme.label((index + 1).toString() + " · " + topic.title,
            ReplyMetrics.TEXT_CAPTION, theme.accent, bold = true))
        topic.parts.forEach { part ->
            addView(partRow(part), LinearLayout.LayoutParams(-1, -2).apply { topMargin = theme.dp(6) })
        }
        val reason = theme.label(topic.reason, ReplyMetrics.TEXT_CAPTION, theme.muted).apply {
            visibility = View.GONE
            setPadding(0, theme.dp(6), 0, 0)
        }
        val toggle = theme.action("为什么聊这个 ▾", quiet = true) { }
        toggle.setOnClickListener {
            val open = reason.visibility != View.VISIBLE
            reason.visibility = if (open) View.VISIBLE else View.GONE
            toggle.text = if (open) "收起理由 ▴" else "为什么聊这个 ▾"
        }
        addView(toggle, LinearLayout.LayoutParams(-1, theme.dp(ReplyMetrics.TAP)).apply { topMargin = theme.dp(2) })
        addView(reason)
    }

    /** 一句可以直接发的短消息：整行可点，点了就复制，不填入任何输入框。 */
    private fun partRow(text: String): TextView = theme.label(text, ReplyMetrics.TEXT_BODY, theme.ink).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(theme.dp(12), theme.dp(10), theme.dp(12), theme.dp(10))
        minimumHeight = theme.dp(ReplyMetrics.TAP)
        background = theme.shape(theme.soft, ReplyMetrics.RADIUS_BUTTON)
        isClickable = true; isFocusable = true
        setOnClickListener { copy(text, "已复制这句，去聊天框里粘贴") }
    }

    /**
     * 失败与退化的统一出口。[degrade] 为真时说明这件事是「本机拿不到这个会话的记录」，
     * 而不是模型出错：那种情况只能提示用户切到那个聊天再生成，并给一个能带走的名字。
     */
    private fun problem(message: String, tone: PanelTone, retry: Boolean, degrade: Boolean = false) {
        results.removeAllViews()
        results.addView(kit.status(message, tone))
        if (degrade) {
            results.addView(kit.body("想在这里直接生成，可以打开这个聊天再点「找话题」：那边能核对到完整的前后文。")
                .apply { setPadding(0, theme.dp(8), 0, 0) })
            results.addView(theme.action("复制联系人名字", quiet = true) { copy(name, "已复制名字") },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = theme.dp(8) })
        }
        if (retry) {
            results.addView(theme.action("重试", primary = true) { request(topics.orEmpty()) },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = theme.dp(8) })
        }
    }

    private fun copy(text: String, done: String) {
        val ok = runCatching {
            (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("言外话题", text))
        }.isSuccess
        toast(if (ok) done else "复制失败，请长按文字手动复制")
    }

    private fun toast(message: String) = Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()

    /** 日志里的会话标识：只留哈希前 16 位，不写 wxid。 */
    private fun hash() = "talker=" + AnalysisCacheKey.digest(talker).take(16)

    companion object {
        /** 一个会话取多少条最近消息：和抽屉的默认「参考最近」口径接近，不至于压满上下文。 */
        const val COUNT = 20
        /** 读关系与背景的上限：这是可选输入，等太久不如没有。 */
        private const val IDENTITY_WAIT_MS = 2000L

        fun open(activity: Activity, talker: String, contactKey: String?, name: String,
            quietText: String, accountScope: String?) =
            RelationTopicUi(activity, talker, contactKey, name, quietText, accountScope).show()
    }
}
