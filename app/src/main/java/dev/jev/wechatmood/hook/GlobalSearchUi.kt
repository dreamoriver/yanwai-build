package dev.jev.wechatmood.hook

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import dev.jev.wechatmood.core.MoodLog
import dev.jev.wechatmood.core.RelationFactBridge
import dev.jev.wechatmood.reply.FactTimes
import dev.jev.wechatmood.reply.RelationFact
import dev.jev.wechatmood.reply.SearchHit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 全局搜索：跨所有会话搜关系档案事实与历史纪要。
 *
 * 与档案页里那个搜索框不同，这里不认当前聊天，直接扫整库，用来回答
 * 「我是不是答应过谁什么事」「上次那个群的纪要在哪」。
 * 只读本机档案库：不联网、不调模型，也不改任何条目，所以点一下就是复制而不是跳转。
 */
internal class GlobalSearchUi(private val activity: Activity) {
    private val kit = PanelKit(activity)
    private val theme = kit.theme
    /** 每次打开都用新的 scope：关面板时会取消，复用得重建。 */
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val listContainer = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    /** 当前打开的面板：同一个实例再点一次入口只当成一次，不叠第二层。 */
    private var sheet: Dialog? = null

    /** 状态行放在列表下面：搜索结果的反馈要贴近结果本身，不能只在顶部闪一下。 */
    private val statusText: TextView = kit.status("只搜本机已整理的内容")
    private var hits: List<SearchHit> = emptyList()
    private var query = ""

    /**
     * 防抖令牌：每次输入都 +1，异步回来时对不上就丢掉。
     * 只靠 delay 不够——先发的那次可能在后发之后才拿到库，会把新结果覆盖成旧的。
     */
    private var searchToken = 0
    private var previewDialog: Dialog? = null

    /** 输入即搜：没有搜索按钮，边打边看结果。 */
    private val searchField: EditText = EditText(activity).apply {
        hint = "搜事实或纪要里的词"
        setSingleLine(true)
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, ReplyMetrics.TEXT_BODY)
        setTextColor(theme.ink)
        setHintTextColor(theme.muted)
        // 和档案页的搜索框同款：宿主主题里的 EditText 背景在微信内是错的，这里自己画。
        background = theme.shape(theme.card, ReplyMetrics.RADIUS_CARD, theme.border)
        setPadding(theme.dp(10), theme.dp(8), theme.dp(10), theme.dp(8))
        addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(editable: Editable?) {
                query = editable?.toString().orEmpty()
                schedule()
            }

            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) = Unit
        })
    }

    /** 打开面板即用：调用方只需要 new 一个再 [show]。 */
    fun show() {
        // 已经开着就不再叠一层，重复点入口只当成一次（和 SnippetUi 一致）。
        // 而且 searchField/statusText 是成员字段：同一个实例第二次 addView 到新 root，
        // 会因为「View 已有 parent」抛 IllegalStateException。
        if (sheet?.isShowing == true) return
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val dialog = kit.sheet()
        val root = kit.root()
        val column = kit.content(root)
        column.addView(kit.heading("搜档案与纪要"))
        column.addView(kit.caption("跨所有会话，搜整理出来的事实和历史纪要；只读本机，不联网").apply {
            setPadding(0, theme.dp(4), 0, theme.dp(8))
        })
        column.addView(searchField, LinearLayout.LayoutParams(-1, -2).apply { topMargin = theme.dp(4) })

        val scroll = PanelMaxScroll(activity, (activity.resources.displayMetrics.heightPixels * 0.55f).toInt()).apply {
            addView(listContainer)
        }
        column.addView(scroll, LinearLayout.LayoutParams(-1, -2).apply { topMargin = theme.dp(6) })
        column.addView(statusText)
        column.addView(theme.action("关闭", primary = true) { dialog.dismiss() },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = theme.dp(8) })

        dialog.setContentView(root)
        dialog.setOnDismissListener {
            // 长按预览是独立窗口，主面板关掉时它必须一起走，否则会留下一个孤立的弹层。
            previewDialog?.dismiss()
            // 关掉时把成员 View 从旧 root 里摘出来，复用同一实例再打开一次才不会报「已有 parent」。
            listOfNotNull(searchField, statusText, listContainer).forEach { view ->
                (view.parent as? ViewGroup)?.removeView(view)
            }
            if (sheet === dialog) {
                sheet = null
                scope.cancel()
            }
        }
        // show 之前就定好宽度：放到 onShow 里设置会先按内容宽度露半屏，再跳成全宽。
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        sheet = dialog
        dialog.show()
        searchField.requestFocus()
        renderIdle()
        MoodLog.i("GLOBAL_SEARCH_OPEN")
    }

    /**
     * 输入变化后 300ms 才真正查库。
     *
     * 逐字查等于每敲一下开一次 SQLite 扫描，中文输入法还会先送拼音串，
     * 结果就是满屏乱闪；停手再查一次就够。
     */
    private fun schedule() {
        val token = ++searchToken
        val keyword = query.trim()
        // 清空输入框不是「搜空串」：回到引导态，别把整库都列出来。
        if (keyword.isEmpty()) {
            hits = emptyList()
            renderIdle()
            return
        }
        renderLoading()
        scope.launch {
            delay(DEBOUNCE_MS)
            if (token != searchToken) return@launch
            val loaded = withContext(Dispatchers.IO) { RelationFactBridge.searchOrNull(activity, keyword) }
            if (token != searchToken) return@launch
            // 只记关键词长度和条数：搜索词本身是用户隐私，日志会被导出。
            MoodLog.i("GLOBAL_SEARCH len=" + keyword.length + " hits=" + (loaded?.size ?: -1))
            if (loaded == null) {
                hits = emptyList()
                renderFailure()
                return@launch
            }
            hits = loaded
            render()
        }
    }

    private fun renderIdle() {
        listContainer.removeAllViews()
        listContainer.addView(kit.body("输入关键词开始搜索"))
        listContainer.addView(kit.caption("搜的是已经整理出来的短句和 AI 纪要；没整理过的聊天里搜不到"))
        showStatus("只搜本机已整理的内容", PanelTone.NEUTRAL)
    }

    private fun renderLoading() {
        listContainer.removeAllViews()
        listContainer.addView(kit.body("正在搜索…"))
        showStatus("正在搜索…", PanelTone.NEUTRAL)
    }

    /** 读失败和「真没有」是两件事：库异常时不能说成空态，也不能让用户以为是自己没记过。 */
    private fun renderFailure() {
        listContainer.removeAllViews()
        listContainer.addView(kit.body("档案库暂时读不出来"))
        listContainer.addView(kit.caption("稍后重试；如果一直这样，切回聊天再打开这个面板"))
        showStatus("读取失败，稍后重试", PanelTone.ERROR)
    }

    private fun render() {
        listContainer.removeAllViews()
        if (hits.isEmpty()) {
            listContainer.addView(kit.body("没有搜到「" + query.trim() + "」"))
            listContainer.addView(kit.caption("换一个词试试；也可以先回那个聊天里整理一次档案"))
            showStatus("没搜到", PanelTone.WARNING)
            return
        }
        showStatus("找到 " + hits.size + " 条 · 点一下复制，长按看全文", PanelTone.SUCCESS)
        hits.forEach { hit ->
            listContainer.addView(hitRow(hit), LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = theme.dp(8)
            })
        }
    }

    private fun hitRow(hit: SearchHit): View {
        val card = kit.card()
        card.addView(theme.label(nameOf(hit), ReplyMetrics.TEXT_ITEM, theme.ink, bold = true))
        card.addView(theme.label(metaOf(hit), ReplyMetrics.TEXT_CAPTION, theme.accent).apply {
            setPadding(0, theme.dp(2), 0, 0)
        })
        card.addView(theme.label(previewOf(hit), ReplyMetrics.TEXT_BODY, theme.ink).apply {
            setPadding(0, theme.dp(4), 0, 0)
        })
        card.minimumHeight = theme.dp(ReplyMetrics.TAP)
        card.isClickable = true
        card.isFocusable = true
        card.isLongClickable = true
        card.contentDescription = nameOf(hit) + "，" + metaOf(hit) + "，点按复制，长按看全文"
        // 短按只复制不跳转：搜索结果可能来自几个月前的聊天，跳过去反而要重新找上下文。
        card.setOnClickListener { copy(hit) }
        card.setOnLongClickListener {
            preview(hit)
            true
        }
        return card
    }

    /** 目录表里没记过名字的旧数据只剩哈希键，不能显示成空白行。 */
    private fun nameOf(hit: SearchHit): String = hit.contactName.trim().ifEmpty { "未知会话" }

    /** 日期 + 类型：类型走统一的中文名，纪要固定显示「纪要」。 */
    private fun metaOf(hit: SearchHit): String {
        val day = if (hit.at > 0) FactTimes.formatLocal(hit.at, "MM-dd") else "时间未知"
        return day + " · " + RelationFact.kindLabel(hit.kind)
    }

    /**
     * 列表里的正文只给前 60 字：一条纪要可能有好几段，全铺开会把列表挤成一屏一条。
     * 换行先压成空格，否则高度算不准、一条就能顶掉半屏。
     */
    private fun previewOf(hit: SearchHit): String {
        val flat = hit.text.replace('\n', ' ').replace('\r', ' ')
        return if (flat.length > PREVIEW_CHARS) flat.take(PREVIEW_CHARS) + "…" else flat
    }

    private fun copy(hit: SearchHit) {
        (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("关系档案", hit.text))
        showStatus("已复制", PanelTone.SUCCESS)
    }

    /** 长按看全文：正文可选中，弹层里还能再复制一次，避免长纪要看不全。 */
    private fun preview(hit: SearchHit) {
        val sheet = kit.sheet()
        val root = kit.root()
        val column = kit.content(root)
        column.addView(kit.heading(nameOf(hit)))
        column.addView(kit.caption(metaOf(hit)).apply { setPadding(0, theme.dp(4), 0, theme.dp(8)) })
        val scroll = PanelMaxScroll(activity, (activity.resources.displayMetrics.heightPixels * 0.45f).toInt()).apply {
            addView(kit.body(hit.text).apply { setTextIsSelectable(true) })
        }
        column.addView(scroll, LinearLayout.LayoutParams(-1, -2))
        column.addView(theme.action("复制", primary = true) {
            copy(hit)
            sheet.dismiss()
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = theme.dp(10) })
        column.addView(theme.action("关闭", quiet = true) { sheet.dismiss() },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = theme.dp(8) })
        sheet.setContentView(root)
        // 记住这一层：主面板被关掉或界面销毁时不能留一个没有父窗口的弹层。
        sheet.setOnDismissListener { if (previewDialog === sheet) previewDialog = null }
        sheet.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        previewDialog = sheet
        sheet.show()
    }

    /** 状态行只有一行，换文案时颜色也要跟着语义走，不能让失败看起来像一条普通提示。 */
    private fun showStatus(text: String, tone: PanelTone) {
        statusText.text = text
        statusText.setTextColor(when (tone) {
            PanelTone.NEUTRAL -> theme.muted
            PanelTone.SUCCESS -> theme.success
            PanelTone.WARNING -> theme.warning
            PanelTone.ERROR -> theme.error
        })
    }

    private companion object {
        /** 打字停顿多久才查库；再短会在中文输入法候选阶段反复查询。 */
        const val DEBOUNCE_MS = 300L

        /** 列表里每条最多露多少字，多出来的靠长按看全文。 */
        const val PREVIEW_CHARS = 60
    }
}
