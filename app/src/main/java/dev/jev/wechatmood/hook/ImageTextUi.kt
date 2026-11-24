package dev.jev.wechatmood.hook

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import dev.jev.wechatmood.core.ModulePrefs
import dev.jev.wechatmood.core.MoodLog
import dev.jev.wechatmood.reply.ImageText
import dev.jev.wechatmood.reply.takeSafely
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 长按图片 → 「提取文字」：打开就显示加载态，只抄图里的字，结果可以整段复制。
 *
 * 和「图片描述」故意分成两个入口（见 [ImageText] 的类注释）：描述留一句概括，
 * 取字要逐行原样，同一段提示词干不了两件事，混用就会把单号、价格压成一句空话。
 *
 * [image] 是长按那一刻已经从气泡取好、压好的 data URL（[MediaImage] 的输出）；
 * 气泡刚好被回收时传空，弹层再按 [messageId] 去聊天列表里找一次——两处都拿不到才报错。
 */
internal class ImageTextUi(private val activity: Activity, private val talker: String,
    private val messageId: Long, private val image: String? = null) {
    private val kit = PanelKit(activity)
    private val theme = kit.theme
    /** 协程体里直接取图、调模型，异常必须在这里收住，否则会落到线程默认处理器把微信进程带崩。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate +
        CoroutineExceptionHandler { _, error ->
            MoodLog.w("IMAGE_TEXT_CRASH " + error.javaClass.simpleName + " " + error.message)
        })
    private var dialog: Dialog? = null
    private var body: TextView? = null
    private var status: TextView? = null
    private var loading: ReplyLoadingView? = null
    private var copy: TextView? = null
    private var retry: TextView? = null
    private var result = ""

    fun show() {
        // 长按那一刻页面可能已经在关：对已销毁的 Activity 建弹层会崩。
        if (activity.isFinishing || activity.isDestroyed) return
        val dialog = kit.sheet().also { this.dialog = it }
        val root = kit.root()
        val column = kit.content(root)

        column.addView(kit.heading("提取文字"))
        column.addView(kit.caption("只抄图中的文字，不解释、不总结 · 由你配置的回复模型识别")
            .apply { setPadding(0, theme.dp(4), 0, theme.dp(10)) })

        // 取字结果可以很长（价目表、聊天截图），整段可选可滚动，复制按钮再兜一层。
        val text = kit.body("", ReplyMetrics.TEXT_ITEM).apply { setTextIsSelectable(true) }
        body = text
        val scroll = PanelMaxScroll(activity, (activity.resources.displayMetrics.heightPixels * 0.55f).toInt())
            .apply { addView(text) }
        column.addView(scroll, LinearLayout.LayoutParams(-1, -2))

        // 状态行：失败原因、缓存提示、字数都写这里；颜色只是辅助。
        val statusText = kit.status("", PanelTone.NEUTRAL)
        status = statusText
        column.addView(statusText, LinearLayout.LayoutParams(-1, -2))

        val busy = kit.loading()
        loading = busy
        column.addView(busy, LinearLayout.LayoutParams(-1, -2).apply { topMargin = theme.dp(4) })

        val copyButton = theme.action("复制全部", primary = true) { copyAll() }.apply {
            isEnabled = false
            alpha = 0.5f
        }
        copy = copyButton
        column.addView(copyButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = theme.dp(12) })

        val retryButton = theme.action("重试", quiet = true) { request() }.apply { visibility = View.GONE }
        retry = retryButton
        column.addView(retryButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = theme.dp(8) })

        column.addView(theme.action("关闭", quiet = true) { dismiss() },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = theme.dp(8) })

        dialog.setContentView(root)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnDismissListener { scope.cancel() }
        dialog.show()
        request()
    }

    private fun request() {
        retry?.visibility = View.GONE
        status?.text = ""
        copy?.isEnabled = false
        copy?.alpha = 0.5f
        // 缓存命中直接显示：同一张图长按两次不重复请求、不重复计费。
        val hit = ImageText.cached(activity, talker, messageId)
        if (hit != null) {
            loading?.showLoading(null)
            render(hit, fromCache = true)
            return
        }
        body?.text = ""
        body?.setTextColor(theme.ink)
        result = ""
        loading?.showLoading("正在识别图里的文字…")
        scope.launch {
            val settings = ModulePrefs.replySettings()
            if (!settings.isConfigured) {
                fail("请先在言外的「回复建议」里配置模型")
                return@launch
            }
            // 长按那一刻带过来的图优先；没有就按消息 id 去列表里补一次（主线程取图、后台压缩）。
            val source = image?.takeIf { it.isNotBlank() }
                ?: runCatching { MessageSniffer.captureMediaImage(messageId) }.getOrNull()
            if (source.isNullOrBlank()) {
                fail("没能取到这张图片，请重新长按图片再试")
                return@launch
            }
            ImageText.extract(activity, settings, talker, messageId, source)
                .onSuccess { render(it, fromCache = false) }
                .onFailure { fail(it.message.orEmpty().ifBlank { "识别失败，请重试" }) }
        }
    }

    private fun render(raw: String, fromCache: Boolean) {
        loading?.showLoading(null)
        retry?.visibility = View.GONE
        if (ImageText.isNoText(raw)) {
            // 「图里没字」不是失败：不给重试，也不写缓存，免得一次误答被永久记住。
            result = ""
            body?.text = "这张图里没识别到文字"
            body?.setTextColor(theme.muted)
            copy?.isEnabled = false
            copy?.alpha = 0.5f
            status?.text = "换一张有字的图，或重新长按再识别一次"
            status?.setTextColor(theme.muted)
            return
        }
        result = raw
        body?.text = raw
        body?.setTextColor(theme.ink)
        copy?.isEnabled = true
        copy?.alpha = 1f
        status?.text = if (fromCache) "上次识别过的同一张图，已直接读取缓存"
        else "共 ${raw.length} 字，长按文字可选中局部"
        status?.setTextColor(theme.muted)
    }

    private fun fail(reason: String) {
        loading?.showLoading(null)
        result = ""
        body?.text = ""
        copy?.isEnabled = false
        copy?.alpha = 0.5f
        status?.text = reason.takeSafely(160)
        status?.setTextColor(theme.error)
        retry?.visibility = View.VISIBLE
    }

    private fun copyAll() {
        if (result.isBlank()) return
        runCatching {
            (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("图片文字", result))
            Toast.makeText(activity, "已复制全部文字", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(activity, "复制失败，请长按文字手动选中", Toast.LENGTH_SHORT).show()
        }
    }

    fun dismiss() {
        scope.cancel()
        dialog?.dismiss()
    }
}
