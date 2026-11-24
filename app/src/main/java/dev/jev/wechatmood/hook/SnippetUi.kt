package dev.jev.wechatmood.hook

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import dev.jev.wechatmood.core.MoodLog
import dev.jev.wechatmood.core.Snippet
import dev.jev.wechatmood.core.SnippetStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 话术片段：常用表态短句的点选面板。
 *
 * 用途是「这次不想让模型现编」：拒绝、请假、约时间这类话事先写好，
 * 需要时点一下就进剪贴板，不花一次模型调用。
 *
 * 数据全在本机（SnippetStore），不读聊天、不请求网络，关掉面板也不会留下后台任务。
 */
internal class SnippetUi(private val activity: Activity) {
    private val kit = PanelKit(activity)
    private val theme = kit.theme
    /** 列表单独放在这个容器里，保存或删除后只重画它，面板本身不重建。 */
    private val listContainer = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private var items: List<Snippet> = emptyList()
    private var sheet: Dialog? = null
    private var scope: CoroutineScope? = null

    fun show() {
        // 已经开着就不再叠一层，重复点入口只当成一次。
        if (sheet?.isShowing == true) return
        val dialog = kit.sheet()
        val root = kit.root()
        val column = kit.content(root)
        column.addView(kit.heading("话术片段"))
        column.addView(kit.caption("常用表态存本机，点一下就复制").apply {
            setPadding(0, theme.dp(4), 0, theme.dp(8))
        })
        val scroll = PanelMaxScroll(activity, (activity.resources.displayMetrics.heightPixels * 0.55f).toInt()).apply {
            addView(listContainer)
        }
        column.addView(scroll, LinearLayout.LayoutParams(-1, -2).apply { topMargin = theme.dp(6) })
        column.addView(theme.action("新增片段", primary = true) { edit(null) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = theme.dp(12) })
        column.addView(theme.action("关闭", quiet = true) { dialog.dismiss() },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = theme.dp(4) })

        dialog.setContentView(root)
        val work = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = work
        // 每次打开都用新的 scope：上一次关掉时取消过，复用得重建。
        dialog.setOnDismissListener {
            if (sheet === dialog) sheet = null
            work.cancel()
        }
        // show 之前定宽度，否则会先按内容宽度露半屏再跳成全宽。
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        sheet = dialog
        dialog.show()
        MoodLog.i("SNIPPET_OPEN")
        reload()
    }

    /** 只刷新列表区域；面板、按钮、滚动位置都保持原样。 */
    private fun reload() {
        val work = scope ?: return
        work.launch {
            val loaded = withContext(Dispatchers.IO) { SnippetStore.list(activity) }
            items = loaded
            render()
            MoodLog.i("SNIPPET_LOAD total=" + loaded.size)
        }
    }

    private fun render() {
        listContainer.removeAllViews()
        if (items.isEmpty()) {
            listContainer.addView(kit.body("还没有片段。把常用的话存进来，下次点一下就复制。"))
            return
        }
        items.forEach { snippet ->
            listContainer.addView(row(snippet), LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = theme.dp(8)
            })
        }
    }

    /** 整行点击=复制；行尾「⋯」或长按=编辑/删除。 */
    private fun row(snippet: Snippet): View {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(theme.dp(12), theme.dp(10), theme.dp(8), theme.dp(10))
            background = theme.shape(theme.card, ReplyMetrics.RADIUS_CARD, theme.border)
            minimumHeight = theme.dp(ReplyMetrics.TAP)
            isClickable = true
            isFocusable = true
            contentDescription = "话术片段，" + label(snippet) + "，点一下复制"
            setOnClickListener { copy(snippet) }
            setOnLongClickListener { menu(snippet); true }
        }
        val texts = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(theme.label(label(snippet), ReplyMetrics.TEXT_ITEM, theme.ink).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        val preview = preview(snippet.text)
        if (preview.isNotEmpty()) {
            texts.addView(theme.label(preview, ReplyMetrics.TEXT_CAPTION, theme.muted).apply {
                setPadding(0, theme.dp(2), 0, 0)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
        }
        card.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        val more = theme.label("⋯", ReplyMetrics.TEXT_ITEM, theme.muted).apply {
            gravity = Gravity.CENTER
            minWidth = theme.dp(ReplyMetrics.TAP)
            minHeight = theme.dp(ReplyMetrics.TAP)
            isClickable = true
            isFocusable = true
            contentDescription = "编辑或删除这个片段"
            // 和选项行同一套按压反馈，否则这个小按钮点了没反应感。
            background = RippleDrawable(ColorStateList.valueOf((theme.accent and 0x00ffffff) or 0x22000000), null, null)
            setOnClickListener { menu(snippet) }
        }
        card.addView(more, LinearLayout.LayoutParams(-2, -2))
        return card
    }

    /** 标题留空时用正文开头顶上，列表里至少要能认出是哪一句。 */
    private fun label(snippet: Snippet): String {
        val title = snippet.title.trim()
        if (title.isNotEmpty()) return title
        val head = snippet.text.trim().replace('\n', ' ').take(12)
        return if (snippet.text.trim().length > 12) head + "…" else head
    }

    private fun preview(text: String): String {
        val flat = text.trim().replace('\n', ' ')
        return if (flat.length <= 40) flat else flat.take(40) + "…"
    }

    private fun copy(snippet: Snippet) {
        val copied = runCatching {
            (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("话术片段", snippet.text))
        }.onFailure { MoodLog.w("SNIPPET_COPY_FAIL " + it.javaClass.simpleName) }.isSuccess
        Toast.makeText(activity, if (copied) "已复制" else "复制失败，再点一次", Toast.LENGTH_SHORT).show()
        MoodLog.i("SNIPPET_COPY id=" + snippet.id + " ok=" + copied)
    }

    private fun menu(snippet: Snippet) {
        kit.picker("「" + label(snippet) + "」", listOf("编辑", "删除")) { index ->
            if (index == 0) edit(snippet) else confirmDelete(snippet)
        }
    }

    /** 删除要过第二道门：点错「⋯」不至于直接丢掉自己写的话。 */
    private fun confirmDelete(snippet: Snippet) {
        kit.picker("删除「" + label(snippet) + "」？删了就找不回来", listOf("删除", "取消")) { index ->
            if (index == 0) remove(snippet)
        }
    }

    private fun remove(snippet: Snippet) {
        val work = scope ?: return
        work.launch {
            withContext(Dispatchers.IO) { SnippetStore.delete(activity, snippet.id) }
            val latest = withContext(Dispatchers.IO) { SnippetStore.list(activity) }
            items = latest
            render()
            // 删没删掉以重新读出来的结果为准，不信「调用没抛异常就是成功」。
            val gone = latest.none { it.id == snippet.id }
            MoodLog.i("SNIPPET_DELETE id=" + snippet.id + " ok=" + gone)
            if (!gone) Toast.makeText(activity, "没删掉，稍后再试", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 新增/编辑共用的弹层。
     *
     * 正文用自建多行 EditText：PanelKit.input 只有单行，而话术常常要留段落。
     * 标题留空是允许的，列表会用正文开头代替。
     */
    private fun edit(existing: Snippet?) {
        val dialog = kit.sheet()
        val root = kit.root()
        val column = kit.content(root)
        column.addView(kit.heading(if (existing == null) "新增片段" else "编辑片段"))
        column.addView(kit.caption("标题可以不填；内容最多 " + SnippetStore.MAX_TEXT + " 个字").apply {
            setPadding(0, theme.dp(4), 0, 0)
        })
        val titleField = field("标题，例如：婉拒饭局", singleLine = true).apply {
            setText(existing?.title.orEmpty())
        }
        column.addView(titleField, LinearLayout.LayoutParams(-1, -2).apply { topMargin = theme.dp(10) })
        val textField = field("要复制出去的话…", singleLine = false).apply {
            setText(existing?.text.orEmpty())
            gravity = Gravity.TOP
            setHorizontallyScrolling(false)
            minLines = 3
            maxLines = 6
        }
        column.addView(textField, LinearLayout.LayoutParams(-1, -2).apply { topMargin = theme.dp(8) })
        val hint = kit.status("")
        column.addView(hint)
        column.addView(theme.action("保存", primary = true) {
            val text = textField.text.toString().trim()
            when {
                text.isEmpty() -> hint.text = "写一句要复制的话再保存"
                text.length > SnippetStore.MAX_TEXT -> hint.text = "超过 " + SnippetStore.MAX_TEXT + " 个字了，删短一点"
                else -> {
                    // 空 id 交给存储侧生成 UUID，界面不碰 id 规则。
                    val value = Snippet(existing?.id.orEmpty(), titleField.text.toString(), text,
                        System.currentTimeMillis())
                    if (SnippetStore.save(activity, value)) {
                        dialog.dismiss()
                        Toast.makeText(activity, "已保存", Toast.LENGTH_SHORT).show()
                        reload()
                    } else {
                        hint.text = "没保存成功，稍后再试"
                    }
                }
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = theme.dp(10) })
        column.addView(theme.action("取消", quiet = true) { dialog.dismiss() },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = theme.dp(4) })

        dialog.setContentView(root)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        // 键盘弹起来时把输入框顶上来，多行正文别被键盘挡住。
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.show()
        textField.requestFocus()
    }

    /** 输入框样式跟关系档案的手填框一致，只是这里能选单行/多行。 */
    private fun field(hintText: String, singleLine: Boolean): EditText = EditText(activity).apply {
        textSize = ReplyMetrics.TEXT_BODY
        setTextColor(theme.ink)
        setHintTextColor(theme.muted)
        background = theme.shape(theme.card, ReplyMetrics.RADIUS_CARD, theme.border)
        setPadding(theme.dp(12), theme.dp(10), theme.dp(12), theme.dp(10))
        hint = hintText
        minHeight = theme.dp(ReplyMetrics.TAP)
        if (singleLine) {
            maxLines = 1
            setSingleLine(true)
        } else {
            // 输入类型写死，不靠宿主主题推断；多行内部滚动，不把面板撑高。
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
    }
}
