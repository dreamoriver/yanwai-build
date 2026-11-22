package dev.jev.wechatmood.hook

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import dev.jev.wechatmood.core.MoodLog

/** Restores the in-panel mounting from 1a8f1f1; original tiles/listeners are retained. */
internal class ReplyPlusRow(private val activity: Activity, private val createAnalysisControl: () -> View, private val open: () -> Boolean) {
    private var panel: ViewGroup? = null
    private var wrapper: LinearLayout? = null
    private var original: View? = null
    private var originalParams: ViewGroup.LayoutParams? = null

    private var footer: View? = null
    private var observer: ViewTreeObserver? = null
    private var row: View? = null
    private var blockedPanel: ViewGroup? = null
    private var entryView: android.widget.TextView? = null
    private var refreshing = false
    /** 面板还在测量时下一帧再试，最多连续追 30 帧，之后交给普通布局回调。 */
    private var nudge = false
    private var nudgeView: View? = null
    private var nudgeBudget = 0
    private var lastStatus: String? = null
    private var lastRefreshAt = 0L
    private var pendingRefresh: Runnable? = null
    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { requestRefresh() }
    private val attachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = Unit
        override fun onViewDetachedFromWindow(view: View) { clear() }
    }

    fun update(currentFooter: View?) {
        nudgeBudget = 30
        if (footer !== currentFooter) {
            clear()
            footer = currentFooter
            currentFooter?.takeIf { it.isAttachedToWindow }?.let {
                observer = it.viewTreeObserver.also { tree -> tree.addOnGlobalLayoutListener(layoutListener) }
                it.addOnAttachStateChangeListener(attachListener)
            }
        }
        requestRefresh()
    }

    /**
     * 全局布局监听在面板展开动画期间会连续触发，每次都要遍历最多 1500 个节点。
     * 合并成最多 8 次/秒，并把最后一次布局留到延后执行，避免漏掉动画结束状态。
     */
    private fun requestRefresh() {
        val now = android.os.SystemClock.uptimeMillis()
        // 以前 120ms 一档，点开「＋」后第一帧经常被延后；48ms 只多一点遍历，体感是立刻出现。
        val wait = 48 - (now - lastRefreshAt)
        if (wait <= 0) {
            lastRefreshAt = now
            refresh()
            return
        }
        if (pendingRefresh != null) return
        val runnable = Runnable {
            pendingRefresh = null
            lastRefreshAt = android.os.SystemClock.uptimeMillis()
            refresh()
        }
        pendingRefresh = runnable
        footer?.postDelayed(runnable, wait)
    }

    private fun status(value: String) {
        if (lastStatus != value) { lastStatus = value; MoodLog.i("REPLY_PLUS_ROW_$value") }
    }

    /** 入口文案带上这个聊天的状态：有今天到期或过期的写「提醒」，只有以后的写「待办」。 */
    private fun updateEntryLabel() {
        val view = entryView ?: return
        val suffix = RelationBadge.label(activity, MessageSniffer.currentReplyTalker())
        val text = if (suffix == null) "附加" else "附加（" + suffix + "）"
        if (view.text?.toString() != text) view.text = text
    }

    private fun refresh() {
        if (refreshing) return
        refreshing = true
        try { updateEntryLabel(); syncRow() }
        catch (error: Exception) {
            blockedPanel = panel
            runCatching { restoreContent() }
            MoodLog.w("REPLY_PLUS_ROW_FAILED " + error.javaClass.simpleName)
        } finally {
            refreshing = false
            if (nudge) {
                nudge = false
                val view = nudgeView
                if (view != null && nudgeBudget > 0) {
                    nudgeBudget--
                    view.postOnAnimation {
                        lastRefreshAt = 0
                        refresh()
                    }
                }
            }
        }
    }

    private fun syncRow() {
        // 已经挂好且可见时不需要再遍历整棵底部面板。
        val mounted = panel
        if (mounted != null && mounted.isAttachedToWindow && mounted.isShown && wrapper?.parent === mounted) return
        val found = footer?.takeIf { it.isAttachedToWindow && it.isShown }?.let(::descendants)?.filterIsInstance<ViewGroup>()?.singleOrNull {
            it.javaClass.name == "com.tencent.mm.pluginsdk.ui.chat.AppPanel" && it.isShown
        }
        if (found == null) {
            restoreContent(); blockedPanel = null; status("HIDDEN"); return
        }
        if (found === blockedPanel) return
        if (panel === found && wrapper?.parent === found && original?.parent === wrapper && found.childCount == 1) return
        restoreContent()
        // Match the measured host structure, not obfuscated IDs or tile positions.
        val content = found.getChildAt(0) as? LinearLayout
        if (found.childCount != 1 || content == null || descendants(content).none { it.javaClass.name.endsWith(".MMFlipper") }) { status("SKIPPED structure"); return }
        if (found.height < (180 * activity.resources.displayMetrics.density).toInt()) {
            // 面板展开动画期间高度还没测出来：下一帧就再来，别等下一次全局布局。
            status("WAITING panel_measure")
            nudge = true
            nudgeView = found
            return
        }
        val theme = ReplyTheme(activity)
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(theme.dp(8), 0, theme.dp(8), 0)
        }
        // 横条只留一个入口：分析开关和各项功能都收进「附加」弹窗。
        // 用透明底的主色文字，不做主色填充按钮：这一排是微信自己的图标，实心按钮会抢眼。
        val entry = theme.action("附加", quiet = true) {
            // 刚展开/切聊天时 AppPanel 可能还在动画里，isShown 和 isAttachedToWindow 会瞬时为 false。
            // 以前这里直接忽略点击，第一次点「附加」就像没反应；只要还是我们挂的面板就允许打开。
            if (panel === found) {
                runCatching(open).onFailure { MoodLog.w("REPLY_PLUS_OPEN_FAILED " + it.javaClass.simpleName) }
            } else {
                MoodLog.i("REPLY_PLUS_ENTRY_IGNORED shown=${found.isShown} attached=${found.isAttachedToWindow}")
                requestRefresh()
            }
        }.apply {
            textSize = 13f
            minHeight = theme.dp(34)
            setPadding(theme.dp(6), theme.dp(4), theme.dp(6), theme.dp(4))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        row.addView(entry, LinearLayout.LayoutParams(-2, theme.dp(34)))
        entryView = entry
        updateEntryLabel()
        row.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
        val host = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val params = content.layoutParams
        panel = found; original = content; originalParams = params; wrapper = host; this.row = row
        runCatching {
            found.removeView(content)
            host.addView(row, LinearLayout.LayoutParams(-1, theme.dp(48)))
            host.addView(View(activity).apply { setBackgroundColor(theme.border) }, LinearLayout.LayoutParams(-1, theme.dp(1).coerceAtLeast(1)))
            host.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
            // The old root may have WRAP_CONTENT height. A weighted child needs the panel's full bounds.
            found.addView(host, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            status("ATTACHED")
        }.onFailure { blockedPanel = found; restoreContent(); MoodLog.w("REPLY_PLUS_ROW_FAILED ${it.javaClass.simpleName}") }
    }

    fun clear() {
        pendingRefresh?.let { footer?.removeCallbacks(it) }
        pendingRefresh = null
        observer?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(layoutListener)
        observer = null
        footer?.removeOnAttachStateChangeListener(attachListener)
        footer = null; blockedPanel = null; lastStatus = null; entryView = null
        restoreContent()
    }

    private fun restoreContent() {
        row?.setOnClickListener(null)
        val target = panel; val host = wrapper; val content = original
        if (target != null && host != null && content != null &&
            (content.parent === host || content.parent == null) &&
            ((host.parent === target && target.childCount == 1) || target.childCount == 0)) {
            (content.parent as? ViewGroup)?.removeView(content)
            if (host.parent === target) target.removeView(host)
            target.addView(content, originalParams)
        } else if (target != null && host?.parent === target) {
            target.removeView(host)
        }
        panel = null; wrapper = null; original = null; originalParams = null; row = null
    }

    private fun descendants(root: View): List<View> = buildList {
        fun visit(view: View, depth: Int) {
            if (depth > 30 || size >= 1500) return
            add(view)
            if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i), depth + 1)
        }
        visit(root, 0)
    }
}
