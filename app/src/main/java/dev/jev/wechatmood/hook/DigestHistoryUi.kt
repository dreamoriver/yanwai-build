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
import android.widget.Toast
import dev.jev.wechatmood.core.MoodLog
import dev.jev.wechatmood.core.RelationFactBridge
import dev.jev.wechatmood.reply.DigestArchive
import dev.jev.wechatmood.reply.ReplyContactKey
import dev.jev.wechatmood.reply.StoredDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 历史纪要：存下来的 AI 转述。
 *
 * 只读本机档案库，不额外请求模型；每条可以复制、转成待办或删除。
 * 库里存的是模型写出的结论，聊天素材不在里面。
 */
internal class DigestHistoryUi(private val activity: Activity, private val talker: String?) {
    private val kit = PanelKit(activity)
    private val theme = kit.theme
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val listContainer = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private var items: List<StoredDigest> = emptyList()
    private var contactKey: ReplyContactKey? = null

    fun show() {
        val dialog = Dialog(activity)
        val root = kit.root()
        val column = kit.content(root)
        column.addView(kit.heading("历史纪要"))
        column.addView(kit.caption("存下来的 AI 转述，只有结论，没有聊天原文").apply {
            setPadding(0, theme.dp(4), 0, theme.dp(8))
        })
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
        val loaded = withContext(Dispatchers.IO) { RelationFactBridge.digests(activity, key) }
        MoodLog.i("DIGEST_HISTORY_LOAD total=" + (loaded?.size ?: -1))
        if (loaded == null) {
            // 读失败和「真没有」是两件事：库异常时不能说成空态。
            listContainer.removeAllViews()
            listContainer.addView(kit.body("读取纪要失败，稍后重试"))
            return
        }
        items = loaded
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
}
